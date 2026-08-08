package io.github.xxfast.kotlin.native.nuget.test.clinic

/**
 * ADR-081 ("Value-class collection components: cross as the underlying, re-wrap per element").
 * Value classes as `List`/`Set`/`Map` elements, keys and values, spread across every ADR-077
 * underlying (String: [ChartId], Primitive: [Dosage], Enum: [Temperament], ObjectHandle:
 * [ChartRef]) rather than the single easiest one.
 *
 * Input side (constructor + methods): [file] is the crash cell -- a `List<ChartId>` parameter
 * stops the whole KSP run today (`elementKotlinTypeName` has no `ValueClass` branch, per the
 * ADR's finding 1). [sectioned] deliberately keeps the spike's own member name
 * (`ChartBook.sectioned`, the ADR's finding 2) for the `Map<String, ChartId>` VALUE-position skip.
 * [tallyByChart] is the KEY-position sibling. [sumDosages] and [namesOf] spread the same input
 * shape across the Primitive and ObjectHandle underlyings. [recordMoods]'s `Set<Temperament>`
 * skips `SKIPPED_UNSUPPORTED_INPUT` today, but the ADR's finalized Decision (point 2) admits
 * enum-underlying value classes at input positions too -- `isWrappableComponent`'s new
 * `ValueClass` branch admits `is BridgeType.Enum` alongside String/primitive/ObjectHandle, and the
 * C# call site pre-casts `(int)x.Mood` before boxing to ride the existing `Wrap<int>` branch --
 * so this cell binds once the fix lands, same as every other input cell here. Only a *bare* enum
 * component (no value-class wrapper) stays with the sibling ROADMAP item.
 *
 * Read side: [issuedCharts] is the method-return cell (binds-and-breaks today, per the ADR's
 * finding 3); [pendingCharts] is the `var` property cell (getter AND setter), observed
 * Kotlin-side by [pendingChartsSummary] so a C# write only reads back correctly if it arrived as
 * genuinely re-wrapped [ChartId]s, not a stray handle; [moodsOnFile] is the second read kind, an
 * enum-underlying `Set` return.
 *
 * Oreo's chart book always has more entries than Mylo's, and never the same ones -- every fixture
 * below uses multiple, distinct-valued elements, so an echo/identity bug (first-element-only,
 * key/value swapped, wrong ordinal) cannot pass by coincidence.
 */
class ChartBook(charts: List<ChartId>) {
  /** ADR-081 · constructor-parameter position, `List<ChartId>` (spread cheaply: one ctor cell). */
  val manifest: String = charts.joinToString(",") { it.value }

  /** ADR-081 finding 1 · `List<ChartId>` method parameter. Crashes the KSP run today. */
  fun file(charts: List<ChartId>): String = charts.joinToString(",") { it.value }

  /** ADR-081 finding 2 · `Map<String, ChartId>` parameter, VALUE position (spike's own name). */
  fun sectioned(sections: Map<String, ChartId>): String =
    sections.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value.value}" }

  /** ADR-081 · `Map<ChartId, Int>` parameter, KEY position. */
  fun tallyByChart(counts: Map<ChartId, Int>): Int = counts.values.sum()

  /** ADR-081 · `MutableList<Dosage>` parameter, Primitive underlying. */
  fun sumDosages(doses: MutableList<Dosage>): Double = doses.sumOf { it.milligrams }

  /** ADR-081 · `List<ChartRef>` parameter, ObjectHandle underlying. */
  fun namesOf(refs: List<ChartRef>): String = refs.joinToString(",") { it.patient.name }

  /**
   * ADR-081 · `Set<Temperament>` parameter, Enum underlying. Skips `SKIPPED_UNSUPPORTED_INPUT`
   * today; binds once `isWrappableComponent` admits the value-class-over-enum branch, per the
   * ADR's finalized Decision point 2.
   */
  fun recordMoods(moods: Set<Temperament>): String =
    moods.map { it.mood }.sortedBy { it.ordinal }.joinToString(",")

  /** ADR-081 · method-return position, `List<ChartId>`. Binds-and-breaks today (finding 3). */
  fun issuedCharts(): List<ChartId> = listOf(ChartId("CH-OREO-9"), ChartId("CH-MYLO-4"))

  /** ADR-081 · `var` property position, `List<ChartId>` -- getter and setter in one cell. */
  var pendingCharts: List<ChartId> = listOf(ChartId("CH-PEND-1"), ChartId("CH-PEND-2"))

  /** The Kotlin-side observation for [pendingCharts]'s setter. */
  fun pendingChartsSummary(): String = pendingCharts.joinToString(",") { it.value }

  /** ADR-081 · second read kind: `Set<Temperament>` return, Enum underlying. */
  fun moodsOnFile(): Set<Temperament> = setOf(Temperament(Mood.ANXIOUS), Temperament(Mood.PLAYFUL))
}
