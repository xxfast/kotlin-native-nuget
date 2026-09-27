package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardValueClassUnderlying
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.forward.valueClassUnderlying

/**
 * Value-class exports: plan-only. A reference-underlying value class exports no *primary*
 * constructor (ADR-035 keeps its positional record one), but its secondaries do cross since the
 * 2026-09-11 amendment.
 */
internal fun FileSpec.Builder.addValueClassExports(
  cls: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
  classifier: ForwardBridgeTypeClassifier,
) {
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return

  // The planner reads the same rule, so the two halves agree on the `_create` export. A private
  // qualified-name list here used to call `Char` and a plain interface references while the
  // planner called them values, and KSP aborted on the missing Kotlin half.
  val role: ForwardValueClassUnderlying = classifier.valueClassUnderlying(cls)
  if (role == ForwardValueClassUnderlying.REFUSED) return
  val isReferenceUnderlying: Boolean = role == ForwardValueClassUnderlying.REFERENCE

  val secondaryConstructors: List<KSFunctionDeclaration> = cls.declarations
    .filterIsInstance<KSFunctionDeclaration>()
    .filter { it.simpleName.asString() == "<init>" }
    .filter { it != cls.primaryConstructor }
    .toList()

  // ADR-035: a reference-underlying value class is a positional record struct over the underlying
  // handle, so C# already constructs one and the primary does not cross. Its secondaries do
  // (2026-09-11 amendment): each returns the underlying as a fresh handle for C# to rebuild.
  val constructorSymbols: List<String> = buildList {
    if (!isReferenceUnderlying) add("")
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
  // ADR-171: the box/unbox pair `NugetMarshal.Wrap<T>` / `Factories` call at an erased position.
  callableCatalog.valueClassBoxing(qualifiedName)?.toList()
    ?.forEach { addForwardKotlinPlanExport(it) }
}
