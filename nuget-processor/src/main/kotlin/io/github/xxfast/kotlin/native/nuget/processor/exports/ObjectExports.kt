package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports

/**
 * Generates @CName bridge exports for Kotlin object singletons via the forward plan catalog.
 * Ordinary object methods without a plan are skipped (no pointer/numeric fallthrough).
 *
 * ADR-095: members come off the catalog rather than from a per-declaration plan lookup — with
 * per-object overload numbering the n-th namesake's symbol is `$owner.${name}_$n`, so an
 * unsuffixed `getAllFunctions()` lookup would silently bind every namesake to the first one's plan.
 *
 * ROADMAP Phase 4: the object's own properties ride the same plan, looked up by name (a property
 * cannot be overloaded, so the symbol IS derivable here). The walk must stay byte-identical to
 * `translateObject`'s C# one: a spelling that disagrees makes the property vanish from one half,
 * which is exactly what ADR-055's contract check fails the build over.
 */
internal fun FileSpec.Builder.addObjectExports(
  obj: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val qualifiedName: String = obj.qualifiedName?.asString() ?: return
  obj.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .forEach { prop ->
      val planned: ForwardPropertyPlan? =
        callableCatalog.propertyFor("$qualifiedName.${prop.simpleName.asString()}")
      if (planned != null) addForwardPropertyPlanExports(planned)
    }
  callableCatalog.objectMethods(qualifiedName).forEach { plan -> addForwardKotlinPlanExport(plan) }
}
