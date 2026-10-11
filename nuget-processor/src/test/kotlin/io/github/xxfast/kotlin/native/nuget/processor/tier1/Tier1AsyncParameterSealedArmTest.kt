package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-210 spike: what a sealed-arm method taking a `Flow<T>` or a `suspend (T) -> R` parameter does
 * TODAY, measured rather than read. Three cells on one arm, each asserting the observed state so
 * the ADR-210 implementation has a pin to move:
 *
 *  - (a) `fun watchFlow(ticks: Flow<Int>): Int` is a **silent drop**: absent from both halves and
 *    named by no diagnostic of any kind. That is a defect, pinned here and not fixed. The sealed
 *    post-process (`ForwardCallablePlanner.kt`, the `isUnrouted` test, "ADR-124: and the same for
 *    the legacy Flow/StateFlow route") exempts `FLOW_PROTOCOL` by reason, on the assumption the
 *    arm flow route re-emits the member, which is true of a Flow *return* and false of a Flow
 *    *parameter*; the cause is read from that site, the behaviour is measured here. The ordinary
 *    class owner names the same shape `SKIPPED_UNSUPPORTED_INPUT` ([Tier1UnroutedPositionsTest],
 *    `Depot.flowParamOnClass`).
 *  - (b) `fun watchPrepare(prepare: suspend (Int) -> String): Int` BINDS since ADR-210 slice (a),
 *    as `Func<int, CancellationToken, Task<string>>`. It was named
 *    `SKIPPED_UNSUPPORTED_COMBINATION` (`SUSPEND_CALLBACK_PROTOCOL` member of a sealed subclass).
 *  - (c) `suspend fun watchFlowSuspended(ticks: Flow<Int>): Int` is named
 *    `SKIPPED_UNSUPPORTED_INPUT` by the refused-legacy-route walk, under the package-less symbol
 *    `Shape.Circle.watchFlowSuspended`.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without `Flow` on the
 * KSP libraries path the parameter is an error type and every cell proves nothing.
 */
class Tier1AsyncParameterSealedArmTest {

  private val fixture: String = """
    package tier1.armasync

    import kotlinx.coroutines.flow.Flow

    sealed class Shape {
      class Circle(val radius: Int) : Shape() {
        fun okOnArm(): Int = radius
        fun watchFlow(ticks: Flow<Int>): Int = 0
        fun watchPrepare(prepare: suspend (Int) -> String): Int = if (prepare === prepare) 0 else 1
        suspend fun watchFlowSuspended(ticks: Flow<Int>): Int = 0
      }

      object Blank : Shape()
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "ShapeSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /** Every diagnostic, error or warning, that mentions [member] as a whole word. */
  private fun Tier1Result.diagnosticsMentioning(member: String): List<String> {
    val word = Regex("\\b${Regex.escape(member)}\\b")
    return (kspErrors + kspWarnings).filter { line -> word.containsMatchIn(line) }
  }

  /** Lines of either generated half that mention [name] as a whole word, comments included. */
  private fun Tier1Result.generatedMentioning(name: String): List<String> {
    val word = Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE)
    return (generatedCSharp.lines() + generated.lines()).filter { line ->
      word.containsMatchIn(line)
    }
  }

  @Test
  fun `the arm itself binds so an absent member is not a skipped arm`() {
    val result: Tier1Result = run()

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(
      result.generatedCSharp.contains("public int OkOnArm()"),
      "the control member must bind, or every absence below is vacuous",
    )
  }

  @Test
  fun `DEFECT a Flow parameter on a sealed arm method is dropped with no diagnostic`() {
    val result: Tier1Result = run()

    assertEquals(
      emptyList(),
      result.generatedMentioning("watchFlow"),
      "watchFlow is in neither half today, not even as a `Not generated from Kotlin` remark",
    )
    assertFalse(
      result.generated.contains("shape_circle_watchFlow\")"),
      "no Kotlin export either",
    )
    // The pinned defect: nothing names it. When ADR-210 binds it (or a fix names it), this cell
    // is the one to rewrite.
    assertEquals(
      emptyList(),
      result.diagnosticsMentioning("watchFlow"),
      "today no diagnostic of any kind names Shape.Circle.watchFlow",
    )
  }

  // ADR-210 slice (a): the cell that flipped. A non-suspend arm member can only store the lambda,
  // which the Kotlin-owned lifetime makes sound, so it binds off the plan like any other owner.
  @Test
  fun `a suspend lambda parameter on a sealed arm method binds and is not named`() {
    val result: Tier1Result = run()

    assertTrue(
      result.generatedCSharp.contains(
        "public int WatchPrepare(Func<int, global::System.Threading.CancellationToken, " +
            "global::System.Threading.Tasks.Task<string>> prepare)"
      ),
      "got=" + result.generatedCSharp.lines().filter { it.contains("WatchPrepare") },
    )
    assertTrue(
      result.generated.contains("shape_circle_watchPrepare\")"),
      "the Kotlin half exports it too",
    )
    assertEquals(emptyList(), result.diagnosticsMentioning("watchPrepare"))
  }

  @Test
  fun `a Flow parameter on a sealed arm suspend member is named as an unsupported input`() {
    val result: Tier1Result = run()

    assertFalse(result.generatedCSharp.contains("WatchFlowSuspendedAsync("), "absent from Interop.cs")
    assertFalse(result.generatedCSharp.contains("WatchFlowSuspended("), "absent from Interop.cs")
    val named: List<String> = result.diagnosticsMentioning("watchFlowSuspended")
    assertEquals(1, named.size, "named exactly once; got=$named")
    val warning: String = named.single()
    assertTrue(
      warning.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name}]"),
      warning,
    )
    // The refused-legacy-route walk spells the symbol without the package, unlike cell (b).
    assertTrue(warning.contains("Skipping Shape.Circle.watchFlowSuspended:"), warning)
    assertTrue(warning.contains("but not ticks: Flow<Int>"), warning)
  }
}
