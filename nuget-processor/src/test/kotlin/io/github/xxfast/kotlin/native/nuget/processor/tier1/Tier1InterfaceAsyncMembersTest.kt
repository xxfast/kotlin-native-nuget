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

  @Test
  fun `an async member inherited by the only reachable interface is declared on its super`() {
    // ADR-174 amendment, shape A: only `Manger` is reachable. The async members are declared on
    // `ITrough` (made reachable because it carries them) and inherited through `IManger`, as the
    // sync route does. The generic implementer `Nosebag<T>` needs explicit `ITrough` forwards and
    // a `DisposeAsync`, or `IManger : IAsyncDisposable` is CS0535 in the consumer build.
    val result = Tier1Harness.run(
      """
      package tier1.asyncinherited

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      interface Trough {
        suspend fun fetch(id: Int): String
        fun ticks(): Flow<Int>
        val level: StateFlow<Int>
        fun name(): String
      }

      interface Manger : Trough {
        fun own(): Int
      }

      class Stall : Manger {
        override suspend fun fetch(id: Int): String = "stall-${'$'}id"
        override fun ticks(): Flow<Int> = flowOf(1, 2, 3)
        override val level: StateFlow<Int> = MutableStateFlow(1)
        override fun name(): String = "stall"
        override fun own(): Int = 11
      }

      class Nosebag<T>(val item: T) : Manger {
        override suspend fun fetch(id: Int): String = "nosebag-${'$'}id"
        override fun ticks(): Flow<Int> = flowOf(7, 8)
        override val level: StateFlow<Int> = MutableStateFlow(4)
        override fun name(): String = "nosebag"
        override fun own(): Int = 22
      }

      fun makeManger(): Manger = Stall()
      fun makeNosebag(): Manger = Nosebag(1)
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    // Precondition on the generated KOTLIN only; the C# is judged by its text below.
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val trough: String = result.generatedCSharp.interfaceBlock("ITrough")
    val manger: String = result.generatedCSharp.interfaceBlock("IManger")
    assertTrue(trough.isNotEmpty() && manger.isNotEmpty(),
      "expected ITrough and IManger; generatedCSharp=${result.generatedCSharp}")

    val asyncSignatures: List<String> = listOf(
      "Task<string> FetchAsync(int id, CancellationToken cancellationToken = default);",
      "KotlinFlow<int> Ticks();",
      "KotlinStateFlow<int> Level",
    )
    assertTrue(trough.contains("IAsyncDisposable"),
      "expected ITrough : IAsyncDisposable; got:\n$trough")
    asyncSignatures.forEach { expected ->
      assertTrue(trough.contains(expected), "expected `$expected` on ITrough; got:\n$trough")
      assertFalse(manger.contains(expected), "expected no `$expected` on IManger; got:\n$manger")
    }
    assertTrue(manger.contains("IManger : ITrough"),
      "expected IManger to inherit ITrough; got:\n$manger")

    val nosebag: String = result.generatedCSharp.substringAfter("public class Nosebag<T>", "")
      .substringBefore("\n    }\n")
    assertTrue(nosebag.isNotEmpty(),
      "expected Nosebag<T>; generatedCSharp=${result.generatedCSharp}")
    listOf(
      "IAsyncDisposable",
      "public ValueTask DisposeAsync()",
      "Task<string> ITrough.FetchAsync(int id, CancellationToken cancellationToken)",
      "KotlinFlow<int> ITrough.Ticks()",
      "KotlinStateFlow<int> ITrough.Level",
    ).forEach { expected ->
      assertTrue(nosebag.contains(expected), "expected `$expected` on Nosebag<T>; got:\n$nosebag")
    }
  }

  @Test
  fun `a restated async override on a reachable derived interface is declared only on its super`() {
    // ADR-174 amendment, shape E: both interfaces reachable, `Hayrack` restates `fetch` as an
    // identical override. The sync route classifies that IDENTICAL_OVERRIDE and omits it; the
    // async route must too, or `IHayrack.FetchAsync` hides `IChute.FetchAsync` (CS0108, an error
    // under TreatWarningsAsErrors).
    val result = Tier1Harness.run(
      """
      package tier1.asyncrestated

      interface Chute {
        suspend fun fetch(id: Int): String
      }

      interface Hayrack : Chute {
        override suspend fun fetch(id: Int): String
        fun own(): Int
      }

      class Hayloft : Hayrack {
        override suspend fun fetch(id: Int): String = "hayloft-${'$'}id"
        override fun own(): Int = 33
      }

      fun makeHayrack(): Hayrack = Hayloft()
      fun makeChute(): Chute = Hayloft()
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val chute: String = result.generatedCSharp.interfaceBlock("IChute")
    val hayrack: String = result.generatedCSharp.interfaceBlock("IHayrack")
    assertTrue(chute.isNotEmpty() && hayrack.isNotEmpty(),
      "expected IChute and IHayrack; generatedCSharp=${result.generatedCSharp}")
    assertTrue(chute.contains("Task<string> FetchAsync("),
      "expected FetchAsync on IChute; got:\n$chute")
    assertFalse(hayrack.contains("FetchAsync("),
      "expected no FetchAsync on IHayrack; got:\n$hayrack")
    assertTrue(hayrack.contains("int Own();"), "expected IHayrack's own member; got:\n$hayrack")
  }

  @Test
  fun `an async member inherited from a generic super is declared on the derived interface`() {
    // ADR-174 amendment: `Base<T>` is generic, so it never carries (ruling 3) and is never
    // promoted. Its async member is declared on `IDerived` instead, the nearest carrying interface,
    // as an unexported super's is. Otherwise `IDerived : IAsyncDisposable` (it counts the inherited
    // member) leaves the generic implementer `Crate<X>` without forwards or `DisposeAsync`: CS0535.
    val result = Tier1Harness.run(
      """
      package tier1.asyncgenericsuper

      interface Base<T> {
        suspend fun fetch(): T
      }

      interface Derived : Base<Int> {
        fun own(): Int
      }

      class Impl : Derived {
        override suspend fun fetch(): Int = 7
        override fun own(): Int = 1
      }

      class Crate<X>(val x: X) : Derived {
        override suspend fun fetch(): Int = 8
        override fun own(): Int = 2
      }

      fun makeDerived(): Derived = Impl()
      fun makeCrate(): Derived = Crate("oats")
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val derived: String = result.generatedCSharp.interfaceBlock("IDerived")
    val base: String = result.generatedCSharp.interfaceBlock("IBase<T>")
    assertTrue(derived.isNotEmpty(), "expected IDerived; generatedCSharp=${result.generatedCSharp}")
    assertTrue(derived.contains("IAsyncDisposable"),
      "expected IDerived : IAsyncDisposable; got:\n$derived")
    assertTrue(
      derived.contains("Task<int> FetchAsync(CancellationToken cancellationToken = default);"),
      "expected FetchAsync on IDerived; got:\n$derived")
    assertFalse(base.contains("FetchAsync("),
      "expected no FetchAsync on the generic IBase<T>; got:\n$base")

    val crate: String = result.generatedCSharp.substringAfter("public class Crate<", "")
      .substringBefore("\n    }\n")
    assertTrue(crate.isNotEmpty(), "expected Crate<X>; generatedCSharp=${result.generatedCSharp}")
    listOf(
      "IAsyncDisposable",
      "public ValueTask DisposeAsync()",
      "Task<int> IDerived.FetchAsync(CancellationToken cancellationToken)",
    ).forEach { expected ->
      assertTrue(crate.contains(expected), "expected `$expected` on Crate<X>; got:\n$crate")
    }
  }

  @Test
  fun `an unexported super's async members are re-homed onto the derived interface`() {
    // ADR-174 amendment: a super outside the export set (here a package outside the root) is
    // dropped with SKIPPED_UNEXPORTED_SUPERTYPE and its members re-homed onto `IShy` (DECLARED
    // placement), async members included, exactly as the sync route does. The generic implementer
    // forwards them under `IShy`.
    val hidden: String = """
      package tier1asynchidden

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.StateFlow

      interface Tagged {
        suspend fun fetch(id: Int): String
        fun ticks(): Flow<Int>
        val level: StateFlow<Int>
        fun retag(t: String): String
      }
    """.trimIndent()
    val fixture: String = """
      package tier1.asyncrehomed

      import tier1asynchidden.Tagged
      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      interface Shy : Tagged { fun hello(): String }

      class ShyImpl : Shy {
        override suspend fun fetch(id: Int): String = "shy-${'$'}id"
        override fun ticks(): Flow<Int> = flowOf(1)
        override val level: StateFlow<Int> = MutableStateFlow(1)
        override fun retag(t: String): String = t
        override fun hello(): String = "hi"
      }

      class Bag<T>(val t: T) : Shy {
        override suspend fun fetch(id: Int): String = "bag-${'$'}id"
        override fun ticks(): Flow<Int> = flowOf(2)
        override val level: StateFlow<Int> = MutableStateFlow(2)
        override fun retag(t: String): String = t
        override fun hello(): String = "hey"
      }

      fun adoptShy(): Shy = ShyImpl()
      fun adoptBag(): Shy = Bag(1)
    """.trimIndent()
    val result = Tier1Harness.run(
      mapOf("Hidden.kt" to hidden, "Rehomed.kt" to fixture),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.asyncrehomed"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.any { "SKIPPED_UNEXPORTED_SUPERTYPE" in it && "Shy : Tagged" in it },
      "expected the dropped super named; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(result.generatedCSharp.contains("ITagged"), "no ITagged may be named")
    val shy: String = result.generatedCSharp.interfaceBlock("IShy")
    listOf(
      "IAsyncDisposable",
      "Task<string> FetchAsync(int id, CancellationToken cancellationToken = default);",
      "KotlinFlow<int> Ticks();",
      "KotlinStateFlow<int> Level",
      "string Retag(string t);",
    ).forEach { expected ->
      assertTrue(shy.contains(expected), "expected `$expected` on IShy; got:\n$shy")
    }
    val bag: String = result.generatedCSharp.substringAfter("public class Bag<", "")
      .substringBefore("\n    }\n")
    listOf(
      "public ValueTask DisposeAsync()",
      "Task<string> IShy.FetchAsync(int id, CancellationToken cancellationToken)",
      "KotlinFlow<int> IShy.Ticks()",
      "KotlinStateFlow<int> IShy.Level",
    ).forEach { expected ->
      assertTrue(bag.contains(expected), "expected `$expected` on Bag<T>; got:\n$bag")
    }
  }

  @Test
  fun `an async member two carrying supers both declare is redeclared with new`() {
    // ADR-174 amendment: `Feeder : Scoop, Ladle` with both supers declaring `fetch`. Both supers
    // are promoted (they carry async members); inheriting `FetchAsync` from both would be CS0121 on
    // `feeder.FetchAsync(...)`, so `IFeeder` redeclares it with `new`, the sync DIAMOND_OVERRIDE.
    val result = Tier1Harness.run(
      """
      package tier1.asyncdiamond

      interface Scoop {
        suspend fun fetch(id: Int): String
      }

      interface Ladle {
        suspend fun fetch(id: Int): String
      }

      interface Feeder : Scoop, Ladle {
        fun own(): Int
      }

      class Trug : Feeder {
        override suspend fun fetch(id: Int): String = "trug-${'$'}id"
        override fun own(): Int = 3
      }

      fun makeFeeder(): Feeder = Trug()
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val signature =
      "Task<string> FetchAsync(int id, CancellationToken cancellationToken = default);"
    val scoop: String = result.generatedCSharp.interfaceBlock("IScoop")
    val ladle: String = result.generatedCSharp.interfaceBlock("ILadle")
    val feeder: String = result.generatedCSharp.interfaceBlock("IFeeder")
    assertTrue(scoop.contains(signature), "expected FetchAsync on IScoop; got:\n$scoop")
    assertTrue(ladle.contains(signature), "expected FetchAsync on ILadle; got:\n$ladle")
    assertTrue(feeder.contains("IFeeder : IScoop, ILadle"),
      "expected both supers kept; got:\n$feeder")
    assertTrue(feeder.contains("new $signature"),
      "expected `new` FetchAsync on IFeeder; got:\n$feeder")
    assertTrue(feeder.contains("int Own();"), "expected IFeeder's own member; got:\n$feeder")
  }
}
