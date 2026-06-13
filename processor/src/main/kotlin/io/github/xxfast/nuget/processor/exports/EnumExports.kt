package io.github.xxfast.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec

internal fun FileSpec.Builder.addEnumExports(enum: KSClassDeclaration) {
  val name: String = enum.simpleName.asString()
  val qualifiedName: String = enum.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()

  val properties: List<KSPropertyDeclaration> = enum.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in setOf("name", "ordinal", "declaringJavaClass") }
    .toList()

  for (prop in properties) {
    val propName: String = prop.simpleName.asString()
    val propType: String = prop.type.resolve().declaration.qualifiedName?.asString() ?: "Any"

    addFunction(
      FunSpec.builder("export_${prefix}_get_$propName")
        .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
        .addParameter("ordinal", Int::class)
        .returns(ClassName.bestGuess(propType))
        .addStatement("val ${prefix}: %L = %L.entries[ordinal]", qualifiedName, qualifiedName)
        .addStatement("return ${prefix}.$propName")
        .build()
    )
  }
}
