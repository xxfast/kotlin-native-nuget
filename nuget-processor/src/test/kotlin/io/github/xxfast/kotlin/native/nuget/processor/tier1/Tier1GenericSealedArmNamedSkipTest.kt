package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-147 on an arm of a generic sealed hierarchy (ADR-199): what a generic arm cannot bind is
 * named, under the code a generic CLASS's member takes (`Tier1GenericOwnerLegacyRouteTest`).
 *
 * Two member kinds are drops on an arm with a Kotlin type parameter in scope:
 *  - a `suspend` member. The suspend route refuses a generic owner on both halves, so `Hot<T>.cool`
 *    had no export, no `CoolAsync` and no diagnostic. An override of a base member is the same
 *    drop: the generic base projects nothing, and its own skip tells the author to declare the
 *    member on the arm. It reads the generic class's sentence, which is true of it.
 *  - a callback member whose lambda carries the type parameter (`cb: (T) -> Unit`). The route has
 *    no marshalling for a `T` payload, so the generated Kotlin did not compile. It reads a sentence
 *    of its own: the arm's receiver IS spelled, so "cannot spell a generic receiver" would be
 *    false, and what stops it is the payload the erased receiver leaves as `Any?`.
 *
 * Everything else on the arm binds, and so does every member of an arm that is generic in C# only.
 * The callback sentence's two remedies are cells here: a concrete payload on the generic arm
 * (`Hot.withCount`) and the member on an arm that fixes the argument (`Cold.withChill`).
 */
class Tier1GenericSealedArmNamedSkipTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.larder

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      sealed class Dish<T> {
        abstract suspend fun over(): Int

        class Hot<T>(val item: T) : Dish<T>() {
          suspend fun cool(): Int = 6
          suspend fun warm(cb: suspend (Int) -> Int): Int = cb(1)
          override suspend fun over(): Int = 2
          fun withItem(cb: (T) -> Unit) = cb(item)
          fun pick(cb: (Char) -> T): T = cb('a')
          fun addTaste(listener: (T) -> Unit) {}
          fun removeTaste(listener: (T) -> Unit) {}

          // The controls: the same routes without a type parameter in the lambda bind.
          fun each(cb: (Char) -> Unit) = cb('a')
          fun withCount(cb: (Int) -> Unit) = cb(1)
          fun wafts(): Flow<Int> = flowOf(1)
        }

        // The control: an arm that fixes the argument has every route.
        class Cold : Dish<Int>() {
          suspend fun thaw(): Int = 7
          override suspend fun over(): Int = 3
          fun withChill(cb: (Int) -> Unit) = cb(1)
        }
      }

      sealed interface Reply<out T> {
        suspend fun hush(): Int

        class Trill<T>(val pitch: T) : Reply<T> {
          override suspend fun hush(): Int = 1
        }
      }

      sealed class Plate<out T> {
        // Not generic in Kotlin, `Bare<T>` in C#: a phantom parameter takes no route away.
        class Bare(val id: Int) : Plate<Nothing>() {
          suspend fun wipe(): Int = id
        }
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private fun named(member: String): List<String> =
    result.kspWarnings.filter { warning -> "$member:" in warning }

  private val erasedLambdaReason: String =
    "one of its lambdas takes or returns a type parameter of its generic sealed arm, and the " +
        "bridge calls the arm with that parameter erased: the lambda's payload crosses as " +
        "`Any?`, which the callback route cannot marshal (ADR-199)"

  private val erasedLambdaHint: String =
    "give the lambda a concrete payload type (`(Int) -> Unit`), or declare the member on an arm " +
        "that fixes the type argument (for example `class Whole : Base<Int>()`), where the " +
        "lambda is concrete and the member binds"

  @Test
  fun `both halves compile`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Threading.Tasks;
      using Interop.Larder;
      using Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<int> Run()
              {
                  int seen = 0;
                  using var hot = new Dish.Hot<string>("tuna");
                  hot.Each(letter => seen += letter);
                  hot.WithCount(count => seen += count);
                  await foreach (int waft in hot.Wafts()) seen += waft;
                  using var cold = new Dish.Cold();
                  cold.WithChill(chill => seen += chill);
                  using var bare = new Plate.Bare<string>(3);
                  return seen + await cold.ThawAsync() + await cold.OverAsync()
                      + await bare.WipeAsync();
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `a suspend member of a generic arm is named like a generic class's`() {
    listOf("Dish.Hot.cool", "Dish.Hot.warm", "Dish.Hot.over", "Reply.Trill.hush")
      .forEach { member ->
        val warnings: List<String> = named(member)
        assertEquals(1, warnings.size, "expected `$member` named once; got: ${result.kspWarnings}")
        assertContains(warnings.single(), "SKIPPED_UNSUPPORTED_COMBINATION")
        assertContains(warnings.single(), "it is a `suspend` member of a generic class")
        assertContains(warnings.single(), "(ADR-147)")
      }
    listOf("hot_cool", "hot_warm", "hot_over", "trill_hush").forEach { export ->
      assertFalse(export in result.generated, "expected no Kotlin export for $export")
    }
    listOf("CoolAsync(", "WarmAsync(", "HushAsync(").forEach { member ->
      assertFalse(member in result.generatedCSharp, "expected no `$member` in C#")
    }
  }

  @Test
  fun `a callback carrying the type parameter is named and reaches neither half`() {
    listOf("Dish.Hot.addTaste", "Dish.Hot.removeTaste")
      .forEach { member ->
        val warnings: List<String> = named(member)
        assertEquals(1, warnings.size, "expected `$member` named once; got: ${result.kspWarnings}")
        assertEquals(
          "[nuget:SKIPPED_UNSUPPORTED_COMBINATION] Skipping tier1.larder.$member: " +
              "$erasedLambdaReason. $erasedLambdaHint",
          warnings.single().lines().first(),
        )
        // The generic class's reason is false here: this arm's receiver is spelled.
        assertFalse("cannot spell a generic receiver" in warnings.single())
      }
    // A type-parameter RESULT is one of the shapes the route already refuses by name, so it keeps
    // that refusal and is not named a second time.
    val pick: List<String> = named("Dish.Hot.pick")
    assertEquals(1, pick.size, "expected `pick` named once; got: ${result.kspWarnings}")
    assertContains(pick.single(), "SKIPPED_UNSUPPORTED_RETURN")
    assertContains(pick.single(), "returns `T`")
    listOf("hot_pick", "hot_addTaste", "hot_removeTaste").forEach { export ->
      assertFalse(export in result.generated, "expected no Kotlin export for $export")
    }
    listOf("Pick(", "AddTaste(").forEach { member ->
      assertFalse(member in result.generatedCSharp, "expected no `$member` in C#")
    }
  }

  @Test
  fun `every other member binds and is not named`() {
    listOf(
      "Dish.Hot.each", "Dish.Hot.wafts", "Dish.Cold.thaw", "Dish.Cold.over", "Plate.Bare.wipe",
      // The two remedies the type-parameter lambda's hint names.
      "Dish.Hot.withCount", "Dish.Cold.withChill",
      // ADR-215 lane A: a per-call lambda over the arm's `T` binds on the plan callback.
      "Dish.Hot.withItem",
    ).forEach { member ->
      assertTrue(named(member).isEmpty(), "`$member` binds; got: ${named(member)}")
    }
    val cs: String = result.generatedCSharp
    listOf(
      "public void Each(Action<char> cb)", "public KotlinFlow<int> Wafts()",
      "public Task<int> ThawAsync(", "public Task<int> OverAsync(", "public Task<int> WipeAsync(",
      "public void WithCount(Action<int> cb)", "public void WithChill(Action<int> cb)",
      "public void WithItem(Action<T> cb)",
    ).forEach { member -> assertContains(cs, member) }
    // `over` binds on the one arm that can carry it, and nowhere else.
    assertEquals(1, cs.lines().count { "public Task<int> OverAsync(" in it })
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
  }
}
