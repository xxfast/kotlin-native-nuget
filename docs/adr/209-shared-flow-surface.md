# ADR-209: `KotlinSharedFlow<T>` with `ReplayCache`, and `KotlinMutableSharedFlow<T>` with `SubscriptionCount`, `EmitAsync` and `TryEmit`

## Status

Accepted

## Context

ADR-205 bound every `SharedFlow<T>` and `MutableSharedFlow<T>` position as `KotlinFlow<T>` and
recorded Alternative 2, a `KotlinSharedFlow<T>` subclass, as the end state. A ROADMAP line asked
for `ReplayCache` and `SubscriptionCount` on `SharedFlow<T>` and `Emit` and `TryEmit` on
`MutableSharedFlow<T>`. ADR-071 had parked the same four members.

Two ADR-205 claims were wrong, and this ADR corrects them:

1. **`subscriptionCount` is not a `SharedFlow<T>` member.** It is declared on
   `MutableSharedFlow<T>`, with `emit`, `tryEmit` and the experimental `resetReplayCache()`;
   `SharedFlow<T>` declares only `replayCache` and `collect`. **Verified by spike** against the
   repo's kotlinx-coroutines 1.10.2 (a Tier 1 negative fixture):

   ```kotlin
   class Probe { val s: SharedFlow<Int> = MutableSharedFlow(replay = 1) }
   fun probeCount(p: Probe): StateFlow<Int> = p.s.subscriptionCount
   ```
   ```
   Unresolved reference 'subscriptionCount'.
   ```

2. **Narrowing `KotlinFlow<T>` to a subclass is not source-compatible for every consumer**
   (ADR-205, Alternative 2). **Verified by spike** (a scratch `net10.0` class library): base-typed
   locals, `await` into the base type, method-group delegates, `IAsyncEnumerable<T>` and
   `await foreach` still compile. Three spellings do not:

   ```
   Task<KotlinFlow<int>> t = b.LatestAsync();       error CS0029 (Task<T> is invariant)
   class MyFeed : IFeed { KotlinFlow<int> Pings }   error CS0738 (C# class implementing a generated interface)
   var e = b.Editions; e = new KotlinFlow<int>();   error CS0266
   ```

   All three are compile errors, never silent. Whether a C# implementer of a `SharedFlow`-bearing
   interface can reach Kotlin at all is **inferred** (ADR-174 verified "no reverse bridge" only for
   an interface with a `suspend` member); the CS0738 break is a compile-time break either way. No
   repo test used these spellings on a `SharedFlow`.

ADR-205 also decided that a declared `MutableSharedFlow<T>` binds the read-only view. This ADR
reverses that.

## Alternatives Considered

### 1. Two per-module subclasses, `KotlinSharedFlow<T>` and `KotlinMutableSharedFlow<T>` (chosen)

`KotlinSharedFlow<T> : KotlinFlow<T>` adds `IReadOnlyList<T> ReplayCache`.
`KotlinMutableSharedFlow<T> : KotlinSharedFlow<T>` adds `KotlinStateFlow<int> SubscriptionCount`,
`Task EmitAsync(T, CancellationToken)` and `bool TryEmit(T)`. This mirrors Kotlin's own split
(point 1 above), SKIE's `SkieSwiftSharedFlow` / `SkieSwiftMutableSharedFlow` (**inferred from
docs**) and the `KotlinStateFlow` / `KotlinMutableStateFlow` pair of ADR-065 and ADR-071.

- Pro: every member Kotlin declares is reachable, on the type Kotlin declares it on.
- Pro: no runtime ABI change; every export is generated per module.
- Con: the three source breaks in point 2.

### 2. `SubscriptionCount` on `KotlinSharedFlow<T>`

Rejected: there is no Kotlin `SharedFlow.subscriptionCount` to call (point 1). A `SharedFlow`-typed
member's runtime object is often a `MutableSharedFlow`, but casting to reach it would surface a
member the Kotlin author deliberately hid behind `asSharedFlow()`.

