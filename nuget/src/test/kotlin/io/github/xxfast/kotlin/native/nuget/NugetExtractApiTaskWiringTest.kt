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
import kotlin.test.assertEquals

class NugetExtractApiTaskWiringTest {
  private fun buildProject(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    return project
  }

  private fun Project.evaluate() {
    (this as ProjectInternal).evaluate()
  }

  @Test
  fun `nugetExtractApi is registered when at least one dependency has bind block`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    assertNotNull(project.tasks.findByName("nugetExtractApi"))
  }

  @Test
  fun `nugetExtractApi is not registered when no dependencies have bind block`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3")
    }

    project.evaluate()

    assertNull(project.tasks.findByName("nugetExtractApi"))
  }

  @Test
  fun `nugetExtractApi assetsFile path matches nugetRestore assetsFile path`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val nugetRestore: NugetRestoreTask =
      project.tasks.getByName("nugetRestore") as NugetRestoreTask
    val nugetExtractApi: NugetExtractApiTask =
      project.tasks.getByName("nugetExtractApi") as NugetExtractApiTask

    assertEquals(
      nugetRestore.assetsFile.get().asFile.absolutePath,
      nugetExtractApi.assetsFile.get().asFile.absolutePath,
    )
  }

  @Test
  fun `nugetExtractApi reverseIrFile is under nuget-interop directory`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val nugetExtractApi: NugetExtractApiTask =
      project.tasks.getByName("nugetExtractApi") as NugetExtractApiTask
    val path: String = nugetExtractApi.reverseIrFile.get().asFile.absolutePath

    assertTrue(path.contains("nuget-interop"), "reverseIrFile must be under nuget-interop/")
    assertTrue(path.endsWith("reverse-ir.json"), "reverseIrFile must be named reverse-ir.json")
  }

  @Test
  fun `nugetImport depends on nugetExtractApi when bound packages exist`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val nugetExtractApi: Task = project.tasks.getByName("nugetExtractApi")
    val nugetImport: Task = project.tasks.getByName("nugetImport")
    val deps: Set<Task> = nugetImport.taskDependencies.getDependencies(nugetImport)

    assertTrue(deps.contains(nugetExtractApi), "nugetImport must depend on nugetExtractApi")
  }

  @Test
  fun `nugetImport does not depend on nugetExtractApi when no bound packages`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3")
    }

    project.evaluate()

    assertNull(project.tasks.findByName("nugetExtractApi"))

    val nugetImport: Task = project.tasks.getByName("nugetImport")
    val deps: Set<Task> = nugetImport.taskDependencies.getDependencies(nugetImport)

    assertFalse(
      deps.any { it.name == "nugetExtractApi" },
      "nugetImport must not depend on nugetExtractApi when no packages are bound",
    )
  }

  @Test
  fun `nugetExtractApi boundPackageIds contains only packages with bind block`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
    }

    project.evaluate()

    val nugetExtractApi: NugetExtractApiTask =
      project.tasks.getByName("nugetExtractApi") as NugetExtractApiTask

    assertEquals(listOf("Newtonsoft.Json"), nugetExtractApi.boundPackageIds.get())
  }

  @Test
  fun `nugetExtractApi boundPackageIds excludes resolve-only dependencies`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3") {
        bind { }
      }
      dependency("Serilog", version = "3.1.1")
    }

    project.evaluate()

    val nugetExtractApi: NugetExtractApiTask =
      project.tasks.getByName("nugetExtractApi") as NugetExtractApiTask
    val ids: List<String> = nugetExtractApi.boundPackageIds.get()

    assertTrue(ids.contains("Newtonsoft.Json"), "bound package must be in boundPackageIds")
    assertFalse(ids.contains("Serilog"), "resolve-only package must not be in boundPackageIds")
  }
}
