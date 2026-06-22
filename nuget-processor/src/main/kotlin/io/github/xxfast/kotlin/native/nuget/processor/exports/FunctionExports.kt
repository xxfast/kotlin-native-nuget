package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/**
 * Generates @CName bridge exports for top-level functions.
 * Handles nullable returns (ADR-002), String returns, and sealed class returns.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md">ADR-002: Nullable two-call pattern</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/007-top-level-function-class-naming.md">ADR-007: Top-level function naming</a>
 */
internal fun FileSpec.Builder.addFunctionExports(func: KSFunctionDeclaration) {
  val cname: String = toCName(func.simpleName.asString())
  val funcName: String = func.simpleName.asString()
  val returnType: KSType? = func.returnType?.resolve()?.expandAliases()
  val isNullable: Boolean = returnType?.isMarkedNullable == true
  val qualifiedReturn: String = returnType
    ?.declaration?.qualifiedName?.asString() ?: "Unit"

  val paramCall: String = func.parameters.joinToString(", ") {
    it.name?.asString() ?: "_"
  }

  val returnDecl: KSClassDeclaration? = returnType?.declaration as? KSClassDeclaration
  val isSealedReturnType: Boolean = returnDecl?.modifiers?.contains(Modifier.SEALED) == true
  val isGenericReturnType: Boolean = returnDecl?.typeParameters?.isNotEmpty() == true &&
    returnType.arguments.isNotEmpty()

  if (isNullable) {
    addFunction(
      FunSpec.builder("export_${cname}_has_value")
        .addAnnotation(cNameAnnotation(cname + "_has_value"))
        .addParameters(func)
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .returns(Boolean::class)
        .addCode(buildString {
          appendLine("return try {")
          appendLine("  %L(%L) != null")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      Pair(e::class.qualifiedName ?: e::class.simpleName ?: \"UnknownException\", e.message ?: \"Kotlin error\")")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          appendLine("  false")
          append("}")
        }, funcName, paramCall, cOpaquePointerVar, stableRef)
        .build()
    )

    val valueReturn: String =
      if (qualifiedReturn == "kotlin.String") "kotlin.String"
      else qualifiedReturn

    val valueDummy: String = defaultValueFor(valueReturn)
    val valueReturnType = ClassName.bestGuess(valueReturn).copy(nullable = valueDummy == "null")

    addFunction(
      FunSpec.builder("export_${cname}_value")
        .addAnnotation(cNameAnnotation(cname + "_value"))
        .addParameters(func)
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .returns(valueReturnType)
        .addCode(buildString {
          appendLine("return try {")
          appendLine("  %L(%L)!!")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      Pair(e::class.qualifiedName ?: e::class.simpleName ?: \"UnknownException\", e.message ?: \"Kotlin error\")")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          appendLine("  $valueDummy")
          append("}")
        }, funcName, paramCall, cOpaquePointerVar, stableRef)
        .build()
    )
    return
  }

  if (isGenericReturnType || isSealedReturnType) {
    addFunction(
      FunSpec.builder("export_$cname")
        .addAnnotation(cNameAnnotation(cname))
        .addParameters(func)
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .returns(cOpaquePointer.copy(nullable = true))
        .addCode(buildString {
          appendLine("return try {")
          appendLine("  %T.create(%L(%L)).asCPointer()")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      Pair(e::class.qualifiedName ?: e::class.simpleName ?: \"UnknownException\", e.message ?: \"Kotlin error\")")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          appendLine("  null")
          append("}")
        }, stableRef, funcName, paramCall, cOpaquePointerVar, stableRef)
        .build()
    )
    return
  }

  if (qualifiedReturn == "kotlin.Unit") {
    addFunction(
      FunSpec.builder("export_$cname")
        .addAnnotation(cNameAnnotation(cname))
        .addParameters(func)
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .addCode(buildString {
          appendLine("try {")
          appendLine("  %L(%L)")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      Pair(e::class.qualifiedName ?: e::class.simpleName ?: \"UnknownException\", e.message ?: \"Kotlin error\")")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          append("}")
        }, funcName, paramCall, cOpaquePointerVar, stableRef)
        .build()
    )
    return
  }

  addFunction(
    FunSpec.builder("export_$cname")
      .addAnnotation(cNameAnnotation(cname))
      .addParameters(func)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(ClassName.bestGuess(qualifiedReturn))
      .addCode(buildString {
        appendLine("return try {")
        appendLine("  %L(%L)")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      Pair(e::class.qualifiedName ?: e::class.simpleName ?: \"UnknownException\", e.message ?: \"Kotlin error\")")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  ${defaultValueFor(qualifiedReturn)}")
        append("}")
      }, funcName, paramCall, cOpaquePointerVar, stableRef)
      .build()
  )
}
