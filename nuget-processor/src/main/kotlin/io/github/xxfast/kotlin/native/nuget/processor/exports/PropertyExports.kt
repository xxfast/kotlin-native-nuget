package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports

/**
 * Top-level properties via [ForwardPropertyPlan]. Unplanned properties (specialized protocols,
 * unsupported types) are skipped — no two-call or defaultValueFor fallthrough here.
 */
internal fun FileSpec.Builder.addPropertyExports(
  prop: KSPropertyDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val propName: String = prop.simpleName.asString()
  val planned: ForwardPropertyPlan? =
    callableCatalog.propertyFor("${prop.packageName.asString()}.$propName")
  if (planned == null) return
  // ADR-064: imported behind the plan gate, so a skipped property leaves no dead import.
  addImport(prop.packageName.asString(), propName)
  addForwardPropertyPlanExports(planned)
}
