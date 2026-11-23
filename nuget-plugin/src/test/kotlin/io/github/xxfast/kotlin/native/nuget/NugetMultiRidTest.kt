package io.github.xxfast.kotlin.native.nuget

import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-093: one package carries native libs for more RIDs than the packing host can link, fed by
 * `publish { prebuiltRuntimes = ... }` pointing at a `rid/native` tree of dll, dylib or so files
 * staged by another CI host, plus fail-fast validation replacing the silent skip.
 */
class NugetMultiRidTest {
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
    writeProducerContract(dir)
    return dir
  }

  private fun prebuiltTree(rid: String, libName: String): File {
    val root: File = Files.createTempDirectory("prebuilt").toFile()
    val native = File(root, "$rid/native")
    native.mkdirs()
    File(native, libName).writeText("fake native binary")
    writeProducerContract(native.parentFile)
    return root
  }

  @Test
  fun `pack merges locally linked and prebuilt rids into one package`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()

    configureCommon(task, outputDir)
    val local = nativeDir("local", "libkn_testlibrary.dylib")
    task.nativeLibDirs.set(mapOf("osx-arm64" to local.path))
    task.localContractDirs.set(mapOf("osx-arm64" to local.path))
    task.prebuiltRuntimesDir.set(prebuiltTree("win-x64", "kn_testlibrary.dll"))

    task.pack()

    val staged = File(outputDir, "TestLibrary.1.0.0")
    assertTrue(
      File(staged, "runtimes/osx-arm64/native/libkn_testlibrary.dylib").exists(),
      "the locally linked RID must be staged",
    )
    assertTrue(
      File(staged, "runtimes/win-x64/native/kn_testlibrary.dll").exists(),
      "the prebuilt RID must be staged alongside it",
    )

    val entries: List<String> = ZipFile(File(outputDir, "TestLibrary.1.0.0.nupkg")).use { zip ->
      zip.entries().toList().map { it.name }
    }
    assertTrue(
      entries.contains("runtimes/osx-arm64/native/libkn_testlibrary.dylib"),
      "the nupkg must carry the local RID, was $entries",
    )
    assertTrue(
      entries.contains("runtimes/win-x64/native/kn_testlibrary.dll"),
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
    val local: File = nativeDir("local", "kn_testlibrary.dll")

    configureCommon(task, outputDir)
    task.nativeLibDirs.set(mapOf("win-x64" to local.path))
    task.localContractDirs.set(mapOf("win-x64" to local.path))
    task.prebuiltRuntimesDir.set(prebuiltTree("win-x64", "kn_testlibrary.dll"))

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
    val root: File = prebuiltTree("win-x64", "kn_testlibrary.dll")
    File(root, ".DS_Store").writeText("junk")

    configureCommon(task, outputDir)
    task.prebuiltRuntimesDir.set(root)

    task.pack()

    assertTrue(
      File(outputDir, "TestLibrary.1.0.0/runtimes/win-x64/native/kn_testlibrary.dll").exists(),
      "a stray file next to the RID directories must not stop the pack",
    )
  }

  @Test
  fun `pack stages an unknown prebuilt rid instead of failing`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()

    configureCommon(task, outputDir)
    task.prebuiltRuntimesDir.set(prebuiltTree("win-arm64", "kn_testlibrary.dll"))

    task.pack()

    assertTrue(
      File(outputDir, "TestLibrary.1.0.0/runtimes/win-arm64/native/kn_testlibrary.dll").exists(),
      "a RID this plugin version cannot build is a warning, not an error",
    )
  }

  @Test
  fun `pack filters non-native files out of a prebuilt rid`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    val root: File = prebuiltTree("win-x64", "kn_testlibrary.dll")
    File(root, "win-x64/native/test.pdb").writeText("fake debug symbols")

    configureCommon(task, outputDir)
    task.prebuiltRuntimesDir.set(root)

    task.pack()

    val native = File(outputDir, "TestLibrary.1.0.0/runtimes/win-x64/native")
    assertTrue(File(native, "kn_testlibrary.dll").exists())
    assertTrue(!File(native, "test.pdb").exists(), "debug symbols must not be packed")
  }

  private fun nupkgEntries(outputDir: File): List<String> =
    ZipFile(File(outputDir, "TestLibrary.1.0.0.nupkg")).use { zip ->
      zip.entries().toList().map { it.name }
    }

  // #618: one local and one prebuilt RID, the mix that used to put both producers' contract
  // sidecars into the published package.
  private fun packLocalAndPrebuilt(outputDir: File): PackNugetTask {
    val task: PackNugetTask = newTask()
    configureCommon(task, outputDir)
    val local: File = nativeDir("local", "libkn_testlibrary.dylib")
    File(local, "libold.dylib").writeText("leftover from an old baseName")
    task.nativeLibDirs.set(mapOf("osx-arm64" to local.path))
    task.localContractDirs.set(mapOf("osx-arm64" to local.path))
    task.prebuiltRuntimesDir.set(prebuiltTree("win-x64", "kn_testlibrary.dll"))
    return task
  }

  private fun assertTransferable(tree: File, rid: String, lib: String) {
    assertEquals(
      listOf(lib),
      File(tree, "$rid/native").listFiles()?.map { it.name }.orEmpty(),
      "$rid/native must hold exactly the primary library in $tree",
    )
    assertTrue(File(tree, "$rid/ForwardAbi.json").isFile, "$rid/ForwardAbi.json missing in $tree")
    assertTrue(File(tree, "$rid/Interop.cs").isFile, "$rid/Interop.cs missing in $tree")
  }

  @Test
  fun `the nupkg ships only native libraries under runtimes`() {
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()

    packLocalAndPrebuilt(outputDir).pack()

    val entries: List<String> = nupkgEntries(outputDir)
    val runtimeNative = Regex("runtimes/[^/]+/native/[^/]+")
    val nonNative: List<String> =
      entries.filter { it.startsWith("runtimes/") && !runtimeNative.matches(it) }
    assertTrue(nonNative.isEmpty(), "runtimes/ must hold only <rid>/native/, found $nonNative")
    val manifests: List<String> = entries.filter { it.substringAfterLast('/') == "ForwardAbi.json" }
    assertTrue(manifests.isEmpty(), "the nupkg must not ship ForwardAbi.json, found $manifests")
    val sources: List<String> = entries
      .filter { it.substringAfterLast('/') == "Interop.cs" && !it.startsWith("contentFiles/") }
    assertTrue(sources.isEmpty(), "Interop.cs belongs only under contentFiles/, found $sources")
    val shipsMac: Boolean = entries.contains("runtimes/osx-arm64/native/libkn_testlibrary.dylib")
    val shipsWindows: Boolean = entries.contains("runtimes/win-x64/native/kn_testlibrary.dll")
    assertTrue(shipsMac && shipsWindows, "both RIDs must still ship their native library: $entries")
  }

  @Test
  fun `pack writes a transferable prebuilt runtimes tree for every producer`() {
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()

    packLocalAndPrebuilt(outputDir).pack()

    val tree = File(outputDir, "prebuilt-runtimes")
    assertTransferable(tree, "osx-arm64", "libkn_testlibrary.dylib")
    assertTransferable(tree, "win-x64", "kn_testlibrary.dll")
  }

  @Test
  fun `a second host chains the first host's prebuilt runtimes tree`() {
    val firstOut: File = Files.createTempDirectory("pack-out-a").toFile()
    packLocalAndPrebuilt(firstOut).pack()
    val firstTree = File(firstOut, "prebuilt-runtimes")
    assertTransferable(firstTree, "osx-arm64", "libkn_testlibrary.dylib")
    assertTransferable(firstTree, "win-x64", "kn_testlibrary.dll")

    val second: PackNugetTask = newTask()
    val secondOut: File = Files.createTempDirectory("pack-out-b").toFile()
    configureCommon(second, secondOut)
    val local: File = nativeDir("local-b", "kn_testlibrary.dll")
    second.nativeLibDirs.set(mapOf("win-arm64" to local.path))
    second.localContractDirs.set(mapOf("win-arm64" to local.path))
    second.prebuiltRuntimesDir.set(firstTree)

    second.pack()

    val secondTree = File(secondOut, "prebuilt-runtimes")
    assertEquals(
      listOf("osx-arm64", "win-arm64", "win-x64"),
      secondTree.listFiles()?.map { it.name }?.sorted(),
    )
    assertTransferable(secondTree, "osx-arm64", "libkn_testlibrary.dylib")
    assertTransferable(secondTree, "win-arm64", "kn_testlibrary.dll")
    assertTransferable(secondTree, "win-x64", "kn_testlibrary.dll")
    val sidecars: List<String> = nupkgEntries(secondOut)
      .filter { it.startsWith("runtimes/") }
      .filter { it.endsWith("/ForwardAbi.json") || it.endsWith("/Interop.cs") }
    assertTrue(sidecars.isEmpty(), "the chained nupkg must not ship sidecars, found $sidecars")
  }

  @Test
  fun `a rid dropped from a later pack does not linger in the prebuilt runtimes tree`() {
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    val task: PackNugetTask = packLocalAndPrebuilt(outputDir)
    task.pack()
    val tree = File(outputDir, "prebuilt-runtimes")
    assertTrue(File(tree, "win-x64").isDirectory, "the first pack must write win-x64 to $tree")

    task.prebuiltRuntimesDir.unset()
    task.pack()

    assertEquals(listOf("osx-arm64"), tree.listFiles()?.map { it.name })
  }
}
