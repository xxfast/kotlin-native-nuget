package dev.other.core

/**
 * The interface a **sealed arm** implements from one module away, outside `:test-library`'s
 * `rootPackage`, so it has no C# declaration and cannot sit in the arm's C# base list. ADR-101's
 * named skip (`SKIPPED_UNEXPORTED_SUPERTYPE`) drops it, and its public members are re-homed onto
 * the arm itself as plain members.
 *
 * One member per mechanism the re-homing has to cross: an abstract `val` the arm implements, a
 * defaulted `val` the arm inherits (the property route), and a defaulted `fun` the arm inherits
 * (the method route, on the arm's own surface now that the arm selectors admit an inherited
 * interface member: ADR-101 amendment 2026-09-27).
 *
 * Oreo sleeps pressed against the radiator fins; Mylo only goes near it when it ticks.
 */
interface UnexportedRadiator {
  val fins: Int

  val ticks: Int get() = 7

  fun hum(): String = "radiator hums through $fins fins"
}
