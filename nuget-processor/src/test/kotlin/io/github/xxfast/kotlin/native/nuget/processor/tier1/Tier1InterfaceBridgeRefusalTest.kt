package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-084: an interface with a member outside the bridge's slot vocabulary plans no bridge
 * factory, so a C# class implementing it cannot be passed to Kotlin. That used to be silent at
 * build time and surfaced only as a runtime `NotSupportedException` naming the C# type. It is now
 * one named skip per interface, naming every member that keeps the bridge out and why.
 */
class Tier1InterfaceBridgeRefusalTest {

  private val code: String = ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_INTERFACE.name

  private fun refusal(pkg: String, iface: String): Pair<Tier1Result, String> {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.$pkg

      $iface

      class Holder {
        fun take(pet: Pet) = Unit
      }
      """.trimIndent(),
    )
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertFalse(result.generated.contains("pet_bridge_create"), "no factory for an unplanned Pet")
    // ADR-040: the interface itself still declares; only the C#-implementation bridge is skipped.
    assertContains(result.generatedCSharp, "interface IPet")
    val warnings: List<String> = result.kspWarnings.filter { it.contains(code) }
    assertEquals(1, warnings.size, "expected one $code line for Pet; got ${result.kspWarnings}")
    val warning: String = warnings.single()
    assertTrue("] Skipping tier1.$pkg.Pet:" in warning, warning)
    assertTrue("IPet" in warning, warning)
    return result to warning
  }

  /** ADR-233: a collection slot crosses as a class member's collection does, so it plans. */
  @Test
  fun `a collection-typed slot plans the bridge`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.refuselist

      interface Pet { val tags: List<String> }

      class Holder {
        fun take(pet: Pet) = Unit
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertTrue(result.generated.contains("pet_bridge_create"), result.generated)
    assertTrue(result.kspWarnings.none { it.contains(code) }, "${result.kspWarnings}")
  }

  @Test
  fun `a generic member names the interface unimplementable`() {
    val (_, warning) = refusal(
      "refusegeneric",
      "interface Pet { fun <T> pick(item: T): T }",
    )

    assertTrue("`fun pick`" in warning, warning)
    assertTrue("type parameter" in warning, warning)
  }

  /** Every disqualifying member is named in the one line, not just the first. */
  @Test
  fun `every disqualifying member is named in one line`() {
    val (_, warning) = refusal(
      "refuseall",
      """
      interface Pet {
        val name: String
        var mood: String
        val tags: List<String>
        suspend fun fetch(id: Int): String
        fun <T> pick(item: T): T
        fun <R> choose(item: R): R
        fun walk(a: Int, b: Int, c: Int)
      }
      """.trimIndent(),
    )

    for (member in listOf("fun pick", "fun choose")) {
      assertTrue("`$member" in warning, "expected $member named; got: $warning")
    }
    // ADR-084 amendment (2026-10-22): a `var` bridges through a setter slot, so it is not named.
    // ADR-232: nor is a `suspend` member, which bridges through an awaited Begin and End pair.
    // ADR-233: nor a collection slot, nor a member of any arity.
    for (member in listOf("val name", "var mood", "suspend fun fetch", "val tags", "fun walk")) {
      assertFalse("`$member" in warning, "a bridgeable member is not named; got: $warning")
    }
  }

  /** ADR-201: a narrow-Throwable result alone keeps its shipped code, and the new one is silent. */
  @Test
  fun `a narrow Throwable result alone keeps ADR-201's code`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.refusethrowable

      interface Pet {
        fun last(): IllegalStateException?
      }

      class Holder {
        fun take(pet: Pet) = Unit
      }
      """.trimIndent(),
    )

    assertTrue(result.kspWarnings.none { it.contains(code) }, "${result.kspWarnings}")
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name) &&
          it.contains("tier1.refusethrowable.Pet.last")
      },
      "${result.kspWarnings}",
    )
  }

  /** A bridgeable interface is planned and names nothing. */
  @Test
  fun `a fully bridgeable interface is silent`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.refusenone

      interface Pet {
        val name: String
        fun speak(times: Int): String
      }

      class Holder {
        fun take(pet: Pet) = Unit
      }
      """.trimIndent(),
    )

    assertTrue(result.generated.contains("pet_bridge_create"), result.generated)
    assertTrue(result.kspWarnings.none { it.contains(code) }, "${result.kspWarnings}")
  }
}
