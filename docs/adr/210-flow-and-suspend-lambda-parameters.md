# ADR-210: `Flow<T>` and suspend lambda parameters: pull over the runtime's await seam, Kotlin-owned lifetime

## Status

Accepted. Slice (a), the suspend lambda parameter, and slice (b), the `Flow<T>` parameter, have both
shipped and the full verify ran green on JIT and NativeAOT (IntegrationTests 3518 passed; AOT 3514
passed, 4 skipped; LeakTests 260 and AOT LeakTests 260).

History: amended 2026-10-12 after an independent review ("accept with named changes"): the thunk
shell, the admitted v1 types, the slot layout, the thread wording, the lifetime note and the price
below are the amended text. Each amended point was re-read in source before it was written.
Amended again the same day with the human's gate decisions (memo questions 1 to 7, all decided as
recommended) and two spike results, recorded under "Spike results" below. Slice (a) corrected the
price and the `Throwable` result, and added the key-ownership rule. Slice (b) resolved the
`Throwable` flow element (refused by name), corrected the `Flow` element set to the classifier's
own, and settled the claims marked below as no longer inferred; an independent review of the
shipped slice supplied those corrections, each re-read in source.

## Context

A Kotlin declaration may take a `Flow<T>`, a `StateFlow<T>` or a `suspend (T) -> R` as a parameter.
Today they are named skips at most positions, not all (`SKIPPED_UNSUPPORTED_INPUT`; on a sealed
arm a suspend lambda parameter is `SKIPPED_UNSUPPORTED_COMBINATION`, reason
`SEALED_SUBCLASS_UNROUTED`); the research memo that cited each site was deleted when slice (b)
closed the item, and the decisions below are what it fed. One position is
worse than a skip: a `Flow<T>` parameter on a sealed-arm method that does not return a `Flow` is
SILENTLY DROPPED, absent from both halves with no diagnostic (**verified by a run**, spike 1
below; memo finding 9). The `Flow<T>` slice fixes that by binding the position. ROADMAP lists "`Flow` as a parameter" under "Deferred
to 1.x" as additive, so the design must not change the Gradle DSL, task names, public generated C#
types, the `nuget_*` runtime ABI, diagnostic codes or the `NugetDiagnostics.json` schema.

Three things constrain it.

1. **The argument outlives the P/Invoke.** A suspend lambda can only be invoked, and a `Flow` only
   collected, from a coroutine. On a `suspend fun` the body runs inside `launchForCSharp`, which
   returns the job handle at once (**verified by reading** `NugetLaunch.kt:35-57`). On a non-suspend
   function or a constructor the argument can only be stored. ADR-160's rule (free the C# context
   in the `finally` after the call, never bind at a storing position) cannot carry either.
2. **Kotlin must suspend until a C# `Task` completes.** The runtime already owns that:
   `awaitForKotlin` (**verified by reading** `NugetAwait.kt:73-103`, and by its tests,
   `AwaitForKotlinTest.kt:59-296`), shipped for the reverse bridge by ADR-152 and ADR-153. It is
   public, `@NugetRuntimeApi`, parameterised only on lambdas, and names nothing reverse-specific.
3. **The C# half of that seam exists only in the reverse shim** (`NugetTasks.Attach` and the
   enumeration thunks, **verified by reading** `NugetGenerateShimsTask.kt:2807-2965`), where it is
   reached through `nuget_runtime_register` slots. The forward bridge has no registration step.

