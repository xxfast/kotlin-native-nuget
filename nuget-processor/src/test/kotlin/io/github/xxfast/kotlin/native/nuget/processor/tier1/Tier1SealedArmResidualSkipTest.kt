package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPlanSkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-116: the member shape a sealed arm still drops as `SEALED_SUBCLASS_UNROUTED`, end to end. A
 * `suspend` lambda parameter has no route on an ordinary class either, so the arm has nothing to
 * key to; what it owes the author is one named warning per member, with a hint that can be
 * followed. A generic method (`fun <T>`) was the other residual until ADR-197 routed it on the
 * plan for every class-like owner, the arm included; it is pinned here as binding.
 *
 * The hint used to read "move the member onto an ordinary class (which still has the legacy route
 * this member kind needs)", which sent the author to an owner that skips the same member again.
 *
 * `TYPE_PARAMETER`, the third `droppedFromCSharp = false` reason the post-process would reclassify,
 * has no producer in the planner (a nested `T` classifies as `UNSUPPORTED`), so no arm can reach it.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` matches the other suspend cells, so
 * the `suspend` lambda resolves as one rather than as something unrecognisable.
 *
 * Rex fetches whatever he is thrown, and fetches later only when he feels like it.
 */
class Tier1SealedArmResidualSkipTest {

  private val fixture: String = """
    package tier1.armresidual

    sealed class Errand {
      class Fetch(val item: String) : Errand() {
        // The former generic residual: ADR-197 routes it on the plan.
        fun <T> pick(x: T): T = x

        // A generic shape the member route refuses on every owner: a `T` nested in a collection.
        fun <T> sort(values: List<T>): Int = values.size

        // The suspend-lambda residual: SUSPEND_CALLBACK_PROTOCOL from the parameter.
        fun later(block: suspend (Int) -> String): Int = item.length

        // The control: an ordinary member on the same arm keeps binding.
        fun weight(): Int = item.length
      }

      data object Rest : Errand()
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "ErrandSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  @Test
  fun `an arm with residual members still compiles clean`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the arm to compile with its residual members dropped; got: " +
          "${result.compileErrors} ${result.kspErrors}",
    )
  }

  /** Declarations only: the generated `///` remarks deliberately name skipped members. */
  @Test
  fun `the residual members are absent from both halves while the control binds`() {
    val result = run()

    val leakedKotlin: List<String> = listOf("errand_fetch_sort", "errand_fetch_later")
      .filter { name -> Regex("@CName\\(\"[^\"]*__${name}\"\\)").containsMatchIn(result.generated) }
    assertTrue(leakedKotlin.isEmpty(), "expected no Kotlin export; got: $leakedKotlin")

    val declarations: List<String> = result.generatedCSharp.lines()
      .map(String::trim)
      .filterNot { line -> line.startsWith("//") }
    val leakedCSharp: List<String> = listOf("Sort", "Later").filter { member ->
      declarations.any { line -> Regex("\\b$member\\s*[<(]").containsMatchIn(line) }
    }
    assertTrue(leakedCSharp.isEmpty(), "expected no C# member; got: $leakedCSharp")

    assertTrue(
      declarations.any { line -> Regex("\\bWeight\\s*\\(").containsMatchIn(line) },
      "expected the control member to keep binding; got: " +
          "${declarations.filter { it.contains("Weight") }}",
    )
  }

  /** ADR-197: the former residual binds on both halves and is named nowhere. */
  @Test
  fun `the generic method binds on the arm`() {
    val result = run()

    assertTrue(
      Regex("@CName\\(\"[^\"]*__errand_fetch_pick\"\\)").containsMatchIn(result.generated),
      "expected the arm's generic method to export",
    )
    assertTrue(
      result.generatedCSharp.contains("public T Pick<T>(T x)"),
      "expected the arm to declare `public T Pick<T>(T x)`; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("Pick") }}",
    )
    assertTrue(
      result.kspWarnings.none { warning -> warning.contains("Errand.Fetch.pick") },
      "a member that binds must not be named as a skip; got: ${result.kspWarnings}",
    )
  }

  @Test
  fun `a refused generic shape is named once with a hint that binds`() {
    assertNamedOnce(
      member = "Errand.Fetch.sort",
      detail = ForwardPlanSkipReason.GENERIC,
      hint = "expose a non-generic wrapper",
    )
    // ADR-197: the sentence names the rule the shape broke; the hint stays general.
    val warning: String = run().kspWarnings.single { it.contains("Errand.Fetch.sort") }
    assertTrue(
      warning.contains("(here, its type parameter is nested in `"),
      "expected the specific refusal; got: $warning",
    )
  }

  @Test
  fun `the suspend lambda parameter is named once with a hint that binds`() {
    assertNamedOnce(
      member = "Errand.Fetch.later",
      detail = ForwardPlanSkipReason.SUSPEND_CALLBACK_PROTOCOL,
      hint = "take or return a plain (non-suspend) lambda instead",
    )
  }

  /**
   * The detail in the sentence is the discriminator: it proves the member reached
   * `SEALED_SUBCLASS_UNROUTED` from the reason this cell claims, not from `UNSUPPORTED` or the
   * ordinary-owner `UNROUTED_POSITION`.
   */
  private fun assertNamedOnce(member: String, detail: ForwardPlanSkipReason, hint: String) {
    val result = run()

    val named: List<String> = result.kspWarnings.filter { warning -> warning.contains(member) }
    assertEquals(1, named.size, "expected exactly one warning naming $member; got: $named")

    val warning: String = named.single()
    assertTrue(
      warning.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION.name}]"),
      "expected SKIPPED_UNSUPPORTED_COMBINATION; got: $warning",
    )
    assertTrue(
      warning.contains("it is a ${detail.name} member of a sealed subclass"),
      "expected the ${detail.name} sealed-subclass sentence; got: $warning",
    )
    assertTrue(warning.contains(hint), "expected the hint `$hint`; got: $warning")
    assertFalse(
      warning.contains("move the member onto an ordinary class"),
      "expected no hint toward an ordinary class, which skips the member too; got: $warning",
    )
  }
}
