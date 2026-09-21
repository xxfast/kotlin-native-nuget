# ADR-153: Reverse, cancelling the awaiting coroutine cancels a bridge-owned `CancellationToken`, and a cancelled C# task surfaces as `CancellationException`

## Status

Accepted

## Context

Reverse, C# declares, Kotlin consumes: when a Kotlin consumer cancels the coroutine awaiting a
bound C# `Task`/`Task<T>` method, the C# work is actually told to stop through a
`CancellationToken`, and a C# task that ends cancelled surfaces in Kotlin as a
`CancellationException` instead of today's
`NugetManagedException("System.Threading.Tasks.TaskCanceledException")`.

This is the mirror of [ADR-022](022-cancellation-token-support.md) on top of
[ADR-152](152-task-to-suspend-fun.md)'s Begin/End pair and
[ADR-104](104-reverse-thunk-error-channel.md)'s error envelope. `ValueTask`, `IAsyncEnumerable`,
async on structs/interfaces/generic classes and `Task` at non-return positions are separate
ROADMAP items and stay out.

What the pipeline does today:

- **Verified by reading.** A `CancellationToken` parameter is a `TypeReference` into
  `System.Private.CoreLib`/`System.Runtime`, and `GetTypeFromReference` knows only
  `System.String`, `Task` and `ValueTask` (`NugetMetadataReader/Program.cs:3206-3249`). It falls
  through to `skipped_unbound_type_reference`, and `TryMapMethod`'s per-type diagnostic loop
  (`Program.cs:2118-2133`) skips the whole member. So **every** method taking a
  `CancellationToken`, sync or async, is unbound today, with a hint that tells the user to bind
  the BCL.
- **Verified by reading.** `awaitForKotlin` registers no `invokeOnCancellation` handler
  (`nuget-runtime/.../NugetAwait.kt:64-78`). Cancelling the coroutine resumes it promptly with
  its own `CancellationException`; `End` is never called on that path, and the task's `GCHandle`
  is freed by `resume`'s `onCancellation` when the completion later arrives (`NugetAwait.kt:45`).
- **Verified by reading.** Every managed throw, from any thunk, becomes a Kotlin exception at
  exactly one site: the generated `nugetThrowManagedError`
  (`NugetGenerateBindingsTask.kt:4518-4523`), which compiles from `nativeMain` and therefore
  cannot name a `kotlinx.coroutines` symbol (ADR-130, ADR-152 Context).
- **Verified by grep.** Nothing in `nuget-plugin/src/main` or the reader *produces*
  `error_kotlin_signature_collision` for the reverse direction (the enum value exists,
  `rir/RirModel.kt:345`, and one test feeds it from hand-written JSON). Two C# overloads that
  project to the same Kotlin signature reach the Kotlin compiler as a "conflicting overloads"
  error in the consumer's build.
  Amended 2026-09-22: a collision is now reported as `error_kotlin_signature_collision` before any
  Kotlin is written, not left to reach the consumer's Kotlin compiler (ADR-057's 2026-09-22
  amendment).

## Alternatives Considered

### 1. Begin mints the `CancellationTokenSource` and returns its handle; one runtime release slot; `managedErrorKind` accessor (chosen)

The `Begin` thunk of a token-taking method creates the CTS, passes `cts.Token` at the elided
parameter's index, attaches the completion, and **returns** the CTS `GCHandle` (`IntPtr` instead
of `void`). `awaitForKotlin` stores it in an atomic cell, registers `invokeOnCancellation`, and
releases the handle exactly once through one new runtime slot. The exception mapping is a second
new runtime slot, `managedErrorKind(err) -> Int`, decided on the C# side with `is`.

