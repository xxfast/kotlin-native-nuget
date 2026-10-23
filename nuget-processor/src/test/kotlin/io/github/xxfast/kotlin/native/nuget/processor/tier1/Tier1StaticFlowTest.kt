package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-230: a `Flow` family return or property on an owner with no C# instance scope binds as a
 * static `KotlinFlow<T>` (or hot variant), its exports keyed on no owner handle and no scope.
 */
class Tier1StaticFlowTest {

  private val source: String = """
    package tier1.staticflow

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    fun purrs(count: Int): Flow<Int> = flowOf(count)

    val bedtime: MutableStateFlow<Int> = MutableStateFlow(21)

    object Clock {
      fun ticks(): Flow<Int> = flowOf(1)
      val hour: StateFlow<Int> = MutableStateFlow(9)
    }

    class Hourglass {
      companion object Sand {
        fun grains(): Flow<Int> = flowOf(1)
      }
    }

    enum class Chime {
      BONG;

      fun play(): Flow<Int> = flowOf(ordinal)
    }

    value class Pendulum(val millis: Int) {
      fun beats(): Flow<Int> = flowOf(millis)
      val pulse: Flow<Int> get() = flowOf(millis)
    }
  """.trimIndent()

  @Test
  fun `a flow on every static owner binds with no owner handle and no scope`() {
    val result: Tier1Result =
      Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    val unrouted: List<String> = result.kspWarnings.filter { warning ->
      val names = listOf("purrs", "bedtime", "ticks", "hour", "grains", "play", "beats", "pulse")
      names.any { it in warning }
    }
    assertTrue(unrouted.isEmpty(), "no static flow member may be named; got $unrouted")
    assertContains(result.generated, "_clock_ticks_collect(\n  onNextPtr: COpaquePointer,")
    assertContains(result.generated, "CoroutineScope(Dispatchers.Default)")
    assertContains(result.generatedCSharp, "public static KotlinFlow<int> Purrs(int count)")
    assertContains(result.generatedCSharp, "public static KotlinMutableStateFlow<int> Bedtime")
    assertContains(result.generatedCSharp, "public static KotlinStateFlow<int> Hour")
    assertContains(result.generatedCSharp, "public static KotlinFlow<int> Grains()")
    assertContains(result.generatedCSharp, "public static KotlinFlow<int> Play(this ")
    assertContains(result.generatedCSharp, "public KotlinFlow<int> Beats()")
    assertContains(result.generatedCSharp, "public KotlinFlow<int> Pulse")
    assertFalse(
      result.generated.lines().any { line -> "StableRef.create(" in line },
      "every handle goes through NugetHandles",
    )
  }

  /**
   * A named companion's member is `Owner.Sand.x` in every diagnostic, never `Owner.Companion.x`:
   * the companion's real name, which is what the author reads and greps for.
   */
  @Test
  fun `a named companion's skipped member names the companion it is declared in`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.namedcompaniondiagnostic

      class Hourglass {
        companion object Sand {
          fun pour(grains: Sequence<Int>): Int = 0
          val heap: Sequence<Int> = emptySequence()
        }
      }
      """.trimIndent(),
    )

    val pour: List<String> = result.kspWarnings.filter { "pour" in it }
    val heap: List<String> = result.kspWarnings.filter { "heap" in it }
    assertTrue(pour.any { "Hourglass.Sand.pour" in it }, "kspWarnings=${result.kspWarnings}")
    assertTrue(heap.any { "Hourglass.Sand.heap" in it }, "kspWarnings=${result.kspWarnings}")
    assertTrue(
      result.kspWarnings.none { "Hourglass.Companion." in it },
      "kspWarnings=${result.kspWarnings}",
    )
  }
}
