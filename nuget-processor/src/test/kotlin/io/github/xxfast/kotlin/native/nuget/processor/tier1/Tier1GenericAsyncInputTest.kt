package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.ForwardAbiContract
import io.github.xxfast.kotlin.native.nuget.processor.ForwardAbiSignature
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-215 lane C: a member's own `T` flowing INTO Kotlin asynchronously, on the legacy suspend
 * route. A `Flow<T>` element is an owned box read through the shared `CurrentBoxed()`, a
 * `suspend (T) -> T` result an owned box read through `NugetTasks.EndBoxed`; Kotlin reads,
 * casts to the bound and releases each. The retain export `WrapOwned<T>` calls is gated on this
 * route's own sighting, so it binds with no plan carrying a lambda result.
 *
 * Oreo drinks from the siphon.
 */
class Tier1GenericAsyncInputTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private val source: String = """
    package tier1.siphons

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.toList

    interface Furry { val name: String }
    interface Tricky { val tricks: Int }

    enum class Tint { TUXEDO, MOCHA }

    class Siphon {
      suspend fun <T> gulp(source: Flow<T>): List<T> = source.toList()
      suspend fun <T : Furry> names(source: Flow<T>): String =
        source.toList().joinToString(",") { pet -> pet.name }
      suspend fun <T> brew(value: T, step: suspend (T) -> T): T = step(value)
      suspend fun <T> tip(value: T, cup: suspend (T) -> Unit) { cup(value) }
      suspend fun <T : Enum<T>> tints(source: Flow<T>): Int = source.toList().size
      suspend fun <T : Enum<T>> retint(tint: T, step: suspend (T) -> T): String = step(tint).name
      suspend fun <T> stars(source: Flow<T>): Int where T : Furry, T : Tricky =
        source.toList().sumOf { pet -> pet.tricks }
      suspend fun <T> decant(value: T, pour: (T) -> T): T = pour(value)
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    source,
    processorOptions = options,
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  private fun assertKotlinCompiles(result: Tier1Result) {
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
  }

  @Test
  fun `a Flow of T and a suspend lambda over T bind on the suspend route`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)
    val csharp: String = result.generatedCSharp

    assertContains(
      csharp,
      "public Task<IReadOnlyList<T>> GulpAsync<T>(" +
        "global::System.Collections.Generic.IAsyncEnumerable<T> source, ",
    )
    assertContains(csharp, "where T : global::TestLibrary.Siphons.IFurry")
    assertContains(
      csharp,
      "public Task<T> BrewAsync<T>(T value, Func<T, global::System.Threading.CancellationToken, " +
        "global::System.Threading.Tasks.Task<T>> step, ",
    )
    assertContains(csharp, "NugetTasks.BeginBoxed<T>(")
    assertContains(csharp, "internal static IntPtr EndBoxed(IntPtr task)")
    assertContains(csharp, "NugetAsyncEnumeration.CurrentBoxed(a0)")
    assertContains(csharp, "internal override IntPtr CurrentBoxed() => NugetMarshal.WrapOwned(")
    // The bound is the element type Kotlin declares, so `names<T : Furry>` infers it from the flow.
    assertContains(result.generated, "kotlinx.coroutines.flow.Flow<tier1.siphons.Furry>")
    assertContains(result.generated, "suspend (kotlin.Any?) -> kotlin.Any?")
    assertContains(result.generated, "NugetHandles.release(")
    assertFalse(result.generated.contains("StableRef.create("), "bare StableRef.create in output")
  }

  @Test
  fun `the retain export binds from the suspend route alone`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    assertContains(result.generated, "@CName(\"library__retain_handle\")")
    val scraped: List<ForwardAbiSignature> = ForwardAbiContract
      .csharpLegacy(result.generatedCSharp, ordinaryNames = emptySet())
      .filter { signature -> signature.exportName == "library__retain_handle" }
    assertEquals("[library__retain_handle(in pointer) -> pointer]", scraped.toString())
  }

  /**
   * A trampolined (ADR-198) or multi-bound (ADR-217) `T` in a suspend lambda or a
   * `Flow` binds. The export runs under the trampoline, so the lambda and the flow are spelled with
   * its own type variable and each box is checked against the bounds, then cast to it.
   */
  @Test
  fun `a trampolined or multi-bound T binds inside the trampoline`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)
    val warned: List<String> = result.kspWarnings.filter { warning ->
      warning.contains("Siphon.tints") || warning.contains("Siphon.retint") ||
        warning.contains("Siphon.stars")
    }
    assertEquals(emptyList(), warned)
    listOf("TintsAsync<T>(", "RetintAsync<T>(", "StarsAsync<T>(").forEach { member ->
      assertContains(result.generatedCSharp, member)
    }
    assertContains(result.generated, "kotlinx.coroutines.flow.Flow<T>")
    assertContains(result.generated, "suspend (T) -> T")
    assertContains(result.generated, "bounded as kotlin.Enum<*>; bounded as T")
  }

  /**
   * A plain lambda on a `suspend` member reaches the hand-written callback route, which lowers no
   * `T` result. The refusal names the lambda's position, not `T` itself: lane A binds a `T` result
   * on an ordinary member's per-call lambda.
   */
  @Test
  fun `a T callback result on a suspend member names the position, not the type`() {
    val result: Tier1Result = run()
    val named: List<String> = result.kspWarnings.filter { warning ->
      warning.contains("Siphon.decant") && warning.contains("SKIPPED_UNSUPPORTED_RETURN")
    }
    assertEquals(1, named.size, "expected one return warning naming Siphon.decant; got: $named")
    assertContains(
      named.single(),
      "a callback returning a type parameter binds only as a per-call lambda on an ordinary " +
        "(non-suspend) member, but `pour: (T) -> T` returns `T` from a member that lambda " +
        "route does not carry",
    )
    assertFalse(named.single().contains("a callback can return `Unit`"), "stale text: $named")
    assertFalse(result.generatedCSharp.contains("DecantAsync"), "a refused member binds")
  }
}
