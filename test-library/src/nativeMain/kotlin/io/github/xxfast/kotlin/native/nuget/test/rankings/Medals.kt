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
}

/** ADR-198: the generic-function route under the same bound. Bronze is no prize. */
fun <T : Enum<T>> requirePrize(medal: T): T {
  require(medal.ordinal > 0) { "${medal.name} is no prize" }
  return medal
}

/** ADR-197 + ADR-198: a member function's own enum-bounded type parameter, on an object. */
object Judge {
  fun <T : Enum<T>> rank(medal: T): String = "${medal.name}#${medal.ordinal}"
}
