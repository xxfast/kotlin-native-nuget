package io.github.xxfast.kotlin.native.nuget.test.models

/**
 * Fixture for the **undeclared-enum gate**, shape (b): a *cross-module* `enum class` nested inside
 * a dependency class that the ADR-066 reachability closure **does** admit.
 *
 * Unlike [Broadcast] itself, [Broadcast.AdBand] is nested. Before the gate the closure's ENUM
 * admission had no `parentDeclaration` filter, so `AdBand` was admitted and declared at the
 * namespace root from its simple name while every reference spelled it `Broadcast.AdBand`
 * (CS0426). The closure now refuses nested enums, so `AdBand` is neither declared nor referenced
 * and `band` skips named through the same `UNDECLARED_ENUM` route as shape (a).
 *
 * This class is a plain `class`, not a `data class`, on purpose: `copy(band: AdBand, ...)` would
 * add an uncontrolled fourth enum position and muddy which seam a failure came from.
 *
 * [station] is the control that must keep binding, mirroring `NestedModeOwner.name` on the
 * module-local side, so "the owning dependency class still generates and constructs" is
 * observable from C#.
 *
 * Mylo (brown and creamy) insists the cat radio station only sounds right on FM.
 */
class Broadcast {

  /** Cross-module, nested: refused by the closure, so never declared; `band` skips named. */
  enum class AdBand { AM, FM }

  /** Property position on an admitted dependency type. */
  val band: AdBand = AdBand.FM

  /** Control: must survive the gate. */
  val station: String = "Radio Mylo 101.1"
}
