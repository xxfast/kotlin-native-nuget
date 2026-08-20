package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `CNameExports.kt`'s file-level `@OptIn` named `kotlinx.coroutines.ExperimentalCoroutinesApi`
 * unconditionally, while every coroutines *import* it needs is gated on
 * `hasSuspendFunctions || needsFlowImports`. A library with no suspend and no `Flow` surface
 * therefore failed its own Kotlin compile on an unresolved reference unless its author added
 * `kotlinx-coroutines-core` by hand, a dependency the library does not otherwise use.
 *
 * The gate is the complete one: every coroutines-referencing emission (suspend functions and
 * suspend lambdas, `Flow`/`StateFlow`, the scope helpers) sits underneath it, so the opt-in
 * member belongs under the same condition as the imports.
 */
class Tier1CoroutineFreeModuleTest {

  private val coroutineFreeFixture = """
    package tier1.coroutinefree

    class Ledger(val entries: Int) {
      fun describe(label: String): String = "${'$'}label: ${'$'}entries"
    }

    fun total(a: Int, b: Int): Int = a + b
    """.trimIndent()

  /**
   * The structural half: a module with no coroutines surface must not so much as mention the
   * package, since the mention is exactly what forces the dependency.
   */
  @Test
  fun `a coroutine-free module generates no reference to kotlinx coroutines`() {
    val result = Tier1Harness.run(coroutineFreeFixture, coroutinesOnCompileClasspath = false)

    assertFalse(
      "kotlinx.coroutines" in result.generated,
      "expected no kotlinx.coroutines reference in a module with no suspend/Flow surface, " +
          "but the generated file references it:\n${result.generated}",
    )
  }

  /**
   * The compile half, and the one that reproduces the reported failure: the same fixture built
   * with `kotlinx-coroutines-core` genuinely absent from the classpath, as it is for a consumer
   * library that never took the dependency.
   */
  @Test
  fun `a coroutine-free module compiles without coroutines on the classpath`() {
    val result = Tier1Harness.run(coroutineFreeFixture, coroutinesOnCompileClasspath = false)

    assertTrue(
      result.compiledClean,
      "expected the generated file to compile without kotlinx-coroutines-core on the classpath; " +
          "got: ${result.compileErrors}",
    )
  }

  /**
   * Positive control: the gate must still open. A suspend method keeps the opt-in, and the
   * generated file still compiles green against the default (coroutines-bearing) classpath.
   */
  @Test
  fun `a module with a suspend method keeps the coroutines opt-in`() {
    val result = Tier1Harness.run(
      """
      package tier1.coroutineusing

      class Loader {
        suspend fun load(id: Int): String = "item-${'$'}id"
      }
      """.trimIndent(),
    )

    assertContains(
      result.generated,
      "ExperimentalCoroutinesApi",
      message = "expected the coroutines opt-in to survive for a module WITH suspend surface; " +
          "got:\n${result.generated}",
    )
    assertTrue(
      result.compiledClean,
      "expected the suspend fixture to still compile green; got: ${result.compileErrors}",
    )
  }
}
