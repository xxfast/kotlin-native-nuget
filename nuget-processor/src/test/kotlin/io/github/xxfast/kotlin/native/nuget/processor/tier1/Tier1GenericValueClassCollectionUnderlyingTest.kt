package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * A generic value class over a collection of its own type parameter
 * (`value class Bundle<T>(val items: List<T>)`, and `Set<T>`, `Map<String, T>`, `List<T>?`,
 * `List<T?>`) renders as `readonly record struct Bundle<T>(IReadOnlyList<T> Items)` and binds at
 * every position a non-generic collection value class (ADR-235) and `Sticker<T>` (ADR-220, 225,
 * 226, 228) bind: the substituted collection crosses on ADR-212's element wire.
 *
 * One cell per position; each pins that the member binds, and a consumer touching every member
 * compiles against the generated `Interop.cs`.
 */
class Tier1GenericValueClassCollectionUnderlyingTest {

  private val fixture: String = """
    package tier1.bundles

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.asFlow
    import kotlinx.coroutines.flow.flowOf

    class Cat(val name: String)

    class Box<T>(val item: T)

    @JvmInline
    value class Bundle<T>(val items: List<T>) {
      init {
        require(items.size <= 3) { "too many" }
      }

      constructor(item: T, times: Int) : this(List(times) { item })

      val count: Int get() = items.size

      fun first(): T = items.first()

      fun plus(item: T): Bundle<T> = Bundle(items + item)

      suspend fun countLater(): Int = items.size

      fun each(): Flow<T> = items.asFlow()
    }

    @JvmInline
    value class Pile<T>(val items: Set<T>)

    @JvmInline
    value class Ledger<T>(val entries: Map<String, T>)

    @JvmInline
    value class MaybeBundle<T>(val items: List<T>?)

    @JvmInline
    value class Gappy<T>(val items: List<T?>)

    fun pack(items: List<Int>): Bundle<Int> = Bundle(items)
    fun total(bundle: Bundle<Int>): Int = bundle.items.sum()
    fun names(bundle: Bundle<String>): Bundle<String> = Bundle(bundle.items.map { it.uppercase() })
    fun cats(bundle: Bundle<Cat>): String = bundle.items.joinToString { it.name }
    fun maybePack(present: Boolean): Bundle<Int>? = if (present) Bundle(listOf(1)) else null
    fun maybeTotal(bundle: Bundle<Int>?): Int = bundle?.items?.sum() ?: -1
    fun packs(bundles: List<Bundle<Int>>): List<Bundle<Int>> = bundles
    fun pile(items: Set<String>): Pile<String> = Pile(items)
    fun pileSize(pile: Pile<String>): Int = pile.items.size
    fun ledger(entries: Map<String, Int>): Ledger<Int> = Ledger(entries)
    fun ledgerSum(ledger: Ledger<Int>): Int = ledger.entries.values.sum()
    fun maybeBundle(present: Boolean): MaybeBundle<Int> = MaybeBundle(if (present) listOf(1) else null)
    fun maybeBundleSize(bundle: MaybeBundle<Int>): Int = bundle.items?.size ?: -1
    fun maybeMaybe(present: Boolean): MaybeBundle<Int>? = if (present) MaybeBundle(null) else null
    fun maybeMaybeState(bundle: MaybeBundle<Int>?): String =
      if (bundle == null) "absent" else if (bundle.items == null) "empty" else "full"
    fun gappy(): Gappy<Int> = Gappy(listOf(1, null))
    fun gaps(gappy: Gappy<Int>): Int = gappy.items.count { it == null }
    fun maybes(bundles: List<MaybeBundle<Int>?>): List<MaybeBundle<Int>?> = bundles

    fun <T> wrap(items: List<T>): Bundle<T> = Bundle(items)
    fun <T> unwrap(bundle: Bundle<T>): List<T> = bundle.items

    fun boxed(): Box<Bundle<Int>> = Box(Bundle(listOf(1, 2)))
    fun unboxed(box: Box<Bundle<Int>>): Int = box.item.items.sum()
    fun mapped(bundle: Bundle<Int>, block: (Bundle<Int>) -> Bundle<Int>): Bundle<Int> = block(bundle)
    fun stream(): Flow<Bundle<Int>> = flowOf(Bundle(listOf(1)), Bundle(listOf(2, 3)))
    suspend fun later(): Bundle<Int> = Bundle(listOf(4))
    suspend fun totalLater(bundle: Bundle<Int>): Int = bundle.items.sum()

    class Bundler(var current: Bundle<String>) {
      val board: MutableStateFlow<Bundle<Int>> = MutableStateFlow(Bundle(listOf(0)))
      var spare: Bundle<Int>? = null
      var maybeSpare: MaybeBundle<Int>? = null
    }
  """.trimIndent()

