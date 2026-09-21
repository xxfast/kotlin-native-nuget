# ADR-156: Reverse, a C# `IAsyncEnumerable<T>` method binds as a Kotlin `Flow<T>` that pulls `MoveNextAsync` over ADR-152's begin/end pair

## Status

Accepted

## Context

A Kotlin consumer calling a bound C# method (plain bound class, static or instance) that returns
`IAsyncEnumerable<T>` gets a cold `Flow<T>`: each `collect` runs one enumeration, collector
cancellation stops the C# enumeration, and a C# throw mid-stream surfaces on the collector as
`NugetManagedException` ([ADR-104](104-reverse-thunk-error-channel.md)), not a host abort.
Direction: reverse (C# declares, Kotlin consumes).

The ROADMAP line calls this the mirror of [ADR-026](026-flow-mapping.md). ADR-026 is push: native
`collect` calls `onNext`/`onComplete`/`onError` into a C# `Channel`. The literal mirror would be a
generated C# pump doing `await foreach` and calling back into Kotlin once per element.

What the reverse machinery supports today (all **verified by reading**, 2026-09-21, branch `main`):

- The only managed-to-Kotlin completion path is one-shot. `nugetTaskCompleted` releases the `ctx`
  `StableRef` the moment it has read it ("the callback fires exactly once",
  `nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetAwait.kt:32-47`),
  `NugetPendingTask` holds one `CancellableContinuation` (`NugetAwait.kt:23-26`), and every row of
  the ownership tables in ADR-152 and ADR-153 is exactly-once. **Nothing in the reverse bridge
  supports a managed operation calling back into Kotlin repeatedly while in flight.**
- A push design would have to add: a pending record holding a `Channel`, a `ctx` whose release
  moves to a terminal callback (otherwise a Kotlin cancel frees the `StableRef` while a C#
  `onNext` on a pool thread is racing toward it, a silent use-after-free), a buffering rule, and
  one typed `onNext` callback per element ABI shape, which is ADR-152's rejected Alternative 2.
  It would also inherit the mirror of the open forward bug
  `docs/backlog/flow-onnext-materialisation-throw-kills-host.md` (element materialisation running
  on a foreign thread inside an unmanaged callback).
- `IAsyncEnumerable<T>` is itself a pull protocol (`MoveNextAsync` returns `ValueTask<bool>`,
  then `Current`), and ADR-152 already binds "start a managed awaitable, suspend, unwrap the
  result through the ordinary sync return half".
- The reader names the shape and skips it: `AsyncTypeNames`
  (`NugetMetadataReader/Program.cs:126-133`), `IsAsyncTypeName` (`Program.cs:3568-3576`), the
  informational fall-through in `GetGenericInstantiation` (`Program.cs:3411-3425`). `RirAsyncKind`
  has one value, `TASK` (`nuget-plugin/.../rir/RirModel.kt:155-158`). The generator treats
  `asyncKind != null` as "is a `suspend fun`" at 22 sites across `NugetGenerateBindingsTask.kt`
  (11), `RirBridging.kt` (9), `NugetGenerateShimsTask.kt` (1), `RirModel.kt` (1).
- Generated bound classes compile from `nativeMain` (`NugetPlugin.kt:192-194`), while
  `nuget-runtime` and therefore `kotlinx-coroutines-core` reach only `${target}MainApi`
  (`NugetPlugin.kt:241`). ADR-152 avoided the problem because `suspend` is stdlib. `Flow` is not.

## Alternatives Considered

### 1. Pull: Kotlin `flow { }` drives `MoveNextAsync` through ADR-152's `awaitForKotlin` (chosen)

`Enumerate` (sync thunk) calls the method and `GetAsyncEnumerator(cts.Token)`, returning one
`GCHandle` per collection. Each step is an ADR-152 begin/end await of `MoveNextAsync().AsTask()`;
`Current` is the ordinary sync return thunk; a single `Dispose` thunk ends the enumeration.

- Pros: no repeated callback, so none of the missing machinery above is needed. Every callback is
  still exactly-once, and ADR-152's verified claims (foreign-thread resume, cancel-then-complete
  releasing the task handle) carry over unchanged. Backpressure is inherent: C# does not produce
  element N+1 until the collector asked for it. `emit` runs on the collector's coroutine, never on
  a .NET thread. Element marshalling is `buildThunkMethod`'s return half, so the element
  vocabulary is ADR-152's `Task<T>` vocabulary with zero new marshalling. A mid-stream throw
  leaves `MoveNextEnd` through `errOut`.
