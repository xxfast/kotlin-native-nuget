package io.github.xxfast.kotlin.native.nuget.test.issue115

/**
 * The interface half of the sealed-base default cell: a top-level interface whose only member
 * carries a trailing default.
 *
 * `Double` in and out on purpose. An interface-typed return would drag an ADR-040 interface-return
 * dependency into a cell that is only about where the default bit is read, and a primitive result
 * keeps the arm's value the thing that proves dispatch.
 */
interface Squishy {
  /** [factor] defaults here, on the root of the override chain, and only here. */
  fun squish(factor: Double = 1.0): Double
}

/**
 * Green pin for the ROADMAP's "sealed base member whose trailing default lives on the interface it
 * overrides" cell: `sealedBaseEntries` synthesizes its ADR-096 omitting overloads from
 * `method.parameters.map { it.hasDefault }`, the raw KSP bit, and on KSP 2.3.10 that bit already
 * reads `true` on an `override` whose overridee states the default (verified by execution,
 * 2026-09-19).
 *
 * So [Pose.squish], an `override` of [Squishy.squish], does get its `Squish()` on the C# base, and
 * every arm inherits it. [Pose.Croissant]'s own override synthesizes none of its own:
 * `sealedSubclassEntries` returns early on any override whose overridee is a planned base member
 * (ADR-116's "the base is the carrier"), so the short call lives on the base exactly once.
 * `classEntries` (ADR-096, 2026-09-11) and `sealedSubclassEntries` (ADR-116, 2026-09-13) read the
 * same defaults through `memberDefaultFlags`, a defensive walk of the override chain.
 *
 * ### Cells
 *
 * - [Pose.squish]: the subject. Overrides an interface member with a trailing default, and KSP
 *   carries the bit onto the override's own parameter. The C# base gets `Squish()` beside
 *   `Squish(double)`, and both arms inherit it.
 * - [Pose.settle]: the control, where the default needs no override chain at all. The base owns it
 *   itself. It pins that a base-synthesized omitting overload renders end to end: no other shipped
 *   fixture has one, because [Job.tag]'s base member is ADR-115-declined. If `Settle()` is red,
 *   the base pass never rendered its own synthesized arity, independent of where the default
 *   lives.
 * - [Pose.Croissant]: the arm that overrides [Pose.squish]. It must declare no `Squish()` of its
 *   own; the short call arrives by C# inheritance from the base.
 * - [Pose.Splat]: the arm that inherits [Pose.squish] unchanged, so the base's body answers and
 *   the same short call has to work on a `data object` arm too.
 *
 * Oreo folds himself into a croissant on the arm of the sofa and squishes proportionally to how
 * tightly he is curled. Mylo just splats, and squishes by exactly as much as you ask.
 */
sealed class Pose : Squishy {
  /**
   * The default for `factor` lives on [Squishy.squish] and cannot be restated here. The base is the
   * C# carrier, not necessarily the Kotlin root of the chain.
   */
  override fun squish(factor: Double): Double = factor

  /** Control: a trailing default the base owns itself, with no override chain in the way. */
  open fun settle(steps: Int = 3): Int = steps * 2

  /** Oreo, curled tight. The arm that overrides [squish] with a body of its own. */
  data class Croissant(val curl: Int) : Pose() {
    override fun squish(factor: Double): Double = factor * curl
  }

  /** Mylo, flat on the floorboards. The arm that overrides nothing and inherits both overloads. */
  data object Splat : Pose()
}

/** The `data object` arm behind a sealed-base return, the way `anyJob` reaches [Job.Running]. */
fun anyPose(): Pose = Pose.Splat

/** The `data class` arm behind a sealed-base return, so C# reaches it without a constructor. */
fun curledPose(curl: Int): Pose = Pose.Croissant(curl)
