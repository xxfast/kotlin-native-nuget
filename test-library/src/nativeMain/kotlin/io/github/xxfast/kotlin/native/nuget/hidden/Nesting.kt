package io.github.xxfast.kotlin.native.nuget.hidden

/**
 * ADR-075 amendment (2026-09-13): the UNEXPORTED half of the "abstract class inherits an abstract
 * property it does not implement" pair.
 *
 * The package is `io.github.xxfast.kotlin.native.nuget.hidden`, outside `rootPackage`
 * (`io.github.xxfast.kotlin.native.nuget.test`) and not a subpackage of it, so KSP never exports
 * it: the ADR-066 reachability closure admits returns, parameters and type arguments, never a
 * supertype. Same recipe as [Skiff], and deliberately NOT the recipe
 * `io.github.xxfast.kotlin.native.nuget.test.aviary.Feathered` uses -- that one is the *exported*
 * interface pin for the same mechanism and must stay as it is.
 *
 * [io.github.xxfast.kotlin.native.nuget.test.aviary.Nester] implements none of these three, so
 * every one of them has to be spelled on `Nester` itself in C#:
 *  - [material] -> `public abstract string Material { get; }`
 *  - [height]   -> `public abstract int Height { get; set; }`
 *  - [lining]   -> nothing at all, named `SKIPPED_UNSUPPORTED_PROPERTY`, never a crash.
 *
 * [lining] is typed with a NESTED class on purpose. A top-level class in this package could be
 * dragged into the export set by the ADR-066 closure through `Wren.lining` (a property type IS a
 * closure edge), which would quietly make the member bridgeable and stop exercising the skip.
 * A nested class is refused by the closure, so it stays unbridgeable however the fixture grows.
 *
 * Oreo, the black one with the white middle, sleeps in the down-lined one.
 */
interface Nesting {
  /** Bridgeable read-only slot: must reach C# as an abstract, get-only property on `Nester`. */
  val material: String

  /** Bridgeable mutable slot: must reach C# as an abstract property with BOTH accessors. */
  var height: Int

  /** Unbridgeable slot: nested-class type, so C# must see no member and one named skip. */
  val lining: Lining

  /** Nested, so the reachability closure cannot admit it and the property stays unsupported. */
  class Lining(val fibre: String = "down")
}
