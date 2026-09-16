package io.github.xxfast.kotlin.native.nuget.test.parcel

/**
 * Item 15 / ADR-101: an exported *generic* base class must render its type arguments in the
 * derived class's base list, `public class NamedParcel : Parcel<string>`, and must expose a
 * `virtual Dispose()` so the derived `override` compiles.
 *
 * Today the translator spells the base by simple name only, so the base list comes out
 * `: Parcel` and the consumer fails CS0305.
 *
 * The parcel is Oreo's, obviously. Mylo only ever gets the box it came in.
 */
open class Parcel<T>(val value: T)

/**
 * Closes [Parcel] over `String`: inherits `Value`, adds one member of its own.
 *
 * [own] reads the inherited [Parcel.value] rather than the constructor parameter, which a member
 * body cannot see, and which also makes the derived member depend on the generic base's state.
 */
class NamedParcel(name: String) : Parcel<String>(name) {
  fun own(): String = "own:$value"
}

/**
 * ADR-101 amendment (2026-09-13) sibling of [Parcel]: the base function's parameter mentions `T`,
 * so KSP hands the subclass a *substituted* `describe(tag: String)` parented to the subclass.
 *
 * The crate is Oreo's cat-treat crate. He describes its contents at length, to nobody.
 */
open class Crate<T>(val item: T) {
  fun describe(tag: T): String = "$tag:$item"

  /**
   * ADR-147: `T` at a parameter *and* at the return, so the boxed-handle wire has to cross in both
   * directions on one call. A primitive instantiation pays a box mint per argument; an exported
   * class instantiation borrows the wrapper's own handle and mints nothing on the way in.
   *
   * Oreo always picks the treat you offer him over the one already in the crate.
   */
  fun pick(other: T): T = other

  /**
   * ADR-147: no `T` anywhere. The generic class is on the ADR-062 callable plan now, so an
   * ordinary position binds on it exactly as it does on a non-generic class.
   *
   * Mylo cannot read the label either, but he does like to sit on it.
   */
  fun label(prefix: String, count: Int): String = "$prefix-$count:$item"
}

/**
 * Declares an overload of [Crate.describe] with a *different* signature. Only this one is the
 * subclass's own declaration: the substituted `describe(String)` must stay off `LabelledCrate`.
 *
 * Mylo labels his crate by number, because he cannot read.
 */
class LabelledCrate(item: String) : Crate<String>(item) {
  fun describe(tag: Int): String = "#$tag:$item"
}
