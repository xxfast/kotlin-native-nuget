# `Flow<T>` / `StateFlow<T>` / suspend lambda as a function parameter, including on sealed arms

- ROADMAP: line 58 as of 2026-10-12, Phase 7: "`Flow<T>` / `StateFlow<T>` / suspend lambda (`suspend (T) -> R`) as a function parameter, including on sealed arms"
- Researched: 2026-10-12, about 15 of 20 minutes, then amended the same day (about 8 of 10 minutes) with an independent review's nine points, each re-read in source before it was written (findings 17 to 22), then with the human's gate decisions and two spike results run by a kotlin-dev (finding 23). This agent ran no spike itself; the two recorded ones are a kept Tier 1 test and a deleted scratch `nativeTest`. Claims are labelled below.
- Restatement: a Kotlin library declares a function, method or constructor whose parameter is a `Flow<T>`, a `StateFlow<T>` or a `suspend (T) -> R`; the generated C# binding lets the C# caller supply that argument idiomatically and Kotlin collects, reads or invokes it. Forward binding (Kotlin declares, C# consumes), argument data flowing C# to Kotlin. Research reshapes one third of it: `StateFlow<T>` has no honest C# source, so the recommendation there is a dedicated named refusal, not a binding.
- Verdict: fix (suspend lambda, `Flow<T>`), pin and close (`StateFlow<T>`, as a named refusal). ADR-210 drafted, Proposed, `docs/adr/210-flow-and-suspend-lambda-parameters.md`; all seven what-questions decided 2026-10-12. Additive: no change to the Gradle DSL, task names, public generated C# types, the `nuget_*` runtime ABI, diagnostic codes or the `NugetDiagnostics.json` schema. Nothing in `nuget-runtime` changes at all.

## Findings

### The mechanism already exists on the Kotlin side

1. **verified (by reading)** `awaitForKotlin` is the piece that suspends Kotlin until a C# `Task` completes: `nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetAwait.kt:73-103`. It is `public`, `@NugetRuntimeApi`, takes three lambdas (`release`, `cancel`, `begin`) and names nothing from the reverse plugin, so forward generated code can call it as it stands. The continuation is resumed by one shared `staticCFunction` (`NugetAwait.kt:32-49`) that the C# side calls with `(taskHandle, ctx)`.
   - Exactly once: the `ctx` is released the moment the callback reads it (`NugetAwait.kt:42`), before the resume.
   - Cancel then complete: the resume's `onCancellation` arm frees the task handle nobody will `End` (`NugetAwait.kt:46`).
   - Synchronous throw from `begin`: `ctx` released, exception rethrown (`NugetAwait.kt:84-91`).
   - Cancellation source released exactly once through an `AtomicReference` swap, by either `invokeOnCancellation` (`cancelled = true`) or the `finally` (`cancelled = false`) (`NugetAwait.kt:94-101`).
   - Repo tests that prove each path: `nuget-runtime/src/nativeTest/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/AwaitForKotlinTest.kt:59` (completes before `begin` returns), `:77` (completes from another thread), `:103` (cancel then complete releases the task handle exactly once), `:128` (throwing `begin`), `:172`, `:200`, `:253` (source cancelled or released exactly once on each ordering).
