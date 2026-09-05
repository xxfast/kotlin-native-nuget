package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

/**
 * The declared base class, ungated: what the class *says* it extends, whether or not the export
 * set carries it. Read only by `CirClassTranslator.translateClass`, to decide whether a
 * `SKIPPED_UNEXPORTED_SUPERTYPE` diagnostic is owed for a base [forwardSuperClass] is about to
 * drop. No other site may read it: asking what a class declares, rather than what the forward
 * pipeline can actually generate, is exactly the CS0246 in issue #42.
 */
internal fun KSClassDeclaration.declaredSuperClass(): KSClassDeclaration? = superTypes
  .map { type -> type.resolve().declaration }
  .filterIsInstance<KSClassDeclaration>()
  .firstOrNull { declaration ->
    declaration.classKind == ClassKind.CLASS &&
        declaration.qualifiedName?.asString() != "kotlin.Any"
  }

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