- Pros: no extra crossing per call (the handle rides `Begin`'s return). Existing thunks keep
  their arity, so `REVERSE_ABI_TAG` does not bump. Exactly-once release is an atomic swap, not a
  reasoning about kotlinx handler ordering. `managedErrorKind` is the end state the "map core
  .NET exceptions" ROADMAP item needs anyway (an `is` test, which a name `when` cannot do).
- Cons: `nuget_runtime_register` grows 5 to 7 slots (new `NUGET_RUNTIME_CONTRACT_HASH`).
  `awaitForKotlin`'s signature changes (runtime and plugin already version together, ADR-127/129).

### 2. Kotlin mints the CTS through a `nuget_cts_create` runtime slot before `Begin`

Rejected: one more crossing and one more slot per call for the same result. Its only advantage
(the handle is known before `Begin` runs) buys nothing, because `invokeOnCancellation` invoked on
an already-cancelled continuation fires immediately (kotlinx documented behaviour), so
registering after `Begin` returns loses no cancellation.

### 3. Map by name: `when (managedType)` over `TaskCanceledException` and `OperationCanceledException`

The patch ADR-104 Fork D priced (zero ABI change, one file). Rejected as the recommendation:
**verified by spike** below that a user subclass of `OperationCanceledException` thrown from an
`async` method yields a `Canceled` task whose `GetResult()` throws the subclass
(`Probe.MyCancel`), which a name match silently leaves as `NugetManagedException`. Price: 1 file
(`NugetGenerateBindingsTask.kt`) against Alternative 1's 3 for the mapping half. It is an honest
fallback if the gate wants `nuget_runtime_register` untouched.

### 4. Throw `CancellationException` only when the Kotlin coroutine is itself cancelled

Addresses the kotlinx footgun (a `CancellationException` thrown in a non-cancelled coroutine ends
it silently without failing the parent). Rejected, because it is dead code: when the Kotlin
coroutine **is** cancelled, `suspendCancellableCoroutine` throws its own exception and `End` is
never reached (verified by reading, above). The mapping site is reached *only* when the callee
cancelled and the caller did not. So the real choice is "always `CancellationException`" or
"never", and every kotlinx precedent picks always: play-services `Task.await()` does
`if (isCanceled) cont.cancel()` / `throw CancellationException(...)`, and `Deferred.await()` and
`CompletableFuture.await()` rethrow the callee's cancellation (inferred, from the kotlinx
sources and docs). This is an open what-question at the gate.

### 5. Call `cts.Cancel()` inline from the cancellation handler

Rejected: **verified by spike** that `Cancel()` runs every token registration inline on the
calling thread and throws `AggregateException` when one throws. The caller here is a kotlinx
`invokeOnCancellation` handler, which must be fast and must not throw: **verified by reading**
kotlinx-coroutines-core 1.10.2 `CancellableContinuationImpl.kt:226-236`, a throwing handler goes
to `handleCoroutineException` (on Kotlin/Native with no handler installed that terminates the
process, inferred). The thunk queues the cancel to the .NET thread pool instead.

## Decision

Alternative 1.

### Consumer-visible Kotlin

```kotlin
// C#: Task<int> StayAsync(string name, CancellationToken ct);  Task<int> DozeAsync(int ms, CancellationToken ct = default);
class Kennel : AutoCloseable {
  suspend fun stay(name: String): Int     // token elided, the bridge supplies it
  suspend fun doze(ms: Int): Int
}

withTimeout(100.milliseconds) { kennel.stay("Oreo") }   // C# sees ct.IsCancellationRequested
val job = launch { kennel.stay("Mylo") }; job.cancel()  // same
try { kennel.bolt() } catch (e: CancellationException) { /* C# cancelled itself */ }
```

### Scope rules (reader)

- A parameter whose type is the `TypeReference` `System.Threading.CancellationToken` is elided
  when the method is async (`asyncKind != null`) and has **exactly one** such parameter, at
  **any** position. `RirMethod` gains `cancellationToken: Int? = null`, the index in the **C#**
  parameter list where the shim inserts `cts.Token`. The parameter is removed from `parameters`,
  so no `RirTypeRef` subtype is added and no exhaustive `when` over `RirTypeRef` moves.
- A default value is irrelevant: the bridge always supplies the token. **Verified by spike**:
  `CancellationToken ct = default` is `Optional, HasDefault` with a `NullReference` constant; the
  reader does not need to read it.
