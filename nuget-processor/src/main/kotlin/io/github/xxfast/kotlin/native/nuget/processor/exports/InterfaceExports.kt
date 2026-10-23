package io.github.xxfast.kotlin.native.nuget.processor.exports

import io.github.xxfast.kotlin.native.nuget.processor.forward.getAllPlainProperties
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.getVisibility
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardOwnerTypeName
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardInterfaceFlowMethods
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardInterfaceFlowProperties
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardInterfaceSuspendMethods
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports
import io.github.xxfast.kotlin.native.nuget.processor.cir.nativePrefix

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
  /** ADR-163: the one symbol table. */
  symbols: ForwardSymbolTable,
  classifier: ForwardBridgeTypeClassifier,
) {
  val qualifiedName: String = iface.qualifiedName?.asString() ?: return
  val prefix: String = iface.nativePrefix(symbols)

  // ADR-174: the interface as an owner of the class route's own async emitters, under its own
  // prefix. The receiver is spelled `asStableRef<Feed>()`, which reaches any implementer
  // (`Crate<Int>` included) through Kotlin's virtual dispatch. The selectors are the ones the C#
  // half reads (`forwardInterface*`), so the two halves carry the same members.
  if (iface.forwardInterfaceSuspendMethods(classifier).isNotEmpty()) {
    addSuspendClassMethodExports(iface, classifier, callableCatalog, symbols, prefix = prefix)
  }
  // ADR-224: a generic interface reads its receiver erased (`asStableRef<Heirloom<Any?>>()`), the
  // ADR-215 spelling a generic class's flow members take.
  val receiver: String = iface.forwardOwnerTypeName() ?: qualifiedName
  iface.forwardInterfaceFlowProperties(classifier).forEach { prop ->
    addFlowPropertyExports(prop, receiver, prefix, classifier)
  }
  iface.forwardInterfaceFlowMethods(classifier).forEach { method ->
    addFlowMethodExports(method, receiver, prefix, classifier, callableCatalog, owner = iface)
  }

  iface.getAllPlainProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    // Interface super-interfaces: every member, own and inherited, since the backing class
    // implements `IDerived` and through it every `IBase`.
    .forEach { prop ->
      val planned: ForwardPropertyPlan? =
        callableCatalog.propertyFor("$qualifiedName.${prop.simpleName.asString()}")
      if (planned != null) addForwardPropertyPlanExports(planned)
    }

  // ADR-090 amendment (2026-09-26): read off the catalog, owner-exact, like `ClassExports`. With
  // overload numbering the n-th namesake's symbol is `$qualifiedName.${name}_$n`, which a
  // declaration walk cannot re-derive (it re-emitted the first overload's plan). Not node-keyed
  // either: an implementer's inherited default member shares the interface member's node.
  callableCatalog.classMethods(qualifiedName).forEach { plan -> addForwardKotlinPlanExport(plan) }

  addFunction(
    FunSpec.builder("export_${prefix}_dispose")
      .addAnnotation(cNameAnnotation("${prefix}_dispose", ownedBy(iface, "generated Dispose")))
      .addParameter("handle", cOpaquePointer)
      .addStatement("%T.release(handle)", nugetHandles)
      .build()
  )
}
