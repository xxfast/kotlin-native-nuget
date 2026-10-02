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

class NugetPublishTaskWiringTest {
  private fun buildProject(): Project {
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

  private fun Project.publish(configure: (NugetRepositoriesScope) -> Unit) {
    extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.repositories { scope -> configure(scope) }
    }
  }

  private fun Task.dependencies(): Set<Task> = taskDependencies.getDependencies(this)

  @Test
  fun `repositories block registers named repositories keyed by name`() {
    val extension: NugetExtension = ProjectBuilder.builder().build()
      .objects.newInstance(NugetExtension::class.java)

    extension.publish {
      it.repositories {
        it.nuget("nugetOrg") { repository -> repository.url.set("https://api.nuget.org/v3/index.json") }
        it.nuget("github") { repository -> repository.url.set("https://nuget.pkg.github.com/xxfast/index.json") }
      }
    }

    val repositories = extension.publish.repositories
    assertEquals(setOf("nugetOrg", "github"), repositories.names)
    assertEquals(
      "https://api.nuget.org/v3/index.json",
      repositories.getByName("nugetOrg").url.get(),
    )
    assertEquals(
      "https://nuget.pkg.github.com/xxfast/index.json",
      repositories.getByName("github").url.get(),
    )
  }

  @Test
  fun `each repository gets its own publish task depending on nugetPack`() {
    val project: Project = buildProject()
    project.publish {
      it.nuget("nugetOrg") { repository -> repository.url.set("https://api.nuget.org/v3/index.json") }
      it.nuget("github") { repository -> repository.url.set("https://nuget.pkg.github.com/xxfast/index.json") }
    }

    project.evaluate()

    val nugetPack: Task = project.tasks.getByName("nugetPack")
    val nugetOrg: Task = assertNotNull(project.tasks.findByName("nugetPublishToNugetOrgRepository"))
    val github: Task = assertNotNull(project.tasks.findByName("nugetPublishToGithubRepository"))

    assertTrue(
      nugetOrg is NugetPublishTask,
      "nugetPublishToNugetOrgRepository must be a NugetPublishTask",
    )
    assertTrue(
      nugetPack in nugetOrg.dependencies(),
      "nugetPublishToNugetOrgRepository must depend on nugetPack",
    )
    assertTrue(
      nugetPack in github.dependencies(),
      "nugetPublishToGithubRepository must depend on nugetPack",
    )
  }

  @Test
  fun `aggregate nugetPublish depends on every repository task`() {
    val project: Project = buildProject()
    project.publish {
      it.nuget("nugetOrg") { repository -> repository.url.set("https://api.nuget.org/v3/index.json") }
      it.nuget("github") { repository -> repository.url.set("https://nuget.pkg.github.com/xxfast/index.json") }
    }

    project.evaluate()

    val nugetPublish: Task = assertNotNull(project.tasks.findByName("nugetPublish"))
    val deps: Set<Task> = nugetPublish.dependencies()
    assertTrue(project.tasks.getByName("nugetPublishToNugetOrgRepository") in deps)
    assertTrue(project.tasks.getByName("nugetPublishToGithubRepository") in deps)
  }

  @Test
  fun `no repositories registers no repository tasks but nugetPublish still exists`() {
    val project: Project = buildProject()
    project.publish { }

    project.evaluate()

    assertNotNull(project.tasks.findByName("nugetPublish"))
    assertTrue(project.tasks.withType(NugetPublishTask::class.java).isEmpty())
  }

  @Test
  fun `repository task carries the url and the nugetPack output file`() {
    val project: Project = buildProject()
    project.publish {
      it.nuget("nugetOrg") { repository -> repository.url.set("https://api.nuget.org/v3/index.json") }
    }

    project.evaluate()

    val task = project.tasks.getByName("nugetPublishToNugetOrgRepository") as NugetPublishTask
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
    val repository: NugetRepository = ProjectBuilder.builder().build()
      .objects.newInstance(NugetRepository::class.java, "nugetOrg")

    assertEquals("nugetOrgApiKey", repository.apiKeyProperty)
    assertEquals("nugetOrgUsername", repository.usernameProperty)
    assertEquals("nugetOrgPassword", repository.passwordProperty)
  }

  @Test
  fun `explicit apiKey wins over the Gradle property`() {
    val project: Project = buildProject()
    project.publish {
      it.nuget("github") {
        it.url.set("https://nuget.pkg.github.com/xxfast/index.json")
        it.apiKey.set(project.providers.provider { "explicit-key" })
      }
    }

    project.evaluate()

    val task = project.tasks.getByName("nugetPublishToGithubRepository") as NugetPublishTask
    assertEquals("explicit-key", task.apiKey.get())
  }

  @Test
  fun `missing apiKey does not fail configuration but fails when the task runs`() {
    val project: Project = buildProject()
    project.publish {
      it.nuget("nugetOrg") { repository -> repository.url.set("https://api.nuget.org/v3/index.json") }
    }

    project.evaluate()

    val task = project.tasks.getByName("nugetPublishToNugetOrgRepository") as NugetPublishTask
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
