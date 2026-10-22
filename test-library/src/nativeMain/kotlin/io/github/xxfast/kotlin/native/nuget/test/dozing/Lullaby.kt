package io.github.xxfast.kotlin.native.nuget.test.dozing

/**
 * ADR-217 (from ADR-213's sync plan route): a non-generic interface that is reachable only as the
 * bound of a top-level `T`. No parameter, property or return names [Snoozer] directly, so this is
 * the cell that proves a bound alone generates the C# bridge a C#-implemented snoozer crosses on.
 *
 * Oreo snores on the windowsill; Mylo snores louder on the bed.
 */
interface Snoozer {
  val snore: String
}

/** Tucks a snoozer in and reads its snore on the Kotlin side. */
fun <T : Snoozer> tuckIn(snoozer: T): String = "tucked in: ${snoozer.snore}"
