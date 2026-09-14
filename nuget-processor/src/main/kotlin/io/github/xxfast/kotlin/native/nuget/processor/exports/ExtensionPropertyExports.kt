package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.nestedCsName
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports

/**
 * Extension properties via [ForwardPropertyPlan]. Unplanned properties are skipped, import
 * included: adding it ahead of the plan gate left a dead `import` line in `CNameExports.kt` for
 * every dropped extension property (unsupported receiver, unsupported type, or legacy-routed).
 */
internal fun FileSpec.Builder.addExtensionPropertyExports(
  prop: KSPropertyDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val propName: String = prop.simpleName.asString()
  val receiverType: KSType = prop.extensionReceiver!!.resolve().expandAliases()
  // ADR-133 amendment: the THIRD spelling of an extension property plan symbol (the planner and
  // `CirTranslator` hold the other two), so it chains with them -- `pkg.Aviary.Perch.isHigh`. A
  // stale spelling here is silent: `propertyFor` returns null and the Kotlin export simply never
  // renders, which the ADR-055 contract then reports as a missing Kotlin export for the C# import.
  val receiverSimpleName: String =
    (receiverType.declaration as? KSClassDeclaration)?.nestedCsName()
      ?: receiverType.declaration.simpleName.asString()
  val planned: ForwardPropertyPlan? = callableCatalog.propertyFor(
    "${prop.packageName.asString()}.$receiverSimpleName.$propName",
  )
  if (planned == null) return
  addImport(prop.packageName.asString(), propName)
  addForwardPropertyPlanExports(planned)
}
