package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-097: a bare enum collection component crosses as its `Int` ordinal, and `List`'s
 * callable-input gate narrows to `isWrappableComponent()`.
 *
 * Every positive cell asserts **both** halves in the same test. The two halves are only correct
 * together: `Select(x => (int)x)` against an unchanged `it as Mood` compiles on both sides and
 * `ClassCastException`s at the first call, because `elementKotlinTypeName` already has an
 * `is BridgeType.Enum` branch and would happily render the wrong cast. Asserting one half alone
 * would let that ship.
 *
 * The gate-narrowing cell carries `List<List<String>>`, which cannot live in the `test-library`
 * corpus: before this ADR it aborted the whole KSP run in `elementKotlinTypeName`, so this is its
 * only coverage.
 */
class Tier1EnumCollectionComponentTest {

  @Test
  fun `enum components ride the int ordinal on both halves at every input position`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumcollectioninput

      enum class Mood { CALM, ANXIOUS, PLAYFUL }

      class MoodLedger {
        fun logMoods(moods: List<Mood>): Int = moods.size

        fun tallyMoods(moods: Set<Mood>): Int = moods.size

        fun chartMoods(byPatient: Map<String, Mood>): Int = byPatient.size

        fun moodRoster(byMood: Map<Mood, String>): Int = byMood.size

        fun logMoodTrail(moods: List<Mood?>): Int = moods.size

