package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-219: the `suspend` members of a generic class and a generic sealed arm bind on the suspend
 * route over the erased receiver (`Platter<Any?>`, `Picnic<*>`), the Flow route's spelling
 * (ADR-215). A multi-bound owner `T` parameter and a trampolined own `T` (ADR-198) run the whole
 * export, launch included, inside the trampoline: the box is read before the launch, the checked
 * cast inside it (ADR-214's faulted-Task rule). A generic sealed base keeps ADR-147's refusal.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without the
 * coroutines library on the KSP path every suspend member here is skipped and the assertions on
 * absence pass vacuously.
 */
class Tier1GenericOwnerSuspendTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.ownersuspend

      interface Furry { val name: String }
      interface Tricky { val tricks: Int }
      enum class Medal { BRONZE, SILVER, GOLD }

      class Platter<T>(var served: T) {
        suspend fun take(): T = served
        suspend fun put(item: T) { served = item }
        suspend fun <M : Enum<M>> rate(medal: M): M = medal
      }

      class Banquet<T : Furry>(val guest: T) {
        suspend fun greet(other: T): String = other.name
      }

      class Picnic<T>(val host: T) where T : Furry, T : Tricky {
        suspend fun host(): T = host
        suspend fun invite(guest: T): String = guest.name
      }

      suspend fun <T : Enum<T>> rateLater(medal: T): T = medal

      sealed class Buffet<out T> {
        suspend fun peek(): Int = 0
        class Tureen<T>(val ladle: T) : Buffet<T>() {
          suspend fun serve(): T = ladle
        }
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `a generic class's suspend members bind over the erased receiver on both halves`() {
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    assertContains(result.generated, "handle.asStableRef<tier1.ownersuspend.Platter<Any?>>().get()")
    assertContains(
      result.generated,
      "handle.asStableRef<tier1.ownersuspend.Banquet<tier1.ownersuspend.Furry>>().get()",
    )
    assertContains(
      result.generatedCSharp,
      "public Task<T> TakeAsync(CancellationToken cancellationToken = default)",
    )
    assertContains(
      result.generatedCSharp,
      "public Task PutAsync(T item, CancellationToken cancellationToken = default)",
    )
    assertContains(
      result.generatedCSharp,
      "public Task<string> GreetAsync(T other, CancellationToken cancellationToken = default)",
    )
    assertContains(result.generatedCSharp, "nameof(Platter<T>)")
    assertContains(
      result.generatedCSharp,
      "public class Platter<T> : IDisposable, IAsyncDisposable",
    )
    listOf("Platter.take", "Platter.put", "Banquet.greet", "Picnic.host", "Picnic.invite")
      .forEach { member ->
        assertFalse(result.kspWarnings.any { "$member:" in it }, "warnings=${result.kspWarnings}")
      }
  }

  @Test
  fun `a multi-bound owner T reads the star receiver and trampolines a parameter`() {
    assertContains(result.generated, "handle.asStableRef<tier1.ownersuspend.Picnic<*>>().get()")
    val invite: String =
      result.generated.substringAfter("picnic_invite_async\")")
        .substringBefore("\n}\n")
    assertContains(
      invite,
      "fun <T> nugetTrampoline(): kotlinx.cinterop.COpaquePointer " +
        "where T : tier1.ownersuspend.Furry, " +
        "T : tier1.ownersuspend.Tricky",
    )
    assertContains(invite, "as tier1.ownersuspend.Picnic<T>)")
    // The box is read on the caller's thread; the checked cast faults the Task from inside it.
    assertTrue(
      invite.indexOf("guest?.asStableRef<Any>()?.get()") < invite.indexOf("launchForCSharp("),
      "expected the box read before the launch: $invite",
    )
    assertTrue(
      invite.indexOf("bounded as T") > invite.indexOf("launchForCSharp("),
      "expected the cast inside the launch: $invite",
    )
    assertContains(
      result.generatedCSharp,
      "public Task<string> InviteAsync(T guest, CancellationToken cancellationToken = default)",
    )
  }

  @Test
  fun `a trampolined T parameter binds on the suspend route, member and top level`() {
    val rate: String =
      result.generated.substringAfter("platter_rate_async\")")
        .substringBefore("\n}\n")
    assertContains(
      rate,
      "fun <M> nugetTrampoline(): kotlinx.cinterop.COpaquePointer where M : kotlin.Enum<M>",
    )
    assertTrue(
      rate.indexOf("bounded as M") > rate.indexOf("launchForCSharp("),
      "expected the cast inside the launch: $rate",
    )
    val later: String =
      result.generated.substringAfter("__rateLater_async\")")
        .substringBefore("\n}\n")
    assertContains(
      later,
      "fun <T> nugetTrampoline(): kotlinx.cinterop.COpaquePointer where T : kotlin.Enum<T>",
    )
    assertContains(result.generatedCSharp, "RateAsync<M>(")
    assertContains(result.generatedCSharp, "RateLaterAsync<T>(")
    assertFalse(
      result.kspWarnings.any { "rateLater" in it || "Platter.rate" in it },
      "warnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a generic arm's suspend members bind and a generic sealed base's stay refused`() {
    assertContains(result.generated, "asStableRef<tier1.ownersuspend.Buffet.Tureen<Any?>>().get()")
    assertContains(
      result.generatedCSharp,
      "public Task<T> ServeAsync(CancellationToken cancellationToken = default)",
    )
    assertFalse("PeekAsync(" in result.generatedCSharp, "the generic base keeps ADR-147's refusal")
    assertFalse("buffet_peek_async" in result.generated, "no Kotlin export for the base's peek")
  }

  /**
   * The ROADMAP Phase 6 build break (`class Hot<T> : Dish<T>(), Feed`), the probe its backlog note
   * describes: the arm's public `FetchAsync` implements `IFeed.FetchAsync` and the arm owns the
   * scope, so neither CS0535 remains, and the member binds rather than dropping silently.
   */
  @Test
  fun `a generic arm implementing an exported suspend interface compiles in C#`() {
    val dish: Tier1Result = Tier1Harness.run(
      """
      package tier1.hotdish

      interface Feed { suspend fun fetch(): Int }

      sealed class Dish<out T> {
        class Hot<T>(val item: T) : Dish<T>(), Feed {
          override suspend fun fetch(): Int = 1
        }
      }

      fun feed(): Feed = Dish.Hot("Oreo")
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(dish.compiledClean, "compileErrors=${dish.compileErrors}")
    assertFalse(dish.kspWarnings.any { "Hot.fetch" in it }, "warnings=${dish.kspWarnings}")
    Tier1CSharpCompile.assertCompiles(
      dish,
      """
      using System.Threading.Tasks;
      using Interop;
      class Consumer {
        static async Task<int> Run() {
          await using Dish.Hot<string> hot = new Dish.Hot<string>("Mylo");
          IFeed feed = hot;
          return await hot.FetchAsync() + await feed.FetchAsync();
        }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
