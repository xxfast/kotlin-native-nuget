package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmFlowMethods
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmFlowProperties
import io.github.xxfast.kotlin.native.nuget.processor.exports.isCompilerOwnedMember
import io.github.xxfast.kotlin.native.nuget.processor.exports.isForwardFlowType
import io.github.xxfast.kotlin.native.nuget.processor.exports.returnsForwardFlow

/**
 * ADR-159: who owns the coroutine scope in an inheritance chain.
 *
 * One scope per instance, owned by the **first class in the kept chain that projects a scope-using
 * member**; every class below the owner reuses that one scope and inherits `DisposeAsync`. Before
 * this file the scope emission fired only on a base-less class while the flag that drives
 * `Dispose()` came off a raw `getAllFunctions()` scan, so an async member anywhere but the root of a
 * chain generated C# that did not compile (CS0103/CS0108/CS0122/CS0535), and a class whose only
 * async member was *refused* was handed an `IAsyncDisposable` it could not implement.
 *
 * "Projects" is the load-bearing word: the question is what reached the generated artifacts, not
 * what the author declared. Every refusal the async and Flow routes apply (ADR-114 parameter,
 * ADR-119 return, ADR-123 flow element, ADR-147 generic owner, issue #121 opt-in) is applied here
 * too, through the same helpers, which is why this file exists rather than three scans that agree
 * by luck.
 */

/**
 * The suspend members this class projects on the legacy async route: the single selector read by
 * the C# translator (`translateClass`'s `allSuspendMethods`), the Kotlin export builder
 * (`addSuspendClassMethodExports`) and the export gate in `NugetProcessor`. Before ADR-159 the
 * Kotlin half filtered on strictly less than the C# half and emitted strays like
 * `paddedwindowseat_settle_async`, invisible to `ForwardAbiContract` because it filters Kotlin
 * exports down to the C# import set.
 *
 * [declaredOnly] is the sealed-arm rule (ADR-118): an arm carries what it declares, because the
 * generated sealed base carries nothing. [superClass] is the *kept* base (`forwardSuperClass`), so
 * a member inherited from a base with a generated C# class of its own belongs to that base.
 */
internal fun KSClassDeclaration.forwardSuspendRouteMethods(
  classifier: ForwardBridgeTypeClassifier,
  superClass: KSClassDeclaration?,
  declaredOnly: Boolean = false,
): List<KSFunctionDeclaration> {
  // ADR-147: the suspend route spells `asStableRef<Crate>()`, which does not compile for a generic
  // owner. Refused there on both halves, so a generic class projects no scope-using member either.
  if (typeParameters.isNotEmpty()) return emptyList()
  return getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.modifiers.contains(Modifier.SUSPEND) }
    .filter { method -> !method.isCompilerOwnedMember(this) }
    .filter { !declaredOnly || it.parentDeclaration == this }
    // ADR-114 / ADR-119: a parameter or return this route cannot marshal drops the member on both
    // halves, named once by `warnRefusedLegacyRouteMembers`.
    .filter { method -> classifier.legacyRefusedParameter(method.parameters) == null }
    .filter { method -> classifier.legacyRefusedReturn(method) == null }
    .filter { method -> declaredOnly || method.isForwardMemberOf(this, superClass) }
    .filter { method -> declaredOnly || !method.reProjectsKeptBaseMember(this, superClass) }
    .toList()
}

/**
 * The Flow-returning methods this class projects on the legacy Flow route, the ordinary-class twin
 * of [forwardArmFlowMethods][io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmFlowMethods].
 * Read only by the scope question: the two artifact halves already agree on this route (both apply
 * `isForwardMemberOf`), which is why the Flow twin of the shape compiled and leaked instead of
 * failing the build.
 */
internal fun KSClassDeclaration.forwardClassFlowMethods(
  classifier: ForwardBridgeTypeClassifier,
  superClass: KSClassDeclaration?,
): List<KSFunctionDeclaration> {
  if (typeParameters.isNotEmpty()) return emptyList()
  return getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { !it.modifiers.contains(Modifier.SUSPEND) }
    .filter { method -> !method.isCompilerOwnedMember(this) }
    .filter { it.returnsForwardFlow() }
    .filter { method -> classifier.legacyRefusedParameter(method.parameters) == null }
    .filter { method -> classifier.legacyRefusedReturn(method) == null }
    .filter { method -> method.isForwardMemberOf(this, superClass) }
    .toList()
}

