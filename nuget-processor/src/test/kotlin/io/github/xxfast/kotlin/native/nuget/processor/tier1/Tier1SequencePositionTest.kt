package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-064 amendment (2026-09-11): `kotlin.sequences.Sequence` is a **named** unsupported stdlib
 * type at every callable position, not a silent one.
 *
 * `Sequence` is an interface with one type parameter, so the classifier used to answer
 * `SpecializedProtocol("generic declaration kotlin.sequences.Sequence")`, which the planner maps to
 * `ForwardPlanSkipReason.GENERIC` -- a `droppedFromCSharp = false` legacy-route deferral. No legacy
 * route is keyed on a *parameter's* type though (the generic routes key on the callable's or the
 * class's own type parameters), so the member vanished from the generated C# with no warning at
 * all. Recognising the qualified name in the classifier's known-stdlib block turns every position
 * into a named drop.
 *
 * A `Sequence`-typed *property* was already named (`ForwardPropertyPlanner.recordDropped` is silent
 * only for the lambda/flow legacy protocols), so its cell here guards wording, not silence.
 */
class Tier1SequencePositionTest {

  private val fixture: String =
    """
    package tier1.sequence

    class Ticker(val name: String) {
      fun load(entries: Sequence<String>): Int = entries.count()
      fun stream(): Sequence<String> = sequenceOf(name)
      val unsupported: Sequence<String> = sequenceOf(name)
      fun size(): Int = name.length
    }

    fun tick(entries: Sequence<Int>): Int = entries.sum()
    """.trimIndent()

  @Test
  fun `class method with a Sequence parameter fires a named skip and is omitted from both halves`() {
    val result = Tier1Harness.run(fixture)

    assertTrue(
      result.compiledClean,
      "expected no broken source for the Sequence fixture; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_ticker_load" !in result.generated,
      "expected load to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      "Load(" !in result.generatedCSharp,
      "expected load to be entirely absent from the generated C#; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name) &&
            it.contains("Ticker.load")
      },
      "expected a named skip diagnostic for Ticker.load's Sequence parameter; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `class method returning a Sequence fires a named skip and is omitted from both halves`() {
    val result = Tier1Harness.run(fixture)

    assertTrue(
      "export_ticker_stream" !in result.generated,
      "expected stream to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      "Stream(" !in result.generatedCSharp,
      "expected stream to be entirely absent from the generated C#; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name) &&
            it.contains("Ticker.stream")
      },
      "expected a named skip diagnostic for Ticker.stream's Sequence return; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `top-level function with a Sequence parameter fires a named skip and is omitted`() {
    val result = Tier1Harness.run(fixture)

    assertTrue(
      "export_tick(" !in result.generated,
      "expected tick to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name) && it.contains("tick")
      },
      "expected a named skip diagnostic for the top-level tick; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Wording cell: the property half already warned, but through the `generic declaration ` reason.
   * After the fix the message names the stdlib type itself, and no diagnostic anywhere mentions the
   * legacy generic route.
   */
  @Test
  fun `Sequence property keeps its SKIPPED_UNSUPPORTED_PROPERTY and names the stdlib type`() {
    val result = Tier1Harness.run(fixture)

    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("Ticker.unsupported") && it.contains("kotlin.sequences.Sequence")
      },
      "expected SKIPPED_UNSUPPORTED_PROPERTY naming Ticker.unsupported and its stdlib type; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.none { it.contains("generic declaration") },
      "expected no diagnostic to defer a Sequence to the generic legacy route; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /** Control: a member with no `Sequence` anywhere in its signature still binds. */
  @Test
  fun `sibling member without a Sequence still binds`() {
    val result = Tier1Harness.run(fixture)

    assertTrue(
      "export_ticker_size" in result.generated,
      "expected the control member to survive; generated=${result.generated}",
    )
  }
}
