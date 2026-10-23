# A plain (non-suspend) lambda parameter on a `suspend` member binds, its callback context living until the `Task` completes

- ROADMAP: line 75 as of 2026-10-22: "A plain (non-suspend) lambda parameter on a `suspend` member has no route for any payload, `T` or not: the per-call callback context would have to outlive the P/Invoke until the `Task` completes; found by ADR-215"
- Researched: 2026-10-22, about 12 minutes of a 35 minute budget shared with C1 and C2. Read-only, no Gradle run, no spike build.
- Restatement: forward. `suspend fun run(onStep: (Int) -> Unit): Int`, `suspend fun <T> decant(value: T, pour: (T) -> T): T` (and the same on a sealed arm, a generic class, top level and, once C1 lands, an object or companion) bind with a C# delegate parameter, `Task<int> RunAsync(Action<int> onStep, CancellationToken cancellationToken = default)`. The delegate's registered context lives until the coroutine ends and is released on completion, fault and cancellation, including when completion beats the P/Invoke's return.
- Verdict: fix. Reserved ADR-231. Recommend a **new ADR**: there are two genuine release mechanisms with different tradeoffs (alternatives 1 and 2 below), and the choice also decides the Flow-returning sibling (finding 8).

## Findings

Paths under `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/` unless stated. Checkout `kn-p4f-c`, 2026-10-22.

