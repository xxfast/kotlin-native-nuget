package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports
import io.github.xxfast.kotlin.native.nuget.processor.forward.enumMembersOf

/**
 * Generates the @CName bridge exports for an enum's own member properties.
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
}
