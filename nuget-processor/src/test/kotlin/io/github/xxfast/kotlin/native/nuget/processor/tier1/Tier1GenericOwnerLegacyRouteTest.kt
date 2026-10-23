package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-147 / ADR-160 on a GENERIC owner. A per-call lambda member the ADR-062 plan owns binds on
 * `Crate<T>` like any planned member (the export reads the `Crate<Any?>` receiver). A member only a
 * legacy route could carry (a `Char` or `T` lambda payload, a stored pair) has no route on a generic
 * owner, whose routes spell the bare receiver, so it is dropped on both halves and named exactly
 * once. The `Flow` (ADR-215) and `suspend` (ADR-219) routes read the erased receiver and bind.
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
    // ADR-215 lane A: the owner's `T` in a lambda rides the plan callback too.
    // ADR-215 lane B: a Flow member reads the erased receiver on the Flow route too.
    listOf(
      "public void Each(Action<int> cb)", "public int Count(Func<int, int> cb)",
      "public void WithItem(Action<T> cb)", "public KotlinFlow<int> Ticks()",
      // ADR-219: and a suspend member on the suspend route.
      "public Task<int> LoadAsync(CancellationToken cancellationToken = default)",
    )
      .forEach { member ->
        assertTrue(member in crate, "expected `$member` on Crate<T>; got:\n$crate")
      }
    listOf("each", "count", "withItem", "fetch", "ticks", "load").forEach { member ->
      assertFalse(
        result.kspWarnings.any { warning -> "Crate.$member:" in warning },
        "a member that binds must not be reported; got: ${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `the callback members of a generic owner bind and the refused async one is named once`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.compiledClean, "expected compilable Kotlin; got: ${result.compileErrors}")
    val crate: String = result.generatedCSharp.typeBody("public class Crate<T>")
    // ADR-239: a `Char` and a `List` payload ride the plan's boxed wire, and a stored pair binds
    // over the erased receiver on the managed-listener route.
    listOf(
      "public void EachChar(Action<char> cb)", "public IDisposable AddBell(Action<int> listener)",
      "OnBatch(Action<IReadOnlyList<int>> cb)",
    ).forEach { member ->
      assertTrue(member in crate, "expected `$member` on Crate<T>; got:\n$crate")
    }
    listOf("eachChar", "addBell", "removeBell", "onBatch").forEach { member ->
      assertFalse(
        result.kspWarnings.any { warning -> "Crate.$member:" in warning },
        "a member that binds must not be reported; got: ${result.kspWarnings}",
      )
    }
    // An async member's refused parameter (ADR-114) is named by the legacy-route walk.
    val pack: List<String> = result.kspWarnings.filter { warning -> "Crate.pack:" in warning }
    assertEquals(1, pack.size, "expected `pack` named exactly once; got: $pack")
    assertTrue(
      "Flow-returning or suspend member can take" in pack.single(),
      "expected the ADR-114 refusal; got: $pack",
    )
  }

  /**
   * The subscription (ADR-039) pair on a generic owner. Its Kotlin half once emitted
   * `asStableRef<Crate>()`, which does not compile, so it was refused; ADR-239 binds it over the
   * erased receiver (`asStableRef<Crate<Any?>>()`), as the owner's lambda pairs.
   */
  @Test
  fun `a subscription pair on a generic owner binds over the erased receiver`() {
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
    assertTrue("asStableRef<tier1.genericsubscription.Crate<Any?>>()" in result.generated)
    val crate: String = result.generatedCSharp.typeBody("public class Crate<T>")
    assertTrue("public IDisposable AddPurr(" in crate, "expected AddPurr on Crate<T>; got:\n$crate")
    assertTrue("nameof(Crate<T>)" in crate, "the disposed check names the generic owner")
    assertTrue(
      result.kspWarnings.none { warning -> "Crate." in warning },
      "a pair that binds must not be reported; got: ${result.kspWarnings}",
    )
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
