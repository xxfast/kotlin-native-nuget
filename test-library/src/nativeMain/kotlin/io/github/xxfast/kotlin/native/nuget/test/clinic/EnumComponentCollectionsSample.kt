package io.github.xxfast.kotlin.native.nuget.test.clinic

/**
 * ADR-097 ("Forward, enum collection components: the int ordinal in the component slot, and
 * `List`'s input gate narrowed to what the write side can box"). One class, cells as members,
 * mirroring ADR-081's `ValueClassCollectionsSample.kt` and ADR-083's
 * `NullableComponentCollectionsSample.kt` precedents -- but the new thing crossing here is a
 * *bare* [Mood] (no value-class wrapper) in a collection component slot, which is bind-then-throw
 * in **both** directions today: `Wrap<T>` has no enum branch on the write side, and
 * `FromHandle<Mood>` falls through to `Materialize` with no `Factories` entry on the read side.
 *
 * ADR-081 already ships the wire this rides -- `Set<Temperament>` (a value class over [Mood])
 * crosses as `(int)x.Mood` and re-wraps as `Temperament(Mood.entries[it as Int])`. Only the bare
 * spelling is missing, so every cell below is the same wire minus the wrapper.
 *
 * Input side: [logMoods] is the restatement's exact `List<Mood>` shape. [tallyMoods] is the `Set`
 * sibling -- a different native export and a different Kotlin lowering (`mapTo(mutableSetOf())`),
 * so it cannot ride [logMoods]'s coverage. [chartMoods] carries an enum VALUE next to a `String`
 * KEY, the one collection where a slot needing conversion sits beside a slot needing none.
 * [moodRoster] is the KEY-position sibling, proving the key slot projects independently of the
 * value slot. [logMoodTrail] is `List<Mood?>`, which `isWrappableComponent`'s `Nullable` branch
 * admits automatically the moment `Enum` is allowed -- without its own write/read arms it would
 * emit `it as Mood?` against a boxed `Int` and `ClassCastException` at the first call, so it is
 * not an optional cell.
 *
 * Read side: [moodsOnFile] is the method-return cell (`List<Mood>`) and [moodChart] is the `Map`
 * cell whose enum sits in the VALUE slot, distinguishing the value slot's read projection from the
 * element slot's. Both bind today and throw `NotSupportedException` at the first read.
 *
 * [logSpans] is the gate-narrowing cell: its C# member must be **absent** once `List`'s input gate
 * narrows to `isWrappableComponent()`.
 *
 * `List<List<String>>` is deliberately NOT a cell here -- it aborts the whole KSP run today
 * (`elementKotlinTypeName` has no `Collection` branch), so it cannot live in the corpus until the
 * narrowing lands. Its coverage belongs in a Tier 1 planner test.
 *
 * Oreo's and Mylo's moods are always distinct and never the first enum entry alone: every cell
 * below uses a non-zero ordinal somewhere, so a projection that always sends `0` (CALM) cannot
 * pass by coincidence.
 */
class MoodLedger {
  /**
   * ADR-097 · `List<Mood>` parameter -- the restatement's exact shape, and the cell for the C#
   * call-site projection `Select(x => (int)x)` plus the Kotlin `Mood.entries[it as Int]` re-wrap.
   * Binds today and throws `NotSupportedException` out of `Wrap<T>` at the first call.
   */
  fun logMoods(moods: List<Mood>): String = moods.joinToString(",")

  /**
   * ADR-097 · `Set<Mood>` parameter -- a different native export and a different Kotlin lowering
   * (`mapTo(mutableSetOf())`) than [logMoods], so it cannot ride that cell's coverage.
   */
  fun tallyMoods(moods: Set<Mood>): String = moods.map { it.name }.sorted().joinToString(",")

  /**
   * ADR-097 · `Map<String, Mood>` parameter -- an enum VALUE beside a `String` KEY. The only
   * collection carrying both a component that needs conversion at the seam (Mood -> int) and one
   * that needs none, so the mixed `new KeyValuePair<string, int>(x.Key, (int)x.Value)` projection
   * is compiled by a real compiler rather than only asserted as generated text. Runs the narrower
   * `isWrappableComponent()` gate today, so this member is not generated at all.
   */
  fun chartMoods(byPatient: Map<String, Mood>): String =
    byPatient.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

  /**
   * ADR-097 · `Map<Mood, String>` parameter -- enum KEY, `String` value. The key slot projects
   * independently from the value slot, so [chartMoods]'s coverage does not reach it. Also not
   * generated today.
   */
  fun moodRoster(byMood: Map<Mood, String>): String =
    byMood.entries.sortedBy { it.key.ordinal }.joinToString(";") { "${it.key}=${it.value}" }

  /**
   * ADR-097 · `List<Mood?>` parameter -- admitted automatically by `isWrappableComponent`'s
   * `Nullable` branch the moment `Enum` is allowed, so it needs its own
   * `(x == null ? (int?)null : (int)x)` write arm and `(it as Int?)?.let { v -> Mood.entries[v] }`
   * read arm. Without this cell the ADR ships an admitted shape whose Kotlin lowering
   * `ClassCastException`s at the first call.
   */
  fun logMoodTrail(moods: List<Mood?>): String = moods.joinToString(",") { it?.name ?: "null" }

  /**
   * ADR-097 · read side, `List<Mood>` return. Binds today and throws `NotSupportedException` out
   * of `FromHandle<Mood>` -> `Materialize` at the first read.
   */
  fun moodsOnFile(): List<Mood> = listOf(Mood.CALM, Mood.PLAYFUL)

  /**
   * ADR-097 · read side, `Map<String, Mood>` return -- the enum sits in the VALUE slot, which
   * distinguishes the value slot's read projection from [moodsOnFile]'s element slot.
   */
  fun moodChart(): Map<String, Mood> = mapOf("oreo" to Mood.CALM, "mylo" to Mood.ANXIOUS)

  /**
   * ADR-097 §3 · the gate-narrowing cell. `List`'s callable-input gate narrows from
   * `isBridgeableComponent()` to `isWrappableComponent()`, so this becomes a named
   * `SKIPPED_UNSUPPORTED_INPUT` instead of binding and throwing `NotSupportedException` at the
   * first call. Asserted **absent** from the generated C# -- there is no callable to invoke.
   */
  fun logSpans(spans: List<Short>): String = spans.joinToString(",")
}
