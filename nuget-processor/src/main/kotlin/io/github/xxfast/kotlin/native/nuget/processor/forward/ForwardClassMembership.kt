package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier

/**
 * The declared base class, ungated: what the class *says* it extends, whether or not the export
 * set carries it. Read by `CirClassTranslator.translateClass`, to decide whether a
 * `SKIPPED_UNEXPORTED_SUPERTYPE` diagnostic is owed for a base [forwardSuperClass] is about to
 * drop, and by [isSealedSubclass], which asks the same ungated question about sealedness. No other
 * site may read it: asking what a class declares, rather than what the forward pipeline can
 * actually generate, is exactly the CS0246 in issue #42.
 */
internal fun KSClassDeclaration.declaredSuperClass(): KSClassDeclaration? = superTypes
  .map { type -> type.resolve().declaration }
  .filterIsInstance<KSClassDeclaration>()
  .firstOrNull { declaration ->
    declaration.classKind == ClassKind.CLASS &&
        declaration.qualifiedName?.asString() != "kotlin.Any"
  }

/**
 * ADR-112: the one eligibility test for a `sealed interface` taking the ADR-009 sealed-class route,
 * and the reason it is refused when it cannot. `null` means eligible.
 *
 * Only meaningful for a sealed interface; every other declaration answers `null` here and is gated
 * by [isEligibleSealedType] instead.
 *
 * The generated shape is an `abstract class` with `sealed` subclasses and one flat `FromHandle`
 * switch, so the hierarchy has to be expressible as exactly that: no type parameters (the sealed
 * route renders none), no second superclass (a C# arm can only extend the abstract base) and no
 * sub-interface (the discriminator is a flat `when` over `getSealedSubclasses()`, and a
 * sub-interface has no single C# class to construct).
 *
 * ADR-125 (issue #130): where the arm is *declared* is not one of those constraints. This used to
 * require every arm nested in the interface, which is a style rule rather than a C# one: discovery
 * is `getSealedSubclasses()` either way, and `CirSealedRenderer` already outdents a sibling arm to
 * namespace level (shipped for sealed classes since ADR-009's issue-#54 amendment). Nesting was,
 * however, implicitly buying the two refusals below, because a nested declaration has exactly one
 * enclosing declaration and a nested enum is never declared by `rootEnums`:
 * - an `enum class` arm, which a C# enum cannot be (it admits only an integral base, CS1008), and
 * - an arm implementing two sealed interfaces, which C# single inheritance cannot express (the
 *   renderer would outdent one `public sealed class` per base under the same namespace, CS0101).
 *
 * ADR-112 amendment (2026-09-11): *every* refusing arm is named, `; `-joined in
 * `getSealedSubclasses()` order. One refusing arm reads exactly as it did before (a one-element
 * join is the element); two of them now take one rebuild to fix rather than one rebuild each.
 */
