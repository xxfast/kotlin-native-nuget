package io.github.xxfast.kotlin.native.nuget.processor.cir

class CirRenderer {
  fun render(file: CirFile): String = renderFile(file).withUtf8StringParameters()

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

/** ADR-133: every nested `CirEnum` under [declaration], at any depth. */
private fun nestedEnumsOf(declaration: CirDeclaration): List<CirEnum> = when (declaration) {
  is CirClass -> declaration.nestedDeclarations.flatMap { nested ->
    listOfNotNull(nested as? CirEnum) + nestedEnumsOf(nested)
  }
  is CirObject -> declaration.nestedDeclarations.flatMap { nested ->
    listOfNotNull(nested as? CirEnum) + nestedEnumsOf(nested)
  }
  else -> emptyList()
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
    is CirListHelper -> renderListHelper(declaration)
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
    is CirGenericClass -> renderGenericClass(declaration)
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
