package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-147 on an arm of a generic sealed hierarchy (ADR-199): what a generic arm cannot bind is
 * named, with the code and sentence a generic CLASS's member takes
 * (`Tier1GenericOwnerLegacyRouteTest`).
 *
 * Two member kinds are drops on an arm with a Kotlin type parameter in scope:
 *  - a `suspend` member. The suspend route refuses a generic owner on both halves, so `Hot<T>.cool`
 *    had no export, no `CoolAsync` and no diagnostic. An override of a base member is the same
 *    drop: the generic base projects nothing, and its own skip tells the author to declare the
 *    member on the arm.
 *  - a callback member whose lambda carries the type parameter (`cb: (T) -> Unit`). The route has
 *    no marshalling for a `T` payload, so the generated Kotlin did not compile.
 *
 * Everything else on the arm binds, and so does every member of an arm that is generic in C# only.
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
          fun wafts(): Flow<Int> = flowOf(1)
        }

        // The control: an arm that fixes the argument has every route.
        class Cold : Dish<Int>() {
          suspend fun thaw(): Int = 7
          override suspend fun over(): Int = 3
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
                  await foreach (int waft in hot.Wafts()) seen += waft;
                  using var cold = new Dish.Cold();
                  using var bare = new Plate.Bare<string>(3);
                  return seen + await cold.ThawAsync() + await cold.OverAsync() + await bare.WipeAsync();
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `a suspend member of a generic arm is named like a generic class's`() {
    listOf("Dish.Hot.cool", "Dish.Hot.warm", "Dish.Hot.over", "Reply.Trill.hush").forEach { member ->
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
    listOf("Dish.Hot.withItem", "Dish.Hot.addTaste", "Dish.Hot.removeTaste")
      .forEach { member ->
        val warnings: List<String> = named(member)
        assertEquals(1, warnings.size, "expected `$member` named once; got: ${result.kspWarnings}")
        assertContains(warnings.single(), "SKIPPED_UNSUPPORTED_COMBINATION")
        assertContains(warnings.single(), "it is a callback member of a generic class")
      }
    // A type-parameter RESULT is one of the shapes the route already refuses by name, so it keeps
    // that refusal and is not named a second time.
    val pick: List<String> = named("Dish.Hot.pick")
    assertEquals(1, pick.size, "expected `pick` named once; got: ${result.kspWarnings}")
    assertContains(pick.single(), "SKIPPED_UNSUPPORTED_RETURN")
    assertContains(pick.single(), "returns `T`")
    listOf("hot_withItem", "hot_pick", "hot_addTaste", "hot_removeTaste").forEach { export ->
      assertFalse(export in result.generated, "expected no Kotlin export for $export")
    }
    listOf("WithItem(", "Pick(", "AddTaste(").forEach { member ->
      assertFalse(member in result.generatedCSharp, "expected no `$member` in C#")
    }
  }

  @Test
  fun `every other member binds and is not named`() {
    listOf(
      "Dish.Hot.each", "Dish.Hot.wafts", "Dish.Cold.thaw", "Dish.Cold.over", "Plate.Bare.wipe",
    ).forEach { member ->
      assertTrue(named(member).isEmpty(), "`$member` binds; got: ${named(member)}")
    }
    val cs: String = result.generatedCSharp
    listOf(
      "public void Each(Action<char> cb)", "public KotlinFlow<int> Wafts()",
      "public Task<int> ThawAsync(", "public Task<int> OverAsync(", "public Task<int> WipeAsync(",
    ).forEach { member -> assertContains(cs, member) }
    // `over` binds on the one arm that can carry it, and nowhere else.
    assertEquals(1, cs.lines().count { "public Task<int> OverAsync(" in it })
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
  }
}
