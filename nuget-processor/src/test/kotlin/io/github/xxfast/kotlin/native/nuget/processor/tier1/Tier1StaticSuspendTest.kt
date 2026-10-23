package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-229: the static suspend route renders a companion's `suspend` member as a static `XAsync`
 * on its owner class, beside the class's instance `XAsync` members. C# static-ness is not part of
 * a signature, so a companion member and an instance member of one name and one parameter list
 * are CS0111. The ADR-034 signature guard sees both lists and fails the build instead.
 */
class Tier1StaticSuspendTest {

  @Test
  fun `a companion XAsync clashing with an instance XAsync of the same parameters is fatal`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.staticsuspendclash

      class Den(val name: String) {
        suspend fun open(times: Int): String = name

        companion object {
          suspend fun open(times: Int): String = "den"
        }
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val collision: String? = result.kspErrors.firstOrNull { error ->
      ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name in error && "OpenAsync" in error
    }
    assertTrue(collision != null, "expected the OpenAsync clash named; kspErrors=${result.kspErrors}")
  }

  @Test
  fun `a companion XAsync with a different parameter list binds beside the instance one`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.staticsuspendoverload

      class Den(val name: String) {
        suspend fun open(): String = name

        companion object {
          suspend fun open(times: Int): String = "den"
        }
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertContains(result.generatedCSharp, "public Task<string> OpenAsync(CancellationToken")
    assertContains(result.generatedCSharp, "public static Task<string> OpenAsync(int times")
    assertContains(result.generated, "_den_companion_open_async")
  }

  /** ADR-230: a static owner's `Flow` result binds, awaited and then collected statically. */
  @Test
  fun `a static suspend member returning a Flow binds`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.staticsuspendflow

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      object Clock {
        suspend fun ticks(): Flow<Int> = flowOf(1)
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.kspWarnings.none { "Clock.ticks" in it }, "kspWarnings=${result.kspWarnings}")
    assertContains(result.generated, "_clock_ticks_async")
    assertContains(result.generatedCSharp, "public static Task<KotlinFlow<int>> TicksAsync(")
  }

  /** A receiver the route has no slot for (a `List`) keeps the extension's named skip. */
  @Test
  fun `a suspend extension on a collection receiver stays named`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.staticsuspendreceiver

      suspend fun List<Int>.total(): Int = sum()
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val named: List<String> = result.kspWarnings.filter { "total" in it }
    assertTrue(named.size == 1, "total must be named once; kspWarnings=${result.kspWarnings}")
    assertContains(named.single(), "as an extension on any other receiver")
  }
}
