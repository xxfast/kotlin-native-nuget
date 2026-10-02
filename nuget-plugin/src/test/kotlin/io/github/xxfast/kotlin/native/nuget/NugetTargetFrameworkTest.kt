package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-184: one `nuget { targetFramework }` value, read lazily by every place the plugin names a
 * .NET target framework. Default `net10.0`, the C# 14 floor of ADR-188.
 */
class NugetTargetFrameworkTest {
  private fun project(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    project.extensions.getByType(KotlinMultiplatformExtension::class.java)
      .mingwX64 { target -> target.binaries { sharedLib { baseName = "test" } } }
    return project
  }

  private fun Project.declareEverything(): NugetExtension {
    val nuget: NugetExtension = extensions.getByType(NugetExtension::class.java)
    nuget.publish { pub -> pub.packageId.set("Acme.Kennel"); pub.version.set("1.0.0") }
    nuget.dependencies { deps -> deps.dependency("Serilog", version = "3.1.1") { it.bind { } } }
    return nuget
  }

  private fun Project.frameworks(): List<String> = listOf(
    (tasks.getByName("nugetGenerateRestoreProject") as NugetGenerateRestoreProjectTask).targetFramework.get(),
    (tasks.getByName("nugetRestore") as NugetRestoreTask).targetFramework.get(),
    (tasks.getByName("nugetExtractApi") as NugetExtractApiTask).targetFramework.get(),
    (tasks.getByName("nugetCompileInterop") as NugetCompileInteropTask).targetFramework.get(),
    (tasks.getByName("nugetPack") as NugetPackTask).targetFramework.get(),
  )

  @Test
  fun `targetFramework flows to every task that names a TFM`() {
    val project: Project = project()
    val nuget: NugetExtension = project.declareEverything()

    // Set AFTER the blocks registered the tasks: the provider chain, not a snapshot.
    nuget.targetFramework.set("net11.0")

    assertEquals(List(5) { "net11.0" }, project.frameworks())
  }

  @Test
  fun `targetFramework defaults to net10 0`() {
    val project: Project = project()
    project.declareEverything()

    assertEquals(List(5) { "net10.0" }, project.frameworks())
  }

  @Test
  fun `targetFramework below net10 0 or not netX 0 fails with a nuget error`() {
    listOf("net8.0", "net9.0", "netstandard2.0", "net10.0-windows", "net10", "net48", "")
      .forEach { value ->
        val project: Project = project()
        val nuget: NugetExtension = project.declareEverything()
        nuget.targetFramework.set(value)

        val error: Throwable = assertFailsWith<Throwable>(value) {
          (project.tasks.getByName("nugetPack") as NugetPackTask).targetFramework.get()
        }
        val messages: String = generateSequence(error) { it.cause }
          .mapNotNull { it.message }
          .joinToString("\n")
        assertTrue("[nuget]" in messages && "netX.0" in messages, messages)
        assertTrue("'$value'" in messages, messages)
        assertTrue("ADR-188" in messages && "C# 14" in messages, messages)
      }
  }

  @Test
  fun `validateTargetFramework accepts netX 0 at or above the floor`() {
    assertEquals("net10.0", validateTargetFramework("net10.0"))
    assertEquals("net11.0", validateTargetFramework("net11.0"))
  }
}
