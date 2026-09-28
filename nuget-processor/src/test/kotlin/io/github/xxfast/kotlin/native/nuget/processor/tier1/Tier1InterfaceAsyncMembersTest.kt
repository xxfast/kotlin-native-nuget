package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-174: an interface's `suspend`/`Flow`/`StateFlow` members are declared on `I<Name>` only for a
 * reachable, non-generic, non-sealed interface. The two exclusions are the cells here; the positive
 * shape (`IFeed`, `Feed`, `Crate<T>`'s explicit implementations) is proven end to end by
 * `InterfaceAsyncMemberTests` and the LeakTests rows over `test-library`'s `catfeed` fixture.
 */
class Tier1InterfaceAsyncMembersTest {

  /** The generated `public interface <name>` block, up to its closing brace. */
  private fun String.interfaceBlock(name: String): String {
    val lines: List<String> = lines()
    val start: Int = lines.indexOfFirst { it.trim().startsWith("public interface $name") }
    if (start < 0) return ""
    val end: Int = (start until lines.size).first { lines[it] == "    }" }
    return lines.subList(start, end + 1).joinToString("\n")
  }

  @Test
  fun `a generic implementer forwards the interface's async members through the wrapper's carrier`() {
    // Ruling 4, at generation time: the export compiles as `asStableRef<Feed>()` (the harness
    // compiles the generated Kotlin), and `Crate<T>` implements `IFeed` explicitly over `FeedNative`.
    val result = Tier1Harness.run(
      """
      package tier1.asyncforward

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      interface Feed {
        suspend fun fetch(id: Int): String
        fun ticks(): Flow<Int>
        val level: StateFlow<Int>
      }

      class Crate<T>(val item: T) : Feed {
        override suspend fun fetch(id: Int): String = "crate-${'$'}id"
        override fun ticks(): Flow<Int> = flowOf(4, 5)
        override val level: StateFlow<Int> = MutableStateFlow(2)
      }

      fun makeFeed(): Feed = Crate(1)
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      result.generated.contains("asStableRef<tier1.asyncforward.Feed>()"),
      "expected the interface-owned receiver; generated=${result.generated}",
    )
    listOf(
      "public interface IFeed : IDisposable, IAsyncDisposable",
      "Task<string> FetchAsync(int id, CancellationToken cancellationToken = default);",
      "internal static class FeedNative",
      "public sealed class Feed : IFeed, IDisposable, IAsyncDisposable, INugetHandle",
      "Task<string> IFeed.FetchAsync(int id, CancellationToken cancellationToken)",
      "FeedNative.Native_FetchAsync(_handle, GetOrCreateScope(), id,",
      "KotlinFlow<int> IFeed.Ticks()",
      "KotlinStateFlow<int> IFeed.Level",
      "FeedNative.Native_GetLevelValue(_handle)",
    ).forEach { expected ->
      assertTrue(
        result.generatedCSharp.contains(expected),
        "expected `$expected`; generatedCSharp=${result.generatedCSharp}",
      )
    }
    // ADR-147's refusal of Crate<T>'s OWN async members stands: nothing public was added.
    val crate: String = result.generatedCSharp.substringAfter("public class Crate<T>")
      .substringBefore("\n    }\n")
    listOf("public KotlinFlow<int> Ticks()", "public Task<string> FetchAsync(").forEach { absent ->
      assertFalse(crate.contains(absent), "expected no `$absent` on Crate<T>; got:\n$crate")
    }
  }

  @Test
  fun `an interface async member with a default parameter declares the implementer's signature`() {
    // ADR-164 on the legacy routes reads its default flags off the catalog's SUSPEND/FLOW skip
    // entry. The interface's entries must carry them too, or `IFeed` declares `FetchAsync(int id)`
    // while `Rss` renders the widened `FetchAsync(int? id = null)`, and `Rss : IFeed` is CS0535.
    val result = Tier1Harness.run(
      """
      package tier1.asyncdefaults

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      interface Feed {
        suspend fun fetch(id: Int = 1): String
        fun ticks(from: Int = 0): Flow<Int>
      }

      class Rss : Feed {
        override suspend fun fetch(id: Int): String = "rss-${'$'}id"
        override fun ticks(from: Int): Flow<Int> = flowOf(from)
      }

      fun makeFeed(): Feed = Rss()
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val iface: String = result.generatedCSharp.interfaceBlock("IFeed")
    val rss: String = result.generatedCSharp.substringAfter("public class Rss")
      .substringBefore("\n    }\n")
    fun String.parametersOf(member: String): String? =
      lines().firstOrNull { it.contains(" $member(") }
        ?.substringAfter("$member(")?.substringBeforeLast(")")
    listOf("FetchAsync", "Ticks").forEach { member ->
      val declared: String? = iface.parametersOf(member)
      val implemented: String? = rss.parametersOf(member)
      assertTrue(declared != null && declared == implemented,
        "expected IFeed.$member($declared) to match Rss.$member($implemented);\n$iface\n$rss")
    }
  }

  @Test
  fun `a sealed interface with a suspend member declares no Task member on its interface`() {
    // Ruling 2: never an interface type, so never reachable, so `IMixed` stays as it was. `Mixed`
    // is ineligible (its arm has a second superclass), which is the shape that keeps an `IMixed`.
    val result = Tier1Harness.run(
      """
      package tier1.asyncsealed

      open class Rhythm {
        fun tempo(): String = "steady"
      }

      sealed interface Mixed {
        suspend fun ping(): Int
        class Odd : Rhythm(), Mixed {
          override suspend fun ping(): Int = 1
        }
      }

      class Desk {
        fun mixed(): Mixed = Mixed.Odd()
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val block: String = result.generatedCSharp.interfaceBlock("IMixed")
    assertTrue(block.isNotEmpty(), "expected IMixed; generatedCSharp=${result.generatedCSharp}")
    assertFalse(block.contains("Task<"), "expected no async member on IMixed; got:\n$block")
    assertFalse(block.contains("IAsyncDisposable"), "expected no IAsyncDisposable; got:\n$block")
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_GENERIC_INTERFACE_ASYNC_MEMBER.name)
      },
      "expected a sealed interface to be excluded silently; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a generic interface's async members are a named skip and stay off the interface`() {
    // Ruling 3: `asStableRef<Feed<...>>()` has no type argument to spell (the ADR-147 reason).
    // `get(): T` is the case the type-parameter carve-out would otherwise have declared as `T Get()`.
    val result = Tier1Harness.run(
      """
      package tier1.asyncgeneric

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      interface Feed<T> {
        suspend fun get(): T
        suspend fun count(): Int
        fun ticks(): Flow<Int>
        fun name(): String
      }

      class Bowl : Feed<Int> {
        override suspend fun get(): Int = 1
        override suspend fun count(): Int = 2
        override fun ticks(): Flow<Int> = flowOf(1)
        override fun name(): String = "bowl"
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val block: String = result.generatedCSharp.interfaceBlock("IFeed<T>")
    assertTrue(block.isNotEmpty(), "expected IFeed<T>; generatedCSharp=${result.generatedCSharp}")
    listOf("Task<", "KotlinFlow<", " Get(", "IAsyncDisposable").forEach { absent ->
      assertFalse(block.contains(absent), "expected no `$absent` on IFeed<T>; got:\n$block")
    }
    listOf("get", "count", "ticks").forEach { member ->
      assertTrue(
        result.kspWarnings.any {
          it.contains(ForwardDiagnosticKind.SKIPPED_GENERIC_INTERFACE_ASYNC_MEMBER.name) &&
              it.contains("tier1.asyncgeneric.Feed.$member")
        },
        "expected `$member` named; kspWarnings=${result.kspWarnings}",
      )
    }
    assertFalse(
      result.kspWarnings.any { it.contains("tier1.asyncgeneric.Feed.name") },
      "expected the sync member not to be named; kspWarnings=${result.kspWarnings}",
    )
  }
}
