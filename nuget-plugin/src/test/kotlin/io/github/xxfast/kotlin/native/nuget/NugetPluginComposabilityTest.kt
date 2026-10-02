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
 * Today `NugetPlugin.kt`'s `publish {}`/`nugetPack` `afterEvaluate` block calls
 * `requireNotNull(extension.publish)` unconditionally whenever the KMP plugin is applied. A project
 * that configures only `nuget { dependencies { dependency(...) { bind {} } } }` — no `publish {}` —
 * therefore crashes at `project.evaluate()`. ADR-050 replaces that guard with an early return so
 * publish-only, consume-only, and publish+consume are all valid, composable configurations.
 *
 * Mirrors the `buildProjectWithSharedLib` helper from [NugetGenerateShimsTaskWiringTest]: the KMP
 * plugin must be applied (to reach the `nugetPack`-registering code path) with a
 * `binaries { sharedLib {} }` target configured (required by the consume-side
 * `nativeLibraryName` derivation and by `nugetPack`'s own supported-target check).
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
          baseName = "test"
        }
      }
    }

    return project
  }

  private fun Project.evaluate() {
    (this as ProjectInternal).evaluate()
  }

  @Test
  fun `publish-only project evaluates without throwing and registers nugetPack`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
    }

    project.evaluate()

    assertNotNull(project.tasks.findByName("nugetPack"))
    assertNull(project.tasks.findByName("nugetExtractApi"))
    assertNull(project.tasks.findByName("nugetGenerateBindings"))
    assertNull(project.tasks.findByName("nugetGenerateShims"))
  }

  @Test
  fun `consume-only project evaluates without throwing and registers consume tasks but not nugetPack`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      it.dependency("MimeMapping", version = "4.0.0") {
        it.bind { }
      }
    }

    // Today this call throws IllegalArgumentException from the publish/nugetPack block's
    // `requireNotNull(extension.publish)`, even though this project declares no `publish {}` block
    // at all — that crash is the regression this test pins (ADR-050 Alternative 6).
    project.evaluate()

    assertNotNull(project.tasks.findByName("nugetExtractApi"))
    assertNotNull(project.tasks.findByName("nugetGenerateBindings"))
    assertNotNull(project.tasks.findByName("nugetGenerateShims"))
    assertNull(project.tasks.findByName("nugetPack"))
  }

  @Test
  fun `publish and consume together evaluate without throwing and register all tasks`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
    }

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      it.dependency("MimeMapping", version = "4.0.0") {
        it.bind { }
      }
    }

    project.evaluate()

    assertNotNull(project.tasks.findByName("nugetPack"))
    assertNotNull(project.tasks.findByName("nugetExtractApi"))
    assertNotNull(project.tasks.findByName("nugetGenerateBindings"))
    assertNotNull(project.tasks.findByName("nugetGenerateShims"))
  }
}
