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
