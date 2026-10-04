package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `SKIPPED_UNEXPORTED_SUPERTYPE` said "its public members are bound on X directly" for every
 * dropped supertype, which was false whenever an inherited member had no route and was named by
 * its own `SKIPPED_*` warning on the inheriting class. The sentence now reads the real per-member
 * outcome off the planned catalog: all bound keeps the shipped wording to the byte, some unrouted
 * names those members as named separately, and none bound says so.
 *
 * `tier1.outcomehidden` is a sibling of the export root, so its declarations stay out of scope.
 * Oreo sits on every shelf; Mylo only on the ones nobody exported.
 */
class Tier1UnexportedSupertypeOutcomeTest {

  private val sources: Map<String, String> = mapOf(
    "Hidden.kt" to """
      package tier1.outcomehidden

      import kotlinx.coroutines.flow.Flow

      interface Tidy {
        fun dust(): Int = 1
        val gleam: Int get() = 2
      }

      interface Mixed {
        fun okMixed(): Int = 1
        fun <T> tally(item: T): Int = 1
        fun feedMixed(ticks: Flow<Int>): Int = 0
      }

      interface Lost {
        fun <T> pick(item: T): Int = 1
        fun drain(ticks: Flow<Int>): Int = 0
      }

      open class MixedBase {
        fun okBase(): Int = 2
        fun <T> weigh(item: T): Int = 1
      }
    """.trimIndent(),
    "Owners.kt" to """
      package tier1.outcome

      import tier1.outcomehidden.Lost
      import tier1.outcomehidden.Mixed
      import tier1.outcomehidden.MixedBase
      import tier1.outcomehidden.Tidy

      class Shelf : Tidy

      class Rack : Mixed

      class Ledge : Lost {
        fun okLedge(): Int = 3
      }

      class Bench : MixedBase()
    """.trimIndent(),
  )

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      sources,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.outcome"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private fun supertypeWarning(owner: String): String {
    val warnings: List<String> = result.kspWarnings.filter { warning ->
      "SKIPPED_UNEXPORTED_SUPERTYPE" in warning && "Skipping $owner : " in warning
    }
    assertEquals(1, warnings.size, "kspWarnings=${result.kspWarnings}")
    return warnings.single()
  }

  @Test
  fun `every inherited member binds, so the sentence is unchanged`() {
    val warning: String = supertypeWarning("Shelf")
    assertTrue(
      "Shelf is generated without it and its public members are bound on Shelf directly" in
          warning,
      warning,
    )
    assertTrue("nothing callable is lost" in warning, warning)
  }

  @Test
  fun `some inherited members are unrouted, so the sentence names them`() {
    val warning: String = supertypeWarning("Rack")
    assertFalse("its public members are bound on Rack directly." in warning, warning)
    assertTrue(
      "its public members are bound on Rack directly except `tally` and `feedMixed`, which no " +
          "route carries and which are each named by their own warning" in warning,
      warning,
    )
    assertFalse("nothing callable is lost" in warning, warning)
    // The unrouted members really are named on the inheriting class.
    listOf("tier1.outcome.Rack.tally", "tier1.outcome.Rack.feedMixed").forEach { member ->
      assertTrue(result.kspWarnings.any { "Skipping $member" in it }, "$member is named")
    }
  }

  @Test
  fun `no inherited member binds, so the sentence says none do`() {
    val warning: String = supertypeWarning("Ledge")
    assertFalse("bound on Ledge directly" in warning, warning)
    assertTrue(
      "none of its public members bind on Ledge: `pick` and `drain` are each named by their " +
          "own warning" in warning,
      warning,
    )
    assertFalse("nothing callable is lost" in warning, warning)
  }

  @Test
  fun `the base-class sentence reads the same outcome`() {
    val warning: String = supertypeWarning("Bench")
    assertTrue(
      "the base's public members are bound on Bench directly except `weigh`, which no route " +
          "carries and which is named by its own warning" in warning,
      warning,
    )
    assertFalse("nothing callable is lost" in warning, warning)
  }
}
