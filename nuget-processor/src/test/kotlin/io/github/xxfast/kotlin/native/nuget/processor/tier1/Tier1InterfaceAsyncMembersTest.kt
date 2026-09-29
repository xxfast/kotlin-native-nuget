package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-174: an interface's `suspend`/`Flow`/`StateFlow` members are declared on `I<Name>` only for a
 * reachable, non-generic, non-sealed interface. The two exclusions are cells here, beside the
 * generator shape of the positive case, top-level and nested inside a `class` or `object` owner
 * (the `...Native` carrier nests with the interface). The positive shape is proven end to end by
 * `InterfaceAsyncMemberTests` and the LeakTests rows over `test-library`'s `catfeed` fixture
 * (`IFeed` top-level, `Pantry.IBowl` nested).
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
  fun `a generic implementer forwards interface async members through the wrapper carrier`() {
    // Ruling 4, at generation time: the export compiles as `asStableRef<Feed>()` (the harness
    // compiles the generated Kotlin), and `Crate<T>` implements `IFeed` explicitly over
    // `FeedNative`.
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
    // `get(): T` is the case the type-parameter carve-out would otherwise have declared as
    // `T Get()`.
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

  /** The body of a top-level (4-space) C# type, from its header to its own closing brace. */
  private fun String.typeBody(header: String): String {
    val lines: List<String> = lines()
    val start: Int = lines.indexOfFirst { it.startsWith("    $header") }
    if (start < 0) return ""
    val end: Int = (start until lines.size).first { lines[it] == "    }" }
    return lines.subList(start, end + 1).joinToString("\n")
  }

  /** The body of a nested (8-space) C# type, from its header to its own closing brace. */
  private fun String.nestedTypeBody(header: String): String {
    val lines: List<String> = lines()
    val start: Int = lines.indexOfFirst { it.startsWith("        $header") }
    if (start < 0) return ""
    val end: Int = (start until lines.size).first { lines[it] == "        }" }
    return lines.subList(start, end + 1).joinToString("\n")
  }

  @Test
  fun `a nested interface nests its async carrier in its owner and generics qualify it`() {
    // The `...Native` carrier is emitted beside the backing wrapper, which ADR-133 nests beside
    // its interface, so for a nested interface it lands in the owner's body. `Crate<T>` is the
    // one path that reads the carrier from outside the wrapper (Rule 7), so it must spell it
    // `Shelf.FeedNative`. Both a `class` owner and an `object` owner are covered. Factories are
    // `open()`/`tap()`: a factory PascalCasing onto the nested wrapper's name is CS0102.
    val result = Tier1Harness.run(
      """
      package tier1.nestedasync

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf
      import kotlinx.coroutines.flow.map

      class Shelf {
        interface Feed {
          suspend fun fetch(id: Int): String
          fun ticks(): Flow<Int>
          val level: StateFlow<Int>
          fun doubled(): Flow<Int> = ticks().map { it * 2 }
        }
        fun open(): Feed = Crate(1)
      }

      object Registry {
        interface Source {
          suspend fun pull(): Int
          val state: StateFlow<Int>
        }
        fun tap(): Source = Tap()
      }

      class Crate<T>(val item: T) : Shelf.Feed {
        override suspend fun fetch(id: Int): String = "crate"
        override fun ticks(): Flow<Int> = flowOf(4)
        override val level: StateFlow<Int> = MutableStateFlow(3)
      }

      class Tap : Registry.Source {
        override suspend fun pull(): Int = 7
        override val state: StateFlow<Int> = MutableStateFlow(1)
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    val shelf: String = cs.typeBody("public class Shelf ")
    val registry: String = cs.typeBody("public static class Registry")
    assertTrue(shelf.isNotEmpty(), "expected Shelf; generatedCSharp=$cs")
    assertTrue(registry.isNotEmpty(), "expected Registry; generatedCSharp=$cs")
    listOf(
      "        public interface IFeed : IDisposable, IAsyncDisposable",
      "        internal static class FeedNative",
      "        public sealed class Feed : IFeed,",
    ).forEach { assertTrue(shelf.contains(it), "expected `$it` inside Shelf; got:\n$shelf") }
    listOf(
      "        public interface ISource : IDisposable, IAsyncDisposable",
      "        internal static class SourceNative",
      "        public sealed class Source : ISource,",
    ).forEach { member ->
      assertTrue(registry.contains(member), "expected `$member` inside Registry; got:\n$registry")
    }
    listOf("    internal static class FeedNative", "    internal static class SourceNative")
      .forEach { root ->
        assertFalse(cs.lines().any { it == root }, "carrier leaked to namespace root: `$root`")
      }

    // Tier 1 never compiles the C#, so pin the declarations on the nested interfaces too.
    val feed: String = shelf.nestedTypeBody("public interface IFeed")
    listOf(
      "Task<string> FetchAsync(int id, CancellationToken cancellationToken = default);",
      "KotlinFlow<int> Ticks();",
      "KotlinStateFlow<int> Level { get; }",
      "KotlinFlow<int> Doubled()",
    ).forEach { assertTrue(feed.contains(it), "expected `$it` on Shelf.IFeed; got:\n$feed") }
    val source: String = registry.nestedTypeBody("public interface ISource")
    listOf(
      "Task<int> PullAsync(CancellationToken cancellationToken = default);",
      "KotlinStateFlow<int> State { get; }",
    ).forEach { member ->
      assertTrue(source.contains(member), "expected `$member` on Registry.ISource; got:\n$source")
    }

    val crate: String = cs.typeBody("public class Crate<T>")
    listOf(
      "Task<string> Shelf.IFeed.FetchAsync(int id, CancellationToken cancellationToken)",
      "Shelf.FeedNative.Native_FetchAsync(_handle, GetOrCreateScope(), id,",
      "KotlinFlow<int> Shelf.IFeed.Ticks()",
      "Shelf.FeedNative.Native_TicksCollect(_handle, GetOrCreateScope(),",
      "KotlinStateFlow<int> Shelf.IFeed.Level",
      "Shelf.FeedNative.Native_GetLevelValue(_handle)",
      "KotlinFlow<int> Shelf.IFeed.Doubled()",
    ).forEach { assertTrue(crate.contains(it), "expected `$it` on Crate<T>; got:\n$crate") }
  }

  @Test
  fun `a nested interface sharing a top-level interface's name binds each to its own carrier`() {
    // Top-level `Feed` and nested `Shelf.Feed` both emit a `FeedNative`. A generic implementer of
    // both must reach each through its own spelling, and a nested implementer of the TOP-LEVEL
    // interface must not write a bare `FeedNative` inside `Shelf`, where it would bind to
    // `Shelf.FeedNative`.
    val result = Tier1Harness.run(
      """
      package tier1.nestedasyncshadow

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      interface Feed {
        suspend fun fetch(id: Int): String
      }

      class Shelf {
        interface Feed {
          suspend fun pull(id: Int): String
          fun ticks(): Flow<Int>
        }
        class Box : tier1.nestedasyncshadow.Feed {
          override suspend fun fetch(id: Int): String = "box"
        }
        fun open(): Shelf.Feed = Crate(1)
      }

      class Crate<T>(val item: T) : Feed, Shelf.Feed {
        override suspend fun fetch(id: Int): String = "crate"
        override suspend fun pull(id: Int): String = "pull"
        override fun ticks(): Flow<Int> = flowOf(1)
      }

      fun makeFeed(): Feed = Crate(1)
      fun makeBox(): Shelf.Box = Shelf.Box()
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    val shelf: String = cs.typeBody("public class Shelf ")
    assertTrue(shelf.isNotEmpty(), "expected Shelf; generatedCSharp=$cs")
    assertTrue(
      shelf.contains("        internal static class FeedNative"),
      "expected the nested carrier inside Shelf; got:\n$shelf",
    )
    assertTrue(
      cs.lines().any { it == "    internal static class FeedNative" },
      "expected the top-level carrier at the namespace root; generatedCSharp=$cs",
    )

    val crate: String = cs.typeBody("public class Crate<T>")
    listOf(
      "Task<string> IFeed.FetchAsync(int id, CancellationToken cancellationToken)",
      " FeedNative.Native_FetchAsync(_handle, GetOrCreateScope(), id,",
      "Task<string> Shelf.IFeed.PullAsync(int id, CancellationToken cancellationToken)",
      "Shelf.FeedNative.Native_PullAsync(_handle, GetOrCreateScope(), id,",
      "Shelf.FeedNative.Native_TicksCollect(_handle, GetOrCreateScope(),",
    ).forEach { assertTrue(crate.contains(it), "expected `$it` on Crate<T>; got:\n$crate") }
    assertFalse(
      crate.contains("Shelf.FeedNative.Native_FetchAsync"),
      "top-level IFeed.FetchAsync bound to the nested carrier; got:\n$crate",
    )

    // The nested implementer of the top-level interface names it root-qualified and projects
    // `FetchAsync` itself, so no bare `FeedNative` reference is written inside Shelf's body.
    val box: String = shelf.nestedTypeBody("public class Box")
    assertTrue(box.isNotEmpty(), "expected Shelf.Box; got:\n$shelf")
    assertTrue(box.contains("global::"), "expected a root-qualified IFeed on Shelf.Box; got:\n$box")
    assertFalse(
      Regex("""(?<![.\w])FeedNative\.""").containsMatchIn(box),
      "expected no bare FeedNative inside Shelf.Box; got:\n$box",
    )
  }
}
