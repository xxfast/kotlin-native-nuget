package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.toCName
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports

internal fun FileSpec.Builder.addExtensionPropertyExports(
  prop: KSPropertyDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val propName: String = prop.simpleName.asString()
  val receiverType: KSType = prop.extensionReceiver!!.resolve().expandAliases()
  val receiverSimpleName: String = receiverType.declaration.simpleName.asString()
  val planned = callableCatalog.propertyFor("${prop.packageName.asString()}.$receiverSimpleName.$propName")
  if (planned != null) {
    addForwardPropertyPlanExports(planned)
    return
  }
  val receiverQualified: String = receiverType.declaration.qualifiedName?.asString() ?: return
  val receiverPrefix: String = receiverSimpleName.lowercase()
  val cname: String = "${receiverPrefix}_get_${toCName(propName)}"

  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val qualifiedReturn: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Unit"

  val isPrimitiveReceiver: Boolean = receiverQualified in setOf(
    "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short",
    "kotlin.UShort", "kotlin.Int", "kotlin.UInt", "kotlin.Long",
    "kotlin.ULong", "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
  )

  val builder: FunSpec.Builder = FunSpec.builder("export_$cname")
    .addAnnotation(cNameAnnotation(cname))

  if (isPrimitiveReceiver) {
    builder.addParameter("receiver", ClassName.bestGuess(receiverQualified))
  } else {
    builder.addParameter("handle", cOpaquePointer)
  }

  builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))

  val receiverExpr: String = if (isPrimitiveReceiver) "receiver"
  else "handle.asStableRef<$receiverQualified>().get()"

  val dummy: String = defaultValueFor(qualifiedReturn)
  val isUnit = qualifiedReturn == "kotlin.Unit"

  if (isUnit) {
    builder.addCode(buildString {
      appendLine("try {")
      appendLine("  $receiverExpr.$propName")
      appendLine("} catch (e: Throwable) {")
      appendLine("  if (errorOut != null) {")
      appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
      appendLine("      buildError(e)")
      appendLine("    ).asCPointer()")
      appendLine("  }")
      append("}")
    }, cOpaquePointerVar, stableRef)
  } else {
    builder.returns(ClassName.bestGuess(qualifiedReturn))
    builder.addCode(buildString {
      appendLine("return try {")
      appendLine("  $receiverExpr.$propName")
      appendLine("} catch (e: Throwable) {")
      appendLine("  if (errorOut != null) {")
      appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
      appendLine("      buildError(e)")
      appendLine("    ).asCPointer()")
      appendLine("  }")
      appendLine("  $dummy")
      append("}")
    }, cOpaquePointerVar, stableRef)
  }

  addFunction(builder.build())
}
