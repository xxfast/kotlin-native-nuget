package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
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
 * One scope per instance, owned by the **first class in the kept chain that projects a
 * scope-using member**; every class below the owner reuses that one scope and inherits
 * `DisposeAsync`. Before this file the scope emission fired only on a base-less class while the
 * flag that drives `Dispose()` came off a raw `getAllFunctions()` scan, so an async member
 * anywhere but the root of a chain generated C# that did not compile
 * (CS0103/CS0108/CS0122/CS0535), and a class whose only async member was *refused* was handed an
 * `IAsyncDisposable` it could not implement.
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
 * [isArm] is the sealed-arm rule (ADR-118, widened by the ADR-101 amendment of 2026-09-27): an
 * arm binds its declared suspend members plus those inherited from an interface the sealed type
 * does not carry ([isForwardArmMember]). The sealed base's own suspend members stay off the arm.
 * ADR-175: the sealed base now projects its own async members too, so an arm's `override` of one
 * is not re-projected ([forwardArmMemberProjectedByBase], ADR-159 rule 4: the base export reaches
 * it through Kotlin's dispatch); an arm member the base does not declare still binds on the arm.
 * [superClass]
 * is the *kept* base (`forwardSuperClass`), so a member inherited from a base with a generated C#
 * class of its own belongs to that base; unused for an arm.
 */
internal fun KSClassDeclaration.forwardSuspendRouteMethods(
  classifier: ForwardBridgeTypeClassifier,
  superClass: KSClassDeclaration?,
  isArm: Boolean = false,
): List<KSFunctionDeclaration> {
  // ADR-147: the suspend route spells `asStableRef<Crate>()`, which does not compile for a generic
  // owner. Refused there on both halves, so a generic class projects no scope-using member either.
  if (typeParameters.isNotEmpty()) return emptyList()
  return getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.modifiers.contains(Modifier.SUSPEND) }
    .filter { method -> !method.isCompilerOwnedMember(this) }
    .filter { method -> !isArm || isForwardArmMember(method) }
    .filter { method -> !isArm || !forwardArmMemberProjectedByBase(method, classifier) }
    // ADR-114 / ADR-119: a parameter or return this route cannot marshal drops the member on both
    // halves, named once by `warnRefusedLegacyRouteMembers`.
    .filter { method -> classifier.legacyRefusedParameter(method.parameters) == null }
    .filter { method -> classifier.legacyRefusedReturn(method) == null }
    .filter { method -> isArm || method.isForwardMemberOf(this, superClass) }
    .filter { method -> isArm || !method.reProjectsKeptBaseMember(this, superClass) }
    .toList()
}

/**
 * The Flow-returning methods this class projects on the legacy Flow route, the ordinary-class
 * twin of [forwardArmFlowMethods]. Read only by the scope question: the two artifact halves
 * already agree on this route (both apply `isForwardMemberOf`), which is why the Flow twin of
 * the shape compiled and leaked instead of failing the build.
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
  // ADR-174: the generic-owner guard its two siblings above always had. The C# property half
  // refuses a generic owner (`CirClassTranslator`'s flow-property branch), so without it `Crate<T>`
  // owned a scope for a StateFlow property it never projected (ADR-159's rule, broken).
  .filter { typeParameters.isEmpty() }
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
  // ADR-118 / ADR-124: an arm's member surface is the arm route's own, on all three halves.
  val isArm: Boolean = isSealedSubclass()
  val keptBase: KSClassDeclaration? = if (isArm) null else forwardSuperClass(exportedTypes)
  if (forwardSuspendRouteMethods(classifier, keptBase, isArm).isNotEmpty()) return true
  // ADR-174: a generic implementer projects none of its own async members (ADR-147, kept), but it
  // does carry the explicit implementations of every interface async member it forwards, and those
  // run on its scope.
  if (forwardAsyncInterfaceForwards(classifier).isNotEmpty()) return true
  if (isArm) {
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
 * the walk answers for arm owners too, through [forwardDeclaresScopeMember]'s arm branch.
 *
 * ADR-175: an arm's sealed base is in the chain, whichever kind it is. [declaredBaseChain] keeps
 * only `CLASS` supertypes, so a sealed *interface* base (ADR-112's abstract class) is spliced in
 * after the arm that lists it; the sealed base can own the scope now that it projects async
 * members of its own.
 */
