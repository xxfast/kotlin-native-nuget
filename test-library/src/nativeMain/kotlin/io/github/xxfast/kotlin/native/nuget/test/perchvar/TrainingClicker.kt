package io.github.xxfast.kotlin.native.nuget.test.perchvar

/**
 * A `var` on an exported Kotlin interface must reach C# as `{ get; set; }` on the generated
 * `ITally`, and one `override var` that satisfies both a base-class `open val` and that interface
 * `var` (case D) must still let C# write through `ITally`.
 *
 * Case D is [TrainingClicker]: the class route refuses its public setter (a C# `override` cannot
 * add a setter to a get-only base property, CS0546), so `TrainingClicker.Count` stays `{ get; }`
 * and the only way to honour `ITally.Count { get; set; }` is an explicit interface implementation
 * beside it. Without that, widening `ITally` is CS0535 on `TrainingClicker` and the whole
 * generated file stops compiling.
 *
 * Named `TrainingClicker`, not `Clicker`, because `TestLibrary.Cat.Clicker` already exists and the
 * leak tests import both namespaces.
 *
 * The members cross one setter shape each, so the explicit setter cannot be shaped around a
 * value type: [Tally.count] a value, [Tally.label] a string, [Tally.toy] a nullable exported
 * handle, [Tally.names] a collection. [Tally.lastSlip] is the refused-setter cell (ADR-107: C#
 * cannot mint a Kotlin `Throwable`), so it stays `{ get; }` on `ITally` and case D must not
 * invent an explicit member for it.
 *
 * Mylo is doing clicker training. Oreo keeps knocking the clicker off the desk.
 */
interface Tally {
  var count: Int
  var label: String
  var toy: Pompom?
  var names: List<String>
  var lastSlip: Throwable?
}

/** The toy crossed at [Tally.toy]: an exported class, so its setter hands over a handle. */
class Pompom(val colour: String)

/**
 * The read-only base of case D. Every [Tally] member is an `open val` here, so the overrides on
 * [TrainingClicker] are get-only on the C# class. [describe] reads through Kotlin's own dispatch,
 * so a C# write through `ITally` is observed by Kotlin rather than echoed by the C# getter.
 */
open class Scoreboard {
  open val count: Int = 0
  open val label: String = "scoreboard"
  open val toy: Pompom? = null
  open val names: List<String> = emptyList()
  open val lastSlip: Throwable? = null

  fun describe(): String =
    "$label: $count clicks for ${names.joinToString()} with ${toy?.colour ?: "no"} pompom"
}

/**
 * Case D: one `override var` per member satisfies both [Scoreboard]'s `val` and [Tally]'s `var`.
 */
class TrainingClicker : Scoreboard(), Tally {
  override var count: Int = 0
  override var label: String = "clicker"
  override var toy: Pompom? = null
  override var names: List<String> = listOf("Mylo")
  override var lastSlip: Throwable? = null

  /** Makes [lastSlip] non-null from Kotlin, so its get-only binding is provably live. */
  fun slip() {
    lastSlip = IllegalStateException("Oreo knocked the clicker off the desk")
  }

  /**
   * The ADR-040 route: this same object behind the generated `Tally` wrapper, seen only as
   * `ITally`.
   */
  fun asTally(): Tally = this
}

/**
 * The ordinary implementer: no read-only base, so every setter is public on the C# class and
 * `ITally` is satisfied implicitly. Pins that the explicit form is reserved for case D.
 */
class Abacus : Tally {
  override var count: Int = 0
  override var label: String = "abacus"
  override var toy: Pompom? = null
  override var names: List<String> = listOf("Oreo")
  override var lastSlip: Throwable? = null

  fun describe(): String =
    "$label: $count clicks for ${names.joinToString()} with ${toy?.colour ?: "no"} pompom"
}
