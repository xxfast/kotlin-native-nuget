package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-210: a sealed-arm method taking a `Flow<T>` or a `suspend (T) -> R` parameter. This class
 * began as a spike that pinned what the three cells did BEFORE the ADR, measured rather than read,
 * so each slice had a pin to move. All three bind now:
 *
 *  - (a) `fun watchFlow(ticks: Flow<Int>): Int` was a **silent drop**: absent from both halves and
 *    named by no diagnostic, because the sealed post-process exempted `FLOW_PROTOCOL` by reason on
 *    the assumption the arm flow route (ADR-124) re-emits the member, which is true of a Flow
 *    *return* and false of a Flow *parameter*. Slice (b) binds it as `IAsyncEnumerable<int>`, and
 *    the exemption no longer covers a Flow parameter of a member no legacy route hosts, so a shape
 *    that still cannot bind there is named ([Tier1FlowParameterTest], the `StateFlow` cell).
 *  - (b) `fun watchPrepare(prepare: suspend (Int) -> String): Int` was named
 *    `SKIPPED_UNSUPPORTED_COMBINATION`. Slice (a) binds it as
 *    `Func<int, CancellationToken, Task<string>>`.
 *  - (c) `suspend fun watchFlowSuspended(ticks: Flow<Int>): Int` was named
 *    `SKIPPED_UNSUPPORTED_INPUT` by the refused-legacy-route walk. Slice (b) binds it on ADR-118's
 *    arm suspend route.
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

  private val flow: String = "global::System.Collections.Generic.IAsyncEnumerable<int>"

  @Test
  fun `the arm itself binds and the generated bridge compiles`() {
    val result: Tier1Result = run()

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")
    assertTrue(result.generatedCSharp.contains("public int OkOnArm()"))
  }

  // ADR-210 slice (b): the cell that was the pinned silent drop. A non-suspend arm member can only
  // store the flow, which the Kotlin-owned lifetime makes sound.
  @Test
  fun `a Flow parameter on a sealed arm method binds and is not named`() {
    val result: Tier1Result = run()

    assertTrue(
      result.generatedCSharp.contains("public int WatchFlow($flow ticks)"),
      "got=" + result.generatedCSharp.lines().filter { it.contains("WatchFlow(") },
    )
    assertTrue(
      result.generated.contains("shape_circle_watchFlow\")"),
      "the Kotlin half exports it too",
    )
    assertEquals(emptyList(), result.diagnosticsMentioning("watchFlow"))
  }

  // ADR-210 slice (a).
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

  // ADR-210 slice (b), on ADR-118's arm suspend route.
  @Test
  fun `a Flow parameter on a sealed arm suspend member binds and is not named`() {
    val result: Tier1Result = run()

    assertTrue(
      result.generatedCSharp.contains(
        "public Task<int> WatchFlowSuspendedAsync($flow ticks, " +
            "CancellationToken cancellationToken = default)"
      ),
      "got=" + result.generatedCSharp.lines().filter { it.contains("WatchFlowSuspended") },
    )
    assertTrue(
      result.generated.contains("shape_circle_watchFlowSuspended_async\")"),
      "the Kotlin half exports it too",
    )
    assertEquals(emptyList(), result.diagnosticsMentioning("watchFlowSuspended"))
  }
}
