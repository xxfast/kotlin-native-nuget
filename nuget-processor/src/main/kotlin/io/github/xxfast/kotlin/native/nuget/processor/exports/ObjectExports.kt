package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport

/**
 * Generates @CName bridge exports for Kotlin object singletons via the forward plan catalog.
 * Ordinary object methods without a plan are skipped (no pointer/numeric fallthrough).
 *
 * ADR-095: members come off the catalog rather than from a per-declaration plan lookup — with
 * per-object overload numbering the n-th namesake's symbol is `$owner.${name}_$n`, so an
 * unsuffixed `getAllFunctions()` lookup would silently bind every namesake to the first one's plan.
 */
internal fun FileSpec.Builder.addObjectExports(
  obj: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val qualifiedName: String = obj.qualifiedName?.asString() ?: return
  callableCatalog.objectMethods(qualifiedName).forEach { plan -> addForwardKotlinPlanExport(plan) }
}
