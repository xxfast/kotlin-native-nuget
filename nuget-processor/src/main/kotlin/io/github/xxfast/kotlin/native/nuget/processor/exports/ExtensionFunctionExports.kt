package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor

/**
 * Extension functions on the ordinary plan path. Unplanned extensions (unsupported receivers,
 * specialized protocols) are skipped — no defaultValueFor / IntPtr fallthrough.
 */
internal fun FileSpec.Builder.addExtensionFunctionExports(
  func: KSFunctionDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val symbol: String = "${func.packageName.asString()}.${func.simpleName.asString()}"
  val plan: ForwardCallablePlan? = callableCatalog.planFor(symbol)
  if (plan != null) addForwardKotlinPlanExport(plan)
}
