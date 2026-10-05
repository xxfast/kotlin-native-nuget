package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-179: the plugin adds `io.github.xxfast:nuget-annotations` to `commonMainApi`, so
 * `@CSharpName` resolves in `commonMain` with nothing for the author to add. A mixed jvm + native
 * consumer must still configure, since the annotation reaches every target through `commonMain`.
 *
 * Issue #464: the annotations-only plugin does that one wiring for a dependency module that does
 * not apply the main plugin, and the main plugin applies it rather than wiring it again.
 */
class NugetPluginAnnotationsWiringTest {

  private val coordinate: String = "io.github.xxfast:nuget-annotations:$PLUGIN_VERSION"

  private fun configure(vararg ids: String): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    ids.forEach { id -> project.plugins.apply(id) }

    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.jvm()
    kotlin.macosArm64()
    (project as ProjectInternal).evaluate()
    return project
  }

  private fun Project.commonMainApi(): List<String> =
    configurations.getByName("commonMainApi").allDependencies
      .map { "${it.group.orEmpty()}:${it.name}:${it.version.orEmpty()}" }

  @Test
  fun `the annotations module lands on commonMainApi of a mixed jvm and native consumer`() {
    val project: Project = configure("io.github.xxfast.kotlin.native.nuget")

    val names: List<String> = project.commonMainApi()
    assertTrue(coordinate in names, "expected $coordinate on commonMainApi; got $names")
  }

  @Test
  fun `the annotations plugin alone adds the annotations module and nothing else`() {
    val project: Project = configure("io.github.xxfast.kotlin.native.nuget.annotations")

    val names: List<String> = project.commonMainApi()
    assertTrue(coordinate in names, "expected $coordinate on commonMainApi; got $names")
    assertNull(project.extensions.findByName("nuget"), "the annotations plugin registered `nuget`")
    assertFalse(
      project.pluginManager.hasPlugin("com.google.devtools.ksp"),
      "the annotations plugin applied KSP",
    )
  }

  @Test
  fun `applying both plugins adds the annotations module once`() {
    val project: Project = configure(
      "io.github.xxfast.kotlin.native.nuget.annotations",
      "io.github.xxfast.kotlin.native.nuget",
    )

    val names: List<String> = project.commonMainApi()
    assertEquals(1, names.count { it == coordinate }, "commonMainApi: $names")
  }
}
