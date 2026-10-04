package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.squareup.kotlinpoet.FileSpec
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
  // ADR-132 amendment (2026-10-04): looked up by declaration, through the planner's own symbol
  // spelling (`extensionPropertySymbol`), rather than a third hand-spelled copy of it.
  val planned: ForwardPropertyPlan? = callableCatalog.extensionPropertyFor(prop)
  if (planned == null) return
  addImport(prop.packageName.asString(), propName)
  addForwardPropertyPlanExports(planned)
}
