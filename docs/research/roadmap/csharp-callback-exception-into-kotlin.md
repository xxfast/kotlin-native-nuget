# Generated forward callbacks that take down the host process: C# callback throw, Flow `onNext` materialisation throw, callback racing past `Dispose()`

- ROADMAP: three lines as of 2026-09-21 (line numbers drift, grep the quotes).
  1. Phase 7, `ROADMAP.md:122`: "Exception propagation from a C# callback into Kotlin (mirror of ADR-024/028/029); ADR-102 sets the v1 fail-fast policy this replaces." Details `docs/backlog/csharp-callback-exception-into-kotlin.md`.
  2. Phase 6, `ROADMAP.md:115`: "A materialisation throw inside a `Flow<T>`'s `onNext` callback kills the host process". Details `docs/backlog/flow-onnext-materialisation-throw-kills-host.md`.
  3. Phase 7, `ROADMAP.md:126`: "A callback invocation racing past `removeListener`/`Dispose()` can now trip `Environment.FailFast`". Details `docs/backlog/callback-race-past-dispose-failfast.md`.
- Researched: 2026-09-21, two passes. Pass 1, about 20 of 20 minutes, source reading only. Pass 2 (same day, about 10 of 25 minutes): the scratch .NET spikes (a), (b), the message-shape half of (d), and a new race spike (f) were RUN outside any checkout, see `## Spikes run (2026-09-21)`. Claims are now **verified by reading**, **verified by spike** (output quoted), or **inferred**. Still no Gradle and no repo build was run, so spikes (c), (e) and the real-build half of (d) remain open under `## Spike first`. All spike results are win-x64 only (.NET SDK 10.0.301; CoreCLR 10.0.9, CoreCLR 9.0.17, NativeAOT 10.0.9); Mono, Linux and macOS behaviour stays inferred.
- Restatement: forward direction, Kotlin declares, C# consumes. (1) A C# lambda, stored listener or C#-implemented interface member that throws while Kotlin is calling it no longer kills the process: Kotlin sees a `NugetManagedException` thrown at the Kotlin call site of the lambda, can catch it, and if it does not, the C# caller of the outer export gets a catchable exception. (2) A bridge-internal failure while completing a `Flow<T>` item (or a suspend result) faults the `IAsyncEnumerable<T>` (or the `Task`) and cancels the Kotlin collector, instead of killing the process. (3) A Kotlin invocation that lands after the C# side disposed the subscription is dropped (or reported through the new channel), never a use-after-free on a freed `GCHandle`.
- Verdict: **fix, as a sequence of three PRs under one new ADR (not drafted; two what-questions gate it).** Item 2 is C#-only and independent, ship first. Item 1 is the ABI change. Item 3 lands last because its honest answer for value-returning shapes needs item 1's channel. ADR-102's "Exception discipline" section is superseded for user-code thunks; ADR-104's convergence section is implemented with one recorded deviation (what the slot carries).

## Findings

### 1. Exactly one emitter owns the FailFast policy (verified by reading)

`grep FailFast|UnmanagedCallersOnly` over `nuget-processor/src/main` hits only `cir/CirCallbackRenderer.kt` (`:10`, `:78`, `:93`). `appendThunkBody` (`CirCallbackRenderer.kt:70-100`) renders every forward thunk: `try { [return] <invocation>; } catch (Exception ex) { Environment.FailFast("nuget: unhandled exception in <name>", ex); [return default;] }`. Its callers:

| Shape | Thunk emitter | ctx | Who invokes it on the Kotlin side | Runs user C# code inline? |
|---|---|---|---|---|
| Per-call lambda (ADR-036), 8 `NugetString*`/`NugetObjectVoid`/`NugetIntVoid`/`NugetVoid` shapes | `appendCtxDispatchThunk` via `renderCallbackDelegateHelper` `CirCallbackRenderer.kt:3-20` | GCHandle to the closure, freed in `finally` (`CirClassRenderer.kt:861-868`) | KSP-generated `exports/LambdaParameterExports.kt:123-135` (`$fnVar.invoke(...)`), inside the user's Kotlin lambda, inside the export's ADR-024 `try/catch` (`:149-196`) | yes |
| Stored callback (ADR-037) | same thunks | GCHandle, freed in the `NugetSubscription` action right after the native remove (`CirClassRenderer.kt:772-779`) | KSP-generated `exports/StoredCallbackExports.kt:195` (`fn.invoke(...)`) inside the `bridge` lambda the Kotlin object stores (`:204-210`) | yes |
| ADR-039 listener bridge (add/remove pair taking an interface) | same thunks (bridge delegates are merged into the shared delegate list, doc comment `CirCallbackRenderer.kt:22-27`) | one GCHandle per method, all freed in the subscription action (`CirClassRenderer.kt:827-845`) | `exports/InterfaceBridgeExports.kt:75` | yes |
| ADR-084 interface bridge slots | same thunks, `NugetBridge*Callback` | per-slot GCHandle in `_pins`, freed by `FreeAll()` (`CirBridgeRenderer.kt:70-108`) | KSP-generated `exports/InterfaceBridgeFactoryExports.kt:112-122` (`invocation(slot)`) | yes |
| ADR-084 bridge release | `NugetBridgeVoidCallback` thunk (`CirBridgeRenderer.kt:139-146`) | `releaseCtx` | Kotlin `createCleaner` block, `InterfaceBridgeFactoryExports.kt:48-50` | no (`state.FreeAll()`) |
| Suspend completion, including `KotlinSuspendFunc` (ADR-019/020) | `CirConcurrencyRenderer.kt:10-11` | GCHandle to the completion closure, freed inside the closure | **runtime** `launchForCSharp`, `nuget-runtime/.../NugetLaunch.kt:33-54` | no (TCS uses `RunContinuationsAsynchronously`, `CirConcurrencyRenderer.kt:195`), but see finding 3 |
| Flow `onNext`/`onComplete`/`onError` (ADR-026/065) | `appendFlowThunk` `CirFlowRenderer.kt:7-19`, `:65-71` | one shared GCHandle to `NugetFlowCallbacks`, rendezvous release (`:36-63`) | **runtime** `collectForCSharp`, `NugetLaunch.kt:66-88` | no (writes to a `Channel<T>`), but see finding 2 |
| `KotlinFunc`/`KotlinAction` (`nuget_funcN_invoke`) | none | n/a | n/a: a Kotlin lambda run inside Kotlin, never enters managed code (ADR-102 `:71-77`, verified there) | out of scope |

