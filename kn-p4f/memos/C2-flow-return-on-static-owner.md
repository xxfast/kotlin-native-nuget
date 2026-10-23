# A `Flow` return on an `object`, companion or top-level function binds as a static `KotlinFlow<T>`

- ROADMAP: line 74 as of 2026-10-22: "A `Flow` return on an `object`, companion or top-level function has no route, generic or not (a static owner has no instance coroutine scope to collect on); the generic form gets the Flow route's own `SKIPPED_UNSUPPORTED_RETURN`; found by ADR-215"
- Researched: 2026-10-22, about 10 minutes of a 35 minute budget shared with C1 and C3. Read-only, no Gradle run, no spike build.
- Restatement: forward. `fun ticks(): Flow<Int>` at top level, `object Clock { fun ticks(): Flow<Int> }`, `Den.Companion.fun doors(): Flow<String>`, their generic forms (`fun <T> repeat(value: T): Flow<T>`) and the hot variants (`StateFlow<T>`, `MutableStateFlow<T>`, `SharedFlow<T>`, `MutableSharedFlow<T>`) bind as `public static KotlinFlow<int> Ticks()` (and `KotlinStateFlow<T>` / `KotlinMutableStateFlow<T>` / `KotlinSharedFlow<T>` / `KotlinMutableSharedFlow<T>`) on the file-facade class, the object's static class and the companion's owner class.
- Verdict: fix. Reserved ADR-230. Recommend an **amendment to ADR-026** (Flow mapping), with the scope rule stated once: the static-owner scope question has precedent in the shipped ADR-068 2026-09-27 amendment (null scope, ad-hoc root scope, the enumerator's job is the only cancellation handle), so it is not a new decision. Write ADR-230 only if the main thread prefers alternative 2 below.

## Findings

Paths under `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/` unless stated. Checkout `kn-p4f-c`, 2026-10-22.

1. **Skip site.** A static owner's member is planned by `staticEntry` (`forward/ForwardCallablePlanner.kt:2652-2712`); a `Flow` return classifies as a `flow ...` `SpecializedProtocol` and is skipped `FLOW_PROTOCOL` at RETURN (`:5982-5983`, `:5887`). The object and companion walks then call `nameUnroutedPositions()` with the default `isRouted = { false }` (`:2508`, `:2648`; the helper at `:468-488`), which rewrites it to `UNROUTED_POSITION`, rendered `SKIPPED_UNSUPPORTED_RETURN`. A class walk passes an `isRouted` that recognises its legacy Flow route. The generic form is refused first by `forwardMemberGenericRefusal()` under ADR-215 (`docs/adr/215-type-parameter-in-lambda-and-flow.md:86-95`). **Verified by reading** for the object and companion walks; the top-level walk's call is **inferred** to be the same shape (not opened).
2. **The legacy Flow route is instance-keyed.** `exports/FlowExports.kt:327-470` (`addFlowMethodExports`): the plain-Flow export is `export_<prefix>_<name>_collect(handle, scopeHandle, params..., onNext, onComplete, onError, userData)`, body (`buildFlowMethodCollectBody`, `:619-657`) reads `handle.asStableRef<Owner>()` and `scopeHandle.asStableRef<CoroutineScope>()`, then `collectForCSharp(scope, ...) { emit -> owner.f(...).collect { ... } }`: the function is **re-invoked per C# collection**. `MutableStateFlow` and `MutableSharedFlow` returns are **held** instead: one acquire export returns the flow's own handle (`:417-461`). C# (`cir/CirFlowRenderer.kt:544-590`, `renderFlowMethod`) guards `_handle.IsInvalid` and passes `GetOrCreateScope()`. **Verified by reading.**
3. **A null scope already means "ad-hoc root scope" on every flow-keyed seam.** `exports/SuspendFunctionExports.kt:478-506` (`handleKeyedFlowCollectExport`): `scopeHandle: COpaquePointer?`, `scopeHandle?.asStableRef<CoroutineScope>()?.get() ?: CoroutineScope(Dispatchers.Default)`, KDoc: "A null scope launches on an ad-hoc root scope, the top-level routes' shape". The runtime's `nuget_stateflow_collect` does the same (`nuget-runtime/src/nativeMain/.../runtime/NugetRuntime.kt:929-958`, ADR-068 2026-09-27 amendment comment). The flow-keyed shared seams take a nullable scope too (`exports/SharedFlowExports.kt:244-252`); the handle-keyed property seams do not (`:174-180`). **Verified by reading.**
4. **C# already renders the static case for an awaited flow.** `cir/CirConcurrencyRenderer.kt:456-461` and `:532-534`: a static (top-level) suspend member returning `StateFlow`/`Flow` passes `NugetKotlinHandle.Null` as the collect scope. So `suspend fun watch(): Flow<Int>` at top level ships today, collecting on an ad-hoc scope. **Verified by reading.** That is the precise precedent for a static non-suspend Flow return.
5. **Cancellation and cleanup do not need an owner scope.** `collectForCSharp` (`nuget-runtime/.../NugetLaunch.kt:74-115`) returns a `NugetFlowCollection` handle that is the job; `KotlinFlowEnumerator` cancels it on token or `DisposeAsync` (`cir/CirFlowRenderer.kt:117-240`, `NugetJobNative.Cancel`). `NugetFlowCollections.track` removes the job on completion (`NugetLaunch.kt:141-148`), so an ad-hoc root collection leaves no tracking entry behind. **Verified by reading.** The instance scope exists for one extra job, cancelling every live collection when the owner is `DisposeAsync`ed; an `object`, a companion and a file facade are process-lifetime statics with no dispose, so there is nothing for an owner scope to do.
6. **`KotlinFlow` can own a scope if wanted.** `cir/CirFlowRenderer.kt:84-115`: `KotlinFlow(startCollect, read, ownedHandle, release, ownedScope)`; `Dispose()` cancels `ownedScope` then releases `ownedHandle` (ADR-208 part E, used at `cir/CirFlowArguments.kt:40-57` with `new NugetScopeHandle(NugetScopeNative.Create())`). **Verified by reading.** This is alternative 2.
7. **Sibling gap: Flow, StateFlow and SharedFlow PROPERTIES on a static owner** (`object Clock { val ticks: Flow<Int> }`, a companion `val`, a top-level `val`). `forward/ForwardPropertyPlanner.kt:1431-1436`: only `position == ForwardPropertyPosition.CLASS` is legacy-routed; every static position is a named `SKIPPED_UNSUPPORTED_PROPERTY` (comment `:1378-1383`: "a `StateFlow` property on a COMPANION, at TOP LEVEL, and ... on an OBJECT produced no diagnostic ... neither has a flow adapter"). Not a ROADMAP line. **Verified by reading.** Same design applies; folded into scope below (rule 1).

## Recommendation

Mirror the instance Flow route with a **static receiver and no scope slot**, every flow kind, both halves, functions and properties.

- **Plain `Flow<T>` / `StateFlow<T>` / `SharedFlow<T>` return (re-invoked):** Kotlin `export_<stem>_collect(params..., onNext, onComplete, onError, userData)` with body `collectForCSharp(CoroutineScope(Dispatchers.Default), ...) { emit -> <Receiver>.f(...).collect { emit(box) } }`, `<Receiver>` being `com.pkg.Clock`, `com.pkg.Den` (companion) or the package-qualified top-level name. No `handle`, no `scopeHandle`. `StateFlow` also gets `export_<stem>_value(params...)`. C#: `public static KotlinFlow<int> Ticks() => new KotlinFlow<int>((onNext, onComplete, onError, userData) => Native_Ticks_collect(onNext, onComplete, onError, userData));` with no `_handle` guard and no `GetOrCreateScope()`.
- **`MutableStateFlow<T>` / `MutableSharedFlow<T>` return (held):** acquire export `export_<stem>(params...)` returning the flow handle; then the existing flow-keyed `_collect` / runtime `nuget_stateflow_collect` / flow-keyed shared seams with `NugetKotlinHandle.Null` for the scope, which finding 3 shows they already accept.
- **Properties:** `export_<stem>_<prop>_collect(onNext, ...)` reading `<Receiver>.ticks` per collection (the instance property route's re-read semantics), and the held variants for `MutableStateFlow` / `MutableSharedFlow` `val`s with flow-keyed seams.
- **Generic `fun <T>`:** ADR-215 lane B's boxed element and trampoline (`flowTrampoline(func, owner = null, ...)`) unchanged; only the receiver differs. The GENERIC refusal for a static Flow return is lifted in `forwardMemberGenericRefusal()`'s caller for these owners.
- **Planner:** pass an `isRouted` to `nameUnroutedPositions()` in `objectEntries`, `companionEntries` and the top-level walk that recognises the new route (`FLOW_PROTOCOL` at RETURN on a member the static Flow selector admits), exactly as the class walk does. A new selector (`forwardStaticFlowRouteMethods(owner)` in `forward/ForwardScopeOwnership.kt`) is read by the emitter, the C# translator and the diagnostic walk.

Alternatives considered:

1. **Ad-hoc root scope per collection, no owner scope (chosen).** Matches the ADR-068 amendment and the shipped awaited-flow static path (finding 4), so a static `suspend fun f(): Flow<Int>` and a static `fun f(): Flow<Int>` behave the same. Each collection is cancelled by its own enumerator. Nothing to dispose on a static class.
2. **`KotlinFlow` owns a `NugetScopeHandle` per returned flow (ADR-208 part E).** `Dispose()` on the returned `KotlinFlow` would cancel all its live collections. Rejected for v1 behaviour parity: no other static surface owns a scope, it mints one handle per call even when never enumerated (visible in `NugetMarshal.LiveHandles`), and an undisposed `KotlinFlow` would hold it until finalization. Pick this one if the main thread wants `using var flow = Clock.Ticks();` to cancel every enumeration; then it is an ADR.
3. **One process-wide library scope.** Rejected: nothing could ever cancel it, and it would make every static collection a child of one long-lived job.

## Files an implementation touches

- `exports/FlowExports.kt` (`addFlowMethodExports` / `addFlowPropertyExports` over a static receiver; held acquire without `handle`)
- `exports/SharedFlowExports.kt` (static property shared seams use the flow-keyed nullable-scope variant)
- `NugetProcessor.kt` (emitter loops over objects, companions, top-level functions and properties; coroutine-import gate)
- `cir/CirFlowRenderer.kt` (`renderFlowMethod`, `renderStateFlowMethod`, held renderers: an `isStatic` arm), `cir/CirSharedFlowRenderer.kt`, `cir/CirObjectRenderer.kt`, `cir/CirClassTranslator.kt` (companion), `cir/CirTranslator.kt` (top-level facade), `cir/CirFunctionTranslator.kt`
- `forward/ForwardCallablePlanner.kt` (`isRouted` for static walks), `forward/ForwardPropertyPlanner.kt:1431-1436` (static positions legacy-routed), `forward/ForwardScopeOwnership.kt` (selectors), `forward/ForwardMemberTypeParameters.kt` (generic refusal lifted for static Flow)
- `ForwardAbiContract.kt` / `ForwardAbiLegacyRoutes.kt`
- Fixture: `test-library/.../test/clock/` (`object Clock`, `Den` companion, top-level `ticks()`)
- Tests: `IntegrationTests/StaticFlowTests.cs`, `LeakTests/LiveHandleTests.cs`, Tier 1 named-skip cells flip (`Tier1...` cells asserting `plainTop`/`plainObj`/`plainComp` skips, per ADR-215 lane B's probe)
- Docs: `docs/topics/supported-features.md:99` ("Not on an `object`, companion or top level" goes), `docs/topics/coroutines-and-flow.md`, ADR-026 amendment, ADR-215 text at `:86-95` and `:205-206`

## Sample test

Fixture:

```kotlin
fun ticks(count: Int): Flow<Int> = flow { repeat(count) { emit(it) } }

object Clock {
  val beats: Flow<Int> = flow { emit(1); emit(2) }
  val hour: MutableStateFlow<Int> = MutableStateFlow(9)
  fun <T> echo(value: T, times: Int): Flow<T> = flow { repeat(times) { emit(value) } }
  fun forever(): Flow<Int> = flow { var n = 0; while (true) { emit(n++); delay(1) } }
  fun broken(): Flow<Int> = flow { emit(1); throw IllegalStateException("clock stopped") }
}

class Den { companion object { fun doors(): Flow<String> = flowOf("front", "back") } }
```

xunit:

```csharp
[Fact]
public async Task TopLevel_Ticks_EnumeratesStatically()
    => Assert.Equal(new[] { 0, 1, 2 }, await Collect(TicksKt.Ticks(3)));   // facade name per ADR-007

[Fact]
public async Task Clock_Echo_GenericStaticFlow()
    => Assert.Equal(new[] { "Mylo", "Mylo" }, await Collect(Clock.Echo("Mylo", 2)));

[Fact]
public async Task Clock_Beats_StaticFlowProperty()
    => Assert.Equal(new[] { 1, 2 }, await Collect(Clock.Beats));

[Fact]
public void Clock_Hour_StaticMutableStateFlow_IsSettable()
{
    Clock.Hour.Value = 10;
    Assert.Equal(10, Clock.Hour.Value);
}

[Fact]
public async Task Den_Doors_CompanionFlow()
    => Assert.Equal(new[] { "front", "back" }, await Collect(Den.Doors()));

[Fact]
public async Task Clock_Forever_CancelsThroughTheToken()
{
    using var cts = new CancellationTokenSource();
    await Assert.ThrowsAnyAsync<OperationCanceledException>(async () =>
    {
        await foreach (int n in Clock.Forever().WithCancellation(cts.Token))
            if (n == 3) cts.Cancel();
    });
}

[Fact]
public async Task Clock_Broken_FaultsTheStream()
{
    var seen = new List<int>();
    var ex = await Assert.ThrowsAsync<KotlinException>(async () =>
    {
        await foreach (int n in Clock.Broken()) seen.Add(n);
    });
    Assert.Equal(new[] { 1 }, seen);
}

[Fact]
public async Task Clock_Ticks_TwoEnumerationsReinvokeTheFunction()
{
    KotlinFlow<int> flow = TicksKt.Ticks(2);
    Assert.Equal(await Collect(flow), await Collect(flow));
}
```

LeakTests (`LiveHandleTests.cs`, `// ADR-230` banner):

- `StaticFlow_Complete_ReturnsToBaseline`: 200 x full enumeration of `Ticks(3)`, `Clock.Echo(tabby, 2)` (dispose items), `Clock.Beats`, `Den.Doors()`.
- `StaticFlow_EarlyBreak_ReturnsToBaseline`: 200 x `break` after the first item of `Clock.Forever()` (DisposeAsync path).
- `StaticFlow_Cancel_ReturnsToBaseline`: 200 x a token canceled mid-stream; each enumeration awaited to its end before the count.
- `StaticFlow_Fault_ReturnsToBaseline`: 200 x `Clock.Broken()` caught.
- `StaticFlow_TightLoop_SynchronousCompletion_ReturnsToBaseline`: 1000 x `Ticks(0)` (empty flow: `onComplete` can fire before `startCollect` returns, the ADR-161 ordering window).
- `StaticMutableStateFlow_HeldHandle_ReturnsToBaseline`: 200 x read `Clock.Hour` holder, set `Value`, dispose the holder.

## Deferred scope

None. Static flow properties (finding 7) are in scope.

## Open what-questions

1. Alternative 1 (no owned scope, `KotlinFlow.Dispose()` a no-op for a re-invoked static flow) or alternative 2 (owned scope, `Dispose()` cancels live enumerations)? Recommendation: 1, for parity with the shipped awaited static flow.
2. Held `MutableStateFlow` `val` on an object: acquire once per C# property read (a fresh holder each read, disposed by the consumer) or cache one holder in a static field? Recommendation: per read, as the instance held property route does (inferred; check `renderHeldStateFlowMethod`'s property twin).
3. The top-level property walk's facade class name for a static flow `val` (ADR-007): inferred to be the same file-facade class as top-level functions.

### Verified vs inferred

- Verified by reading: findings 1 (object, companion), 2 to 7.
- Inferred: the top-level walk's skip call (finding 1), facade naming in the sample, `KotlinException` type name, questions 2 and 3.
