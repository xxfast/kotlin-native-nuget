# Decide the forward finalizer contract

- ROADMAP: line text as of 2026-10-02: "Decide the forward finalizer contract: ADR-003 lists a finalizer as a mitigation and ADR-121 says none." (`ROADMAP.md:24`, section "0.9.0: breaking changes")
- Researched: 2026-10-02, about 15 of a shared 30 minute budget
- Restatement: a C# consumer of a generated forward wrapper gets one stated contract for what happens to an undisposed wrapper, the generated code matches it, and the 1.0.0 memory topic (`ROADMAP.md:46`) can state it. Forward; generated C# declares, C# consumers rely on it.
- Verdict: fix, contract decided at the gate 2026-10-02, implementation deferred to its own item ("Implement ADR-187: SafeHandle-owned forward handles"). ADR-187 drafted (Proposed): every generated forward wrapper's Kotlin handle is owned by a `SafeHandle`, so the .NET GC releases an undisposed wrapper's `StableRef` eventually; `Dispose()` stays the prompt path. This is a behaviour change (breaking only for code that relied on an undisposed wrapper keeping a Kotlin object alive with no C# reference, which is not observable through the API).

## Findings

1. **Today: no finalizer, no SafeHandle, anywhere in the forward generator.** Verified by reading. `renderDispose` emits `Interlocked.Exchange(ref _handle, IntPtr.Zero)` then `Native_Dispose(handle)` (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirClassRenderer.kt:740-799`); the class owns a bare `internal IntPtr _handle` (`CirClassRenderer.kt:203`). A grep for `SafeHandle`, `SuppressFinalize`, `GC.KeepAlive` or a `~Type()` under `nuget-processor/src/main` returns nothing (verified). The only `SafeHandle` in the codebase is the reverse-direction `KotlinRefHandle` (`nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetGenerateShimsTask.kt:3083-3099`).
2. **ADR-003 and ADR-121 disagree only on paper.** ADR-003 lists "IDisposable with a destructor/finalizer as safety net" as a mitigation (`docs/adr/003-memory-management-across-bridge.md:87`), never implemented. ADR-121 describes the shipped code ("No finalizer, no .NET GC in the path", `docs/adr/121-kotlin-object-collectability-after-last-dispose.md:24`); it is a description, not a decision. Verified by reading.
3. **Wrapper kinds that own a Kotlin handle** (all `IDisposable`, none finalizable; verified by grep): class wrappers and sealed bases/arms (`CirClassRenderer.kt:180`, `CirSealedRenderer.kt:27`), interface handle containers (`CirClassRenderer.kt:18`), `KotlinFunc`/`KotlinAction`/`KotlinSuspendFunc`/`KotlinSuspendAction` (`CirFunctionRenderer.kt:35,64,105,137,199,252,306,356`), `KotlinStateFlow<T>` (`CirFlowRenderer.kt:259`), `NugetSubscription` (`CirCallbackRenderer.kt:240`), plus the suspend scope handle (`_scopeHandle`, `CirClassRenderer.kt:771-781`).
4. **Thread affinity is not a blocker.** Verified by ADR-085's spike (`docs/adr/085-kotlin-implemented-csharp-interfaces.md:34-63`, K/N 2.4.10 macosArm64 + net10.0): a .NET finalizer on the finalizer thread P/Invokes a Kotlin export that disposes a `StableRef`, reliably, with no thread registration ("Kotlin/Native 2.4.10's MM attaches the incoming thread transparently"). The shipped reverse `KotlinRefHandle.ReleaseHandle` does exactly that today and is exercised by `IntegrationTests/MenagerieRoundTripTests.cs`, `IntegrationTests/WorkshopRoundTripTests.cs` and `LeakTests/LiveHandleTests.cs` (verified by grep that they reference the release count). Not spiked on mingwX64 or linux (inferred to behave the same). `NugetHandles.release` is `StableRef.dispose()` plus an `AtomicLong` decrement (`nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetRuntime.kt:59-70`), so it is thread-safe as far as the counter goes (verified by reading).
5. **Premature-finalization race is the real cost.** Inferred from .NET docs (`GC.KeepAlive`, `HandleRef`, SafeHandle docs), not spiked: once a wrapper is finalizable, the JIT may treat `this` (or an argument wrapper) as dead after `_handle` is loaded, so the finalizer can free the `StableRef` while Kotlin is mid-call. The P/Invoke marshaller `DangerousAddRef`s a `SafeHandle` parameter for the call's duration, which closes this for any handle passed as a `SafeHandle`; a raw `IntPtr` from `INugetHandle.Handle` (`CirMarshalRenderer.kt:387-390`) or `other._handle` in generated `Equals` (seen in the TestLibrary `Interop.cs`, e.g. `Native_Equals(_handle, other._handle)`) is not protected.
6. **Suspend routes do not hold the borrowed handle across the await.** Verified by reading the legacy method route: `buildSuspendMethodBody` dereferences `handle.asStableRef<T>().get()` and the argument prelude on the caller's thread before `launchForCSharp` (`nuget-processor/.../exports/SuspendFunctionExports.kt:225-254`), so the coroutine holds the Kotlin object strongly and the C# handle need only live for the synchronous P/Invoke. Verified in generated code that the C# completion closure captures `tcs`, `job`, `callbackHandle`, `cancellationToken`, not `this` (TestLibrary `Interop.cs`, `BoardingDesk.SettleAsync`). Inferred, not read: the CIR plan route and the Flow/StateFlow routes follow the same "deref before launch" rule; the comment at `SuspendFunctionExports.kt:236-238` says so for the helper, and an implementation must confirm each route. **The scope is the hazard, not the handle.** The class also owns `_scopeHandle`, whose release is `NugetScopeNative.Cancel` then `Dispose` (`CirClassRenderer.kt:771-781`). Since the completion closure does not capture `this`, `await new BoardingDesk().SettleAsync(5)` leaves the wrapper unreachable while the coroutine runs; under a GC-released scope that cancels the in-flight call and faults the Task. Today it works only because nothing releases the scope. Same for a wrapper dropped mid `await foreach` on a Flow route. Fix: the suspend, Flow and StateFlow completion closures capture the wrapper until completion (inferred design, not built).
7. **Ownership: no wrapper found over a handle it does not own.** Partially verified by reading: `NugetMarshal.Wrap` passes an `INugetHandle` handle as borrowed (`owned = false`, `CirMarshalRenderer.kt:343-353`) and never transfers it; callback payloads are owned by the C# wrapper by design (ADR-036 2026-09-11 amendment, `docs/backlog/callback-payload-wrapper-no-finalizer.md`). The TestLibrary fixture has 831 `(handle, out _)` construction sites; they were not all audited. A full audit is a precondition of the implementation: any wrapper built over a handle something else frees would double-free under a finalizer.
8. **Two ROADMAP items depend on this.** `ROADMAP.md:298` (callback-payload wrapper, `docs/backlog/callback-payload-wrapper-no-finalizer.md`) and `ROADMAP.md:308` (abandoned `Flow` item after `DisposeAsync` leaks a wrapper box). ROADMAP:298 closes under the SafeHandle contract with no extra code. ROADMAP:308 closes only for its wrapper-typed half (the `new T(handle)` box becomes collectible); its ADR-123 collection-element half (a raw container handle plus per-element boxes no wrapper owns) stays open under either contract and needs the enumerator to dispose when `TryWrite` returns false. Both stay open as documented leaks under "no finalizer". Verified by reading both entries.
9. **Cost.** Inferred from .NET docs: a finalizable object is registered on allocation and survives at least one extra GC (promoted to gen1). With `SafeHandle`, the finalizable object is the small handle, not the wrapper; `Dispose()` calls `SafeHandle.Dispose()`, which suppresses finalization, so a disposed wrapper pays only the registration. Not measured.

10. **SafeHandle marshals through the generated P/Invoke shape, JIT and NativeAOT.** Verified by spike (2026-10-02, SDK 10.0.301, ILCompiler 10.0.9, win-x64, MSVC BuildTools linker, scratch dir). The generated P/Invoke setup, verified by grep of the TestLibrary `Interop.cs`: 3,696 `[DllImport("<lib>", CallingConvention = CallingConvention.Cdecl, EntryPoint = "...")]` declarations, zero `LibraryImport`, zero `DisableRuntimeMarshalling` (also absent from `nuget-processor/src/main`, `nuget-plugin/src/main`, `AotSmokeTest`), and `delegate* unmanaged[Cdecl]` only for the C#-side callback thunks (29 sites), never for calls into Kotlin. The generated code already depends on runtime marshalling: 485 `[MarshalAs(UnmanagedType.LPUTF8Str)]` and 262 `[MarshalAs(UnmanagedType.I1)]` slots. Probe: `internal sealed class NugetKotlinHandle : SafeHandle` (ReleaseHandle counts and calls `CloseHandle`) declared as a `DllImport ... Cdecl, EntryPoint=` **return** (`CreateEventW`) and **parameter** (`SetEvent`, `WaitForSingleObject`) against kernel32, with `PublishAot=true`, `IsAotCompatible=true`. Output of the published NativeAOT `a.exe` (no AOT or trim warnings; `dotnet run` under the JIT printed the same lines with `aot=False`):

    ```
    aot=True
    invalid=False set=1 wait=0
    released after Dispose=1
    released after GC=1001
    exit=0
    ```

    So a SafeHandle return is constructed by the marshaller, a SafeHandle parameter passes the raw handle, `Dispose()` releases once, and 1,000 dropped handles are all released by finalization. With `[assembly: DisableRuntimeMarshalling]` added, the same declarations build but report `CA1420` ("Managed parameter or return types require runtime marshalling to be enabled") on each SafeHandle slot; that would fail `GeneratedBindingsCheck` (warnings as errors). The alternative if that attribute were ever adopted is `IntPtr` signatures with `DangerousAddRef` / `DangerousGetHandle` / `DangerousRelease` around each call; not needed, because the generated `string`/`bool` slots already rule `DisableRuntimeMarshalling` out (a consumer assembly that sets it breaks today's bindings too, inferred from the same CA1420 rule). Not spiked: macOS/linux NativeAOT (the repo's AOT smoke test runs osx-arm64; inferred to match), and a native side that is Kotlin rather than kernel32 (the ABI is a pointer-sized handle either way).

## Prior art (stopped once it settled)

- .NET guidance: types that own an unmanaged resource should wrap it in a `SafeHandle` rather than write a finalizer (https://learn.microsoft.com/dotnet/standard/garbage-collection/implementing-dispose, https://learn.microsoft.com/dotnet/api/system.runtime.interopservices.safehandle). Inferred (docs).
- Kotlin's own ObjC/Swift export: a Kotlin object referenced from Swift is released when the last Swift reference goes (ARC); no manual dispose (https://kotlinlang.org/docs/native-objc-interop.html). Inferred (docs).
- This repo's reverse side already chose it: `KotlinRefHandle : SafeHandle` releases a Kotlin `StableRef` on .NET collection (ADR-085, ADR-158). Verified by reading.
- Skipped: JavaCPP, CsWinRT. Three precedents already agree; nothing they add would change the call.

## Recommendation

SafeHandle-backed ownership on every forward wrapper kind (finding 3): one generated `internal sealed class NugetKotlinHandle : SafeHandle` per package (ReleaseHandle calls the wrapper kind's existing `_dispose` export, which is `NugetHandles.release` for classes), `_handle` becomes that type, `Dispose()` becomes `_handle.Dispose()` (plus the existing scope cancel), and every DllImport that takes a wrapper's handle takes the SafeHandle so the marshaller pins it for the call. The generic `INugetHandle.Handle` path keeps returning `IntPtr`; the code that consumes the pointer calls `GC.KeepAlive(wrapper)` after the native call. Suspend, Flow and StateFlow completion closures capture the wrapper so its scope is never released mid-flight. Size: L, roughly 12 to 15 processor files plus fixtures and leak rows.

Contract text for the 1.0.0 memory topic: "Dispose a wrapper to release its Kotlin object promptly. A wrapper you drop without disposing is released when the .NET GC finalizes it: eventually, on the finalizer thread, and not at process exit."

Rejected:
- Pin and close on "no finalizer, undisposed leaks, `NugetMarshal.LiveHandles` diagnoses": zero code, but contradicts .NET guidance and the repo's own reverse side, and leaves ROADMAP:298 and :308 as permanent documented leaks. It is the fallback if the implementing audit (findings 6, 7) finds a handle that cannot be kept alive.
- Plain `~Wrapper()` finalizer plus `GC.KeepAlive(this)` after each call: same audit, makes the whole wrapper finalizable (bigger gen1 promotion), and the KeepAlive discipline is hand-maintained per call site.
- `#if DEBUG` reporting finalizer: implementable (contentFiles compile in the consumer, so the consumer's configuration applies), but it still frees nothing and would need the same lifetime audit the day it grows a free.

## Files an implementation touches

- `nuget-processor/.../cir/CirClassRenderer.kt` (field, ctor `SetHandle`, `renderDispose`, `Equals`/`GetHashCode`/`ToString`, member call sites, and the suspend-method renderer so the completion closure captures the wrapper; `CirFlowRenderer.kt` likewise for Flow/StateFlow), `CirSealedRenderer.kt`, `CirFunctionRenderer.kt`, `CirFlowRenderer.kt`, `CirCallbackRenderer.kt`, `CirMarshalRenderer.kt` (`Wrap`, `FromHandle`, `Materialize`), `CirCollectionParameters.kt`, `CirConcurrencyRenderer.kt`, `CirErrorRenderer.kt`, `CirBridgeRenderer.kt`, `CirRenderer.kt` (the shared `NugetKotlinHandle` class), `CirModel.kt`/`CirClassTranslator.kt` (DllImport parameter type for handle slots).
- Processor golden/unit tests asserting the rendered `Dispose` and DllImport shapes.
- `IntegrationTests/` new GC-stress test; `LeakTests/LiveHandleTests.cs` new rows (undisposed wrapper, undisposed callback payload, abandoned Flow item).
- `ROADMAP.md:24,298,308` closed; `docs/backlog/callback-payload-wrapper-no-finalizer.md` deleted; ADR-003 and ADR-121 amended; docs/topics memory section (or the 1.0.0 topic).

## Sample test

```csharp
[Fact]
public void UndisposedWrapper_IsReleasedByTheGc()
{
    long baseline = NugetMarshal.LiveHandles;
    MakeAndDrop();                        // new Cat("x") with no using, in a non-inlined method
    for (int i = 0; i < 50 && NugetMarshal.LiveHandles > baseline; i++)
    {
        GC.Collect(); GC.WaitForPendingFinalizers();
    }
    Assert.Equal(baseline, NugetMarshal.LiveHandles);
}

[Fact]
public void CallsUnderGcPressure_NeverUseAFreedHandle()
{
    var stop = new CancellationTokenSource(TimeSpan.FromSeconds(2));
    var gc = Task.Run(() => { while (!stop.IsCancellationRequested) GC.Collect(); });
    for (int i = 0; i < 100_000; i++)
        Assert.Equal("x", new Cat("x").Name);  // receiver is dead right after _handle is read
    stop.Cancel(); gc.Wait();
}

[Fact]
public async Task DroppedWrapper_InFlightSuspendCall_Completes()
{
    var stop = new CancellationTokenSource(TimeSpan.FromSeconds(2));
    var gc = Task.Run(() => { while (!stop.IsCancellationRequested) GC.Collect(); });
    Assert.NotNull(await new BoardingDesk().SettleAsync(5)); // no local keeps the desk alive
    stop.Cancel(); await gc;
}
```

## Deferred scope

- Finalizers do not run at process exit on .NET Core and later (inferred, docs); the contract says so rather than adding a shutdown hook.
- Measuring allocation cost per wrapper (a BenchmarkDotNet row) is follow-up, not a gate.

## Open what-questions

Gate decisions, 2026-10-02 (human gate, relayed by the coordinator). The contract is decided; the implementation is deferred to its own item ("Implement ADR-187: SafeHandle-owned forward handles") and ADR-187 stays Proposed until that item lands.

1. Adopt the SafeHandle contract, or pin "no finalizer"? **Decided: adopt SafeHandle.** Implementation is not in the 0.9.0 decide batch; it is its own item.
2. One shared `NugetKotlinHandle` or one subclass per wrapper kind? **Decided: one per package, with small subclasses** for kinds whose release is not `NugetHandles.release` (scope, subscription token).
3. Does `INugetHandle.Handle` stay `IntPtr`? **Decided: yes, with the keep-alive at the users of the pointer** (`GC.KeepAlive(wrapper)` after the native call that consumes it, e.g. after the caller's `Add`/`Put`, not inside `NugetMarshal.Wrap`).
4. Does a `SafeHandle` parameter and return marshal through the generated P/Invoke shape under NativeAOT? **Answered by finding 10 above: yes (verified, win-x64).**
5. Is a dropped wrapper with an in-flight `suspend` or `Flow` operation kept alive until completion? **Decided: yes;** the suspend, Flow and StateFlow completion closures capture the wrapper, so a scope release never cancels an in-flight call.
6. Still open for the implementing item: the full ownership audit (finding 7) and confirming the CIR plan, Flow and StateFlow routes deref before launch (finding 6).
