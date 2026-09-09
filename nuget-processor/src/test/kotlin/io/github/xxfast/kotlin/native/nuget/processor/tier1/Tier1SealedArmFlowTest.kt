package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPlanSkipReason
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #129 / ADR-124: a `Flow<T>` or `StateFlow<T>` **declared on a sealed subclass** binds on
 * that arm, off the arm's own export prefix (`job_watching_get_ticks_collect`), through the same
 * collect and value thunks the ordinary-class route uses.
 *
 * Two halves failed for two different reasons before this. The method form was named
 * `SKIPPED_UNSUPPORTED_COMBINATION` (`SEALED_SUBCLASS_UNROUTED`, detail `FLOW_PROTOCOL`); the
 * property form was dropped in silence, because the property planner returns early for a
 * legacy-routed protocol on the assumption a named legacy route re-emits it, which was false for an
 * arm. So the cells below assert the property and the method together, and assert the *diagnostic*
 * separately: a `FLOW_PROTOCOL` arm member is no longer named, a `GENERIC` one still is.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing, not decoration:
 * without `Flow` and `StateFlow` on the KSP libraries path every member here is skipped as an
 * unsupported type and every assertion below passes vacuously.
 *
 * Mylo watches from the windowsill and ticks; Oreo runs the hallway and beats.
 */
class Tier1SealedArmFlowTest {

  private val fixture: String = """
    package tier1.armflow

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flow

    sealed class Job {
      // A flow-only arm: no suspend member, so the scope, IAsyncDisposable and DisposeAsync all
      // arrive from the flow route alone.
      data class Watching(val id: String) : Job() {
        private val _ticks: MutableStateFlow<Int> = MutableStateFlow(id.length)

        // The issue's own shape: a StateFlow property getter on an arm.
        val ticks: StateFlow<Int> get() = _ticks

        // The method half, String in and out on the collect protocol.
        fun labels(prefix: String): Flow<String> = flow { emit("${'$'}prefix${'$'}id") }

        // Overload pair on an arm: `_2` on the entry point AND on the extern stem.
        fun labels(prefix: String, times: Int): Flow<String> =
          flow { repeat(times) { index -> emit("${'$'}prefix#${'$'}index") } }
      }

      // Coexistence: a flow member on an arm that already carries a suspend member. One scope.
      data class Running(val progress: Int) : Job() {
        private val _beats: MutableStateFlow<Int> = MutableStateFlow(progress)
        val beats: StateFlow<Int> get() = _beats
        suspend fun pause(): Int = progress
      }

      // The control: neither a suspend nor a flow member, so no scope and no IAsyncDisposable.
      data class Done(val code: Int) : Job()
    }

    class JobFactory {
      fun watching(id: String): Job.Watching = Job.Watching(id)
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "JobSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /**
   * The Kotlin half: every export takes the arm's own `${'$'}{sealed}_${'$'}{sub}` prefix, and the
   * overload number lands on the entry point. The absence assertions are the load-bearing half:
   * nothing may be exported under the sealed **base**'s prefix (the member exists on one arm), and
   * the flow-free arm must gain nothing at all.
   */
  @Test
  fun `an arm's flow members export under the arm's own prefix`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the arm's flow exports to compile; got: " +
          "${result.compileErrors} ${result.kspErrors}",
    )

    val missing: List<String> = listOf(
      "@CName(\"job_watching_get_ticks_collect\")",
      "@CName(\"job_watching_get_ticks_value\")",
      "@CName(\"job_watching_labels_collect\")",
      "@CName(\"job_watching_labels_2_collect\")",
      "@CName(\"job_running_get_beats_collect\")",
      "@CName(\"job_running_get_beats_value\")",
    ).filterNot(result.generated::contains)

    assertTrue(missing.isEmpty(), "expected the arm-prefixed flow exports; missing: $missing")

    val leaked: List<String> = listOf(
      // The base is abstract in C# and has no scope to collect into; a member declared on one arm
      // must not be exported as if every arm had it.
      "job_get_ticks_collect",
      "job_labels_collect",
      // The control arm declares no flow member, so no collect protocol may be minted for it.
      "job_done_ticks_collect",
      "job_done_get_ticks_collect",
    ).filter { entryPoint -> result.generated.contains("@CName(\"$entryPoint") }

    assertTrue(
      leaked.isEmpty(),
      "expected no base-prefixed or control-arm flow export; got: $leaked",
    )
  }

  /**
   * The C# half. The element is spelled exactly as the ordinary-class route spells it, and the two
   * overloads carry **distinct externs**: a `_2` that lands on the `EntryPoint` but not on
   * `CirMethod.nativeName` compiles (the arities differ) and dispatches the two-argument body to
   * the one-argument extern, which is a wrong *value*, not a missing member.
   */
  @Test
  fun `an arm's flow members render as KotlinFlow and KotlinStateFlow`() {
    val result = run()

    val missing: List<String> = listOf(
      "public KotlinStateFlow<int> Ticks",
      "public KotlinFlow<string> Labels(string prefix)",
      "public KotlinFlow<string> Labels(string prefix, int times)",
      "public KotlinStateFlow<int> Beats",
      "Native_LabelsCollect(",
      "Native_Labels_2Collect(",
      "EntryPoint = \"job_watching_get_ticks_collect\"",
      "EntryPoint = \"job_watching_get_ticks_value\"",
      "EntryPoint = \"job_watching_labels_collect\"",
      "EntryPoint = \"job_watching_labels_2_collect\"",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected the arm's flow surface; missing: $missing; got: " +
          "${csharpLinesFor(result, "Kotlin")}",
    )
  }

  /**
   * The lifetime shape. A flow-only arm owns a scope exactly as an ordinary class whose only async
   * member is a flow does, so it is `IAsyncDisposable`; the arm carrying both routes owns **one**
   * scope, not one per route; and the arm with neither keeps the shape it has shipped with.
   */
  @Test
  fun `a flow-bearing arm is IAsyncDisposable and owns exactly one scope`() {
    val result = run()

    val missing: List<String> = listOf(
      "public sealed class Watching : Job, IAsyncDisposable",
      "public sealed class Running : Job, IAsyncDisposable",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected a scope-owning arm to be IAsyncDisposable; missing: $missing",
    )

    assertTrue(
      result.generatedCSharp.contains("public sealed class Done : Job\n"),
      "expected the arm with neither a suspend nor a flow member to keep its shipped " +
          "header; got: ${csharpLinesFor(result, "class Done")}",
    )
    // One `_scopeHandle` per scope-owning arm: Watching and Running, and nothing for Done.
    assertTrue(
      Regex("internal IntPtr _scopeHandle;").findAll(result.generatedCSharp).count() == 2,
      "expected exactly one scope field per scope-owning arm; got: " +
          "${csharpLinesFor(result, "_scopeHandle")}",
    )
  }

  /**
   * The diagnostic pair that keeps this change from silently closing issue #129's siblings: a
   * `FLOW_PROTOCOL` arm member is routed now and must not be named a drop, while a `GENERIC` one
   * has no arm route and must keep saying so.
   */
  @Test
  fun `a flow arm member is no longer named unrouted while a generic one still is`() {
    val result = Tier1Harness.run(
      """
      package tier1.armunrouted

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flow

