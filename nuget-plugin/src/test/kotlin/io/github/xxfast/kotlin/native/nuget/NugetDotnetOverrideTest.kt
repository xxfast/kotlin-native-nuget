package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * `nuget.dotnet` points the plugin at a `dotnet` that is not on Gradle's PATH. `local.properties`
 * is read first, then the Gradle property, and all three tasks that run `dotnet` see the same value.
 */
class NugetDotnetOverrideTest {
  private fun project(dir: File): Project {
    val project: Project = ProjectBuilder.builder().withProjectDir(dir).build()

    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    project.extensions.getByType(KotlinMultiplatformExtension::class.java)
      .mingwX64 { target -> target.binaries { sharedLib { baseName = "test" } } }

    val nuget: NugetExtension = project.extensions.getByType(NugetExtension::class.java)
    nuget.dependencies { deps -> deps.dependency("Acme", "1.0.0") { dep -> dep.bind { } } }
    nuget.publish {
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
    }

    return project
  }

  private fun Project.restore(): NugetRestoreTask =
    tasks.getByName(NugetTaskNames.RESTORE) as NugetRestoreTask

  private fun Project.extract(): NugetExtractApiTask =
    tasks.getByName(NugetTaskNames.EXTRACT_API) as NugetExtractApiTask

  private fun Project.compileInterop(): NugetCompileInteropTask =
    tasks.getByName(NugetTaskNames.COMPILE_INTEROP) as NugetCompileInteropTask

  @Test
  fun `local properties reaches all three dotnet tasks`(@TempDir root: File) {
    val dir: File = File(root, "project").also { it.mkdirs() }
    File(dir, "local.properties").writeText("sdk.dir=/ignored\nnuget.dotnet=/opt/dotnet/dotnet\n")
    val project: Project = project(dir)

    assertEquals("/opt/dotnet/dotnet", project.restore().dotnet.get())
    assertEquals("/opt/dotnet/dotnet", project.extract().dotnet.get())
    assertEquals("/opt/dotnet/dotnet", project.compileInterop().dotnet.get())
    assertContains(project.restore().dotnetSource.get(), "local.properties")
    assertContains(project.compileInterop().dotnetSource.get(), "local.properties")
  }

  // ProjectBuilder never feeds `providers.gradleProperty` (the PublishNugetTaskWiringTest note), so
  // the Gradle-property fallback and its precedence are checked by a real `-Pnuget.dotnet` build.
  @Test
  fun `a local properties file without the key leaves the tasks on PATH`(@TempDir root: File) {
    val dir: File = File(root, "project").also { it.mkdirs() }
    File(dir, "local.properties").writeText("sdk.dir=/ignored\n")
    val project: Project = project(dir)

    assertFalse(project.restore().dotnet.isPresent)
    assertFalse(project.compileInterop().dotnetSource.isPresent)
  }

  @Test
  fun `nothing configured leaves the tasks on PATH`(@TempDir root: File) {
    val project: Project = project(File(root, "project").also { it.mkdirs() })

    assertFalse(project.restore().dotnet.isPresent)
    assertFalse(project.extract().dotnet.isPresent)
    assertFalse(project.compileInterop().dotnet.isPresent)
  }

  // A configured fake dotnet that fails its first restore with NU1301 on stdout, where the real
  // one reports it, then succeeds: the task retries through the configured path.
  @Test
  @DisabledOnOs(OS.WINDOWS)
  fun `restore retries a transient feed failure through the configured dotnet`(
    @TempDir root: File,
  ) {
    val calls = File(root, "calls")
    val fake = File(root, "dotnet")
    fake.writeText(
      """
      #!/bin/sh
      echo x >> "${calls.absolutePath}"
      if [ "${'$'}(wc -l < "${calls.absolutePath}")" -lt 2 ]; then
        echo "r.csproj : error NU1301: Unable to load the service index for source https://feed."
        exit 1
      fi
      exit 0
      """.trimIndent()
    )
    fake.setExecutable(true)

    val csproj = File(root, "interop.csproj")
    csproj.writeText("<Project />")
    val task: NugetRestoreTask = ProjectBuilder.builder().build().tasks
      .register("nugetRestore", NugetRestoreTask::class.java)
      .get()

    task.csprojFile.set(csproj)
    task.targetFramework.set("net10.0")
    task.dotnet.set(fake.absolutePath)
    task.dotnetSource.set("local.properties")

    task.restore()

    assertEquals(2, calls.readLines().size)
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  fun `a restore failure message carries the NU error from stdout`(@TempDir root: File) {
    val fake = File(root, "dotnet")
    fake.writeText("#!/bin/sh\necho \"r.csproj : error NU1101: Unable to find package Acme.\"\nexit 1\n")
    fake.setExecutable(true)

    val csproj = File(root, "interop.csproj")
    csproj.writeText("<Project />")
    val task: NugetRestoreTask = ProjectBuilder.builder().build().tasks
      .register("nugetRestore", NugetRestoreTask::class.java)
      .get()

    task.csprojFile.set(csproj)
    task.targetFramework.set("net10.0")
    task.dotnet.set(fake.absolutePath)
    task.dotnetSource.set("local.properties")

    val failure: GradleException = assertFailsWith<GradleException> { task.restore() }

    assertContains(failure.message.orEmpty(), "NU1101")
  }
}
