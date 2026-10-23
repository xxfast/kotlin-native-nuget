package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The callback shapes ADR-239 leaves refused, each pinned to its current diagnostic so the
 * function-type spelling keeps a stable home:
 *  - a lambda of four or more parameters, past the shipped arity 0 to 3 rule;
 *  - a `Pair` or `IntArray` payload, which the bridge maps at no position;
 *  - an `Any` payload, whose wire is a separate change.
 * Each is named exactly once, spelled as the source wrote it, and absent from both halves.
 */
class Tier1RefusedCallbackShapesTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.refusedshapes

      class Kennel {
        fun crowd(pick: (Int, Int, Int, Int) -> Int): Int = pick(1, 2, 3, 4)
        fun pairs(cb: (Pair<Int, Int>) -> Unit) = cb(1 to 2)
        fun ints(cb: (IntArray) -> Unit) = cb(IntArray(0))
        fun anything(cb: (Any) -> Unit) = cb(1)
      }
      """.trimIndent(),
    )
  }

  private fun named(member: String): List<String> =
    result.kspWarnings.filter { warning -> "Kennel.$member:" in warning }

  @Test
  fun `each refused shape is named once with its spelling and reaches neither half`() {
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    mapOf(
      "crowd" to "`(Int, Int, Int, Int) -> Int` takes 4 parameters, and a lambda crosses with at " +
        "most 3 on every callback route. Group the rest into a class, or split the lambda",
      "pairs" to "(Pair<Int, Int>) -> Unit",
      "ints" to "(IntArray) -> Unit",
      "anything" to "(Any) -> Unit",
    ).forEach { (member, spelling) ->
      val warnings: List<String> = named(member)
      assertEquals(1, warnings.size, "expected `$member` named once; got: ${result.kspWarnings}")
      assertTrue(spelling in warnings.single(), "expected `$spelling` in: ${warnings.single()}")
      assertTrue("Function4" !in warnings.single(), warnings.single())
      assertTrue("generic class" !in warnings.single(), warnings.single())
      val csharp: String = member.replaceFirstChar { it.uppercase() } + "("
      assertFalse(
        result.generatedCSharp.lines().any { line ->
          line.trimStart().startsWith("public ") && csharp in line
        },
        "expected no `$csharp` declared",
      )
      assertFalse("kennel_$member\"" in result.generated, "expected no Kotlin export for $member")
    }
  }
}
