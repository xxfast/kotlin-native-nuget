package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

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
 */
internal fun KSClassDeclaration.forwardSuperClass(): KSClassDeclaration? = superTypes
  .map { type -> type.resolve().declaration }
  .filterIsInstance<KSClassDeclaration>()
  .firstOrNull { declaration ->
    declaration.classKind == ClassKind.CLASS &&
        declaration.qualifiedName?.asString() != "kotlin.Any"
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
