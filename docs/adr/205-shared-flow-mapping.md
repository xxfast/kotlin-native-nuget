# ADR-205: Forward `SharedFlow<T>` mapping: the shipped Flow route, hot semantics carried by `SharedFlow.collect`

## Status

Accepted

## Context

ROADMAP Phase 6: "Map `SharedFlow<T>` (hot stream with subscribers – may map to `IAsyncEnumerable<T>`
with replay or `IObservable<T>`)". ADR-026 (`docs/adr/026-flow-mapping.md:617`) and ADR-065
(`docs/adr/065-stateflow-mapping.md:356-357, 473-474`) both deferred `SharedFlow` as an open three-way
choice: `IAsyncEnumerable<T>` with a C#-side replay buffer, `IObservable<T>`, or a custom type.
ADR-071 (`:578-580`) parked `Emit`/`TryEmit`/`ReplayCache`/`SubscriptionCount` with it.

Kotlin's hierarchy is `MutableSharedFlow<T> : SharedFlow<T> : Flow<T>` and
`StateFlow<T> : SharedFlow<T>`. A `SharedFlow` is hot (emits whether or not anyone collects),
multicast, carries a `replay` cache that every new collector receives first, and its `collect`
never completes normally (kotlinx.coroutines contract, **inferred from documentation**).

