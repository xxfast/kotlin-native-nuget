package io.github.xxfast.kotlin.native.nuget

import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task-action tests for `PackNugetTask` (ADR-050 pieces 1 and 2).
 *
 * Piece 1: `generatedCsDir: DirectoryProperty` becomes `generatedCsDirs: ConfigurableFileCollection`
 * so `packNuget` can merge `.cs` files from multiple producers (KSP's forward `Interop.cs` and
 * `nugetGenerateShims`'s reverse registration shims) into one `contentFiles/cs/any/` folder.
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
  fun `pack merges cs files from multiple generatedCsDirs into contentFiles cs any`() {
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

    val contentDir = File(outputDir, "TestLibrary.1.0.0/contentFiles/cs/any")
    assertTrue(
      File(contentDir, "Interop.cs").exists(),
      "Interop.cs from the first generatedCsDirs entry must be copied into contentFiles/cs/any/",
    )
    assertTrue(
      File(contentDir, "FooRegistration.cs").exists(),
      "FooRegistration.cs from the second generatedCsDirs entry must be copied into " +
        "contentFiles/cs/any/",
    )

    val nuspec: String = File(outputDir, "TestLibrary.1.0.0/TestLibrary.nuspec").readText()
    assertContains(
      nuspec,
      """<file src="contentFiles/cs/any/Interop.cs" target="contentFiles/cs/any/Interop.cs" />""",
    )
    assertContains(
      nuspec,
      """<file src="contentFiles/cs/any/FooRegistration.cs" """ +
        """target="contentFiles/cs/any/FooRegistration.cs" />""",
    )
    assertContains(
      nuspec,
      """<files include="cs/any/**/*.cs" buildAction="Compile" />""",
      message = "the contentFiles include glob must cover both merged files",
    )
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
    assertContains(nuspec, """<group targetFramework="net8.0">""")
    assertContains(nuspec, """<dependency id="MimeMapping" version="[4.0.0]" />""")
  }

  @Test
  fun `pack nuspec omits dependencies block when dependencyVersions is empty`() {
    val task: PackNugetTask = newTask()

    val csDir: File = Files.createTempDirectory("ksp-cs").toFile()
    File(csDir, "Interop.cs").writeText("// forward bindings\n")

    val outputDir: File = Files.createTempDirectory("pack-out").toFile()
    configureCommon(task, outputDir)
    task.generatedCsDirs.from(csDir)
    task.dependencyVersions.set(emptyMap())

    task.pack()

    val nuspec: String = File(outputDir, "TestLibrary.1.0.0/TestLibrary.nuspec").readText()
    assertFalse(
      nuspec.contains("<dependencies>"),
      "no <dependencies> block should be emitted when dependencyVersions is empty",
    )
  }
}
