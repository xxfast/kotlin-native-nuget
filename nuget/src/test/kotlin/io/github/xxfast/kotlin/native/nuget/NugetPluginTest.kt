package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.internal.project.ProjectInternal
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NugetPluginTest {
  private fun buildProject(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    return project
  }

  private fun Project.evaluate() {
    (this as ProjectInternal).evaluate()
  }

  @Test
  fun `nugetGen task is registered when dependencies block is non-empty`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3")
    }

    project.evaluate()

    assertNotNull(project.tasks.findByName("nugetGen"))
  }

  @Test
  fun `nugetGen task is not registered when dependencies block is empty`() {
    val project: Project = buildProject()

    project.evaluate()

    assertNull(project.tasks.findByName("nugetGen"))
  }

  @Test
  fun `nugetRestore depends on nugetGen`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3")
    }

    project.evaluate()

    val nugetGen: Task = project.tasks.getByName("nugetGen")
    val nugetRestore: Task = project.tasks.getByName("nugetRestore")
    val deps: Set<Task> = nugetRestore.taskDependencies.getDependencies(nugetRestore)

    assertTrue(deps.contains(nugetGen), "nugetRestore must depend on nugetGen")
  }

  @Test
  fun `nugetImport depends on nugetRestore`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3")
    }

    project.evaluate()

    val nugetRestore: Task = project.tasks.getByName("nugetRestore")
    val nugetImport: Task = project.tasks.getByName("nugetImport")
    val deps: Set<Task> = nugetImport.taskDependencies.getDependencies(nugetImport)

    assertTrue(deps.contains(nugetRestore), "nugetImport must depend on nugetRestore")
  }

  @Test
  fun `nugetGen csprojFile points into build nuget-interop directory`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3")
    }

    project.evaluate()

    val nugetGen: NugetGenTask = project.tasks.getByName("nugetGen") as NugetGenTask
    val path: String = nugetGen.csprojFile.get().asFile.absolutePath

    assertTrue(path.contains("nuget-interop"), "csprojFile must be under nuget-interop/")
    assertTrue(path.endsWith("interop.csproj"), "csprojFile must be named interop.csproj")
  }

  @Test
  fun `nugetRestore assetsFile points to obj project assets json`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Newtonsoft.Json", version = "13.0.3")
    }

    project.evaluate()

    val nugetRestore: NugetRestoreTask = project.tasks.getByName("nugetRestore") as NugetRestoreTask
    val path: String = nugetRestore.assetsFile.get().asFile.absolutePath

    assertTrue(path.contains("nuget-interop"), "assetsFile must be under nuget-interop/")
    assertTrue(path.endsWith("project.assets.json"), "assetsFile must be named project.assets.json")
  }
}