Today every `SharedFlow` shape is a named skip, nothing is mis-rendered. **Verified by spike**
(Tier 1 harness at `1507feab`, unmodified): a `SharedFlow<Int>` property is
`SKIPPED_UNSUPPORTED_PROPERTY` ("has no property getter or setter shape",
`cir/CirClassTranslator.kt:548-551`), a method return is `SKIPPED_UNSUPPORTED_RETURN` (the
generic-type message), a `suspend` return is `SKIPPED_UNSUPPORTED_RETURN` ("a suspend member can
return ... but not the generic type SharedFlow<Int>", `forward/ForwardLegacyRouteCollections.kt:697-712`).
`Tier1SuspendFlowTest.kt:38, :87` pins the suspend refusal.

The route is gated by `FLOW_TYPES = setOf("kotlinx.coroutines.flow.Flow")`
(`cir/CirTypeMapping.kt:119-121`) at nine sites, and by the bare literal
`"kotlinx.coroutines.flow.Flow"` at six more (`exports/FlowExports.kt:60, :160`,
`exports/ClassExports.kt:59`, `NugetProcessor.kt:3120, :3128, :3481`). **Verified by reading.**

## Alternatives Considered

### 1. Ride the shipped Flow route: `SharedFlow<T>` -> `KotlinFlow<T>` (`IAsyncEnumerable<T>`) (chosen for v1)

Add `SharedFlow`/`MutableSharedFlow` to the Flow type set and fold the six literal sites onto it.
The per-member `_collect` export calls `obj.member.collect { }`, which on a `SharedFlow` replays the
cache and then suspends forever; the C# `KotlinFlowEnumerator<T>` already handles a stream that
never completes (ADR-065 documented exactly this for `StateFlow`, `065:350-355`).

- Pro: zero new mechanism, no C# template change, no runtime ABI change, no C#-side replay state.
  **Verified by spike** (below): all five shapes bind and the generated Kotlin compiles.
- Pro: the same base type as `Flow` and `StateFlow`, mirroring Kotlin's own subtyping and SKIE's
  `AsyncSequence` family.
- Con: no `ReplayCache` or `SubscriptionCount`. SKIE and KMP-NativeCoroutines both expose the replay
  cache (prior art below). Deferred, additive (Alternative 2 is the end state).
- Con: the C# type name does not say "hot". Neither does `IAsyncEnumerable<T>` anywhere in .NET
  (`ChannelReader<T>.ReadAllAsync()` is hot); the topic page documents it, as it does for
  `StateFlow`.

### 2. `KotlinSharedFlow<T> : KotlinFlow<T>` with `IReadOnlyList<T> ReplayCache` (end state, deferred)

The shape both closest analogues converge on. Needs a `KotlinSharedFlow<T>` template class beside
`KotlinStateFlow<T>` (`cir/CirFlowRenderer.kt:300`), a `needsSharedFlow` tracker bit through
`cir/CirClassTranslator.kt:1994, :2132, :2404`, `cir/CirFunctionTranslator.kt:746`,
`cir/CirModel.kt:817`, `cir/CirTranslator.kt:1042-1046`, and a per-member `_replay_cache` export
returning a retained list of boxed elements, read through `NugetMarshal.ReadList(handle, _read)`
(`cir/CirMarshalRenderer.kt:563`) with the element `read` lambda `KotlinFlow<T>` already takes
(`cir/CirFlowRenderer.kt:91`). **Inferred pricing, not spiked.**

- Pro: full prior-art parity.
- Con: a new C# type, a new export shape and a new leak row for a shape no issue has asked for.
- Not foreclosed by Alternative 1: narrowing a return type from `KotlinFlow<T>` to a subclass is
  source-compatible for every consumer, and the package regenerates per module.

### 3. `IObservable<T>`

Rejected for the reasons ADR-026 and ADR-065 already gave: the usable surface (`Subscribe` with
lambdas, `ReplaySubject`) lives in the System.Reactive NuGet dependency, and it would make
`SharedFlow` the one member of the Flow family with a different base type.

### 4. A C#-side replay buffer (ADR-065's sketch)

Unnecessary. Replay is a Kotlin-side property of `SharedFlow.collect`; the bridge sees the replayed
items as ordinary `onNext` callbacks. **Verified by spike** that no C#-side state is generated.

## Decision

Alternative 1 for this item, with Alternative 2 recorded as the end state on a ROADMAP
line naming `ReplayCache`, `SubscriptionCount`, `Emit` and `TryEmit`.

### Consumer API

```kotlin
class CatBulletin {
  private val _headlines = MutableSharedFlow<String>(replay = 1)
  val headlines: SharedFlow<String> = _headlines.asSharedFlow()
  val extras: MutableSharedFlow<Int> = MutableSharedFlow(replay = 1)   // read-only view in v1
  fun editionReport(): SharedFlow<Int> = ...
  suspend fun awaitHeadlines(): SharedFlow<String> = ...
  fun publish(headline: String) { _headlines.tryEmit(headline) }
}
```

```csharp
using var bulletin = new CatBulletin();
bulletin.Publish("Oreo found the treat jar");
var cts = new CancellationTokenSource();
await foreach (var headline in bulletin.Headlines.WithCancellation(cts.Token))
{
    // the replay cache arrives first; a SharedFlow never completes, so bound the loop
    Console.WriteLine(headline);
    cts.Cancel();
}
KotlinFlow<int> extras = bulletin.Extras;                    // MutableSharedFlow, read-only view
KotlinFlow<int> report = bulletin.EditionReport();
KotlinFlow<string> later = await bulletin.AwaitHeadlinesAsync(); // ADR-194 shape, dispose it
```

### Bridge mechanism

Generated-per-module exports only; nothing in the `nuget_*` runtime ABI changes.

- `cir/CirTypeMapping.kt`: `SHARED_FLOW_TYPES = setOf("kotlinx.coroutines.flow.SharedFlow",
  "kotlinx.coroutines.flow.MutableSharedFlow")`, `PLAIN_FLOW_TYPES = setOf("kotlinx.coroutines.flow.Flow")`,
  `FLOW_TYPES = PLAIN_FLOW_TYPES + SHARED_FLOW_TYPES` (the ADR-071 union pattern, so every existing
  `in FLOW_TYPES` consumer is unchanged and a later `KotlinSharedFlow<T>` diff stays local).
  `STATE_FLOW_TYPES` stays separate and is still checked first (ADR-065's ordering rule is untouched:
  a `StateFlow` is also a `SharedFlow` in Kotlin, but detection is on the exact declared
  `qualifiedName`, never `isAssignableFrom`).
- The six literal `"kotlinx.coroutines.flow.Flow"` sites become `in FLOW_TYPES`. The three in
  `exports/FlowExports.kt:60, :160` and `exports/ClassExports.kt:59` are load-bearing: without them
  the Kotlin half is not emitted and `:160`'s `require` would throw. The three in
  `NugetProcessor.kt:3120, :3128, :3481` only drive imports (`nuget_scope_create` lives in the runtime
  klib, `nuget-runtime/.../NugetRuntime.kt:493`); the spike compiled clean without them, so they are
  consistency.
- Property and method: the shipped per-member `_collect` export (`exports/FlowExports.kt:495-560`),
  body `obj.member.collect { value -> emit(box) }` inside `collectForCSharp` (ADR-128). Suspend
  return: the ADR-194 pair, `_async` retaining the `SharedFlow` as `Any` and `_collect` reading it
  back with `flowHandle.asStableRef<Flow<T>>()`, an unchecked reinterpretation that holds because
  `SharedFlow` is-a `Flow` (verified by reading, then by the native `suspend` round trip).
- Element vocabulary (nullable element, collections, enums, value classes, `Throwable` envelope,
  interface elements) is the shared route's, peeled at `ForwardLegacyRouteCollections.kt:907`, so
  ADR-067/123/201's admissions and refusals apply unchanged (**inferred**, the spike's elements were
  `Int`, `String` and a class).

