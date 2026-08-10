package io.github.xxfast.kotlin.native.nuget

import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-093: one package carries native libs for more RIDs than the packing host can link, fed by
 * `publish { prebuiltRuntimes = ... }` pointing at a `rid/native` tree of dll, dylib or so files
 * staged by another CI host, plus fail-fast validation replacing the silent skip.
 */
class PackNugetMultiRidTest {
  private fun newTask(): PackNugetTask {
    val project = ProjectBuilder.builder().build()
    return project.tasks.create("packNuget", PackNugetTask::class.java)
  }

  private fun configureCommon(task: PackNugetTask, outputDir: File) {
    task.packageId.set("TestLibrary")
    task.packageVersion.set("1.0.0")
    task.authors.set("Test Author")
    task.packageDescription.set("Test description")
    task.nativeLibDirs.set(emptyMap())
    task.dependencyVersions.set(emptyMap())
    task.outputDir.set(outputDir)
  }

  private fun nativeDir(name: String, libName: String): File {
    val dir: File = Files.createTempDirectory(name).toFile()
    File(dir, libName).writeText("fake native binary")
    return dir
  }

  private fun prebuiltTree(rid: String, libName: String): File {
    val root: File = Files.createTempDirectory("prebuilt").toFile()
    val native = File(root, "$rid/native")
    native.mkdirs()
    File(native, libName).writeText("fake native binary")
    return root
  }

  @Test
  fun `pack merges locally linked and prebuilt rids into one package`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()

    configureCommon(task, outputDir)
    task.nativeLibDirs.set(mapOf("osx-arm64" to nativeDir("local", "libtest.dylib").path))
    task.prebuiltRuntimesDir.set(prebuiltTree("win-x64", "test.dll"))

    task.pack()

    val staged = File(outputDir, "TestLibrary.1.0.0")
    assertTrue(
      File(staged, "runtimes/osx-arm64/native/libtest.dylib").exists(),
      "the locally linked RID must be staged",
    )
    assertTrue(
      File(staged, "runtimes/win-x64/native/test.dll").exists(),
      "the prebuilt RID must be staged alongside it",
    )

    val entries: List<String> = ZipFile(File(outputDir, "TestLibrary.1.0.0.nupkg")).use { zip ->
      zip.entries().toList().map { it.name }
    }
    assertTrue(
      entries.contains("runtimes/osx-arm64/native/libtest.dylib"),
      "the nupkg must carry the local RID, was $entries",
    )
    assertTrue(
      entries.contains("runtimes/win-x64/native/test.dll"),
      "the nupkg must carry the prebuilt RID, was $entries",
    )
  }

  @Test
  fun `pack fails when a locally linked rid contributes no native library`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    val empty: File = Files.createTempDirectory("empty-local").toFile()

    configureCommon(task, outputDir)
    task.nativeLibDirs.set(mapOf("osx-arm64" to empty.path))

    val error = assertFailsWith<IllegalStateException> { task.pack() }

    assertTrue(error.message.orEmpty().contains("osx-arm64"), "must name the RID: ${error.message}")
    assertTrue(
      error.message.orEmpty().contains(empty.absolutePath),
      "must name the scanned path: ${error.message}",
    )
  }

  @Test
  fun `pack fails when a locally linked rid path does not exist`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()

    configureCommon(task, outputDir)
    task.nativeLibDirs.set(mapOf("osx-arm64" to "/nonexistent/path/xyz"))

    val error = assertFailsWith<IllegalStateException> { task.pack() }

    assertTrue(error.message.orEmpty().contains("osx-arm64"), "must name the RID: ${error.message}")
  }

  @Test
  fun `pack fails when the prebuilt tree has no rid subdirectory`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    val root: File = Files.createTempDirectory("prebuilt").toFile()
    File(root, ".DS_Store").writeText("junk")

    configureCommon(task, outputDir)
    task.prebuiltRuntimesDir.set(root)

    val error = assertFailsWith<IllegalArgumentException> { task.pack() }

    assertTrue(
      error.message.orEmpty().contains(root.absolutePath),
      "must name the declared directory: ${error.message}",
    )
    assertTrue(
      error.message.orEmpty().contains("native"),
      "must print the expected layout: ${error.message}",
    )
  }

  @Test
  fun `pack fails when a prebuilt rid has no native directory`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    val root: File = Files.createTempDirectory("prebuilt").toFile()
    File(root, "win-x64").mkdirs()

    configureCommon(task, outputDir)
    task.prebuiltRuntimesDir.set(root)

    val error = assertFailsWith<IllegalArgumentException> { task.pack() }

    assertTrue(error.message.orEmpty().contains("win-x64"), "must name the RID: ${error.message}")
    assertTrue(
      error.message.orEmpty().contains("native"),
      "must print the expected layout: ${error.message}",
    )
  }

  @Test
  fun `pack fails when a prebuilt rid native directory is empty`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    val root: File = Files.createTempDirectory("prebuilt").toFile()
    File(root, "win-x64/native").mkdirs()

    configureCommon(task, outputDir)
    task.prebuiltRuntimesDir.set(root)

    val error = assertFailsWith<IllegalArgumentException> { task.pack() }

    assertTrue(error.message.orEmpty().contains("win-x64"), "must name the RID: ${error.message}")
  }

  @Test
  fun `pack fails when the same rid is both locally linked and prebuilt`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    val local: File = nativeDir("local", "test.dll")

    configureCommon(task, outputDir)
    task.nativeLibDirs.set(mapOf("win-x64" to local.path))
    task.prebuiltRuntimesDir.set(prebuiltTree("win-x64", "test.dll"))

    val error = assertFailsWith<IllegalArgumentException> { task.pack() }

    assertTrue(error.message.orEmpty().contains("win-x64"), "must name the RID: ${error.message}")
    assertTrue(
      error.message.orEmpty().contains(local.absolutePath),
      "must name both sources: ${error.message}",
    )
  }

  @Test
  fun `pack ignores non-directory entries at the top of the prebuilt tree`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    val root: File = prebuiltTree("win-x64", "test.dll")
    File(root, ".DS_Store").writeText("junk")

    configureCommon(task, outputDir)
    task.prebuiltRuntimesDir.set(root)

    task.pack()

    assertTrue(
      File(outputDir, "TestLibrary.1.0.0/runtimes/win-x64/native/test.dll").exists(),
      "a stray file next to the RID directories must not stop the pack",
    )
  }

  @Test
  fun `pack stages an unknown prebuilt rid instead of failing`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()

    configureCommon(task, outputDir)
    task.prebuiltRuntimesDir.set(prebuiltTree("win-arm64", "test.dll"))

    task.pack()

    assertTrue(
      File(outputDir, "TestLibrary.1.0.0/runtimes/win-arm64/native/test.dll").exists(),
      "a RID this plugin version cannot build is a warning, not an error",
    )
  }

  @Test
  fun `pack filters non-native files out of a prebuilt rid`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    val root: File = prebuiltTree("win-x64", "test.dll")
    File(root, "win-x64/native/test.pdb").writeText("fake debug symbols")

    configureCommon(task, outputDir)
    task.prebuiltRuntimesDir.set(root)

    task.pack()

    val native = File(outputDir, "TestLibrary.1.0.0/runtimes/win-x64/native")
    assertTrue(File(native, "test.dll").exists())
    assertTrue(!File(native, "test.pdb").exists(), "debug symbols must not be packed")
  }
}
