# ADR-207: Forward, Flow backpressure: a one-credit gate in `collectForCSharp`, resumed by `nuget_flow_resume` from `MoveNextAsync`

## Status

Proposed

## Context

[ADR-026](026-flow-mapping.md) bridges a Kotlin `Flow<T>` to a C# `IAsyncEnumerable<T>` by pushing
every emission through the `onNext` callback into a C# `Channel<T>`. It deferred backpressure
("bounded `Channel<T>` with explicit resume signaling", ADR-026 Consequences) and chose an
unbounded channel for v1. ROADMAP Phase 6 carries that deferral as this item.

What ships today (all **verified by reading**, 2026-10-08):

- `collectForCSharp` (`nuget-runtime/.../NugetLaunch.kt:63-87`) binds the body's `emit` to a
  **non-suspending** lambda `{ itemRef -> onNext.invoke(itemRef, 0, userData) }` (line 79). The
  Kotlin `collect` resumes the instant the C callback returns.
- The generated enumerator (`cir/CirFlowRenderer.kt:136`) is `Channel.CreateUnbounded<T>(...)`;
  its `onNext` does `TryWrite`, which never blocks; `MoveNextAsync` (`:234-266`) signals nothing
  back to Kotlin.
- Every generated route (`exports/FlowExports.kt:531-581`, `exports/SuspendFunctionExports.kt:407-409`)
  and the runtime's own `nuget_stateflow_collect` (`NugetRuntime.kt:904-932`) call `emit(...)`
  from inside a `collect { }` suspend lambda.

**Verified by spike** (xunit, scratch project against the prebuilt `TestLibrary
1.0.0-fixture.1791371413333` package; that package predates HEAD but `CirFlowRenderer.kt:136`
at HEAD is the same `CreateUnbounded`): `CatFeeder.Treats(20)` emits 20 items with a 50 ms delay
before each. Reading one item, sleeping 2 s, then timing the drain of the rest printed
`SPIKE firstMs=64 rest=19 drainMs=0`: the producer ran to completion while the consumer slept.
Cancelling after one read plus 500 ms printed `rest=8`: eight already-buffered items were still
handed out after the cancel. So the bridge today buffers unbounded, and a slow consumer neither
slows the producer nor limits what it holds.

Kotlin's `emit` suspends for a slow collector; that is the contract a Kotlin author writes
against (`flow { while (true) { emit(expensive()) } }` is fine in Kotlin). Across the bridge it
is a memory leak by design and a surprise for side effects inside the flow body.

Prior art (both **verified by fetching source**):

- KMP-NativeCoroutines `NativeFlow.kt`: `collect { suspendCoroutine { cont -> onItem(it) { cont.resume(Unit) } } }`.
  The producer suspends until Swift invokes the `next` continuation: rendezvous by construction.
- SKIE `SkieColdFlowIterator.kt`: `flow { ... }.buffer(Channel.RENDEZVOUS).produceIn(scope)` pulled
  by a Swift `AsyncIteratorProtocol`. Also rendezvous. (ADR-026:145's "SKIE has no explicit
  backpressure" was mistaken.)

Neither exposes a consumer-side prefetch knob; the Kotlin author adds `.buffer(n)` upstream.

Constraints inherited: the callback trio `onNext/onComplete/onError` is pinned (ADR-026, ADR-102's
AOT thunk table); the runtime's fixed export list is pinned at 67 names (ADR-127, ADR-129,
`ForwardAbiContract.kt:154,655-680`, `Tier1RuntimeVersionTest.kt:43`); `nuget_job_cancel` and
`nuget_job_dispose` cast the collect handle with `asStableRef<Job>()` (`NugetRuntime.kt:537-553`);
row 16l of LeakTests relies on a refused `TryWrite` meaning "channel completed".

## Alternatives Considered

### 1. One-credit gate in `collectForCSharp`, resumed from `MoveNextAsync` through a new `nuget_flow_resume` export (chosen)

Kotlin side: `emit` becomes `suspend`; before each `onNext` it takes a token from a
`Channel<Unit>(capacity = 1)` pre-filled with one token. C# side: after `MoveNextAsync` hands an
item out (`Current = item`), it calls `nuget_flow_resume(jobHandle)`, which `trySend`s one token
back. The collect handle becomes a `NugetFlowCollection : Job by job` carrying the credit channel,
so the shipped cancel/dispose exports keep working and the resume export reaches the gate.