internal fun KSClassDeclaration.forwardScopeOwner(
  classifier: ForwardBridgeTypeClassifier,
  exportedTypes: Set<String>,
): KSClassDeclaration? {
  val keptChain: List<KSClassDeclaration> = (listOf(this) + declaredBaseChain())
    .flatMap { level ->
      val sealed: KSClassDeclaration? =
        if (level.isSealedSubclass()) level.forwardArmSealedParent() else null
      listOfNotNull(level, sealed)
    }
    .filterIndexed { index, level ->
      index == 0 || level.qualifiedName?.asString() in exportedTypes || level.isEligibleSealedType()
    }
    .distinctBy { level -> level.qualifiedName?.asString() }
  return keptChain.asReversed()
    .firstOrNull { owner -> owner.forwardDeclaresScopeMember(classifier, exportedTypes) }
}

/**
 * ADR-175: the async members a sealed base projects on its own prefix, on the ordinary class's
 * selectors with the base's kept base (ADR-159): the suspend and Flow-returning methods, then the
 * Flow/StateFlow properties. Empty for a generic sealed base (ADR-147, kept by both selectors).
 */
internal fun KSClassDeclaration.forwardSealedBaseAsyncMethods(
  classifier: ForwardBridgeTypeClassifier,
): List<KSFunctionDeclaration> {
  val keptBase: KSClassDeclaration? = forwardSuperClass(classifier.exportedObjectHandles)
  return forwardSuspendRouteMethods(classifier, keptBase) +
      forwardClassFlowMethods(classifier, keptBase)
}

/** ADR-175: the Flow/StateFlow-property half of [forwardSealedBaseAsyncMethods]. */
internal fun KSClassDeclaration.forwardSealedBaseFlowProperties(
  classifier: ForwardBridgeTypeClassifier,
): List<KSPropertyDeclaration> =
  forwardClassFlowProperties(classifier, forwardSuperClass(classifier.exportedObjectHandles))

/**
 * ADR-175 (ADR-159 rule 4 on the sealed route): whether [member], on this sealed arm, is a member
 * its sealed base already projects. Such an override is not re-projected on the arm, on either
 * half: the base's export calls it on `asStableRef<Base>().get()`, so Kotlin's dispatch reaches
 * the arm's body, and a second C# `AreaAsync` on the arm would hide the base's (CS0108). Matched
 * on signature (ADR-082's wildcard key) for a method and on name for a property.
 */
