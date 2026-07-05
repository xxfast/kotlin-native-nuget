package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task-wiring tests for `nugetGenerateBindings`. Verifies that the task is registered (or not)
 * based on whether dependencies have a `bind {}` block, that it is wired into the `nugetImport`
 * dependency graph, and that its output dir is rooted under `build/nuget-interop/kotlin/`.
 *
 * ADR-048: task registration guard (bound.isNotEmpty()), output dir layout, task-graph position.
 */
class NugetGenerateBindingsTaskWiringTest {
  private fun buildProject(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    return project
  }

  private fun Project.evaluate() {
    (this as ProjectInternal).evaluate()
  }

  @Test
  fun `nugetGenerateBindings is registered when at least one dependency has bind block`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    assertNotNull(project.tasks.findByName("nugetGenerateBindings"))
  }

  @Test
  fun `nugetGenerateBindings is not registered when no dependencies have bind block`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3")
    }

    project.evaluate()

    assertNull(project.tasks.findByName("nugetGenerateBindings"))
  }

  @Test
  fun `nugetImport depends on nugetGenerateBindings when bound packages exist`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val nugetGenerateBindings: Task = project.tasks.getByName("nugetGenerateBindings")
    val nugetImport: Task = project.tasks.getByName("nugetImport")
    val deps: Set<Task> = nugetImport.taskDependencies.getDependencies(nugetImport)

    assertTrue(deps.contains(nugetGenerateBindings), "nugetImport must depend on nugetGenerateBindings")
  }

  @Test
  fun `nugetImport does not depend on nugetGenerateBindings when no bound packages`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3")
    }

    project.evaluate()

    assertNull(project.tasks.findByName("nugetGenerateBindings"))

    val nugetImport: Task = project.tasks.getByName("nugetImport")
    val deps: Set<Task> = nugetImport.taskDependencies.getDependencies(nugetImport)

    assertFalse(
      deps.any { it.name == "nugetGenerateBindings" },
      "nugetImport must not depend on nugetGenerateBindings when no packages are bound",
    )
  }

  @Test
  fun `nugetGenerateBindings kotlinOutputDir is under nuget-interop kotlin directory`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val task: NugetGenerateBindingsTask =
      project.tasks.getByName("nugetGenerateBindings") as NugetGenerateBindingsTask
    val path: String = task.kotlinOutputDir.get().asFile.absolutePath

    assertTrue(path.contains("nuget-interop"), "kotlinOutputDir must be under nuget-interop/")
    assertTrue(path.endsWith("kotlin"), "kotlinOutputDir must end with 'kotlin'")
  }

  @Test
  fun `nugetGenerateBindings reverseIrFile path matches nugetExtractApi reverseIrFile path`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val nugetExtractApi: NugetExtractApiTask =
      project.tasks.getByName("nugetExtractApi") as NugetExtractApiTask
    val nugetGenerateBindings: NugetGenerateBindingsTask =
      project.tasks.getByName("nugetGenerateBindings") as NugetGenerateBindingsTask

    assertTrue(
      nugetGenerateBindings.reverseIrFile.get().asFile.absolutePath ==
        nugetExtractApi.reverseIrFile.get().asFile.absolutePath,
      "nugetGenerateBindings.reverseIrFile must point at the same file as nugetExtractApi.reverseIrFile",
    )
  }
}
