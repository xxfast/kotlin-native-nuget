package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import io.github.xxfast.kotlin.native.nuget.processor.cir.NugetContext
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBoundInterface
import io.github.xxfast.kotlin.native.nuget.processor.forward.PublishedScope
import io.github.xxfast.kotlin.native.nuget.processor.forward.parseBoundTypesManifest
import io.github.xxfast.kotlin.native.nuget.processor.forward.parsePublishedScopes
import java.io.File

class NugetProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
    // ADR-088: the cross-pipeline manifest, written by `nugetGenerateBindings` (which
    // `kspKotlin{Target}` already dependsOn). Absent option or absent file means "nothing bound":
    // the option is only threaded when the project declares a `bind {}` dependency.
    val boundInterfaces: Map<String, ForwardBoundInterface> =
      environment.options["nuget.boundTypesManifest"]
        ?.takeIf { path -> path.isNotBlank() }
        ?.let(::File)
        ?.takeIf { file -> file.isFile }
        ?.let { file -> parseBoundTypesManifest(file.readText()) }
        .orEmpty()
        .associateBy { bound -> bound.kotlinName }

    // ADR-109: every OTHER forward publisher's export scope in this Gradle build, delivered as a
    // lazy `Provider<String>` KSP option (its body walks `rootProject.allprojects` after every
    // project is evaluated). Absent when this project does not publish, or when an older plugin
    // runs against this processor: then no duplicate-type warning can fire, as before.
    val rootNamespace: String = environment.options["nuget.namespace"] ?: "Interop"
    val publishedScopes: List<PublishedScope> =
      parsePublishedScopes(environment.options["nuget.publishedScopes"], rootNamespace)

    val context = NugetContext(
      libraryName = environment.options["nuget.libraryName"] ?: "library",
      rootNamespace = rootNamespace,
      rootPackage = environment.options["nuget.rootPackage"] ?: "",
      className = environment.options["nuget.className"] ?: "NativeBindings",
      includePackages = environment.options["nuget.includePackages"]
        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
      excludePackages = environment.options["nuget.excludePackages"]
        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
      boundPackages = environment.options["nuget.boundPackages"]
        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
      boundInterfaces = boundInterfaces,
      publishedScopes = publishedScopes,
    )

    return NugetProcessor(
      codeGenerator = environment.codeGenerator,
      logger = environment.logger,
      context = context,
    )
  }
}
