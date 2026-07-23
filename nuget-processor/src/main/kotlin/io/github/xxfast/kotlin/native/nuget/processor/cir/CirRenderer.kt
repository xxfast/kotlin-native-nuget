package io.github.xxfast.kotlin.native.nuget.processor.cir

class CirRenderer {
  fun render(file: CirFile): String = buildString {
    appendLine("#nullable enable")
    appendLine()

    for (using in file.usings) {
      appendLine("using $using;")
    }

    appendLine()

    for (namespace in file.namespaces) {
      renderNamespace(namespace)
    }
  }

  private fun StringBuilder.renderNamespace(namespace: CirNamespace) {
    appendLine("namespace ${namespace.name}")
    appendLine("{")

    for (declaration in namespace.declarations) {
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
        is CirFlowHelper -> renderFlowHelper(declaration)
        is CirStateFlowHandleHelper -> renderStateFlowHandleHelper(declaration)
        is CirCallbackDelegateHelper -> renderCallbackDelegateHelper(declaration)
        is CirSubscriptionHelper -> renderSubscriptionHelper(declaration)
        is CirStaticClass -> renderStaticClass(declaration)
        is CirInterface -> renderInterface(declaration)
        is CirClass -> renderClass(declaration)
        is CirGenericClass -> renderGenericClass(declaration)
        is CirEnum -> renderEnum(declaration)
        is CirSealedClass -> renderSealedClass(declaration)
        is CirObject -> renderObject(declaration)
        is CirValueClass -> renderValueClass(declaration)
      }
    }

    appendLine("}")
    appendLine()
  }
}
