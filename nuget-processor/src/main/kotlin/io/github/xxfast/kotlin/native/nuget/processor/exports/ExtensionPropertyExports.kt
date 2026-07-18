package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports

/**
 * Extension properties via [ForwardPropertyPlan]. Unplanned properties are skipped.
 */
internal fun FileSpec.Builder.addExtensionPropertyExports(
  prop: KSPropertyDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val propName: String = prop.simpleName.asString()
  val receiverType: KSType = prop.extensionReceiver!!.resolve().expandAliases()
  val receiverSimpleName: String = receiverType.declaration.simpleName.asString()
  val planned: ForwardPropertyPlan? = callableCatalog.propertyFor(
    "${prop.packageName.asString()}.$receiverSimpleName.$propName",
  )
  if (planned != null) addForwardPropertyPlanExports(planned)
}
