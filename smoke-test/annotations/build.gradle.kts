import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation

// Issue #464: a dependency module that applies ONLY the annotations plugin, the way a module that
// does not apply the main plugin gets `@CSharpName` (ADR-179). Its own project, because the root
// here applies the main plugin, which applies this one too and adds the dependency on its own.
plugins {
  kotlin("multiplatform")
  id("io.github.xxfast.kotlin.native.nuget.annotations")
}

kotlin {
  macosArm64()
}

// The assertion. Resolving the native main compile classpath forces the plugin's maven-coordinate
// fallback to fetch `nuget-annotations` from the local repo, the same way
// `verifyProcessorResolvesByCoordinate` proves the processor's.
val verifyAnnotationsPluginResolvesByCoordinate by tasks.registering {
  group = "verification"
  description = "Fails unless the annotations plugin alone resolves nuget-annotations by coordinate"

  val annotationKlibs: Provider<Set<File>> = providers.provider {
    val compilation: KotlinCompilation<*> =
      kotlin.targets.getByName("macosArm64").compilations.getByName("main")
    configurations.getByName(compilation.compileDependencyConfigurationName).files
      .filter { it.name.startsWith("nuget-annotations") }
      .toSet()
  }

  doLast {
    val klibs: Set<File> = annotationKlibs.get()
    check(klibs.isNotEmpty()) {
      "nuget-annotations did not resolve from a maven coordinate. The annotations plugin marker or " +
        "the fallback in NugetAnnotationsPlugin.kt is broken."
    }
    logger.lifecycle("Resolved nuget-annotations by coordinate: ${klibs.joinToString { it.name }}")
  }
}