- Cons: three crossings plus one `Task` `GCHandle` per element (Begin, callback, End, then
  `Current`). Chatty for a high-rate stream. `nuget_runtime_register` grows 7 to 10 slots.

### 2. Push: C# `await foreach` pump calling `onNext` / `onComplete` / `onError` into a Kotlin channel (literal ADR-026 mirror)

- Pros: one crossing per element.
- Cons: everything listed in Context. Backpressure needs either an unbounded buffer (C# outruns
  the collector without limit) or a suspend-until-consumed handshake, which is a second crossing
  per element and erases the advantage. New lifetime rules with a silent failure mode. Rejected.

### 3. Batch pull: `MoveNext` drains up to N ready elements per crossing

An optimisation of Alternative 1 for high-rate streams. Deferred, not rejected: it changes element
marshalling (a buffer of N values per ABI shape) and nothing in Alternative 1 forecloses it.

### 4. Amend ADR-152 instead of a new ADR

Rejected as the record: push versus pull, the enumeration-scoped `CancellationTokenSource`, the
dispose ordering, and `Flow` in a `nativeMain` signature are all decisions ADR-152 never faced.

## Decision

Alternative 1.

### Consumer-visible Kotlin

```kotlin
// C#: public class Kennel {
//   IAsyncEnumerable<string> BarksAsync(int count);
//   IAsyncEnumerable<Kitten> LitterAsync([EnumeratorCancellation] CancellationToken ct = default);
//   static IAsyncEnumerable<int> Ticks();
// }
class Kennel : AutoCloseable {
  fun barks(count: Int): Flow<String>      // not suspend; nothing runs until collect
  fun litter(): Flow<Kitten>               // token elided (ADR-153), the bridge owns it per collect
  companion object { fun ticks(): Flow<Int> }
}

kennel.barks(3).collect { println(it) }            // one C# enumeration
kennel.barks(3).take(1).collect { }                // C# enumerator disposed after the first element
withTimeout(100.milliseconds) { kennel.litter().collect { it.close() } }   // C# sees ct cancelled
try { kennel.barks(-1).collect { } } catch (e: NugetManagedException) { }  // mid-stream throw
```

**Shipped as `Kitten`, not the sketch's earlier `Cat`:** the fixture's actual element type. No
mechanism difference; a bound-class element still crosses through the ordinary sync return half.

Naming follows ADR-152: a trailing `Async` is dropped unless a sibling already has the stripped
name. The method is a plain `fun`. The C# method is called **at collect time**, inside the flow
(see open question 1): it is what makes the flow cold in the Kotlin sense, what lets one
per-collect token reach both an elided `CancellationToken` parameter and `GetAsyncEnumerator`,
and it leaves no long-lived `IAsyncEnumerable` `GCHandle` for a `Cleaner` to own.

### Wire shape

Per async-enumerable method, two adjacent registration slots, `Enumerate` then `Current`, both
through `errorChannelThunk`. (Drafted as `BarksAsync_Enumerate_Thunk`; corrected during
implementation to `BarksAsyncEnumerate_Thunk`, no inner underscore, because `RirSlotRole.nameSuffix`
concatenates — ADR-152's pair is `CountAsyncBegin_Thunk`, and a second convention for the same
mechanism would be a needless split.)

```csharp
private static unsafe IntPtr BarksAsyncEnumerate_Thunk(IntPtr selfHandle, int count, IntPtr* errOut)
{
    try
    {
        Kennel receiver = (Kennel)GCHandle.FromIntPtr(selfHandle).Target!;
        CancellationTokenSource cts = new();
        IAsyncEnumerator<string> e = receiver.BarksAsync(count /*, cts.Token at cancellationToken index */)
            .GetAsyncEnumerator(cts.Token);
        return GCHandle.ToIntPtr(GCHandle.Alloc(new NugetAsyncEnumeration<string>(e, cts)));
    }
    catch (Exception ex) { *errOut = GCHandle.ToIntPtr(GCHandle.Alloc(ex)); return IntPtr.Zero; }
}

// the sync return half, unchanged, with this as its call expression:
//   ((NugetAsyncEnumeration<string>)GCHandle.FromIntPtr(h).Target!).Enumerator.Current
private static unsafe IntPtr BarksAsyncCurrent_Thunk(IntPtr enumeration, IntPtr* errOut) { ... }
```

