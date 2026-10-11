package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A flow wrapper built from a property or a re-invoked method return keys its delegates on the
 * OWNER's `_handle`, read lazily: on each collect, `.Value` read or write, `CompareAndSet`,
 * `ReplayCache`, `SubscriptionCount`, `EmitAsync` and `TryEmit`. A wrapper that outlives its owner
 * used to pass the disposed owner's zero handle to Kotlin, which dereferences it. Every such
 * delegate now opens with the guard a disposed owner's methods already use, before any P/Invoke
 * and before anything is minted (the lazy scope, a collection argument's wire handle).
 *
 * The positions that hold the flow's own handle are immune and stay unguarded: a held
 * `MutableStateFlow` / `MutableSharedFlow` method return and every awaited `suspend` return.
 */
class Tier1FlowWrapperOwnerDisposeTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.ownerdispose

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableSharedFlow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.SharedFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      @JvmInline
      value class Tag(val id: String)

      class Cat(val name: String)

      class Desk {
        val tag: MutableStateFlow<Tag> = MutableStateFlow(Tag("oreo-1"))
        val tags: MutableSharedFlow<Tag> = MutableSharedFlow(replay = 1)
        val friend: MutableStateFlow<Cat> = MutableStateFlow(Cat("Mylo"))
        val visitors: MutableSharedFlow<Cat> = MutableSharedFlow(replay = 1)
        val ticks: Flow<Int> = flowOf(1)
        val level: StateFlow<Int> = MutableStateFlow(0)
        val dial: MutableStateFlow<Int> = MutableStateFlow(0)
        val editions: SharedFlow<Int> = MutableSharedFlow(replay = 1)
        val headlines: MutableSharedFlow<String> = MutableSharedFlow(replay = 1)
        fun tickReport(): Flow<Int> = ticks
        fun tagged(tags: List<String>): Flow<Int> = ticks
        fun levelReport(): StateFlow<Int> = level
        fun levelFor(tags: List<String>): StateFlow<Int> = level
        fun editionReport(): SharedFlow<Int> = editions
        fun editionsFor(tags: List<String>): SharedFlow<Int> = editions
        fun heldDial(): MutableStateFlow<Int> = dial
        fun heldDesk(): MutableSharedFlow<String> = headlines
        suspend fun awaitLevel(): StateFlow<Int> = level
        suspend fun awaitDesk(): MutableSharedFlow<String> = headlines
      }

      sealed class Nap {
        class Loaf : Nap() {
          val purrs: MutableStateFlow<Int> = MutableStateFlow(0)
        }
      }

      interface Feed {
        val pings: MutableSharedFlow<Int>
      }

      class LoudFeed : Feed {
        override val pings: MutableSharedFlow<Int> = MutableSharedFlow(replay = 1)
      }

      fun feed(): Feed = LoudFeed()
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private val guard = Regex(
    """if \(_handle\.IsInvalid\) throw new ObjectDisposedException\(nameof\(([\w.]+)\)\);""",
  )

  /** Where a generated flow delegate starts: its parameter list, never a nested `static x =>`. */
  private val lambdaHead = Regex("""(\(\)|\bv|\([A-Za-z_, ]+\)) =>""")

  /**
   * Every lambda body that calls [extern] on the owner's `_handle`, from its parameter list up to
   * the call. The extern's declaration (and a carrier's forwarder) takes `handle`, not `_handle`.
   */
  private fun String.lambdasCalling(extern: String): List<String> =
    Regex("""\b${Regex.escape(extern)}\(_handle""").findAll(this)
      .map { call ->
        val start: Int = lambdaHead.findAll(substring(0, call.range.first)).last().range.first
        substring(start, call.range.first)
      }
      .toList()

  private fun assertGuarded(cs: String, extern: String, owner: String?) {
    val lambdas: List<String> = cs.lambdasCalling(extern)
    assertTrue(lambdas.isNotEmpty(), "expected a call to $extern; generatedCSharp=$cs")
    lambdas.forEach { lambda ->
      val found: MatchResult? = guard.find(lambda)
      assertTrue(found != null, "expected the disposed-owner guard before $extern; got:\n$lambda")
      if (owner != null) assertTrue(found.groupValues[1] == owner, "wrong owner in:\n$lambda")
      // Nothing is minted on a disposed owner: not its lazy scope, not an argument's wire handle.
      val beforeGuard: String = lambda.substring(0, found.range.first)
      assertFalse("GetOrCreateScope()" in beforeGuard, "scope minted before the guard:\n$lambda")
      assertFalse("NugetMarshal." in beforeGuard, "handle minted before the guard:\n$lambda")
      // Receiver state first: a disposed owner answers `ObjectDisposedException` whatever the
      // argument, never the null check's or the value-class `default(V)` check's exception.
      assertFalse("throw new Argument" in beforeGuard, "argument check before the guard:\n$lambda")
    }
  }

  @Test
  fun `the owner guard precedes a write's argument guards, which stay`() {
    val cs: String = result.generatedCSharp
    // ADR-071's value-class arm: `default(Tag)` is refused in C#, after the owner check.
    listOf(
      "Native_SetTagValue", "Native_CompareAndSetTagValue", "Native_EmitTags",
      "Native_TryEmitTags",
    ).forEach { extern ->
      assertGuarded(cs, extern, owner = "Desk")
      cs.lambdasCalling(extern).forEach { lambda ->
        assertContains(lambda, "throw new ArgumentException(\"default(Tag) carries no Id;")
      }
    }
    // A non-null object element: the null check stays, after the owner check.
    listOf(
      "Native_SetFriendValue", "Native_CompareAndSetFriendValue", "Native_EmitVisitors",
      "Native_TryEmitVisitors",
    ).forEach { extern ->
      assertGuarded(cs, extern, owner = "Desk")
      cs.lambdasCalling(extern).forEach { lambda ->
        assertContains(lambda, "throw new ArgumentNullException(")
      }
    }
  }

  @Test
  fun `every owner-keyed property delegate checks the owner before it crosses`() {
    assertTrue(result.compiledClean, "${result.compileErrors} ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    listOf(
      "Native_GetTicksCollect",
      "Native_GetLevelCollect", "Native_GetLevelValue",
      "Native_GetDialCollect", "Native_GetDialValue", "Native_SetDialValue",
      "Native_CompareAndSetDialValue",
      "Native_GetEditionsCollect", "Native_GetEditionsReplayCache",
      "Native_GetHeadlinesCollect", "Native_GetHeadlinesReplayCache",
      "Native_GetHeadlinesSubscriptionCount", "Native_EmitHeadlines", "Native_TryEmitHeadlines",
    ).forEach { extern -> assertGuarded(cs, extern, owner = "Desk") }
    // The MutableSharedFlow's scope delegate feeds `SubscriptionCount`'s collect: it must not mint
    // a scope on a disposed owner either.
    assertFalse("() => GetOrCreateScope()" in cs, "expected a guarded scope delegate; got:\n$cs")
  }

  @Test
  fun `every re-invoked method return delegate checks the owner before it crosses`() {
    val cs: String = result.generatedCSharp
    listOf(
      "Native_TickReportCollect", "Native_TaggedCollect",
      "Native_LevelReportCollect", "Native_LevelReportValue",
      "Native_LevelForCollect", "Native_LevelForValue",
      "Native_EditionReportCollect", "Native_EditionReportReplayCache",
      "Native_EditionsForCollect", "Native_EditionsForReplayCache",
    ).forEach { extern -> assertGuarded(cs, extern, owner = "Desk") }
  }

  @Test
  fun `a sealed arm and an interface owner take the same guard`() {
    val cs: String = result.generatedCSharp
    listOf(
      "Native_GetPurrsCollect", "Native_GetPurrsValue", "Native_SetPurrsValue",
      "Native_CompareAndSetPurrsValue",
      "Native_GetPingsCollect", "Native_GetPingsReplayCache", "Native_EmitPings",
      "Native_TryEmitPings",
    ).forEach { extern -> assertGuarded(cs, extern, owner = null) }
  }

  @Test
  fun `a held or awaited flow keys on its own handle and needs no owner guard`() {
    val cs: String = result.generatedCSharp
    listOf(
      "Native_HeldDialSetValue(owned,",
      "Native_HeldDeskCollect(owned,",
      "Native_HeldDeskReplayCache(owned,",
      "Native_HeldDeskEmit(owned,",
      "Native_HeldDeskTryEmit(owned,",
      "Native_AwaitDeskAsyncTryEmit(flowHandle,",
      "NugetStateFlowNative.Value(flowHandle)",
    ).forEach { call -> assertContains(cs, call) }
  }

  @Test
  fun `the guarded delegates compile`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      using System.Threading.Tasks;
      using global::Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static async Task<int> Run(Desk desk, Nap.Loaf loaf, IFeed feed)
              {
                  desk.Dial.Value = desk.Level.Value + desk.LevelFor(new[] { "a" }).Value;
                  desk.Dial.CompareAndSet(0, 1);
                  IReadOnlyList<int> editions = desk.EditionsFor(new[] { "a" }).ReplayCache;
                  using KotlinStateFlow<int> subscribers = desk.Headlines.SubscriptionCount;
                  await desk.Headlines.EmitAsync("x");
                  loaf.Purrs.Value = 2;
                  feed.Pings.TryEmit(1);
                  await foreach (int tick in desk.Tagged(new[] { "a" })) return tick;
                  return editions.Count;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
