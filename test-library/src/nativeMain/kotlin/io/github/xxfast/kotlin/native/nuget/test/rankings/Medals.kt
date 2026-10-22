package io.github.xxfast.kotlin.native.nuget.test.rankings

/** ADR-198: the medals Oreo and Mylo compete for. */
enum class Medal { BRONZE, SILVER, GOLD }

/**
 * ADR-198: the generic-class route under an enum self-bound (`T : Enum<T>`), bound in C# as
 * `where T : struct, global::System.Enum`. Every member's body uses the enum API on the Kotlin
 * side (`name`, `ordinal`, `compareTo`), so a crossing that handed Kotlin anything but the real
 * entry would answer wrongly or throw.
 *
 * Oreo stands on the podium; Mylo is the runner-up he keeps out-ranking.
 */
class Rosette<T : Enum<T>>(val winner: T) {
  /** Always empty: a `T?` that is null on the Kotlin side must read back as null, not `Bronze`. */
  val vacancy: T? = null

  fun outranks(other: T): Boolean = winner > other

  fun describe(other: T): String =
    "${winner.name}#${winner.ordinal} over ${other.name}#${other.ordinal}"

  fun better(other: T): T = if (other > winner) other else winner

  fun claim(other: T?): T? = other ?: vacancy

  /**
   * ADR-217: the class's own trampolined `T` inside a `List`, sorted best first by `compareTo` on
   * the Kotlin side, with the winner placed ahead of every entry he outranks.
   */
  fun rank(items: List<T>): List<T> = (items + winner).sortedDescending()
}

/**
 * ADR-217: a constructor taking `List<T>` under the enum self-bound, and a `val` reading it back.
 * Mylo collects every ribbon Oreo turns down.
 */
class Sash<T : Enum<T>>(val ribbons: List<T>) {
  /** The best ribbon on the sash, picked by `compareTo` on the Kotlin side. */
  val best: T get() = ribbons.max()

  /** Each ribbon by `name#ordinal`, so a wrong entry would read wrongly. */
  fun describe(): String = ribbons.joinToString { "${it.name}#${it.ordinal}" }
}

/** ADR-198: the generic-function route under the same bound. Bronze is no prize. */
fun <T : Enum<T>> requirePrize(medal: T): T {
  require(medal.ordinal > 0) { "${medal.name} is no prize" }
  return medal
}

/** ADR-197 + ADR-198: a member function's own enum-bounded type parameter, on an object. */
object Judge {
  fun <T : Enum<T>> rank(medal: T): String = "${medal.name}#${medal.ordinal}"

  // ADR-217: a member's own trampolined `T` in every collection position.

  /** A `List<T>`, best first, so the order proves `compareTo` ran on the real entries. */
  fun <T : Enum<T>> sorted(medals: List<T>): List<T> = medals.sortedDescending()

  /** A `List<T?>` with one empty step added: a null must read back as null, not `Bronze`. */
  fun <T : Enum<T>> pad(medals: List<T?>): List<T?> = medals + null

  /** A `Set<T>` with every entry the set lacks, by `ordinal`. */
  fun <T : Enum<T>> unique(medals: Set<T>): Set<T> = medals.filter { it.ordinal > 0 }.toSet()

  /** A map keyed by `T`: each score doubled, keyed by the same entry. */
  fun <T : Enum<T>> scores(board: Map<T, Int>): Map<T, Int> = board.mapValues { it.value * 2 }

  /** A `List<List<T>>`: each heat sorted best first. */
  fun <T : Enum<T>> heats(heats: List<List<T>>): List<List<T>> =
    heats.map { heat -> heat.sortedDescending() }
}

/** ADR-217: a top-level function's own trampolined `T` in a `List`, by `name`. */
fun <T : Enum<T>> ordered(medals: List<T>): List<T> = medals.sortedBy { it.name }
