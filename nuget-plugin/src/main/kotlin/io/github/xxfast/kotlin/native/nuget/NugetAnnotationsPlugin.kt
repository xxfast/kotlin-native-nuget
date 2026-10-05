package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Adds `nuget-annotations` (`@CSharpName`, ADR-179) to `commonMainApi` and nothing else, so a
 * dependency module that does not apply [NugetPlugin] can annotate its members without spelling a
 * coordinate (issue #464). [NugetPlugin] applies it too, so there is one wiring.
 */
public class NugetAnnotationsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") { _ ->
      // ADR-179: `@CSharpName` lives in a pure-common module so an author writes it in
      // `commonMain` with nothing else to add. `api`, so a downstream KMP consumer compiling
      // against this klib never sees an unresolved annotation class. No `export()`: it has no
      // `@CName`. Same local-vs-published fallback as the runtime.
      val annotationsDep: Any = project.findProject(":nuget-annotations")
        ?: "io.github.xxfast:nuget-annotations:$PLUGIN_VERSION"
      project.dependencies.add("commonMainApi", annotationsDep)
    }
  }
}
