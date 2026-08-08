package io.github.xxfast.kotlin.native.nuget.test.clinic

/**
 * ADR-083 ("Nullable collection components: the null pointer in the component slot, both
 * directions"). One class, cells as members, mirroring ADR-081's `ValueClassCollectionsSample.kt`
 * precedent -- but the new thing crossing here is a null element/value inside a collection slot,
 * not a value-class element. Every component kind (String reference, boxed Int, and [ChartId]
 * value-class) rides the same in-band null pointer, both write and read.
 *
 * Input side: [tagRoll] is `List<String?>` (a reference null, no conversion needed in the slot --
 * the crash cell per the ADR's finding 3: `elementKotlinTypeName` has no `Nullable` branch today
 * and `error(...)`s the whole KSP run). [logAges] is `MutableList<Int?>` (a boxed-primitive null).
 * [tagSet] is `Set<String?>`. [scoreBoard] is `Map<String, Int?>` (nullable VALUE slot; the ADR
 * deliberately excludes nullable map KEYS as a named skip -- a `Dictionary` cannot hold a null key
 * -- so there is no cell here for that). [fileCharts] is `List<ChartId?>`, the ADR's own motivating
 * shape: a nullable value-class element, composing ADR-081's underlying projection with this ADR's
 * null-pointer component slot. [moodTrail] is the Enum-underlying sibling of that same cell,
 * `List<Temperament?>`: the C# write side pre-casts `x == null ? (int?)null : (int)x.Value.Mood`
 * before boxing, a spelling that needs its own real-compiler proof rather than riding on
 * [fileCharts]'s String-underlying coverage.
 *
 * Every input observer joins with the literal `"null"` in place of a null element/value, so a C#
 * `null` that silently became something else (an empty string, a stray zero, a skipped element)
 * cannot pass by coincidence, and the joined length/count also proves the null did not collapse
 * the collection.
 *
 * Read side: [missingTags] returns `List<String?>` with a genuine null at a non-first index (closes
 * the `nuget_list_get` `list[index]!!` NPE -- this shape binds today and NPEs at first read).
 * [visitCounts] is a `var List<Int?>` property, getter AND setter in one cell, observed
 * Kotlin-side by [visitCountsSummary] so a C# write only reads back correctly if the null truly
 * arrived as `null` and not a stray boxed zero. [chartAssignments] returns `Map<String, ChartId?>`
 * with one present-but-null value alongside a real [ChartId], so a C# reader must be able to tell
 * "key present, value null" apart from "key missing" (the value class re-wrap only applies to the
 * non-null branch).
 *
 * Oreo's and Mylo's entries are always distinct and never padded to the easiest shape: every
 * collection below mixes a null at a non-first position with at least two non-null, non-equal
 * values.
 */
class ChartLedger {
  /**
   * ADR-083 finding 3 · `List<String?>` parameter -- reference null, no conversion in the slot.
   * Crashes the whole KSP run today (`componentLowering`/`elementKotlinTypeName` has no `Nullable`
   * branch).
   */
  fun tagRoll(tags: List<String?>): String = tags.joinToString(",") { it ?: "null" }

  /** ADR-083 · `MutableList<Int?>` parameter -- boxed-primitive null. */
  fun logAges(ages: MutableList<Int?>): String =
    ages.joinToString(",") { it?.toString() ?: "null" }

  /** ADR-083 · `Set<String?>` parameter. */
  fun tagSet(tags: Set<String?>): String = tags.map { it ?: "null" }.sorted().joinToString(",")

  /**
   * ADR-083 · `Map<String, Int?>` parameter -- nullable VALUE slot. Nullable map KEYS stay
   * excluded (named skip, `Dictionary` cannot hold a null key), so only the value side is a cell.
   */
  fun scoreBoard(scores: Map<String, Int?>): String =
    scores.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value ?: "null"}" }

  /**
   * ADR-083 · `List<ChartId?>` parameter -- the ADR's own motivating cell: nullable value-class
   * element, composing ADR-081's underlying projection with this ADR's null-pointer component
   * slot (`(it as kotlin.String?)?.let { v -> ChartId(v) }`).
   */
  fun fileCharts(charts: List<ChartId?>): String =
    charts.joinToString(",") { it?.value ?: "null" }

  /**
   * ADR-083 · method-return position, `List<String?>` with a genuine null at a non-first index.
   * Binds today and NPEs inside `nuget_list_get`'s `list[index]!!` at the first read; this ADR
   * closes that for every returned collection, not only this one.
   */
  fun missingTags(): List<String?> = listOf("OREO", null, "MYLO")

  /** ADR-083 · `var` property position, `List<Int?>` -- getter AND setter in one cell. */
  var visitCounts: List<Int?> = listOf(3, null, 5)

  /** The Kotlin-side observation for [visitCounts]'s setter. */
  fun visitCountsSummary(): String =
    visitCounts.joinToString(",") { it?.toString() ?: "null" }

  /**
   * ADR-083 · `Map<String, ChartId?>` return with one present-but-null value alongside a real
   * [ChartId], so a C# reader must distinguish "key missing" from "key present, value null" -- and
   * the non-null branch must still re-wrap through ADR-081's underlying projection.
   */
  fun chartAssignments(): Map<String, ChartId?> =
    mapOf("oreo" to ChartId("CH-OREO-1"), "mylo" to null)

  /**
   * ADR-083 · `List<Temperament?>` parameter -- enum-underlying nullable value-class element. The
   * C# write side pre-casts `x == null ? (int?)null : (int)x.Value.Mood` before boxing; until this
   * cell existed, that spelling was asserted only as generated text in Tier 1, never compiled by a
   * real C# compiler.
   */
  fun moodTrail(moods: List<Temperament?>): String =
    moods.joinToString(",") { it?.mood?.toString() ?: "null" }
}