Invariant: **at most one unread item sits in the C# buffer, and the Kotlin flow body runs up to,
and parks inside, the next `emit`** (side effects placed before an `emit` therefore run one step
further than the items the consumer has seen). The first item is produced eagerly (pre-filled
token; collection starts in the enumerator constructor as today), the second after the first is
handed out, and so on.

Pros: no new callback (AOT thunks untouched), no per-member export change, same handle count for
LeakTests, cancel-while-parked falls into the existing cancel arm (`receive()` throws
`CancellationException`), over-resume is a silent no-op (`trySend` on a full channel), prefetch
stays the Kotlin author's `.buffer(n)`. Cons: one extra P/Invoke per item; behaviour change for
consumers (below).

### 2. Bounded C# channel, blocking write in `onNext`

`Channel.CreateBounded<T>(1)` and `WriteAsync(...).AsTask().Wait()` inside the callback. Rejected:
parks a `Dispatchers.Default` thread inside a C callback, deadlocks a single-threaded consumer,
and a refused `TryWrite` could no longer mean "completed" (row 16l).

### 3. Consumer-side prefetch knob (`KotlinFlow<T>.WithPrefetch(n)`, credit N)

Rejected for v1: duplicates `Flow.buffer(n)`, which the Kotlin author already owns; neither
prior-art bridge offers one. Credit 1 is fixed.

### 4. Pull model (SKIE shape): `produceIn` on Kotlin, a `nuget_flow_next` suspend round-trip per item

Rejected: needs a new callback per pull and a rewrite of the enumerator; the credit scheme reuses
the shipped trio and changes `MoveNextAsync` by two lines.

### 5. Keep ADR-026's unbounded channel (strike the item)

Rejected: the spike shows a 50 ms-per-item producer 19 items ahead of a sleeping consumer; for an
infinite or large flow that is unbounded memory, and ADR-026 deferred rather than rejected this.

## Decision

Alternative 1.

### Runtime Kotlin (`nuget-runtime`, `NugetLaunch.kt`)

```kotlin
@NugetRuntimeApi
public class NugetFlowCollection(job: Job, internal val credits: Channel<Unit>) : Job by job

@NugetRuntimeApi
public fun collectForCSharp(
  scope: CoroutineScope, onNextPtr: COpaquePointer, onCompletePtr: COpaquePointer,
  onErrorPtr: COpaquePointer, userData: COpaquePointer, mappedType: (Throwable) -> String?,
  body: suspend (emit: suspend (COpaquePointer?) -> Unit) -> Unit,   // emit is now suspend
): COpaquePointer {
  // ... reinterpret as today ...
  val credits = Channel<Unit>(capacity = 1).also { it.trySend(Unit) }
  val job: Job = scope.launch(start = CoroutineStart.ATOMIC) {
    try {
      body { itemRef -> credits.receive(); onNext.invoke(itemRef, 0.toByte(), userData) }
      onComplete.invoke(userData)
    } catch (e: CancellationException) { onNext.invoke(null, 1.toByte(), userData); throw e }
    catch (e: Throwable) { onError.invoke(NugetHandles.retain(buildError(e, mappedType)), userData) }
  }
  return NugetHandles.retain(NugetFlowCollection(job, credits))
}
```

The cancel and error arms take no credit. This is a change to a public `@NugetRuntimeApi`
signature plus one new public class: binary-incompatible for a stale consumer, but ADR-127
version-locks generator and runtime, so the `NugetRuntimeAbi1` anchor is **not** renamed
(dated amendments to ADR-127 and ADR-128 record it).

**Verified (Tier 1 spike, K2JVMCompiler against kotlinx-coroutines-core 1.10.2, `compiledClean=true`,
no opt-in needed)**: `internal class FlowCollection(job: Job, val credits: Channel<Unit>) : Job by job`
plus a `suspend (Int) -> Unit` emit parked on `credits.receive()` compiles, and the generated
Flow property export calls `emit(itemRef)` inside `obj.belt.collect { value -> ... }`.
**Inferred for the native target**: the Kotlin/Native frontend applies the same delegation and
opt-in rules; not built with konan this session. If `Job by job` fails there, fall back to a side
map `Job -> Channel<Unit>` cleared in `invokeOnCompletion`.

### Runtime C ABI (`NugetRuntime.kt`): the 68th fixed name

```kotlin
@NugetRuntimeApi
@CName("nuget_flow_resume")
public fun export_nuget_flow_resume(handle: COpaquePointer?) {
  handle?.asStableRef<NugetFlowCollection>()?.get()?.credits?.trySend(Unit)
}
```

