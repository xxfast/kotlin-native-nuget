package io.github.xxfast.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.nuget.processor.toCName

internal fun FileSpec.Builder.addFunctionExports(func: KSFunctionDeclaration) {
  val cname: String = toCName(func.simpleName.asString())
  val funcName: String = func.simpleName.asString()
  val returnType: KSType? = func.returnType?.resolve()
  val isNullable: Boolean = returnType?.isMarkedNullable == true
  val qualifiedReturn: String = returnType
    ?.declaration?.qualifiedName?.asString() ?: "Unit"

  val paramCall: String = func.parameters.joinToString(", ") {
    it.name?.asString() ?: "_"
  }

  val isSealedReturnType: Boolean = (returnType?.declaration as? KSClassDeclaration)
    ?.modifiers?.contains(Modifier.SEALED) == true

  if (isNullable) {
    addFunction(
      FunSpec.builder("export_${cname}_has_value")
        .addAnnotation(cNameAnnotation(cname + "_has_value"))
        .addParameters(func)
        .returns(Boolean::class)
        .addStatement("return %L(%L) != null", funcName, paramCall)
        .build()
    )

    val valueReturn: String =
      if (qualifiedReturn == "kotlin.String") "kotlin.String"
      else qualifiedReturn

    addFunction(
      FunSpec.builder("export_${cname}_value")
        .addAnnotation(cNameAnnotation(cname + "_value"))
        .addParameters(func)
        .returns(ClassName.bestGuess(valueReturn))
        .addStatement("return %L(%L)!!", funcName, paramCall)
        .build()
    )
    return
  }

  if (isSealedReturnType) {
    addFunction(
      FunSpec.builder("export_$cname")
        .addAnnotation(cNameAnnotation(cname))
        .addParameters(func)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(%L)).asCPointer()", stableRef, funcName, paramCall)
        .build()
    )
    return
  }

  if (qualifiedReturn == "kotlin.Unit") {
    addFunction(
      FunSpec.builder("export_$cname")
        .addAnnotation(cNameAnnotation(cname))
        .addParameters(func)
        .addStatement("%L(%L)", funcName, paramCall)
        .build()
    )
    return
  }

  addFunction(
    FunSpec.builder("export_$cname")
      .addAnnotation(cNameAnnotation(cname))
      .addParameters(func)
      .returns(ClassName.bestGuess(qualifiedReturn))
      .addStatement("return %L(%L)", funcName, paramCall)
      .build()
  )
}
