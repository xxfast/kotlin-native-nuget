package io.github.xxfast.kotlin.native.nuget.processor.cir

class CirRenderer {
  fun render(file: CirFile): String =
    renderFile(file).withUtf8StringParameters().checkSpellableInCSharp()

  private fun renderFile(file: CirFile): String = buildString {
    appendLine("#nullable enable")
    appendLine()

    for (using in file.usings) {
      appendLine("using $using;")
    }

    appendLine()

    // ADR-094: one internal interface at the global namespace, so every generated namespace sees it
    // unqualified. Every class that declares `internal IntPtr _handle` implements it explicitly,
    // which is how erased-generic code extracts a handle without `GetField("_handle")`.
    appendLine("internal interface INugetHandle")
    appendLine("{")
    appendLine("    IntPtr Handle { get; }")
    appendLine("}")
    appendLine()
    // ROADMAP line 26: `Interop.cs` compiles into the consumer, so `internal` does not hide the
    // handle constructor from overload resolution. A trailing `out` parameter of this type is one
    // no ordinary call binds, so `new Tag('O')` and `new Circle(5)` always reach the public one.
    appendLine("/// <summary>")
    appendLine("/// Marks internal constructors that adopt an existing Kotlin handle, so no")
    appendLine("/// ordinary call binds one accidentally.")
    appendLine("/// </summary>")
    appendLine("internal readonly struct NugetHandleTag")
    appendLine("{")
    appendLine("}")
    appendLine()

    for (namespace in file.namespaces) {
      renderNamespace(namespace)
    }
  }

  private fun StringBuilder.renderNamespace(namespace: CirNamespace) {
    appendLine("namespace ${namespace.name}")
    appendLine("{")

    for (declaration in namespace.declarations) {
      renderDeclaration(declaration)
    }

    // ADR-133: an extension class cannot be nested (CS1109), so a NESTED enum's extensions are
    // hoisted here, named for the whole chain (`OwnerKindExtensions`), while the enum itself
    // renders inside its owner's block. A top-level enum still renders its own, in `renderEnum`.
    namespace.declarations.forEach { declaration ->
      nestedEnumsOf(declaration)
        .filter { it.properties.isNotEmpty() }
        .forEach { nestedEnum ->
          appendLine()
          renderEnumExtensions(nestedEnum)
        }
    }

    appendLine("}")
    appendLine()
  }
}

/**
 * ADR-133/ADR-134: the declarations nested inside [declaration], whatever owner kind it is. A
 * missing arm here is SILENT: a nested enum under that owner keeps its declaration and loses the
 * namespace-level extension class its properties are read through (CS1109 forbids nesting one).
 */
private fun nestedDeclarationsOf(declaration: CirDeclaration): List<CirDeclaration> =
  when (declaration) {
    is CirClass -> declaration.nestedDeclarations
    is CirObject -> declaration.nestedDeclarations
    // ADR-134's new owner kinds. A sealed base owns both its own children and every arm's.
    is CirInterface -> declaration.nestedDeclarations
    is CirSealedClass ->
      declaration.nestedDeclarations + declaration.subclasses.flatMap { it.nestedDeclarations }
    else -> emptyList()
  }

/** ADR-133: every nested `CirEnum` under [declaration], at any depth. */
private fun nestedEnumsOf(declaration: CirDeclaration): List<CirEnum> =
  nestedDeclarationsOf(declaration).flatMap { nested ->
    listOfNotNull(nested as? CirEnum) + nestedEnumsOf(nested)
  }

/**
 * ADR-133: the one dispatch over [CirDeclaration], called at namespace level and recursively from
 * [renderClass] / [renderObject] for a nested declaration.
 *
 * [nested] reaches only the enum, whose extension class is hoisted to namespace level by
 * `renderNamespace` (CS1109); every other kind renders identically wherever it lives and is
 * re-indented by [renderNestedDeclarations].
 */
