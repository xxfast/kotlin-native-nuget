package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-216 (second amendment): every generated C# body whose signature nests an open exported
 * generic instantiation fills that instantiation's `NugetFactory<T>` slot on entry: a getter, a
 * callback argument, a `Flow` element, a returned lambda, a `suspend` result, and a generic sealed
 * or abstract class nested inside one. A generic abstract class gets its own slot through its
 * backing wrapper.
 *
 * Oreo packs the satchel; Mylo unpacks it.
 */
class Tier1OpenSlotFillTest {

  private val options: Map<String, String> = mapOf(
    "nuget.namespace" to "TestLibrary",
    "nuget.rootPackage" to "tier1",
  )

  private val source: String = """
    package tier1.satchel

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    class Basket<T>(val item: T)

    sealed class Pickup<out T> {
      data class Fluffed<T>(val pet: T) : Pickup<T>()
    }

    abstract class Shelf<T>(val item: T) {
      abstract val label: String
    }

    class Dresser<T>(item: T) : Shelf<T>(item) {
      override val label: String = "dresser"
    }

    class Satchel<T>(val item: T) {
      val nested: Basket<Basket<T>> = Basket(Basket(item))
      val picked: Basket<Pickup<T>> = Basket(Pickup.Fluffed(item))
      val shelved: Basket<Shelf<T>> = Basket(Dresser(item))
      fun visit(block: (Basket<Basket<T>>) -> Unit) = block(nested)
    }

    class Porter {
      fun <T> stream(item: T): Flow<Basket<Basket<T>>> = flowOf(Basket(Basket(item)))
      suspend fun <T> fetch(item: T): Basket<Basket<T>> = Basket(Basket(item))
    }

    fun <T> later(item: T): () -> Basket<Basket<T>> = { Basket(Basket(item)) }
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      source,
      fileName = "Satchels.kt",
      processorOptions = options,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private val ns = "global::TestLibrary.Satchel"
  private val inner = "NugetFactory<$ns.Basket<T>>.Create ??= " +
    "static handle => new $ns.Basket<T>(handle, out _);"
  private val outer = "NugetFactory<$ns.Basket<$ns.Basket<T>>>.Create ??= " +
    "static handle => new $ns.Basket<$ns.Basket<T>>(handle, out _);"

  /** The body of the member whose declaration line contains [head], up to the next member. */
  private fun body(head: String): String {
    val cs: String = result.generatedCSharp
    assertContains(cs, head)
    return cs.substringAfter(head).substringBefore("\n        public ")
  }

  private fun assertKotlinCompiles() {
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
  }

  @Test
  fun `a getter fills the basket it nests`() {
    assertKotlinCompiles()
    val nested: String = body("public $ns.Basket<$ns.Basket<T>> Nested")
    assertContains(nested, inner)
  }

  @Test
  fun `a sealed slot nested in a getter reads through its discriminator`() {
    val picked: String = body("public $ns.Basket<$ns.Pickup<T>> Picked")
    assertContains(
      picked,
      "NugetFactory<$ns.Pickup<T>>.Create ??= static handle => $ns.Pickup<T>.FromHandle(handle);",
    )
  }

  @Test
  fun `an abstract class nested in a getter constructs its backing, and owns a slot`() {
    val shelved: String = body("public $ns.Basket<$ns.Shelf<T>> Shelved")
    assertContains(
      shelved,
      "NugetFactory<$ns.Shelf<T>>.Create ??= " +
        "static handle => new $ns.Shelf.Backing<T>(handle, out _);",
    )
    assertContains(
      result.generatedCSharp,
      "NugetFactory<Shelf<T>>.Create = static handle => new Shelf.Backing<T>(handle, out _);",
    )
  }

  @Test
  fun `a callback argument fills the outer basket too, since it reads through FromHandle`() {
    val visit: String = body("public void Visit(")
    assertContains(visit, outer)
    assertContains(visit, inner)
  }

  @Test
  fun `a flow element and a suspend result fill on entry`() {
    val stream: String = body("Stream<T>(T item)")
    assertTrue(stream.trimStart().startsWith("{\n            $outer"), "stream body: $stream")
    assertContains(stream, inner)
    val fetch: String = body("FetchAsync<T>(T item")
    assertTrue(fetch.substringAfter("{\n").trimStart().startsWith("NugetFactory<"), "fetch: $fetch")
    assertContains(fetch, inner)
  }

  @Test
  fun `a returned lambda fills its result's slots`() {
    val later: String = body("Later<T>(T item)")
    assertContains(later, outer)
    assertContains(later, inner)
  }

  @Test
  fun `the filled C# compiles and a consumer reads every position`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using System.Threading.Tasks;
      using TestLibrary.Satchel;

      namespace Consumer
      {
          public static class Reads
          {
              public static async Task<short> All()
              {
                  using var satchel = new Satchel<short>(7);
                  using Basket<Basket<short>> nested = satchel.Nested;
                  using Basket<short> inner = nested.Item;
                  using Basket<Pickup<short>> picked = satchel.Picked;
                  using Basket<Shelf<short>> shelved = satchel.Shelved;
                  satchel.Visit(outer => { using Basket<short> b = outer.Item; });
                  using var porter = new Porter();
                  await foreach (Basket<Basket<short>> each in porter.Stream((short)1))
                  {
                      each.Dispose();
                  }
                  using Basket<Basket<short>> fetched = await porter.FetchAsync((short)2);
                  return inner.Item;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