### Spike run for this ADR

Scratch worktree `kn7-E` at `1507feab`, reverted afterwards. Fixture: `val ticks: SharedFlow<Int>`,
`val cats: SharedFlow<Cat>`, `val mutableTicks: MutableSharedFlow<String>`,
`fun tickMethod(): SharedFlow<Int>`, `suspend fun tickSuspend(): SharedFlow<Int>` on a class `Feed`.

Edit: `FLOW_TYPES` widened to `{Flow, SharedFlow, MutableSharedFlow}` plus `FlowExports.kt:60, :160`
and `ClassExports.kt:59` switched from the literal to `in FLOW_TYPES`.

```
./gradlew --no-daemon :nuget-processor:test --tests '*Tier1SharedFlowSpikeTest*'
BUILD SUCCESSFUL in 27s
=== SPIKE compiledClean=true
=== SPIKE compileErrors=[]
=== SPIKE kspWarnings:            (none)
@CName("library_tier1_sharedspike__feed_get_ticks_collect")       obj.ticks.collect { value ->
@CName("library_tier1_sharedspike__feed_get_cats_collect")        obj.cats.collect { value ->
@CName("library_tier1_sharedspike__feed_get_mutableTicks_collect") obj.mutableTicks.collect { value ->
@CName("library_tier1_sharedspike__feed_tickMethod_collect")      obj.tickMethod().collect { value ->
@CName("library_tier1_sharedspike__feed_tickSuspend_async")       val result = obj.tickSuspend()
@CName("library_tier1_sharedspike__feed_tickSuspend_collect")     val flow = flowHandle.asStableRef<Flow<Int>>().get()
public KotlinFlow<int> Ticks
public KotlinFlow<global::Interop.Cat> Cats
public KotlinFlow<string> MutableTicks
public KotlinFlow<int> TickMethod()
public Task<KotlinFlow<int>> TickSuspendAsync(CancellationToken cancellationToken = default)
```

**Verified by that output:** the widening binds all five shapes, the generated Kotlin compiles
against the fixture, and `MutableSharedFlow<T>` binds as a read-only `KotlinFlow<T>` through the
identical seam.

