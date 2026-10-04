package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports
import io.github.xxfast.kotlin.native.nuget.processor.forward.enumMembersOf

/**
 * Generates the @CName bridge exports for an enum's own member properties and functions, and for
 * its companion's functions and `val`/`var`s (all rendered into `{Enum}Extensions` on the C# side).
 *
 * ADR-006 amendment: the properties ride the ADR-062 forward property plan
 * ([io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPosition.ENUM_MEMBER]),
 * so each getter carries the error slot and `try`/`catch` every other property route has, and a
 * `var` binds its setter. This file only selects the enum's plans; the plan owns the ABI.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/006-enum-mapping.md">ADR-006: Enum mapping</a>
 */
internal fun FileSpec.Builder.addEnumExports(
  enum: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val qualifiedName: String = enum.qualifiedName?.asString() ?: return
  callableCatalog.propertyPlans.enumMembersOf(qualifiedName)
    .forEach { plan -> addForwardPropertyPlanExports(plan) }
  // ADR-006 amendment: member functions, companion functions and companion `val`/`var`, all off the
  // catalog, so the C# half (`translateEnum`) reads the very same plans.
  callableCatalog.enumMethods(qualifiedName).forEach { plan -> addForwardKotlinPlanExport(plan) }
  callableCatalog.companionMethods(qualifiedName)
    .forEach { plan -> addForwardKotlinPlanExport(plan) }
  callableCatalog.enumCompanionProperties(qualifiedName)
    .forEach { plan -> addForwardPropertyPlanExports(plan) }
  // ADR-094 (write side): the box `NugetMarshal.Boxers` calls for an enum at an erased slot.
  callableCatalog.enumBox(qualifiedName)?.let { plan -> addForwardKotlinPlanExport(plan) }
}
