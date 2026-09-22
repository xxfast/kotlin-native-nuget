package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-159: one coroutine scope per instance, owned by the **first class in the kept chain that
 * projects an async member**, wherever in the chain that is. Everything below the owner reuses that
 * scope and inherits `DisposeAsync`.
 *
 * Before this, the scope emission fired only on a base-less class while the flag that drives
 * `Dispose()` came off a raw `getAllFunctions()` scan, so every hierarchy shape but the root-owner
 * one was broken, each in its own way (all reproduced by spike against the ADR-138 gate's csproj):
 * - derived declares async, base has none: CS0103 x3 (`GetOrCreateScope`, `_scopeHandle` twice)
 * - base declares async, derived none: CS0108, a second `DisposeAsync` hiding the base's
 * - both declare: the above plus CS0122, the base's `private GetOrCreateScope()`
 * - abstract owner: CS0535, `IAsyncDisposable` advertised and never implemented
 * - `override suspend fun`: CS0108 on a re-projected `FillAsync`
 * - the Flow twin of the base-owner shape: **compiles**, and drops the scope block from the
 *   subclass's `override Dispose()`, so a sync dispose leaks the base's scope
 *
 * Text cells, because the shapes are compile failures of the *generated* C#, which no in-process
 * Kotlin compile can see. The runtime halves are `SubclassAsyncTests.cs` and `LiveHandleTests`
 * rows 13/13a/13b.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without coroutines on
 * the KSP libraries path the suspend members resolve to nothing and every assertion here passes for
 * the wrong reason.
 */
class Tier1SubclassScopeOwnerTest {

  private val fixture: String = """
    package tier1.scopeowner

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    // The derived class projects the chain's first async member: it owns the scope, the base gains
    // nothing.
    open class SunShelf(val spot: String) {
      fun describe(): String = "${'$'}spot is warm"
    }

    class NapLounge(spot: String) : SunShelf(spot) {
      suspend fun rest(cat: String): String = "${'$'}cat napped on ${'$'}spot"
      fun nappers(): Flow<String> = flowOf("Oreo", "Mylo")
    }

    // The base owns the scope on both routes at once; the subclass declares no async member.
    open class WindowSeat(val height: Int) {
      suspend fun settle(cat: String): String = "${'$'}cat settled at ${'$'}height"
      fun watchers(): Flow<String> = flowOf("Oreo", "Mylo")
    }

    class PaddedWindowSeat(height: Int) : WindowSeat(height) {
      fun cushion(): String = "${'$'}height cushioned"
    }

    // Both levels declare, and the derived one overrides the base's member.
    open class Feeder(val bowls: Int) {
      open suspend fun fill(): String = "${'$'}bowls bowls filled"
    }

    class TimedFeeder(bowls: Int, val hour: Int) : Feeder(bowls) {
      override suspend fun fill(): String = "${'$'}bowls bowls filled at ${'$'}hour"
      suspend fun schedule(): Int = hour
    }

    // An abstract owner with a concrete subclass.
    abstract class Brusher {
      suspend fun groom(cat: String): String = "${'$'}cat groomed with ${'$'}{tool()}"
      abstract fun tool(): String
    }

    class MittBrusher : Brusher() {
      override fun tool(): String = "a brush"
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "ScopeOwnerSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /** The whole point: the generated Kotlin still compiles, and KSP names no error. */
  @Test
  fun `every hierarchy shape generates without a KSP error`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the chain fixture to generate and compile; got: " +
          "${result.compileErrors} ${result.kspErrors}",
    )
  }

  /**
   * Shape: derived owner. The scope moves to the level that projects the first async member, so the
   * three declarations that used to be pinned to a base-less class have to appear on a class that
   * has a base -- and `SunShelf` must gain nothing at all.
   */
  @Test
  fun `a derived owner declares the scope and the base gains nothing`() {
    val result = run()
    val cs: String = result.generatedCSharp

    assertContains(cs, "public class NapLounge : SunShelf, IAsyncDisposable")
    assertEquals(
      1,
      Regex("class NapLounge[^\\n]*\\n(?:.*\\n)*?\\s+internal IntPtr _scopeHandle;")
        .findAll(cs).count(),
      "expected NapLounge to declare the scope field; got: ${classBlock(cs, "NapLounge")}",
    )
    assertTrue(
      classBlock(cs, "NapLounge").contains("internal IntPtr GetOrCreateScope()"),
      "expected the derived owner to declare the scope factory; got: " +
          classBlock(cs, "NapLounge"),
    )
    assertTrue(
      classBlock(cs, "NapLounge").contains("public ValueTask DisposeAsync()"),
      "expected the derived owner to declare the drain; got: ${classBlock(cs, "NapLounge")}",
    )

    val base: String = classBlock(cs, "SunShelf")
    assertFalse(
      base.contains("IAsyncDisposable") || base.contains("_scopeHandle") ||
          base.contains("DisposeAsync"),
      "expected SunShelf to own no scope at all; got: $base",
    )
  }

  /**
   * Shape: base owner. One `IAsyncDisposable`, one `_scopeHandle`, one `DisposeAsync` in the chain,
   * all on `WindowSeat`. The subclass's `override Dispose()` still has to cancel and dispose the
   * inherited scope: a derived `Dispose` replaces the base body rather than chaining into it, so
   * dropping the block there is the silent leak the Flow twin of this shape ships today.
   */
  @Test
  fun `a subclass under a base owner declares no second scope and still cleans up`() {
    val result = run()
    val sub: String = classBlock(result.generatedCSharp, "PaddedWindowSeat")

    assertContains(result.generatedCSharp, "public class PaddedWindowSeat : WindowSeat\n")
    assertFalse(
      sub.contains("_scopeHandle;"),
      "expected no second scope field on the subclass (CS0108); got: $sub",
    )
    assertFalse(
      sub.contains("DisposeAsync()"),
      "expected the subclass to inherit DisposeAsync rather than hide it (CS0108); got: $sub",
    )
    assertFalse(
      sub.contains("GetOrCreateScope()"),
      "expected no second scope factory on the subclass; got: $sub",
    )
    assertTrue(
      sub.contains("NugetScopeNative.Cancel(scopeHandle);") &&
          sub.contains("NugetScopeNative.Dispose(scopeHandle);"),
      "expected the subclass's override Dispose() to cancel and dispose the inherited scope, " +
          "which is the leak the Flow twin of this shape ships today; got: $sub",
    )
  }

  /**
   * Shape: both levels declare. The owner's scope factory has to be reachable from the
   * subclass's own async bodies, which `private` was not (CS0122). `internal` matches
   * `_scopeHandle`, which was already visible.
   */
  @Test
  fun `the scope factory is visible to a subclass that calls it`() {
    val result = run()

    assertFalse(
      result.generatedCSharp.contains("private IntPtr GetOrCreateScope()"),
      "expected no private scope factory: a subclass's async body calls it unqualified (CS0122)",
    )
    assertTrue(
      classBlock(result.generatedCSharp, "TimedFeeder").contains("GetOrCreateScope()"),
      "expected TimedFeeder's own async body to call the inherited factory; got: " +
          classBlock(result.generatedCSharp, "TimedFeeder"),
    )
  }

  /**
   * Rule 5. An `override suspend fun` is not re-projected: the base's export calls the member on
   * `asStableRef<Feeder>().get()`, so Kotlin's own dynamic dispatch reaches `TimedFeeder.fill`. A
   * second `FillAsync` on the subclass is CS0108 and buys nothing.
   *
   * Both halves, because the stray Kotlin export is invisible to `ForwardAbiContract` (it filters
   * Kotlin exports down to the C# import set) and would ship as dead weight forever.
   */
  @Test
  fun `an overridden suspend member is projected once, on the base`() {
    val result = run()

    assertFalse(
      result.generated.contains("timedfeeder_fill_async"),
      "expected no stray Kotlin export for the override; got: " +
          result.generated.lines().filter { it.contains("timedfeeder") }.map(String::trim),
    )
    assertContains(result.generated, "@CName(\"library_tier1_scopeowner__feeder_fill_async\")")
    assertContains(
      result.generated,
      "@CName(\"library_tier1_scopeowner__timedfeeder_schedule_async\")",
    )

    assertEquals(
      0,
      Regex("EntryPoint = \"library_tier1_scopeowner__timedfeeder_fill_async\"")
        .findAll(result.generatedCSharp).count(),
      "expected no C# import for the override either",
    )
    assertEquals(
      1,
      Regex("public Task<string> FillAsync\\(").findAll(result.generatedCSharp).count(),
      "expected exactly one FillAsync in the chain, on Feeder; got: " +
          result.generatedCSharp.lines().filter { it.contains("FillAsync") }.map(String::trim),
    )
  }

  /**
   * Rule 4. `DisposeAsync` follows `Dispose`'s spelling. An abstract owner can only declare the
   * drain (it has no `Native_Dispose` import to call), so each concrete class below it renders the
   * body as an `override`. Without the pair the abstract class advertised `IAsyncDisposable` and
   * implemented nothing (CS0535).
   */
  @Test
  fun `an abstract owner declares DisposeAsync abstractly and the concrete class overrides it`() {
    val result = run()

    val owner: String = classBlock(result.generatedCSharp, "Brusher")
    assertContains(
      result.generatedCSharp,
      "public abstract class Brusher : IDisposable, IAsyncDisposable",
    )
    assertTrue(
      owner.contains("public abstract ValueTask DisposeAsync();"),
      "expected the abstract owner to declare the drain (CS0535 without it); got: $owner",
    )
    assertTrue(
      owner.contains("internal IntPtr _scopeHandle;") &&
          owner.contains("internal IntPtr GetOrCreateScope()"),
      "expected the abstract owner to still own the scope itself; got: $owner",
    )

    val concrete: String = classBlock(result.generatedCSharp, "MittBrusher")
    assertTrue(
      concrete.contains("public override ValueTask DisposeAsync()"),
      "expected the concrete class to carry the drain body as an override; got: $concrete",
    )
    assertFalse(
      concrete.contains("_scopeHandle;"),
      "expected the concrete class to reuse the abstract owner's scope field; got: $concrete",
    )
  }

  /** The C# class body, from its declaration line to the next class declaration. */
  private fun classBlock(csharp: String, name: String): String {
    val lines: List<String> = csharp.lines()
    val start: Int =
      lines.indexOfFirst { it.contains("class $name ") || it.endsWith("class $name") }
    if (start < 0) return "<no class $name in generated C#>"
    val end: Int = lines.drop(start + 1)
      .indexOfFirst { it.trimStart().startsWith("public ") && it.contains("class ") }
    return lines.drop(start).take(if (end < 0) lines.size else end + 1).joinToString("\n")
  }
}
