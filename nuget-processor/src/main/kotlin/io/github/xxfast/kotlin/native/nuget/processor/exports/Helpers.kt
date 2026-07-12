package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

internal val cNameAnnotation = ClassName("kotlin.native", "CName")
internal val cOpaquePointer = ClassName("kotlinx.cinterop", "COpaquePointer")
internal val cOpaquePointerVar = ClassName("kotlinx.cinterop", "COpaquePointerVar")
internal val stableRef = ClassName("kotlinx.cinterop", "StableRef")

internal fun defaultValueFor(qualifiedReturn: String): String = when (qualifiedReturn) {
  "kotlin.Boolean" -> "false"
  "kotlin.String" -> "\"\""
  "kotlin.Float" -> "0.0f"
  "kotlin.Double" -> "0.0"
  "kotlin.UByte" -> "0.toUByte()"
  "kotlin.UShort" -> "0.toUShort()"
  "kotlin.UInt" -> "0u"
  "kotlin.ULong" -> "0uL"
  "kotlin.Unit" -> ""
  else -> if (qualifiedReturn.startsWith("kotlin.")) "0" else "null"
}

internal fun FunSpec.Builder.addParameters(
  func: KSFunctionDeclaration,
): FunSpec.Builder {
  for (param in func.parameters) {
    val resolved = param.type.resolve().expandAliases()
    val type: String =
      resolved.declaration.qualifiedName?.asString()
        ?: resolved.declaration.simpleName.asString()

    addParameter(param.name?.asString() ?: "_", ClassName.bestGuess(type))
  }
  return this
}

/**
 * Same as [addParameters], except an enum param is declared as the ordinal [Int] it crosses the
 * C ABI as (ADR-006). Only for exports whose C# half maps enum params to `int`: top-level
 * functions today. [addParameters] stays as-is for the callers that do not.
 */
internal fun FunSpec.Builder.addEnumAwareParameters(
  func: KSFunctionDeclaration,
): FunSpec.Builder {
  for (param in func.parameters) {
    val resolved: KSType = param.type.resolve().expandAliases()
    val name: String = param.name?.asString() ?: "_"
    val isEnum: Boolean = (resolved.declaration as? KSClassDeclaration)
      ?.classKind == ClassKind.ENUM_CLASS

    if (isEnum) {
      addParameter(name, Int::class)
      continue
    }

    val type: String = resolved.declaration.qualifiedName?.asString()
      ?: resolved.declaration.simpleName.asString()

    addParameter(name, ClassName.bestGuess(type))
  }
  return this
}

internal fun cNameAnnotation(value: String): AnnotationSpec =
  AnnotationSpec.builder(cNameAnnotation)
    .addMember("%S", value)
    .build()