### 3. Re-parent `KotlinStateFlow<T>` onto `KotlinSharedFlow<T>`

Kotlin has `StateFlow<T> : SharedFlow<T>`. Rejected here: it adds a `_replay_cache` export (and for
`MutableStateFlow`, four more) to every shipped StateFlow member for a surface nothing asks for,
and C# single inheritance cannot give `KotlinMutableStateFlow<T>` both `KotlinStateFlow<T>` and
`KotlinMutableSharedFlow<T>` as bases.

### 4. Runtime-owned `nuget_sharedflow_*` exports keyed on a flow handle

Rejected: it grows the frozen runtime ABI for what generated exports do without it, and the
element projection is per member anyway.

### 5. Re-invoke a `MutableSharedFlow<T>` method return per call (no hold)

Rejected for the reason ADR-071's 2026-09-11 amendment held `MutableStateFlow<T>` method returns:
a write through a re-invoking lambda lands in a throwaway flow.

## Decision

Alternative 1.

### Consumer API

```kotlin
class CatBulletin(private val name: String) {
  val editions: SharedFlow<Int> = _editions.asSharedFlow()
  val headlines: MutableSharedFlow<String> = MutableSharedFlow(replay = 2)
  val moods: MutableSharedFlow<Mood> = MutableSharedFlow(replay = 1)
  fun headlineDesk(): MutableSharedFlow<String> = headlines
  suspend fun awaitHeadlineDesk(): MutableSharedFlow<String> = headlines
}
```

```C#
using var bulletin = new CatBulletin("Oreo");
IReadOnlyList<int> lastEdition = bulletin.Editions.ReplayCache;   // KotlinSharedFlow<int>
KotlinMutableSharedFlow<string> desk = bulletin.Headlines;
await desk.EmitAsync("found the treat jar", cancellationToken);
bool accepted = desk.TryEmit("napped on the keyboard");
using KotlinStateFlow<int> subscribers = desk.SubscriptionCount;
bulletin.Moods.TryEmit(Mood.Grumpy);                              // an enum crosses by ordinal
using KotlinMutableSharedFlow<string> held = bulletin.HeadlineDesk();
using KotlinMutableSharedFlow<string> awaited = await bulletin.AwaitHeadlineDeskAsync();
```

`SharedFlow<T>` is `KotlinSharedFlow<T>` at every position ADR-205 binds (property, method return,
`suspend` return) on every owner it binds (class, interface, sealed arm, object, top-level
`suspend`). A declared `MutableSharedFlow<T>` is `KotlinMutableSharedFlow<T>` when its element has
a write arm (below). A `MutableSharedFlow<T>` narrowed to `SharedFlow<T>` in the declaration stays
`KotlinSharedFlow<T>`.

### C# types

Both classes are generated per module beside `KotlinStateFlow<T>` (the Flow family is already per
module; ADR-178 and ADR-206 give the reason), gated by `includesSharedFlow` and
`includesMutableSharedFlow`. The class owns the machinery and each member supplies small delegates,
as `KotlinFlow<T>` does for `NugetFlowCollectDelegate`.

- `ReplayCache` is `NugetMarshal.ReadList(_readReplayCache(), _read)`, a snapshot read with the
  flow's own element reader, so it covers every element the collect path admits.
- `SubscriptionCount` is a new `KotlinStateFlow<int>` over an owned handle on every read, read
  through the existing ADR-068 `nuget_stateflow_collect` / `nuget_stateflow_value` pair. The caller
  disposes it; an abandoned one is released by the GC (ADR-187).
- `EmitAsync` has the TaskCompletionSource, callback and cancellation shape every `suspend` member
  has. An already-cancelled token returns a cancelled `Task` without crossing. A token cancelled
  while the emit is parked cancels the Kotlin job.
- `TryEmit` is `false` when Kotlin would have to suspend.

### Exports

All generated per module. **No `nuget_*` runtime export is added or changed**: the C# side calls
only existing runtime exports, and the Kotlin side uses the `launchForCSharp` helper.

