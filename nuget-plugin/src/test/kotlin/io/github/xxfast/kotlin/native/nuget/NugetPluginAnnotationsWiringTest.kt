package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-179: the plugin adds `io.github.xxfast:nuget-annotations` to `commonMainApi`, so
 * `@CSharpName` resolves in `commonMain` with nothing for the author to add. A mixed jvm + native
 * consumer must still configure, since the annotation reaches every target through `commonMain`.
 */
class NugetPluginAnnotationsWiringTest {

  private val coordinate: String = "io.github.xxfast:nuget-annotations:$PLUGIN_VERSION"

  @Test
  fun `the annotations module lands on commonMainApi of a mixed jvm and native consumer`() {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")

    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.jvm()
    kotlin.macosArm64()
    (project as ProjectInternal).evaluate()

    val names: List<String> = project.configurations.getByName("commonMainApi").allDependencies
      .map { "${it.group.orEmpty()}:${it.name}:${it.version.orEmpty()}" }
    assertTrue(coordinate in names, "expected $coordinate on commonMainApi; got $names")
  }
}
