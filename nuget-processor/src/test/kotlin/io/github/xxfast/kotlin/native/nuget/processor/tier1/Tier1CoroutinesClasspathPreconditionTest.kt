package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A Tier 1 fixture that mentions `kotlinx.coroutines` without `libraries =
 * listOf(Tier1Classpath.kotlinxCoroutinesCore)` used to degrade silently: the KSP2 analysis session
 * only ever sees `kotlinStdlib + libraries`, so `Flow<Int>` resolved to a KSP error type, the
 * member was dropped as unsupported, and the cell went green against the wrong thing.
 * `coroutinesOnCompileClasspath` (default `true`) does not help: it feeds the `K2JVMCompiler` step
 * only, never the KSP path. The harness now refuses the run.
 */
class Tier1CoroutinesClasspathPreconditionTest {

  private val flowSource: String = """
    package tier1.coroutinesclasspath

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    class Ticker {
      fun ticks(): Flow<Int> = flowOf(1)
    }
  """.trimIndent()

  @Test
  fun `a coroutines fixture without the jar fails the harness precondition`() {
    val failure = assertFailsWith<IllegalArgumentException> { Tier1Harness.run(flowSource) }

    assertTrue(
      failure.message.orEmpty().contains("Tier1Classpath.kotlinxCoroutinesCore"),
      "the message must name the fix; got: ${failure.message}",
    )
    assertTrue(
      failure.message.orEmpty().contains("coroutinesOnCompileClasspath"),
      "the message must name the flag that does NOT cover the KSP path; got: ${failure.message}",
    )
  }

  @Test
  fun `the same fixture with the jar runs`() {
    val result = Tier1Harness.run(
      flowSource,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "export_library_tier1_coroutinesclasspath__ticker_ticks" in result.generated,
      "expected the Flow route to bind against the real Flow; generated:\n${result.generated}",
    )
  }

  @Test
  fun `a commonSources half mentioning coroutines is covered too`() {
    assertFailsWith<IllegalArgumentException> {
      Tier1Harness.run(
        sources = mapOf(
          "Actual.kt" to """
          package tier1.coroutinesclasspath.common

          actual class Ticker actual constructor()
          """.trimIndent(),
        ),
        commonSources = mapOf(
          "Common.kt" to """
          package tier1.coroutinesclasspath.common

          import kotlinx.coroutines.flow.Flow

          expect class Ticker() {
            fun ticks(): Flow<Int>
          }
          """.trimIndent(),
        ),
      )
    }
  }

  @Test
  fun `a coroutines-free fixture is unaffected`() {
    val result = Tier1Harness.run(
      """
      package tier1.coroutinesclasspath.free

      class Counter(val start: Int) {
        fun next(): Int = start + 1
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
  }
}