        fun chartMoodTrail(byPatient: Map<String, Mood?>): Int = byPatient.size
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected bare-enum collection inputs to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(
      kotlin,
      ".map { tier1.enumcollectioninput.Mood.entries[it as kotlin.Int] }",
    )
    assertContains(
      kotlin,
      ".mapTo(mutableSetOf()) { tier1.enumcollectioninput.Mood.entries[it as kotlin.Int] }",
    )
    assertContains(
      kotlin,
      "associate { (k, v) -> (k as kotlin.String) to " +
          "(tier1.enumcollectioninput.Mood.entries[v as kotlin.Int]) }",
    )
    assertContains(
      kotlin,
      "associate { (k, v) -> (tier1.enumcollectioninput.Mood.entries[k as kotlin.Int]) " +
          "to (v as kotlin.String) }",
    )
    // The nullable spelling is admitted by isWrappableComponent's Nullable branch the moment a
    // bare Enum is wrappable, so it needs its own arm or it casts a boxed Int to `Mood?`.
    assertContains(
      kotlin,
      ".map { (it as kotlin.Int?)?.let { v -> tier1.enumcollectioninput.Mood.entries[v] } }",
    )

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(moods, x => (int)x))",
    )
    assertContains(
      cs,
      "NugetMarshal.CreateSet(global::System.Linq.Enumerable.Select(moods, x => (int)x))",
    )
    // The one collection carrying a slot that needs conversion beside one that does not.
    assertContains(
      cs,
      "NugetMarshal.CreateMap(global::System.Linq.Enumerable.Select(byPatient, " +
          "x => new KeyValuePair<string, int>(x.Key, (int)x.Value)))",
    )
    assertContains(
      cs,
      "NugetMarshal.CreateMap(global::System.Linq.Enumerable.Select(byMood, " +
          "x => new KeyValuePair<int, string>((int)x.Key, x.Value)))",
    )
    assertContains(
      cs,
      "NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(moods, " +
          "x => x == null ? (int?)null : (int)x.Value))",
    )
    // The nullable enum in a map slot rides the nullable spelling of its own wire type (`int?`),
    // which the list spelling above never reaches.
    assertContains(
      cs,
      "NugetMarshal.CreateMap(global::System.Linq.Enumerable.Select(byPatient, " +
          "x => new KeyValuePair<string, int?>(x.Key, " +
          "x.Value == null ? (int?)null : (int)x.Value.Value)))",
    )
  }

  @Test
  fun `enum components read back through FromHandle int on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumcollectionreturn

      enum class Mood { CALM, ANXIOUS, PLAYFUL }

      class MoodLedger {
        fun moodsOnFile(): List<Mood> = listOf(Mood.CALM, Mood.PLAYFUL)

        fun moodChart(): Map<String, Mood> = mapOf("oreo" to Mood.CALM)

        fun moodTrailOnFile(): List<Mood?> = listOf(Mood.CALM, null)
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected bare-enum collection returns to compile; got: ${result.compileErrors}",
    )

    // Kotlin boxes the ordinal, not the Mood: FromHandle<Mood> reaches Materialize and throws.
    val kotlin: String = result.generated
    assertContains(kotlin, "moodsOnFile().map { it.ordinal }")
    assertContains(kotlin, "moodChart().entries.associate { (k, v) -> k to v.ordinal }")
    // ADR-083 composes: the null element rides the null pointer, the present one its ordinal.
    assertContains(kotlin, "moodTrailOnFile().map { it?.ordinal }")

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "result.Add((global::Interop.Mood)NugetMarshal.FromHandle<int>" +
          "(NugetListNative.Get(listHandle, i)));",
    )
    assertContains(
      cs,
      "var value = (global::Interop.Mood)NugetMarshal.FromHandle<int>" +
          "(NugetMapNative.ValueAt(mapHandle, i));",
    )
    assertContains(
      cs,
      "result.Add(elementHandle == IntPtr.Zero ? (global::Interop.Mood?)null : " +
          "(global::Interop.Mood)NugetMarshal.FromHandle<int>(elementHandle));",
    )
  }

  /**
   * ADR-097 section 2: one predicate, all three positions. `isWrappableComponent()` is also the
   * collection *property setter* gate (ADR-075), so the same branch flips a `List<Mood>` property
   * from get-only to a round trip.
   */
  @Test
  fun `enum component collection property gains a setter on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumcollectionproperty

      enum class Mood { CALM, ANXIOUS }

      class Chart {
        var moods: List<Mood> = emptyList()
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected an enum-component collection property to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(
      kotlin,
      "moods = value.asStableRef<MutableList<Any?>>().get()" +
          ".map { tier1.enumcollectionproperty.Mood.entries[it as kotlin.Int] }",
    )
    assertContains(kotlin, "get().moods.map { it.ordinal }")

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(value, x => (int)x))",
    )
    assertContains(
      cs,
      "result.Add((global::Interop.Mood)NugetMarshal.FromHandle<int>" +
          "(NugetListNative.Get(nativeResult, i)));",
    )
  }

  /**
   * ADR-097 section 3. `List<Short>` bound and threw `NotSupportedException` out of `Wrap<T>` at
   * the first call; `List<List<String>>` aborted the entire KSP run in `elementKotlinTypeName`.
   * Both became the same named skip `Map`/`Set` already emit.
   *
   * ADR-098 then minted `nuget_wrap_short`, so `List<Short>` moves out of the skip and binds: the
   * gate is unchanged, its allow-list grew. `List<List<String>>` stays skipped -- nested
   * collections have no wire at all -- which is what keeps this cell honest about the gate still
   * being a gate.
   */
  @Test
  fun `list input over a narrow primitive binds while a nested collection still skips named`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumcollectionnarrowing

      enum class Mood { CALM, ANXIOUS }

      class MoodLedger {
        fun logMoods(moods: List<Mood>): Int = moods.size

        fun logSpans(spans: List<Short>): Int = spans.size

        fun logNested(rows: List<List<String>>): Int = rows.size
      }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.isEmpty(),
      "expected the narrowed List gate to skip, not crash; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.compiledClean,
      "expected the narrowed List gate to leave compilable source; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(kotlin, "export_moodledger_logMoods")
    // ADR-098: the Short element crosses as itself, so the Kotlin lowering is a plain cast off
    // the boxed component -- no ordinal projection, unlike the enum cells above.
    assertContains(kotlin, "export_moodledger_logSpans")
    assertContains(kotlin, "it as kotlin.Short")
    assertTrue("export_moodledger_logNested" !in kotlin, "generated=$kotlin")

    val cs: String = result.generatedCSharp
    assertContains(cs, "LogSpans")
    assertContains(cs, "NugetMarshal.CreateList(spans)")
    assertTrue("LogNested" !in cs, "generatedCSharp=$cs")

    val skips: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) }
    assertTrue(
      skips.none { it.contains("logSpans") },
      "expected no SKIPPED_UNSUPPORTED_INPUT diagnostic for logSpans now that Short is " +
          "wrappable; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      skips.any { it.contains("logNested") },
      "expected a SKIPPED_UNSUPPORTED_INPUT diagnostic naming logNested; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }
}