internal fun StringBuilder.renderDeclaration(declaration: CirDeclaration, nested: Boolean = false) {
  when (declaration) {
    is CirMarshalHelper -> renderMarshalHelper(declaration)
    CirOptionalHelper -> renderOptionalHelper()
    is CirListHelper -> renderListHelper(declaration)
    is CirBytesHelper -> renderBytesHelper(declaration)
    is CirMapHelper -> renderMapHelper(declaration)
    is CirSetHelper -> renderSetHelper(declaration)
    is CirFuncNativeHelper -> renderFuncNativeHelper(declaration)
    is CirFuncHelper -> renderFuncHelper(declaration)
    is CirSuspendFuncNativeHelper -> renderSuspendFuncNativeHelper(declaration)
    is CirSuspendFuncHelper -> renderSuspendFuncHelper(declaration)
    is CirAsyncHelper -> renderAsyncHelper(declaration)
    is CirScopeHelper -> renderScopeHelper(declaration)
    is CirJobHelper -> renderJobHelper(declaration)
    is CirErrorHelper -> renderErrorHelper(declaration)
    is CirRuntimeHelper -> renderRuntimeHelper(declaration)
    is CirFlowHelper -> renderFlowHelper(declaration)
    is CirStateFlowHandleHelper -> renderStateFlowHandleHelper(declaration)
    is CirCallbackDelegateHelper -> renderCallbackDelegateHelper(declaration)
    is CirSubscriptionHelper -> renderSubscriptionHelper(declaration)
    is CirBridgeHelper -> renderBridgeHelper(declaration)
    is CirStaticClass -> renderStaticClass(declaration)
    is CirInterface -> renderInterface(declaration)
    is CirClass -> renderClass(declaration)
    is CirEnum -> renderEnum(declaration, nested = nested)
    is CirSealedClass -> renderSealedClass(declaration)
    is CirObject -> renderObject(declaration)
    is CirValueClass -> renderValueClass(declaration)
  }
}

/**
 * ADR-133: renders each nested declaration one level in, through the same `indentNestedBody()`
 * re-indent ADR-009 uses for a sealed arm. Called from the owner's own block.
 */
internal fun StringBuilder.renderNestedDeclarations(declarations: List<CirDeclaration>) {
  declarations.forEach { declaration ->
    appendLine()
    append(buildString { renderDeclaration(declaration, nested = true) }.indentNestedBody())
  }
}

// Issue #223: `$` is legal in C# source only inside an interpolated string, so string literals are
// stripped before the scan. A `$` anywhere else is an identifier no C# compiler can read, whatever
// route emitted it.
private val CSHARP_STRING_LITERAL = Regex("[@\\$]?\"(?:[^\"\\\\]|\\\\.)*\"")

private val CSHARP_DOLLAR_IDENTIFIER = Regex("[\\w\\$]*\\$[\\w\\$]*")

/**
 * Issue #223: the single structural guard that no emitted identifier contains `$`.
 *
 * kotlinx.serialization's compiler plugin synthesizes a nested `$serializer` object on every
 * `@Serializable` declaration, and the ADR-134 nested-declaration walk used to declare it. The walk
 * refuses synthetic declarations now; this check is what makes any OTHER route reaching such a name
 * fail at generation time rather than in the consumer's `dotnet build`, where it reads as six parse
 * errors per site with nothing naming the Kotlin declaration behind them.
 */
private fun String.checkSpellableInCSharp(): String {
  val offending: String? = lineSequence()
    // ADR-150: a `///` doc comment carries the author's prose, where `$` is legal C# and common
    // (a KDoc that quotes a Kotlin template, `"${'$'}name"`, is prose, not an identifier).
    .filterNot { line -> line.trimStart().startsWith("//") }
    .map { line -> CSHARP_STRING_LITERAL.replace(line, "") }
    .firstNotNullOfOrNull { line -> CSHARP_DOLLAR_IDENTIFIER.find(line)?.value }

  check(offending == null) {
    "Generated C# declares the identifier `$offending`, which contains a '\$'. A name that " +
      "cannot be spelled in C# is a generator defect: the declaration behind it is " +
      "compiler-synthesized and must not be walked, and must not be renamed into something legal."
  }

  return this
}
