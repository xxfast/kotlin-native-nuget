package io.github.xxfast.kotlin.native.nuget.test.bed

/**
 * ADR-101 / ADR-075: an ordinary exported base class whose *own* members are declared `open`.
 * Nothing here is an `override` of anything, which is the whole point: the existing fixtures
 * (`Animal.vibe`, `Issue42Derived`) only ever reach `virtual` through the `override && !final`
 * arm, so a declared `open` member has never been projected.
 *
 * Every open member must render `public virtual ...` in C#, or [Hammock]'s `override` below
 * does not compile (CS0506). Oreo tests the springs, Mylo just moves in.
 */
open class Bed {
  /** `open val`: must render `public virtual int Softness { get; }`. */
  open val softness: Int = 1

  /** `open var`: both accessors virtual, and the override keeps its setter (ADR-075). */
  open var occupant: String = "Oreo"

  /** Control: final by Kotlin default, must stay non-virtual in C#. */
  val brand: String = "Catnap"

  /**
   * Deliberately **not** `open`: a declared `open fun` never rendering `virtual` is a separate,
   * split-out bug, and overriding one here would fail the C# build for the wrong reason.
   *
   * It reads both open properties so Kotlin's own dynamic dispatch through [Hammock]'s
   * overrides is observable from the base's method, through either static type in C#.
   */
  fun describe(): String = "$brand, softness $softness, occupied by $occupant"
}

/** Overrides every open member of [Bed]. Compiles in C# only once the base says `virtual`. */
class Hammock : Bed() {
  override val softness: Int = 9
  override var occupant: String = "Mylo"
}
