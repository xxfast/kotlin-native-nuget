package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The reverse bindings reach `nativeMain` through a srcDir with no producer task, so nothing
 * infers that the shared-source-set metadata compile needs them. Before this wiring a clean
 * `compileNativeMainKotlinMetadata` failed with `Unresolved reference` until
 * `nugetGenerateBindings` happened to run first.
 */
class NugetMetadataCompileWiringTest {
  private fun boundProject(): Project {
    val project: Project = ProjectBuilder.builder().withName("fixture").build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    // Two native targets, so `nativeMain` is a shared source set with its own metadata compile.
    kotlin.mingwX64()
    kotlin.linuxX64()
    project.extensions.getByType(NugetExtension::class.java).dependencies {
      it.dependency("Newtonsoft.Json", version = "13.0.3") { dependency ->
        dependency.bind { }
      }
    }
    (project as ProjectInternal).evaluate()
    return project
  }

  @Test
  fun `compileNativeMainKotlinMetadata depends on nugetGenerateBindings`() {
    val project: Project = boundProject()

    val metadata: Task = project.tasks.getByName("compileNativeMainKotlinMetadata")
    val generate: Task = project.tasks.getByName(NugetTaskNames.GENERATE_BINDINGS)
    val dependencies: Set<Task> = metadata.taskDependencies.getDependencies(metadata)

    assertTrue(
      generate in dependencies,
      "compileNativeMainKotlinMetadata must depend on ${generate.name}; was ${dependencies.map { it.name }}",
    )
  }
}
