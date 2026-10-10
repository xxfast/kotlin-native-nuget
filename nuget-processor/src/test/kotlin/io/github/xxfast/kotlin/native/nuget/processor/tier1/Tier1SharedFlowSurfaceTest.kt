package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-209: a `SharedFlow<T>` binds as `KotlinSharedFlow<T>` (adding `ReplayCache`) and a declared
 * `MutableSharedFlow<T>` whose element has an ADR-071 write arm as `KotlinMutableSharedFlow<T>`
 * (adding `SubscriptionCount`, `EmitAsync` and `TryEmit`), at every position and owner ADR-205
 * binds: property, method return (read-only re-invoked, mutable held), `suspend` return; class,
 * interface (and a generic implementer forwarding through the `FeedNative` carrier), sealed arm,
 * object and top-level `suspend`.
 *
 * A `MutableSharedFlow<T>` whose element has no write arm binds as `KotlinSharedFlow<T>` and the
 * absent writes are named (`SKIPPED_UNSUPPORTED_INPUT`, the MutableStateFlow setter's family).
 */
class Tier1SharedFlowSurfaceTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.sharedsurface

      import kotlinx.coroutines.flow.MutableSharedFlow
      import kotlinx.coroutines.flow.SharedFlow

      enum class Mood { HAPPY, SLEEPY, GRUMPY }

      class Cat(val name: String)

      @JvmInline
      value class Tag(val id: String)

      class Bulletin {
        val headlines: MutableSharedFlow<String> = MutableSharedFlow(replay = 2)
        val moods: MutableSharedFlow<Mood> = MutableSharedFlow(replay = 1)
        val cats: MutableSharedFlow<Cat> = MutableSharedFlow(replay = 1)
        val maybe: MutableSharedFlow<String?> = MutableSharedFlow(replay = 1)
        val counts: MutableSharedFlow<Int?> = MutableSharedFlow(replay = 1)
        val editions: SharedFlow<Int> = MutableSharedFlow(replay = 1)
        val sightings: SharedFlow<Cat> = MutableSharedFlow(replay = 1)
        val lists: MutableSharedFlow<List<Int>> = MutableSharedFlow(replay = 1)
        val tags: MutableSharedFlow<Tag> = MutableSharedFlow(replay = 1)
        val absent: MutableSharedFlow<Int>? = null
        fun desk(): MutableSharedFlow<String> = headlines
        fun moodDesk(): MutableSharedFlow<Mood> = moods
        fun editionReport(): SharedFlow<Int> = editions
        fun scaled(factor: Int): SharedFlow<Int> = editions
        fun tagged(tags: List<String>): SharedFlow<Int> = editions
        fun taggedDesk(tags: List<String>): MutableSharedFlow<String> = headlines
        fun maybeDesk(): MutableSharedFlow<Int>? = absent
        fun maybeReport(): SharedFlow<Int>? = null
        suspend fun maybeAwait(): MutableSharedFlow<Int>? = absent
        val faults: SharedFlow<Throwable> = MutableSharedFlow(replay = 1)
        val maybeFaults: SharedFlow<Throwable?> = MutableSharedFlow(replay = 1)
        val flags: MutableSharedFlow<Boolean?> = MutableSharedFlow(replay = 1)
        val maybeMoods: MutableSharedFlow<Mood?> = MutableSharedFlow(replay = 1)
        val blobs: SharedFlow<ByteArray> = MutableSharedFlow(replay = 1)
        val feeds: SharedFlow<Feed> = MutableSharedFlow(replay = 1)
        suspend fun awaitDesk(): MutableSharedFlow<String> = headlines
        suspend fun latest(): SharedFlow<Int> = editions
      }

      interface Feed {
        val pings: MutableSharedFlow<Int>
        val echoes: SharedFlow<Int>
        val batches: MutableSharedFlow<List<Int>>
        fun pingDesk(): MutableSharedFlow<Int>
        suspend fun awaitPings(): MutableSharedFlow<Int>
      }

      class LoudFeed : Feed {
        override val pings: MutableSharedFlow<Int> = MutableSharedFlow(replay = 1)
        override val echoes: SharedFlow<Int> = pings
        override val batches: MutableSharedFlow<List<Int>> = MutableSharedFlow(replay = 1)
        override fun pingDesk(): MutableSharedFlow<Int> = pings
        override suspend fun awaitPings(): MutableSharedFlow<Int> = pings
      }

      class Crate<T>(val item: T) : Feed {
        override val pings: MutableSharedFlow<Int> = MutableSharedFlow(replay = 1)
        override val echoes: SharedFlow<Int> = pings
        override val batches: MutableSharedFlow<List<Int>> = MutableSharedFlow(replay = 1)
        override fun pingDesk(): MutableSharedFlow<Int> = pings
        override suspend fun awaitPings(): MutableSharedFlow<Int> = pings
      }

      fun feed(): Feed = LoudFeed()

      fun crate(): Crate<Int> = Crate(1)

      sealed class Nap {
        class Loaf : Nap() {
          val purrs: MutableSharedFlow<Int> = MutableSharedFlow(replay = 1)
          fun purrDesk(): MutableSharedFlow<Int> = purrs
        }
      }

      sealed class Doze {
        object Deep : Doze() {
          suspend fun dozes(): MutableSharedFlow<Int> = MutableSharedFlow(replay = 1)
        }
      }

      suspend fun bulletinWire(): MutableSharedFlow<Int> = MutableSharedFlow(replay = 1)

      suspend fun bulletinEcho(): SharedFlow<Int> = MutableSharedFlow(replay = 1)

      suspend fun bulletinBatches(): MutableSharedFlow<List<Int>> = MutableSharedFlow(replay = 1)
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `a SharedFlow is KotlinSharedFlow and a writable MutableSharedFlow the mutable one`() {
    assertTrue(result.compiledClean, "${result.compileErrors} ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public class KotlinSharedFlow<T> : KotlinFlow<T>")
    assertContains(cs, "public class KotlinMutableSharedFlow<T> : KotlinSharedFlow<T>")
    assertContains(cs, "public IReadOnlyList<T> ReplayCache")
    assertContains(cs, "public KotlinStateFlow<int> SubscriptionCount")
    assertContains(
      cs, "public Task EmitAsync(T value, CancellationToken cancellationToken = default)",
    )
    assertContains(cs, "public bool TryEmit(T value)")
    assertContains(cs, "public KotlinMutableSharedFlow<string> Headlines")
    assertContains(cs, Regex("""public KotlinMutableSharedFlow<[\w.:]*Mood> Moods\b"""))
    assertContains(cs, Regex("""public KotlinMutableSharedFlow<[\w.:]*Cat> Cats\b"""))
    assertContains(cs, "public KotlinMutableSharedFlow<string?> Maybe")
    assertContains(cs, "public KotlinMutableSharedFlow<int?> Counts")
    assertContains(cs, "public KotlinSharedFlow<int> Editions")
    assertContains(cs, "public KotlinMutableSharedFlow<int>? Absent")
    assertContains(cs, "public KotlinMutableSharedFlow<string> Desk()")
    assertContains(cs, "public KotlinSharedFlow<int> EditionReport()")
    assertContains(cs, "public KotlinSharedFlow<int> Scaled(int factor)")
    assertContains(cs, "public Task<KotlinMutableSharedFlow<string>> AwaitDeskAsync(")
    assertContains(cs, "public Task<KotlinSharedFlow<int>> LatestAsync(")
    assertContains(cs, "public static Task<KotlinMutableSharedFlow<int>> BulletinWireAsync(")
    assertContains(cs, "public static Task<KotlinSharedFlow<int>> BulletinEchoAsync(")
    assertContains(cs, "public KotlinMutableSharedFlow<int> Purrs")
    assertContains(cs, "public KotlinMutableSharedFlow<int> PurrDesk()")
    assertContains(cs, "public Task<KotlinMutableSharedFlow<int>> DozesAsync(")
  }

  @Test
  fun `the property exports are keyed on the owner and reuse the write slot`() {
    val kt: String = result.generated
    val prefix = "library_tier1_sharedsurface__bulletin"
    listOf(
      "${prefix}_get_headlines_replay_cache",
      "${prefix}_get_headlines_subscription_count",
      "${prefix}_emit_headlines",
      "${prefix}_try_emit_headlines",
      "${prefix}_get_editions_replay_cache",
    ).forEach { name -> assertContains(kt, "@CName(\"$name\")") }
    assertContains(kt, ".headlines.replayCache.map")
    assertContains(kt, ".headlines.subscriptionCount")
    assertContains(kt, ".headlines.tryEmit(value)")
    assertContains(kt, ".headlines.emit(element)")
    assertContains(kt, ".moods.tryEmit(tier1.sharedsurface.Mood.entries[value])")
    // A read-only SharedFlow gains the replay cache and nothing else.
    assertFalse("${prefix}_try_emit_editions" in kt, "no write for a read-only SharedFlow")
    assertFalse("${prefix}_get_editions_subscription_count" in kt)
  }

  @Test
  fun `a MutableSharedFlow method return is held and a SharedFlow one re-invoked`() {
    val kt: String = result.generated
    val prefix = "library_tier1_sharedsurface__bulletin"
    // Held: one acquire, then every seam keyed on the flow handle it returned.
    assertContains(kt, "@CName(\"${prefix}_desk\")")
    listOf("collect", "replay_cache", "subscription_count", "emit", "try_emit").forEach { seam ->
      assertContains(kt, "@CName(\"${prefix}_desk_$seam\")")
    }
    assertContains(kt, "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableSharedFlow<")
    // The held collect is keyed on the flow, not on the owner (which would re-invoke `desk()`).
    val deskCollect: String = kt.substringAfter("fun export_${prefix}_desk_collect(")
    assertTrue(deskCollect.trimStart().startsWith("flowHandle:"), deskCollect.take(80))
    // Re-invoked: the replay cache re-runs the method with its own arguments.
    assertContains(kt, "@CName(\"${prefix}_scaled_replay_cache\")")
    assertContains(kt, ".scaled(factor).replayCache")
    // Awaited: the suspend export already hands the flow back; the seams key on it.
    listOf("replay_cache", "subscription_count", "emit", "try_emit").forEach { seam ->
      assertContains(kt, "@CName(\"${prefix}_awaitDesk_$seam\")")
    }
    assertContains(kt, "@CName(\"${prefix}_latest_replay_cache\")")
    assertFalse("${prefix}_latest_try_emit" in kt)
  }

  @Test
  fun `an element with no write arm binds read-only and the missing writes are named`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, Regex("""public KotlinSharedFlow<[^\n]*> Lists\b"""))
    assertContains(cs, Regex("""public KotlinSharedFlow<[\w.:]*Tag> Tags\b"""))
    val mutable = Regex("""public KotlinMutableSharedFlow<[^\n]*> (Lists|Tags)\b""")
    assertFalse(mutable.containsMatchIn(cs), "a refused element must not bind the mutable holder")
    val kt: String = result.generated
    assertFalse("try_emit_lists" in kt || "try_emit_tags" in kt, "no write export expected")
    assertContains(kt, "@CName(\"library_tier1_sharedsurface__bulletin_get_lists_replay_cache\")")
    // Every owner ADR-205 binds names it: class, interface (ADR-174) and top-level suspend.
    listOf(
      "Bulletin.lists", "Bulletin.tags", "Bulletin.flags", "Bulletin.maybeMoods", "Feed.batches",
      "bulletinBatches",
    ).forEach { member ->
      assertTrue(
        result.kspWarnings.any { warning ->
          ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name in warning &&
              member in warning && "EmitAsync" in warning
        },
        "expected a named refusal for $member; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  @Test
  fun `interface, generic implementer, sealed arm, object and top-level owners bind`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "KotlinMutableSharedFlow<int> Pings")
    assertContains(cs, "KotlinSharedFlow<int> Echoes")
    assertContains(cs, "KotlinMutableSharedFlow<int> PingDesk()")
    assertContains(cs, "Task<KotlinMutableSharedFlow<int>> AwaitPingsAsync(")
    // ADR-174 ruling 4: the generic implementer reaches the interface's exports through the
    // backing wrapper's carrier, not imports of its own.
    assertContains(cs, "KotlinMutableSharedFlow<int> IFeed.Pings")
    assertContains(cs, "KotlinMutableSharedFlow<int> IFeed.PingDesk()")
    assertContains(cs, "FeedNative.Native_TryEmitPings(")
    assertContains(cs, "FeedNative.Native_PingDeskTryEmit(")
    assertContains(cs, "FeedNative.Native_AwaitPingsAsyncTryEmit(")
    val kt: String = result.generated
    assertContains(kt, "@CName(\"library_tier1_sharedsurface__feed_try_emit_pings\")")
    // ADR-174: the interface's own export reads the receiver as the interface, so dispatch reaches
    // whichever class implements it (`Crate<Int>` included).
    val feedTryEmit: String = kt.substringAfter("__feed_try_emit_pings(").substringBefore("\n}\n")
    assertContains(feedTryEmit, "handle.asStableRef<tier1.sharedsurface.Feed>().get()")
    assertContains(feedTryEmit, "obj.pings.tryEmit(value)")
    assertTrue(
      result.kspWarnings.none { it.contains(".pings:") || it.contains(".purrs:") },
      "expected no skip on the shared owners; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `the generated C# compiles against a consumer of every member on every owner`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using System.Threading;
      using System.Threading.Tasks;
      using global::Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<int> Run(
                  Bulletin bulletin, IFeed feed, Crate<int> crate, Nap.Loaf loaf, Doze.Deep deep,
                  CancellationToken token)
              {
                  KotlinMutableSharedFlow<string> headlines = bulletin.Headlines;
                  IReadOnlyList<string> cache = headlines.ReplayCache;
                  using KotlinStateFlow<int> subscribers = headlines.SubscriptionCount;
                  await headlines.EmitAsync("napped", token);
                  bool accepted = headlines.TryEmit("purred");
                  bulletin.Moods.TryEmit(Mood.Grumpy);
                  await bulletin.Moods.EmitAsync(Mood.Sleepy);
                  IReadOnlyList<Mood> moods = bulletin.Moods.ReplayCache;
                  bulletin.Cats.TryEmit(bulletin.Cats.ReplayCache[0]);
                  bulletin.Maybe.TryEmit(null);
                  bulletin.Counts.TryEmit(null);
                  bulletin.Counts.TryEmit(3);
                  IReadOnlyList<int> editions = bulletin.Editions.ReplayCache;
                  IReadOnlyList<Cat> sightings = bulletin.Sightings.ReplayCache;
                  KotlinSharedFlow<IReadOnlyList<int>> lists = bulletin.Lists;
                  IReadOnlyList<IReadOnlyList<int>> listCache = lists.ReplayCache;
                  IReadOnlyList<Tag> tags = bulletin.Tags.ReplayCache;
                  bulletin.Absent?.TryEmit(1);
                  using KotlinMutableSharedFlow<string> desk = bulletin.Desk();
                  desk.TryEmit("held");
                  using KotlinStateFlow<int> deskSubscribers = desk.SubscriptionCount;
                  await bulletin.MoodDesk().EmitAsync(Mood.Happy, token);
                  IReadOnlyList<int> report = bulletin.EditionReport().ReplayCache;
                  IReadOnlyList<int> scaled = bulletin.Scaled(2).ReplayCache;
                  IReadOnlyList<int> tagged = bulletin.Tagged(new[] { "a" }).ReplayCache;
                  using KotlinMutableSharedFlow<string> taggedDesk =
                      bulletin.TaggedDesk(new[] { "a" });
                  using KotlinMutableSharedFlow<int>? maybeDesk = bulletin.MaybeDesk();
                  IReadOnlyList<int>? maybeReport = bulletin.MaybeReport()?.ReplayCache;
                  using KotlinMutableSharedFlow<int>? maybeAwait = await bulletin.MaybeAwaitAsync();
                  IReadOnlyList<System.Exception> faults = bulletin.Faults.ReplayCache;
                  IReadOnlyList<System.Exception?> maybeFaults = bulletin.MaybeFaults.ReplayCache;
                  KotlinSharedFlow<bool?> flags = bulletin.Flags;
                  KotlinSharedFlow<Mood?> maybeMoods = bulletin.MaybeMoods;
                  IReadOnlyList<byte[]> blobs = bulletin.Blobs.ReplayCache;
                  IReadOnlyList<IFeed> feeds = bulletin.Feeds.ReplayCache;
                  using KotlinMutableSharedFlow<string> awaited = await bulletin.AwaitDeskAsync();
                  await awaited.EmitAsync("awaited");
                  IReadOnlyList<int> latest = (await bulletin.LatestAsync()).ReplayCache;
                  KotlinFlow<int> asBase = bulletin.Editions;
                  feed.Pings.TryEmit(1);
                  await feed.Pings.EmitAsync(2, token);
                  IReadOnlyList<int> pings = feed.Pings.ReplayCache;
                  IReadOnlyList<int> echoes = feed.Echoes.ReplayCache;
                  KotlinSharedFlow<IReadOnlyList<int>> batches = feed.Batches;
                  using KotlinStateFlow<int> feedSubscribers = feed.PingDesk().SubscriptionCount;
                  await (await feed.AwaitPingsAsync()).EmitAsync(3);
                  IFeed crateFeed = crate;
                  crateFeed.Pings.TryEmit(4);
                  crateFeed.PingDesk().TryEmit(5);
                  (await crateFeed.AwaitPingsAsync()).TryEmit(6);
                  loaf.Purrs.TryEmit(1);
                  loaf.PurrDesk().TryEmit(2);
                  (await deep.DozesAsync()).TryEmit(3);
                  using KotlinMutableSharedFlow<int> wire = await Fixture.BulletinWireAsync();
                  await wire.EmitAsync(4);
                  IReadOnlyList<int> echo = (await Fixture.BulletinEchoAsync()).ReplayCache;
                  return accepted ? cache.Count + editions.Count : 0;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
