package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression tests for ADR-050 Alternative 6 (single merged `afterEvaluate` block).
 *
 * Today `NugetPlugin.kt`'s `publish {}`/`packNuget` `afterEvaluate` block calls
 * `requireNotNull(extension.publish)` unconditionally whenever the KMP plugin is applied. A project
 * that configures only `nuget { dependencies { dependency(...) { bind {} } } }` — no `publish {}` —
 * therefore crashes at `project.evaluate()`. ADR-050 replaces that guard with an early return so
 * publish-only, consume-only, and publish+consume are all valid, composable configurations.
 *
 * Mirrors the `buildProjectWithSharedLib` helper from [NugetGenerateShimsTaskWiringTest]: the KMP
 * plugin must be applied (to reach the `packNuget`-registering code path) with a
 * `binaries { sharedLib {} }` target configured (required by the consume-side
 * `nativeLibraryName` derivation and by `packNuget`'s own supported-target check).
 */
class NugetPluginComposabilityTest {
  private fun buildProjectWithSharedLib(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")

    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.mingwX64 {
      binaries {
        sharedLib {
          baseName = "sample"
        }
      }
    }

    return project
  }

  private fun Project.evaluate() {
    (this as ProjectInternal).evaluate()
  }

  @Test
  fun `publish-only project evaluates without throwing and registers packNuget`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "SampleLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
    }

    project.evaluate()

    assertNotNull(project.tasks.findByName("packNuget"))
    assertNull(project.tasks.findByName("nugetExtractApi"))
    assertNull(project.tasks.findByName("nugetGenerateBindings"))
    assertNull(project.tasks.findByName("nugetGenerateShims"))
  }

  @Test
  fun `consume-only project evaluates without throwing and registers consume tasks but not packNuget`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("MimeMapping", version = "4.0.0") {
        bind { }
      }
    }

    // Today this call throws IllegalArgumentException from the publish/packNuget block's
    // `requireNotNull(extension.publish)`, even though this project declares no `publish {}` block
    // at all — that crash is the regression this test pins (ADR-050 Alternative 6).
    project.evaluate()

    assertNotNull(project.tasks.findByName("nugetExtractApi"))
    assertNotNull(project.tasks.findByName("nugetGenerateBindings"))
    assertNotNull(project.tasks.findByName("nugetGenerateShims"))
    assertNull(project.tasks.findByName("packNuget"))
  }

  @Test
  fun `publish and consume together evaluate without throwing and register all tasks`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "SampleLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
    }

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("MimeMapping", version = "4.0.0") {
        bind { }
      }
    }

    project.evaluate()

    assertNotNull(project.tasks.findByName("packNuget"))
    assertNotNull(project.tasks.findByName("nugetExtractApi"))
    assertNotNull(project.tasks.findByName("nugetGenerateBindings"))
    assertNotNull(project.tasks.findByName("nugetGenerateShims"))
  }
}
