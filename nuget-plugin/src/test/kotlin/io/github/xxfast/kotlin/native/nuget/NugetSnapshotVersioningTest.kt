package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-092: `nuget { publish { snapshot = true } }` mints `<version>-snapshot.<epochMillis>` at
 * execution time and emits an MSBuild props file pinning it, promoting the fixture mechanism in
 * `test-library/build.gradle.kts` to a first-class DSL option.
 */
class NugetSnapshotVersioningTest {
  private fun buildProjectWithSharedLib(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")

    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.mingwX64 {
      binaries {
        sharedLib {
          baseName = "test"
        }
      }
    }

    return project
  }

  private fun Project.evaluate() {
    (this as ProjectInternal).evaluate()
  }

  private val snapshotVersion = Regex("""^1\.0\.0-snapshot\.\d+$""")

  @Test
  fun `snapshot mode registers both snapshot tasks`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("PeopleInSpace.Kotlin")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.snapshot.set(true)
    }

    project.evaluate()

    assertNotNull(project.tasks.findByName("nugetSnapshotVersion"))
    assertNotNull(project.tasks.findByName("nugetSnapshotVersionProps"))
  }

  @Test
  fun `packNuget resolves the minted version once the version task has run`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("PeopleInSpace.Kotlin")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.snapshot.set(true)
    }

    project.evaluate()

    val mint = project.tasks.getByName("nugetSnapshotVersion") as NugetSnapshotVersionTask
    mint.write()

    val packNuget = project.tasks.getByName("packNuget") as PackNugetTask
    val version: String = packNuget.packageVersion.get()

    assertTrue(
      snapshotVersion.matches(version),
      "packNuget.packageVersion must resolve to <base>-snapshot.<millis>, was '$version'",
    )
  }

  @Test
  fun `packNuget depends on both snapshot tasks`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.snapshot.set(true)
    }

    project.evaluate()

    val packNuget: Task = project.tasks.getByName("packNuget")
    val names: Set<String> = packNuget.taskDependencies
      .getDependencies(packNuget)
      .map { it.name }
      .toSet()

    assertTrue(
      names.contains("nugetSnapshotVersion"),
      "packNuget must depend on nugetSnapshotVersion",
    )
    assertTrue(
      names.contains("nugetSnapshotVersionProps"),
      "packNuget must depend on nugetSnapshotVersionProps",
    )
  }

  @Test
  fun `non-snapshot mode keeps packNuget off the snapshot tasks and keeps the literal version`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
    }

    project.evaluate()

    // ADR-180: the snapshot tasks are registered with `publish {}`; packNuget pulls them in only
    // when `snapshot` is true.
    val packNuget = project.tasks.getByName("packNuget") as PackNugetTask
    val names: Set<String> =
      packNuget.taskDependencies.getDependencies(packNuget).map { it.name }.toSet()
    assertFalse("nugetSnapshotVersion" in names, "was $names")
    assertFalse("nugetSnapshotVersionProps" in names, "was $names")
    assertEquals("1.0.0", packNuget.packageVersion.get())
  }

  @Test
  fun `snapshot without a base version fails when the version is minted`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("TestLibrary")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.snapshot.set(true)
    }

    project.evaluate()
    val mint = project.tasks.getByName("nugetSnapshotVersion") as NugetSnapshotVersionTask
    val error: Throwable = assertFailsWith<Exception> { mint.write() }
    val message: String = generateSequence(error) { it.cause }
      .mapNotNull { it.message }
      .joinToString("\n")

    assertTrue(
      message.contains("snapshot"),
      "the failure must name the snapshot option, was '$message'",
    )
  }

  @Test
  fun `snapshot without a package id fails when the version is minted`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.snapshot.set(true)
    }

    project.evaluate()
    val mint = project.tasks.getByName("nugetSnapshotVersion") as NugetSnapshotVersionTask
    val error: Throwable = assertFailsWith<Exception> { mint.write() }
    val message: String = generateSequence(error) { it.cause }
      .mapNotNull { it.message }
      .joinToString("\n")

    assertTrue(
      message.contains("packageId"),
      "the failure must name the missing packageId, was '$message'",
    )
  }

  @Test
  fun `a package id with no usable character fails fast`() {
    val error = assertFailsWith<IllegalArgumentException> { msbuildVersionPropertyName("...") }

    assertTrue(
      error.message.orEmpty().contains("MSBuild"),
      "the failure must explain the MSBuild property name constraint, was '${error.message}'",
    )
  }

  @Test
  fun `props file carries the minted version under the sanitized property name`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("PeopleInSpace.Kotlin")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.snapshot.set(true)
    }

    project.evaluate()

    val mint = project.tasks.getByName("nugetSnapshotVersion") as NugetSnapshotVersionTask
    mint.write()

    val props =
      project.tasks.getByName("nugetSnapshotVersionProps") as NugetSnapshotVersionPropsTask
    props.write()

    val file: File = props.outputFile.get().asFile
    val content: String = file.readText()
    val version: String = mint.outputFile.get().asFile.readText().trim()

    assertTrue(
      file.name == "PeopleInSpace.KotlinVersions.props",
      "the props file name keeps the literal package id, was '${file.name}'",
    )
    assertTrue(
      content.contains("<PeopleInSpaceKotlinVersion>$version</PeopleInSpaceKotlinVersion>"),
      "props must pin the minted version under the sanitized name, was:\n$content",
    )
    assertTrue(snapshotVersion.matches(version), "minted version was '$version'")
  }

  @Test
  fun `versionPropsFile override is respected`() {
    val project: Project = buildProjectWithSharedLib()
    val override: File = File(Files.createTempDirectory("props-override").toFile(), "Custom.props")

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.snapshot.set(true)
      it.versionPropsFile.set(override)
    }

    project.evaluate()

    val mint = project.tasks.getByName("nugetSnapshotVersion") as NugetSnapshotVersionTask
    mint.write()

    val props =
      project.tasks.getByName("nugetSnapshotVersionProps") as NugetSnapshotVersionPropsTask
    props.write()

    assertEquals(override.absolutePath, props.outputFile.get().asFile.absolutePath)
    assertTrue(
      override.readText().contains("<TestLibraryVersion>"),
      "override file must be written",
    )
  }

  @Test
  fun `dots are dropped from the msbuild property name`() {
    assertEquals("PeopleInSpaceKotlinVersion", msbuildVersionPropertyName("PeopleInSpace.Kotlin"))
  }

  @Test
  fun `a plain id keeps its shape`() {
    assertEquals("TestLibraryVersion", msbuildVersionPropertyName("TestLibrary"))
  }

  @Test
  fun `a leading digit is underscore-prefixed`() {
    assertEquals("_51DegreesmobiVersion", msbuildVersionPropertyName("51Degrees.mobi"))
  }

  @Test
  fun `dashes are dropped and underscores kept`() {
    assertEquals("MyPackageVersion", msbuildVersionPropertyName("My-Package"))
    assertEquals("My_PackageVersion", msbuildVersionPropertyName("My_Package"))
  }
}
