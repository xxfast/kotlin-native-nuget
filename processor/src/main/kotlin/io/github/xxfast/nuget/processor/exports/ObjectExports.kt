package io.github.xxfast.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.nuget.processor.toCName

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
    val methodReturn: String = method.returnType?.resolve()
      ?.declaration?.qualifiedName?.asString() ?: "Unit"

    val methodParamCall: String = method.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_$entryPoint")
      .addAnnotation(cNameAnnotation(entryPoint))

    for (param in method.parameters) {
      val type: String =
        param.type.resolve().declaration.qualifiedName?.asString()
          ?: param.type.resolve().declaration.simpleName.asString()

      builder.addParameter(
        param.name?.asString() ?: "_",
        ClassName.bestGuess(type),
      )
    }

    if (methodReturn == "kotlin.Unit") {
      builder.addStatement(
        "%L.%L(%L)",
        qualifiedName, methodName, methodParamCall,
      )
    } else {
      builder.returns(ClassName.bestGuess(methodReturn))
      builder.addStatement(
        "return %L.%L(%L)",
        qualifiedName, methodName, methodParamCall,
      )
    }

    addFunction(builder.build())
  }
}
