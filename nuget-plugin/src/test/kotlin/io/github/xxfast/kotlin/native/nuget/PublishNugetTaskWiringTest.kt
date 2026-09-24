package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PublishNugetTaskWiringTest {
  private fun buildProject(): Project {
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

  private fun Project.publish(configure: NugetRepositoriesScope.() -> Unit) {
    extensions.getByType(NugetExtension::class.java).publish {
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
      repositories(configure)
    }
  }

  private fun Task.dependencies(): Set<Task> = taskDependencies.getDependencies(this)

  @Test
  fun `repositories block registers named repositories in order`() {
    val extension = NugetExtension()

    extension.publish {
      repositories {
        nuget("nugetOrg") { url = "https://api.nuget.org/v3/index.json" }
        nuget("github") { url = "https://nuget.pkg.github.com/xxfast/index.json" }
      }
    }

    val repositories: List<NugetRepository> = extension.publish!!.repositories
    assertEquals(listOf("nugetOrg", "github"), repositories.map { it.name })
    assertEquals("https://api.nuget.org/v3/index.json", repositories[0].url)
    assertEquals("https://nuget.pkg.github.com/xxfast/index.json", repositories[1].url)
  }

  @Test
  fun `each repository gets its own publish task depending on packNuget`() {
    val project: Project = buildProject()
    project.publish {
      nuget("nugetOrg") { url = "https://api.nuget.org/v3/index.json" }
      nuget("github") { url = "https://nuget.pkg.github.com/xxfast/index.json" }
    }

    project.evaluate()

    val packNuget: Task = project.tasks.getByName("packNuget")
    val nugetOrg: Task = assertNotNull(project.tasks.findByName("publishNugetToNugetOrgRepository"))
    val github: Task = assertNotNull(project.tasks.findByName("publishNugetToGithubRepository"))

    assertTrue(
      nugetOrg is PublishNugetTask,
      "publishNugetToNugetOrgRepository must be a PublishNugetTask",
    )
    assertTrue(
      packNuget in nugetOrg.dependencies(),
      "publishNugetToNugetOrgRepository must depend on packNuget",
    )
    assertTrue(
      packNuget in github.dependencies(),
      "publishNugetToGithubRepository must depend on packNuget",
    )
  }

  @Test
  fun `aggregate publishNuget depends on every repository task`() {
    val project: Project = buildProject()
    project.publish {
      nuget("nugetOrg") { url = "https://api.nuget.org/v3/index.json" }
      nuget("github") { url = "https://nuget.pkg.github.com/xxfast/index.json" }
    }

    project.evaluate()

    val publishNuget: Task = assertNotNull(project.tasks.findByName("publishNuget"))
    val deps: Set<Task> = publishNuget.dependencies()
    assertTrue(project.tasks.getByName("publishNugetToNugetOrgRepository") in deps)
    assertTrue(project.tasks.getByName("publishNugetToGithubRepository") in deps)
  }

  @Test
  fun `no repositories registers no repository tasks but publishNuget still exists`() {
    val project: Project = buildProject()
    project.publish { }

    project.evaluate()

    assertNotNull(project.tasks.findByName("publishNuget"))
    assertTrue(project.tasks.withType(PublishNugetTask::class.java).isEmpty())
  }

  @Test
  fun `repository task carries the url and the packNuget output file`() {
    val project: Project = buildProject()
    project.publish {
      nuget("nugetOrg") { url = "https://api.nuget.org/v3/index.json" }
    }

    project.evaluate()

    val task = project.tasks.getByName("publishNugetToNugetOrgRepository") as PublishNugetTask
    val expected: File =
      project.layout.buildDirectory.file("nuget/TestLibrary.1.0.0.nupkg").get().asFile
    assertEquals("nugetOrg", task.repositoryName.get())
    assertEquals("https://api.nuget.org/v3/index.json", task.repositoryUrl.get())
    assertEquals(expected.absolutePath, task.packageFile.get().asFile.absolutePath)
    assertFalse(task.dryRun.get())
    assertFalse(task.skipDuplicate.get())
  }

  // ProjectBuilder never feeds `providers.gradleProperty` (neither gradle.properties nor
  // `org.gradle.project.*` reach it), so the fallback is pinned by name here and by the run-time
  // missing-key message below.
  @Test
  fun `unset credentials resolve from repository-named Gradle properties`() {
    val repository = NugetRepository("nugetOrg")

    assertEquals("nugetOrgApiKey", repository.apiKeyProperty)
    assertEquals("nugetOrgUsername", repository.usernameProperty)
    assertEquals("nugetOrgPassword", repository.passwordProperty)
  }

  @Test
  fun `explicit apiKey wins over the Gradle property`() {
    val project: Project = buildProject()
    project.publish {
      nuget("github") {
        url = "https://nuget.pkg.github.com/xxfast/index.json"
        apiKey = project.providers.provider { "explicit-key" }
      }
    }

    project.evaluate()

    val task = project.tasks.getByName("publishNugetToGithubRepository") as PublishNugetTask
    assertEquals("explicit-key", task.apiKey.get())
  }

  @Test
  fun `missing apiKey does not fail configuration but fails when the task runs`() {
    val project: Project = buildProject()
    project.publish {
      nuget("nugetOrg") { url = "https://api.nuget.org/v3/index.json" }
    }

    project.evaluate()

    val task = project.tasks.getByName("publishNugetToNugetOrgRepository") as PublishNugetTask
    assertFalse(task.apiKey.isPresent)

    val file: File = task.packageFile.get().asFile
    file.parentFile.mkdirs()
    file.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))

    val error: Throwable = assertFailsWith<Throwable> { task.publish() }
    assertTrue(
      error.message.orEmpty().contains("nugetOrgApiKey"),
      "missing-key error must name the property to set, was: ${error.message}",
    )
  }
}
