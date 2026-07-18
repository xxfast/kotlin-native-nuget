package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.toCName

internal fun FileSpec.Builder.addExtensionFunctionExports(func: KSFunctionDeclaration) {
  val funcName: String = func.simpleName.asString()
  val receiverType: KSType = func.extensionReceiver!!.resolve().expandAliases()
  val receiverSimpleName: String = receiverType.declaration.simpleName.asString()
  val receiverQualified: String = receiverType.declaration.qualifiedName?.asString() ?: return
  val receiverPrefix: String = receiverSimpleName.lowercase()
  val cname: String = "${receiverPrefix}_${toCName(funcName)}"

  val returnType: KSType? = func.returnType?.resolve()?.expandAliases()
  val qualifiedReturn: String = returnType?.declaration?.qualifiedName?.asString() ?: "Unit"

  val isPrimitiveReceiver: Boolean = receiverQualified in setOf(
    "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short",
    "kotlin.UShort", "kotlin.Int", "kotlin.UInt", "kotlin.Long",
    "kotlin.ULong", "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
  )

  val paramCall: String = func.parameters.joinToString(", ") {
    it.name?.asString() ?: "_"
  }

  val builder: FunSpec.Builder = FunSpec.builder("export_$cname")
    .addAnnotation(cNameAnnotation(cname))

  if (isPrimitiveReceiver) {
    builder.addParameter("receiver", ClassName.bestGuess(receiverQualified))
  } else {
    builder.addParameter("handle", cOpaquePointer)
  }

  for (param in func.parameters) {
    val resolved = param.type.resolve().expandAliases()
    builder.addParameter(param.name?.asString() ?: "_", resolved.toBridgeTypeName(nullable = false))
  }

  val receiverExpr: String = if (isPrimitiveReceiver) "receiver"
  else "handle.asStableRef<$receiverQualified>().get()"

  val callExpr: String = if (paramCall.isEmpty()) "$receiverExpr.$funcName()"
  else "$receiverExpr.$funcName($paramCall)"

  // ADR-061: the same return-marshalling cascade as the class-method position (mirrored 1:1;
  // see ClassExports.kt's `methods.forEach` loop for the shared design rationale).
  val isNullableReturn: Boolean = returnType?.isMarkedNullable == true
  val isEnumReturn: Boolean = (returnType?.declaration as? KSClassDeclaration)
    ?.classKind == ClassKind.ENUM_CLASS
  // "kotlin.Char" is deliberately included even though it is not one of ADR-061's five return
  // shapes — see ClassExports.kt's identical comment on the mirrored class-method cascade.
  val isPrimitiveReturn: Boolean = qualifiedReturn in setOf(
    "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short",
    "kotlin.UShort", "kotlin.Int", "kotlin.UInt", "kotlin.Long",
    "kotlin.ULong", "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
    "kotlin.Char", "kotlin.Unit",
  )
  val isListReturn: Boolean = !isEnumReturn &&
      (qualifiedReturn == "kotlin.collections.List" ||
          qualifiedReturn == "kotlin.collections.MutableList")
  val isObjectReturn: Boolean = !isEnumReturn && !isPrimitiveReturn && !isListReturn

  when {
    qualifiedReturn == "kotlin.Unit" -> {
      builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      builder.addCode(buildString {
        appendLine("try {")
        appendLine("  $callExpr")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        append("}")
      }, cOpaquePointerVar, stableRef)
    }

    isListReturn || (isObjectReturn && !isNullableReturn) -> {
      builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(buildString {
        appendLine("return try {")
        appendLine("  %T.create($callExpr).asCPointer()")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  null")
        append("}")
      }, stableRef, cOpaquePointerVar, stableRef)
    }

    isObjectReturn && isNullableReturn -> {
      builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(buildString {
        appendLine("return try {")
        appendLine("  val obj: %T = $callExpr")
        appendLine("  if (obj == null) null else %T.create(obj).asCPointer()")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  null")
        append("}")
      }, returnType!!.toBridgeTypeName(), stableRef, cOpaquePointerVar, stableRef)
    }

    isPrimitiveReturn && isNullableReturn && qualifiedReturn != "kotlin.String" -> {
      val nonNullReturnType: TypeName = returnType!!.toBridgeTypeName(nullable = false)
      builder.addParameter("valueOut", cOpaquePointer.copy(nullable = true))
      builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      builder.returns(Boolean::class)
      builder.addCode(buildString {
        appendLine("return try {")
        appendLine("  val result: %T? = $callExpr")
        appendLine("  if (result != null && valueOut != null) {")
        appendLine("    valueOut.reinterpret<%T>().pointed.value = result")
        appendLine("  }")
        appendLine("  result != null")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  false")
        append("}")
      }, nonNullReturnType, cVarTypeFor(qualifiedReturn), cOpaquePointerVar, stableRef)
    }

    else -> {
      builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      builder.returns(returnType?.toBridgeTypeName() ?: ClassName.bestGuess(qualifiedReturn))
      builder.addCode(buildString {
        appendLine("return try {")
        appendLine("  $callExpr")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  ${defaultValueFor(qualifiedReturn)}")
        append("}")
      }, cOpaquePointerVar, stableRef)
    }
  }

  addFunction(builder.build())
}