2. **verified (by reading)** `flowForKotlin` is the `Flow` implementation that wraps a C# source: `nuget-runtime/.../runtime/NugetFlow.kt:47-84`. It is a `flow { }` builder, so it is cold and re-collectable: `enumerate` runs once per `collect` (`NugetFlow.kt:59`). It is pull, one `awaitForKotlin` per element (`NugetFlow.kt:66-70`), so backpressure is inherent. The enumeration handle is minted by `enumerate` and released by `dispose` from the `finally` exactly once, on completion, collector throw, cancellation and early abort (`NugetFlow.kt:78-83`); `dispose` receives `cancelled = true` only on a `CancellationException`. Tests: `FlowForKotlinTest.kt:54` (full collect, dispose once), `:80` (two collects run two enumerations), `:113` (early abort), `:139` (cancelled mid step), `:166` (throwing step), `:189` (throwing `enumerate` disposes nothing).
3. **verified (by reading)** The forward bridge already has the channel a C# throw needs to reach Kotlin from a function-pointer call: `nugetCallbackCall` (`nuget-runtime/.../runtime/NugetRuntime.kt:766`) plus `nuget_managed_error_create` (`NugetRuntime.kt:749`), decided in `docs/adr/161-csharp-callback-exception-into-kotlin.md:54-88`. `kind = 1` (a C# `OperationCanceledException`) becomes a Kotlin `CancellationException` (`161-...md:80-85`).
4. **verified (by reading)** Forward generated Kotlin already opts in to `NugetRuntimeApi` and imports from the runtime package (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/NugetProcessor.kt:2293`, `:3522`, `:3648`).
5. **verified (by reading)** So the Phase 13 gaps do not apply here. Phase 13 lacks a Kotlin-to-C# completion (a `TaskCompletionSource` driven from Kotlin) and a repeated callback, both for the reverse bridge (`ROADMAP.md:147`, `:149`). This item needs the opposite completion (C# `Task` resumes a Kotlin continuation), which is `awaitForKotlin`, and no repeated callback at all, because the pull design steps through exactly-once awaits (`docs/adr/156-iasyncenumerable-to-flow.md:49-63`).

### The C# half exists only in the reverse shim

6. **verified (by reading)** `NugetTasks.Attach` (the one `ContinueWith` that calls back into Kotlin, `TaskScheduler.Default`, frees the task handle if attaching throws) is emitted only by the reverse plugin: `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetGenerateShimsTask.kt:2937-2965`. So are `MoveNextBegin_Thunk` (`:2815-2830`), `MoveNextEnd_Thunk` (`:2834-2852`), `DisposeEnumeration_Thunk` with its load-bearing order cancel, await pending, then `DisposeAsync` (`:2866-2893`) and `NugetAsyncEnumeration` / `NugetAsyncEnumeration<T>` (`:2897-2922`). No forward emitter mentions `NugetTasks` or `NugetAsyncEnumeration` (grep of `nuget-processor/src/main` returns nothing). The forward `Interop.cs` needs its own emission of both.
7. **verified (by reading)** The reverse shim reaches those thunks through `nuget_runtime_register` slots (`NugetGenerateShimsTask.kt:2682-2691`). The forward bridge has no registration step; its convention is that thunk pointers ride per call as `IntPtr` arguments beside a context (`docs/adr/160-callback-parameter-on-the-forward-plan.md:40-45`, `:138-142`; `docs/adr/102-aot-safe-forward-callbacks.md:127`).
8. **verified (by reading)** `[UnmanagedCallersOnly]` cannot be generic (CS8895, noted at `NugetGenerateShimsTask.kt:953`), so the forward thunks must be per wire shape, registered first-wins in `NugetThunks`, as ADR-102 and ADR-160 already do for plain lambdas.
   - **verified (by reading), closed**: a forward `Interop.cs` type named `NugetTasks` does not collide with the reverse shim's. The reverse one lives in `<root>.NugetReverse` (`reverseNamespace`, `NugetGenerateShimsTask.kt:80-83`, used at `:2651`); the forward fixed helpers render in the root namespace (`cir/CirRenderer.kt:148-152`).

### Current behaviour of the three shapes

9. All three are named skips today, never bound wrongly, with one unverified exception.
   - **verified (by reading)** Classification: `Flow`, `StateFlow` and a suspend function type all become `BridgeType.SpecializedProtocol` (`forward/ForwardBridgeTypeClassifier.kt:1133-1145`), which the planner maps to `FLOW_PROTOCOL` or `SUSPEND_CALLBACK_PROTOCOL` (`forward/ForwardCallablePlanner.kt:5774-5780`). Both are silent legacy deferrals (`ForwardCallablePlanner.kt:60`, `:63`) that `nameUnroutedPosition` relabels `UNROUTED_POSITION` when no route re-emits the member (`ForwardCallablePlanner.kt:426-431`, `:464-470`). At an input position the diagnostic kind is `SKIPPED_UNSUPPORTED_INPUT` (`forward/ForwardDiagnostic.kt:623-627`).
   - **verified (by reading, pinned by a test)** `Flow<T>` parameter on a class method, object member, interface default, extension, top-level function and secondary constructor: `SKIPPED_UNSUPPORTED_INPUT`, `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1UnroutedPositionsTest.kt:74`, `:98`, `:109`, `:117`, `:127`, `:136` (the three constructors). Sentence: "a Flow/StateFlow binds at a class-method return and a property, but not at this position" (`ForwardDiagnostic.kt:883-884`); hint says "a Flow cannot be passed in" (`ForwardDiagnostic.kt:1385-1388`).
   - **verified (by reading, pinned by a test)** suspend lambda parameter on a class method: `SKIPPED_UNSUPPORTED_INPUT`, `Tier1UnroutedPositionsTest.kt:85`; sentence at `ForwardDiagnostic.kt:898-900`, pinned by `Tier1LambdaReturnParameterTest.kt:261`; hint at `ForwardDiagnostic.kt:1394-1396`.
   - **verified (by reading, and by the run recorded below)** suspend lambda parameter on a sealed arm: named `SEALED_SUBCLASS_UNROUTED`, diagnostic kind `SKIPPED_UNSUPPORTED_COMBINATION`, because `SUSPEND_CALLBACK_PROTOCOL` is deliberately not exempted (`ForwardCallablePlanner.kt:2046-2073`); hint borrowed from the position hint (`ForwardDiagnostic.kt:1414-1423`).
   - **verified (by reading)** A nullable `Flow<T>?` or `(suspend (T) -> R)?` parameter: `NULLABLE` (`ForwardCallablePlanner.kt:4634-4638`).
   - **verified (by reading)** On a `suspend fun` or a `Flow`-returning member (the legacy async routes), every generic parameter that is not a supported collection or an exported generic class is `ForwardLegacyParameterShape.Refused` (`forward/ForwardLegacyRouteCollections.kt:235-246`), and the route drops the member (`exports/SuspendFunctionExports.kt:72`, `exports/FlowExports.kt:116`, `cir/CirFunctionTranslator.kt:52`). `suspend fun input(flow: Flow<Int>)` is a fixture for this (`Tier1SuspendFlowTest.kt:42`, `:102`). This is the position a suspend lambda realistically lives at, so the refusal at `:242` is the one the first slice replaces. Side finding: a plain `(Int) -> Unit` parameter on a `suspend fun` is refused by the same line.
   - **verified (by a run, 2026-10-12)**: a `Flow<T>` parameter on a sealed arm method whose return is not a `Flow` is SILENTLY DROPPED today, absent from `Interop.cs` and `CNameExports.kt` with no diagnostic. Pinned by `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1AsyncParameterSealedArmTest.kt` (4 tests green, run by a kotlin-dev, reported by the coordinator; this agent read the file, `:42-44`, `:83`, `:105`, `:125`, and did not re-run it). The same test pins two neighbours: a `suspend (Int) -> String` parameter there is named once by `SKIPPED_UNSUPPORTED_COMBINATION` (`:105-113`), and a `Flow<Int>` parameter on a `suspend fun` of the arm is named once by `SKIPPED_UNSUPPORTED_INPUT` under the package-less symbol `Shape.Circle.watchFlowSuspended` (`:125-138`), unlike the qualified symbol the other skip carries. Slice (b) fixes the drop by binding the position. The reading that predicted it: The arm relabel exempts `FLOW_PROTOCOL` by reason, not by position (`ForwardCallablePlanner.kt:2054-2056`), and the arm entries are not passed through `nameUnroutedPositions` at all: `sealedSubclassEntries` (`ForwardCallablePlanner.kt:1940`) returns its relabelled list directly and its one caller only adds `.ownedBy(...)` (`ForwardCallablePlanner.kt:1005`), where class entries get the route's own predicate (`:1548`, `:1690`) and value-class entries the default "not routed" one (`:551-553`, `:1462`). The sealed base takes the same by-reason exemption (`ForwardCallablePlanner.kt:1895-1900`). The one other walk that could name it does not reach it: `warnRefusedLegacyRouteMembers` (`NugetProcessor.kt:808`) visits an arm's members only after `.filter { method -> method.isForwardLegacyAsyncRoute() }` (`NugetProcessor.kt:1446-1456`, read; the predicate's own body was not read, its name and the comment at `:1451-1453` say suspend and Flow-returning members). No Tier 1 cell covered it when this was first written; one does now.
   - **not read**: where ADR-204's sealed-interface-over-declared-arms members are planned. `ForwardCallablePlanner.kt` does not mention ADR-204; the references are in `cir/CirSealedRenderer.kt`, `cir/CirClassTranslator.kt`, `cir/CirFunctionTranslator.kt`, `cir/CirClassRenderer.kt`. Whether a plan-route `BridgeType` reaches those members by position, as it does for ADR-116 arms, is unverified.

### StateFlow

10. **verified (by reading)** There is no honest C# source for a `StateFlow<T>`. The BCL has no type with a synchronous current value plus a change stream. The generated `KotlinStateFlow<T>` cannot be handed back by handle in general: the property and method variants hold no flow handle, only closures over the owner (`cir/CirFlowRenderer.kt:305-321`, the comment at `:307-311`; `ownedHandle` is set only for the awaited-suspend variant of ADR-068).
11. **verified (by reading)** A `KotlinFlow<T>` or `KotlinStateFlow<T>` does bind to a `Flow<T>` parameter for free, since `KotlinFlow<T> : IAsyncEnumerable<T>` (`cir/CirFlowRenderer.kt:84`). It crosses twice and loses Kotlin identity.

### Lifetime

12. **verified (by reading)** The per-call lambda contract frees the C# context in the `finally` when the P/Invoke returns (`docs/adr/160-...md:60-78`) and therefore never binds at a storing position (`160-...md:122-129`). A suspend lambda or a `Flow` is used after the P/Invoke returns by construction: a `suspend fun` body runs inside `launchForCSharp`, which returns the job handle immediately (`nuget-runtime/.../runtime/NugetLaunch.kt:35-57`).
13. **verified (by reading)** The forward bridge already has a Kotlin-owned lifetime for a C# object: the ADR-084 bridge object owns a `createCleaner` over the release function pointer and its context (`exports/InterfaceBridgeFactoryExports.kt:25-29`, `:61-63`). `LeakTests` already settles cleaner rounds (`LeakTests/LiveHandleTests.cs:88-117`, `NugetMarshal.GcCollect()` at `:117`).
14. **verified (by reading)** ADR-161 part C replaced the raw `GCHandle` context with a never-reused key, so a late call after release is a miss (`ObjectDisposedException` for a value-returning delegate), never a use-after-free (`docs/adr/161-...md:155-175`).

### Prior art

15. SKIE, the closest analogue, refuses this direction: "Conversions in the other direction (from `AsyncSequence` to `Flow`) are not supported" (https://skie.touchlab.co/features/flows, fetched 2026-10-12). **inferred (from the docs page, not run)**. No precedent to follow outside the repo; ADR-156 is the precedent. Swift Export, ObjC Export, JS and Wasm were not consulted: the first precedent settled that nothing external changes the decision.
16. **verified by spike in ADR-156, not re-run**: `DisposeAsync` during a pending `MoveNextAsync` throws `NotSupportedException`; the enumerator token cancels a pending step only with `[EnumeratorCancellation]`; a compiler-generated iterator re-enumerates from the start (`docs/adr/156-...md:303-333`).

### Independent review, 2026-10-12

17. **verified (by reading)** The existing thunk shell is unsafe for the new thunks. `appendCtxDispatchThunk` takes the last parameter as the ctx (`cir/CirCallbackRenderer.kt:110`), and on a key miss `ctxLookupPreamble` calls `NugetMarshal.Dispose` on every `IntPtr` slot ahead of it (`cir/CirCallbackRenderer.kt:157-175`), which is sound only because each such slot on the four existing routes is a Kotlin `StableRef` (its own comment, `:149-155`). A Begin thunk carries the completion function pointer and `awaitForKotlin`'s pending `StableRef` as `IntPtr` slots, so a late call through that shell would `nuget_dispose` a function pointer and free the pending ctx, which `awaitForKotlin` releases again at `NugetAwait.kt:89`. Each step read; the crash itself **not run**. ADR-210 now decides: own body, key last before `errOut`, and a slot-by-slot key-miss rule (a handle-passed payload is disposed; `callback` and `awaitCtx` are never touched, the ctx stays owned by `awaitForKotlin`; no source, task or task handle exists; `ObjectDisposedException` through `errOut`).
18. **verified (by reading)** Vocabulary direction. `isCallbackResult` admits `Unit`, a primitive, `String`, and `Throwable` where the declaration accepts a `NugetManagedException` (`forward/ForwardBridgeTypeClassifier.kt:1107-1132`); `isCallbackPayload` admits a primitive, `String`, an enum, an object handle, an interface, `Throwable` (`:1078-1085`); the primitives are the eleven `PrimitiveKind`s (`forward/ForwardMarshallingModel.kt:425-437`). Chosen: the v1 set is exactly those, with conversion in End and Current. **inferred (not run)**: the alternative, an adapting closure that converts before `Attach`, would leak a Kotlin-owned `String` box on cancel-then-complete, because `release` frees only the task handle (`NugetAwait.kt:46`).
19. **verified (by reading)** Lifetime during a flight. The pending record holds the continuation (`NugetAwait.kt:23-26`, `:81`) and its ctx is counted and released only by the completion callback (`NugetAwait.kt:42`) or a throwing `begin` (`:89`). So a C# lambda that ignores its token after Kotlin cancels holds one counted handle until its `Task` really completes. **verified (by spike, 2026-10-12)**: the continuation keeps the cleaner's holder reachable, so the cleaner cannot fire mid flight (finding 23; debug test binary only).
20. **verified (by reading)** ADR-084's flat factory is the slot precedent: 2N+3 parameters, 17 for the 7-member `IPet` (`docs/adr/084-csharp-implemented-interfaces.md:109`). No forward export passes a table.
21. Thread semantics, corrected. Only the synchronous prefix of the C# lambda, up to its first `await` that does not complete synchronously, runs on the Kotlin dispatcher thread (inside Begin; for a `Flow`, inside Enumerate and each MoveNextBegin). **verified (by reading)**: the completion callback into Kotlin arrives on `TaskScheduler.Default` (`NugetGenerateShimsTask.kt:2946-2955`). **inferred (standard .NET behaviour, not run)**: the lambda's own continuations resume on the thread pool, because that thread carries no `SynchronizationContext`. Note the cited lines prove the first statement, not the second.
22. **verified (by reading)** Two notes for the implementer. The `createCleaner` import is emitted only when the module has bridge factories (`NugetProcessor.kt:3620-3623`) and must be widened; each export that builds a holder needs `@OptIn(ExperimentalNativeApi::class)`, as the factory export carries (`exports/InterfaceBridgeFactoryExports.kt:83`). **inferred (not run)** residual: the `_lastManagedFault` stash is written on a Kotlin worker thread, so the C# caller of the outer `Task` may get the mapped exception rather than the original object; the rebuild path covers `OperationCanceledException` only (`docs/adr/161-csharp-callback-exception-into-kotlin.md:336-338` records the same inference).

### Gate decisions and spikes, 2026-10-12

23. **verified (by spike, run by a kotlin-dev; the scratch test was deleted, so this is recorded from the coordinator's report and its source cannot be re-read)** The ADR's Kotlin half composed with fake thunks passed 5 of 5 on `:nuget-runtime:mingwX64Test`: it completes from a Worker thread; it completes inline before `begin` returns; an error written through `err` throws `NugetManagedException` at the invocation site with `NugetHandles.live` back at baseline; cancel while pending calls `cancel` once with `cancelled = true` and the late completion releases the task handle once with End never called; over a 633 ms pending window with three `GC.collect()` calls an unreferenced control holder was cleaned and the pending one was not, and one round after completion released it exactly once (debug test binary only). One correction it forced: `begin` must return `COpaquePointer?` (`NugetAwait.kt:76`), so the Begin thunk returns the cancellation source handle; the ADR sketch now says so. Not covered by the spike: real C# thunks, the `Flow` composition over `flowForKotlin`, an optimised binary.
24. The human decided all seven what-questions as recommended (recorded under Open what-questions). Consequence for this memo: the ROADMAP line is not split; it ships as two stacked PRs, (a) then (b).

## Recommendation

One foundation, two bindings, one refusal, decided in one ADR (210).

- **`suspend (T) -> R` parameter** binds as `Func<T, CancellationToken, Task<R>>` (`Func<T, CancellationToken, Task>` for `Unit`). Kotlin wraps the pointers in a real `suspend` lambda whose body is `awaitForKotlin(begin = Begin thunk through nugetCallbackCall)` followed by the End thunk through `nugetCallbackCall`.
- **`Flow<T>` parameter** binds as `IAsyncEnumerable<T>`. Kotlin wraps it with `flowForKotlin`. Cold and re-collectable exactly as far as the C# source is.
- **`StateFlow<T>` parameter** stays refused, with its own sentence and hint (declare `Flow<T>` plus an initial value and call `stateIn` in Kotlin).
- **Wire**: flat `IntPtr` slots on the per-declaration export (finding 7), no table. 3 per parameter (suspend lambda: Begin, End, key; `Flow`: Enumerate, Current, key); the parameter-independent thunks once per export (release task and release context always, release cancellation with a suspend lambda, MoveNextBegin, MoveNextEnd and Dispose enumeration with a `Flow`). One suspend lambda is 6 slots, one `Flow` 8, two suspend lambdas 9, one of each 12. No new `nuget_*` export, no `nuget-runtime` change.
- **Thunk shell**: the new thunks get their own body, never `ctxLookupPreamble` / `appendCtxDispatchThunk`, with the key last before `errOut` (finding 17).
- **v1 types**: results and `Flow` elements are exactly ADR-160's `isCallbackResult` (`Unit` for a lambda result, the eleven primitives, `String`); payloads exactly `isCallbackPayload`. Conversion happens in End and Current, never in an adapting closure before `Attach` (finding 18).
- **C# fixed helpers**: a forward twin of `NugetTasks.Attach` and of `NugetAsyncEnumeration`, internal, emitted into `Interop.cs` only when a bound member needs them.
- **Lifetime**: Kotlin-owned through `createCleaner`, the ADR-084 rule (finding 13), at every position. This is what makes a constructor and a non-suspend method bindable, which the ADR-160 call-scoped rule cannot.
- **Route**: a new `BridgeType` leaf per shape on the ADR-062 plan, as ADR-160 did for plain lambdas, so class method, sealed arm, object, top-level, extension and constructor come by position; plus one new `ForwardLegacyParameterShape` arm so the legacy suspend and Flow-return routes accept the same parameter (finding 9, the realistic host). Said plainly: the feature lands on two dialects, the plan leaf and the legacy arm, because the codebase forces it. A `suspend fun` is not on the plan, and the plan alone would bind every position except the one that matters most.

Is this one item or three: two mechanisms that share a foundation, and a refusal. Decided 2026-10-12: one ROADMAP line, two stacked PRs, (a) suspend lambda parameter then (b) `Flow<T>` parameter, with `StateFlow<T>` folded into (b) as the refusal sentence. (a) is the cheapest first slice and installs everything (b) reuses.

Price, in files touched (processor main only):
- Slice (a), about 17 (the first estimate of 13 missed the legacy route's C# half): `forward/ForwardMarshallingModel.kt`, `forward/ForwardBridgeTypeClassifier.kt`, `forward/ForwardCallablePlanner.kt`, `forward/ForwardLegacyRouteCollections.kt`, `forward/ForwardDiagnostic.kt`, `forward/ForwardCirPlanProjection.kt`, `ForwardAbiContract.kt` (the Tier 1 arity agreement check, in the `processor` package root), `cir/CirCallbackRenderer.kt`, `cir/CirRuntimeRenderer.kt` (or a new `CirAsyncCallbackRenderer.kt`), `cir/CirCollectionParameters.kt`, `cir/CirTranslator.kt`, `exports/SuspendFunctionExports.kt`, `NugetProcessor.kt`, and the four the legacy route adds: `cir/CirConcurrencyRenderer.kt` (`renderAsyncMethod`, `:284-541`, builds the C# call site), `cir/CirFunctionTranslator.kt` and `cir/CirClassTranslator.kt` (produce the `CirMethod`; they call `legacyRouteParameters` at `CirFunctionTranslator.kt:79` and `CirClassTranslator.kt:2279`, `:2577`, `:2722`), `cir/CirModel.kt` (`CirParameter`, `:1336`, has no export-level slots). `cir/CirCollectionParameters.kt` was already counted; the anchor is `nativeImportParameters()` at `:54-65`, which fans one parameter out to at most three slots today.
- Slice (b), about 3 new files (a new `cir/CirFlowSourceRenderer.kt`, `exports/FlowExports.kt`, `exports/ClassExports.kt`) plus a second pass over about 10 of slice (a)'s (model, classifier, planner, legacy collections, diagnostic, projection, callback renderer, concurrency renderer, collection parameters, the two translators).

Alternatives rejected:
- Push (C# `await foreach` pump into a Kotlin channel): needs a repeated callback and a backpressure handshake; ADR-156 already rejected it (`156-...md:65-70`).
- Register the thunks once through a new `nuget_*` export: touches the 1.0-frozen runtime ABI for no gain over per-call pointers.
- Call-scoped lifetime (free when the P/Invoke or the outer `Task` ends): deterministic, but a stored lambda or `Flow` then fails at its first late use, and constructors and non-suspend members cannot bind at all.
- `Func<T, Task<R>>` without a token: Kotlin cancellation could not reach the C# body.
- `StateFlow<T>` from `IAsyncEnumerable<T>` plus an initial value through `stateIn`: needs a scope that outlives the call and makes `.value` lag the C# source, so it is not a `StateFlow` in any honest sense.
- Legacy-route-only patch (a new arm in `legacyParameterShape` and nothing on the plan): about 5 files fewer, but leaves sealed arms, objects, top-level functions and constructors refused and adds a hand-written route the codebase is moving away from.

## Files an implementation touches

Processor: the files listed under Price, at these anchors (all read on 2026-10-12; the two line numbers ADR-160 itself quotes, classifier `:684-710` and planner `:2621`, are stale): `ForwardBridgeTypeClassifier.kt:1015` (where `BridgeType.Callback` is built) and `:1133-1145`; `ForwardCallablePlanner.kt:2915-2917` and `:3445-3456` (the `${name}Ptr` / `${name}UserData` slot fan-out), `:4634-4638`, `:4746`, `:4925`, `:5774-5780`, `:2046-2073`; `ForwardLegacyRouteCollections.kt:134-138`, `:191-247`; `ForwardDiagnostic.kt:883-900`, `:1385-1396`, `:1414-1423`; `ForwardMarshallingModel.kt:238-241`.

Runtime: none.

Fixtures: `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/unrouted/UnroutedPositionsSample.kt:21`, `:41`, `:57`, `:70`, `:98`, `:122`, `:150` (cells that stop being skips); a new fixture file for the bound shapes.

Processor tests whose assertions become false: `Tier1UnroutedPositionsTest.kt:74`, `:85`, `:98`, `:109`, `:117`, `:127`, `:136`; `Tier1LambdaReturnParameterTest.kt:261`; `Tier1SuspendFlowTest.kt:42`, `:102`. New: one Tier 1 test per slice, including the sealed-arm cell of finding 9.

C# tests: `IntegrationTests/SuspendLambdaParameterTests.cs`, `IntegrationTests/FlowParameterTests.cs`, `LeakTests/LiveHandleTests.cs`, `AotIntegrationTests/` (the new thunks are `[UnmanagedCallersOnly]`, ADR-102's lane).

Docs (documenter only): `docs/topics/supported-features.md:119` neighbourhood and the Flow rows at `:158-175`, `docs/topics/coroutines-and-flow.md`, `docs/topics/lambdas-and-callbacks.md`, `docs/topics/forward-overview.md`, `docs/adr/README.md`.

## Sample test

Kotlin fixture (proposed):

```kotlin
class TreatDispenser {
  suspend fun dispense(count: Int, prepare: suspend (Int) -> String): List<String> =
    (1..count).map { prepare(it) }

  suspend fun totalWeight(weights: Flow<Int>): Int = weights.fold(0) { sum, w -> sum + w }

  suspend fun firstTwo(weights: Flow<Int>): List<Int> = weights.take(2).toList()
}
```

Generated C# surface (proposed):

```csharp
public Task<IReadOnlyList<string>> DispenseAsync(int count, Func<int, CancellationToken, Task<string>> prepare, CancellationToken cancellationToken = default);
public Task<int> TotalWeightAsync(IAsyncEnumerable<int> weights, CancellationToken cancellationToken = default);
public Task<IReadOnlyList<int>> FirstTwoAsync(IAsyncEnumerable<int> weights, CancellationToken cancellationToken = default);
```

```csharp
[Fact]
public async Task SuspendLambdaParameter_AwaitsEachCSharpTask()
{
    using var dispenser = new TreatDispenser();
    IReadOnlyList<string> treats = await dispenser.DispenseAsync(2, async (n, token) =>
    {
        await Task.Delay(10, token);
        return $"treat {n} for Oreo";
    });
    Assert.Equal(new[] { "treat 1 for Oreo", "treat 2 for Oreo" }, treats);
}

[Fact]
public async Task FlowParameter_CollectsACSharpAsyncEnumerable()
{
    using var dispenser = new TreatDispenser();
    static async IAsyncEnumerable<int> Weights()
    {
        yield return 3;
        await Task.Yield();
        yield return 4;
    }
    Assert.Equal(7, await dispenser.TotalWeightAsync(Weights()));
}

[Fact]
public async Task FlowParameter_EarlyAbort_RunsTheIteratorFinally()
{
    using var dispenser = new TreatDispenser();
    var finallyRan = new TaskCompletionSource();
    async IAsyncEnumerable<int> Endless()
    {
        try { for (int i = 0; ; i++) { await Task.Yield(); yield return i; } }
        finally { finallyRan.SetResult(); }
    }
    Assert.Equal(new[] { 0, 1 }, await dispenser.FirstTwoAsync(Endless()));
    await finallyRan.Task.WaitAsync(TimeSpan.FromSeconds(5));
}
```

`StateFlow<T>` (Tier 1 cell, not xunit): `fun watch(mood: StateFlow<Int>): Int` reports `SKIPPED_UNSUPPORTED_INPUT` naming `tier1....watch` with the new StateFlow sentence, and `Interop.cs` declares no `Watch`.

`LeakTests/LiveHandleTests.cs` rows (through `AssertNoLeakAsync`, `:161`, with `Settle` because the cleaner owns the release):
- suspend lambda, happy path, a lambda that really suspends (`Task.Delay`).
- suspend lambda, tight loop: `Task.FromResult`, so the completion can land before Begin returns; the mirror of Row 9a (`LiveHandleTests.cs:3994-4001`).
- suspend lambda, throw path: the lambda throws synchronously, and separately returns a faulted `Task`.
- suspend lambda, cancel path: the outer token is cancelled while the C# `Task` is pending (the cancel-then-complete window of finding 1). The row must await the C# task's real completion before it asserts the baseline: a lambda that ignores its token holds one counted ctx handle until then (finding 19).
- `Flow`, full collect; `Flow`, early abort (`FirstTwoAsync`); `Flow`, the iterator throws mid stream; `Flow`, outer token cancelled mid step.
- `Flow`, tight loop: an iterator with no real suspension, so each `MoveNextAsync` is already complete.

## Deferred scope

- `StateFlow<T>` passthrough of a Kotlin-produced `KotlinStateFlow<T>` by handle: needs every wrapper variant to carry a flow handle (finding 10). 1.x.
- `SharedFlow<T>` / `MutableStateFlow<T>` parameters: same reasoning as `StateFlow<T>`; refused by the same sentence.
- Nullable `Flow<T>?` and `(suspend (T) -> R)?` parameters: keep `NULLABLE` in v1.
- Suspend lambda arity above 3, and payload or result shapes outside what `BridgeType.Callback` admits today (`ForwardMarshallingModel.kt:229-231`).
- `Flow` element and suspend lambda result types outside `isCallbackResult` (`forward/ForwardBridgeTypeClassifier.kt:1107-1132`): `Char`, an enum, object, interface or value class, a collection, a nullable. The legacy Flow element vocabulary (`ForwardLegacyRouteCollections.kt:911-1009`) is Kotlin to C# and does not apply here; the first draft of this memo pointed at it by mistake. Widening needs either more per-type End and Current thunks or an adapting closure with a release thunk that knows the payload (finding 18).
- Batch pull for high-rate streams (ADR-156 alternative 3).
- A plain `(T) -> R` parameter on a `suspend fun` (finding 9 side finding): the same cleaner lifetime would carry it, but it is a different ROADMAP line.
- The stash residual: the new thunks run user C# on Kotlin dispatcher threads, which widens the open item at `ROADMAP.md:61` (`_lastManagedFault` never cleared on dispatcher threads). Not made worse in kind, only in reach.

## Open what-questions
With the main thread's recommendation, the independent review's answer (2026-10-12), and the human's decision once given.

1. **Split the ROADMAP line?** Recommendation: yes, into (a) suspend lambda parameter and (b) `Flow<T>` parameter with the `StateFlow<T>` refusal folded into (b); implement (a) first.
   - Independent review: keep one ROADMAP line; ship it as two stacked PRs, (a) then (b).
   - Decision: keep one ROADMAP line; ship as two stacked PRs, (a) suspend lambda parameter then (b) `Flow<T>` parameter with the `StateFlow<T>` refusal (human, 2026-10-12).
2. **`StateFlow<T>` parameter: refuse by name, or bind something?** Recommendation: refuse, with a dedicated sentence and the `Flow<T>` plus `stateIn` hint; defer handle passthrough to 1.x.
   - Independent review: refuse.
   - Decision: a `StateFlow<T>` (and `SharedFlow<T>`, `MutableStateFlow<T>`) parameter is refused by name with the `Flow<T>` plus `stateIn` hint (human, 2026-10-12).
3. **Lifetime of the C# delegate or enumerable: Kotlin-owned (cleaner) or call-scoped?** Recommendation: Kotlin-owned through `createCleaner` at every position. Consequence to accept: the C# closure and whatever it captures stay alive until a Kotlin GC round, exactly as an ADR-084 bridge does.
   - Independent review: cleaner-owned.
   - Decision: Kotlin-owned lifetime through `createCleaner` at every position (human, 2026-10-12).
4. **C# delegate shape: `Func<T, CancellationToken, Task<R>>` or `Func<T, Task<R>>`?** Recommendation: with the token (one shape, no overload pair; a caller who does not need it writes `(x, _) =>`).
   - Independent review: with the token.
   - Decision: with the token, accepted as recommended (2026-10-12; research and independent review agreed, the human raised no objection).
5. **Do constructors and non-suspend members bind in v1?** They can only store the argument. Recommendation: yes if question 3 is answered Kotlin-owned (they come free with the plan route); no, and named, if call-scoped.
   - Independent review: yes.
   - Decision: yes, constructors and non-suspend members bind, accepted as recommended (2026-10-12; no objection).
6. **Which thread runs the C# lambda?** Only its synchronous prefix, up to the first `await` that does not complete synchronously, runs on the Kotlin dispatcher thread that invoked it; what follows resumes on the thread pool, and the caller's `SynchronizationContext` is never captured (finding 21; the first draft said the whole lambda ran on the dispatcher thread, which was wrong). Recommendation: document it, do not marshal back in v1.
   - Independent review: document it, with the corrected wording.
   - Decision: document the corrected thread wording, accepted as recommended (2026-10-12; no objection).
7. **Flat pointer slots or one table pointer?** Flat slots cost 3 per parameter plus the fixed thunks once per export (6 for one suspend lambda, 8 for one `Flow`, 12 for one of each); a pinned table would cost 2 per parameter but is a wire shape nothing in the forward bridge has. The first draft of this memo leaned towards the table; the review's precedent argument (finding 20) is the better one. Recommendation: flat slots, fixed thunks once per export. Neither touches the runtime ABI.
   - Independent review: flat slots, fixed thunks once per export.
   - Decision: flat slots with the fixed thunks once per export, accepted as recommended (2026-10-12; no objection).

Unresolved by this research, for the implementer:
- closed: the finding 9 sealed-arm `Flow` parameter drop is verified by a run (`Tier1AsyncParameterSealedArmTest`);
- the ADR-204 route (not read);
- the body of `isForwardLegacyAsyncRoute` (not read; finding 9 rests on its name and the comment beside its use);
- the inferred halves of findings 17 to 22: the double-free through the old shell, the adapting-closure leak, the thread-pool resumption and the stash residual were all reasoned from read code, none was run;
- **verified (by reading)**: there is no observable for the C# key table today. `NugetThunks` exposes only `RegisterCtx`, `UnregisterCtx` and `LookupCtx` over a private `_ctxTable` (`cir/CirCallbackRenderer.kt:39-62`), `LeakTests/` never references it, and `nuget_live_handles` counts Kotlin-side handles only. A leaked C# key on this route is therefore SILENT unless the implementation adds an internal count (one more touch in `cir/CirCallbackRenderer.kt`) or the leak rows assert collectability of a captured object through a `WeakReference`.
