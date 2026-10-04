package io.github.xxfast.kotlin.native.nuget

import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task-action tests for `PackNugetTask` (ADR-050 pieces 1 and 2).
 *
 * Piece 1: `generatedCsDir: DirectoryProperty` becomes `generatedCsDirs: ConfigurableFileCollection`
 * so `packNuget` can merge `.cs` files from multiple producers (KSP's forward `Interop.cs` and
 * `nugetGenerateShims`'s reverse registration shims) into one `contentFiles/cs/net10.0/` folder.
 *
 * Piece 2: `dependencyVersions: MapProperty<String, String>` drives a `.nuspec` `<dependencies>`
 * block pinning each bound package at its exact resolved version (nuspec exact-version range
 * syntax, e.g. `version="[4.0.0]"`).
 */
class PackNugetTaskTest {
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
    task.outputDir.set(outputDir)
  }

  @Test
  fun `pack merges cs files from multiple generatedCsDirs into contentFiles cs net10 0`() {
    val task: PackNugetTask = newTask()

    val kspDir: File = Files.createTempDirectory("ksp-cs").toFile()
    File(kspDir, "Interop.cs").writeText("// forward bindings\nnamespace Sample { }\n")

    val shimDir: File = Files.createTempDirectory("shim-cs").toFile()
    File(shimDir, "FooRegistration.cs").writeText("// reverse shim\nnamespace Acme.Lib { }\n")

    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    configureCommon(task, outputDir)
    task.generatedCsDirs.from(kspDir, shimDir)
    task.dependencyVersions.set(emptyMap())

    task.pack()

    val contentDir = File(outputDir, "TestLibrary.1.0.0/contentFiles/cs/net10.0")
    assertTrue(
      File(contentDir, "Interop.cs").exists(),
      "Interop.cs from the first generatedCsDirs entry must be copied into " +
        "contentFiles/cs/net10.0/",
    )
    assertTrue(
      File(contentDir, "FooRegistration.cs").exists(),
      "FooRegistration.cs from the second generatedCsDirs entry must be copied into " +
          "contentFiles/cs/net10.0/",
    )

    val nuspec: String = File(outputDir, "TestLibrary.1.0.0/TestLibrary.nuspec").readText()
    assertContains(
      nuspec,
      """<file src="contentFiles/cs/net10.0/Interop.cs" """ +
        """target="contentFiles/cs/net10.0/Interop.cs" />""",
    )
    assertContains(
      nuspec,
      """<file src="contentFiles/cs/net10.0/FooRegistration.cs" """ +
          """target="contentFiles/cs/net10.0/FooRegistration.cs" />""",
    )
    assertContains(
      nuspec,
      """<files include="cs/net10.0/**/*.cs" buildAction="Compile" />""",
      message = "the contentFiles include glob must cover both merged files",
    )
  }

  // ADR-184: cs/<tfm> plus an empty lib/<tfm>/_._ is the layout that makes NuGet answer a lower
  // consumer with NU1202; either alone is silent (the memo's spike, variants Q and R).
  @Test
  fun `pack scopes contentFiles and an empty lib placeholder to the configured framework`() {
    val task: PackNugetTask = newTask()

    val csDir: File = Files.createTempDirectory("ksp-cs").toFile()
    File(csDir, "Interop.cs").writeText("// forward bindings\n")

    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    configureCommon(task, outputDir)
    task.targetFramework.set("net11.0")
    task.generatedCsDirs.from(csDir)
    task.dependencyVersions.set(emptyMap())

    task.pack()

    val root = File(outputDir, "TestLibrary.1.0.0")
    assertTrue(File(root, "contentFiles/cs/net11.0/Interop.cs").exists())
    assertFalse(File(root, "contentFiles/cs/any").exists())
    val placeholder = File(root, "lib/net11.0/_._")
    assertTrue(placeholder.exists(), "lib/net11.0/_._ must exist")
    assertTrue(placeholder.length() == 0L, "the placeholder must be empty")
    // A root build/ asset makes the package compatible with every TFM and suppresses NU1202.
    assertTrue(File(root, "build/net11.0/TestLibrary.targets").exists())
    assertFalse(File(root, "build/TestLibrary.targets").exists())

    val nuspec: String = File(root, "TestLibrary.nuspec").readText()
    assertContains(nuspec, """<files include="cs/net11.0/**/*.cs" buildAction="Compile" />""")
    assertContains(nuspec, """<group targetFramework="net11.0">""")
    assertContains(nuspec, """<file src="lib/net11.0/_._" target="lib/net11.0/_._" />""")
  }

  @Test
  fun `pack nuspec includes dependencies block with exact resolved version when non-empty`() {
    val task: PackNugetTask = newTask()

    val csDir: File = Files.createTempDirectory("ksp-cs").toFile()
    File(csDir, "Interop.cs").writeText("// forward bindings\n")

    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    configureCommon(task, outputDir)
    task.generatedCsDirs.from(csDir)
    task.dependencyVersions.set(mapOf("MimeMapping" to "4.0.0"))

    task.pack()

    val nuspec: String = File(outputDir, "TestLibrary.1.0.0/TestLibrary.nuspec").readText()
    assertContains(nuspec, "<dependencies>")
    assertContains(nuspec, """<group targetFramework="net10.0">""")
    assertContains(nuspec, """<dependency id="MimeMapping" version="[4.0.0]" />""")
  }

  @Test
  fun `pack nuspec includes contract when bound dependencyVersions is empty`() {
    val task: PackNugetTask = newTask()

    val csDir: File = Files.createTempDirectory("ksp-cs").toFile()
    File(csDir, "Interop.cs").writeText("// forward bindings\n")

    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    configureCommon(task, outputDir)
    task.generatedCsDirs.from(csDir)
    task.dependencyVersions.set(emptyMap())

    task.pack()

    val nuspec: String = File(outputDir, "TestLibrary.1.0.0/TestLibrary.nuspec").readText()
    assertContains(nuspec, "<dependency id=\"Kotlin.Native.Interop\" version=\"[1.0.0,2.0.0)\" />")
  }

  @Test
  fun `pack copies native libraries into runtimes rid native`() {
    val task: PackNugetTask = newTask()

    val csDir: File = Files.createTempDirectory("ksp-cs").toFile()
    File(csDir, "Interop.cs").writeText("// forward bindings\n")

    val nativeDir: File = Files.createTempDirectory("native-libs").toFile()
    File(nativeDir, "kn_testlibrary.dll").writeText("fake native binary")

    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    configureCommon(task, outputDir)
    task.generatedCsDirs.from(csDir)
    task.dependencyVersions.set(emptyMap())
    task.nativeLibDirs.set(mapOf("win-x64" to nativeDir.path))
    writeProducerContract(csDir, "// forward bindings\n")
    task.localContractDirs.set(mapOf("win-x64" to csDir.path))

    task.pack()

    val copied = File(outputDir, "TestLibrary.1.0.0/runtimes/win-x64/native/kn_testlibrary.dll")
    assertTrue(copied.exists(), "kn_testlibrary.dll must be copied into runtimes/win-x64/native/")
  }

  @Test
  fun `pack filters out non-native files from a nativeLibDirs source`() {
    val task: PackNugetTask = newTask()

    val csDir: File = Files.createTempDirectory("ksp-cs").toFile()
    File(csDir, "Interop.cs").writeText("// forward bindings\n")

    val nativeDir: File = Files.createTempDirectory("native-libs").toFile()
    File(nativeDir, "kn_testlibrary.dll").writeText("fake native binary")
    File(nativeDir, "Sample.pdb").writeText("fake debug symbols")
    File(nativeDir, "Sample.xml").writeText("<doc></doc>")

    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    configureCommon(task, outputDir)
    task.generatedCsDirs.from(csDir)
    task.dependencyVersions.set(emptyMap())
    task.nativeLibDirs.set(mapOf("win-x64" to nativeDir.path))
    writeProducerContract(csDir, "// forward bindings\n")
    task.localContractDirs.set(mapOf("win-x64" to csDir.path))

    task.pack()

    val nativeOutDir = File(outputDir, "TestLibrary.1.0.0/runtimes/win-x64/native")
    assertTrue(File(nativeOutDir, "kn_testlibrary.dll").exists())
    assertFalse(File(nativeOutDir, "Sample.pdb").exists())
    assertFalse(File(nativeOutDir, "Sample.xml").exists())
  }

  // ADR-093 flips this case: a nativeLibDirs entry that produced nothing used to be skipped
  // silently, which shipped packages missing a platform. Disabled targets no longer reach the map,
  // so an empty entry now means the local link is broken.
  @Test
  fun `pack fails for a nativeLibDirs entry whose path does not exist`() {
    val task: PackNugetTask = newTask()

    val csDir: File = Files.createTempDirectory("ksp-cs").toFile()
    File(csDir, "Interop.cs").writeText("// forward bindings\n")

    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    configureCommon(task, outputDir)
    task.generatedCsDirs.from(csDir)
    task.dependencyVersions.set(emptyMap())
    task.nativeLibDirs.set(mapOf("osx-arm64" to "/nonexistent/path/xyz"))

    val error = assertFailsWith<IllegalStateException> { task.pack() }

    assertContains(error.message.orEmpty(), "osx-arm64")
  }

  // A blank packageId passes `Property.get()`, and would name the .nupkg `  .1.0.0.nupkg` with a
  // `<id>` of spaces. packNuget is the one task that truly needs an id, so it fails clearly.
  @Test
  fun `pack fails clearly for a blank packageId`() {
    val task: PackNugetTask = newTask()
    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    configureCommon(task, outputDir)
    task.packageId.set("  ")
    task.dependencyVersions.set(emptyMap())
    task.nativeLibDirs.set(emptyMap())

    val error = assertFailsWith<IllegalArgumentException> { task.pack() }

    assertContains(error.message.orEmpty(), "packageId")
  }
}
