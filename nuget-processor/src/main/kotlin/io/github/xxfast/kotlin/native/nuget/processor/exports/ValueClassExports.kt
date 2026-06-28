package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

private val PRIMITIVE_TYPES: Set<String> = setOf(
  "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short", "kotlin.UShort",
  "kotlin.Int", "kotlin.UInt", "kotlin.Long", "kotlin.ULong",
  "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
)

internal fun FileSpec.Builder.addValueClassExports(cls: KSClassDeclaration) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()

  val underlyingProp = cls.primaryConstructor!!.parameters.first()
  val underlyingPropName: String = underlyingProp.name?.asString() ?: return
  val underlyingType: String =
    underlyingProp.type.resolve().expandAliases().declaration.qualifiedName?.asString() ?: return
  val isReferenceUnderlying: Boolean = underlyingType !in PRIMITIVE_TYPES

  val secondaryConstructors: List<KSFunctionDeclaration> = cls.declarations
    .filterIsInstance<KSFunctionDeclaration>()
    .filter { it.simpleName.asString() == "<init>" }
    .filter { it != cls.primaryConstructor }
    .toList()

  val constructorExports: List<Pair<KSFunctionDeclaration, String>> = if (isReferenceUnderlying) {
    // ADR-035: primary `init` validation for reference-underlying value classes is
    // deferred; keep the existing secondary-only export scheme (old numbering).
    secondaryConstructors.mapIndexed { index, ctor ->
      val cname: String = if (index == 0) "${prefix}_create" else "${prefix}_create_${index}"
      ctor to cname
    }
  } else {
    // ADR-035: export the primary so its `init` runs across the bridge; secondaries
    // renumber to `_create_2+` (aligns with ADR-034).
    buildList {
      add(cls.primaryConstructor!! to "${prefix}_create")
      secondaryConstructors.forEachIndexed { index, ctor ->
        add(ctor to "${prefix}_create_${index + 2}")
      }
    }
  }

  constructorExports.forEach { (ctor, cname) ->
    val paramCall: String = ctor.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_$cname")
      .addAnnotation(cNameAnnotation(cname))

    ctor.parameters.forEach { param ->
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

    val dummy: String = defaultValueFor(underlyingType)
    builder.returns(ClassName.bestGuess(underlyingType).copy(nullable = dummy == "null"))
    builder.addCode(buildString {
      appendLine("return try {")
      appendLine("  %L(%L).%L")
      appendLine("} catch (e: Throwable) {")
      appendLine("  if (errorOut != null) {")
      appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
      appendLine("      buildError(e)")
      appendLine("    ).asCPointer()")
      appendLine("  }")
      appendLine("  $dummy")
      append("}")
    }, qualifiedName, paramCall, underlyingPropName, cOpaquePointerVar, stableRef)

    addFunction(builder.build())
  }

  val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() != underlyingPropName }
    .toList()

  properties.forEach { prop ->
    val propName: String = prop.simpleName.asString()
    val propType: String =
      prop.type.resolve().expandAliases().declaration.qualifiedName?.asString() ?: "kotlin.Unit"

    val builder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_get_$propName")
      .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))

    if (isReferenceUnderlying) {
      builder
        .addParameter("handle", cOpaquePointer)
        .returns(ClassName.bestGuess(propType))
        .addStatement(
          "return %L(handle.asStableRef<%L>().get()).%L",
          qualifiedName, underlyingType, propName,
        )
    } else {
      builder
        .addParameter("value", ClassName.bestGuess(underlyingType))
        .returns(ClassName.bestGuess(propType))
        .addStatement("return %L(value).%L", qualifiedName, propName)
    }

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
    val methodReturn: String = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"

    val builder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_$methodName")
      .addAnnotation(cNameAnnotation("${prefix}_$methodName"))

    if (isReferenceUnderlying) {
      builder.addParameter("handle", cOpaquePointer)

      if (methodReturn == "kotlin.Unit") {
        builder.addStatement(
          "%L(handle.asStableRef<%L>().get()).%L()",
          qualifiedName, underlyingType, methodName,
        )
      } else {
        builder.returns(ClassName.bestGuess(methodReturn))
        builder.addStatement(
          "return %L(handle.asStableRef<%L>().get()).%L()",
          qualifiedName, underlyingType, methodName,
        )
      }
    } else {
      builder.addParameter("value", ClassName.bestGuess(underlyingType))

      if (methodReturn == "kotlin.Unit") {
        builder.addStatement("%L(value).%L()", qualifiedName, methodName)
      } else {
        builder.returns(ClassName.bestGuess(methodReturn))
        builder.addStatement("return %L(value).%L()", qualifiedName, methodName)
      }
    }

    addFunction(builder.build())
  }
}
