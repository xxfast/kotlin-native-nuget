package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

internal val cNameAnnotation = ClassName("kotlin.native", "CName")
internal val cOpaquePointer = ClassName("kotlinx.cinterop", "COpaquePointer")
internal val stableRef = ClassName("kotlinx.cinterop", "StableRef")

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

internal fun cNameAnnotation(value: String): AnnotationSpec =
  AnnotationSpec.builder(cNameAnnotation)
    .addMember("%S", value)
    .build()
