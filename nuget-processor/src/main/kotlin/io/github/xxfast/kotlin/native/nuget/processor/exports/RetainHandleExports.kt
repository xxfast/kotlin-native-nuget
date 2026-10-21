package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
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
) {
  val owner: String = catalog.objectCallbackResultOwner() ?: return
  val name: String = symbols.retainHandleExport()
  addFunction(
    FunSpec.builder("export_$name")
      .addAnnotation(
        cNameAnnotation(name, ForwardExportOwnerTag(symbol = owner, role = "lambda result retain")),
      )
      .addParameter("handle", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode("return NugetHandles.retain(handle.asStableRef<Any>().get())\n")
      .build(),
  )
}
