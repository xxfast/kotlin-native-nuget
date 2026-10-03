package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-147 / ADR-160 on a GENERIC owner. A per-call lambda member the ADR-062 plan owns binds on
 * `Crate<T>` like any planned member (the export reads the `Crate<Any?>` receiver). A member only a
 * legacy route could carry (a `Char` or `T` lambda payload, a stored pair, a `Flow`, a `suspend`)
 * has no route on a generic owner, whose routes all spell the bare receiver, so it is dropped on
 * both halves and named exactly once.
 */
class Tier1GenericOwnerLegacyRouteTest {

  private val source: String = """
    package tier1.genericlegacy

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    interface Feed {
      suspend fun fetch(id: Int): String
    }

    class Crate<T>(val item: T) : Feed {
      fun each(cb: (Int) -> Unit) = cb(1)
      fun count(cb: (Int) -> Int): Int = cb(1)
      fun eachChar(cb: (Char) -> Unit) = cb('a')
      fun withItem(cb: (T) -> Unit) = cb(item)
      fun addBell(listener: (Int) -> Unit) {}
      fun removeBell(listener: (Int) -> Unit) {}
      fun ticks(): Flow<Int> = flowOf(1)
      suspend fun load(): Int = 1
      suspend fun pack(pair: Pair<Int, Int>): Int = pair.first
      fun onBatch(cb: (List<Int>) -> Unit) = cb(listOf(1))
      override suspend fun fetch(id: Int): String = "crate"
    }

    fun makeFeed(): Feed = Crate(1)
  """.trimIndent()

  @Test
  fun `a planned callback member binds on a generic owner`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")
    assertTrue(
      "asStableRef<tier1.genericlegacy.Crate<Any?>>().get().each(" in result.generated,
      "expected the planned export to read the Crate<Any?> receiver; got: ${result.generated}",
    )
    val crate: String = result.generatedCSharp.typeBody("public class Crate<T>")
    listOf("public void Each(Action<int> cb)", "public int Count(Func<int, int> cb)")
      .forEach { member ->
        assertTrue(member in crate, "expected `$member` on Crate<T>; got:\n$crate")
      }
    listOf("each", "count", "fetch").forEach { member ->
      assertFalse(
        result.kspWarnings.any { warning -> "Crate.$member:" in warning },
        "a member that binds must not be reported; got: ${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `every legacy-route member of a generic owner is named exactly once`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")
    val crate: String = result.generatedCSharp.typeBody("public class Crate<T>")
    val declarations: List<String> = crate.lines()
      .filter { line -> line.trimStart().startsWith("public ") }
    listOf("EachChar(", "WithItem(", "AddBell(", "RemoveBell(", "Ticks(", "LoadAsync(")
      .forEach { member ->
        assertFalse(
          declarations.any { line -> member in line },
          "expected no `$member` declared on Crate<T>; got:\n$crate",
        )
      }
    listOf("eachChar", "withItem", "addBell", "removeBell", "ticks", "load").forEach { member ->
      val named: List<String> = result.kspWarnings.filter { warning -> "Crate.$member:" in warning }
      assertEquals(1, named.size, "expected `$member` named once; got: ${result.kspWarnings}")
      assertTrue(
        "generic class" in named.single(),
        "expected the generic-owner reason for `$member`; got: $named",
      )
    }
    // The walk-named refusal keeps its own reason and stays single.
    val onBatch: List<String> = result.kspWarnings.filter { warning -> "Crate.onBatch:" in warning }
    assertEquals(1, onBatch.size, "expected `onBatch` named exactly once; got: $onBatch")
    assertTrue("List<Int>" in onBatch.single(), "expected the payload refusal; got: $onBatch")
    // So does an async member's refused parameter (ADR-114), named by the legacy-route walk.
    val pack: List<String> = result.kspWarnings.filter { warning -> "Crate.pack:" in warning }
    assertEquals(1, pack.size, "expected `pack` named exactly once; got: $pack")
    assertTrue(
      "Flow-returning or suspend member can take" in pack.single(),
      "expected the ADR-114 refusal; got: $pack",
    )
  }

  /**
   * The subscription (ADR-039) pair is a legacy route too. Its Kotlin half used to escape the
   * generic-owner refusal (no lambda, no async return) and emitted `asStableRef<Crate>()`, which
   * does not compile.
   */
  @Test
  fun `a subscription pair on a generic owner is refused on both halves and named`() {
    val result = Tier1Harness.run(
      """
      package tier1.genericsubscription

      interface Purr { fun onPurr(volume: Int) }

      class Crate<T>(val item: T) {
        fun addPurr(listener: Purr) {}
        fun removePurr(listener: Purr) {}
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")
    assertFalse("crate_addPurr" in result.generated, "expected no Kotlin export for addPurr")
    val crate: String = result.generatedCSharp.typeBody("public class Crate<T>")
    assertFalse(
      crate.lines().any { line -> line.trimStart().startsWith("public ") && "AddPurr(" in line },
      "expected no AddPurr on Crate<T>; got:\n$crate",
    )
    listOf("addPurr", "removePurr").forEach { member ->
      val named: List<String> = result.kspWarnings.filter { warning -> "Crate.$member:" in warning }
      assertEquals(1, named.size, "expected `$member` named once; got: ${result.kspWarnings}")
    }
  }

  /** The body of a top-level (4-space) C# type, from its header to its own closing brace. */
  private fun String.typeBody(header: String): String {
    val lines: List<String> = lines()
    val start: Int = lines.indexOfFirst { it.startsWith("    $header") }
    if (start < 0) return ""
    val end: Int = (start until lines.size).first { lines[it] == "    }" }
    return lines.subList(start, end + 1).joinToString("\n")
  }
}
