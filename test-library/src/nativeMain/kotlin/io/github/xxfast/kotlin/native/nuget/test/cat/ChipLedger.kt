package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * Every ordinary position at which a value class crosses from C# into a non-null Kotlin slot: a
 * constructor parameter, a property setter, a method parameter, a `List` component and the
 * nullable `V?` spelling, over a String underlying ([CatId]) and a handle underlying
 * ([CatResult]).
 *
 * A C# `record struct` always has a `default`, whose underlying is null. The generated C# refuses
 * it before the call, so none of these members ever sees a `CatId` Kotlin could not have built.
 * Each member reads back what it received, so a test can tell a refused write from a lost one.
 */
class ChipLedger(val issued: CatId) {
  /** Setter over a String underlying. */
  var current: CatId = issued

  /** Setter over the nullable spelling: a null clears it, a default must not. */
  var spare: CatId? = null

  /** Setter over a handle underlying. */
  var wearer: CatResult = CatResult("Oreo")

  /** Method parameter over a String underlying. */
  fun register(id: CatId): String = "registered ${id.id}"

  /** Method parameter over a handle underlying. */
  fun registerWearer(wearer: CatResult): String = "registered ${wearer.cat.name}"

  /** Nullable method parameter: a null is "none", a default must not be. */
  fun registerSpare(id: CatId?): String = id?.id ?: "none"

  /** A value class inside a `List` parameter. */
  fun registerAll(ids: List<CatId>): Int = ids.size

  /**
   * A collection ahead of the value class: the C# side has already built [names]' native handle
   * when the default [id] is refused, so that handle has to be released on the way out.
   */
  fun registerNamed(names: List<String>, id: CatId): Int = names.size + id.id.length

  /** Kotlin-side read-back of [current]. */
  fun currentId(): String = current.id

  /** Kotlin-side read-back of [spare]: "none" when Kotlin holds null. */
  fun spareId(): String = spare?.id ?: "none"
}