Consequence: errOut is only *needed* on the four user-code rows, and all four are invoked by KSP-generated Kotlin in the consumer module. The two rows invoked by the published `nuget-runtime` klib (suspend, Flow) run no user code and need no arity change. **The runtime callback wire protocol (`launchForCSharp`, `collectForCSharp`) stays untouched.**

### 2. Item 2's backlog text is stale on mechanism and line numbers; the bug is real (verified by reading)

- The cited `CirFlowRenderer.kt:44-49` / `:56-59` no longer match. `onNext` is now `CirFlowRenderer.kt:111-119`: `T value = _read(itemPtr); _channel.Writer.TryWrite(value);` with no `try`. `onError` is `:127-131` and, contrary to the backlog text, has **no** `try/catch` either: `NugetErrorNative.BuildException(errorPtr)` runs bare.
- Since ADR-102 the throw no longer "unwinds through the native-to-managed thunk": it is caught by `appendThunkBody`'s catch-all and becomes `Environment.FailFast`. Same consumer symptom (test host aborted), different mechanism. **Inferred** for this route: not reproduced against the repo build; the only recorded reproduction (Issue #40) predates ADR-102. The generic mechanism (a throw caught in a thunk, then `Environment.FailFast(message, ex)`, ends the process with that message on stderr) is **verified by spike (a)**, finding 8.
- `MoveNextAsync` already rethrows a faulted channel's inner exception (`CirFlowRenderer.kt:162-179`), so `_channel.Writer.TryComplete(ex)` is a complete consumer-visible fault path today.
- The reverse twin already got this right and says so: `nuget-runtime/.../NugetFlow.kt:74-76`, "`emit` runs on the collector, never on a .NET thread: element materialisation cannot throw inside an unmanaged callback (the reverse of the open forward `onNext` bug)".

### 3. The suspend completion closure has the same bug as item 2 and no ROADMAP line (verified by reading)

`CirConcurrencyRenderer.kt:199-216`: the closure runs `t.SetException(NugetErrorNative.BuildException(errorPtr))` or the `resultExtraction` (`new T(resultPtr)`, `FromHandle`, a collection read) bare. A materialisation throw there is `FailFast` via the `NugetAsyncCallback` thunk. Same closure is rendered at `CirClassRenderer.kt:~741-753` and four times in `CirFunctionRenderer.kt` (`:274-292`, `:340-355`, `:401-419`, `:467-482`). Also `callbackHandle.Free()` runs first inside the closure, so after a throw the handle is already freed and the `Task` never completes: even a swallow would hang the awaiter. Fold into item 2 (same path, same fix shape), per the "do not grow the phase with deferrals" rule.

### 4. Where the Kotlin side can throw from, per shape (verified by reading)

- Per-call: the `fn.invoke` is lexically inside the lambda handed to the user's method, which is inside `try { ... } catch (e: Throwable) { errorOut = buildError(e) }` (`LambdaParameterExports.kt:149-196`). A throw raised right after `fn.invoke` surfaces at the user's `format(name)` call site (fixture `test-library/.../cat/Cat.kt:78`), is catchable there, and if uncaught reaches C# through ADR-024 as `KotlinException` with `KotlinType` = the exception's `qualifiedName` (`NugetRuntime.kt:495-507`, `CirErrorRenderer.kt:76-84`). No new plumbing.
- Stored / listener bridge: the throw surfaces wherever the Kotlin object invokes its listeners (`moodListeners.forEach { it(mood) }`), usually under some other forward export's ADR-024 catch, sometimes on a coroutine or worker with no catch at all. That is ordinary Kotlin semantics for a throwing listener and is the library author's to handle. **Inferred**: an uncaught throw on a Kotlin worker still terminates the process (Kotlin/Native default unhandled-exception hook), so item 1 moves some crashes from "always" to "when Kotlin does not catch", it does not eliminate them.
- ADR-084 slot: surfaces at the Kotlin call of the interface member. Same as above.
- Cleaner release: nowhere to throw to. Must keep FailFast (or swallow).

### 5. ADR-104 convergence: what it fixes and what it leaves open (verified by reading the ADR and the shipped code)

ADR-104 `:505-540` fixes two things: trailing `IntPtr* errOut`, caller-zeroed, check-before-read; and one `NugetManagedException(managedType, message)`. It explicitly leaves "where the accessors come from" as a forward-run decision (`:523-531`). Shipped reality:

