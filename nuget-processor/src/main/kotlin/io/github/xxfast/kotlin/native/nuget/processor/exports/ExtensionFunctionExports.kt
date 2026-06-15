package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.toCName

internal fun FileSpec.Builder.addExtensionFunctionExports(func: KSFunctionDeclaration) {
  val funcName: String = func.simpleName.asString()
  val receiverType: KSType = func.extensionReceiver!!.resolve()
  val receiverSimpleName: String = receiverType.declaration.simpleName.asString()
  val receiverQualified: String = receiverType.declaration.qualifiedName?.asString() ?: return
  val receiverPrefix: String = receiverSimpleName.lowercase()
  val cname: String = "${receiverPrefix}_${toCName(funcName)}"

  val returnType: KSType? = func.returnType?.resolve()
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
    val type: String = param.type.resolve().declaration.qualifiedName?.asString()
      ?: param.type.resolve().declaration.simpleName.asString()
    builder.addParameter(param.name?.asString() ?: "_", ClassName.bestGuess(type))
  }

  val receiverExpr: String = if (isPrimitiveReceiver) "receiver"
    else "handle.asStableRef<$receiverQualified>().get()"

  val callExpr: String = if (paramCall.isEmpty()) "$receiverExpr.$funcName()"
    else "$receiverExpr.$funcName($paramCall)"

  if (qualifiedReturn == "kotlin.Unit") {
    builder.addStatement(callExpr)
  } else {
    builder.returns(ClassName.bestGuess(qualifiedReturn))
    builder.addStatement("return $callExpr")
  }

  addFunction(builder.build())
}
