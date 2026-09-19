package io.github.xxfast.kotlin.native.nuget.test.ledge

/**
 * ADR-101 amendment: a class that has **both** an exported base class and its own interface.
 * [Ledge] must render `public class Ledge : Shelf, IGroomable`: keeping a base no longer empties
 * the interface list, and `override` in C# must mean "overrides a base *class* member", not "the
 * Kotlin modifier said `override`".
 *
 * Today [Ledge] loses `: IGroomable` entirely, [brushes] is dropped with it (every inherited
 * member is dropped once a base is kept), and [groom] still renders `public override` against a
 * [Shelf] that declares no `Groom`, which is CS0115.
 *
 * The cats' window ledge: [Shelf] is the thing it sits on, [Groomable] is what Mylo does on it.
 */
open class Shelf {
  /** Declared on the base only. Reachable through a `Shelf`-typed reference to a [Ledge]. */
  fun height(): Int = 3
}

/** Implemented by [Ledge], which the base [Shelf] knows nothing about. */
interface Groomable {
  /** Overridden by [Ledge]: renders `virtual` (no base-class member to override). */
  fun groom(): String

  /**
   * Defaulted and never overridden: must still be bound on [Ledge], or `: IGroomable` is CS0535.
   */
  fun brushes(): Int = 1
}

/** An exported base and an interface the base does not implement. */
class Ledge : Shelf(), Groomable {
  override fun groom(): String = "Mylo: groomed"
}

/**
 * The same shape as [Shelf], with one difference that decides `override` vs `virtual`: the base
 * carries an *unrelated overload* of the interface member's name.
 *
 * `baseClassOverridee`'s fallback matches a base-class function by simple name only, so
 * [Post.scratch] (which overrides [Scratchable.scratch], arity 0) matches [Perch.scratch] (arity 1)
 * and renders `public override string Scratch()` against a base that only has `Scratch(int)`:
 * CS0115. The fallback needs ADR-082's wildcard signature comparison, not a name.
 *
 * This is a sibling of [Shelf]/[Ledge] rather than an overload added to [Shelf], because
 * `typeof(Ledge).GetMethod("Groom")` in `InterfaceBesideBaseTests` throws `AmbiguousMatchException`
 * the moment `Shelf` gains a second `Groom`.
 *
 * Oreo's scratching post: the [Perch] is what he stands on, [Scratchable] is what he does to it.
 */
open class Perch {
  /** Shares a simple name with [Scratchable.scratch] and nothing else. Never overridden. */
  fun scratch(strokes: Int): String = "Perch: $strokes strokes"
}

/** Implemented by [Post]. [Perch] knows nothing about it. */
interface Scratchable {
  /** Overridden by [Post]: renders `virtual`, since [Perch] has no zero-arg `scratch`. */
  fun scratch(): String
}

/** An exported base that overloads the interface member's name at a different arity. */
class Post : Perch(), Scratchable {
  override fun scratch(): String = "Oreo: scratched"
}