- `NugetManagedException` is **plugin-emitted text**, `internal`, in the consumer module's `INTERNAL_PKG`, inside `nugetRuntimeContent()` (`nuget-plugin/.../NugetGenerateBindingsTask.kt:5056-5059`), together with `nugetThrowManagedError` (`:5075-5090`, includes ADR-153's `kind == 1` to `CancellationException` mapping) and `nugetCall` (`:5099-5106`). It exists only when the reverse pipeline emitted the runtime file (`:4962-4963`: "whenever any bound signature contains a RirObjectHandleType").
- The accessors are function pointers registered through `nuget_runtime_register` by the reverse shim. **A forward-only library has no init-time registration of any kind**, so the GCHandle-plus-accessors envelope has nothing to read it with.
- `nuget-runtime` reaches only `${target}MainApi` (`nuget-plugin/.../NugetPlugin.kt:261`), and ADR-130 (`docs/adr/130-...md:145-190`) **verified by build** that `nativeMain` code cannot name a runtime klib type (`compileNativeMainKotlinMetadata` fails) and that the runtime cannot move to `nativeMain` (it publishes four targets, no iOS). So a `public class NugetManagedException` placed in `nuget-runtime` is visible to KSP-generated code (per-target source set) but **not nameable from an author's `nativeMain` code**, which is where authors write (`test-library/src/nativeMain/...`).
- ADR-130's shipped answer to the same boundary is the expect/actual seam (`internal expect fun` in the `nativeMain` file, `actual` in the `mingwMain`/`posixMain` files the plugin already emits).

### 6. No forward contract hash covers callback arity (verified by reading the emitters; ForwardAbiContract body only skimmed, so that half is inferred)

Callback pointers cross as `IntPtr` in every `DllImport` (`CirClassRenderer.kt:764`, `:853`), and the Kotlin side `reinterpret`s to a `CFunction<...>` type it spells itself (`LambdaParameterExports.kt:146`, `StoredCallbackExports.kt:204`, `InterfaceBridgeExports.kt:75`, `InterfaceBridgeFactoryExports.kt:36-42`). Nothing compares the C# thunk's `delegate* unmanaged[Cdecl]<...>` signature (`CirCallbackRenderer.kt:103-112`) with the Kotlin `CFunction` type. Both halves come from one KSP run and ship in one `.nupkg`, so there is no cross-version skew for the four user-code rows; the risk is one emitter being missed. ADR-104's two failure modes apply verbatim (`docs/adr/104-...md:393-401`): Kotlin passing N+1 to an N-ary thunk silently has no channel; an N+1-ary thunk called with N writes a GCHandle through garbage on the error path only. `ForwardAbiContract.kt:525-560` holds the runtime export name list (67 today, `nuget_runtime_version` the 67th, ADR-129); a new runtime export must be added there and to `scripts/verify-runtime-exports.sh`.

### 7. The dispose race: what is actually at risk (code verified by reading; .NET behaviour verified by spike (b) and (f), win-x64)