1. **Skip site.** `forward/ForwardLegacyRouteCollections.kt:289-332` (`legacyParameterShape(parameter)`): on a `suspend` host only a suspend lambda (`asyncCallbackOrNull`, `:296-300`) and a `Flow` (`:307-311`) get the `Async` shape; a plain lambda falls through to `legacyParameterShape(type)` (`:215-282`), whose type-argument branch ends in `Refused` (`:273-281`). `forwardSuspendRouteMethods` then drops the member on both halves (`forward/ForwardScopeOwnership.kt:77`), and the top-level route returns early on the same refusal (`exports/SuspendFunctionExports.kt:79`, `cir/CirFunctionTranslator.kt:48`). A `T`-result lambda is named by `refusedLegacyLambdaShape` with the ADR-215 sentence (`exports/ClassExports.kt:240-250`). **Verified by reading.**
2. **Fixture and parked test exist.** `test-library/src/nativeMain/kotlin/.../test/membergeneric/Drains.kt:72-76` (`Siphon.decant`, "Not bound ... named `SKIPPED_UNSUPPORTED_INPUT`") and the commented-out fact `IntegrationTests/MemberGenericAsyncTests.cs:435-455` (`Decant_PlainLambdaOverT_RunsInsideTheSuspendCall`). **Verified by reading.**
3. **The plain callback wire, C# side.** `forward/ForwardCirCallbackProjection.kt:72-148` (`forwardCallbackPrelude`): a managed delegate `xNative`, `IntPtr xCtx = IntPtr.Zero`, registered with `NugetThunks.RegisterCtx(xNative)`; the native arguments are `NugetThunks.<Shape>Ptr, xCtx` (`:160-164`); ADR-160's sync route unregisters in the `finally` after the call (`forwardCallbackCleanup`, `:150-154`). **Verified by reading.**
4. **The context table makes double release and late use safe.** `cir/CirCallbackRenderer.kt:41-69`: a `ConcurrentDictionary<IntPtr, object>`, keys from a pre-incremented `Interlocked` counter (never reused, never zero), `UnregisterCtx` is `TryRemove` (idempotent). A key miss in a user-code thunk (`ctxLookupPreamble`, `:159-177`) disposes the `IntPtr` payloads and returns for a `void` callback, or throws `ObjectDisposedException` through the error slot for a returning one. **Verified by reading.** So a Kotlin body that stores the lambda and calls it after the `Task` ended gets a clean no-op or a Kotlin-side `NugetManagedException`, never a use-after-free: the same late-use behaviour ADR-160 gives a stored lambda on a sync member.
5. **The plain callback wire, Kotlin side.** `forward/ForwardKotlinPlanEmitter.kt:2106-2108` and `:2208-...` (`loweredCallbackExpression`, `private`): one expression building a Kotlin lambda over the `(payloads..., ctx, errOut)` C function pointer, wrapped in `nugetCallbackCall`. It mints nothing and owns nothing, so it can be built in the suspend export's prelude and captured by the launch. **Verified by reading.**
6. **The suspend route already owns a key across the call, for a suspend lambda (ADR-210).** `cir/CirCollectionParameters.kt:96-154` (`collectionScopedCall`): the key is registered inside the `try`, handed over by zeroing the local after the call returns (`forwardAsyncCallbackHandOver`, `forward/ForwardCirCallbackProjection.kt:385-389`), and unregistered in the `finally` (no-op once zero). ADR-210 "Who owns the registered key" (`docs/adr/210-flow-and-suspend-lambda-parameters.md:313-330`). Kotlin then releases the key through the shared `NugetAsyncReleaseContext` thunk (`cir/CirCallbackRenderer.kt:533-537`, body `UnregisterCtx(a0)`), whose pointer rides as a per-export shared slot (`ForwardAsyncShared.RELEASE_CONTEXT`, `forward/ForwardCirCallbackProjection.kt:446`). ADR-210 releases it from a `createCleaner` holder (GC-driven). **Verified by reading.**
7. **The launch guarantees one terminal path.** `nuget-runtime/src/nativeMain/.../runtime/NugetLaunch.kt:35-57`: `scope.launch(start = CoroutineStart.ATOMIC) { try { body(); callback(...) } catch (CancellationException) { callback(cancelled) ; throw } catch (Throwable) { callback(error) } }`. ATOMIC means the body starts even when the job is cancelled before dispatch, so a `try/finally` inside `body` runs on success, fault and cancellation, and runs **before** the completion callback. **Verified by reading** (the ATOMIC start semantics themselves are kotlinx.coroutines documentation, **inferred**). The C# completion closure (`cir/CirConcurrencyRenderer.kt:185-235`) frees the completion `GCHandle` first thing, and the call-site catch (`:237-260`, `appendCompletionHandleCatch`) frees it only with `IsAllocated`, because "a callback that already ran has already freed it": the shipped code already handles completion beating the P/Invoke's return. **Verified by reading.**
8. **Sibling gap: a plain lambda on a `Flow`-returning member.** `legacyParameterShape(parameter)` admits only a `Flow` parameter on a plain-Flow host (`forward/ForwardLegacyRouteCollections.kt:304-311`); a plain lambda on `fun watch(onSeen: (Int) -> Unit): Flow<Int>` falls to the same `Refused`. Not a ROADMAP line. **Verified by reading** that it is refused; the design below covers it (rule 1).
9. **The context table is invisible to `nuget_live_handles`.** ADR-210 says so (`docs/adr/210-flow-and-suspend-lambda-parameters.md` "Pinned by `LeakTests/CollectabilityTests.cs` ... collectability rows, because the context table is invisible to `nuget_live_handles`"). **Verified by reading** the ADR; leak proof must be a collectability row (weak reference on the delegate's captured state), not only a live-handle count.

## Recommendation

**Alternative 1 (chosen): coroutine-scoped release from Kotlin, ADR-210's key-ownership rule.**

- New `ForwardLegacyParameterShape.Callback(type: BridgeType.Callback)` returned by `legacyParameterShape(parameter)` on a suspend host (and on a plain-Flow host) when `classify(type)` is a `Callback`. Its ABI slots are the plan's two (`<name>Ptr`, `<name>Ctx`), plus the export gains the shared `RELEASE_CONTEXT` slot if it has none yet.
- C#: the delegate is registered exactly as a suspend lambda's key is, through `collectionScopedCall`'s key list: registered in the `try`, handed over (local zeroed) after the extern returns, unregistered in the `finally` only if the extern never returned. Before the hand-over C# owns it; after it Kotlin does.
- Kotlin: the prelude builds the lambda with `loweredCallbackExpression` (made `internal`), and the launched body becomes `try { <call> } finally { releaseContext.invoke(<name>Ctx, null) }`. The `finally` runs on all three arms and before the completion callback (finding 7), so by the time C# observes the `Task` completed, the key is gone. On the Flow route the same `finally` wraps the `collectForCSharp` body, so the key is released when the collection ends (complete, fault, cancel, `DisposeAsync`); the C# collect delegate registers a fresh key per enumeration, inside the delegate, through the same hand-over (`collectionScopedCall` already supports a returning call across a hand-over: "the Flow-return route's shape", `cir/CirCollectionParameters.kt:108-114`).
- Completion beating the P/Invoke: Kotlin's `finally` may `TryRemove` the key before C# zeroes its local; C# then zeroes it and its `finally` is a no-op. A second `TryRemove` would be harmless anyway (finding 4).
- `null` error slot on the release call selects the thunk shell's FailFast branch, the cleaner's existing convention (`cir/CirCallbackRenderer.kt:532`); `UnregisterCtx` cannot throw, so this is inert (inferred from `TryRemove` semantics).

**Alternative 2: release from the C# completion closure.** Declare the key at method scope, unregister it in `appendAsyncCompletionClosure` beside `callbackHandle.Free()`, and in `appendCompletionHandleCatch`. No new Kotlin slot. Rejected: the Flow route has no per-member completion closure (the enumerator is shared, `cir/CirFlowRenderer.kt:117-240`), so the sibling in finding 8 would need a second mechanism; and a key local declared before the closure cannot use `collectionScopedCall`'s existing key handling, which is block-scoped.

**Alternative 3: ADR-210's cleaner-owned holder.** Release when Kotlin's GC collects the lambda. Rejected: the restatement asks for release at task completion, a C# delegate pinned until a Kotlin GC round is exactly the kind of hold the collectability rows cannot assert deterministically, and a plain lambda on a sync member is call-scoped (ADR-160), so call-scoped-to-the-coroutine is the consistent extension. (ADR-210's human gate chose cleaner lifetime for suspend lambdas and `Flow` parameters because those can be legitimately stored and invoked later; a stored plain lambda on a sync member already fails late, so no shipped expectation is broken.)

Alternatives 1 and 3 can coexist with the same holder if the main thread prefers "release at completion OR collection, whichever first"; not recommended, it doubles the paths to test for no consumer-visible gain.

## Consumer API

```csharp
// suspend fun run(onStep: (Int) -> Unit): Int
public Task<int> RunAsync(Action<int> onStep, CancellationToken cancellationToken = default)
// suspend fun <T> decant(value: T, pour: (T) -> T): T
public Task<T> DecantAsync<T>(T value, Func<T, T> pour, CancellationToken cancellationToken = default)
// fun watch(onSeen: (Int) -> Unit): Flow<Int>
public KotlinFlow<int> Watch(Action<int> onSeen)
```

`onStep` runs on a Kotlin dispatcher thread (as every forward callback invoked from a coroutine does); a throw from it faults the `Task` with the original exception (ADR-161's stash, already cleared by the completion closure).

## Files an implementation touches

- `forward/ForwardLegacyRouteCollections.kt` (new `Callback` shape, `legacyParameterShape(parameter)`, `ForwardLegacyNames` slot minting)
- `forward/ForwardKotlinPlanEmitter.kt` (`loweredCallbackExpression` to `internal`)
- `exports/SuspendFunctionExports.kt` (slots; `try/finally` release in `buildSuspendFunctionBody` / `buildSuspendMethodBody`), `exports/FlowExports.kt` (`buildFlowMethodCollectBody` and the property twin)
- `exports/ClassExports.kt:240-250` (the ADR-215 "binds only as a per-call lambda on an ordinary (non-suspend) member" sentence goes), `refusedLegacyLambdaShape` still applies its payload refusals
- `cir/CirCollectionParameters.kt` (a callback parameter's key joins the key list), `forward/ForwardCirCallbackProjection.kt` (register/hand-over for a plain callback), `cir/CirConcurrencyRenderer.kt` (nothing expected; check the `RELEASE_CONTEXT` gate), `cir/CirCallbackRenderer.kt` (emit `NugetAsyncReleaseContext` and the plain thunk shapes when a suspend/Flow member carries a plain callback)
- `ForwardAbiContract.kt` / `ForwardAbiLegacyRoutes.kt`
- Fixtures: `membergeneric/Drains.kt` (`decant` KDoc), a new non-generic fixture, e.g. `test-library/.../test/cat/Stopwatch.kt`
- Tests: `IntegrationTests/MemberGenericAsyncTests.cs` (uncomment `Decant_*`), new `IntegrationTests/SuspendPlainLambdaTests.cs`, `LeakTests/LiveHandleTests.cs`, `LeakTests/CollectabilityTests.cs`, Tier 1 (`Tier1GenericAsyncInputTest` cell flips)
- Docs: `docs/topics/supported-features.md`, `docs/topics/coroutines-and-flow.md`, ADR-215 ("Not bound, found by the red run" paragraph), ADR-210 cross-reference

## Sample test

Fixture:

```kotlin
class Stopwatch {
  suspend fun run(steps: Int, onStep: (Int) -> Unit): Int {
    repeat(steps) { delay(1); onStep(it) }
    return steps
  }
  suspend fun quick(onStep: (Int) -> Unit): Int { onStep(7); return 1 }   // no suspension point
  suspend fun stall(onStep: (Int) -> Unit): Int { onStep(0); awaitCancellation() }
  suspend fun crash(onStep: (Int) -> Unit): Int { onStep(0); delay(1); error("stopwatch jammed") }
  suspend fun label(n: Int, format: (Int) -> String): String { delay(1); return format(n) }
  fun watch(onSeen: (Int) -> Unit): Flow<Int> = flow { repeat(3) { onSeen(it); emit(it) } }
}
```

xunit (`IntegrationTests/SuspendPlainLambdaTests.cs`):

```csharp
[Fact]
public async Task Run_InvokesTheDelegateDuringTheSuspension()
{
    using var watch = new Stopwatch();
    var seen = new List<int>();
    Assert.Equal(3, await watch.RunAsync(3, seen.Add));
    Assert.Equal(new[] { 0, 1, 2 }, seen);
}

[Fact]
public async Task Label_ReturnsTheDelegateResult()
{
    using var watch = new Stopwatch();
    Assert.Equal("#4", await watch.LabelAsync(4, n => $"#{n}"));
}

[Fact]
public async Task Crash_FaultsTheTask()
{
    using var watch = new Stopwatch();
    await Assert.ThrowsAsync<KotlinException>(() => watch.CrashAsync(_ => { }));
}

[Fact]
public async Task Stall_CancelsThroughTheToken()
{
    using var watch = new Stopwatch();
    using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
    await Assert.ThrowsAnyAsync<OperationCanceledException>(() => watch.StallAsync(_ => { }, cts.Token));
}

[Fact]
public async Task Run_DelegateThrows_FaultsWithTheOriginalException()
{
    using var watch = new Stopwatch();
    var boom = new InvalidOperationException("tripped");
    var ex = await Assert.ThrowsAsync<InvalidOperationException>(() => watch.RunAsync(2, _ => throw boom));
    Assert.Same(boom, ex);
}

[Fact]
public async Task Watch_PlainLambdaOnAFlowMember_RunsPerItem()
{
    using var watch = new Stopwatch();
    var seen = new List<int>();
    var items = new List<int>();
    await foreach (int n in watch.Watch(seen.Add)) items.Add(n);
    Assert.Equal(items, seen);
}
```

Plus the uncommented `Decant_PlainLambdaOverT_RunsInsideTheSuspendCall` (`MemberGenericAsyncTests.cs:435-455`). Exception type names follow the existing suspend fault rows; check before writing.

LeakTests:

- `LiveHandleTests.SuspendPlainLambda_Complete_ReturnsToBaseline`: 200 x `RunAsync(2, ...)` and `LabelAsync`.
- `LiveHandleTests.SuspendPlainLambda_Fault_ReturnsToBaseline`: 200 x `CrashAsync` and 200 x `RunAsync` with a throwing delegate.
- `LiveHandleTests.SuspendPlainLambda_Cancel_ReturnsToBaseline`: 200 x `StallAsync` with a pre-canceled token, 50 x a 1 ms timeout, each awaited to completion.
- `LiveHandleTests.SuspendPlainLambda_TightLoop_CompletionBeforeReturn_ReturnsToBaseline`: 1000 x `QuickAsync` (no suspension point, so Kotlin's `finally` and the completion callback routinely run before the extern returns).
- `CollectabilityTests.SuspendPlainLambda_AfterCompletion_ItsClosureIsCollected`, `..._AfterFault_...`, `..._AfterCancellation_...`, `..._TightLoop_...`: a closure capturing a `WeakReference`d object, awaited, then GC and assert dead (finding 9). Under NativeAOT the fault row needs `await Task.Yield()` before polling (CLAUDE.md, the `ManagedFault_NotCaughtByKotlin_...` lesson), and the async-helper rows clear their local in a `NoInlining` helper before the yield.
- `CollectabilityTests.SuspendPlainLambda_LaterArgumentFailsItsNullGuard_ItsClosureIsCollected`: a member `suspend fun tag(onStep: (Int) -> Unit, name: String)` called with `null!` for `name`: the pre-P/Invoke path, the `finally` unregister.
- `CollectabilityTests.FlowPlainLambda_EarlyBreak_ItsClosureIsCollected`: `break` after the first `Watch` item.

## Deferred scope

None. The Flow-returning sibling (finding 8) is in scope.

## Open what-questions

1. Alternative 1 (Kotlin `finally` through `RELEASE_CONTEXT`) or 2 (C# completion closure)? Recommendation: 1, one mechanism for the suspend and Flow routes.
2. Does a stored plain lambda (Kotlin keeps `onStep` in a field and calls it after the `Task`) need anything beyond the key-miss behaviour of finding 4 (silent no-op for `void`, `ObjectDisposedException` for a returning lambda)? Recommendation: no, it matches ADR-160 on sync members; document it on the topic page.
3. Order relative to C1: C3 does not depend on C1, but once both land an `object`'s suspend member with a plain lambda must bind; a Tier 1 cell for that combination belongs to whichever lands second.

### Verified vs inferred

- Verified by reading: findings 1 to 9 (code paths, slot names, table semantics, launch arms).
- Inferred: ATOMIC-start semantics (kotlinx.coroutines docs, not re-read here), that `UnregisterCtx` cannot throw under the FailFast shell, that `collectionScopedCall`'s hand-over needs no change to carry a plain callback key, exception type names in the sample.
- Load-bearing and not spiked: "Kotlin's `finally` inside `body` runs before the completion callback on the cancel arm". Read from `NugetLaunch.kt:44-56` (the callback is invoked in the catch, after `body()` unwound through its `finally`), so it is verified by reading, but no test pins the ordering. The tight-loop and cancellation collectability rows are what would catch it if wrong.
