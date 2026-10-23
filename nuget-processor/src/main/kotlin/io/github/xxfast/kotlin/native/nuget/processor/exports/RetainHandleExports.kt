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
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.boundBoxExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.boundUnboxExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.handleOutExpression
import io.github.xxfast.kotlin.native.nuget.processor.forward.valueHelper
import io.github.xxfast.kotlin.native.nuget.processor.forward.managedExceptionLowering
import io.github.xxfast.kotlin.native.nuget.processor.forward.throwableEnvelopeExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.throwableFromTextExport

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

/**
 * ADR-239: the two exports a bound C# interface needs at an erased position (a stored listener's
 * payload or result, a returned lambda's or a lambda property's type argument). The box takes the
 * GCHandle C# minted and keeps the reverse pipeline's value behind a Kotlin handle; the unbox reads
 * a Kotlin handle (borrowed: the caller still disposes it) and hands out a fresh GCHandle, which
 * the reverse pipeline duplicates or mints exactly as at a return position.
 */
internal fun FileSpec.Builder.addBoundInterfaceBoxExports(
  bounds: Collection<BridgeType.BoundInterface>,
  symbols: ForwardSymbolTable,
) {
  bounds.forEach { bound ->
    val owner = ForwardExportOwnerTag(symbol = bound.qualifiedName, role = "bound interface box")
    val box: String = symbols.boundBoxExport(bound)
    addFunction(
      FunSpec.builder("export_$box")
        .addAnnotation(cNameAnnotation(box, owner))
        .addParameter("gcHandle", cOpaquePointer)
        .returns(cOpaquePointer)
        .addCode("return NugetHandles.retain(${bound.valueHelper()}(gcHandle))\n")
        .build(),
    )
    val unbox: String = symbols.boundUnboxExport(bound)
    val unboxed: String = "(handle.asStableRef<Any>().get() as ${bound.qualifiedName})"
    addFunction(
      FunSpec.builder("export_$unbox")
        .addAnnotation(cNameAnnotation(unbox, owner.copy(role = "bound interface unbox")))
        .addParameter("handle", cOpaquePointer)
        .returns(cOpaquePointer)
        .addCode("return ${bound.handleOutExpression(unboxed)}\n")
        .build(),
    )
  }
}

/**
 * ADR-239: the two exports a `Throwable` needs at an erased position (a lambda's type argument, a
 * stored listener's payload or result). The envelope reads a Kotlin handle (borrowed) and mints the
 * ADR-107 envelope C#'s `BuildException` reads; the other builds a `NugetManagedException` from the
 * managed-exception text C# sends, the input rule a `Throwable` parameter already follows
 * (ADR-201).
 */
internal fun FileSpec.Builder.addThrowableBoxExports(symbols: ForwardSymbolTable) {
  val owner = ForwardExportOwnerTag(symbol = "kotlin.Throwable", role = "throwable envelope")
  val envelope: String = symbols.throwableEnvelopeExport()
  addFunction(
    FunSpec.builder("export_$envelope")
      .addAnnotation(cNameAnnotation(envelope, owner))
      .addParameter("handle", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(
        "return NugetHandles.retain(" +
          "buildError(handle.asStableRef<Any>().get() as Throwable, ::nugetMappedType))\n",
      )
      .build(),
  )
  val fromText: String = symbols.throwableFromTextExport()
  val lowered: String = managedExceptionLowering("text", nullable = false)
  addFunction(
    FunSpec.builder("export_$fromText")
      .addAnnotation(cNameAnnotation(fromText, owner.copy(role = "throwable from text")))
      .addParameter("text", String::class)
      .returns(cOpaquePointer)
      .addCode("return NugetHandles.retain($lowered)\n")
      .build(),
  )
}