Once in `NugetRuntimeRegistration.cs`, three new runtime slots (8, 9, 10) over a non-generic base:

```csharp
internal abstract class NugetAsyncEnumeration
{
    internal CancellationTokenSource Cts = null!;
    internal Task Pending = Task.CompletedTask;        // the in-flight MoveNextAsync, if any
    internal abstract ValueTask<bool> MoveNext();
    internal abstract ValueTask Dispose();
}

// slot 8: runs user code, so it carries errOut. Same callback/ctx contract as every Begin.
static void MoveNextBegin_Thunk(IntPtr enumeration, IntPtr callback, IntPtr ctx, IntPtr* errOut)
{   // try: n.Pending = n.MoveNext().AsTask(); NugetTasks.Attach(n.Pending, callback, ctx); catch -> errOut
}
// slot 9: Task<bool> End, frees the task handle in finally, rethrows through errOut. Returns 0/1.
static int MoveNextEnd_Thunk(IntPtr taskHandle, IntPtr* errOut) { ... }
// slot 10: plumbing, no errOut. Frees the enumeration handle, then IN THIS ORDER, inside
//   _ = Task.Run(async () => { try { ... } catch (Exception) { } });
// NEVER an `async` lambda handed to ThreadPool.UnsafeQueueUserWorkItem or any other `async void`
// shape: an exception escaping `async void` terminates the .NET host, which is the host abort the
// restatement forbids. ADR-153's ReleaseCancellation_Thunk may use a plain work item only because
// its body is synchronous. The single outer catch is load-bearing.
//   if (cancelled != 0) try { Cts.Cancel(); } catch (AggregateException) { }
//   try { await Pending; } catch { }
//   try { await Dispose(); } catch { }        // v1: a throwing C# finally is dropped, see Consequences
//   Cts.Dispose();
static void DisposeEnumeration_Thunk(IntPtr enumeration, int cancelled) { ... }
```

**Kotlin, three layers, corrected to what shipped.** The sketch above drew this as one function;
the shipped shape is the same three-layer split ADR-152's `nugetAwaitTask`/`awaitForKotlin` already
established, run twice:

1. **Generated, per member, in `nativeMain`** (`Kennel.kt`): a two-argument call, only what is
   specific to this member — how to start an enumeration and how to read the current element. Every
   local it introduces is `nuget`-prefixed by convention (`nugetStep`, `nugetEnumeration`), the same
   naming the rest of the generated file uses to stay out of the consumer's own name scope:

   ```kotlin
   fun barks(count: Int): Flow<String> {
     val enumerate = requireNotNull(KennelBindings.barksAsync__<hash>EnumerateFn) { /* ... */ }
     val current = requireNotNull(KennelBindings.barksAsync__<hash>CurrentFn) { /* ... */ }
     fun nugetStep(nugetEnumeration: COpaquePointer): String {
       val resultPtr = nugetCall { err -> current.invoke(nugetEnumeration, err) } ?: error(/* ... */)
       return resultPtr.reinterpret<ByteVar>().toKString().also { freeManagedString(resultPtr) }
     }
     return nugetFlow(
       enumerate = { nugetCall { err -> enumerate.invoke(handle.require("Kennel"), count, err) } },
       current = ::nugetStep,
     )
   }
   ```

