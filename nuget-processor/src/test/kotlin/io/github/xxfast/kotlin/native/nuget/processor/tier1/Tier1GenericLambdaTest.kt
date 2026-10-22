package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.ForwardAbiContract
import io.github.xxfast.kotlin.native.nuget.processor.ForwardAbiSignature
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-215 lane A: a member's own `T`, or its owner's `T`, inside a non-suspend lambda binds on the
 * ADR-160 plan callback. A `T` payload crosses on the boxed `Object` wire (Kotlin retains, C#
 * `FromHandle<T>` consumes). A `T` result crosses back as an owned box: C# `WrapOwned<T>` retains a
 * borrowed wrapper handle through the generated `<lib>__retain_handle` export, and Kotlin reads,
 * releases and casts the box to the bound.
 *
 * Oreo deals the cards; Mylo bats them off the table.
 */
class Tier1GenericLambdaTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private val bound: String = """
    package tier1.lambdas

    interface Furry { val name: String }

    class Tabby(override val name: String, val tricks: Int) : Furry

    enum class Tint { TUXEDO, MOCHA }

    class Dealer {
      fun <T> each(items: List<T>, action: (T) -> Unit) { items.forEach(action) }
      fun <T> map(value: T, transform: (T) -> T): T = transform(value)
      fun <T> fold(items: List<T>, initial: T, combine: (T, T) -> T): T = items.fold(initial, combine)
      fun <T : Furry> rename(pet: T, pick: (T) -> T): String = pick(pet).name
      fun <T : Any> peek(seat: T?, look: (T?) -> Unit) { look(seat) }
      fun <T : Any> fill(seat: T?, fallback: () -> T): T = seat ?: fallback()
      fun <T : Enum<T>> restyle(tint: T, step: (T) -> T): String = step(tint).name
      fun <T> deal(item: T, count: Int, done: (Int) -> Unit): T {
        repeat(count) { index -> done(index) }
        return item
      }
    }

    class Serving<T>(val portion: T) {
      fun each(action: (T) -> Unit) { action(portion) }
      fun map(transform: (T) -> T): T = transform(portion)
      fun <U> pair(other: U, combine: (T, U) -> String): String = combine(portion, other)
    }

    sealed class Helping<out T> {
      class Seconds<T>(val portion: T) : Helping<T>() {
        fun each(action: (T) -> Unit) { action(portion) }
        fun map(transform: (T) -> T): T = transform(portion)
      }
      data object Licked : Helping<Nothing>()
    }
  """.trimIndent()

  private fun runBound(): Tier1Result = Tier1Harness.run(bound, processorOptions = options)

  private fun assertKotlinCompiles(result: Tier1Result) {
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
  }

  private fun Tier1Result.publicLines(fragment: String): List<String> =
    generatedCSharp.lines().map(String::trim)
      .filter { line -> line.startsWith("public") && line.contains(fragment) }

  @Test
  fun `a T inside a lambda binds as an Action or Func over T`() {
    val result: Tier1Result = runBound()
    assertKotlinCompiles(result)

    val furry = "global::TestLibrary.Lambdas.IFurry"
    listOf(
      "public void Each<T>(IReadOnlyList<T> items, Action<T> action)",
      "public T Map<T>(T value, Func<T, T> transform)",
      "public T Fold<T>(IReadOnlyList<T> items, T initial, Func<T, T, T> combine)",
      "public string Rename<T>(T pet, Func<T, T> pick) where T : $furry",
      "public void Peek<T>(T? seat, Action<T?> look) where T : notnull",
      "public T Fill<T>(T? seat, Func<T> fallback) where T : notnull",
      "public string Restyle<T>(T tint, Func<T, T> step)",
      "public T Deal<T>(T item, int count, Action<int> done)",
      "public void Each(Action<T> action)",
      "public T Map(Func<T, T> transform)",
      "public string Pair<U>(U other, Func<T, U, string> combine)",
    ).forEach { signature ->
      assertTrue(
        result.generatedCSharp.contains(signature),
        "expected `$signature`; got: ${result.publicLines("(")}",
      )
    }
    val warnings: List<String> =
      result.kspWarnings.filter { warning -> warning.contains("tier1.lambdas") }
    assertTrue(warnings.isEmpty(), "expected every member to bind; got: $warnings")
  }

  @Test
  fun `a T payload is read by FromHandle and a T result is handed back owned`() {
    val result: Tier1Result = runBound()
    assertKotlinCompiles(result)

    assertContains(result.generatedCSharp, "NugetMarshal.FromHandle<T>(a0)")
    assertContains(result.generatedCSharp, "return NugetMarshal.WrapOwned<T>(")
    assertContains(result.generatedCSharp, "internal static IntPtr WrapOwned<T>(T value)")
    // Kotlin retains a nullable payload only when it is not null, and releases the result box.
    assertContains(result.generated, "?.let { NugetHandles.retain(it) }")
    assertContains(result.generated, "NugetHandles.release(")
    assertFalse(result.generated.contains("StableRef.create("), "bare StableRef.create in output")
  }

  /** The retain export is generated per library and pinned on both halves by the ABI contract. */
  @Test
  fun `the retain export binds on both halves when a plan carries an object callback result`() {
    val result: Tier1Result = runBound()
    assertKotlinCompiles(result)

    assertContains(result.generated, "@CName(\"library__retain_handle\")")
    assertContains(result.generatedCSharp, "EntryPoint = \"library__retain_handle\"")
    assertContains(
      result.generatedCSharp,
      "private static extern IntPtr Native_retain_handle(IntPtr handle);",
    )
    // Not a plan node: the import is scraped from the rendered `NugetMarshal`, the universe
    // `ForwardAbiContract.assertMatches` compares against the Kotlin `@CName` set.
    val scraped: List<ForwardAbiSignature> = ForwardAbiContract
      .csharpLegacy(result.generatedCSharp, ordinaryNames = emptySet())
      .filter { signature -> signature.exportName == "library__retain_handle" }
    assertEquals("[library__retain_handle(in pointer) -> pointer]", scraped.toString())
  }

  private val payloadOnly: String = """
    package tier1.lambdas

    class Dealer {
      fun <T> each(items: List<T>, action: (T) -> Unit) { items.forEach(action) }
    }
  """.trimIndent()

  @Test
  fun `no plan with an object callback result emits no retain export`() {
    val result: Tier1Result = Tier1Harness.run(payloadOnly, processorOptions = options)
    assertKotlinCompiles(result)

    assertContains(
      result.generatedCSharp,
      "public void Each<T>(IReadOnlyList<T> items, Action<T> action)",
    )
    assertFalse(result.generated.contains("retain_handle"), "retain export without a result")
    assertFalse(result.generatedCSharp.contains("retain_handle"), "retain import without a result")
    assertFalse(result.generatedCSharp.contains("WrapOwned"), "WrapOwned without a result")
  }

  private val topLevel: String = """
    package tier1.lambdas

    fun <T> echoer(): (T) -> T = { item -> item }
  """.trimIndent()

  @Test
  fun `a top-level returned lambda over its own T binds`() {
    val result: Tier1Result = Tier1Harness.run(topLevel, processorOptions = options)
    assertKotlinCompiles(result)

    assertTrue(
      result.generatedCSharp.contains("KotlinFunc<T, T> Echoer<T>()"),
      "got: ${result.publicLines("Echoer")} ${result.kspWarnings}",
    )
  }
}
