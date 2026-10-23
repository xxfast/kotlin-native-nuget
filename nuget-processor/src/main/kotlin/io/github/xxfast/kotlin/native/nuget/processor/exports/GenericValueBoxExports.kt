package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwnerTag
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardGenericValueBox
import io.github.xxfast.kotlin.native.nuget.processor.forward.genericValueBox

/**
 * ADR-237: the Kotlin half of a generic value class's box/unbox pair ([ForwardGenericValueBox]),
 * `<prefix>_box` and `<prefix>_unbox`. Both take and return `object` boxes, mint through
 * `NugetHandles.retain`, and write a thrown `init` failure to the error slot.
 */
internal fun FileSpec.Builder.addGenericValueBoxExports(cls: KSClassDeclaration, prefix: String) {
  val box: ForwardGenericValueBox = cls.genericValueBox() ?: return
  val read: String = "unboxed?.asStableRef<Any>()?.get()" + (box.cast?.let { " as $it" } ?: "")
  val fail: String = "  if (errorOut != null) {\n" +
    "    errorOut.reinterpret<COpaquePointerVar>().pointed.value = " +
    "NugetHandles.retain(buildError(e, ::nugetMappedType))\n" +
    "  }\n" +
    "  null\n"
  addFunction(
    FunSpec.builder("export_${prefix}_box")
      .addAnnotation(
        cNameAnnotation(
          "${prefix}_box",
          ForwardExportOwnerTag(declaration = cls, role = "generic value class box"),
        ),
      )
      .addParameter("unboxed", cOpaquePointer.copy(nullable = true))
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(cOpaquePointer.copy(nullable = true))
      .addCode(
        "return try {\n  NugetHandles.retain(${box.construction}($read))\n" +
          "} catch (e: Throwable) {\n$fail}\n",
      )
      .build(),
  )
  addFunction(
    FunSpec.builder("export_${prefix}_unbox")
      .addAnnotation(
        cNameAnnotation(
          "${prefix}_unbox",
          ForwardExportOwnerTag(declaration = cls, role = "generic value class unbox"),
        ),
      )
      .addParameter("boxed", cOpaquePointer)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(cOpaquePointer.copy(nullable = true))
      .addCode(
        "return try {\n" +
          "  (boxed.asStableRef<Any>().get() as ${box.star}).${box.underlyingProperty}" +
          "?.let { NugetHandles.retain(it) }\n" +
          "} catch (e: Throwable) {\n$fail}\n",
      )
      .build(),
  )
}
