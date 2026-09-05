package io.github.xxfast.kotlin.native.nuget.test

import io.github.xxfast.kotlin.native.nuget.test.clinic.Mood
import io.github.xxfast.kotlin.native.nuget.test.clinic.Patient

/**
 * Cross-namespace value-class underlying fixture — the value-class mirror of the #41 shape.
 *
 * Every existing `value class` in `test-library` whose underlying is itself an exported type wraps
 * something declared in its *own* package ([io.github.xxfast.kotlin.native.nuget.test.clinic
 * .Temperament] wraps `clinic.Mood`, `clinic.ChartRef` wraps `clinic.Patient`, `cat.CatResult`
 * wraps `cat.Cat`), so the generated `readonly record struct` gets away with spelling the member
 * type bare — `public Mood Mood { get; }`, `record struct ChartRef(Patient Patient)` — because
 * the underlying happens to resolve from inside the struct's own namespace.
 *
 * These two declarations live in the ROOT package, which maps to the bare `TestLibrary`
 * namespace, while both underlyings live in `...test.clinic` → `TestLibrary.Clinic`. A bare
 * `Mood` / `Patient` does not resolve from inside `namespace TestLibrary`, so the generated
 * `Interop.cs` is expected to fail CS0246 until the underlying's C# spelling is qualified as
 * `global::TestLibrary.Clinic.Mood` / `global::TestLibrary.Clinic.Patient` — the same fix #41
 * applied at the other render sites.
 *
 * Both underlying kinds are here on purpose: the enum branch and the ObjectHandle (class) branch
 * are two distinct render sites (`renderValueClass` vs `renderReferenceValueClass`), and a
 * fixture that crossed only one of them would leave the other unproven.
 *
 * Deliberately narrow: no nullability, no collections, no interfaces, no extension members. The
 * only thing under test is how the underlying type is spelled.
 *
 * Oreo runs the desk and flips from calm to playful the moment anyone looks away; Mylo is filed
 * under his own referral and would like it noted that he was calm the whole time.
 */
value class Disposition(val mood: Mood)

/**
 * ObjectHandle-underlying half of the same seam: a root-namespace value class over
 * `TestLibrary.Clinic.Patient`, rendered by `renderReferenceValueClass` as a positional
 * `readonly record struct PatientRef(Patient Patient)`.
 */
value class PatientRef(val patient: Patient)

/**
 * Carrier exercising the two cross-namespace value classes at property, parameter and return
 * positions.
 */
class DispositionDesk(val name: String) {
  /** Property position, both accessors: the getter returns the struct, the setter takes it. */
  var current: Disposition = Disposition(Mood.CALM)

  /**
   * Parameter *and* return in one call, with two distinct non-default ordinals crossing each
   * way, so a same-value echo cannot pass by coincidence.
   */
  fun flip(disposition: Disposition): Disposition =
    if (disposition.mood == Mood.CALM) Disposition(Mood.PLAYFUL) else Disposition(Mood.CALM)

  /**
   * Parameter position for the ObjectHandle-underlying half, returning an already-supported
   * String.
   */
  fun describe(referral: PatientRef): String = "$name is minding ${referral.patient.name}"
}
