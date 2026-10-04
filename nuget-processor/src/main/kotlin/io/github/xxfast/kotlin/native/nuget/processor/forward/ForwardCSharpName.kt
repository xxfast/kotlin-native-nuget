package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

internal const val CSHARP_NAME_ANNOTATION: String =
  "io.github.xxfast.kotlin.native.nuget.annotations.CSharpName"

private val CSHARP_IDENTIFIER: Regex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

/** ADR-179: this declaration's own `@CSharpName` argument, read by qualified name, or null. */
internal fun KSDeclaration.ownCSharpName(): String? = annotations
  .firstOrNull { annotation ->
    annotation.annotationType.resolve().declaration.qualifiedName?.asString() ==
        CSHARP_NAME_ANNOTATION
  }
  ?.arguments
  ?.firstOrNull { argument -> argument.name?.asString() == "name" || argument.name == null }
  ?.value as? String

/** The root of this member's override chain, the declaration an inherited name is read from. */
private fun KSDeclaration.overrideRoot(): KSDeclaration {
  val overridee: KSDeclaration? = when (this) {
    is KSFunctionDeclaration -> findOverridee()
    is KSPropertyDeclaration -> findOverridee()
    else -> null
  }
  return generateSequence(overridee) { declaration ->
    when (declaration) {
      is KSFunctionDeclaration -> declaration.findOverridee()
      is KSPropertyDeclaration -> declaration.findOverridee()
      else -> null
    }
  }.lastOrNull() ?: this
}

/**
 * ADR-179: the author-declared C# name of this member: the root of its override chain's
 * `@CSharpName`, else its own. Verbatim, so callers that append `Async` must not when this answers.
 */
internal fun KSDeclaration.declaredCSharpName(): String? =
  overrideRoot().ownCSharpName() ?: ownCSharpName()

/**
 * ADR-179: the member's C# name, unescaped: declared when present, else PascalCase. Unescaped
 * because plans and CIR member names must not carry a C#-escaped name: the name is also the stem
 * of derived identifiers, and the renderer escapes it through each CIR member's `identifier`.
 */
internal fun KSDeclaration.csharpMemberName(): String =
  declaredCSharpName() ?: simpleName.asString().replaceFirstChar { it.uppercase() }

/**
 * ADR-179: a `suspend` member's C# name, unescaped like [csharpMemberName]: declared verbatim,
 * else `<Pascal>Async`.
 */
internal fun KSDeclaration.csharpAsyncMemberName(): String =
  declaredCSharpName() ?: (simpleName.asString().replaceFirstChar { it.uppercase() } + "Async")

/**
 * ADR-179: validates every `@CSharpName` in this round. An argument that is not a C# identifier is
 * fatal, and so is an override whose declared name disagrees with its root's (or declares one when
 * its root does not), since that would break the C# interface/base implementation.
 */
internal fun validateCSharpNames(resolver: Resolver, logger: KSPLogger) {
  val diagnostics: List<ForwardDiagnostic> = resolver
    .getSymbolsWithAnnotation(CSHARP_NAME_ANNOTATION)
    .filterIsInstance<KSDeclaration>()
    .mapNotNull { declaration ->
      val own: String = declaration.ownCSharpName() ?: return@mapNotNull null
      val kotlin: String =
        declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
      if (!CSHARP_IDENTIFIER.matches(own)) {
        return@mapNotNull ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_NAME_INVALID,
          symbol = declaration,
          declaration = kotlin,
          reason = "@CSharpName(\"$own\") is not a C# identifier",
          hint = "use letters, digits and '_' only, not starting with a digit",
          owner = null,
        )
      }
      val root: KSDeclaration = declaration.overrideRoot()
      if (root == declaration) return@mapNotNull null
      val inherited: String? = root.ownCSharpName()
      if (inherited == own) return@mapNotNull null
      val rootName: String = root.qualifiedName?.asString() ?: root.simpleName.asString()
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.ERROR_CSHARP_NAME_OVERRIDE_MISMATCH,
        symbol = declaration,
        declaration = kotlin,
        reason = "@CSharpName(\"$own\") on an override disagrees with the overridden '$rootName' " +
            (if (inherited == null) "(no @CSharpName)" else "(@CSharpName(\"$inherited\"))") +
            ", which would break the C# implementation of it",
        hint = "put @CSharpName on '$rootName'; overrides inherit it",
        owner = null,
      )
    }
    .toList()
  ForwardDiagnosticSink.emit(diagnostics, logger)
}
