package io.github.xxfast.nuget.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class CSharpBindingsProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
    val libraryName: String = environment.options["nuget.libraryName"] ?: "library"
    val namespace: String = environment.options["nuget.namespace"] ?: "Interop"
    val className: String = environment.options["nuget.className"] ?: "NativeBindings"

    return CSharpBindingsProcessor(
      codeGenerator = environment.codeGenerator,
      logger = environment.logger,
      libraryName = libraryName,
      namespace = namespace,
      className = className,
    )
  }
}