2. **Generated, once per project, split across an `expect`/`actual` seam** (the same seam
   `nugetKotlinError` and `nugetAwaitTask` already use, for the same reason: the runtime klib is
   `api` only on the per-target source set, and the shared `nativeMain` file cannot see it). The
   `expect` side, in `NugetRuntime.kt` (`nativeMain`), is the two-argument signature every generated
   member calls:

   ```kotlin
   internal expect fun <T> nugetFlow(
     enumerate: () -> COpaquePointer?,
     current: (enumeration: COpaquePointer) -> T,
   ): Flow<T>
   ```

   The `actual`, in `NugetKotlinErrors.kt` (`mingwMain`/`posixMain`, where the runtime klib is
   visible), is one line of delegation supplying the other four parameters by closing over the
   shared registered slots (`moveNextBeginFn`, `moveNextEndFn`, `disposeEnumerationFn`,
   `freeGcHandleFn`) `NugetRuntime.kt` already declares for `nuget_runtime_register`:

   ```kotlin
   internal actual fun <T> nugetFlow(
     enumerate: () -> COpaquePointer?,
     current: (enumeration: COpaquePointer) -> T,
   ): Flow<T> = flowForKotlin(
     release = { task -> requireNotNull(freeGcHandleFn) { /* ... */ }.invoke(task) },
     enumerate = { requireNotNull(enumerate()) { /* ... */ } },
     moveNextBegin = { enumeration, callback, ctx ->
       val fn = requireNotNull(moveNextBeginFn) { /* ... */ }
       nugetCall { err -> fn.invoke(enumeration, callback, ctx, err) }
     },
     moveNextEnd = { task ->
       val fn = requireNotNull(moveNextEndFn) { /* ... */ }
       nugetCall { err -> fn.invoke(task, err) } != 0
     },
     current = current,
     dispose = { enumeration, cancelled ->
       requireNotNull(disposeEnumerationFn) { /* ... */ }.invoke(enumeration, if (cancelled) 1 else 0)
     },
   )
   ```

