package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import kotlin.reflect.KClass

/**
 * Generates @CName bridge exports for generic functions using type-variant pattern.
 * For identity<T>(value: T): T, generates identity_string, identity_int, etc.
 */
internal fun FileSpec.Builder.addGenericFunctionExports(func: KSFunctionDeclaration) {
  val funcName: String = func.simpleName.asString()
  val returnType: KSType? = func.returnType?.resolve()?.expandAliases()
  val returnDecl: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

  val typeParamName: String = func.typeParameters.firstOrNull()?.name?.asString() ?: "T"

  val paramIndex: Int = func.parameters.indexOfFirst { param ->
    param.type.resolve().expandAliases().declaration.simpleName.asString() == typeParamName
  }

  if (paramIndex == -1) return

  val paramName: String = func.parameters[paramIndex].name?.asString() ?: "value"

  val hasNonTrivialBound: Boolean = func.typeParameters.firstOrNull()
    ?.bounds?.toList()?.any { bound ->
      val resolved = bound.resolve()
      resolved.declaration.qualifiedName?.asString() != "kotlin.Any"
    } ?: false

  val returnsGenericClass: Boolean = returnDecl != typeParamName && returnDecl != "Unit"

  val primitiveTypes = listOf(
    "string" to "String",
    "byte" to "Byte",
    "ubyte" to "UByte",
    "short" to "Short",
    "ushort" to "UShort",
    "int" to "Int",
    "uint" to "UInt",
    "long" to "Long",
    "ulong" to "ULong",
    "float" to "Float",
    "double" to "Double",
    "bool" to "Boolean",
  )

  if (!hasNonTrivialBound) primitiveTypes.forEach { (suffix, kotlinType) ->
    val cname = "${funcName}_$suffix"

    if (returnsGenericClass) {
      addFunction(
        FunSpec.builder("export_$cname")
          .addAnnotation(cNameAnnotation(cname))
          .addParameter(paramName, kotlinTypeClass(kotlinType))
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
          }, stableRef, funcName, paramName, cOpaquePointerVar, stableRef)
          .build()
      )
    } else if (returnDecl == typeParamName) {
      val qualifiedKotlinType: String = "kotlin.$kotlinType"
      addFunction(
        FunSpec.builder("export_$cname")
          .addAnnotation(cNameAnnotation(cname))
          .addParameter(paramName, kotlinTypeClass(kotlinType))
          .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
          .returns(kotlinTypeClass(kotlinType))
          .addCode(buildString {
            appendLine("return try {")
            appendLine("  %L(%L)")
            appendLine("} catch (e: Throwable) {")
            appendLine("  if (errorOut != null) {")
            appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
            appendLine("      Pair(e::class.qualifiedName ?: e::class.simpleName ?: \"UnknownException\", e.message ?: \"Kotlin error\")")
            appendLine("    ).asCPointer()")
            appendLine("  }")
            appendLine("  ${defaultValueFor(qualifiedKotlinType)}")
            append("}")
          }, funcName, paramName, cOpaquePointerVar, stableRef)
          .build()
      )
    }
  }

  val cname = "${funcName}_object"

  val boundQualified: String? = func.typeParameters.firstOrNull()
    ?.bounds?.toList()?.firstOrNull()?.let { bound ->
      val resolved = bound.resolve()
      val qn: String? = resolved.declaration.qualifiedName?.asString()
      if (qn != null && qn != "kotlin.Any") qn else null
    }

  val refType: String = boundQualified ?: "Any"

  if (returnsGenericClass) {
    addFunction(
      FunSpec.builder("export_$cname")
        .addAnnotation(cNameAnnotation(cname))
        .addParameter(paramName, cOpaquePointer)
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .returns(cOpaquePointer.copy(nullable = true))
        .addCode(buildString {
          appendLine("return try {")
          appendLine("  %T.create(%L(%L.asStableRef<$refType>().get())).asCPointer()")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      Pair(e::class.qualifiedName ?: e::class.simpleName ?: \"UnknownException\", e.message ?: \"Kotlin error\")")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          appendLine("  null")
          append("}")
        }, stableRef, funcName, paramName, cOpaquePointerVar, stableRef)
        .build()
    )
  } else if (returnDecl == typeParamName) {
    addFunction(
      FunSpec.builder("export_$cname")
        .addAnnotation(cNameAnnotation(cname))
        .addParameter(paramName, cOpaquePointer)
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .returns(cOpaquePointer.copy(nullable = true))
        .addCode(buildString {
          appendLine("return try {")
          appendLine("  %T.create(%L(%L.asStableRef<$refType>().get())).asCPointer()")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      Pair(e::class.qualifiedName ?: e::class.simpleName ?: \"UnknownException\", e.message ?: \"Kotlin error\")")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          appendLine("  null")
          append("}")
        }, stableRef, funcName, paramName, cOpaquePointerVar, stableRef)
        .build()
    )
  }
}

private fun kotlinTypeClass(kotlinType: String): KClass<*> = when (kotlinType) {
  "String" -> String::class
  "Byte" -> Byte::class
  "UByte" -> UByte::class
  "Short" -> Short::class
  "UShort" -> UShort::class
  "Int" -> Int::class
  "UInt" -> UInt::class
  "Long" -> Long::class
  "ULong" -> ULong::class
  "Float" -> Float::class
  "Double" -> Double::class
  "Boolean" -> Boolean::class
  else -> String::class
}
