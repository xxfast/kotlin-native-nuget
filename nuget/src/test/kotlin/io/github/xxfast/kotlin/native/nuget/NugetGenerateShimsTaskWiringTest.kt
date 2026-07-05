package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task-wiring tests for `nugetGenerateShims`. Mirrors [NugetGenerateBindingsTaskWiringTest]:
 * verifies that the task is registered (or not) based on whether dependencies have a `bind {}`
 * block, that it is wired into the `nugetImport` dependency graph, that its output dir is rooted
 * under `build/nuget-interop/csharp/`, and — the one input the Kotlin-side sibling task doesn't
 * need — that `nativeLibraryName` is sourced from the configured `binaries { sharedLib {} }`
 * `baseName` (ADR-049 "Native library name source").
 */
class NugetGenerateShimsTaskWiringTest {
  private fun buildProject(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    return project
  }

  // A project with a Kotlin/Native shared-lib binary configured, mirroring sample-library's
  // `binaries { sharedLib { baseName = "sample" } }` — required so `nugetGenerateShims`'s
  // fail-fast `nativeLibraryName` derivation (ADR-049 Alternative 12) has something to resolve.
  //
  // Applying the real "org.jetbrains.kotlin.multiplatform" plugin also activates NugetPlugin's
  // pre-existing forward-direction (`packNuget`) wiring, which independently requires a
  // `nuget { publish { ... } }` block to be present at evaluation time — unrelated to this ADR,
  // but a precondition of applying the KMP plugin at all in this codebase, so it must be
  // satisfied here too (all fields are nullable; an empty block is sufficient).
  private fun buildProjectWithSharedLib(baseName: String = "sample"): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")

    project.extensions.getByType(NugetExtension::class.java).publish { }

    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.mingwX64 {
      binaries {
        sharedLib {
          this.baseName = baseName
        }
      }
    }

    return project
  }

  private fun Project.evaluate() {
    (this as ProjectInternal).evaluate()
  }

  @Test
  fun `nugetGenerateShims is registered when at least one dependency has bind block`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    assertNotNull(project.tasks.findByName("nugetGenerateShims"))
  }

  @Test
  fun `nugetGenerateShims is not registered when no dependencies have bind block`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3")
    }

    project.evaluate()

    assertNull(project.tasks.findByName("nugetGenerateShims"))
  }

  @Test
  fun `nugetGenerateShims task is an instance of NugetGenerateShimsTask`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val task: Task = project.tasks.getByName("nugetGenerateShims")
    assertTrue(
      task is NugetGenerateShimsTask,
      "nugetGenerateShims must be a NugetGenerateShimsTask",
    )
  }

  @Test
  fun `nugetGenerateShims nativeLibraryName resolves from the configured sharedLib baseName`() {
    val project: Project = buildProjectWithSharedLib(baseName = "sample")

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val task: NugetGenerateShimsTask =
      project.tasks.getByName("nugetGenerateShims") as NugetGenerateShimsTask

    assertEquals("sample", task.nativeLibraryName.get())
  }

  @Test
  fun `nugetImport depends on nugetGenerateShims when bound packages exist`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val nugetGenerateShims: Task = project.tasks.getByName("nugetGenerateShims")
    val nugetImport: Task = project.tasks.getByName("nugetImport")
    val deps: Set<Task> = nugetImport.taskDependencies.getDependencies(nugetImport)

    assertTrue(deps.contains(nugetGenerateShims), "nugetImport must depend on nugetGenerateShims")
  }

  @Test
  fun `nugetImport does not depend on nugetGenerateShims when no bound packages`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3")
    }

    project.evaluate()

    assertNull(project.tasks.findByName("nugetGenerateShims"))

    val nugetImport: Task = project.tasks.getByName("nugetImport")
    val deps: Set<Task> = nugetImport.taskDependencies.getDependencies(nugetImport)

    assertFalse(
      deps.any { it.name == "nugetGenerateShims" },
      "nugetImport must not depend on nugetGenerateShims when no packages are bound",
    )
  }

  @Test
  fun `nugetGenerateShims csharpOutputDir is under nuget-interop csharp directory`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val task: NugetGenerateShimsTask =
      project.tasks.getByName("nugetGenerateShims") as NugetGenerateShimsTask
    val path: String = task.csharpOutputDir.get().asFile.absolutePath

    assertTrue(path.contains("nuget-interop"), "csharpOutputDir must be under nuget-interop/")
    assertTrue(path.endsWith("csharp"), "csharpOutputDir must end with 'csharp'")
  }

  @Test
  fun `nugetGenerateShims reverseIrFile path matches nugetExtractApi reverseIrFile path`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val nugetExtractApi: NugetExtractApiTask =
      project.tasks.getByName("nugetExtractApi") as NugetExtractApiTask
    val nugetGenerateShims: NugetGenerateShimsTask =
      project.tasks.getByName("nugetGenerateShims") as NugetGenerateShimsTask

    assertTrue(
      nugetGenerateShims.reverseIrFile.get().asFile.absolutePath ==
        nugetExtractApi.reverseIrFile.get().asFile.absolutePath,
      "nugetGenerateShims.reverseIrFile must point at the same file as " +
        "nugetExtractApi.reverseIrFile",
    )
  }
}
