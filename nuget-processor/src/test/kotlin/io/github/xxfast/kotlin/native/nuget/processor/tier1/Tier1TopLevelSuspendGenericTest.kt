package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-214: a `suspend fun <T>` binds on the suspend route, one `_async` export each, every bare `T`
 * position on ADR-147's boxed-handle wire (`Boxed` parameter and return shapes). Before it, the
 * one-parameter `T`-returning shape landed on the width-variant route and broke the generated
 * Kotlin compile; every other shape was named as an input the route cannot take. ADR-213 deleted
 * that route, so the guard here is that no `suspend fun <T>` reaches the plan's sync route.
 *
 * Oreo gets everything late; Mylo waits by the door.
 */
class Tier1TopLevelSuspendGenericTest {

  private val options: Map<String, String> = mapOf("nuget.rootPackage" to "tier1")

  private val source: String = """
    package tier1.suspendgeneric

    suspend fun <T> fetchLater(value: T): T = value
    suspend fun <T : Any> describeLater(value: T): String = "${'$'}value"
    suspend fun <T> pickLater(first: T, second: T): T = second
    suspend fun <T> perhapsLater(value: T?): T? = value
    suspend fun <T : Number> weighLater(amount: T): Double = amount.toDouble()
    suspend fun <T : Comparable<T>> bestLater(value: T): T = value
    suspend fun <T> shuffleLater(list: List<T>): List<T> = list.reversed()
    suspend fun <K : Any, V> keepLater(map: Map<K, V>): Map<K, V> = map

    class Courier {
      suspend fun <T> ferry(value: T): T = value
      suspend fun <T> ferryAll(items: List<T>): List<T> = items.reversed()
    }

    sealed class Delivery {
      class Porch(val mat: String) : Delivery() {
        suspend fun <T> leave(value: T): T = value
      }
      data object Lost : Delivery()
    }
  """.trimIndent()

  private fun run(): Tier1Result =
    Tier1Harness.run(source, fileName = "Deliveries.kt", processorOptions = options)

  private fun assertKotlinCompiles(result: Tier1Result) {
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
  }

  private fun Tier1Result.tier1Warnings(): List<String> =
    kspWarnings.filter { it.contains("tier1.") }

