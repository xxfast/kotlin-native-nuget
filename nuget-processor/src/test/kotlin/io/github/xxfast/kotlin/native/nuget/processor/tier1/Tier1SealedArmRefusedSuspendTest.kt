package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-114 / ADR-118: a `suspend` member declared **on a sealed arm** whose parameter or return the
 * legacy async route refuses is dropped from both halves, and is dropped *named*, exactly as the
 * same member on an ordinary class is (`TreatBoard.paired`, pinned by
 * [Tier1LegacyRouteCollectionParameterTest]).
 *
 * The arm branch is load-bearing precisely because nothing else would say a word. The Kotlin arm
 * export filters a refused member out, the C# arm projection filters it out too, and the planner's
 * sealed post-process exempts `SUSPEND` from `SEALED_SUBCLASS_UNROUTED`. Delete the sealed loop in
 * `warnRefusedLegacyRouteMembers` (`NugetProcessor.kt`) and `Errand.Fetch.carry` vanishes from the
 * package with **zero** diagnostics: a member the author wrote, gone, unmentioned. That deletion
 * is the red proof for this file; only these tests fail.
 *
 * Two cells on the one walk, because `nameRefused` picks a parameter refusal first and a return
 * refusal second, and the second arm had no coverage on a sealed subclass at all:
 * - `carry(load: Pair<String, Int>)` -> `SKIPPED_UNSUPPORTED_INPUT`
 * - `weigh(): Pair<String, Int>` -> `SKIPPED_UNSUPPORTED_RETURN`
 *
 * Deliberately absent: the ADR-124 refused *flow property* on an arm (`StateFlow<Pair<..>>`,
 * `SKIPPED_UNSUPPORTED_PROPERTY`), which is the same loop's third branch but ADR-124's cell, not
 * this one's.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without coroutines on
 * the KSP libraries path the suspend members resolve to nothing recognisable and every assertion
 * below passes for the wrong reason.
 *
 * Mylo carries one treat at a time and refuses to be weighed.
 */
class Tier1SealedArmRefusedSuspendTest {

  private val fixture: String = """
    package tier1.refusedarm

    sealed class Errand {
      data class Fetch(val item: String) : Errand() {
        // The cell: a refused parameter on an arm-declared suspend member.
        suspend fun carry(load: Pair<String, Int>): Int = load.second

        // The second cell: a refused return on the same arm.
        suspend fun weigh(): Pair<String, Int> = item to 1

        // The control: a routed suspend member on the same arm keeps binding.
        suspend fun drop(): Int = item.length
      }

      data object Rest : Errand()
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "ErrandSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /**
   * A refusal is not allowed to cost the package its build: the arm still compiles, minus the two
   * refused members. `entry: Pair` in the generated Kotlin (ADR-114's original break) fails here.
   */
  @Test
  fun `an arm with refused suspend members still compiles clean`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the arm to compile with its refused members dropped; got: " +
          "${result.compileErrors} ${result.kspErrors}",
    )
  }

  /** Both halves, because either one emitting alone is a broken package, not half a feature. */
  @Test
  fun `the refused arm members are absent from both halves`() {
    val result = run()

    val leakedKotlin: List<String> = listOf(
      "errand_fetch_carry_async",
      "errand_fetch_weigh_async",
    ).filter(result.generated::contains)

    assertTrue(
      leakedKotlin.isEmpty(),
      "expected no Kotlin export for a refused arm member; got: $leakedKotlin in " +
          "${result.generated.lines().filter { it.contains("errand_fetch") }.map(String::trim)}",
    )

    val leakedCSharp: List<String> = listOf("CarryAsync", "WeighAsync")
      .filter(result.generatedCSharp::contains)

    assertTrue(
      leakedCSharp.isEmpty(),
      "expected no C# member for a refused arm member; got: $leakedCSharp in " +
          "${csharpLinesFor(result, "Async")}",
    )
  }

  /**
   * The control. Refusing two members must cost the third nothing, and must not cost it *twice*:
   * exactly one `EntryPoint` for `drop`, so a refusal never mints a duplicate extern on the arm.
   */
  @Test
  fun `a routed suspend member on the same arm keeps binding once`() {
    val result = run()

    assertTrue(
      result.generated.contains("@CName(\"errand_fetch_drop_async\")"),
      "expected the routed arm member to keep its export; got: " +
          "${result.generated.lines().filter { it.contains("drop") }.map(String::trim)}",
    )

    assertEquals(
      1,
      Regex("EntryPoint = \"errand_fetch_drop_async\"").findAll(result.generatedCSharp).count(),
      "expected exactly one extern for the routed arm member; got: " +
          "${csharpLinesFor(result, "errand_fetch_drop_async")}",
    )
  }

  /** The parameter arm of `nameRefused`, named once, naming the type the author has to change. */
  @Test
  fun `the refused parameter is named once`() {
    val result = run()

    val named: List<String> = result.kspWarnings.filter {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name}]") &&
          it.contains("Errand.Fetch.carry")
    }

    assertEquals(
      1,
      named.size,
      "expected exactly one SKIPPED_UNSUPPORTED_INPUT naming Errand.Fetch.carry rather than a " +
          "silent vanish or a doubled warning; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      named.single().contains("Pair<String, Int>"),
      "expected the diagnostic to spell the offending type; got: ${named.single()}",
    )
  }

  /** The return arm of the same `nameRefused`, which no sealed fixture reached before. */
  @Test
  fun `the refused return is named once`() {
    val result = run()

    val named: List<String> = result.kspWarnings.filter {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name}]") &&
          it.contains("Errand.Fetch.weigh")
    }

    assertEquals(
      1,
      named.size,
      "expected exactly one SKIPPED_UNSUPPORTED_RETURN naming Errand.Fetch.weigh; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      named.single().contains("Pair<String, Int>"),
      "expected the diagnostic to spell the offending return type; got: ${named.single()}",
    )
  }

  /**
   * ADR-118's exemption keeps the sealed post-process from *also* naming a suspend arm member
   * `SEALED_SUBCLASS_UNROUTED`. One member, one diagnostic: a second one telling the author a
   * different story about the same line is worse than none.
   */
  @Test
  fun `a refused arm member is never also named an unrouted combination`() {
    val result = run()

    val doubled: List<String> = result.kspWarnings.filter {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION.name}]") &&
          (it.contains("carry") || it.contains("weigh"))
    }

    assertTrue(
      doubled.isEmpty(),
      "expected a refused arm member to be named once, by the refusal walk only; got: $doubled",
    )
  }

  private fun csharpLinesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(needle) }.map(String::trim)
}