SKIE, the closest outside analogue, does not bridge this direction at all ("Conversions in the
other direction (from `AsyncSequence` to `Flow`) are not supported",
https://skie.touchlab.co/features/flows; **inferred** from the docs page). The in-repo precedent is
ADR-156.

## Alternatives Considered

### 1. Pull through `awaitForKotlin` / `flowForKotlin`, flat pointer slots, cleaner-owned lifetime (chosen)

A suspend lambda is a Begin/End thunk pair awaited through `awaitForKotlin`. A `Flow<T>` is
`flowForKotlin` over `GetAsyncEnumerator`, one await per element. Thunk pointers ride as flat
`IntPtr` arguments on the per-declaration export. The Kotlin wrapper owns a `createCleaner` that
releases the C# context, the ADR-084 facet 4 rule.

- Pros: zero `nuget-runtime` change and zero new `nuget_*` export. Every callback into Kotlin stays
  exactly-once, so ADR-152's verified ownership rules carry over. Backpressure is inherent. Sound
  whether Kotlin uses the argument during the call or stores it, so constructors and non-suspend
  members bind too.
- Cons: three crossings per element. The C# closure stays alive until a Kotlin GC round. The
  export's signature grows (counts under Decision, "Slots").

### 2. Push: a C# `await foreach` pump into a Kotlin channel

Rejected for the reasons ADR-156 alternative 2 records: it needs a repeated callback while an
operation is in flight and a suspend-until-consumed handshake to get backpressure back.

### 3. Register the thunks once through a new `nuget_*` export

Shorter per-call signatures, but it adds to the runtime ABI that 1.0 freezes and needs a runtime
release, for nothing the per-call pointers do not already give.

### 4. One pointer to a pinned table of the fixed thunk addresses

Two slots per parameter instead of flat ones. Rejected: nothing in the forward bridge passes a
table today, so it would be a new wire shape for `ForwardAbiContract` and both emitters to model,
while flat function-pointer lists have a shipped precedent (ADR-084's factory is 2N+3 slots, 17
for the 7-member `IPet`; **verified by reading** `084-csharp-implemented-interfaces.md:109`).

### 5. Call-scoped lifetime: free the context when the P/Invoke returns or the outer `Task` completes

Deterministic release and no cleaner. A stored lambda or `Flow` then fails at its first late use
(ADR-161 part C makes that an `ObjectDisposedException`, not a use-after-free), and a constructor
or non-suspend member cannot bind at all. Rejected: the human gate chose the Kotlin-owned
lifetime at every position (2026-10-12, memo question 3).

### 6. `Func<T, Task<R>>`, no token

One parameter fewer for the C# author, but Kotlin cancellation could not reach the C# body: a
cancelled collector or outer `Task` would leave the C# work running to completion.

### 7. Bind `StateFlow<T>` from `IAsyncEnumerable<T>` plus an initial value through `stateIn`

Needs a scope that outlives the call and makes `.value` lag the C# source. Rejected: it is not a
`StateFlow` in any honest sense. Handle passthrough of a Kotlin-produced `KotlinStateFlow<T>` is
not generally possible either, because the property and method wrapper variants hold no flow
handle (**verified by reading** `CirFlowRenderer.kt:305-321`).

### 8. Adapt to the wire type in the generated closure, before `Attach` / `GetAsyncEnumerator`

The closure would turn the user's `Task<string>` into a `Task<IntPtr>` so one End thunk per wire
type serves every result. Rejected for v1: a result converted before Kotlin commits to reading it
is a Kotlin-owned `String` box that the cancel-then-complete path cannot free, because `release`
frees only the task handle (**verified by reading** `NugetAwait.kt:46`; the leak itself is
**inferred**, not run). Converting in End and Current, after the read is certain, has no such
window. It is the way to widen the vocabulary later, with a release thunk that knows the payload.

## Decision

### Consumer surface

| Kotlin parameter | C# parameter |
|---|---|
| `suspend (T) -> R` | `Func<T, CancellationToken, Task<R>>` |
| `suspend (T) -> Unit` | `Func<T, CancellationToken, Task>` |
| `Flow<T>` | `IAsyncEnumerable<T>` |
| `StateFlow<T>`, `SharedFlow<T>`, `MutableStateFlow<T>` | not bound: a named skip with its own sentence |

```kotlin
class TreatDispenser {
  suspend fun dispense(count: Int, prepare: suspend (Int) -> String): List<String> =
    (1..count).map { prepare(it) }
  suspend fun totalWeight(weights: Flow<Int>): Int = weights.fold(0) { sum, w -> sum + w }
}
```

```C#
public Task<IReadOnlyList<string>> DispenseAsync(int count, Func<int, CancellationToken, Task<string>> prepare, CancellationToken cancellationToken = default);
public Task<int> TotalWeightAsync(IAsyncEnumerable<int> weights, CancellationToken cancellationToken = default);
```

### Admitted v1 types

The data here flows C# to Kotlin, so the legacy Flow element vocabulary
(`ForwardLegacyRouteCollections.kt:911-1009`, Kotlin to C#) does not apply. The End and Current
thunks are per result type and can only cast what they name (`Task<int>`, `Task<string>`,
`NugetAsyncEnumeration<int>`). The v1 set is ADR-160's own, read from source:

- **Suspend lambda result** (C# to Kotlin): `isCallbackResult`
  (**verified by reading** `ForwardBridgeTypeClassifier.kt:1107-1132`): `Unit`, `Boolean`, `Byte`,
  `UByte`, `Short`, `UShort`, `Int`, `UInt`, `Long`, `ULong`, `Float`, `Double` (the eleven
  `PrimitiveKind`s, `ForwardMarshallingModel.kt:425-437`) and `String`. `Throwable` is in
  `isCallbackResult` only where the declaration accepts a `NugetManagedException`, but a
  `Throwable` suspend lambda result is **refused by name in v1** (slice (a) as shipped): the End
  thunk casts the `Task<R>` it names, and nothing proved a `Throwable` rides the `String` thunk
  unchanged.
- **`Flow` element** (C# to Kotlin): the eleven primitives and `String`, which is
  `isCallbackResult` minus `Unit` and minus `Throwable`. This is what `flowSourceOrNull`
  (`forward/ForwardBridgeTypeClassifier.kt`, **verified by reading**) admits; an earlier draft of
  this ADR said the set was exactly `isCallbackResult`, which is wrong for `Flow`. A `Throwable`
  flow element is **refused by name in v1**, at every position. A `Throwable` callback result is
  not the `String` thunk unchanged but a per-site adapter (`ForwardCirCallbackProjection.kt`
  renders `NugetMarshal.WrapString(managedExceptionTextCs(...))`), and a `Current` thunk, which
  casts the one element type it names, has no such adapter. Pinned by the Tier 1 test "a
  Throwable element is refused by name at every position".
- **Suspend lambda payload** (Kotlin to C#, one per invocation, arity 0 to 3): exactly
  `isCallbackPayload` (**verified by reading** `ForwardBridgeTypeClassifier.kt:1078-1085`): a
  primitive by value, `String`, an exported enum by ordinal, an exported object or interface over a
  handle, `Throwable`.
- Everything else (`Char`, an enum, object, interface or value class result or element, a
  collection, a nullable result or element) is refused by name and deferred.

No adapting closure converts a result to its wire type (alternative 8). The generated closure only
adapts payloads, as ADR-160's closures do, and returns the user's `Task<R>` as it is.

### Kotlin half

A sketch. **Verified by spike** (spike 2 below): this composition compiles and behaves as its
parts do against fake thunks. `begin` must return `COpaquePointer?` (`NugetAwait.kt:76`), so
`prepareBegin` returns the cancellation source handle and the lambda returns it; the first draft
of this sketch left that implicit.

```kotlin
val prepare: suspend (Int) -> String = { a0 ->
  val task: COpaquePointer = awaitForKotlin(
    release = { t -> releaseTask.invoke(t) },
    cancel = { source, cancelled -> releaseCancellation.invoke(source, if (cancelled) 1 else 0) },
    begin = { callback, ctx ->
      // prepareBegin returns the CancellationTokenSource handle (COpaquePointer?), which
      // awaitForKotlin owns from here and releases exactly once through `cancel`.
      val source: COpaquePointer? =
        nugetCallbackCall { err -> prepareBegin.invoke(a0, callback, ctx, holder.key, err) }
      source
    },
  )
  nugetCallbackCall { err -> prepareEnd.invoke(task, err) } // then the ordinary String read
}
```

`holder` is a small object the lambda captures, owning
`createCleaner(releaseFn to key) { (fn, key) -> fn.invoke(key, null) }`, the shape
`InterfaceBridgeFactoryExports.kt:61-63` already emits (**verified by reading**). The cleaner's
block captures nothing but the pair.

A `Flow<T>` parameter is `flowForKotlin(release, enumerate, moveNextBegin, moveNextEnd, current,
dispose)` with each user-code crossing wrapped in `nugetCallbackCall`, and the same holder.

### Thunk shell: its own body, never the ctx-dispatch shell

The new thunks must **not** render through `appendCtxDispatchThunk` or `ctxLookupPreamble`.
**Verified by reading**: `appendCtxDispatchThunk` takes the last parameter as the ctx
(`CirCallbackRenderer.kt:110`), and on a key miss `ctxLookupPreamble` calls `NugetMarshal.Dispose`
on every `IntPtr` slot ahead of it (`CirCallbackRenderer.kt:157-175`), which is right only because
every such slot on the four existing routes is a Kotlin `StableRef` (its own comment, `:151-155`).
A Begin thunk carries the completion function pointer and `awaitForKotlin`'s pending `StableRef` in
`IntPtr` slots. Through the existing shell a late call would `nuget_dispose` a function pointer and
free the pending ctx, which `awaitForKotlin` then releases a second time at `NugetAwait.kt:89`
(each step read; the crash itself **not run**).

Signatures, key last before `errOut`:

| Thunk | Signature | Key |
|---|---|---|
| Begin (per payload wire shape) | `(payload..., callback, awaitCtx, key, errOut) -> IntPtr source` | yes |
| End (per result type) | `(task, errOut) -> R` | no |
| Enumerate (one shape) | `(key, errOut) -> IntPtr enumeration` | yes |
| Current (per element type) | `(enumeration, errOut) -> T` | no |
| MoveNextBegin, MoveNextEnd, Dispose enumeration | as the reverse shim's | no |
| Release task, release cancellation | `(task)`, `(source, cancelled)` | no |
| Release context | `(key, null)`, called by the cleaner | yes |

What a key miss does, slot by slot. Under the cleaner-owned lifetime a miss should be unreachable
(the key is removed only once the holder is unreachable; **inferred**), but the body defines it:

- **Begin.** A handle-passed payload slot (`String`, object, interface, `Throwable`) is disposed
  with `NugetMarshal.Dispose`, because the delegate read that would have freed it never runs: the
  existing ownership rule, applied to payload slots only. A by-value payload needs nothing.
  `callback` is never disposed and never invoked. `awaitCtx` is never touched by C#: it stays owned
  by `awaitForKotlin`, which releases it exactly once at `NugetAwait.kt:89` when `begin` throws. No
  `CancellationTokenSource` is minted, no `Task` exists, no task handle is allocated, `Attach` is
  never called, so the completion callback never fires. The thunk reports
  `ObjectDisposedException` through `errOut` and returns `IntPtr.Zero`; `nugetCallbackCall` checks
  the slot before the return value and throws.
- **Enumerate.** No enumeration handle is minted. `ObjectDisposedException` through `errOut`,
  `IntPtr.Zero` returned. `flowForKotlin` calls `enumerate` outside its `try`
  (**verified by reading** `NugetFlow.kt:56-59`), so `dispose` is not called with a handle that was
  never minted.
- **Every keyless thunk** acts on a task, source or enumeration handle that Kotlin holds exactly
  once, so it has no miss case. On every non-miss path the task handle is owned as in ADR-152:
  freed by End's `finally`, or by release task when the await was cancelled before completion.

### Slots

Flat `IntPtr` slots, no table. Thunks that do not depend on the parameter are passed **once per
export**, not once per parameter.

- Per parameter: 3 for a suspend lambda (Begin, End, key); 3 for a `Flow` (Enumerate, Current, key).
- Once per export: release task and release context, always; release cancellation when the export
  has a suspend lambda parameter; MoveNextBegin, MoveNextEnd and Dispose enumeration when it has a
  `Flow` parameter.
- So: one suspend lambda, 6 slots; one `Flow`, 8; two suspend lambdas, 9; one of each, 12.

On the legacy routes this is new ground for `nativeImportParameters()`, which fans one parameter
out to at most three slots today and has no export-level slots (**verified by reading**
`CirCollectionParameters.kt:54-65`).

### C# half

`Interop.cs` gains, only when a bound member needs them, internal forward twins of
`NugetTasks.Attach` and `NugetAsyncEnumeration` / `NugetAsyncEnumeration<T>` in the root namespace,
and the thunks above in `NugetThunks`, registered first-wins as ADR-102 and ADR-160 do. There is no
name collision with the reverse shim: its `NugetTasks` lives in `<root>.NugetReverse`
(**verified by reading** `NugetGenerateShimsTask.kt:80-83`, used at `:2651`) and the forward
helpers render in `<root>` (`CirRenderer.kt:148-152`). Rules copied from the reverse shim, each
**verified by reading** there and **inferred** for the forward copy until it is run:

- `Attach` uses `TaskContinuationOptions.None` on `TaskScheduler.Default`, and frees the task
  handle itself if attaching throws.
- Begin mints the `CancellationTokenSource` before the user call and allocates its handle last.
- End reads through `GetAwaiter().GetResult()` and frees the task handle in `finally`.
- Dispose queues cancel, await pending, then `DisposeAsync`, inside `Task.Run` with an outer
  catch-all. `DisposeAsync` during a pending `MoveNextAsync` throws `NotSupportedException`
  (**verified by spike** in ADR-156, not re-run here).
  `NugetAsyncEnumeration.Pending` defaulting to `Task.CompletedTask` is load-bearing: when the
  synchronous prefix of `MoveNextAsync` throws inside MoveNextBegin, `Pending` is never assigned,
  and Dispose then awaits the completed default before `DisposeAsync`.
- User-code thunks (Begin, End, Enumerate, Current, MoveNextBegin, MoveNextEnd) carry ADR-161's
  trailing `IntPtr* errOut`; the context is ADR-161 part C's never-reused key.

### Type model: two dialects, because the codebase forces it

Two new `BridgeType` leaves on the ADR-062 plan (working names `AsyncCallback` and `FlowSource`),
so sealed arms, objects, top-level functions, extensions and constructors bind by position. That
is not enough on its own: the realistic host, a `suspend fun`, is not on the plan. It is on the
legacy suspend route, which classifies its parameters separately and refuses these shapes
(**verified by reading** `ForwardLegacyRouteCollections.kt:235-246`, `SuspendFunctionExports.kt:72`).
So the feature also needs a new `ForwardLegacyParameterShape` arm, threaded through
`legacyRouteParameters` (`CirCollectionParameters.kt:128-177`), the `CirMethod` producers
(`CirFunctionTranslator.kt`, `CirClassTranslator.kt`) and the C# call site `renderAsyncMethod`
builds (`CirConcurrencyRenderer.kt:284-541`). It lands on both dialects; neither alone satisfies
the item.

Slice (b) needed no change to `CirConcurrencyRenderer.kt`. Slice (a)'s `guardsKey` check
(`method.parameters.any { parameter -> parameter.asyncKeyLocal != null }`) is kind-agnostic, and
`FlowSource` rides the same `ForwardLegacyParameterShape.Async` path as `AsyncCallback`, so the
registered-key guard around the native call covers a `Flow` parameter unchanged.

### Lifetime

- The cleaner cannot fire during a flight. The pending record `awaitForKotlin` retains holds the
  continuation (**verified by reading** `NugetAwait.kt:23-26`, `:81`), and the continuation keeps
  the holder reachable (**verified by spike**, spike 2 below, on a debug test binary only).
- A C# lambda that ignores its token after Kotlin cancels keeps one counted ctx handle alive until
  its `Task` really completes: the ctx is released only by the completion callback
  (**verified by reading** `NugetAwait.kt:42`). That is a bounded hold, not a leak, and the cancel
  leak row must await the C# task's real completion before it asserts the baseline.

### Who owns the registered key, and when (added with slice (a))

The lifetime rule above starts at the Kotlin holder. The implementation had to add the rule for the
window before that, because the C# call site registers the delegate in the context table before the
P/Invoke, and a call that fails first would otherwise strand the key (and the delegate it roots)
with no Kotlin holder ever built to release it.

- C# owns a registered key from registration until the native call returns. The generated call site
  unregisters it on any earlier exit: `forwardCirHandleScope` on the plan route, `collectionScopedCall`
  on the legacy suspend route. Once the call has returned the local is zeroed and Kotlin owns the key.
- The Kotlin export builds the cleaner-owning holder (`NugetAsyncContext`) before anything else in the
  export that can throw: the first statement on the plan route, the first statement of the argument's
  block on the legacy suspend route (after the receiver and scope lookups). From then on the
  cleaner owns the key.
- A member that takes a suspend lambda also frees the completion callback's `GCHandle` when the call
  throws before the P/Invoke (a later argument refused by its null guard, or a disposed wrapper). The
  catch around the call does it. A suspend member that takes no suspend lambda leaked that handle
  when a later argument failed its null guard (**verified by a failing test**); the amendment below
  extends the same catch to it. The "or a disposed wrapper" case never leaked on a member without a
  suspend lambda, because the `_handle.IsInvalid` check runs before the callback is allocated.
- Pinned by `LeakTests/CollectabilityTests.cs`: `SuspendLambdaParameter_CallOnADisposedWrapper_ItsClosureIsCollected`,
  `SuspendLambdaParameter_LaterArgumentFailsItsNullGuard_ItsClosureIsCollected` and its control
  `SuspendLambdaParameter_LambdaBeforeAString_AfterTheCallCompletes_ItsClosureIsCollected`. They are
  collectability rows, because the context table is invisible to `nuget_live_handles`.

**Amendment (2026-10-12): the completion-handle rule holds at every suspend call site.** The catch
that frees the completion callback's `GCHandle` is no longer specific to members that take a suspend
lambda. One shared helper, `appendCompletionHandleCatch` in `cir/CirConcurrencyRenderer.kt`, emits
`catch { if (callbackHandle.IsAllocated) callbackHandle.Free(); throw; }` around the native call at
every site that allocates a completion handle: `renderAsyncMethod` (instance, static and top-level,
sealed arm, interface, and `Flow`- or `StateFlow`-returning members), the four
`KotlinSuspendFunc`/`KotlinSuspendAction` holder invokes in `cir/CirFunctionRenderer.kt`,
`DrainAndDisposeAsync` in `cir/CirClassRenderer.kt`, and `EmitAsync` in `cir/CirSharedFlowRenderer.kt`,
which already had it. The invariant is that C# owns the completion `GCHandle` from allocation until
the native call returns, and frees it on any earlier exit; after the call returns, only the
completion callback frees it. A scan of the regenerated `Interop.cs` found 395 allocations and 395
freeing catches. The decision above is unchanged.

- Pinned by `LeakTests/CollectabilityTests.cs`: `SuspendCompletion_MemberArgumentFailsItsNullGuard_ItsStateIsCollected`
  and `SuspendCompletion_TopLevelArgumentFailsItsNullGuard_ItsStateIsCollected` (the two that were red),
  `SuspendCompletion_MemberCallOnADisposedWrapper_ItsStateIsCollected` (a guard on the disposed-check
  ordering, not a reproduction), and the controls `SuspendCompletion_MemberCallCompletes_ItsStateIsCollected`
  and `SuspendCompletion_TopLevelCallCompletes_ItsStateIsCollected`.
- Pinned at Tier 1 by `Tier1SuspendCompletionHandleFreeTest`, which asserts the catch text on the
  routes a running test does not reach (sealed arm, interface, `Flow`- and `StateFlow`-returning
  members, the suspend lambda holders and `EmitAsync`).

### Semantics a consumer can rely on

- Cold: the C# enumerable's `GetAsyncEnumerator` is called once per Kotlin `collect`. Whether a
  second collect replays is the C# source's own property.
- A C# throw (synchronous, a faulted `Task`, or mid stream) reaches Kotlin as
  `NugetManagedException` at the invocation or collection site.
- A cancelled C# `Task` cancels the invoking coroutine (ADR-161 `kind = 1`).
- Kotlin cancellation cancels the token the C# lambda or enumerator received. A C# iterator that
  ignores its token stops at its next element (ADR-156 claim 2).
- Threads: only the **synchronous prefix** of the C# lambda, up to its first `await` that does not
  complete synchronously, runs on the Kotlin dispatcher thread that invoked it (inside Begin; for a
  `Flow`, inside Enumerate and each MoveNextBegin). What follows resumes where .NET schedules it;
  that thread carries no `SynchronizationContext`, so in practice the thread pool (**inferred**,
  standard .NET behaviour, not run). The completion callback into Kotlin always arrives on
  `TaskScheduler.Default` (**verified by reading** `NugetGenerateShimsTask.kt:2946-2955`, which the
  forward twin copies). The caller's own context is never captured.

### Inferred claims, listed

1. No longer inferred: the Kotlin composition above compiles and behaves as its parts do
   (**verified by spike**, spike 2 below). The spike did not cover real C# thunks or the `Flow`
   composition over `flowForKotlin`; both are now **verified by the shipped slices' tests** (the
   full verify ran green on JIT and NativeAOT, figures under Status).
2. The forward copies of the C# rules behave as the reverse originals. No longer wholly inferred:
   the full verify ran green on JIT and NativeAOT. If the Dispose order is copied wrongly the
   failure is SILENT: a leaked running iterator. The row that guards it is
   `FlowParameter_OuterTokenCancelledMidStep_ReturnsToBaseline`, whose iterator ignores its token.
   The early-abort row does **not** discriminate: no `MoveNextAsync` is pending when dispose runs
   there, so a wrong order passes it.
3. The key-miss branch is unreachable under the cleaner-owned lifetime. Half of this is no longer
   inferred: the holder stays reachable for the length of a flight (**verified by spike**, spike 2
   below, debug test binary only; an optimised release binary was not tried). Unreachability on
   every other path is still reasoned, not run. If wrong: the miss branch above is what runs, and
   it is defined to be safe.
4. **Verified by reading, and it is a gap**: `LeakTests` cannot observe the C# context table
   today. `NugetThunks` exposes only `RegisterCtx`, `UnregisterCtx` and `LookupCtx` over a private
   `_ctxTable` (`CirCallbackRenderer.kt:39-62`), and `nuget_live_handles` counts Kotlin handles
   only. A leaked C# key on this route is SILENT until the implementation adds an internal count or
   asserts collectability through a `WeakReference`. This ADR requires one of the two.
5. NativeAOT accepts the new thunks. ADR-102's lane proves the pattern, not these thunks.
6. Which exception object the C# caller of the outer `Task` sees when a C# lambda's fault escapes
   Kotlin uncaught. The `_lastManagedFault` stash is written on a Kotlin worker thread and the
   completion arrives on possibly another thread (with no dispatch between the throw and the
   coroutine completing, it runs on the thread that stashed; inferred, not run), so the
   caller may get the mapped exception rather than the original object; the rebuild path covers
   `OperationCanceledException` only (ADR-161's 2026-10-07 amendment records the same inference for
   the suspend and `Flow` exports, `161-...md:336-338`). Not run. Update: ADR-161's 2026-10-12
   amendment verifies the no-dispatch case (the original object comes back) and clears the stash at
   the completion; the thread-hopping case stays unexercised.

### Gate decisions (2026-10-12, by the human)

1. One ROADMAP line, shipped as two stacked PRs: (a) the suspend lambda parameter, then (b) the
   `Flow<T>` parameter with the `StateFlow<T>` refusal.
2. A `StateFlow<T>`, `SharedFlow<T>` or `MutableStateFlow<T>` parameter is refused by name, with
   the `Flow<T>` plus `stateIn` hint.
3. Kotlin-owned lifetime through `createCleaner` at every position.
4. The delegate shape carries the token: `Func<T, CancellationToken, Task<R>>`.
5. Constructors and non-suspend members bind.
6. The thread behaviour is documented with the corrected wording above; nothing marshals back.
7. Flat slots, the fixed thunks once per export.

Questions 4 to 7 were accepted as recommended: the research and the independent review agreed and
the human raised no objection.

### Spike results (run 2026-10-12 by a kotlin-dev; recorded here from the coordinator's report)

1. **Tier 1, kept in the repo**:
   `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1AsyncParameterSealedArmTest.kt`,
   4 tests green, pinning today's behaviour on a sealed arm `Shape.Circle`:
   - (a) `fun watchFlow(ticks: Flow<Int>): Int` is a SILENT DROP: absent from `Interop.cs` and
     `CNameExports.kt`, no diagnostic (the by-reason exemption at
     `ForwardCallablePlanner.kt:2055-2056`).
   - (b) `fun watchPrepare(prepare: suspend (Int) -> String): Int` is named once,
     `SKIPPED_UNSUPPORTED_COMBINATION`.
   - (c) `suspend fun watchFlowSuspended(ticks: Flow<Int>): Int` is named once,
     `SKIPPED_UNSUPPORTED_INPUT`, under the package-less symbol `Shape.Circle.watchFlowSuspended`,
     unlike (b)'s qualified one.
   Slice (b) fixes (a) by binding the position; its `DEFECT` test then flips.
2. **Scratch `nativeTest`, deleted after the run** (so its source cannot be re-read; the result is
   as reported): the Kotlin half above composed with fake thunks, 5 of 5 passed on
   `:nuget-runtime:mingwX64Test`.
   - completes from a Worker thread;
   - completes inline before `begin` returns;
   - an error written through `err` throws `NugetManagedException` at the invocation site, with
     `NugetHandles.live` back at baseline;
   - cancel while pending calls `cancel` once with `cancelled = true`, and the late completion
     releases the task handle once with End never called;
   - over a 633 ms pending window with three `GC.collect()` calls, an unreferenced control holder
     was cleaned and the pending one was not; one round after completion released it exactly once.
     Debug test binary only.

## Consequences

- `Flow<T>` and suspend lambda parameters bind at every ordinary position, sealed arms included,
  for the v1 types listed above. The `UNROUTED_POSITION` sentences and hints for both details
  change, and `Tier1UnroutedPositionsTest` loses the cells that now bind.
- `StateFlow<T>` at a parameter stays unbound, with a sentence that names the reason and the
  `Flow<T>` plus `stateIn` remedy. Handle passthrough is deferred to 1.x.
- Nothing in `nuget-runtime` changes; no runtime version bump is required by this ADR.
- Price: slice (a), as shipped, changed 25 processor files and added one, `forward/ForwardAsyncCallback.kt`
  (not the "about 17" first estimated). Slice (b) changed 24 processor files and added none, since
  it extends slice (a)'s leaf, thunk and slot machinery; `nuget-runtime` is untouched.
- A C# closure passed for one of these parameters is released by a Kotlin cleaner, not at the end
  of the call. Leak rows for the route settle through the existing cleaner round.
- For the implementer: the `createCleaner` import is emitted only when the module has bridge
  factories (**verified by reading** `NugetProcessor.kt:3620-3623`) and must be widened; each
  export that builds a holder needs `@OptIn(ExperimentalNativeApi::class)`, as the bridge factory
  export carries (`InterfaceBridgeFactoryExports.kt:83`).
- The new thunks run user C# on Kotlin dispatcher threads, which widens the reach of the
  `_lastManagedFault` stash residual, since closed by the ADR-161 2026-10-12 amendment (the
  completion now clears the stash). The completion that reads the stash may
  arrive on another thread, but need not: with no dispatch between the throw and
  the coroutine completing, it runs on the thread that stashed (verified there for an uncaught fault).
- Deferred: every result, element and payload type outside the v1 list, a `Throwable` suspend lambda
  result and a `Throwable` flow element included; nullable `Flow<T>?` and
  nullable suspend lambda parameters; arity above 3; batch pull; a plain `(T) -> R` parameter on a
  `suspend fun` (refused today by the same legacy gate). Amended 2026-10-22: the nullable
  `Flow<T>?` parameter binds; see the amendment below.

## Amendment (2026-10-22): a nullable `Flow<T>?` parameter

A `Flow<T>?` parameter binds as `IAsyncEnumerable<T>?` wherever the non-null `Flow<T>` parameter
does: the ADR-062 plan (top level, object, companion, class, enum, value class) and the legacy
`suspend` and Flow-returning hosts, generic `T` included. The slots are unchanged. C# registers the
source only when it is not `null` (`flap is null ? IntPtr.Zero : NugetTasks.Register(...)`), so a
`null` passes a zero key and registers nothing. Kotlin declares that key slot `COpaquePointer?`,
builds no holder for a zero key, and hands the member `null`; a non-zero key is the ordinary cold
flow. The other slots are thunk addresses C# always passes. `StateFlow<T>?` is not in scope: the
input route takes no `StateFlow` at all (C# has no source for its current value).

Verified by the fixture `purrclock/Doorways.kt` with `IntegrationTests/NullableFlowParameterTests.cs`
(each owner, a `suspend` member, a generic `T`, `null` and present), the leak row
`NullableFlowParameter_NullAndPresent_ReturnToBaseline`, and `Tier1NullableFlowParameterTest`.
`Tier1FlowParameterTest`'s `maybe` cell flipped to binding, and `Tier1NullableParameterDiagnosticTest`
names its unwired nullable parameter with a nullable suspend lambda instead.

## Amendment (2026-10-22): a plain lambda reuses the release thunk with a coroutine-scoped owner

[ADR-231](231-plain-lambda-on-suspend-and-flow-members.md) hands a plain lambda's context key to
Kotlin through the same `NugetAsyncReleaseContext` thunk and the same hand-over described under
"Who owns the registered key", but the owner is the coroutine, not a `createCleaner` holder: a
`finally` around the launched body or the `.collect` releases it, and a `suspend` member returning a
`Flow` ties it to the returned flow's handle. The cleaner-owned lifetime above is unchanged for a
`suspend` lambda and a `Flow` parameter.
