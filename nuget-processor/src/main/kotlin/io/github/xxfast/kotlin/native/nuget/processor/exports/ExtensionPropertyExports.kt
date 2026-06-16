package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.toCName

internal fun FileSpec.Builder.addExtensionPropertyExports(prop: KSPropertyDeclaration) {
  val propName: String = prop.simpleName.asString()
  val receiverType: KSType = prop.extensionReceiver!!.resolve()
  val receiverSimpleName: String = receiverType.declaration.simpleName.asString()
  val receiverQualified: String = receiverType.declaration.qualifiedName?.asString() ?: return
  val receiverPrefix: String = receiverSimpleName.lowercase()
  val cname: String = "${receiverPrefix}_get_${toCName(propName)}"

  val propTypeResolved: KSType = prop.type.resolve()
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

  val receiverExpr: String = if (isPrimitiveReceiver) "receiver"
  else "handle.asStableRef<$receiverQualified>().get()"

  builder.returns(ClassName.bestGuess(qualifiedReturn))
  builder.addStatement("return $receiverExpr.$propName")

  addFunction(builder.build())
}
