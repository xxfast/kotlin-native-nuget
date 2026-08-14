package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport

/**
 * Extension functions on the ordinary plan path. Unplanned extensions (unsupported receivers,
 * specialized protocols) are skipped — no defaultValueFor / IntPtr fallthrough.
 */
internal fun FileSpec.Builder.addExtensionFunctionExports(
  func: KSFunctionDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  // ADR-095: matched by node identity — extension plan symbols are package-scoped and carry the
  // overload number, so a name-derived key would bind every namesake to the first one's plan.
  val plan: ForwardCallablePlan? = callableCatalog.planFor(func)
  if (plan != null) addForwardKotlinPlanExport(plan)
}
