package io.github.xxfast.kotlin.native.nuget.test.playroom

import io.github.xxfast.kotlin.native.nuget.test.membergeneric.Furry

/**
 * ADR-218: every `var` whose type mentions a class's own type parameter gets a C# setter. The bare
 * `T` and `T?` setters box the value on the C# side (`Wrap<T>`) and dispose only the box they
 * minted; the collection setters (ADR-212) already compiled and are pinned here by execution.
 *
 * Every getter reads back what Kotlin stored, and [describe] reads it on the Kotlin side, so a
 * setter that handed Kotlin anything but the real value answers wrongly.
 *
 * Oreo claims the playpen first; Mylo moves in whenever Oreo naps.
 */
class Playpen<T>(first: T) {
  var current: T = first
  var maybe: T? = null
  var items: List<T> = listOf(first)
  var bag: MutableList<T> = mutableListOf(first)
  var unique: Set<T> = setOf(first)
  var tags: Map<String, T> = mapOf("first" to first)

  /** Kotlin's own view of what the setters stored. */
  fun describe(): String =
    "current=$current maybe=$maybe items=$items bag=$bag unique=$unique tags=$tags"
}

/** ADR-218: a bounded `T`, so the Kotlin setter casts the unboxed value to [Furry]. */
class Cradle<T : Furry>(first: T) {
  var current: T = first
  var pets: List<T> = listOf(first)

  /** Reads through the bound on the Kotlin side. */
  fun names(): String = (listOf(current) + pets).joinToString { it.name }
}

/** ADR-218: a `Map<K, V>` setter over two type parameters, one of them `Any`-bounded. */
class Keyring<K : Any, V>(key: K, value: V) {
  var index: Map<K, V> = mapOf(key to value)

  fun lookup(key: K): V? = index[key]
}

/**
 * ADR-218 with ADR-198: an enum self-bound `T` is trampolined, so its setter crosses through the
 * trampoline wrapper rather than a plain cast. [describe] reads the enum API on the Kotlin side.
 */
class Sash<T : Enum<T>>(first: T) {
  var current: T = first
  var runnerUp: T? = null

  fun describe(): String = "${current.name}#${current.ordinal} then ${runnerUp?.name ?: "nobody"}"
}

/** ADR-218: an interface-owned `T` setter, `IKeepsake<T>` with `T Item { get; set; }`. */
interface Keepsake<T> {
  var item: T
}

/** The Kotlin-implemented side of [Keepsake]. */
class Locket<T>(first: T) : Keepsake<T> {
  override var item: T = first

  fun peek(): String = "locket holds $item"
}
