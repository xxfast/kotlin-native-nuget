package io.github.xxfast.kotlin.native.nuget.processor.forward

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
): KSClassDeclaration? = declaredSuperClass()
  ?.takeIf { base -> base.qualifiedName?.asString() in exportedTypes }

/**
 * Whether [member], as returned by `getAllFunctions()`/`getAllProperties()` on a class whose
 * [forwardSuperClass] is [superClass], is bound on that class.
 *
 * Declared members (including overrides) always are. A member inherited from an *interface* and
 * not overridden (a defaulted `fun greet(): String = ...`, a defaulted `val greeting: String get()
 * = ...`) is bound too: the C# class declares that interface, so it must carry the member, and the
 * Kotlin export reaches the default body by ordinary dynamic dispatch on the instance behind the
 * handle. A member inherited from a base *class* is not: the generated C# subclass extends the
 * generated C# base class, which already carries it (and `CirClassTranslator` renders no interface
 * list at all once a base class exists, so nothing is left unimplemented).
 */
internal fun KSDeclaration.isForwardMemberOf(
  cls: KSClassDeclaration,
  superClass: KSClassDeclaration?,
): Boolean = parentDeclaration == cls || superClass == null

/**
 * [isForwardMemberOf] narrowed to the members a *plan* can be built for: an inherited interface
 * member with no implementation has nothing to dispatch to, so it stays unplanned and reaches C#
 * through `CirClassTranslator`'s abstract-method path instead (an abstract C# method, which a
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
): Boolean = parentDeclaration == cls || (superClass == null && hasImplementation())

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