internal fun KSClassDeclaration.sealedInterfaceIneligibility(): String? {
  if (!isSealedInterface()) return null
  if (typeParameters.isNotEmpty()) return "it has type parameters"
  val reasons: List<String> = getSealedSubclasses().mapNotNull { it.armIneligibility() }.toList()
  return reasons.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

/**
 * Why this arm cannot be an arm of a C# sealed hierarchy, or `null` if it can.
 *
 * The first reason on the arm wins, because every one of them is fixed by the same edit (declare
 * the arm as a plain class or object with this interface as its only parent). Across arms,
 * [sealedInterfaceIneligibility] names them all: two independently refusing arms used to cost the
 * author one rebuild each, because the walk returned the first refusal in `getSealedSubclasses()`
 * order as the whole reason.
 */
private fun KSClassDeclaration.armIneligibility(): String? {
  val name: String = simpleName.asString()
  if (classKind == ClassKind.INTERFACE) return "subclass `$name` is an interface"
  if (classKind == ClassKind.ENUM_CLASS) {
    return "subclass `$name` is an enum class, and a C# enum can only extend an integral " +
        "type (CS1008), never the abstract class an arm is declared as"
  }
  val base: KSClassDeclaration? = declaredSuperClass()
  if (base != null) {
    val baseName: String = base.qualifiedName?.asString() ?: base.simpleName.asString()
    return "subclass `$name` extends another class `$baseName`"
  }
  if (sealedInterfaceSupertypes() > 1) {
    return "subclass `$name` implements more than one sealed interface, and a C# class can " +
        "extend only one base"
  }
  return null
}

/**
 * How many sealed interfaces this declaration lists as a supertype.
 *
 * [isSealedInterface], deliberately, and not [isEligibleSealedInterface]: eligibility is what this
 * count is being asked for, so two interfaces sharing an arm would ask each other for it forever.
 */
private fun KSClassDeclaration.sealedInterfaceSupertypes(): Int = superTypes
  .map { type -> type.resolve().declaration }
  .filterIsInstance<KSClassDeclaration>()
  .count { it.isSealedInterface() }

/** A `sealed interface`, eligible or not. */
internal fun KSClassDeclaration.isSealedInterface(): Boolean =
  classKind == ClassKind.INTERFACE && modifiers.contains(Modifier.SEALED)

/** ADR-112: a sealed interface the ADR-009 sealed-class route can carry. */
internal fun KSClassDeclaration.isEligibleSealedInterface(): Boolean =
  isSealedInterface() && sealedInterfaceIneligibility() == null

/**
 * The sealed types the ADR-009 route owns: a sealed *class*, or (ADR-112) an eligible sealed
 * interface. The single test behind `rootSealedClasses`, the reachability closure's bucket, the
 * classifier's discriminator and [isSealedSubclass], because four hand-copied `classKind == CLASS`
 * conditions are how a sealed interface came to be claimed by two routes and finished by neither.
 */
internal fun KSClassDeclaration.isEligibleSealedType(): Boolean =
  modifiers.contains(Modifier.SEALED) &&
      (classKind == ClassKind.CLASS || isEligibleSealedInterface())

/**
 * Whether this class is a subclass of a sealed class, wherever it is *declared*: nested inside its
 * base (`Shape.Circle`) or beside it at top level (`data class Label : Shape()`).
 *
 * Issue #54: a sibling subclass is a top-level public class in its own right, so the ordinary class
 * route collected it as well as the ADR-009 sealed route did, and one Kotlin type became two
 * unrelated C# types (a namespace-level `Label` with `label_*` exports and a nested `Shape.Label`
 * with `shape_label_*` exports) that no `is` check could agree about. The sealed route is the sole
 * owner of a sealed subclass, so both root collection (`NugetProcessor.rootClasses`) and the
 * dependency-module closure (`ForwardReachabilityClosure`) exclude one from the plain-class bucket
 * and reach it only through its base's `getSealedSubclasses()`.
 *
 * Ungated on purpose ([declaredSuperClass], not [forwardSuperClass]): a base outside the export set
 * still owns its subclasses, and admitting the subclass as a plain class in that case would emit
 * the very duplicate this predicate exists to prevent.
 *
 * ADR-112: a subclass of a sealed *interface* is one too, but only when that interface is
 * [isEligibleSealedInterface] and therefore actually declared by the sealed route.
 * [declaredSuperClass] cannot answer this: it keeps only `CLASS` supertypes, so the interface is
 * invisible to it, and the subclass used to read as an ordinary nested class (refused by the
 * closure, classified `Unsupported`, reported `SKIPPED_NESTED_DECLARATION`). An *ineligible*
 * sealed interface still has no sealed route, so its subclasses keep exactly that handling.
 */
internal fun KSClassDeclaration.isSealedSubclass(): Boolean =
  declaredSuperClass()?.modifiers?.contains(Modifier.SEALED) == true ||
      superTypes
        .map { type -> type.resolve().declaration }
        .filterIsInstance<KSClassDeclaration>()
        .any { it.isEligibleSealedInterface() }

/**
 * ADR-112 amendment: an arm of an *ineligible* sealed interface, the half [isSealedSubclass]
 * deliberately does not claim.
 *
 * Such an arm is not a sealed subclass for routing purposes (there is no sealed route to send it
 * to, which is exactly why the eligibility gate above exists), so it keeps plain-class handling and
 * is declared by nobody. Its absence from C# is already explained, once, by its interface's
 * `SKIPPED_INELIGIBLE_SEALED_INTERFACE`, which names the refusing arm and the C# constraint behind
 * it. Naming the arm a second time as `SKIPPED_NESTED_DECLARATION` would hand the author the hint
 * "move it to the top level of its file", and ADR-125 made the declaration position irrelevant to
 * eligibility, so that move fixes nothing.
 *
 * By supertype, not by enclosing declaration: a nested *non*-arm helper inside the same refused
 * interface is skipped for the ordinary nesting reason and still says so, and an arm nested inside
 * some unrelated class is still covered by its interface's warning.
 *
 * Diagnostic-only. No root bucket, closure or planner may read this: admitting an arm of an
 * interface that has no sealed route is the CS0101 duplicate ADR-112 refuses.
 */
internal fun KSClassDeclaration.isArmOfIneligibleSealedInterface(): Boolean = superTypes
  .map { type -> type.resolve().declaration }
  .filterIsInstance<KSClassDeclaration>()
  .any { it.isSealedInterface() && !it.isEligibleSealedInterface() }

/**
 * The one has-superclass predicate the forward direction uses to decide which of a class's
 * `getAll*()` members belong to *its* surface.
 *
 * There used to be two, and they disagreed: `CirClassTranslator` counted only `ClassKind.CLASS`
 * supertypes, while `ForwardPropertyPlanner` counted any non-`Any` supertype (interfaces
 * included). An interface-only class fell into the gap: the translator kept its inherited
 * interface members and rendered `public class Host : IGreeter`, while neither planner ever
 * planned them, so the generated C# declared an interface whose members it did not implement
 * (CS0535 in every consumer). The `ClassKind.CLASS` reading is the one kept, because a C# subclass
 * genuinely inherits its base class's members: re-binding them on the subclass would emit a
 * member that hides the base one (CS0108), while an interface member has no such carrier and has
 * to be bound on the implementing class itself.
 *
 * ADR-101 amendment (2026-09-05), issue #42's base-class half: the answer is now gated on
 * [exportedTypes], the qualified-name-keyed export set. A base outside it has no generated C#
 * class of its own, so naming it in the base list is a guaranteed CS0246 — and, unlike a dropped
 * interface, a dropped base class would otherwise take real callable members with it. Null here
 * means "base-less for every forward consumer", decided in one place: the translator renders no
 * base-list entry, both planners bind the inherited *concrete* members on this class with no
 * `override`, and the Kotlin emitter exports them with this class as the receiver. The set is the
 * translator's `exportedTypes` or, equivalently, the planners'
 * [ForwardBridgeTypeContext.exportedObjectHandles] — the same five buckets under the same key.
 */
internal fun KSClassDeclaration.forwardSuperClass(
  exportedTypes: Set<String>,
): KSClassDeclaration? = declaredBaseChain()
  .firstOrNull { base -> base.qualifiedName?.asString() in exportedTypes }

/**
 * The declared base classes, nearest first: `X`'s own base, then that base's base, up to (but not
 * including) `kotlin.Any`.
 *
 * ADR-101 amendment (2026-09-11): [forwardSuperClass] walks this rather than taking one hop, so
 * `Dinghy : Skiff : Vessel` with only `Skiff` outside the export set renders `Dinghy : Vessel`
 * instead of going base-less. One unexported hop used to cost the consumer every exported base
 * above it, along with the `is`/`as` relation against them, which is exactly what ADR-101's
 * base-class amendment set out to preserve wherever C# can express it.
 *
 * When the direct base is exported the chain's first element answers, so nothing about a shipping
 * class changes: the walk only ever looks past a base that has no generated C# class anyway.
 */
internal fun KSClassDeclaration.declaredBaseChain(): Sequence<KSClassDeclaration> =
  generateSequence(declaredSuperClass()) { base -> base.declaredSuperClass() }

/**
 * The bases between [this] and the base the forward pipeline keeps: the chain prefix that has no
 * generated C# class, and therefore no carrier for its own members other than [this].
 *
 * [keptBase] `null` means base-less, and then the whole chain is dropped. Compared by qualified
 * name rather than declaration identity, because the chain is re-resolved per member.
 */
internal fun KSClassDeclaration.droppedBaseChain(
  keptBase: KSClassDeclaration?,
): List<KSClassDeclaration> {
  val kept: String? = keptBase?.qualifiedName?.asString()
  return declaredBaseChain()
    .takeWhile { base -> base.qualifiedName?.asString() != kept }
    .toList()
}

/**
 * Whether [member], as returned by `getAllFunctions()`/`getAllProperties()` on a class whose
 * [forwardSuperClass] is [superClass], is bound on that class.
 *
 * Declared members (including overrides) always are. A member inherited from an *interface* and
 * not overridden (a defaulted `fun greet(): String = ...`, a defaulted `val greeting: String get()
 * = ...`) is bound too: the C# class declares that interface, so it must carry the member, and the
 * Kotlin export reaches the default body by ordinary dynamic dispatch on the instance behind the
 * handle. A member inherited from a base *class* is not: the generated C# subclass extends the
 * generated C# base class, which already carries it.
 *
 * ADR-101 amendment (2026-09-11): the interface arm applies with a kept base too. A base class no
 * longer empties the interface list (`CirClassTranslator`), so `class Ledge : Shelf(), Groomable`
 * renders `: Shelf, IGroomable` and must carry `Groomable`'s defaulted members itself, or the
 * declaration it just made is CS0535. Only an interface the base does *not* already implement
 * counts: one the base implements is carried by the base, and re-binding it here hides the base
 * member (CS0108).
 */
internal fun KSDeclaration.isForwardMemberOf(
  cls: KSClassDeclaration,
  superClass: KSClassDeclaration?,
): Boolean = isDeclaredBy(cls) ||
    superClass == null ||
    isFromInterfaceBeside(superClass) ||
    isFromDroppedBase(cls, superClass)

/**
 * [isForwardMemberOf] narrowed to the members a *plan* can be built for: an inherited interface
 * member with no implementation has nothing to dispatch to, so it stays unplanned and reaches C#
 * through `CirClassTranslator`'s abstract path instead (an abstract C# method or property, which a
 * subclass can then `override`).
 *
 * Note the abstractness test is [KSFunctionDeclaration.isAbstract] / [KSPropertyDeclaration
 * .isAbstract], **not** `Modifier.ABSTRACT`: an interface member without a body carries no
 * `abstract` modifier of its own. Reading the modifier here bound `Animal.Speak()` as a concrete
 * C# method and broke `Cat`'s `override` with CS0506.
 *
 * The gate applies only to inherited members. A class's *own* abstract member keeps whatever the
 * planner already did with it.
 */
internal fun KSDeclaration.isForwardPlannableMemberOf(
  cls: KSClassDeclaration,
  superClass: KSClassDeclaration?,
): Boolean = isDeclaredBy(cls) ||
    ((superClass == null ||
        isFromInterfaceBeside(superClass) ||
        isFromDroppedBase(cls, superClass)) && hasImplementation())

/**
 * ADR-101 amendment (2026-09-11): whether this member is inherited from an interface the class
 * lists *beside* its base class, one [superClass] does not itself implement.
 *
 * This is the member-level half of the base list `CirClassTranslator` now renders: `: Base, IFoo`
 * keeps every interface the base does not already carry, and exactly those interfaces' members
 * have no C# carrier other than this class. An interface the base implements is excluded on both
 * sides, so the two never disagree about who binds a member.
 *
 * Export status is deliberately not consulted, matching the base-less rule: an unexported
 * interface is dropped from the base list with `SKIPPED_UNEXPORTED_SUPERTYPE`, and its defaulted
 * members still bind on the class as ordinary methods.
 */
private fun KSDeclaration.isFromInterfaceBeside(superClass: KSClassDeclaration): Boolean {
  val owner: KSClassDeclaration = parentDeclaration as? KSClassDeclaration ?: return false
  if (owner.classKind != ClassKind.INTERFACE) return false
  val qualified: String = owner.qualifiedName?.asString() ?: return false
  return qualified !in superClass.forwardSupertypeNames()
}

/**
 * ADR-101 amendment (2026-09-11): whether this member is declared by one of the *dropped*
 * intermediate bases, the hops between [cls] and the base [forwardSuperClass] kept.
 *
 * Walking up to the nearest exported base is not enough on its own. With `Dinghy : Skiff : Vessel`
 * and `superClass = Vessel`, `Skiff`'s members are parented to `Skiff`, so without this arm they
 * bind on neither `Dinghy` nor `Vessel` and vanish from C# with no diagnostic at all. `Skiff` has
 * no generated class, so `Dinghy` is the only carrier there is, which is the same re-homing rule
 * the base-less case already applies to the whole chain.
 */
private fun KSDeclaration.isFromDroppedBase(
  cls: KSClassDeclaration,
  superClass: KSClassDeclaration,
): Boolean {
  val owner: KSClassDeclaration = parentDeclaration as? KSClassDeclaration ?: return false
  val qualified: String = owner.qualifiedName?.asString() ?: return false
  return cls.droppedBaseChain(superClass).any { it.qualifiedName?.asString() == qualified }
}

/**
 * The qualified names of everything [this] is, itself included: the test for "the base already
 * carries this interface".
 */
internal fun KSClassDeclaration.forwardSupertypeNames(): Set<String> =
  (sequenceOf(asStarProjectedType()) + getAllSuperTypes())
    .mapNotNull { it.declaration.qualifiedName?.asString() }
    .toSet()

/**
 * ADR-101 amendment (2026-09-11): whether this member overrides a member of its class's *base
 * class*, which is the one thing C# `override` may mean.
 *
 * The Kotlin `override` modifier is not that question. `class Ledge : Shelf(), Groomable` declares
 * `override fun groom()` for `Groomable.groom`, and `Shelf` has no `Groom` to override, so C# has
 * to spell it `virtual`: `public override string Groom()` is CS0115. Reading the modifier was safe
 * only while a kept base emptied the interface list, which it no longer does.
 *
 * [KSPropertyDeclaration.findOverridee] / [KSFunctionDeclaration.findOverridee] answer first: for
 * `Cat.vibe` over `Animal.vibe` over `Pet.vibe` the property side returns `Animal.vibe`, the
 * class-chain overridee (Verified by a probe in a Tier 1 run; the function side is the same KSP
 * API, Inferred). The answer is trusted only when it lands on a class, because a base class that
 * does not redeclare the member leaves it abstract and the overridee is then the interface
 * declaration, which says nothing about what the base class renders. The fallback walks the base
 * class's own visible members by simple name, which answers that shape too, and answers `Ledge`
 * correctly either way: `Shelf` declares no `groom` under any name.
 */
internal fun KSDeclaration.baseClassOverridee(
  superClass: KSClassDeclaration?,
): KSDeclaration? {
  if (superClass == null || Modifier.OVERRIDE !in modifiers) return null
  val name: String = simpleName.asString()
  val direct: KSDeclaration? = when (this) {
    is KSPropertyDeclaration -> findOverridee()
    is KSFunctionDeclaration -> findOverridee()
    else -> null
  }
  val onBaseClass: Boolean =
    (direct?.parentDeclaration as? KSClassDeclaration)?.classKind == ClassKind.CLASS
  if (onBaseClass) return direct
  return when (this) {
    is KSPropertyDeclaration ->
      superClass.getAllProperties().firstOrNull { it.simpleName.asString() == name }

    is KSFunctionDeclaration ->
      superClass.getAllFunctions().firstOrNull { it.simpleName.asString() == name }

    else -> null
  }
}

/** [baseClassOverridee] as the boolean the `override` / `virtual` pair is keyed on. */
internal fun KSDeclaration.overridesBaseClassMember(superClass: KSClassDeclaration?): Boolean =
  baseClassOverridee(superClass) != null

/**
 * Whether this member is *declared* by [cls], as opposed to inherited into it.
 *
 * `parentDeclaration == cls` is not that question on its own. Verified against KSP while spelling
 * a generic base (ADR-101 amendment, 2026-09-11): when the base is generic, `getAllProperties()` /
 * `getAllFunctions()` hand back the base's member **substituted onto the subclass**, parented to
 * the subclass and carrying `Modifier.OVERRIDE`, so the raw parent test called `Crate<T>.value` a
 * member of `StringCrate`. That re-bound the inherited getter on the subclass and rendered
 * `public override string Value` against a base property that is not `virtual` (CS0506). A
 * non-generic base has no such substitution and is unaffected, which is why the shipped
 * inherited-member behaviour (`Tier1InheritedMemberDiagnosticsTest`) never saw this.
 *
 * The declared list is the ground truth, so a real `override val` in the subclass still answers
 * true and keeps ADR-101's virtual/override pair. Matching is by simple name: a subclass that
 * declares one overload of a name it also inherits *substituted* from a generic base would keep
 * both, which no fixture reaches (ROADMAP has the generic-subclass work).
 */
private fun KSDeclaration.isDeclaredBy(cls: KSClassDeclaration): Boolean {
  if (parentDeclaration != cls) return false
  val name: String = simpleName.asString()
  return when (this) {
    is KSPropertyDeclaration -> cls.getDeclaredProperties().any { it.simpleName.asString() == name }
    is KSFunctionDeclaration -> cls.getDeclaredFunctions().any { it.simpleName.asString() == name }
    else -> true
  }
}

private fun KSDeclaration.hasImplementation(): Boolean = when (this) {
  is KSFunctionDeclaration -> !isAbstract
  is KSPropertyDeclaration -> !isAbstract()
  else -> true
}

/**
 * Kotlin's overridability, as C# `virtual` has to mirror it: an explicit `open`, or an `override`
 * left open (Kotlin's default for an override).
 *
 * ADR-040 only ever needed the second arm: `Animal.fetch(item)` implementing `Pet.fetch` and
 * further overridden by `Cat.fetch` carries `OVERRIDE` and not `FINAL`, and C# needs `virtual` on
 * that declaration for `Cat`'s `override` to compile (CS0506 otherwise). ADR-101's 2026-09-10
 * amendment adds the first: a base class's *own* `open val` / `open var` is overridable too, and
 * had no route to `virtual` at all.
 *
 * An `abstract` member is open as well, but C# spells that `abstract`, never `virtual` (CS0503 on
 * the pair), so it is excluded here and rendered by the abstract path instead.
 */
internal fun Set<Modifier>.isOpenForOverride(): Boolean = !contains(Modifier.ABSTRACT) &&
    (contains(Modifier.OPEN) || (contains(Modifier.OVERRIDE) && !contains(Modifier.FINAL)))
