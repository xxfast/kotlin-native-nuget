package io.github.xxfast.kotlin.native.nuget.test.clinic

import io.github.xxfast.kotlin.native.nuget.test.cat.CatId

/**
 * ADR-171: a value class at an erased lambda position, the `KotlinFunc`/`KotlinSuspendFunc` half
 * of the `NugetMarshal.Wrap<T>` / `FromHandle<T>` crossing. The generic-class half needs no
 * fixture of its own (`Box<T>`, `Slot<T>`, `Crate<T>` already exist; the consumer picks `T`).
 *
 * Kotlin boxes every value class in a generic position, so each payload below must arrive as a
 * real boxed value class (a raw underlying fails the lambda's own checkcast with a
 * `ClassCastException`), and each result hands back a boxed value class the C# side must unbox.
 * One payload and one result per underlying kind, because each kind lowers differently at the
 * ordinary positions the box/unbox exports reuse:
 *
 *  - String: [ChartId]
 *  - Primitive: [Dosage] (a hand-written record struct, not a `double`, so it misses the
 *    primitive branches of `Wrap<T>`)
 *  - Enum: [Temperament] (the int ordinal of [Mood])
 *  - ObjectHandle: [ChartRef] (a [Patient] handle)
 *
 * Every lambda reads its argument's underlying, so a delivered-but-unboxed value fails loudly
 * inside Kotlin rather than passing on arrival alone.
 *
 * The courier runs charts between Oreo (black, white middle) and Mylo (brown and creamy), who are
 * both on the ward for eating a hair tie.
 */
class ChartCourier(val desk: String) {

  /** String underlying, payload. */
  val onChart: (ChartId) -> String = { id -> "$desk filed ${id.value}" }

  /** Primitive underlying, payload. Doubles it so the Kotlin side provably read the value. */
  val onDose: (Dosage) -> Double = { dose -> dose.milligrams * 2 }

  /** Enum underlying, payload. */
  val onMood: (Temperament) -> String = { temperament -> "$desk saw ${temperament.mood.name}" }

  /** ObjectHandle underlying, payload. */
  val onChartRef: (ChartRef) -> String = { ref -> "$desk paged ${ref.patient.name}" }

  /** String underlying, result. */
  val nextChart: () -> ChartId = { ChartId("$desk-next") }

  /** Primitive underlying, result. */
  val nextDose: () -> Dosage = { Dosage(desk.length * 1.5) }

  /** Enum underlying, result. */
  val nextMood: () -> Temperament = { Temperament(Mood.PLAYFUL) }

  /** ObjectHandle underlying, result. A fresh [Patient] the consumer owns. */
  val nextChartRef: () -> ChartRef = { ChartRef(Patient("$desk patient")) }

  /** Payload and result on one call: both halves of the crossing in one `Invoke`. */
  val refile: (ChartId) -> ChartId = { id -> ChartId("${id.value}-refiled") }

  /** Nullable payload: `null` stays the in-band null pointer (ADR-083), a value crosses boxed. */
  val onMaybeChart: (ChartId?) -> String = { id -> id?.value ?: "$desk: no chart" }

  /** Arity 2, value classes in both slots. */
  val onChartForCat: (ChartId, CatId) -> String = { chart, cat -> "${chart.value} for ${cat.id}" }

  /**
   * Arity 2 again, with a [WardBand] second. C# never runs [WardBand]'s `init`, so the box export
   * is the first place it runs, and a nameless patient throws after the first argument's box was
   * already minted: the leak row that pins `KotlinFunc.Invoke`'s dispose loop in a `finally`.
   */
  val onChartForBand: (ChartId, WardBand) -> String =
    { chart, band -> "${chart.value} for ${band.patient.name}" }

  /** The `suspend` arm of the same `Wrap<T>` / `FromHandle<T>` pair, payload and result. */
  val refileAsync: suspend (ChartId) -> ChartId = { id -> ChartId("${id.value}-refiled-later") }
}

/**
 * ADR-171: a value class whose `init` C# never runs. An object-handle underlying renders as a
 * positional record struct (ADR-035), so `new WardBand(new Patient(""))` succeeds in C#; the box
 * export is the first place this `init` runs. [CatId] cannot show that: its String underlying
 * renders a hand-written record struct whose constructor already runs `init` through Kotlin.
 */
value class WardBand(val patient: Patient) {
  init {
    require(patient.name.isNotEmpty()) { "A ward band needs the patient's name" }
  }
}

/** The top-level-function return arm: a lambda over a value class returned, not stored. */
fun chartPicker(): (ChartId) -> String = { id -> "picked ${id.value}" }
