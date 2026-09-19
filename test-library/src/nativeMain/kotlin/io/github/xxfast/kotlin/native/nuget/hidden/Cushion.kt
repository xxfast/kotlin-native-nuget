package io.github.xxfast.kotlin.native.nuget.hidden

/**
 * The BASE-CLASS half of the "an abstract class inherits an abstract property it does not
 * implement" pair. [Nesting] is the interface half of the same mechanism; this is the same shape
 * with an abstract base class as the owner, which `emitInheritedAbstractPropertySkip` refuses
 * outright today (`owner.classKind != ClassKind.INTERFACE`), so the members reach C# nowhere and
 * nothing is said about them.
 *
 * The package is `io.github.xxfast.kotlin.native.nuget.hidden`, outside `rootPackage`
 * (`io.github.xxfast.kotlin.native.nuget.test`) and not a subpackage of it, so KSP never exports
 * it: the ADR-066 reachability closure admits returns, parameters and type arguments, never a
 * supertype. Same module and same recipe as [Nesting] and [Skiff], on purpose: a base in a
 * dependency module cannot be the unexported owner of members that re-home onto a `test-library`
 * class.
 *
 * [io.github.xxfast.kotlin.native.nuget.test.lounge.Lounger] implements none of these four, so
 * every one of them has to be spelled on `Lounger` itself in C#:
 *  - [weave]    -> `public abstract string Weave { get; }`
 *  - [loft]     -> `public abstract int Loft { get; set; }`
 *  - [stuffing] -> nothing at all, named `SKIPPED_UNSUPPORTED_PROPERTY` at `Lounger.stuffing`
 *  - [squish]   -> `public abstract string Squish();` already, the control: the abstract METHOD
 *    walk carries no owner-kind guard, so the method half works while the property half drops.
 *
 * [stuffing] reuses [Nesting.Lining] rather than minting a second unsupported type. A nested class
 * is refused by the ADR-066 closure, so the property stays unbridgeable however the fixture grows;
 * a top-level class in this package would be dragged into the export set through `Beanbag.stuffing`
 * (a property type IS a closure edge) and would quietly stop exercising the skip.
 *
 * Mylo, the brown and creamy one, claims this the moment it is out of the dryer.
 */
abstract class Cushion {
  /** Bridgeable read-only slot: must reach C# as an abstract, get-only property on `Lounger`. */
  abstract val weave: String

  /** Bridgeable mutable slot: must reach C# as an abstract property with BOTH accessors. */
  abstract var loft: Int

  /** Unbridgeable slot: nested-class type, so C# must see no member and one named skip. */
  abstract val stuffing: Nesting.Lining

  /** Control: the method half already renders `public abstract string Squish();` on `Lounger`. */
  abstract fun squish(): String
}
