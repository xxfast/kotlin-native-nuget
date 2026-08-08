package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
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
  val underlyingDeclaration: KSDeclaration =
    underlyingProp.type.resolve().expandAliases().declaration
  val underlyingType: String = underlyingDeclaration.qualifiedName?.asString() ?: return
  // ADR-077 sub-item 4 prerequisite: an enum underlying crosses as its int ordinal, so it is a
  // value underlying. The qualified-name set cannot see that; misclassifying it as reference
  // deferred the primary constructor here while the planner still planned `_create`, leaving the
  // Kotlin half of that export missing (the contract check's crash).
  val isEnumUnderlying: Boolean =
    (underlyingDeclaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
  val isReferenceUnderlying: Boolean = !isEnumUnderlying && underlyingType !in PRIMITIVE_TYPES

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

  // ADR-082: members come off the catalog, not from a per-declaration plan lookup. Two declared
  // same-name methods share a simple name but not a plan symbol (the planner numbers overloads),
  // and re-deriving `"$qualifiedName.$name"` per `getAllFunctions()` entry emitted the first
  // overload's export twice and the second's never.
  callableCatalog.valueClassProperties(qualifiedName).forEach { addForwardKotlinPlanExport(it) }
  callableCatalog.valueClassMethods(qualifiedName).forEach { addForwardKotlinPlanExport(it) }
}
