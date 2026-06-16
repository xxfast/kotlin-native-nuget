package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec

internal fun FileSpec.Builder.addValueClassExports(cls: KSClassDeclaration) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()

  val underlyingProp = cls.primaryConstructor!!.parameters.first()
  val underlyingPropName: String = underlyingProp.name?.asString() ?: return
  val underlyingType: String = underlyingProp.type.resolve().declaration.qualifiedName?.asString() ?: return

  val secondaryConstructors: List<KSFunctionDeclaration> = cls.declarations
    .filterIsInstance<KSFunctionDeclaration>()
    .filter { it.simpleName.asString() == "<init>" }
    .filter { it != cls.primaryConstructor }
    .toList()

  secondaryConstructors.forEachIndexed { index, ctor ->
    val cname: String = if (index == 0) "${prefix}_create" else "${prefix}_create_${index}"

    val paramCall: String = ctor.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_$cname")
      .addAnnotation(cNameAnnotation(cname))

    ctor.parameters.forEach { param ->
      val type: String =
        param.type.resolve().declaration.qualifiedName?.asString()
          ?: param.type.resolve().declaration.simpleName.asString()

      builder.addParameter(
        param.name?.asString() ?: "_",
        ClassName.bestGuess(type),
      )
    }

    builder.returns(ClassName.bestGuess(underlyingType))
    builder.addStatement("return %L(%L).%L", qualifiedName, paramCall, underlyingPropName)

    addFunction(builder.build())
  }

  val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() != underlyingPropName }
    .toList()

  properties.forEach { prop ->
    val propName: String = prop.simpleName.asString()
    val propType: String = prop.type.resolve().declaration.qualifiedName?.asString() ?: "kotlin.Unit"

    val builder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_get_$propName")
      .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
      .addParameter("value", ClassName.bestGuess(underlyingType))
      .returns(ClassName.bestGuess(propType))
      .addStatement("return %L(value).%L", qualifiedName, propName)

    addFunction(builder.build())
  }

  val methods: List<KSFunctionDeclaration> = cls.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf(
      "equals", "hashCode", "toString", "<init>",
      "box-impl", "unbox-impl", "constructor-impl",
      "hashCode-impl", "equals-impl", "equals-impl0", "toString-impl",
    ) }
    .toList()

  methods.forEach { method ->
    val methodName: String = method.simpleName.asString()
    val methodReturn: String = method.returnType?.resolve()
      ?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"

    val builder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_$methodName")
      .addAnnotation(cNameAnnotation("${prefix}_$methodName"))
      .addParameter("value", ClassName.bestGuess(underlyingType))

    if (methodReturn == "kotlin.Unit") {
      builder.addStatement("%L(value).%L()", qualifiedName, methodName)
    } else {
      builder.returns(ClassName.bestGuess(methodReturn))
      builder.addStatement("return %L(value).%L()", qualifiedName, methodName)
    }

    addFunction(builder.build())
  }
}
