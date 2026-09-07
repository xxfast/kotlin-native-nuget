package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.FileSpec
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
 * Value-class exports: plan-only. A reference-underlying value class exports no constructor at
 * all (ADR-035 defers its primary, and the planner skips its secondaries).
 */
internal fun FileSpec.Builder.addValueClassExports(
  cls: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return

  val underlyingProp: KSValueParameter = cls.primaryConstructor!!.parameters.first()
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

  // ADR-035: a reference-underlying value class is a positional record struct over the underlying
  // handle, so C# constructs one directly and no constructor crosses the bridge. Its secondaries
  // are skipped by the planner (`REFERENCE_UNDERLYING_VALUE_CLASS_CONSTRUCTOR`); the deleted
  // adapter here returned the raw underlying object where the C# import expected an IntPtr.
  val constructorSymbols: List<String> = if (isReferenceUnderlying) emptyList() else buildList {
    add("")
    secondaryConstructors.forEachIndexed { index, _ -> add("_${index + 2}") }
  }

  constructorSymbols.forEach { suffix ->
    val planned: ForwardCallablePlan? = callableCatalog.planFor("$qualifiedName.<init>$suffix")
    if (planned != null) addForwardKotlinPlanExport(planned)
  }

  // ADR-082: members come off the catalog, not from a per-declaration plan lookup. Two declared
  // same-name methods share a simple name but not a plan symbol (the planner numbers overloads),
  // and re-deriving `"$qualifiedName.$name"` per `getAllFunctions()` entry emitted the first
  // overload's export twice and the second's never.
  callableCatalog.valueClassProperties(qualifiedName).forEach { addForwardKotlinPlanExport(it) }
  callableCatalog.valueClassMethods(qualifiedName).forEach { addForwardKotlinPlanExport(it) }
}
