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

/** ADR-182 sections 2 and 4: one `nuget<Verb><Object>` scheme, and the old names are gone. */
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
  fun `every task the plugin registers follows the nuget verb object scheme`() {
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
      NugetTaskNames.GENERATE_SNAPSHOT_VERSION,
      NugetTaskNames.GENERATE_SNAPSHOT_VERSION_PROPS,
    )
    expected.forEach { name -> assertNotNull(project.tasks.findByName(name), "$name is not registered") }
    assertEquals("nugetPublishToLocalRepository", NugetTaskNames.publishTo("local"))

    val scheme = Regex("^nuget(Generate|Restore|Import|Extract|Report|Compile|Pack|Publish)[A-Za-z]*$")
    val ours: List<String> = project.tasks
      .filter { it.group == "nuget" || it.name.contains("nuget", ignoreCase = true) }
      .map { it.name }
    assertEquals(expected.toSet(), ours.toSet())
    ours.forEach { name -> assertTrue(scheme.matches(name), "$name breaks the task naming scheme") }
  }

  // Clean break, no tombstones: a stub under the old name would have to be deleted in 0.10.0,
  // which allows no breaks.
  @Test
  fun `the pre-0_9 task names are not registered`() {
    val project: Project = everyTaskProject()
    listOf(
      "nugetGen",
      "packNuget",
      "publishNuget",
      "publishNugetToLocalRepository",
      "nugetSnapshotVersion",
      "nugetSnapshotVersionProps",
    ).forEach { old -> assertNull(project.tasks.findByName(old), "$old is still registered") }
  }

  @Test
  fun `renamed task classes back the renamed tasks`() {
    val project: Project = everyTaskProject()
    assertTrue(project.tasks.getByName(NugetTaskNames.PACK) is NugetPackTask)
    assertTrue(project.tasks.getByName(NugetTaskNames.publishTo("local")) is NugetPublishTask)
    assertTrue(
      project.tasks.getByName(NugetTaskNames.GENERATE_RESTORE_PROJECT) is NugetGenerateRestoreProjectTask
    )
    assertTrue(
      project.tasks.getByName(NugetTaskNames.GENERATE_SNAPSHOT_VERSION) is NugetGenerateSnapshotVersionTask
    )
    assertTrue(
      project.tasks.getByName(NugetTaskNames.GENERATE_SNAPSHOT_VERSION_PROPS)
        is NugetGenerateSnapshotVersionPropsTask
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
