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
 * One member kind is a drop on an arm with a Kotlin type parameter in scope (a `suspend` member was
 * the second until ADR-219, which binds it over the arm's erased receiver, an override of the
 * generic base's member included, since the base projects nothing):
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
                  seen += await hot.CoolAsync() + await hot.OverAsync();
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
  fun `a suspend member of a generic arm binds over the erased receiver`() {
    // ADR-219: the suspend route reads `Dish.Hot<Any?>`, as the arm's Flow route does.
    listOf("Dish.Hot.cool", "Dish.Hot.warm", "Dish.Hot.over", "Reply.Trill.hush")
      .forEach { member ->
        assertTrue(named(member).isEmpty(), "`$member` binds; got: ${named(member)}")
      }
    assertContains(result.generated, "asStableRef<tier1.larder.Dish.Hot<Any?>>().get()")
    listOf("CoolAsync(", "WarmAsync(", "HushAsync(").forEach { member ->
      assertContains(result.generatedCSharp, "public Task<int> $member")
    }
  }

  @Test
  fun `a callback carrying the type parameter binds on both halves`() {
    // ADR-239: a lambda over the arm's `T` binds (ADR-199 refused it): the per-call `pick` on the
    // plan with its `T` boxed, the stored `addTaste` / `removeTaste` pair on the managed-listener
    // route over the erased receiver.
    listOf("Dish.Hot.pick", "Dish.Hot.addTaste", "Dish.Hot.removeTaste").forEach { member ->
      assertTrue(named(member).isEmpty(), "`$member` binds; got: ${named(member)}")
    }
    assertContains(result.generatedCSharp, "Pick(Func<char, T> cb)")
    assertContains(result.generatedCSharp, "public IDisposable AddTaste(Action<T> listener)")
    listOf("hot_addTaste", "hot_removeTaste").forEach { export ->
      assertTrue(export in result.generated, "expected the Kotlin export for $export")
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
    // `over` binds on each arm (ADR-219 for the generic one), never on the generic base.
    assertEquals(2, cs.lines().count { "public Task<int> OverAsync(" in it })
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
  }
}
