package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardExtensionImport
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
  val plan: ForwardCallablePlan = callableCatalog.planFor(func) ?: return
  // ADR-064: imported behind the plan gate, so a skipped extension leaves no dead import. Aliased,
  // never by simple name: the export calls `receiver.<alias>(...)`, which reaches the extension
  // even where a same-named member would win `receiver.y()`, and two same-named extensions from
  // two packages no longer meet as an ambiguous overload pair.
  addForwardExtensionImport(
    packageName = func.packageName.asString(),
    name = func.simpleName.asString(),
    alias = requireNotNull(plan.invocation.extensionImportAlias),
  )
  addForwardKotlinPlanExport(plan)
}
