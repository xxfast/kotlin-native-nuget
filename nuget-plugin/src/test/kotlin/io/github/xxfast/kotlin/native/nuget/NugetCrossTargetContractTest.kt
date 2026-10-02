package io.github.xxfast.kotlin.native.nuget

import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NugetCrossTargetContractTest {
  private fun pack(abi: String = "f(in int) -> int", source: String = "public enum Mode { A = 0 }"): PackNugetTask {
    val project = ProjectBuilder.builder().build()
    val task = project.tasks.create("packNuget", PackNugetTask::class.java)
    task.packageId.set("TestLibrary")
    task.packageVersion.set("1.0.0")
    task.authors.set("Test")
    task.packageDescription.set("Test")
    task.nativeLibDirs.set(emptyMap())
    task.dependencyVersions.set(emptyMap())
    task.outputDir.set(Files.createTempDirectory("contract-output").toFile())
    val root = Files.createTempDirectory("contract-input").toFile()
    listOf("win-x64", "win-arm64").forEach { rid ->
      File(root, "$rid/native").mkdirs()
      File(root, "$rid/native/kn_746573746c696272617279.dll").writeText("binary")
      File(root, "$rid/ForwardAbi.json").writeText("""{"schemaVersion":1,"abi":["${if (rid == "win-x64") abi else "f(in int) -> int"}"]}""")
      File(root, "$rid/Interop.cs").writeText(if (rid == "win-x64") source else "public enum Mode { A = 0 }")
    }
    task.prebuiltRuntimesDir.set(root)
    return task
  }
  @Test fun `native signature drift fails before packing`() {
    val error = assertFailsWith<IllegalArgumentException> { pack("f(in long) -> int").pack() }
    assertTrue(error.message.orEmpty().contains("f("))
    assertTrue(error.message.orEmpty().contains("win-x64"))
  }
  @Test fun `managed only enum drift fails before packing`() {
    val error = assertFailsWith<IllegalArgumentException> { pack(source = "public enum Mode { A = 1 }").pack() }
    assertTrue(error.message.orEmpty().contains("Mode"))
  }
  @Test fun `missing producer manifest fails with migration guidance`() {
    val task = pack()
    File(task.prebuiltRuntimesDir.get().asFile, "win-x64/ForwardAbi.json").delete()
    val error = assertFailsWith<IllegalArgumentException> { task.pack() }
    assertTrue(error.message.orEmpty().contains("ForwardAbi.json"))
  }
  @Test fun `matching unknown producer and source trivia pack with sidecars`() {
    val task = pack(source = "// comment\n public enum Mode /* comment */ { A=0 }")
    task.pack()
    assertTrue(File(task.outputDir.get().asFile, "TestLibrary.1.0.0/runtimes/win-arm64/ForwardAbi.json").isFile)
    assertTrue(File(task.outputDir.get().asFile, "TestLibrary.1.0.0/contentFiles/cs/net10.0/Interop.cs").isFile)
  }
  @Test fun `missing extra symbols and constant drift are actionable`() {
    listOf("g(in int) -> int", "f(in int, out pointer) -> int").forEach { abi ->
      val error = assertFailsWith<IllegalArgumentException> { pack(abi).pack() }
      assertTrue(error.message.orEmpty().contains("ABI differences"))
      assertTrue(error.message.orEmpty().contains("win-arm64"))
    }
    val task = pack(source = "public const int Count = 2;")
    File(task.prebuiltRuntimesDir.get().asFile, "win-arm64/Interop.cs").writeText("public const int Count = 1;")
    val error = assertFailsWith<IllegalArgumentException> { task.pack() }
    assertTrue(error.message.orEmpty().contains("Count"))
  }
  @Test fun `malformed unknown and noncanonical manifests fail even for single producer`() {
    listOf("{", "broken", "{}", "{\"schemaVersion\":2,\"abi\":[]}", "{\"schemaVersion\":1,\"abi\":[4]}",
      "{\"schemaVersion\":1,\"abi\":[\"not a signature\"]}",
      "{\"schemaVersion\":1,\"abi\":[\"z() -> void\",\"a() -> void\"]}").forEach { json ->
      val task = pack()
      File(task.prebuiltRuntimesDir.get().asFile, "win-arm64").deleteRecursively()
      File(task.prebuiltRuntimesDir.get().asFile, "win-x64/ForwardAbi.json").writeText(json)
      val error = assertFailsWith<IllegalArgumentException> { task.pack() }
      assertTrue(error.message.orEmpty().contains("win-x64"), error.message)
      assertTrue(error.message.orEmpty().contains("ForwardAbi.json"), error.message)
    }
  }
  @Test fun `missing original producer source is never replaced with local source`() {
    val task = pack()
    File(task.prebuiltRuntimesDir.get().asFile, "win-x64/Interop.cs").delete()
    val error = assertFailsWith<IllegalArgumentException> { task.pack() }
    assertTrue(error.message.orEmpty().contains("Interop.cs"))
    assertTrue(error.message.orEmpty().contains("original producer"))
  }
  @Test fun `all enabled local producers compared before any package output`() {
    val task = pack()
    val root = task.prebuiltRuntimesDir.get().asFile
    task.nativeLibDirs.set(mapOf("win-x64" to File(root, "win-x64/native").path))
    task.localContractDirs.set(mapOf("win-x64" to File(root, "win-x64").path))
    val prebuilt = Files.createTempDirectory("other-producer").toFile()
    File(root, "win-arm64").copyRecursively(File(prebuilt, "win-arm64"))
    task.prebuiltRuntimesDir.set(prebuilt)
    File(prebuilt, "win-arm64/Interop.cs").writeText("public enum Mode { A = 99 }")
    assertFailsWith<IllegalArgumentException> { task.pack() }
    assertTrue(!File(task.outputDir.get().asFile, "TestLibrary.1.0.0").exists())
  }

  @Test fun `two local producers match and native drift then fails`() {
    val task = pack()
    val root = task.prebuiltRuntimesDir.get().asFile
    task.prebuiltRuntimesDir.unset()
    task.nativeLibDirs.set(listOf("win-x64", "win-arm64").associateWith { File(root, "$it/native").path })
    task.localContractDirs.set(listOf("win-x64", "win-arm64").associateWith { File(root, it).path })
    task.pack()
    assertTrue(File(task.outputDir.get().asFile, "TestLibrary.1.0.0/runtimes/win-x64/Interop.cs").isFile)
    File(root, "win-x64/ForwardAbi.json").writeText("{\"schemaVersion\":1,\"abi\":[\"f(in long) -> int\"]}")
    assertFailsWith<IllegalArgumentException> { task.pack() }
  }
  @Test fun `literal changes fail with exact managed context`() {
    val task = pack(source = "public const string Text = \"a // b\";")
    File(task.prebuiltRuntimesDir.get().asFile, "win-arm64/Interop.cs").writeText("public const string Text = \"a // c\";")
    val error = assertFailsWith<IllegalArgumentException> { task.pack() }
    assertTrue(error.message.orEmpty().contains("a // b"))
    assertTrue(error.message.orEmpty().contains("a // c"))
  }

  @Test fun `unicode producer symbols are preserved without imposing new identifier policy`() {
    val task = pack()
    listOf("win-x64", "win-arm64").forEach { rid ->
      File(task.prebuiltRuntimesDir.get().asFile, "$rid/ForwardAbi.json")
        .writeText("{\"schemaVersion\":1,\"abi\":[\"library_écho(in int) -> int\"]}")
    }
    task.pack()
  }
}
