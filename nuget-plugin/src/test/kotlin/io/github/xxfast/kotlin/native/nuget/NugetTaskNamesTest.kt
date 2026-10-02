package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-182 sections 2 and 4: packaging and publishing tasks are verb-first (`packNuget`,
 * `publishNuget`), as Kotlin's Gradle plugin names producing tasks; tool steps are `nuget`-prefixed.
 * Only `nugetGen` was renamed, as a clean break.
 */
class NugetTaskNamesTest {
  private fun everyTaskProject(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    project.extensions.getByType(KotlinMultiplatformExtension::class.java)
      .mingwX64 { binaries { sharedLib { baseName = "test" } } }

    val nuget: NugetExtension = project.extensions.getByType(NugetExtension::class.java)
    nuget.publish {
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.snapshot.set(true)
      it.repositories { scope ->
        scope.nuget("local") { repository -> repository.url.set("https://example.invalid/v3") }
      }
    }
    nuget.dependencies {
      it.dependency("Acme") { dep ->
        dep.version.set("1.0.0")
        dep.bind { bind -> bind.includeNamespaces("Acme.Core") }
      }
    }
    (project as ProjectInternal).evaluate()
    return project
  }

  @Test
  fun `packaging and publishing tasks are verb-first and tool steps are nuget-prefixed`() {
    val project: Project = everyTaskProject()
    val expected: List<String> = listOf(
      NugetTaskNames.GENERATE_RESTORE_PROJECT,
      NugetTaskNames.RESTORE,
      NugetTaskNames.IMPORT,
      NugetTaskNames.EXTRACT_API,
      NugetTaskNames.GENERATE_BINDINGS,
      NugetTaskNames.GENERATE_SHIMS,
      NugetTaskNames.REPORT_DIAGNOSTICS,
      NugetTaskNames.COMPILE_INTEROP,
      NugetTaskNames.PACK,
      NugetTaskNames.PUBLISH,
      NugetTaskNames.publishTo("local"),
      NugetTaskNames.SNAPSHOT_VERSION,
      NugetTaskNames.SNAPSHOT_VERSION_PROPS,
    )
    expected.forEach { name -> assertNotNull(project.tasks.findByName(name), "$name is not registered") }
    assertEquals("packNuget", NugetTaskNames.PACK)
    assertEquals("publishNuget", NugetTaskNames.PUBLISH)
    assertEquals("publishNugetToLocalRepository", NugetTaskNames.publishTo("local"))
    assertEquals("nugetSnapshotVersion", NugetTaskNames.SNAPSHOT_VERSION)
    assertEquals("nugetSnapshotVersionProps", NugetTaskNames.SNAPSHOT_VERSION_PROPS)
    assertEquals("nugetGenerateRestoreProject", NugetTaskNames.GENERATE_RESTORE_PROJECT)

    val packaging = Regex("^(pack|publish)Nuget([A-Z][A-Za-z]*)?$")
    val toolStep = Regex("^nuget(?!Pack|Publish)[A-Z][A-Za-z]*$")
    val ours: List<String> = project.tasks
      .filter { it.group == "nuget" || it.name.contains("nuget", ignoreCase = true) }
      .map { it.name }
    assertEquals(expected.toSet(), ours.toSet())
    ours.forEach { name ->
      val packages: Boolean = name.startsWith("pack") || name.startsWith("publish")
      val rule: Regex = if (packages) packaging else toolStep
      assertTrue(rule.matches(name), "$name breaks the task naming rule")
    }
  }

  // Clean break for nugetGen, no tombstone: a stub would have to be deleted in 0.10.0, which allows
  // no breaks. The nuget-prefixed packaging names were never released and must not come back.
  @Test
  fun `nugetGen and the unreleased nuget-prefixed packaging names are not registered`() {
    val project: Project = everyTaskProject()
    listOf(
      "nugetGen",
      "nugetPack",
      "nugetPublish",
      "nugetPublishToLocalRepository",
      "nugetGenerateSnapshotVersion",
      "nugetGenerateSnapshotVersionProps",
    ).forEach { old -> assertNull(project.tasks.findByName(old), "$old is still registered") }
  }

  @Test
  fun `task classes back their tasks`() {
    val project: Project = everyTaskProject()
    assertTrue(project.tasks.getByName(NugetTaskNames.PACK) is PackNugetTask)
    assertTrue(project.tasks.getByName(NugetTaskNames.publishTo("local")) is PublishNugetTask)
    assertTrue(
      project.tasks.getByName(NugetTaskNames.GENERATE_RESTORE_PROJECT) is NugetGenerateRestoreProjectTask
    )
    assertTrue(
      project.tasks.getByName(NugetTaskNames.SNAPSHOT_VERSION) is NugetSnapshotVersionTask
    )
    assertTrue(
      project.tasks.getByName(NugetTaskNames.SNAPSHOT_VERSION_PROPS)
        is NugetSnapshotVersionPropsTask
    )
  }

  @Test
  fun `bind filters are named for namespaces and feed nugetExtractApi`() {
    val project: Project = everyTaskProject()
    val bind: NugetBindConfig =
      project.extensions.getByType(NugetExtension::class.java).dependencies.single().bind
    bind.excludeNamespaces("Acme.Core.Internal")

    assertEquals(listOf("Acme.Core"), bind.includeNamespaces.get())
    assertEquals(listOf("Acme.Core.Internal"), bind.excludeNamespaces.get())

    val extract = project.tasks.getByName(NugetTaskNames.EXTRACT_API) as NugetExtractApiTask
    assertEquals(mapOf("Acme" to listOf("Acme.Core")), extract.namespaceIncludes.get())
    assertEquals(mapOf("Acme" to listOf("Acme.Core.Internal")), extract.namespaceExcludes.get())
  }

  // The old verbs cannot be called from here (ERROR-level deprecation is a compile error), so the
  // annotation is read reflectively; the migrated fixture build scripts prove the compiler side.
  @Test
  fun `the old bind include and exclude are ERROR-level deprecations pointing at the new names`() {
    listOf("include" to "includeNamespaces", "exclude" to "excludeNamespaces").forEach { (old, new) ->
      val deprecated: Deprecated = assertNotNull(
        NugetBindConfig::class.java
          .getDeclaredMethod(old, Array<String>::class.java)
          .getAnnotation(Deprecated::class.java),
        "$old has no @Deprecated",
      )
      assertEquals(DeprecationLevel.ERROR, deprecated.level)
      assertEquals("$new(*namespace)", deprecated.replaceWith.expression)
    }
  }
}
