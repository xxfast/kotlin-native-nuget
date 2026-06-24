package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/**
 * Generates @CName bridge exports for Kotlin object singletons.
 * No handle parameter — calls the object's methods directly.
 */
internal fun FileSpec.Builder.addObjectExports(obj: KSClassDeclaration) {
  val name: String = obj.simpleName.asString()
  val qualifiedName: String = obj.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()

  val methods: List<KSFunctionDeclaration> = obj.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    .toList()

  for (method in methods) {
    val methodName: String = method.simpleName.asString()
    val cname: String = toCName(methodName)
    val entryPoint: String = "${prefix}_${cname}"
    val methodReturn: String = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString() ?: "Unit"

    val methodParamCall: String = method.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_$entryPoint")
      .addAnnotation(cNameAnnotation(entryPoint))

    for (param in method.parameters) {
      val resolved = param.type.resolve().expandAliases()
      val type: String =
        resolved.declaration.qualifiedName?.asString()
          ?: resolved.declaration.simpleName.asString()

      builder.addParameter(
        param.name?.asString() ?: "_",
        ClassName.bestGuess(type),
      )
    }

    builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))

    if (methodReturn == "kotlin.Unit") {
      builder.addCode(buildString {
        appendLine("try {")
        appendLine("  %L.%L(%L)")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      Triple(e::class.qualifiedName ?: e::class.simpleName ?: \"UnknownException\", e.message ?: \"Kotlin error\", e.stackTraceToString())")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        append("}")
      }, qualifiedName, methodName, methodParamCall, cOpaquePointerVar, stableRef)
    } else {
      builder.returns(ClassName.bestGuess(methodReturn))
      builder.addCode(buildString {
        appendLine("return try {")
        appendLine("  %L.%L(%L)")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      Triple(e::class.qualifiedName ?: e::class.simpleName ?: \"UnknownException\", e.message ?: \"Kotlin error\", e.stackTraceToString())")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  ${defaultValueFor(methodReturn)}")
        append("}")
      }, qualifiedName, methodName, methodParamCall, cOpaquePointerVar, stableRef)
    }

    addFunction(builder.build())
  }
}
