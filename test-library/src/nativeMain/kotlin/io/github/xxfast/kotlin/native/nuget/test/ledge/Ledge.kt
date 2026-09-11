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
