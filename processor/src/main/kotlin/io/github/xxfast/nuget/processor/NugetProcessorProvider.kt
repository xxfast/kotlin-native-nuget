package io.github.xxfast.nuget.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import io.github.xxfast.nuget.processor.cir.NugetContext

class NugetProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
    val context = NugetContext(
      libraryName = environment.options["nuget.libraryName"] ?: "library",
      rootNamespace = environment.options["nuget.namespace"] ?: "Interop",
      rootPackage = environment.options["nuget.rootPackage"] ?: "",
      className = environment.options["nuget.className"] ?: "NativeBindings",
    )

    return NugetProcessor(
      codeGenerator = environment.codeGenerator,
      logger = environment.logger,
      context = context,
    )
  }
}
