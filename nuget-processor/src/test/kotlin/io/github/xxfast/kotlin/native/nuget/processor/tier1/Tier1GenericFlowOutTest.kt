package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-215 lane B: a type parameter as the element of a `Flow` Kotlin hands to C#. A plain generic
 * class takes the erased receiver on the Flow route (ROADMAP "generic class flow property dropped
 * whole", closed); a member's own `T` is a boxed parameter and a method type parameter; a
 * trampolined `T` and a multi-bound owner `T` parameter ride the ADR-198 trampoline; an
 * unconstrained `T` element boxes through the null-guarded arm, the shipped generic arm included.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without `Flow` on
 * the KSP libraries path every member here is skipped and the assertions pass vacuously.
 */
class Tier1GenericFlowOutTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.flowout

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.flowOf

      interface Furry { val name: String }
      interface Tricky { val tricks: Int }
      enum class Medal { BRONZE, SILVER, GOLD }

      class Brook<T>(val seed: T) {
        val updates: Flow<T> = flowOf(seed)
        val level: MutableStateFlow<T> = MutableStateFlow(seed)
        fun flowing(): Flow<T> = flowOf(seed)
      }

      class Creek {
        fun <T> stream(value: T): Flow<T> = flowOf(value)
        fun <T : Enum<T>> crown(medal: T): Flow<T> = flowOf(medal)
      }

      class Pride<T>(val leader: T) where T : Furry, T : Tricky {
        fun join(pet: T): Flow<T> = flowOf(leader, pet)
      }

      sealed class Dish<T> {
        class Hot<T>(val item: T) : Dish<T>() { val portions: Flow<T> = flowOf(item) }
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `a generic class flow member binds over the erased receiver on both halves`() {
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertContains(result.generated, "asStableRef<tier1.flowout.Brook<Any?>>().get()")
    assertContains(result.generatedCSharp, "public KotlinFlow<T> Updates")
    assertContains(result.generatedCSharp, "public KotlinMutableStateFlow<T> Level")
    assertContains(result.generatedCSharp, "public KotlinFlow<T> Flowing()")
    assertContains(result.generatedCSharp, "nameof(Brook<T>)")
    assertContains(result.generatedCSharp, "NugetMarshal.Wrap<T>(v!, out bool vOwned)")
    assertFalse(result.kspWarnings.any { "Brook." in it }, "warnings=${result.kspWarnings}")
  }

  @Test
  fun `an unconstrained T element boxes null as the null pointer, the generic arm included`() {
    assertContains(result.generated, "if (value != null) NugetHandles.retain(value) else null")
    assertFalse(
      result.generated.lines()
        .dropWhile { "dish_hot_get_portions_collect\")" !in it }
        .take(16)
        .any { "retain(value as Any)" in it },
      "Dish.Hot.portions boxed `value as Any`: ${result.generated}",
    )
  }

  @Test
  fun `a member's own T, a trampolined T and a multi-bound owner T bind on the Flow route`() {
    assertContains(result.generatedCSharp, "public KotlinFlow<T> Stream<T>(T value)")
    assertContains(result.generated, "obj.stream<Any?>(")
    assertContains(
      result.generated,
      "fun <T> nugetTrampoline(): kotlinx.cinterop.COpaquePointer where T : kotlin.Enum<T>",
    )
    assertContains(result.generated, "as tier1.flowout.Pride<T>)")
    assertContains(result.generatedCSharp, "public KotlinFlow<T> Join(T pet)")
    assertFalse(
      result.kspWarnings.any { "Creek." in it || "Pride." in it },
      "warnings=${result.kspWarnings}",
    )
  }

  /**
   * ADR-215 with ADR-174: a generic class that forwards an interface's Flow members (explicit
   * `IFeed.Source` / `IFeed.Ticks()` through the interface's carrier) and declares Flow members of
   * its own (public, over its erased receiver). The two sets share the one scope and both halves
   * compile; neither is projected twice.
   */
  @Test
  fun `a generic class with own Flow members also forwards an interface's Flow members`() {
    val forwarding: Tier1Result = Tier1Harness.run(
      """
      package tier1.flowforward

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      interface Feed {
        val source: Flow<Int>
        fun ticks(): Flow<Int>
      }

      class Bin<T>(val seed: T) : Feed {
        override val source: Flow<Int> = flowOf(1)
        override fun ticks(): Flow<Int> = flowOf(2)
        val updates: Flow<T> = flowOf(seed)
        fun flowing(): Flow<T> = flowOf(seed)
      }

      fun feed(): Feed = Bin("Oreo")
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(forwarding.compiledClean, "compileErrors=${forwarding.compileErrors}")
    val bin: String = forwarding.generatedCSharp.substringAfter("public class Bin<T>")
      .substringBefore("\n    }\n")
    assertContains(bin, "KotlinFlow<int> IFeed.Source")
    assertContains(bin, "KotlinFlow<int> IFeed.Ticks()")
    assertContains(bin, "public KotlinFlow<T> Updates")
    assertContains(bin, "public KotlinFlow<T> Flowing()")
    assertFalse("public KotlinFlow<int> Source" in bin, "Source is the interface's alone: $bin")
    assertFalse("public KotlinFlow<int> Ticks()" in bin, "Ticks is the interface's alone: $bin")
    assertContains(forwarding.generated, "asStableRef<tier1.flowforward.Bin<Any?>>().get()")
    assertFalse(forwarding.kspWarnings.any { "Bin." in it }, "warnings=${forwarding.kspWarnings}")
    Tier1CSharpCompile.assertCompiles(
      forwarding,
      """
      using System.Threading.Tasks;
      using Interop;
      class Consumer {
        static async Task<int> Run() {
          await using Bin<string> bin = new Bin<string>("Mylo");
          IFeed feed = bin;
          int total = 0;
          await foreach (int tick in feed.Ticks()) total += tick;
          await foreach (int item in feed.Source) total += item;
          await foreach (string name in bin.Updates) total += name.Length;
          await foreach (string name in bin.Flowing()) total += name.Length;
          return total;
        }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