  private val consumer: String = """
    using System.Collections.Generic;
    using System.Threading.Tasks;
    using Tier1;
    public static class Consumer
    {
        public static async Task Touch(Bundler bundler, Cat cat)
        {
            Bundle<int> packed = Bundles.Pack(new[] { 1, 2 });
            IReadOnlyList<int> items = packed.Items;
            int total = Bundles.Total(new Bundle<int>(new[] { 1, 2 }));
            Bundle<string> names = Bundles.Names(new Bundle<string>(new[] { "oreo" }));
            string cats = Bundles.Cats(new Bundle<Cat>(new[] { cat }));
            Bundle<int>? maybe = Bundles.MaybePack(true);
            int maybeTotal = Bundles.MaybeTotal(null);
            IReadOnlyList<Bundle<int>> packs = Bundles.Packs(new[] { packed });
            Pile<string> pile = Bundles.Pile(new HashSet<string> { "mylo" });
            IReadOnlySet<string> pileItems = pile.Items;
            int pileSize = Bundles.PileSize(pile);
            Ledger<int> ledger = Bundles.Ledger(new Dictionary<string, int> { ["oreo"] = 1 });
            int ledgerSum = Bundles.LedgerSum(ledger);
            MaybeBundle<int> maybeBundle = Bundles.MaybeBundle(false);
            int maybeBundleSize = Bundles.MaybeBundleSize(new MaybeBundle<int>(null));
            MaybeBundle<int>? maybeMaybe = Bundles.MaybeMaybe(true);
            string maybeMaybeState = Bundles.MaybeMaybeState(new MaybeBundle<int>(null));
            Gappy<int?> gappy = Bundles.Gappy();
            IReadOnlyList<int?> gappyItems = gappy.Items;
            int gaps = Bundles.Gaps(new Gappy<int?>(new int?[] { null }));
            Bundle<string> wrapped = Bundles.Wrap<string>(new[] { "oreo" });
            IReadOnlyList<string> unwrapped = Bundles.Unwrap<string>(wrapped);
            using Box<Bundle<int>> boxed = Bundles.Boxed();
            int unboxed = Bundles.Unboxed(boxed);
            Bundle<int> mapped = Bundles.Mapped(packed, b => b);
            await foreach (Bundle<int> b in Bundles.Stream()) { }
            Bundle<int> later = await Bundles.LaterAsync();
            int totalLater = await Bundles.TotalLaterAsync(packed);
            int count = packed.Count;
            int first = packed.First();
            Bundle<int> plus = packed.Plus(3);
            Bundle<string> repeated = new Bundle<string>("oreo", 2);
            int countLater = await packed.CountLaterAsync();
            await foreach (int each in packed.Each()) { }
            bundler.MaybeSpare = new MaybeBundle<int>(null);
            MaybeBundle<int>? maybeSpare = bundler.MaybeSpare;
            IReadOnlyList<MaybeBundle<int>?> maybes = Bundles.Maybes(new MaybeBundle<int>?[] { null, new MaybeBundle<int>(null) });
            bundler.Current = names;
            Bundle<string> current = bundler.Current;
            bundler.Board.Value = packed;
            bundler.Spare = null;
            Bundle<int>? spare = bundler.Spare;
            Bundler made = new Bundler(names);
        }
    }
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "Bundles.kt",
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private val code: String by lazy { result.generatedCSharp.withoutDocComments() }

  private fun assertBound(vararg members: String) {
    val skipped: List<String> = result.kspWarnings.filter { warning -> "[nuget:" in warning }
    members.forEach { member ->
      val named: List<String> = skipped.filter { Regex("""[ .]$member: """).containsMatchIn(it) }
      assertTrue(named.isEmpty(), "expected $member to bind; got: $named")
    }
  }

  @Test
  fun `the Kotlin half compiles clean`() {
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(
      result.compiledClean,
      "expected the fixture to compile; got: ${result.compileErrors}",
    )
  }

  @Test
  fun `the struct is a positional generic record struct over the collection spelling`() {
    assertContains(code, "public readonly record struct Bundle<T>(IReadOnlyList<T> Items)")
    assertContains(code, "public readonly record struct Pile<T>(IReadOnlySet<T> Items)")
    assertContains(
      code,
      "public readonly record struct Ledger<T>(IReadOnlyDictionary<string, T> Entries)",
    )
    assertContains(code, "public readonly record struct MaybeBundle<T>(IReadOnlyList<T>? Items)")
    assertContains(code, "public readonly record struct Gappy<T>(IReadOnlyList<T?> Items)")
  }

  @Test
  fun `a closed instantiation binds as a parameter and a result`() {
    assertBound("pack", "total", "names", "cats", "pile", "pileSize", "ledger", "ledgerSum")
    assertBound("maybeBundle", "maybeBundleSize", "gappy", "gaps")
  }

  @Test
  fun `a closed instantiation binds under a nullable outer`() {
    assertBound("maybePack", "maybeTotal")
  }

  @Test
  fun `a closed instantiation binds as a collection element`() {
    assertBound("packs")
  }

  @Test
  fun `a function's own T binds inside the struct`() {
    assertBound("wrap", "unwrap")
  }

  @Test
  fun `a property and a constructor parameter bind`() {
    assertBound("current", "spare", "Bundler")
  }

  @Test
  fun `own members and secondary constructors bind`() {
    assertBound("count", "first", "plus", "countLater", "<init>_2")
  }

  @Test
  fun `erased positions bind through the box pair`() {
    assertBound("boxed", "unboxed", "mapped", "stream", "later", "board")
    assertContains(result.generated, "_bundle_box\")")
    assertContains(result.generated, "_bundle_unbox\")")
  }

  @Test
  fun `a suspend parameter binds`() {
    assertBound("totalLater")
  }

  @Test
  fun `no member of the fixture skips`() {
    val skipped: List<String> =
      result.kspWarnings.filter { warning -> "[nuget:SKIPPED_" in warning }
    assertTrue(skipped.isEmpty(), "expected every member bound; skipped=$skipped")
  }

  @Test
  fun `a consumer of every position compiles against the generated C#`() {
    val build: Tier1CSharpBuild = Tier1CSharpCompile.compile(result, consumer, allowUnsafe = true)
    assertTrue(build.succeeded, "expected the consumer to compile; log:\n${build.log}")
  }
}
