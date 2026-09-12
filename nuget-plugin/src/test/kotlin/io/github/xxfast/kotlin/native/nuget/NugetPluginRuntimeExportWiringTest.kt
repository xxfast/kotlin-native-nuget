package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-127 seam 2: the plugin adds `io.github.xxfast:nuget-runtime` as an **`api`** dependency of
 * every bridgeable native target's main source set and `export()`s it from every `SharedLibrary`.
 *
 * Both halves are load-bearing and were verified by the ADR's spike: without `export()` the
 * runtime's `@CName` symbols never reach the consumer's `.dylib` (every `nuget_*` P/Invoke fails
 * with `EntryPointNotFoundException`), and `export()` of an `implementation` dependency fails at
 * link time. There is no `:nuget-runtime` project in a `ProjectBuilder` build, so the resolution
 * falls to the published coordinate at `PLUGIN_VERSION`, exactly as the processor's does.
 */
class NugetPluginRuntimeExportWiringTest {

  private val runtimeCoordinate: String = "io.github.xxfast:nuget-runtime:$PLUGIN_VERSION"

  private fun buildProjectWithSharedLib(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")

    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.macosArm64 {
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

  private fun Project.sharedLibs(targetName: String): List<SharedLibrary> {
    val kotlin: KotlinMultiplatformExtension =
      extensions.getByType(KotlinMultiplatformExtension::class.java)
    val target: KotlinNativeTarget = kotlin.targets.getByName(targetName) as KotlinNativeTarget
    return target.binaries.filterIsInstance<SharedLibrary>()
  }

  private fun Project.dependencyNames(configuration: String): List<String> =
    configurations.getByName(configuration).allDependencies.map { it.coordinate() }

  private fun Dependency.coordinate(): String = "${group.orEmpty()}:$name:${version.orEmpty()}"

  @Test
  fun `the runtime is added as an api dependency of the target's main source set`() {
    val project: Project = buildProjectWithSharedLib()
    project.evaluate()

    assertTrue(
      runtimeCoordinate in project.dependencyNames("macosArm64MainApi"),
      "expected the runtime on the target's api configuration; " +
        "got ${project.dependencyNames("macosArm64MainApi")}",
    )
  }

  @Test
  fun `the runtime is exported from every shared library binary`() {
    val project: Project = buildProjectWithSharedLib()
    project.evaluate()

    project.sharedLibs("macosArm64").forEach { lib ->
      assertTrue(
        runtimeCoordinate in project.dependencyNames(lib.exportConfigurationName),
        "expected the runtime exported from ${lib.name}; " +
          "got ${project.dependencyNames(lib.exportConfigurationName)}",
      )
    }
  }

  /** `export()` appends; an author's own entries must survive the plugin adding the runtime. */
  @Test
  fun `an author's own export entries are left in place`() {
    val project: Project = buildProjectWithSharedLib()
    project.sharedLibs("macosArm64").forEach { lib -> lib.export("com.contoso:widgets:1.2.3") }

    project.evaluate()

    project.sharedLibs("macosArm64").forEach { lib ->
      val exported: List<String> = project.dependencyNames(lib.exportConfigurationName)
      assertTrue("com.contoso:widgets:1.2.3" in exported, "author entry lost; got $exported")
      assertTrue(runtimeCoordinate in exported, "runtime missing; got $exported")
    }
  }

  /** An author who already exports the runtime ends with one entry, not two. */
  @Test
  fun `the runtime is not duplicated when the author already exported it`() {
    val project: Project = buildProjectWithSharedLib()
    project.sharedLibs("macosArm64").forEach { lib -> lib.export(runtimeCoordinate) }

    project.evaluate()

    project.sharedLibs("macosArm64").forEach { lib ->
      val exported: List<String> = project.dependencyNames(lib.exportConfigurationName)
      assertEquals(
        1,
        exported.count { it == runtimeCoordinate },
        "expected exactly one runtime export entry; got $exported",
      )
      assertTrue(
        runtimeCoordinate in project.dependencyNames("macosArm64MainApi"),
        "expected the api dependency regardless of the author's export; " +
          "got ${project.dependencyNames("macosArm64MainApi")}",
      )
    }
  }

  /** A native target outside `KONAN_TO_RID` is not a bridge target: no runtime, no export. */
  @Test
  fun `a target outside the RID map gets neither the dependency nor the export`() {
    val project: Project = buildProjectWithSharedLib()
    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.iosArm64()

    project.evaluate()

    assertTrue(
      runtimeCoordinate !in project.dependencyNames("iosArm64MainApi"),
      "expected no runtime on a non-bridge target; " +
        "got ${project.dependencyNames("iosArm64MainApi")}",
    )
  }
}
