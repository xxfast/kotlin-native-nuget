package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor

private val PRIMITIVE_TYPES: Set<String> = setOf(
  "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short", "kotlin.UShort",
  "kotlin.Int", "kotlin.UInt", "kotlin.Long", "kotlin.ULong",
  "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
)

/**
 * Value-class exports: ordinary members are plan-only. Reference-underlying constructors remain
 * on an explicit named legacy adapter (ADR-035 defers primary planning for that branch).
 */
internal fun FileSpec.Builder.addValueClassExports(
  cls: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()

  val underlyingProp: KSValueParameter = cls.primaryConstructor!!.parameters.first()
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
    // ADR-035: primary deferred for reference-underlying; secondary-only export numbering.
    secondaryConstructors.mapIndexed { index, ctor ->
      val cname: String = if (index == 0) "${prefix}_create" else "${prefix}_create_${index}"
      ctor to cname
    }
  } else {
    buildList {
      add(cls.primaryConstructor!! to "${prefix}_create")
      secondaryConstructors.forEachIndexed { index, ctor ->
        add(ctor to "${prefix}_create_${index + 2}")
      }
    }
  }

  constructorExports.forEachIndexed { index, (ctor, cname) ->
    val symbolSuffix: String = if (isReferenceUnderlying) {
      if (index == 0) "" else "_$index"
    } else if (index == 0) {
      ""
    } else {
      "_${index + 1}"
    }
    val planned: ForwardCallablePlan? =
      callableCatalog.planFor("$qualifiedName.<init>$symbolSuffix")
    if (planned != null) {
      addForwardKotlinPlanExport(planned)
      return@forEachIndexed
    }
    // Explicit named adapter for reference-underlying secondaries only (not planned).
    if (!isReferenceUnderlying) return@forEachIndexed

    val paramCall: String = ctor.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_$cname")
      .addAnnotation(cNameAnnotation(cname))

    ctor.parameters.forEach { param ->
      val resolved: KSType = param.type.resolve().expandAliases()
      val type: String =
        resolved.declaration.qualifiedName?.asString()
          ?: resolved.declaration.simpleName.asString()

      builder.addParameter(
        param.name?.asString() ?: "_",
        ClassName.bestGuess(type),
      )
    }

    builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
    // Reference-underlying secondaries still return the unwrapped underlying value (ADR-014).
    builder.returns(ClassName.bestGuess(underlyingType).copy(nullable = true))
    builder.addCode(buildString {
      appendLine("return try {")
      appendLine("  %L(%L).%L")
      appendLine("} catch (e: Throwable) {")
      appendLine("  if (errorOut != null) {")
      appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
      appendLine("      buildError(e)")
      appendLine("    ).asCPointer()")
      appendLine("  }")
      appendLine("  null")
      append("}")
    }, qualifiedName, paramCall, underlyingPropName, cOpaquePointerVar, stableRef)

    addFunction(builder.build())
  }

  cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() != underlyingPropName }
    .forEach { prop ->
      val planned: ForwardCallablePlan? =
        callableCatalog.planFor("$qualifiedName.${prop.simpleName.asString()}")
      if (planned != null) addForwardKotlinPlanExport(planned)
    }

  val excluded: Set<String> = setOf(
    "equals", "hashCode", "toString", "<init>",
    "box-impl", "unbox-impl", "constructor-impl",
    "hashCode-impl", "equals-impl", "equals-impl0", "toString-impl",
  )
  cls.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in excluded }
    .forEach { method ->
      val planned: ForwardCallablePlan? =
        callableCatalog.planFor("$qualifiedName.${method.simpleName.asString()}")
      if (planned != null) addForwardKotlinPlanExport(planned)
    }
}
