package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-094 amendment: a class's base list advertises what its body implements. `renderDispose` emits
 * `Dispose()` unconditionally and `DisposeAsync()` whenever the class owns a scope, but the
 * `implements` `when` used to be exclusive, so the `interfaces.isNotEmpty()` arm short-circuited
 * both disposable arms. A class implementing an exported interface read `: INapper, INugetHandle`,
 * and the only reason it was disposable at all was `renderInterface` spelling
 * `public interface INapper : IDisposable`.
 *
 * `IAsyncDisposable` had no such transitive rescue: `await using` compiled (it binds the
 * `DisposeAsync` *pattern*, not the interface), but the class could not be **held** as an
 * `IAsyncDisposable`, so a cast or an `IAsyncDisposable`-typed field threw `InvalidCastException`.
 * That type identity is the gap, and a base-list spelling is a compile-time fact, so it is pinned
 * here rather than only through the fixture.
 *
 * Oreo claims the pod, Mylo dozes on top of it.
 */
class Tier1DisposableBaseListTest {

  private val fixture: String = """
    package tier1.baselist

    import kotlinx.coroutines.delay

    interface Napper {
      fun nap(): String
    }

    // Interface + a scope owner: the one shape that dropped both disposables.
    class NapPod : Napper {
      override fun nap(): String = "Oreo curls up in the pod"
      suspend fun doze(): Int {
        delay(1)
        return 20
      }
    }

    // Interface, no scope: gains `IDisposable` by name, keeps its interface first.
    class Cushion : Napper {
      override fun nap(): String = "Mylo flops on the cushion"
    }

    // No interface, no scope: the shipped spelling, byte-identical.
    class Basket {
      fun weave(): String = "wicker"
    }

    // No interface, a scope: the shipped spelling, byte-identical.
    class Heater {
      suspend fun warm(): Int {
        delay(1)
        return 30
      }
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "NapSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /**
   * The exported interfaces come first (the consumer's own contract), then the BCL disposables,
   * then `INugetHandle` in the tail position it has always held.
   */
  @Test
  fun `a class implementing an interface names its disposables too`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the fixture to compile; got: ${result.compileErrors} ${result.kspErrors}",
    )

    val missing: List<String> = listOf(
      "public class NapPod : INapper, IDisposable, IAsyncDisposable, INugetHandle",
      "public class Cushion : INapper, IDisposable, INugetHandle",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected the interfaces and the disposables side by side; missing: $missing; got: " +
          "${csharpLinesFor(result, "class ")}",
    )
  }

  /** The interface-less spellings are untouched by the change: same list, same order. */
  @Test
  fun `a class without interfaces keeps its shipped base list`() {
    val result = run()

    val missing: List<String> = listOf(
      "public class Basket : IDisposable, INugetHandle",
      "public class Heater : IDisposable, IAsyncDisposable, INugetHandle",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected the interface-less base lists unchanged; missing: $missing; got: " +
          "${csharpLinesFor(result, "class ")}",
    )
  }

  private fun csharpLinesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(needle) }
}
