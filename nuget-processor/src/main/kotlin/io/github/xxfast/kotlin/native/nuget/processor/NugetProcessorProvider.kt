package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import io.github.xxfast.kotlin.native.nuget.processor.cir.NugetContext
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBoundInterface
import io.github.xxfast.kotlin.native.nuget.processor.forward.parseBoundTypesManifest
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

    val context = NugetContext(
      libraryName = environment.options["nuget.libraryName"] ?: "library",
      rootNamespace = environment.options["nuget.namespace"] ?: "Interop",
      rootPackage = environment.options["nuget.rootPackage"] ?: "",
      className = environment.options["nuget.className"] ?: "NativeBindings",
      includePackages = environment.options["nuget.includePackages"]
        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
      excludePackages = environment.options["nuget.excludePackages"]
        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
      boundPackages = environment.options["nuget.boundPackages"]
        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
      boundInterfaces = boundInterfaces,
    )

    return NugetProcessor(
      codeGenerator = environment.codeGenerator,
      logger = environment.logger,
      context = context,
    )
  }
}