> Note 2026-09-21 (spike pass): spike (b) closed the question this finding left open, and the result is **stronger** than the inferred text below. Slot reuse is not "possible", it is **deterministic**: the GCHandle free list is LIFO, so the very next `GCHandle.Alloc` takes the freed slot. Observed on CoreCLR 10.0.9 (identical counts on CoreCLR 9.0.17 and NativeAOT 10.0.9):
>
> ```
> [1] after Free, no realloc: Target = null
> [2] 5000 cycles free-then-alloc-one: sameAddress=5000 targetIsOtherObject=5000 invokedWrongListener=5000 null=0 threw=0
> [3] 3000 cycles free-then-alloc-4: targetIsOtherObject=3000 (ofWhichWrongType=0) null=0 threw=0
> [4] 5000 cycles, other thread churning: targetIsOtherObject=408 null=4592 threw=0
> ```
>
> Reading: `[1]` a freed slot with no later allocation reads `null` (the ROADMAP's NullReferenceException then FailFast symptom, **verified**). `[2]` free, then one `Alloc` of a delegate of the same type: the stale `IntPtr` resolves to the new delegate and **invoking it runs the wrong listener, 5000 of 5000**, no exception (**verified**). `[4]` with no deliberate reallocation, only another thread churning handles, 8 to 15 percent of stale reads land on a foreign object (408, 481 and 737 of 5000 across the three runtimes). `FromIntPtr(...).Target` on a freed handle never threw in any run, so there is nothing to catch. `[3]`'s `ofWhichWrongType=0` is an artefact of the spike's allocation order (the first refill was always an `Action<string>`), so the "cast fails, then FailFast" branch stays **inferred**; the "cast succeeds, wrong listener" branch is the verified one and the worse one. **PR C cannot shrink to a null or generation check on the GCHandle; the never-reused key table stands.**

- Stored callback: `new NugetSubscription(() => { Native_remove(_handle, sub); cbHandle.Free(); })` (`CirClassRenderer.kt:779`; listener bridge `:845`). The Kotlin remove export only calls `obj.removeX(bridge)` and releases the unregister lambda (`StoredCallbackExports.kt:233-237`). A Kotlin thread that already read the listener list can call `fn.invoke(..., userData)` after `cbHandle.Free()`.
- The thunk then runs `GCHandle.FromIntPtr(ctx).Target` on a freed handle (`CirCallbackRenderer.kt:60`). **Verified by spike (b)**, see the note above (the documentation only says a freed `GCHandle` is undefined). In practice the slot reads null (NullReferenceException, then FailFast: the ROADMAP symptom) **or the slot has been reused by another allocation**, in which case the cast either fails (FailFast) or, when the new occupant is a delegate of the same type from another subscription, **succeeds and silently invokes the wrong listener**. So "catch and drop" inside the thunk is not a fix: a freed GCHandle cannot be reliably detected. This makes item 3 worse than the ROADMAP line says.
- ADR-084 slots are **not** exposed (inferred): `FreeAll()` is only reached from the Kotlin cleaner, which runs after the bridge object is unreachable, so no slot call can be in flight.
- Flow already has a correct answer for its own version of this, the two-party rendezvous (`CirFlowRenderer.kt:51-62`), possible because Kotlin guarantees a terminal callback. Stored callbacks have no terminal callback.
- ADR-089 precedent (`docs/adr/089-...md:176-190`): a stale release is made a no-op by comparing identity (`entry.ctx == ctx`) rather than trusting the handle. ADR-135 (`:46-80`) is the precedent for "a managed throw overwritten by a crash in cleanup": same family, different site.

### 8. .NET constraints (mechanism verified by spike (a), win-x64 CoreCLR 10.0.9 and NativeAOT 10.0.9; one documented rule CORRECTED)

An exception must not unwind out of an `[UnmanagedCallersOnly]` method; such methods must be static, non-generic, in non-generic types, blittable signature. `IntPtr*` is blittable and already shipped on reverse thunks (ADR-104 `:231`). A P/Invoke made from inside a thunk's `catch` is an ordinary nested managed-to-native call; the closures already P/Invoke from inside thunks (`_read(itemPtr)`, `NugetMarshal.FromHandle`). AOT: nothing here needs a runtime-built stub.

**Verified by spike (a)**: a `[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })] static IntPtr StringString(IntPtr arg, IntPtr ctx, IntPtr* errOut)` whose `catch (Exception ex)` does `*errOut = Native.ErrorCreate(...)` (a `DllImport` with a `[MarshalAs(UnmanagedType.LPUTF8Str)] string`, the same string convention the repo pins in `cir/CirUtf8Marshalling.kt:3`, standing in for `nuget_managed_error_create`) inside a nested `try` whose own catch is `Environment.FailFast`. Invoked through a `delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr*, IntPtr>`, and a sibling thunk invoked by a genuinely native caller (`ucrtbase!qsort` calling the comparator). Identical output under CoreCLR (`dynamicCode=True`) and under `PublishAot=true` (`dynamicCode=False`, publish took about 3 s, no trim or AOT warnings):

```
[good] ret=42 err=0
[bad] ret=0 err=System.InvalidOperationException|boom
[qsort native caller] err=System.InvalidOperationException|boom from native caller; process alive
```

**Verified by spike (a), `missing` mode** (the "old runtime under new C#" claim in "Contract hash impact" below): a P/Invoke to an absent export from inside the catch throws `EntryPointNotFoundException` there, the nested catch fail-fasts, both runtimes: `Process terminated. nuget: error channel failed: EntryPointNotFoundException`. Loud, not silent.

**Verified by spike (a), `failfast` mode** (message shape half of spike (d)): `Environment.FailFast("nuget: unhandled exception in NugetStringStringCallback", ex)` from inside a thunk prints, on CoreCLR, `Process terminated.` then the message on its own line, then the FailFast stack, then `System.InvalidOperationException: boom` with its stack; NativeAOT prints `Process terminated. nuget: unhandled exception in NugetStringStringCallback` on ONE line. A red test that matches stderr must match the message substring, not a whole line.

> Note 2026-09-21 (spike pass), CORRECTION: "an escaping exception always fail-fasts" is **false on CoreCLR win-x64**. With the thunk's catch removed, an exception thrown in the `[UnmanagedCallersOnly]` comparator propagated **through the native `qsort` frame** and was caught by the outer managed `try/catch` (`OUTER MANAGED CATCH GOT InvalidOperationException`, `SURVIVED escape-qsort`), process alive. With no outer managed catch it ends as an ordinary `Unhandled exception. System.InvalidOperationException: boom escaping`. Under NativeAOT 10.0.9 the same two modes terminate the process even with the outer catch in place. So the behaviour is runtime-dependent, and on CoreCLR Windows it is SEH unwinding across native frames: in the product those frames are Kotlin/Native frames, whose `finally` blocks, `memScoped` frees and StableRef disposals would be skipped (**inferred**, not spiked: needs the repo build). Non-Windows CoreCLR is **inferred** to terminate (no SEH interop). This does NOT change the recommendation, and the implementer must not be tempted by it: **the catch-all in every thunk stays mandatory, the escape path is not an error channel.** It does mean that any generated `[UnmanagedCallersOnly]` method that lacks the catch-all (check the reverse thunks and anything rendered outside `appendThunkBody`) fails differently per runtime, which is worth one grep in PR B.

## Recommendation

One design, three PRs, one new ADR.

**PR A (item 2 plus finding 3, C#-only, size S).** In the `onNext` closure wrap the read: on catch, `_channel.Writer.TryComplete(ex)`, set a `_faulted` flag, `NugetJobNative.Cancel(_jobHandle)` when the handle is already assigned, and have the constructor cancel right after `startCollect` returns if `_faulted` was set during it (a synchronous first emission can arrive before `_jobHandle` is assigned, `CirFlowRenderer.kt:137-141`; inferred). Kotlin then delivers its terminal `onNext(isCancelled: 1)`, which is already a no-op on a completed channel and performs the rendezvous `Release()`. Wrap `onError`'s `BuildException` the same way. In the suspend completion closures wrap everything after `job.CompleteFromCallback(); callbackHandle.Free();` and route a catch to `t.TrySetException(ex)`. The thunk-level FailFast stays as the backstop. No Kotlin, runtime or ABI change. Known residue: the item handle that failed to materialise leaks (one StableRef per failed item); accept and note in LeakTests.

**PR B (item 1, size M/L).** Trailing `IntPtr* errOut` on every `helper.delegates` thunk, which is one edit to `appendCtxDispatchThunk`/`appendThunkBody`/`appendThunkPointer`: the thunk takes `errOut` after ctx, does **not** pass it to the delegate (delegate types and every closure stay byte-identical), and the catch becomes `if (errOut != null) { *errOut = NugetManagedErrorNative.Create(ex); } else { Environment.FailFast(...); }` inside a nested `try` whose own catch is FailFast. Flow and async thunks keep today's arity and FailFast. The cleaner's release call passes `null` and keeps FailFast.

What the slot carries (the deviation from ADR-104, what-question 1): **a Kotlin-owned handle, pushed by C#**, not a GCHandle read back through accessors. `Create(ex)` P/Invokes one new runtime export, `nuget_managed_error_create(type: String, message: String?, kind: Int): COpaquePointer`, returning a raw `StableRef` to a small holder (raw, not `NugetHandles.retain`, the ADR-130 "Allocation, pinned" argument: keeps `nuget_live_handles` baselines still). The Kotlin invocation sites wrap the call in a runtime helper, `nugetCallbackCall { err -> fn.invoke(args, ctx, err) }`, that allocates and zeroes the slot, checks it **before** the return value is touched (ADR-104's structural ordering, `NugetGenerateBindingsTask.kt:5092-5106`), disposes the holder and throws (`kind == 1` to `CancellationException` with the managed exception as cause, mirroring ADR-153). Why push rather than accessors: a forward-only library has no registration step, and adding one (a static constructor on `NugetThunks` registering three function pointers) buys nothing the push does not. The deferred Phase 11 extras (stack trace, `InnerException` chain) become extra parameters or sibling exports instead of accessor slots; still no thunk arity change.

Where `NugetManagedException` lives (what-question 2): recommended end state is **one public class in `nuget-runtime`**, thrown by the runtime helper, with the reverse template's `internal class NugetManagedException` (`NugetGenerateBindingsTask.kt:5056`) replaced by ADR-130's seam: `internal expect class` in the `nativeMain` file, `internal actual typealias ... = runtime.NugetManagedException` in the `mingwMain`/`posixMain` files. **Inferred, unspiked, load-bearing for nameability only**: expect class plus actual typealias across this exact source-set split, and whether the Beta expect/actual-classes warning trips a consumer's warnings-as-errors. If it fails, generated code still compiles and still throws the runtime type; authors in `nativeMain` would catch `RuntimeException`. That failure mode is why this beats the alternative of KSP-generated code naming a plugin-emitted class (a missing `nativeMain` srcDir, `docs/backlog/reverse-bindings-srcdir-silently-missing-unnamed-nativemain.md`, would then break every forward library that has a callback).

Optional in PR B, recommended: on the per-call route the C# call site owns the closure, so it can stash the original exception in a local and, when the native call reports an error whose `KotlinType` is the managed-exception type, rethrow the **original** with `ExceptionDispatchInfo.Capture(original).Throw()`. That gives `Assert.Throws<InvalidOperationException>` verbatim for the commonest case at the price of one local and one `if`, with no ABI. Otherwise C# sees `KotlinException` with `KotlinType == "...NugetManagedException"` (ADR-104 Fork C's fidelity loss).

**PR C (item 3, size S/M).** Replace "ctx = `GCHandle.ToIntPtr`" with "ctx = a never-reused key" for stored callbacks and listener bridges: `NugetThunks` gains a `static ConcurrentDictionary<IntPtr, object>` and a monotonic `Interlocked.Increment` counter; subscribe adds, the subscription action removes after the native remove, the thunk does `TryGetValue` and on a miss takes the late-call path. Late-call path: for `void` shapes, return (drop: the consumer unsubscribed, silence is what they asked for); for value-returning shapes, report through `errOut` as `System.ObjectDisposedException` (a default would make Kotlin's `!!` NPE, `LambdaParameterExports.kt:130`). Per-call lambdas and ADR-084 slots can move to the same table for uniformity (a per-call lambda invoked after its call returned is a Kotlin author bug, escaping lambda, and gets the `ObjectDisposedException`). Flow keeps its rendezvous. No Kotlin change, no ABI change. Cost: one dictionary lookup per callback; **verified by spike (f)**: `ConcurrentDictionary<IntPtr, object>.TryGetValue` 2.0 ns per lookup against 1.0 ns for `GCHandle.FromIntPtr(...).Target` (1000 entries, 50M lookups, CoreCLR 10.0.9, Release), so about 1 ns added per callback; the microbenchmark backlog item (`docs/backlog/crossing-microbenchmark.md`) is where to measure it in situ.

> Note 2026-09-21 (spike pass): the table design itself was spiked, (f). An emitter thread holding a stale ctx, 200000 subscribe/dispose cycles on the other thread, each listener returning its own id:
>
> ```
> [GCHandle ctx] 200000 subscribe/dispose: ok=70 droppedNull=5002 WRONG_LISTENER=4340 threw=0 (25 ms)
> [never-reused key table] 200000 subscribe/dispose: ok=15 droppedMiss=26410 WRONG_LISTENER=0 threw=0 (46 ms)
> ```
>
> The `ok`/`dropped` totals are emitter-timing artefacts; the load-bearing columns are `WRONG_LISTENER` (4340 against 0) and `threw=0` (today's scheme fails silently, there is nothing to catch). So the shrink the first pass left open ("if reuse never happens PR C could be a null check") is **closed: no**. A generation counter stored beside the GCHandle does not help either, because the thunk only receives the `IntPtr`; the generation would have to travel in ctx, which is the key table by another name. Accepted residual, state it in the ADR as the semantics PR C ships: a lookup that succeeds just before `TryRemove` still invokes the right delegate after `Dispose()` returned. That is ordinary late delivery to the listener that was subscribed, not use-after-free and not a wrong listener. Only `ConcurrentDictionary` on CoreCLR and NativeAOT was spiked; Mono (the iOS/Android .NET runtimes) stays inferred, and the table is the conservative choice there too.

Alternatives rejected:
- ADR-104 literal (GCHandle in the slot, accessor function pointers): needs a forward registration step that does not exist; two accessor sets when both pipelines run.
- errOut on the Flow and async thunks too: changes the published runtime's callback wire for callbacks that run no user code; the C#-local catch is complete.
- Catch-and-swallow in the thunk: silent corruption, already rejected at ADR-102's gate.
- Item 3 by catching the freed-handle exception, or by a null check on `Target`: **verified by spike (b)** undetectable, the freed slot is reused by the next `Alloc` and nothing throws (finding 7).
- Item 3 by a Kotlin-side in-flight gate that `remove` waits on: deadlocks when `Dispose()` is called from inside the callback; needs a re-entrancy carve-out.
- Item 3 by never freeing: leak per subscription.
- KSP-generated code naming a plugin-emitted `internal` exception class: couples every forward callback to the reverse srcDir wiring bug.

## Files an implementation touches

PR A: `nuget-processor/src/main/kotlin/.../processor/cir/CirFlowRenderer.kt` (`:111-131`, ctor `:137-145`), `cir/CirConcurrencyRenderer.kt` (`:199-216`), `cir/CirClassRenderer.kt` (`~:741-753`), `cir/CirFunctionRenderer.kt` (four closures), Tier 1 text pins for each, `IntegrationTests` one new file, `LeakTests/LiveHandleTests.cs` note. One worktree.

PR B, processor C# side: `cir/CirCallbackRenderer.kt` (thunk shell and pointer signature), `cir/CirErrorRenderer.kt` (new `NugetManagedErrorNative`, optional rethrow-original arm in `BuildMapped`), `cir/CirBridgeRenderer.kt` (release thunk stays errOut-less or passes null), `cir/CirClassRenderer.kt` (`:850-870`, optional stash), `cir/CirModel.kt` and `cir/CirTranslator.kt` (helper gating for the new import), `ForwardAbiContract.kt` (68th runtime export name). Processor Kotlin side: `exports/LambdaParameterExports.kt` (`:83-136`, `:146`), `exports/StoredCallbackExports.kt` (`:180-210`), `exports/InterfaceBridgeExports.kt` (`:75` and its invoke), `exports/InterfaceBridgeFactoryExports.kt` (`:32-50`, `:112-148`; the cleaner's `fn.invoke(ctx)` becomes `fn.invoke(ctx, null)` only if the release thunk shares the new shell), plus any sealed-arm copies routed through the same emitters (ADR-116 selectors reuse them, verified `LambdaParameterExports.kt:225-245`). Runtime: `nuget-runtime/.../NugetRuntime.kt` (export, holder, `NugetManagedException`, `nugetCallbackCall`), a `nativeTest`, `scripts/verify-runtime-exports.sh` (67 to 68). Plugin (the fold, separable): `nuget-plugin/.../NugetGenerateBindingsTask.kt` (`:5056-5090` and the per-target template), `NugetGenerateBindingsTaskTest.kt`. Tests: `IntegrationTests/CallbackExceptionTests.cs` (new), `IntegrationTests/AotThunkGenerationTests.cs` (pins thunk text today, will move), `AotSmokeTest/` (one throwing callback per shape), `LeakTests/LiveHandleTests.cs` (error holder returns to baseline; argument handles minted before a throwing callback, `LambdaParameterExports.kt:100-105`, must not leak), Tier 1 arity-agreement test (finding 6). Fixture: `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/` new `CallbackFaults.kt`. Docs: `docs/topics/` callbacks and exceptions pages, `FEATURES.md`, ADR-102 and ADR-104 status notes (documenter). Conflicts with PR A only in `CirClassRenderer.kt`; stack B on A.

PR C: `cir/CirCallbackRenderer.kt` (table, thunk lookup), `cir/CirClassRenderer.kt` (`:772-779`, `:827-845`, per-call `:861-868`), `cir/CirBridgeRenderer.kt` if slots move, one stress test, AotSmokeTest unchanged. Stack on B (same thunk shell).

Contract hash impact: none on the reverse per-type `contractHash` or `NUGET_RUNTIME_CONTRACT_HASH` unless the plugin fold lands (then the reverse template text changes but no slot count or arity does; inferred). Forward has no hash to bump (finding 6); the new runtime export is additive, and an old runtime under new C# fails loudly (EntryPointNotFoundException inside the catch, then FailFast; **verified by spike (a)** `missing` mode on CoreCLR and NativeAOT, finding 8, provided PR B keeps the nested `try` around the `Create` call).

## Sample test

Fixture Kotlin (`CallbackFaults.kt`):

```kotlin
class CallbackFaults {
  private val listeners = mutableListOf<(String) -> Unit>()
  fun describeWith(format: (String) -> String): String = format("Oreo")
  fun recoverWith(format: (String) -> String): String =
    try { format("Oreo") } catch (e: Exception) { "recovered: ${e.message}" }
  fun addListener(listener: (String) -> Unit) { listeners.add(listener) }
  fun removeListener(listener: (String) -> Unit) { listeners.remove(listener) }
  fun emit(value: String) { listeners.toList().forEach { it(value) } }
  fun emitSafely(value: String): Int =
    listeners.toList().count { runCatching { it(value) }.isFailure }
}
```

xunit:

```csharp
[Fact]
public void PerCallLambdaThrow_ReachesTheCSharpCaller_AndTheHostSurvives()
{
    using var faults = new CallbackFaults();
    var ex = Assert.ThrowsAny<Exception>(
        () => faults.DescribeWith(_ => throw new InvalidOperationException("boom")));
    Assert.Contains("boom", ex.Message);           // with rethrow-original: Assert.Throws<InvalidOperationException>
    Assert.Equal("Oreo!", faults.DescribeWith(s => s + "!"));   // still alive
}

[Fact]
public void PerCallLambdaThrow_IsCatchableInKotlin()
{
    using var faults = new CallbackFaults();
    Assert.Equal("recovered: boom",
        faults.RecoverWith(_ => throw new InvalidOperationException("boom")));
}

[Fact]
public void StoredListenerThrow_SurfacesAtKotlinsInvocationSite()
{
    using var faults = new CallbackFaults();
    using IDisposable sub = faults.AddListener(_ => throw new ArgumentException("bad"));
    Assert.Equal(1, faults.EmitSafely("x"));
    var ex = Assert.ThrowsAny<Exception>(() => faults.Emit("x"));
    Assert.Contains("bad", ex.Message);
}

[Fact]
public async Task DisposeRacingEmit_NeverKillsTheHost()          // PR C
{
    using var faults = new CallbackFaults();
    using var stop = new CancellationTokenSource();
    Task emitter = Task.Run(() => { while (!stop.IsCancellationRequested) faults.Emit("x"); });
    for (int i = 0; i < 10_000; i++) faults.AddListener(_ => { }).Dispose();
    stop.Cancel();
    await emitter;
}
```

PR A: a Tier 1 text pin that `onNext` and the suspend closure carry the `try/catch`, plus an integration test only if a throwing element type is reachable from a fixture (what-question 5). The race test needs the fixture's `listeners` to tolerate concurrent mutation (`toList()` on a plain `mutableListOf` is itself racy; use a copy-on-write `AtomicReference<List<...>>` in the fixture). Inferred: the race is rare enough that this test proves absence of a crash only statistically.

## Deferred scope

- .NET stack trace, `InnerException` chain, and a .NET-to-Kotlin exception type map (ADR-104 Fork B, Phase 11), now as extra `nuget_managed_error_create` parameters or sibling exports.
- Identity-preserving round trip for stored callbacks, listener bridges and ADR-084 slots (the per-call stash does not generalise: the C# frame that would rethrow is not the one that subscribed).
- An uncaught `NugetManagedException` on a Kotlin worker or coroutine with no handler still ends the process (finding 4). Document, do not fix.
- The cleaner release thunk and the Flow/async thunks keep FailFast as a backstop for bugs in generated code.
- `KotlinFunc`/`KotlinAction`/`KotlinSuspendFunc` invoke paths: Kotlin exceptions there already travel the ADR-024 channel or the async error arm; nothing managed throws into Kotlin.
- `docs/backlog/null-lambda-argument-cannot-cross-callback-bridge.md` and `callback-payload-wrapper-no-finalizer.md` touch the same emitters but are separate items.

## Open what-questions

1. WHAT does `errOut` carry on forward thunks: ADR-104's GCHandle read through registered accessors, or a Kotlin-owned holder pushed by C# through one new runtime export? Recommendation: the push (no forward registration exists; one P/Invoke; same trailing-slot shape and same exception type, so ADR-104's two stated convergence points hold). Needs a human yes because ADR-104 says "should not diverge". Human decision: pending.
2. WHERE does `NugetManagedException` live so that it is one type and an author can name it from `nativeMain`? Recommendation: public class in `nuget-runtime`, reverse template folded onto it through the ADR-130 expect/actual seam (`actual typealias`); spike first. Fallback: ship the runtime class, leave the reverse `internal` class alone for now (two types, same simple name, until the fold). Human decision: pending.
3. Should the per-call route rethrow the **original** C# exception to the C# caller when Kotlin did not catch it? Recommendation: yes, it is C#-local and is what a C# developer expects from `cat.DescribeWith(x => throw ...)`. Open sub-question: only when the escaping Kotlin error's type is the managed-exception type, so a Kotlin author who wraps it keeps their wrapper.
4. Late callback after `Dispose()` on a `void` stored listener: drop silently, or throw `ObjectDisposedException` into Kotlin? Recommendation: drop for `void`, throw only where a value must be returned.
5. Is there a fixture-reachable `Flow<T>` element whose materialisation throws today (the enum `FromHandle<T>` gap in `docs/backlog/fromhandle-no-enum-branch.md` looks like one)? If yes PR A gets a real red integration test; if not, a Tier 1 pin is the only proof and the memo's "kills the host" claim stays inferred.
6. ADR: recommendation is one **new** ADR ("forward callback error channel and late-callback policy") that supersedes ADR-102's "Exception discipline" section for user-code thunks and records the deviation from ADR-104's convergence note; ADR-102 and ADR-104 each get a one-line status pointer, not an amendment. PR A needs no ADR (defect fix under ADR-026).

## Spikes run (2026-09-21)

All in a scratch directory outside every checkout (deleted afterwards), win-x64, .NET SDK 10.0.301. No repo code was built or run. NativeAOT publish needed `C:\Program Files (x86)\Microsoft Visual Studio\Installer` on `PATH` from Git Bash (`'vswhere.exe' is not recognized` otherwise), then took about 3 s.

| Spike | Seam | Result | .NET used |
|---|---|---|---|
| (b) GCHandle slot reuse | finding 7, PR C, the rejected "catch or null check" alternative | **Reuse is deterministic (LIFO free list)**: free then one `Alloc`, `sameAddress=5000 targetIsOtherObject=5000 invokedWrongListener=5000 null=0 threw=0`. No realloc: `Target = null`. Background churn only: 408 to 737 of 5000 stale reads hit a foreign object. Never throws. PR C needs the never-reused key table. | CoreCLR 10.0.9, CoreCLR 9.0.17, NativeAOT 10.0.9, same counts for `[1]` to `[3]` |
| (a) errOut thunk, P/Invoke from `catch` | finding 8, PR B thunk shell | Compiles and runs: the throwing delegate returns `ret=0` with the slot carrying `System.InvalidOperationException` and `boom` (full output in finding 8); also with a genuinely native caller (`qsort`). Push design is alive, accessor fallback not needed on mechanism grounds. | CoreCLR 10.0.9, NativeAOT 10.0.9 (`PublishAot=true`, no warnings) |
| (a) `missing` mode | "Contract hash impact", old runtime under new C# | `Process terminated. nuget: error channel failed: EntryPointNotFoundException`. Loud. | CoreCLR 10.0.9, NativeAOT 10.0.9 |
| (a) `failfast` mode, which is the message-shape half of (d) | ADR-102 policy, PR B red test | Message `nuget: unhandled exception in NugetStringStringCallback` reaches stderr verbatim, followed by the original exception and its stack. CoreCLR puts it on its own line after `Process terminated.`, NativeAOT on the same line. | CoreCLR 10.0.9, NativeAOT 10.0.9 |
| (a) `escape`, `escape-caught`, `escape-qsort` modes | finding 8's "must not unwind out" rule | **Contradicts the blanket claim.** CoreCLR win-x64: the exception unwinds through the native `qsort` frame and is caught by the outer managed catch, process survives; uncaught it is a plain `Unhandled exception.` crash. NativeAOT: process dies in all three modes. Recommendation unchanged (catch-all stays mandatory), wording corrected. | CoreCLR 10.0.9, NativeAOT 10.0.9 |
| (f) new: racing emitter, GCHandle ctx against key table | PR C design and its cost claim | `WRONG_LISTENER=4340` against `0`, `threw=0` both; lookup 2.0 ns against 1.0 ns. | CoreCLR 10.0.9 |

Not covered by any spike: Mono, Linux, macOS; the "cast fails then FailFast" branch of finding 7; whether SEH unwinding skips Kotlin/Native cleanup (finding 8 note); everything under `## Spike first`.

## Spike first (still open: each needs the repo build, so they are the implementer's first red cells)

c. PR B, gates what-question 2 only, do it BEFORE writing the runtime class. In a worktree, add to the plugin's reverse template an `internal expect class NugetManagedException(managedType: String, message: String?) : RuntimeException` in the `nativeMain` file and `internal actual typealias NugetManagedException = <runtime package>.NugetManagedException` in the `mingwMain`/`posixMain` files. Red cell: a `NugetGenerateBindingsTaskTest` ProjectBuilder case that runs `compileNativeMainKotlinMetadata` plus one target compile and asserts success; record the exact warning text (Beta expect/actual classes) and whether `allWarningsAsErrors` trips. Pass: fold the reverse class onto the runtime type. Fail: take what-question 2's fallback (two types), nothing else in PR B moves.
d. PR B, first red integration cell (message shape already verified by spike (a), so this only proves the ROUTE): `CallbackExceptionTests.PerCallLambdaThrow_ReachesTheCSharpCaller_AndTheHostSurvives` against today's build must abort the test host with stderr CONTAINING `nuget: unhandled exception in NugetStringStringCallback` (substring match, CoreCLR and NativeAOT differ on line breaks). If the delegate name in the message differs, the fixture went down another thunk shape: fix the fixture before touching the emitter. Run it in its own `dotnet test` invocation, it kills the host.
e. PR B, Tier 1 arity-agreement cell, written red BEFORE the emitter edit: for each of the four user-code emitters (`LambdaParameterExports`, `StoredCallbackExports`, `InterfaceBridgeExports`, `InterfaceBridgeFactoryExports`) parse the generated Kotlin `CFunction<(...) -> R>` parameter count and the C# `delegate* unmanaged[Cdecl]<...>` from `appendThunkPointer`, assert equal counts and that the last is `CPointer<COpaquePointerVar>?` against `IntPtr*`. It passes today at arity N; flip the expectation to N+1 first so it goes red, then make both halves green together. This is the only guard against finding 6's silent failure (verified by reading, not spiked: the pointer crosses as `IntPtr`, so neither compiler sees the other half's arity).
g. PR A, only if what-question 5 finds a throwing element: one integration cell, in its own process, that collects a `Flow<T>` whose item materialisation throws and asserts the `await foreach` faults instead of the host dying. Otherwise the Tier 1 text pin is the proof and "kills the host" stays inferred for item 2.
