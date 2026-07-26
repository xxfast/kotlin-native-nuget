package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.getVisibility
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor

/**
 * ADR-040: `@CName` interface-dispatch exports for a reachable Kotlin interface's own declared
 * members (`pet_get_name`, `pet_speak`, ...) plus `{prefix}_dispose`, generated only for the
 * reachability-driven subset of interfaces that get a concrete `sealed class Foo : IFoo` backing
 * wrapper on the C# side (see `NugetProcessor`'s reachable-interfaces computation). Bodies come
 * from the same plan-driven emitters as an ordinary class (`addForwardKotlinPlanExport` /
 * `addForwardPropertyPlanExports`) over the plans built by `ForwardCallablePlanner
 * .interfaceEntries` / `ForwardPropertyPlanner.interfaceProperties` — this file only wires the
 * lookup, it does not hand-roll any export body.
 *
 * Distinct from `InterfaceBridgeExports.kt`, which is ADR-039's unrelated `add*`/`remove*`
 * subscription route (C# implementing a Kotlin interface, not Kotlin returning one).
 */
internal fun FileSpec.Builder.addInterfaceExports(
  iface: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val qualifiedName: String = iface.qualifiedName?.asString() ?: return
  val prefix: String = iface.simpleName.asString().lowercase()

  iface.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { prop -> prop.parentDeclaration == iface }
    .forEach { prop ->
      val planned: ForwardPropertyPlan? =
        callableCatalog.propertyFor("$qualifiedName.${prop.simpleName.asString()}")
      if (planned != null) addForwardPropertyPlanExports(planned)
    }

  iface.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in setOf("equals", "hashCode", "toString", "<init>") }
    .filter { method -> method.parentDeclaration == iface }
    .forEach { method ->
      val planned: ForwardCallablePlan? =
        callableCatalog.planFor("$qualifiedName.${method.simpleName.asString()}")
      if (planned != null) addForwardKotlinPlanExport(planned)
    }

  addFunction(
    FunSpec.builder("export_${prefix}_dispose")
      .addAnnotation(cNameAnnotation("${prefix}_dispose"))
      .addParameter("handle", cOpaquePointer)
      .addStatement("handle.asStableRef<%L>().dispose()", qualifiedName)
      .build()
  )
}