      sealed class Node {
        data class Live(val id: String) : Node() {
          fun ticks(): Flow<String> = flow { emit(id) }
          fun <T> pick(value: T): T = value
        }
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val unrouted: List<String> = result.kspWarnings
      .filter { warning ->
        warning.contains(ForwardPlanSkipReason.SEALED_SUBCLASS_UNROUTED.name) ||
            warning.contains("sealed subclass")
      }

    assertFalse(
      unrouted.any { it.contains("ticks") },
      "expected a Flow-returning arm member to be routed, not named a drop; got: $unrouted",
    )
    assertTrue(
      unrouted.any { it.contains("pick") },
      "expected a generic arm member to keep its named skip; got: ${result.kspWarnings}",
    )
  }

  /**
   * ADR-112's half of the same question, which ADR-118 left open: `rootSealedClasses` filters
   * `isEligibleSealedType()`, so an **eligible sealed interface** rides the same `sealedClasses`
   * list the arm loop, both gates and the sealed translator iterate. Shaped after
   * `issue54/SealedInterfaceSample.kt`'s `Pulse`, so the answer is pinned rather than argued.
   */
  @Test
  fun `an eligible sealed interface's arm binds its flow member too`() {
    val result = Tier1Harness.run(
      """
      package tier1.armflowinterface

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      sealed interface Pulse {
        data class Beat(val bpm: Int) : Pulse {
          private val _rate: MutableStateFlow<Int> = MutableStateFlow(bpm)
          val rate: StateFlow<Int> get() = _rate
        }

        data object Flat : Pulse
      }

      fun anyPulse(): Pulse = Pulse.Beat(72)
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the sealed-interface arm to compile; got: " +
          "${result.compileErrors} ${result.kspErrors}",
    )
    assertTrue(
      result.generated.contains("@CName(\"pulse_beat_get_rate_collect\")"),
      "expected the eligible sealed interface's arm to take the same route a sealed class's arm " +
          "takes; got: ${result.generated.lines().filter { it.contains("pulse_beat") }}",
    )
    assertTrue(
      result.generatedCSharp.contains("public KotlinStateFlow<int> Rate"),
      "expected the arm's flow property on the C# side too; got: " +
          "${csharpLinesFor(result, "Rate")}",
    )
  }

  private fun csharpLinesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(needle) }
}