Runtime-fixed, additive. `nuget_job_cancel`/`nuget_job_dispose` are unchanged: the handle IS-A
`Job`. **Inferred**: `trySend` from a .NET thread is safe (precedent: `nuget_job_cancel` already
calls `job.cancel()` from C# threads, `NugetRuntime.kt:537-543`, verified by reading). **Inferred**:
with the scope on `Dispatchers.Default` (`NugetRuntime.kt:493-495`, verified) the parked producer
resumes on a Kotlin dispatcher thread, not inline inside the resume export; not load-bearing,
since even an inline resume would only run `TryWrite` after `TryRead` has returned. Pins to
update: `ForwardAbiContract.kt:154,655-680` (68), `Tier1RuntimeVersionTest.kt:43`, ADR-127 "66
names", ADR-129 "67th".

### Generated C# (`cir/CirFlowRenderer.kt`, `cir/CirConcurrencyRenderer.kt:90-97`)

`NugetJobNative` gains `[DllImport(..., EntryPoint = "nuget_flow_resume")] internal static extern void Resume(IntPtr handle);`.
In `MoveNextAsync`, after `Current = item;`:

```csharp
IntPtr job = Volatile.Read(ref _jobHandle);
if (job != IntPtr.Zero) NugetJobNative.Resume(job);
return true;
```

The channel stays `CreateUnbounded` (bounded by credits; a refused `TryWrite` keeps its row 16l
meaning). `DisposeAsync` (`CirFlowRenderer.kt:268-290`) is unchanged: it cancels the parked
producer, whose `receive()` throws into the cancel arm. Generated per module; both halves
regenerate together. The Tier 1 stub `tier1/Tier1RuntimeStub.kt:119` mirrors the new signature.
No generated Kotlin text changes.

### Consumer-visible behaviour

- A slow `await foreach` body now delays the Kotlin flow body between emissions (Flow semantics).
- After `CancellationToken` cancel or `break`, at most one already-buffered item is handed out
  (today: everything the producer managed to emit). `FlowTests.cs:66-84` asserts `>= 1`, unaffected.
- Prefetch: `flow.buffer(n)` on the Kotlin side; no C# knob.

## Consequences

- One extra P/Invoke per item on every Flow, StateFlow and suspend-acquired Flow route.
- **Drain hazard**: `nuget_scope_drain` (`NugetRuntime.kt:516-534`, ADR-025) joins every child job.
  An enumerator the consumer stopped reading but never disposed now parks its producer forever,
  so `await using` on the owning wrapper hangs on any flow longer than two items, not only on an
  infinite one. `await foreach` and `await using var e` always dispose the enumerator first; the
  synchronous `Dispose()` cancels and is the escape hatch. Documented, drain unchanged.
- **Abandoned-enumerator leak becomes permanent**, same trigger as the drain hazard: `callbacks.Root()`
  (`CirFlowRenderer.kt:209-213`) is a GCHandle rooting the closures, the enumerator, `_startCollect`
  and so the wrapper and its scope (ADR-187). Today an abandoned finite flow completes on its own
  and `callbacks.Release()` unroots everything; a parked producer never completes, so an enumerator
  obtained by hand from `GetAsyncEnumerator()` and never disposed leaks enumerator, wrapper, scope
  and coroutine for the process lifetime (no finalizer runs behind a GCHandle root). Same mitigation.
- Fixture: `test-library/.../cat/TreatConveyor.kt` with an atomic `emitted` counter incremented
  **after** each `emit` (so it counts emits that returned; incrementing before would read 3 below),
  `belt: Flow<Int>` (no conversion), `crates: Flow<List<String>>` (conversion, row 16l release),
  `spoiled` (throws after three emits). xunit `FlowBackpressureTests.cs`: one read then
  `Emitted == 2` after a delay (item 0 handed out, item 1 buffered, body parked in `emit(2)`;
  today 100), cancel while parked ends cleanly, exception after a stall surfaces as the ADR-177
  mapped type for `IllegalStateException`. LeakTests: collect one crate, park, `break`, baseline
  restored. `CollectForCSharpTest.kt` (nativeTest) adapts to the suspend `emit` and gains a
  stall/resume/cancel test; resume from the test thread after `holder.items.size` grows, not from
  inside the `staticCFunction` `onNext`, which can run before the handle is returned.
- Deferred: credit N > 1 from C#; `SharedFlow<T>` inherits the gate when it lands on
  `collectForCSharp`; the reverse `IAsyncEnumerable<T>` route is already pull-based (ADR-155).
- Amends ADR-026 (backpressure section and the SKIE claim), ADR-127, ADR-128, ADR-129 by dated note.