3. **`nuget-runtime` klib, once, not regenerated** (`NugetFlow.kt`, the ADR-128 "runtime owns the
   shape" rule): the six-argument function that actually owns stepping, ownership and cancellation,
   unchanged from the original sketch below. Nothing about a specific member — its element type, its
   receiver, whether it is static — appears at this layer; that is entirely the job of the two
   closures layer 2 supplies.

   ```kotlin
   @NugetRuntimeApi
   public fun <T> flowForKotlin(
     release: (task: COpaquePointer) -> Unit,
     enumerate: () -> COpaquePointer,                                   // nugetCall { Enumerate }
     moveNextBegin: (enumeration: COpaquePointer, callback: COpaquePointer, ctx: COpaquePointer) -> Unit,
     moveNextEnd: (task: COpaquePointer) -> Boolean,                    // nugetCall { End }
     current: (enumeration: COpaquePointer) -> T,                       // nugetCall { Current } + wrap
     dispose: (enumeration: COpaquePointer, cancelled: Boolean) -> Unit,
   ): Flow<T> = flow {
     val enumeration = enumerate()
     var cancelled = false
     try {
       while (true) {
         val task = awaitForKotlin(release, cancel = { _, _ -> }) { cb, ctx -> moveNextBegin(enumeration, cb, ctx); null }
         if (!moveNextEnd(task)) break
         emit(current(enumeration))
       }
     } catch (e: CancellationException) { cancelled = true; throw e }
     finally { dispose(enumeration, cancelled) }     // non-suspending, legal under cancellation
   }
   ```

### Ownership, exactly once

| Thing | Minted by | Freed by | On which path |
|---|---|---|---|
| enumeration `GCHandle` | `Enumerate`, last | `DisposeEnumeration` from the flow's `finally` | every path once `enumerate()` returned |
| same | never minted | n/a | `Enumerate` wrote `errOut` |
| per-step `ctx`, `Task` `GCHandle` | ADR-152, unchanged | ADR-152, unchanged | unchanged, including cancel-then-complete |
| CTS | `Enumerate` | the queued dispose sequence, after `DisposeAsync` | every path |

Cancellation racing the final element: the race is cancel against one `MoveNextAsync` completion,
which is ADR-152's verified resumed-then-cancelled window. The task handle is released by
`onCancellation`, `Current` is never read, no element is delivered after cancellation, and the
dispose sequence finds `Pending` completed.

### RIR, reader, contract

`RirAsyncKind` gains `ASYNC_ENUMERABLE` (`"async_enumerable"`); `returnType` is the element type.
**Element vocabulary correction, found in implementation:** the ADR's own `Flow<Int?>` example is
not expressible. `int?` is `System.Nullable<int>`, and `RirPrimitiveType` carries no nullability at
all (only `RirStringType` does), so a nullable VALUE element has no reverse mapping anywhere in the
pipeline. It is a **split-out deferred item**, not part of this one: the fixture's `Ticks()` is
`IAsyncEnumerable<int>`, and `Kennel.NullableTicks()` stays as the live regression fixture asserting
the shape remains a NAMED skip rather than binding as `Flow<Int>` and dropping the nulls.
The reader's `RirAsyncType` wrapper gains the kind, and `` IAsyncEnumerable`1 `` with one
admissible argument produces it, at a **method return only**. The new branch needs the same guard the `` Task`1 `` branch has
(`awaited.TypeRef is not null and not RirAsyncType`, `Program.cs:3421`) on its own argument, so
`IAsyncEnumerable<Task<T>>` and `Task<IAsyncEnumerable<T>>` stay skipped; without it a nested
shape binds silently with the wrong element type. It must count as one annotatable
node exactly as ADR-152's `Task<T>` wrapper does. ADR-153's single-token elision applies
unchanged. `contractSignature` uses a distinct `asyncenum:` prefix. `REVERSE_ABI_TAG` does not
bump; `NUGET_RUNTIME_CONTRACT_HASH` changes (7 to 10 slots). Every one of the 22
`asyncKind != null` sites must become a decision on the kind: the enumerable method is **not**
`suspend`, does not import `nugetAwaitTask`, and registers `Enumerate`/`Current`, not `Begin`/`End`.
`asyncDeferredDiagnostics` (struct, interface, generic class) already keys on `asyncKind != null`
and so keeps those skipped for the new kind with no change.

### Mechanism claims ledger

**Verified by spike, 2026-09-21** (scratch dir under `mktemp -d`, .NET SDK 10.0.301, net10.0
console, Windows x64, `dotnet run`; compiler-generated `async IAsyncEnumerable<int>` iterators):

```
a) DisposeAsync while MoveNext pending: System.NotSupportedException: Specified method is not supported.
a) pending afterwards: WaitingForActivation
b) with attr, cancel mid MoveNext: System.Threading.Tasks.TaskCanceledException: A task was canceled. status=Canceled
b) dispose after faulted MoveNext: no throw
c) no attr, cancel mid MoveNext: no throw status=RanToCompletion current=0
c) dispose at yield (suspended): no throw          (the iterator's finally ran)
d) cancel then chained dispose: no throw
e) round 0: items=[1,2] end=InvalidOperationException:boom     (Finite finally ran, dispose after throw: no throw)
e) round 1: items=[1,2] end=InvalidOperationException:boom     (same IAsyncEnumerable through a GCHandle)
f) 2nd MoveNext while pending: no throw
g) pre-cancelled token: System.Threading.Tasks.TaskCanceledException: A task was canceled.
h) param token A + enumerator token B, cancel B: System.Threading.Tasks.TaskCanceledException
```

1. **`DisposeAsync` during a pending `MoveNextAsync` throws `NotSupportedException` and does not
   stop the iterator (a).** A Kotlin `finally` that disposed directly on cancellation would throw
   and leak the running iterator. Hence the C#-side sequence cancel, await `Pending`, dispose (d).
2. The token given to `GetAsyncEnumerator` cancels a pending step **only** when the source marks a
   parameter `[EnumeratorCancellation]` (b against c). Without it the step runs to its next
   `yield`, then dispose runs the iterator's `finally` (c). So "collector cancellation stops the C#
   enumeration" means: promptly when the C# honours the token, at the next element otherwise.
3. A second concurrent `MoveNextAsync` does not throw (f); it is undefined behaviour. The flow is
   sequential by construction, so the bridge never does it.
4. A mid-stream throw surfaces from the awaited `MoveNextAsync` as the original exception, and
   `DisposeAsync` afterwards is a no-throw (e). A compiler-generated iterator re-enumerates from
   the start on a second `GetAsyncEnumerator` (e, round 1).

**Inferred, nobody has run it:**

- A. **VERIFIED during implementation, and it amends ADR-130.** The proposed fix works: with
  `test-library`'s own `implementation(libs.kotlinx.coroutines.core)` REMOVED (it is now removed
  permanently, so the fixture can no longer hide this), `:test-library:compileKotlinMingwX64`
  resolves every coroutines reference in `nativeMain` from the plugin's
  `api` on the `nativeMain` source set. One correction to the proposal: `findByName("nativeMain")`
  returns null at plugin-apply time (the default hierarchy has not materialised it yet) and the
  dependency is then silently never added; the wiring uses `sourceSets.configureEach` instead,
  adding `kotlinx-coroutines-core` as `api` **unconditionally**, the moment a `nativeMain` source
  set materialises, the same way it wires every other reverse dependency — there is no check for
  whether this particular consumer binds a `Flow`-returning member at all, and no way to opt out.
  **ADR-130's rule is hereby amended**: a generated `nativeMain` declaration may name
  `kotlinx.coroutines.flow.Flow`, and nothing else from kotlinx. The suspend seam is unchanged —
  `suspendCancellableCoroutine` and friends still reach the consumer only through the runtime's
  per-target `api`, and the two generator tests that enforced "no kotlinx symbol at all" now
  enforce "none but `Flow`".

  The original text follows, for the record:

- A. **`kotlinx.coroutines.flow.Flow` does not resolve in the generated `nativeMain` class.**
  ADR-130 Variant 1 verified the general rule (a `nativeMain` file naming a declaration reachable
  only through a per-target `api` fails `compileNativeMainKotlinMetadata`); `Flow` reaches the
  consumer only through `nuget-runtime`'s `api(kotlinx-coroutines-core)` on `${target}MainApi`.
  `test-library` declares coroutines on `nativeMain` itself (`test-library/build.gradle.kts:120`),
  so the fixture hides it. The `internal expect` trick does not help: the type is in a **public**
  signature. Proposed fix: the plugin adds `kotlinx-coroutines-core` (which, unlike
  `nuget-runtime`, publishes every native target, so ADR-130's iOS objection to Variant 2 does not
  apply) as `api` on `findByName("nativeMain")`. Not spiked (a Gradle build outside the 20 minute
  budget). If wrong it is a consumer compile error, loud, not silent. **The implementation must
  spike this first, in a scratch project without coroutines on `nativeMain`.**
- B. `NugetAsyncEnumeration<T>` statically instantiated from each generated `Enumerate` thunk is
  NativeAOT-safe for value-type `T`. If wrong, a loud AOT failure.
- C. **VERIFIED by implementation: runtime slots CAN carry `errOut`.** Slots 8 and 9 are the first
  that do, exactly as proposed; the fallback (per-method Begin/End through `errorChannelThunk`) was
  not needed. Original text:

- C. Runtime slots may carry `errOut` (slots 8, 9). ADR-104 Fork A says plumbing carries none and
  user code carries one; these run user code. **Verified by reading**: today's 7 runtime slots
  (`NugetGenerateBindingsTask.kt:4489-4509`, registered at `:4582-4593`) are plain typed
  `CPointer<CFunction<...>>` variables and none carries an `errOut`, so slots 8 and 9 would be the
  first. **Inferred**: nothing in that emitter prevents one more pointer argument. If it cannot,
  the fallback is per-method `MoveNextBegin`/`MoveNextEnd` slots through `errorChannelThunk`
  (4 slots per method instead of 2, `nuget_runtime_register` 7 to 8).
- F. An exception escaping the queued dispose sequence would abort the host if the sequence were
  an `async void` shape. The `Task.Run` plus outer `catch` shape above is the guard; not spiked.
- D. A hand-written `IAsyncEnumerator` (not compiler-generated) may tolerate or reject a dispose
  during a pending step differently; the chosen sequence never does it, so it is safe either way.
- E. Dropping the `DisposeAsync` exception in the queued sequence raises no
  `UnobservedTaskException` process effect on net8.0+.

## Consequences

- `IAsyncEnumerable<T>` method returns on plain bound classes (instance and static) bind as
  `Flow<T>`. Element types: whatever ADR-152 admits for `Task<T>`.
- Skipped with the existing named diagnostic: `IAsyncEnumerable<T>` at a parameter, property,
  constructor or type-argument position; `IAsyncEnumerable<T>?`; struct, bound-interface and
  generic-class members (ROADMAP Phase 12, the ADR-152 deferred line).
- `ValueTask` stays unmapped as a **surface** type; the shim's internal `.AsTask()` on
  `MoveNextAsync` does not touch that ROADMAP item.
- Disposal is fire-and-forget: `collect` can return before the C# iterator's `finally` has run,
  and an exception thrown by that `finally` is dropped. `await foreach` would surface it.
- A C# `OperationCanceledException` mid-stream with the collector not cancelled surfaces as
  `CancellationException` (ADR-153's single throw site), which ends the collector silently.
- Three crossings per element. Batch pull (Alternative 3) is the deferred answer.
- The enumeration and CTS `GCHandle`s are uncounted by `nuget_live_handles`, as the ADR-153 CTS
  handle is (ROADMAP line 268). The per-step `ctx` is counted, so `LeakTests` rows still catch a
  Kotlin-side leak; a C#-side leak is pinned only by a counting fake in a `nativeTest`.
