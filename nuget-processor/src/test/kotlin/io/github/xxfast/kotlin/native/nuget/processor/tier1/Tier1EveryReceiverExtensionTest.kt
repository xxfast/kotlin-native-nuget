package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-238: an extension binds on every receiver, sync and `suspend`, generic or not. Each cell
 * compiles the generated Kotlin, asserts no skip names the member, and pins the C# spelling;
 * the last compiles the C# with a consumer calling every shape.
 */
class Tier1EveryReceiverExtensionTest {

  private val source: String =
    """
    package tier1.everyreceiver

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf
    import kotlinx.coroutines.flow.asFlow

    class Bindle<T>(var top: T)

    interface Dozer {
      val name: String
    }

    class Loafer(override val name: String) : Dozer

    enum class Drowse { CATNAP, DEEP }

    fun Bindle<*>.isPacked(): Boolean = top != null

    fun List<*>.headcount(): Int = size

    val <T> Bindle<T>.peek: T get() = top

    var <T> Bindle<T>.lid: T
      get() = top
      set(value) {
        top = value
      }

    val Bindle<*>.packed: Boolean get() = top != null

    val List<*>.rollCall: Int get() = size

    val <T : Dozer> T.badge: String get() = "badge:${'$'}name"

    val <T> T.label: String get() = "label:${'$'}this"

    val ByteArray.crumbTotal: Int get() = sum()

    val ByteArray?.isBlank: Boolean get() = this == null || isEmpty()

    val List<Int>?.tallyOrZero: Int get() = this?.sum() ?: 0

    suspend fun Bindle<*>.isPackedLater(): Boolean = top != null

    suspend fun List<*>.headcountLater(): Int = size

    suspend fun Drowse.deepen(): Drowse = Drowse.DEEP

    suspend fun Drowse?.loudly(): String = this?.name ?: "quiet"

    suspend fun Loafer.watch(count: Int): Flow<Int> = flowOf(count)

    fun <T> Bindle<T>.drip(): Flow<T> = flowOf(top)

    fun Loafer?.ticksOrNone(): Flow<String> = flowOf(this?.name ?: "nobody")

    fun List<Int>.stream(): Flow<Int> = asFlow()

    fun <T> Bindle<T>.relay(events: Flow<T>): Flow<T> = events

    fun Loafer?.hears(events: Flow<Int>): String = if (this == null) "nobody" else name

    class Groomhand(val name: String) {
      fun Loafer.brush(strokes: Int): String = name + strokes

      suspend fun Loafer.brushLater(): String = name

      fun Loafer.tickle(count: Int): Flow<Int> = flowOf(count)
    }
    """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  private val members: List<String> = listOf(
    "isPacked", "headcount", "peek", "lid", "packed", "rollCall", "badge", "label",
    "crumbTotal", "isBlank", "tallyOrZero", "isPackedLater", "headcountLater", "deepen", "loudly",
    "watch", "drip", "ticksOrNone", "stream", "relay", "hears", "brush", "tickle",
    "brushLater",
  )