  @Test
  fun `a top-level suspend generic binds on the suspend route, never the plan's sync route`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "public static Task<T> FetchLaterAsync<T>(T value, " +
        "CancellationToken cancellationToken = default)",
    )
    assertContains(
      cs,
      "public static Task<string> DescribeLaterAsync<T>(T value, " +
          "CancellationToken cancellationToken = default) where T : notnull",
    )
    assertContains(
      cs,
      "public static Task<T> PickLaterAsync<T>(T first, T second, " +
          "CancellationToken cancellationToken = default)",
    )
    assertContains(
      cs,
      "public static Task<T?> PerhapsLaterAsync<T>(T? value, " +
          "CancellationToken cancellationToken = default)",
    )
    assertContains(cs, "EntryPoint = \"library_suspendgeneric__fetchLater_async\"")
    assertFalse(
      cs.contains("EntryPoint = \"library_suspendgeneric__fetchLater\""),
      "no sync plan export: $cs",
    )
    assertContains(result.generated, ".fetchLater<Any?>(")
    assertContains(result.generated, ".describeLater<Any>(")
    assertTrue(
      result.tier1Warnings().isEmpty(),
      "expected every member to bind: ${result.tier1Warnings()}",
    )
  }

  @Test
  fun `a boxed T argument is wrapped before the extern and released in its finally`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    val cs: String = result.generatedCSharp
    assertContains(cs, "IntPtr valueBox = NugetMarshal.Wrap<T>(value!, out bool valueOwned);")
    assertContains(cs, "if (valueOwned) NugetMarshal.Dispose(valueBox);")
    assertContains(cs, "else GC.KeepAlive(value);")
    assertContains(cs, "t.SetResult(NugetMarshal.FromHandle<T>(resultPtr));")
    assertContains(cs, "t.SetResult(NugetMarshal.FromHandle<T?>(resultPtr));")
  }

  /** The prelude only reads the box; the bound cast runs inside the launch, so it faults it. */
  @Test
  fun `a bounded T is read in the prelude and cast inside the launch`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    val kotlin: String = result.generated
    val weigh: String = kotlin.substringAfter("__weighLater_async(").substringBefore("\n}\n")
    val prelude: String = weigh.substringBefore("launchForCSharp")
    val launched: String = weigh.substringAfter("launchForCSharp")
    assertContains(prelude, "amount?.asStableRef<Any>()?.get()")
    assertFalse(prelude.contains(" as kotlin.Number"), "cast escaped the launch: $weigh")
    assertContains(launched, ".weighLater<kotlin.Number>((amountArg as kotlin.Number))")
    // `T : Any` asserts non-null inside the launch too.
    assertContains(kotlin, ".describeLater<Any>(valueArg!!)")
    // A generic bound's erased cast is unchecked in its arguments, suppressed per export.
    val best: String = kotlin.substringBefore(
      "public fun export_library_suspendgeneric__bestLater_async(",
    )
      .substringAfterLast("@CName(")
    assertContains(best, "@Suppress(\"UNCHECKED_CAST\")")
  }

  @Test
  fun `collections of the own type parameter bind at a suspend parameter and return`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "public static Task<IReadOnlyList<T>> ShuffleLaterAsync<T>(IReadOnlyList<T> list, " +
          "CancellationToken cancellationToken = default)",
    )
    assertContains(
      cs,
      "public static Task<IReadOnlyDictionary<K, V>> KeepLaterAsync<K, V>(" +
          "IReadOnlyDictionary<K, V> map, CancellationToken cancellationToken = default) " +
          "where K : notnull",
    )
    assertContains(result.generated, ".keepLater<Any, Any?>(")
  }

  /** The finding the red run surfaced: `FerryAllAsync` was emitted without its `<T>` (CS0246). */
  @Test
  fun `a class and a sealed arm declare their suspend members' own type parameters`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    val cs: String = result.generatedCSharp
    assertContains(
      cs, "public Task<T> FerryAsync<T>(T value, CancellationToken cancellationToken = default)",
    )
    assertContains(
      cs,
      "public Task<IReadOnlyList<T>> FerryAllAsync<T>(IReadOnlyList<T> items, " +
          "CancellationToken cancellationToken = default)",
    )
    assertContains(
      cs, "public Task<T> LeaveAsync<T>(T value, CancellationToken cancellationToken = default)",
    )
    assertContains(result.generated, ".ferry<Any?>(")
    assertContains(result.generated, ".leave<Any?>(")
  }

  @Test
  fun `the generated C# compiles against a consumer`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using System.Threading.Tasks;
      using Interop.Suspendgeneric;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<string> Run()
              {
                  int age = await Deliveries.FetchLaterAsync(4);
                  string? none = await Deliveries.PerhapsLaterAsync<string>(null);
                  IReadOnlyList<string> names = await Deliveries.ShuffleLaterAsync(new[] { "Oreo", "Mylo" });
                  using var courier = new Courier();
                  string back = await courier.FerryAsync("Mylo");
                  IReadOnlyList<int> all = await courier.FerryAllAsync(new[] { 1, 2 });
                  return back + age + none + names.Count + all.Count;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * A shape ADR-197 refuses is named with the GENERIC sentence and its rule, not as an input.
   * ADR-213 narrowed the `reified` rule to members, so the reified case is a member here.
   */
  @Test
  fun `a refused suspend generic is named with the structural generic sentence`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.suspendgenericrefused

      class Sitter {
        suspend inline fun <reified T> castLater(value: Any): T = value as T

        suspend fun <T, U : T> nestLater(value: U): T = value
      }

      object Kennel {
        suspend fun <T> holdLater(value: T): T = value
      }
      """.trimIndent(),
      fileName = "Refusals.kt",
      processorOptions = options,
    )
    assertKotlinCompiles(result)

    // A top-level suspend function is named by its simple name, so filter on the members.
    val warnings: List<String> = result.kspWarnings.filter { it.contains("Later") }
    val cast: String = warnings.single { it.contains("castLater") }
    assertContains(cast, "SKIPPED_UNSUPPORTED_COMBINATION")
    assertContains(cast, "including a `suspend` one")
    assertContains(cast, "(here, its type parameter `T` is reified)")
    val nest: String = warnings.single { it.contains("nestLater") }
    assertContains(nest, "SKIPPED_UNSUPPORTED_COMBINATION")
    assertContains(nest, "(here, its type parameter `U` is bounded by another type parameter)")
    assertFalse(nest.contains("but not value: U"), "named as an input: $nest")
    // An object has no suspend route at all, generic or not (ROADMAP Phase 6).
    val kennel: String = warnings.single { it.contains("holdLater") }
    assertContains(kennel, "SKIPPED_UNSUPPORTED_COMBINATION")
    assertFalse(result.generatedCSharp.contains("HoldLaterAsync"), "object member bound")
  }

  /**
   * An interface reachable only as a `T` bound still gets its C# bridge, so a C# implementation
   * passed as `T` crosses `Wrap<T>` instead of throwing from `NugetBridge.HandleFor`.
   */
  @Test
  fun `an interface reachable only as a type parameter bound gets a bridge`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.suspendgenericbound

      interface Snoozer {
        fun murmur(): String
      }

      suspend fun <T : Snoozer> snoozeLater(snoozer: T): String = "zzz ${'$'}{snoozer.murmur()}"
      """.trimIndent(),
      fileName = "Snoozes.kt",
      processorOptions = options,
    )
    assertKotlinCompiles(result)
    assertContains(result.generatedCSharp, "SnoozerBridgeState : NugetBridgeState")
    assertContains(result.generated, "_snoozer_bridge_create")
  }

  /** The `Boxed` shape is the suspend route's alone; a Flow-returning member keeps its refusal. */
  @Test
  fun `a Flow member with its own type parameter does not take the boxed shape`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.suspendgenericflow

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      class Feeder {
        fun <T> echo(value: T): Flow<String> = flowOf("${'$'}value")
      }
      """.trimIndent(),
      fileName = "Feeders.kt",
      processorOptions = options,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertKotlinCompiles(result)
    assertFalse(
      result.generatedCSharp.contains("Echo<T>"),
      "flow member bound: ${result.generatedCSharp}",
    )
  }
}
