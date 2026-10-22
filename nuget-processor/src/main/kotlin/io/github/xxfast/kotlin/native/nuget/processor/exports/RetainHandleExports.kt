package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSClassDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwnerTag
import io.github.xxfast.kotlin.native.nuget.processor.forward.objectCallbackResultOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.retainHandleExport

/**
 * ADR-215 lane A: the Kotlin half of `<lib>__retain_handle`, a fresh StableRef over the object a
 * live handle holds. The caller keeps its own handle; the new one is Kotlin's to release. Emitted
 * only when [objectCallbackResultOwner] names a plan, the same predicate the C# import is gated on.
 */
internal fun FileSpec.Builder.addRetainHandleExport(
  catalog: ForwardCallablePlanCatalog,
  symbols: ForwardSymbolTable,
  // ADR-215 lane C: the legacy suspend route's sighting, when no plan owns the export.
  legacyHost: KSFunctionDeclaration? = null,
  // ADR-233: a C#-implemented interface whose boxed slot result C# hands back through `WrapOwned`.
  bridgeHost: KSClassDeclaration? = null,
) {
  val planOwner: String? = catalog.objectCallbackResultOwner()
  if (planOwner == null && legacyHost == null && bridgeHost == null) return
  val owner: ForwardExportOwnerTag = if (planOwner != null) {
    ForwardExportOwnerTag(symbol = planOwner, role = "lambda result retain")
  } else if (legacyHost == null) {
    ForwardExportOwnerTag(declaration = bridgeHost, role = "bridge slot result retain")
  } else {
    ForwardExportOwnerTag(declaration = legacyHost, role = "lambda result retain")
  }
  val name: String = symbols.retainHandleExport()
  addFunction(
    FunSpec.builder("export_$name")
      .addAnnotation(
        cNameAnnotation(name, owner),
      )
      .addParameter("handle", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode("return NugetHandles.retain(handle.asStableRef<Any>().get())\n")
      .build(),
  )
}