  @Test
  fun `every receiver binds with no skip and compiles`() {
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val named: List<String> = result.kspWarnings.filter { warning ->
      "tier1.everyreceiver" in warning && "SKIPPED_" in warning
    }
    assertTrue(named.isEmpty(), "no member may be skipped; named=$named")
    members.forEach { member ->
      assertTrue(
        result.kspWarnings.none { warning -> ".$member:" in warning },
        "$member must bind; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `a star projection receiver gets a synthesized method type parameter`() {
    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "public static bool IsPacked<TBindle>(this global::Interop.Bindle<TBindle> receiver)",
    )
    assertContains(cs, "public static int Headcount<TList>(this IReadOnlyList<TList> receiver)")
    assertContains(cs, "extension<TBindle>(global::Interop.Bindle<TBindle> receiver)")
    assertContains(cs, "extension<TList>(IReadOnlyList<TList> receiver)")
    assertContains(
      cs,
      "public static Task<bool> IsPackedLaterAsync<TBindle>(" +
        "this global::Interop.Bindle<TBindle> receiver",
    )
    assertContains(
      cs,
      "public static Task<int> HeadcountLaterAsync<TList>(this IReadOnlyList<TList> receiver",
    )
    assertContains(result.generated, ".asStableRef<tier1.everyreceiver.Bindle<*>>()")
  }

  @Test
  fun `a generic or bare type parameter extension property is a generic extension block`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "extension<T>(global::Interop.Bindle<T> receiver)")
    assertContains(cs, "extension<T>(T receiver) where T : global::Interop.IDozer")
    assertContains(cs, "public static partial class ObjectExtensions")
    assertContains(cs, "public static partial class DozerExtensions")
  }

  @Test
  fun `an enum receiver on the suspend route is read back from its ordinal`() {
    assertContains(result.generated, "tier1.everyreceiver.Drowse.entries[receiver]")
    // A nullable one is rebuilt from its has-value pair, parenthesised before the call.
    assertContains(
      result.generated,
      "(if (receiverHasValue) tier1.everyreceiver.Drowse.entries[receiver] else null).",
    )
  }

  @Test
  fun `a suspend extension returning a Flow binds on the static collect route`() {
    assertContains(
      result.generatedCSharp,
      "public static Task<KotlinFlow<int>> WatchAsync(" +
        "this global::Interop.Loafer receiver, int count",
    )
  }

  @Test
  fun `a sync extension returning a Flow binds on the static flow route on a generic receiver`() {
    assertContains(
      result.generatedCSharp,
      "public static KotlinFlow<T> Drip<T>(this global::Interop.Bindle<T> receiver)",
    )
    assertContains(
      result.generatedCSharp,
      "public static KotlinFlow<int> Stream(this IReadOnlyList<int> receiver)",
    )
  }

  @Test
  fun `a sync extension returning a Flow binds on a nullable receiver`() {
    assertContains(
      result.generatedCSharp,
      "public static KotlinFlow<string> TicksOrNone(this global::Interop.Loafer? receiver)",
    )
  }

  @Test
  fun `a Flow parameter on a sync extension binds on a generic and a nullable receiver`() {
    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "public static KotlinFlow<T> Relay<T>(this global::Interop.Bindle<T> receiver, " +
        "global::System.Collections.Generic.IAsyncEnumerable<T> events)",
    )
    assertContains(
      cs,
      "public static string Hears(this global::Interop.Loafer? receiver, " +
        "global::System.Collections.Generic.IAsyncEnumerable<int> events)",
    )
  }

  @Test
  fun `a member extension is an instance method taking the receiver first`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "public string Brush(global::Interop.Loafer receiver, int strokes)")
    assertContains(cs, "public Task<string> BrushLaterAsync(global::Interop.Loafer receiver")
    // A member extension returning a `Flow` too, on the class flow route.
    assertContains(cs, "public KotlinFlow<int> Tickle(global::Interop.Loafer receiver, int count)")
    assertContains(
      result.generated,
      ".asStableRef<tier1.everyreceiver.Loafer>().get().brush(strokes) }",
    )
  }

  @Test
  fun `a generic interface receiver binds an extension property as it does a function`() {
    val shelf: Tier1Result = Tier1Harness.run(
      """
      package tier1.shelfreceiver

      interface Shelf<T> {
        fun top(): T
      }

      val Shelf<Int>.label: String get() = "x" + top()
      """.trimIndent(),
    )

    assertTrue(shelf.compiledClean, "compileErrors=${shelf.compileErrors}")
    assertTrue(shelf.kspWarnings.none { "label" in it }, "kspWarnings=${shelf.kspWarnings}")
    assertContains(shelf.generatedCSharp, "extension(global::Interop.IShelf<int> receiver)")
  }

  @Test
  fun `the C# half compiles against a consumer calling every shape`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using System.Threading.Tasks;
      using Interop;
      using Kotlin.Native.Interop;

      public static class Consumer
      {
          public static async Task<int> Run(Bindle<int> bindle, Loafer loafer, Groomhand hand)
          {
              byte[]? none = null;
              List<int>? noTally = null;
              bindle.Lid = bindle.Peek + 1;
              int total = (bindle.IsPacked() ? 1 : 0) + new List<string>().Headcount() +
                  (bindle.Packed ? 1 : 0) + new[] { 1 }.RollCall + new byte[] { 1 }.CrumbTotal +
                  (none.IsBlank ? 1 : 0) + noTally.TallyOrZero + loafer.Badge.Length +
                  42.Label.Length + hand.Brush(loafer, 3).Length;
              total += (await bindle.IsPackedLaterAsync()) ? 1 : 0;
              total += await new List<string>().HeadcountLaterAsync();
              total += (int)await Drowse.Catnap.DeepenAsync();
              total += (await hand.BrushLaterAsync(loafer)).Length;
              using KotlinFlow<int> flow = await loafer.WatchAsync(2);
              using KotlinFlow<int> tickle = hand.Tickle(loafer, 1);
              Loafer? nobody = null;
              using KotlinFlow<int> drip = bindle.Drip();
              using KotlinFlow<string> nobodyTicks = nobody.TicksOrNone();
              using KotlinFlow<int> stream = new List<int> { 1 }.Stream();
              using KotlinFlow<int> relay = bindle.Relay(Empty());
              total += nobody.Hears(Empty()).Length;
              return total;
          }

          private static async IAsyncEnumerable<int> Empty()
          {
              await Task.Yield();
              yield break;
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