- `FooAsync()` beside `FooAsync(CancellationToken)`: after elision both are `suspend fun foo()`.
  The reader folds the pair: the token overload is kept, the token-less sibling (same name, same
  static-ness, same remaining parameter types) is dropped with an info diagnostic
  `info_cancellation_overload_folded`. Without this, generation fails with
  `error_kotlin_signature_collision` (Context, last bullet; ADR-057's 2026-09-22 amendment).
- **Where the fold sits (amended during implementation).** It is a post-pass over the per-type
  mapped method list in `MapType` (`FoldCancellationOverloads`, `Program.cs`), not a rule inside
  `TryMapMethod`: the decision needs both siblings and `TryMapMethod` sees one member at a time.
  Identity is the CLR one, taken off `ManagedSignature` (receiver, declaring type, name, parameter
  types, return type) with every `CancellationToken` parameter removed. The concern that an
  ADR-043 overload-set filter would drop both siblings first does not apply: the reader has no
  such filter for methods, it maps every overload independently and the plugin disambiguates the
  slots through `bridgeSuffix()` (verified, `Program.cs` method-group loop, and
  `Call_OverloadPair_KeepsTheTokenOverload` is green).
- A sync method taking a token, two or more tokens, `CancellationToken?`, `ref`/`in`/`out`
  tokens, a token on a constructor or property: skipped with a **named** diagnostic
  `info_cancellation_token_not_yet_mapped` instead of `skipped_unbound_type_reference`.
  **Verified by spike**: `CancellationToken?` decodes as `GENERICINST Nullable<CancellationToken>`
  (`15-11-80-E1-01-11-71`), so it never reaches the plain `TypeReference` branch.

### Wire shape

C#, generated per token-taking async method. Only `Begin` changes; `End` is ADR-152's unchanged:

```csharp
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe IntPtr StayAsync_Begin_Thunk(IntPtr selfHandle, IntPtr name, IntPtr callback, IntPtr ctx, IntPtr* errOut)
{
    try
    {
        Kennel receiver = (Kennel)GCHandle.FromIntPtr(selfHandle).Target!;
        CancellationTokenSource cts = new();
        Task task = receiver.StayAsync(/* name */, cts.Token);
        NugetTasks.Attach(task, callback, ctx);
        return GCHandle.ToIntPtr(GCHandle.Alloc(cts));   // minted LAST: nothing above can leak it
    }
    catch (Exception ex) { *errOut = GCHandle.ToIntPtr(GCHandle.Alloc(ex)); return IntPtr.Zero; }
}
```

C#, once in `NugetRuntimeRegistration.cs`, two new runtime slots:

```csharp
// slot 6: release a bridge-owned CancellationTokenSource handle, exactly once.
private static void ReleaseCancellation_Thunk(IntPtr handle, int cancel)
{
    GCHandle h = GCHandle.FromIntPtr(handle);
    CancellationTokenSource cts = (CancellationTokenSource)h.Target!;
    h.Free();
    if (cancel == 0) { cts.Dispose(); return; }
    ThreadPool.UnsafeQueueUserWorkItem(
        static s => { try { s.Cancel(); } catch (AggregateException) { } }, cts, preferLocal: false);
}

// slot 7: 0 = other, 1 = cancellation. Later kinds (ADR-029 reversed) are more values, no new slot.
private static int ManagedErrorKind_Thunk(IntPtr err) =>
    GCHandle.FromIntPtr(err).Target is OperationCanceledException ? 1 : 0;
```

Plumbing thunks carry no `errOut` (ADR-104 Fork A). The cancelled arm never disposes the CTS: a
`Dispose` racing the queued `Cancel` throws `ObjectDisposedException` (verified by spike), and a
plain CTS holds nothing a finalizer does not release (inferred, from the BCL source).

Kotlin, `nuget-runtime/NugetAwait.kt`:

```kotlin
public suspend fun awaitForKotlin(
  release: (task: COpaquePointer) -> Unit,
  cancel: (source: COpaquePointer, cancelled: Boolean) -> Unit,
  begin: (callback: COpaquePointer, ctx: COpaquePointer) -> COpaquePointer?,   // the CTS handle, or null
): COpaquePointer {
  val source = AtomicReference<COpaquePointer?>(null)
  try {
    return suspendCancellableCoroutine { continuation ->
      val ctx = NugetHandles.retain(NugetPendingTask(continuation, release))
      try { source.value = begin(TASK_COMPLETED, ctx) } catch (e: Throwable) { NugetHandles.release(ctx); throw e }
      continuation.invokeOnCancellation {
        val owned = source.getAndSet(null)
        if (owned != null) cancel(owned, true)
      }
    }
  } finally {
    val owned = source.getAndSet(null)
    if (owned != null) cancel(owned, false)
  }
}
```

The generated `nugetAwaitTask` `expect`/`actual` gains the same return type on `begin`; the
`actual` passes `cancel = { s, c -> releaseCancellationFn!!.invoke(s, if (c) 1 else 0) }`. A
token-less stub's `begin` lambda returns `null` (its `Begin` thunk still returns `void`).

Kotlin, generated `nugetThrowManagedError` (`nativeMain`, stdlib names only):

```kotlin
val kind: Int = managedErrorKindFn?.invoke(err) ?: 0
// ... read type + message, free err ...
val managed = NugetManagedException(managedType ?: "System.Exception", message)
if (kind == 1) throw kotlin.coroutines.cancellation.CancellationException(message, managed)
throw managed
```

The mapping applies at the single throw site, so a **sync** thunk that throws an
`OperationCanceledException` also surfaces as `CancellationException`. This is an open
what-question; restricting it to async stubs costs a second `nugetCall` variant.

### Ownership, exactly once (additions to ADR-152's table)

| Thing | Minted by | Freed by | On which path |
|---|---|---|---|
| CTS `GCHandle` | `Begin`, after `Attach` succeeded | `awaitForKotlin`'s `finally` → `ReleaseCancellation(h, 0)` (dispose) | task completed, faulted, or C# cancelled itself, Kotlin not cancelled |
| same | same | the `invokeOnCancellation` handler → `ReleaseCancellation(h, 1)` (queue `Cancel`) | coroutine cancelled before completion |
| same | same | the handler (it still fires, through `invokeHandlers`) | coroutine cancelled after `resume` but before dispatch; the queued `Cancel` lands on a finished task, a no-op |
| same | never minted | n/a | synchronous throw in `Begin` (method threw, or `Attach` threw): `errOut` path, `Begin` returns `IntPtr.Zero` |
| `ctx`, `Task` `GCHandle` | unchanged from ADR-152 | unchanged | unchanged |

Whichever of the handler and the `finally` swaps the cell first owns the handle; the other sees
`null`. Correctness does not depend on which runs first.

### RIR and contract

`RirMethod.cancellationToken: Int?`. `contractSignature` gains `ct<index>:` after `async:`, so a
package adding a token to an existing async method drifts the hash (the `Begin` return type
changes). `REVERSE_ABI_TAG` does not bump. `nuget_runtime_register` goes 5 to 7 slots;
`NUGET_RUNTIME_CONTRACT_HASH` changes, so a stale shim fails loudly at startup (ADR-054).

### Mechanism claims ledger

**Verified by spike, 2026-09-19** (scratch dir under `mktemp -d`, .NET SDK 10.0.300, net10.0
console, macOS arm64; `dotnet run` of a probe holding the fixture shapes, decoded with
`System.Reflection.Metadata`):

```
honour+cancel: Canceled threw=System.Threading.Tasks.TaskCanceledException isOCE=True      // Cancel() through a GCHandle
ignore+cancel: RanToCompletion ok
selfcancel (async throws OCE, foreign token): Canceled threw=System.OperationCanceledException isOCE=True
async throws new OCE() no token: Canceled threw=System.OperationCanceledException isOCE=True
FromException(OCE): Faulted threw=System.OperationCanceledException isOCE=True
FromCanceled: Canceled threw=System.Threading.Tasks.TaskCanceledException isOCE=True
sync ThrowIfCancellationRequested before Task: System.OperationCanceledException
custom OCE subclass: Canceled threw=Probe.MyCancel isOCE=True
Cancel after Dispose: ObjectDisposedException
Register after Dispose: no throw
Register after Cancel+Dispose: no throw ran=True isCancelled=True
second Cancel after Cancel+Dispose: ObjectDisposedException
Cancel with throwing registration: AggregateException
registrations run inline on Cancel() caller: True
HonourAsync:      sig=20-01-15-12-80-9D-01-08-11-71     | param#1 ct attrs=None const=nil
DefaultedAsync:   sig=20-02-15-12-80-9D-01-08-08-11-71  | param#2 ct attrs=Optional, HasDefault const=NullReference
MiddleAsync:      sig=20-02-15-12-80-9D-01-08-11-71-08
NullableTokAsync: sig=20-01-15-12-80-9D-01-08-15-11-80-E1-01-11-71
TypeRef: System.Threading.CancellationToken scope=AssemblyReference
```

1. The exception a cancelled task rethrows is **not** one type: `TaskCanceledException`,
   `OperationCanceledException` and a user subclass all occur, and a `Faulted` task can carry an
   OCE too. All satisfy `is OperationCanceledException`. Hence the C#-side `is` test and not a
   name match, and hence the mapping keys on the exception, never on `Task.Status`.
2. `CancellationToken` is `ELEMENT_TYPE_VALUETYPE` (`0x11`) + a `TypeReference`; a default value
   changes only the `Param` row, not the signature.
3. `Cancel()` is inline and can throw; `Cancel()` after `Dispose()` throws.
4. A second spike ran the `ReleaseCancellation(h, 1)` body above verbatim (resolve, free, queue):
   `cancel=1: task Canceled, TaskCanceledException, registration ran on caller thread: False`,
   with a registration that throws, then `cancel=0: disposed, task RanToCompletion` and
   `process alive`. (`cts.Token` read after `Dispose()` throws `ObjectDisposedException`; the
   bridge never does that.)

**Verified by reading** kotlinx-coroutines-core 1.10.2 sources (the version in
`gradle/libs.versions.toml`):

5. `nativeMain/Exceptions.kt:9`: `public actual typealias CancellationException =
   kotlin.coroutines.cancellation.CancellationException`. Throwing the stdlib type from
   `nativeMain` **is** throwing the kotlinx type; no `expect`/`actual` is needed for the mapping.
6. `CancellableContinuationImpl.kt:201-216`: `cancel()` runs the handler synchronously on the
   cancelling thread, then `dispatchResume`. `:685-688`: a continuation cancelled after `resume`
   but before dispatch invokes **both** the cancel handler and `resume`'s `onCancellation`.

**Verified by execution during implementation, 2026-09-20** (`AwaitForKotlinTest`, Kotlin/Native
macosArm64, 11 arms green):

- A and B below both hold. A cancel that lands while `begin` is on the stack does reach the
  source (`a cancel landing while begin runs still cancels the source`), and an inline-completed
  call still releases it (`an already completed token call still releases the source`). They are
  kept in the Inferred list below as a record of what was assumed at design time.

**Inferred, nobody has run it:**

- A. `invokeOnCancellation` registered after the continuation was already cancelled fires
  immediately, and registered after it was already resumed is stored and fires only on the
  resumed-then-cancelled window (kotlinx docs, and the 1.10.2 source around
  `CancellableContinuationImpl.kt:410-445`). If wrong, a cancel that lands while `Begin` is
  running is lost: the C# work runs on, no handle leaks (the `finally` still releases it). Not
  silent under test: the new `AwaitForKotlinTest` arms below fail.
- B. The coroutine cannot leave `suspendCancellableCoroutine` before its block returns, even when
  the completion callback resumed it inline, so `source.value = begin(...)` is always visible to
  the `finally`. Language semantics (`suspendCoroutineUninterceptedOrReturn` runs the block, then
  `getResult()`), and ADR-152 claim A's inline-completion arm already depends on it. If wrong, one
  CTS `GCHandle` leaks per already-completed call; LeakTests Row "token, already completed, tight
  loop" is the guard.
- C. An `[UnmanagedCallersOnly]` thunk called from whichever thread ran `job.cancel()` is safe.
  The reverse bridge already calls `End` from `Dispatchers.Default` workers in
  `KennelRoundTripTests`, threads the CLR did not create, so this is the same mechanism.
- D. `kotlin.coroutines.cancellation.CancellationException` resolves from the generated
  `nativeMain` source set. It is common stdlib; if wrong it is a compile error, not silent.
- E. Not disposing a cancelled CTS leaks nothing beyond finalizer-delayed release of a
  `WaitHandle` the callee may have forced.
- F. The play-services precedent quotes (`cont.invokeOnCancellation { cancellationTokenSource.cancel() }`,
  `if (it.isCanceled) cont.cancel()`) were read through a fetched copy of `Tasks.kt` on `master`.

## Consequences

- Async methods taking one `CancellationToken` bind, with the token elided. They were skipped
  outright before, so this is new surface, not a changed one. `HttpClient`-shaped APIs
  (`GetStringAsync(string, CancellationToken)`) are the common beneficiary.
- A C#-originated cancellation now ends the awaiting Kotlin coroutine the way a cancelled
  `Deferred.await()` does: silently, unless caught. Documented on the topic page. The
  `NugetManagedException` rides as the `cause`, so `managedType` is not lost.
- Through the forward bridge (ADR-022) the round trip closes: C# cancel → Kotlin
  `CancellationException` → the forward export reports `isCancelled` → the outer C# `Task` is
  `Canceled`.
- `nuget_runtime_register` 5 to 7 slots; every fixture's baked runtime hash literal changes.
- Sync token-taking methods, multi-token methods and `CancellationToken?` stay skipped, now with
  a diagnostic that says why.
- A method that ignores its token behaves exactly as ADR-152 v1: the Kotlin wait stops promptly,
  the C# work runs to completion, and the task handle is freed when it does.