/** The Flow/StateFlow-typed properties this class projects; a scope user like any Flow method. */
internal fun KSClassDeclaration.forwardClassFlowProperties(
  classifier: ForwardBridgeTypeClassifier,
  superClass: KSClassDeclaration?,
): List<KSPropertyDeclaration> = getAllProperties()
  .filter { it.getVisibility() == Visibility.PUBLIC }
  .filter { prop -> prop.type.resolve().expandAliases().isForwardFlowType() }
  .filter { prop -> !prop.isOptInRefused(classifier.exportMarkers) }
  .filter { prop -> classifier.legacyRefusedFlowElement(prop.type.resolve()) == null }
  .filter { prop -> prop.isForwardMemberOf(this, superClass) }
  .toList()

/**
 * Whether this class projects at least one member that needs a coroutine scope: a suspend member, a
 * Flow-returning method or a Flow/StateFlow property. The ADR-114 refusal check the ROADMAP asked
 * for, in the one place the answer is now derived.
 */
internal fun KSClassDeclaration.forwardDeclaresScopeMember(
  classifier: ForwardBridgeTypeClassifier,
  exportedTypes: Set<String>,
): Boolean {
  // ADR-118 / ADR-124: an arm's member surface is declared-only, on all three halves.
  val declaredOnly: Boolean = isSealedSubclass()
  val keptBase: KSClassDeclaration? = if (declaredOnly) null else forwardSuperClass(exportedTypes)
  if (forwardSuspendRouteMethods(classifier, keptBase, declaredOnly).isNotEmpty()) return true
  if (declaredOnly) {
    // The arm route's own two selectors, so an arm answers exactly what `CirSealedRenderer` emits.
    return forwardArmFlowMethods(classifier).isNotEmpty() ||
        forwardArmFlowProperties(classifier).isNotEmpty()
  }
  return forwardClassFlowMethods(classifier, keptBase).isNotEmpty() ||
      forwardClassFlowProperties(classifier, keptBase).isNotEmpty()
}

/**
 * The class in `this`'s kept chain that owns the scope: the **root-most** class that projects a
 * scope-using member, `this` included. Null when nothing in the chain does, which is the "no
 * `IAsyncDisposable` at all" answer.
 *
 * Root-most rather than nearest, because a scope per level would hide the ancestor's field (CS0108)
 * and drain twice. A sealed arm can be an open base of an ordinary class (ADR-009 amendment), so
 * the walk answers for arm owners too, through [forwardDeclaresScopeMember]'s declared-only arm.
 */
internal fun KSClassDeclaration.forwardScopeOwner(
  classifier: ForwardBridgeTypeClassifier,
  exportedTypes: Set<String>,
): KSClassDeclaration? {
  val keptChain: List<KSClassDeclaration> = listOf(this) +
      declaredBaseChain().filter { base -> base.qualifiedName?.asString() in exportedTypes }
  return keptChain.asReversed()
    .firstOrNull { owner -> owner.forwardDeclaresScopeMember(classifier, exportedTypes) }
}

/**
 * Whether an `override suspend fun` re-projects a member a kept base already projected. Skipped on
 * the derived class (ADR-159 rule 5): the base's export calls the member on
 * `asStableRef<Base>().get()`, so Kotlin's own dynamic dispatch reaches the override, and a second
 * C# `FillAsync` on the subclass is CS0108 for no gain.
 *
 * Kept only when the overridee sits on a *dropped* base (ADR-101): that base has no generated C#
 * class, so this class is the only carrier there is and the member must project here.
 */
private fun KSFunctionDeclaration.reProjectsKeptBaseMember(
  cls: KSClassDeclaration,
  superClass: KSClassDeclaration?,
): Boolean {
  val overridee: KSClassDeclaration = (baseClassOverridee(superClass)?.parentDeclaration)
      as? KSClassDeclaration ?: return false
  val qualified: String = overridee.qualifiedName?.asString() ?: return false
  return cls.droppedBaseChain(superClass).none { it.qualifiedName?.asString() == qualified }
}