| Export | Keyed on | When |
|---|---|---|
| `<prefix>_get_<p>_replay_cache` / `<prefix>_<method>_replay_cache` | owner handle (the method's own arguments for a re-invoked return) | every `SharedFlow` member |
| `<prefix>_get_<p>_subscription_count` | owner handle | writable `MutableSharedFlow` property |
| `<prefix>_emit_<p>`, `<prefix>_try_emit_<p>` | owner handle, scope, write slot | writable `MutableSharedFlow` property |
| `<stem>_replay_cache`, `_subscription_count`, `_emit`, `_try_emit`, `_collect` | the flow's own handle | held method return, awaited `suspend` return |

The three synchronous exports (`replay_cache`, `subscription_count`, `try_emit`) carry ADR-030's
`errorOut`, because a getter-backed member or a re-invoked method can throw on every read. `_emit`
reports through its completion callback like every `suspend` export. It unwraps the write slot
before launching, so a failing unwrap (an enum ordinal out of range) faults the `Task` instead of
escaping the `@CName`, and it reads the flow inside the coroutine, so a throwing getter faults the
`Task` too.

A replayed item is projected exactly as the collect path projects an `onNext` item (identity,
nullable guard, ADR-123 collection element, ADR-201 `Throwable` envelope), then minted as a fresh
box by `nuget_list_get` and read by the flow's own reader. No separate replay refusal table exists:
an element the collect path refuses already drops the member with a named skip.

### Positions

| Position | Keying | Notes |
|---|---|---|
| property | owner handle | `_collect` unchanged; new exports beside it |
| method return, `SharedFlow` | owner handle, re-invoked | ADR-065 precedent; `ReplayCache` re-invokes the method like `.Value` |
| method return, writable `MutableSharedFlow` | held flow handle | ADR-071 hold; flow-keyed `_collect`, like ADR-194's |
| `suspend` return | awaited flow handle | already owned (ADR-194) |

An interface owner dispatches through its backing wrapper (ADR-174), including a generic
implementer. The held and awaited flows' `_emit` launches on the parent's scope captured at
construction, the scope their `_collect` uses. **A top-level `suspend` owner has no scope**, so its
`_emit` uses the same ad-hoc `CoroutineScope(Dispatchers.Default)` its `_collect` already uses.
Disposing the owner cancels a parked `EmitAsync` on a property, held or awaited flow of a class
(verified natively for a property). A top-level `suspend` return has no owner scope to cancel
(inferred, not run).

Detecting an awaited plain `Flow` used to depend on the C# spelling prefix `KotlinFlow<`. The
translators now carry a structural `acquiredFlow` flag, so respelling the awaited return as
`KotlinSharedFlow<T>` does not change the route.

### Write arms and named refusals

`EmitAsync` and `TryEmit` take the slot the ADR-071 `.Value` setter takes
(`mutableStateFlowWrite` / `mutableStateFlowWriteSlot`, gated by `isMutableStateFlowElementWritable`):
scalar and `String` by value, enum by ordinal, object by handle, and nullable `String?`, `Int?` and
nullable object elements. `Boolean?`, `Char?` and a nullable enum have no write arm.

A value class element emits by value, through the ADR-071 value-class arm (its 2026-10-10
amendment): over a non-null `String`, a primitive other than `Char`, an enum, or an exported class
or object, C# sends the underlying (`v.Id`) and Kotlin re-wraps it, so its `init` runs on every
emit; a nullable `V?` element emits too. `EmitAsync` and `TryEmit` carry that arm's `default(V)`
guard: a `String` or object underlying throws `ArgumentException` in C# before anything crosses.

A declared `MutableSharedFlow<T>` whose element has no write arm (collections, `ByteArray`,
interfaces, the nullable cases above, and a value class over any other underlying) binds as
`KotlinSharedFlow<T>` and keeps `ReplayCache`. The dropped members are named with
`SKIPPED_UNSUPPORTED_INPUT`: "its EmitAsync, TryEmit and SubscriptionCount are not generated
because a MutableSharedFlow element of X has no write arm", with a hint that the C# property is a
read-only `KotlinSharedFlow` and to expose a function taking X to emit it. A refused value class is
named with its underlying ("of value class X over U has no write arm"). The warning covers class,
sealed-arm, interface and top-level `suspend` owners.

### Declined

- `resetReplayCache()`: experimental in kotlinx, not requested.
- The SharedFlow surface on `KotlinStateFlow<T>` and `KotlinMutableStateFlow<T>` (Alternative 3).
- `EmitAsync` for elements with no write arm (above): parity with the `MutableStateFlow` setter.

### Evidence

**Ran natively** (`IntegrationTests/SharedFlowTests.cs`, `LeakTests`): `ReplayCache` for scalar,
`String` and object elements at property, re-invoked method and awaited positions; `EmitAsync` and
`TryEmit` for `String`, enum and object elements; held and awaited `MutableSharedFlow<String>`;
`SubscriptionCount` rising to 1 while a collector is live and returning to 0; an already-cancelled
`EmitAsync`; a parked `EmitAsync` cancelled by its token and by the owner's `Dispose()`; and
`DisposeAsync` draining a parked emit without hanging.

**Tier 1 only** (generated Kotlin and C# compile, not run natively): `ReplayCache` of collection,
`Throwable`, `ByteArray` and interface elements; nullable-member held and awaited returns;
interface and generic-implementer dispatch; sealed-arm, object and top-level owners; the named
refusals.

### Fixture and tests

- `test-library/.../cat/CatBulletin.kt`: `moods` (enum), `visitors` (object), `pulses` (no replay,
  no buffer: the deterministic parked emit), `headlineDesk()` held, `awaitHeadlineDesk()` suspend,
  and Kotlin read-backs `latestHeadlines()`, `latestMood()`, `latestVisitor()`.
- `IntegrationTests/SharedFlowTests.cs` and `Tier1SharedFlowSurfaceTest.kt` (new); the
  `Tier1SharedFlowTest.kt` spellings respelled.
- `LeakTests/LiveHandleTests.cs` rows 8m to 8r: `SharedFlow_ReplayCache_ObjectElement_ReturnsToBaseline`,
  `MutableSharedFlow_SubscriptionCount_DisposedAndAbandoned_ReturnsToBaseline`,
  `MutableSharedFlow_EmitAsync_CompletedAndCancelled_ReturnsToBaseline`,
  `MutableSharedFlow_ParkedEmitAsync_CancelledByOwnerDispose_ReturnsToBaseline`,
  `MutableSharedFlow_TryEmit_ObjectAndEnumElements_ReturnsToBaseline` and
  `MutableSharedFlow_HeldMethodReturn_DisposedAndAbandoned_ReturnsToBaseline`.

## Consequences

- Every `SharedFlow<T>` position becomes `KotlinSharedFlow<T>` and every declared
  `MutableSharedFlow<T>` with a writable element becomes `KotlinMutableSharedFlow<T>`.
- **Changed (source break for C# consumers of 0.9.0).** Three spellings stop compiling, each as a
  compile error: `Task<KotlinFlow<T>> t = x.FooAsync();` over a `SharedFlow` member (CS0029); a C#
  class implementing a generated interface that spells a `SharedFlow` member `KotlinFlow<T>`
  (CS0738); `var e = x.Flow; e = new KotlinFlow<T>(...)` (CS0266). Spell the new type, or keep
  the base-typed local. There is no changelog file in the repo, so this ADR is the record for the
  release notes.
- A stalled subscriber not on the owner's scope still hangs the drain like any parked `suspend`
  call. A parked emit on the owner's scope does not: `DisposeAsync` cancels the collection first,
  which frees the emit.
- ADR-205 carries a dated amendment: its read-only `MutableSharedFlow` decision is superseded, and
  its `SubscriptionCount` placement and "source-compatible" claims are corrected here. ADR-071's
  parked `Emit`/`TryEmit`/`ReplayCache`/`SubscriptionCount` list now points here.
- No runtime ABI change.