internal fun KSClassDeclaration.forwardArmMemberProjectedByBase(
  member: KSDeclaration,
  classifier: ForwardBridgeTypeClassifier,
): Boolean {
  val sealed: KSClassDeclaration = forwardArmSealedParent() ?: return false
  return when (member) {
    is KSFunctionDeclaration -> {
      val key: List<String> = member.forwardSignatureKey()
      sealed.forwardSealedBaseAsyncMethods(classifier)
        .any { projected -> projected.forwardInheritedSignatureKey().admits(key) }
    }

    is KSPropertyDeclaration -> sealed.forwardSealedBaseFlowProperties(classifier)
      .any { projected -> projected.simpleName.asString() == member.simpleName.asString() }

    else -> false
  }
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

/**
 * ADR-174: the interfaces whose `suspend`/`Flow`/`StateFlow` members are declared on `I<Name>`,
 * dispatched by the ADR-040 backing wrapper and exported under the interface's own prefix. Only a
 * REACHABLE interface qualifies (it is the one with a backing wrapper and dispatch exports), so the
 * set is filled once per KSP round by `NugetProcessor`, exactly as [ForwardDeclaredTypeNames] is.
 * Empty means "no interface carries async members", which is the pre-ADR-174 behaviour.
 */
internal object ForwardAsyncInterfaces {
  private val reachable: MutableSet<String> = mutableSetOf()

  fun reset(interfaces: Iterable<KSClassDeclaration>) {
    reachable.clear()
    interfaces.mapNotNullTo(reachable) { it.qualifiedName?.asString() }
  }

  /**
   * Whether [iface] carries the async surface: reachable, not generic (ADR-174 ruling 3: the
   * receiver would need type arguments, the ADR-147 reason) and not sealed (ruling 2: never an
   * interface type, so never reachable anyway; tested here too so the answer does not rest on it).
   */
  fun carries(iface: KSClassDeclaration): Boolean =
    iface.classKind == ClassKind.INTERFACE &&
        iface.qualifiedName?.asString() in reachable &&
        iface.typeParameters.isEmpty() &&
        !iface.isSealedInterface()
}

/**
 * ADR-174 ruling 1: the interface's async members are the class route's own selectors, called on
 * the interface, so one refusal predicate drops a member from every half at once. A base-less owner
 * (`superClass = null`) binds every member, own and inherited, which is what the backing wrapper
 * implements.
 */
internal fun KSClassDeclaration.forwardInterfaceSuspendMethods(
  classifier: ForwardBridgeTypeClassifier,
): List<KSFunctionDeclaration> =
  if (!ForwardAsyncInterfaces.carries(this)) emptyList()
  else forwardSuspendRouteMethods(classifier, superClass = null)

/** The Flow-returning half of [forwardInterfaceSuspendMethods]. */
internal fun KSClassDeclaration.forwardInterfaceFlowMethods(
  classifier: ForwardBridgeTypeClassifier,
): List<KSFunctionDeclaration> =
  if (!ForwardAsyncInterfaces.carries(this)) emptyList()
  else forwardClassFlowMethods(classifier, superClass = null)

/** The Flow/StateFlow property half of [forwardInterfaceSuspendMethods]. */
internal fun KSClassDeclaration.forwardInterfaceFlowProperties(
  classifier: ForwardBridgeTypeClassifier,
): List<KSPropertyDeclaration> =
  if (!ForwardAsyncInterfaces.carries(this)) emptyList()
  else forwardClassFlowProperties(classifier, superClass = null)

/** Whether the interface projects any scope-using member (ruling 5: `IAsyncDisposable`). */
internal fun KSClassDeclaration.forwardInterfaceDeclaresScopeMember(
  classifier: ForwardBridgeTypeClassifier,
): Boolean = forwardInterfaceSuspendMethods(classifier).isNotEmpty() ||
    forwardInterfaceFlowMethods(classifier).isNotEmpty() ||
    forwardInterfaceFlowProperties(classifier).isNotEmpty()

/**
 * ADR-174 ruling 4: the carrying interfaces a GENERIC class forwards async members of, through C#
 * explicit interface implementations. Empty for a non-generic class, whose own class routes
 * project those members (ruling 1). Each interface forwards only the members it DECLARES, which is
 * where `I<Name>` declares them; an inherited one is forwarded under its declaring interface.
 */
internal fun KSClassDeclaration.forwardAsyncInterfaceForwards(
  classifier: ForwardBridgeTypeClassifier,
): List<KSClassDeclaration> {
  if (typeParameters.isEmpty()) return emptyList()
  return getAllSuperTypes()
    .mapNotNull { it.declaration as? KSClassDeclaration }
    .filter { iface -> ForwardAsyncInterfaces.carries(iface) }
    .filter { iface -> iface.forwardInterfaceDeclaresOwnScopeMember(classifier) }
    .distinctBy { it.qualifiedName?.asString() }
    .toList()
}

/** Whether [this] interface DECLARES (not inherits) a scope-using member it carries. */
internal fun KSClassDeclaration.forwardInterfaceDeclaresOwnScopeMember(
  classifier: ForwardBridgeTypeClassifier,
): Boolean {
  val hierarchy = ForwardInterfaceHierarchy(this, classifier.exportedObjectHandles)
  fun KSDeclaration.declared(): Boolean = forwardAsyncPlacement(this, hierarchy, classifier).let {
    it == ForwardInterfaceMemberPlacement.DECLARED ||
        it == ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE
  }
  return (forwardInterfaceSuspendMethods(classifier) + forwardInterfaceFlowMethods(classifier))
    .any { it.declared() } ||
      forwardInterfaceFlowProperties(classifier).any { it.declared() }
}

/**
 * ADR-174 amendment: where `I<Name>` declares an async member is the sync route's placement, not a
 * lexical owner test: DECLARED (own, or re-homed from an unexported super) or DIAMOND_OVERRIDE
 * (redeclared with `new`). An identical override of a kept super's member stays on the super
 * (CS0108 otherwise); an inherited one is declared on the super that carries it.
 *
 * One departure from the sync placement: a member reached only through kept supers that do NOT
 * carry async members (a generic super, ruling 3, which the reachability closure never promotes)
 * would be declared on no interface, while [this] still counts it for `IAsyncDisposable`, leaving a
 * generic implementer without its forwards (CS0535). So that member is re-homed onto [this], the
 * nearest carrying interface, as an unexported super's member is: DECLARED.
 */
internal fun KSClassDeclaration.forwardAsyncPlacement(
  member: KSDeclaration,
  hierarchy: ForwardInterfaceHierarchy,
  classifier: ForwardBridgeTypeClassifier,
): ForwardInterfaceMemberPlacement? {
  val placed: ForwardInterfaceMemberPlacement? = hierarchy.asyncPlacement(member)
  val reachedThroughSuper: Boolean = placed == ForwardInterfaceMemberPlacement.INHERITED ||
      placed == ForwardInterfaceMemberPlacement.IDENTICAL_OVERRIDE
  if (!reachedThroughSuper || carriedBySuper(member, classifier)) return placed
  return ForwardInterfaceMemberPlacement.DECLARED
}

/** Whether a carrying super-interface of [this] projects an async member of [member]'s shape. */
private fun KSClassDeclaration.carriedBySuper(
  member: KSDeclaration,
  classifier: ForwardBridgeTypeClassifier,
): Boolean {
  val owner: KSClassDeclaration = this
  // Parameter types substituted as members of [owner], so `Satchel<T>.fetch(t: T)` seen through
  // `Haversack : Satchel<Int>` compares as `fetch(Int)`; `null` for a property.
  fun KSDeclaration.parameterTypes(): List<String?>? {
    val function: KSFunctionDeclaration = this as? KSFunctionDeclaration ?: return null
    val types: List<KSType?> = try {
      function.asMemberOf(owner.asStarProjectedType()).parameterTypes
    } catch (_: IllegalArgumentException) {
      function.parameters.map { it.type.resolve() }
    }
    return types.map { type -> type?.declaration?.qualifiedName?.asString() }
  }
  val name: String = member.simpleName.asString()
  val parameters: List<String?>? = member.parameterTypes()
  fun KSDeclaration.sameShape(): Boolean =
    simpleName.asString() == name && parameterTypes() == parameters
  return getAllSuperTypes()
    .mapNotNull { it.declaration as? KSClassDeclaration }
    .filter { ForwardAsyncInterfaces.carries(it) }
    .any { base ->
      (
        base.forwardInterfaceSuspendMethods(classifier) +
          base.forwardInterfaceFlowMethods(classifier)
      )
        .any { it.sameShape() } ||
          base.forwardInterfaceFlowProperties(classifier).any { it.sameShape() }
    }
}

/** [ForwardInterfaceHierarchy.placement] for an async member, a function or a Flow property. */
internal fun ForwardInterfaceHierarchy.asyncPlacement(
  member: KSDeclaration,
): ForwardInterfaceMemberPlacement? = when (member) {
  is KSFunctionDeclaration -> placement(member)
  is KSPropertyDeclaration -> placement(member)
  else -> null
}
