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
 * The generated shape is an `abstract class` with nested `sealed` subclasses and one flat
 * `FromHandle` switch, so the hierarchy has to be expressible as exactly that: no type parameters
 * (the sealed route renders none), every subclass nested directly in the interface (that is how
 * `nestedCsName` spells a reference to it), no second superclass (a C# nested subclass can only
 * extend the abstract base) and no sub-interface (the discriminator is a flat `when` over
 * `getSealedSubclasses()`, and a sub-interface has no single C# class to construct).
 */
internal fun KSClassDeclaration.sealedInterfaceIneligibility(): String? {
  if (!isSealedInterface()) return null
  if (typeParameters.isNotEmpty()) return "it has type parameters"
  getSealedSubclasses().forEach { subclass ->
    val subName: String = subclass.simpleName.asString()
    if (subclass.classKind == ClassKind.INTERFACE) return "subclass `$subName` is an interface"
    if (subclass.parentDeclaration?.qualifiedName?.asString() != qualifiedName?.asString()) {
      return "subclass `$subName` is declared outside the sealed interface"
    }
    val base: KSClassDeclaration? = subclass.declaredSuperClass()
    if (base != null) {
      val baseName: String = base.qualifiedName?.asString() ?: base.simpleName.asString()
      return "subclass `$subName` extends another class `$baseName`"
    }
  }
  return null
}

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