**Confirmed after implementation (2026-10-09):** the real native round trip passed. A property,
a method return and a `suspend` return of `SharedFlow<Int>`, `SharedFlow<Cat>` and a declared
`MutableSharedFlow<String>` replay the cache first, in order, in `IntegrationTests/SharedFlowTests.cs`.
The leak and AOT legs are green. Interface (ADR-174), sealed-arm (ADR-124) and top-level (ADR-194)
owners, and a nullable element `SharedFlow<String?>` binding `KotlinFlow<string?>`, are pinned only
by `Tier1SharedFlowTest` (generated Kotlin compiles, generated C# compiles against a consumer), not
by a native round trip.

### Prior art

- SKIE: `SharedFlow` -> `SkieSwiftSharedFlow`, `MutableSharedFlow` -> `SkieSwiftMutableSharedFlow`,
  `AsyncSequence` (https://skie.touchlab.co/features/flows, **inferred from docs**). Its acceptance
  test `SKIE/acceptance-tests/functional/src/test/resources/tests/coroutines/flow/mapping/subtypes/sharedflow/nonnull/_.swift`
  iterates the flow and asserts `flow.replayCache` (**verified by reading via the GitHub API**);
  the Kotlin side is `class SkieKotlinSharedFlow<out T : Any>(delegate: SharedFlow<T>) : SharedFlow<T> by delegate`.
- KMP-NativeCoroutines: every `Flow` is a `NativeFlow`; "In case of a `SharedFlow` the plugin would
  generate a replay cache property" (README, **inferred from docs**). No `subscriptionCount`.
- Kotlin ObjC/Swift Export: no `Flow` handling, opaque `id` (ADR-026 `:40-43`).
- .NET: `IAsyncEnumerable<T>` carries no hot/cold distinction; `IObservable<T>` rejected per ADR-026.

### Fixture and tests

- `test-library/.../cat/CatBulletin.kt`: `SharedFlow<Int>` and `SharedFlow<Cat>` properties, a
  declared `MutableSharedFlow<String>` (read-only view, `replay = 2`), `fun editionReport():
  SharedFlow<Int>` and `suspend fun latestSightings(): SharedFlow<Cat>`, with a synchronous
  `publish` doing `tryEmit`. Replay is what makes the test deterministic: there is no
  bridge-visible "collector subscribed" signal, so a `replay = 0` subscribe-then-emit test would race.
- `IntegrationTests/SharedFlowTests.cs`: publish first, `await foreach`, assert the replayed items
  arrive first and in order, then `break` or cancel; one test pins that the stream never completes.
- `LeakTests/LiveHandleTests.cs` row 8k, `SharedFlow_AbandonedAfterReplay_ReturnsToBaseline`:
  Row 8's abandoned shape on the hot stream (property and `suspend` return). Row 7's completion
  shape cannot apply, a `SharedFlow` never completes.
- `Tier1SuspendFlowTest.kt`: `shared` moves from the refused list to the bound list. The new
  `Tier1SharedFlowTest` covers property, method, `suspend`, `MutableSharedFlow` view, nullable
  element, and an interface, sealed-arm and top-level owner.

## Consequences

- `SharedFlow<T>` and `MutableSharedFlow<T>` bind at a class property, class method return and
  `suspend` return, and on interface, sealed-arm and top-level `suspend` owners, as `KotlinFlow<T>`
  and `Task<KotlinFlow<T>>`, with the full shared-route element vocabulary. Purely additive: every
  shape was a named skip before.
- `docs/topics/coroutines-and-flow.md` and `docs/topics/supported-features.md` retire the "not
  supported" statements; the topic page documents that a `SharedFlow` enumeration starts with the
  replay cache and never completes on its own, as it does for `StateFlow`.
- ADR-026 and ADR-065 carry dated amendments pointing here; ADR-065's "C#-side replay buffer" sketch
  is retired. ADR-071 and ADR-194 are unchanged and still accurate.
- Deferred, on one ROADMAP line: `ReplayCache` and `SubscriptionCount` on `SharedFlow<T>`, and
  `Emit` / `TryEmit` on `MutableSharedFlow<T>` (Alternative 2's `KotlinSharedFlow<T>`). Nullable
  member `SharedFlow<T>?` binds with `Flow<T>?` (ADR-026 amendment, 2026-10-09); `SharedFlow` as a
  parameter or type
  argument follows the `Flow` parameter / type-argument lines.
