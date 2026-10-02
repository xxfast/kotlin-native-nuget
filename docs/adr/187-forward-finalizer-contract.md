# ADR-187: Forward, an undisposed generated wrapper releases its Kotlin handle through a `SafeHandle` when the .NET GC collects it

## Status
Proposed. Contract decided at the human gate on 2026-10-02; implementation deferred to its own ROADMAP item ("Implement ADR-187: SafeHandle-owned forward handles"), and this ADR moves to Accepted when that item lands.

## Context

ADR-003 lists "IDisposable with a destructor/finalizer as safety net" (`docs/adr/003-memory-management-across-bridge.md:87`); the generator never emitted one, and ADR-121 describes that shipped state ("No finalizer, no .NET GC in the path", `docs/adr/121-kotlin-object-collectability-after-last-dispose.md:24`). The 1.0.0 memory topic (`ROADMAP.md:46`) has to state one contract, and two leak items wait on it: the callback-payload wrapper (`ROADMAP.md:298`, `docs/backlog/callback-payload-wrapper-no-finalizer.md`) and the abandoned `Flow` item after `DisposeAsync` (`ROADMAP.md:308`).

Verified by reading: no forward wrapper has a finalizer, `SafeHandle`, `GC.KeepAlive` or `SuppressFinalize` (grep of `nuget-processor/src/main`); a class wrapper is a bare `internal IntPtr _handle` released by `Interlocked.Exchange` plus `Native_Dispose` (`cir/CirClassRenderer.kt:203,740-799`). Handle-owning kinds: class, sealed base and arm, interface container, `KotlinFunc`/`KotlinAction`/`KotlinSuspend*` (`cir/CirFunctionRenderer.kt`), `KotlinStateFlow<T>` (`cir/CirFlowRenderer.kt:259`), `NugetSubscription` (`cir/CirCallbackRenderer.kt:240`), and the suspend scope handle.

Verified by ADR-085's spike (`docs/adr/085-kotlin-implemented-csharp-interfaces.md:34-63`, K/N 2.4.10 macosArm64, net10.0): a .NET finalizer on the finalizer thread can P/Invoke a Kotlin export that disposes a `StableRef`, with no thread registration. The reverse `KotlinRefHandle : SafeHandle` (`nuget-plugin/.../NugetGenerateShimsTask.kt:3083-3099`) already does this in shipped code. Not verified on mingwX64 or linux (inferred to match).

## Alternatives Considered

### 1. `SafeHandle`-owned handle on every wrapper kind (chosen)

`_handle` becomes an `internal sealed class NugetKotlinHandle : SafeHandle` whose `ReleaseHandle` calls the release export; `Dispose()` calls `_handle.Dispose()`; DllImports that take a wrapper's handle take the `SafeHandle`, so the marshaller `DangerousAddRef`s it for the call. Matches .NET guidance (wrap native resources in `SafeHandle`, https://learn.microsoft.com/dotnet/standard/garbage-collection/implementing-dispose), Kotlin's own Swift/ObjC export (ARC releases the Kotlin object), and this repo's reverse side (ADR-085, ADR-158). Only the small handle object is finalizable, not the wrapper. Cons: size L; a lifetime audit of every raw-`IntPtr` reader is mandatory.

### 2. No finalizer, documented leak (pin and close)

Zero code. `NugetMarshal.LiveHandles` diagnoses an undisposed wrapper. Cons: contradicts .NET guidance and the repo's own reverse side; ROADMAP:298 and :308 stay permanent leaks. Kept as the fallback if the implementing audit finds a handle that cannot be kept alive.

### 3. Plain `~Wrapper()` plus `GC.KeepAlive(this)` per call

Same audit as option 1, but the whole wrapper is finalizable (larger gen1 promotion, inferred from docs) and the keep-alive discipline is hand-written at every call site. Rejected.

### 4. `#if DEBUG` reporting finalizer

Implementable because the generated `.cs` compiles in the consumer (`contentFiles`, `PackNugetTask.kt:105`), so the consumer's configuration applies. Frees nothing, and needs option 1's audit the day it frees. Rejected.

## Decision

The forward contract is: **dispose a wrapper to release its Kotlin object promptly; a wrapper dropped without disposing is released when the .NET GC finalizes its handle, eventually, on the finalizer thread, and not at process exit.**

Shape (generated, sketch):

```csharp
internal sealed class NugetKotlinHandle : SafeHandle
{
    internal NugetKotlinHandle(IntPtr h) : base(IntPtr.Zero, ownsHandle: true) => SetHandle(h);
    public override bool IsInvalid => handle == IntPtr.Zero;
    protected override bool ReleaseHandle() { NugetHandleNative.Release(handle); return true; }
}

public class Cat : IDisposable, INugetHandle
{
    internal NugetKotlinHandle _handle;
    public string Name => NugetMarshal.FromHandle<string>(Native_Name(_handle));
    [DllImport(...)] private static extern IntPtr Native_Name(NugetKotlinHandle self);
    public void Dispose() => _handle.Dispose();
}
```

Mechanism claims and their status:

- Finalizer-thread release into Kotlin works: **verified** (ADR-085 spike, macOS only).
- The marshaller keeps a `SafeHandle` parameter alive for the call: **inferred** (SafeHandle docs). The GC-stress test below is the gate.
- A `SafeHandle` subclass marshals as a `DllImport` return and parameter in the generated declaration shape (`[DllImport("<lib>", CallingConvention = CallingConvention.Cdecl, EntryPoint = "...")]`), under the JIT and NativeAOT: **verified** by spike (2026-10-02, SDK 10.0.301, ILCompiler 10.0.9, win-x64, kernel32 `CreateEventW` return and `SetEvent`/`WaitForSingleObject` parameters; published `a.exe` printed `aot=True`, `invalid=False set=1 wait=0`, `released after Dispose=1`, `released after GC=1001`, no AOT or trim warnings). The generated code uses `DllImport` only (3,696 sites, no `LibraryImport`), carries no `DisableRuntimeMarshalling`, and already relies on runtime marshalling (485 `LPUTF8Str`, 262 `I1` slots), verified by grep of the TestLibrary `Interop.cs`. Under `[assembly: DisableRuntimeMarshalling]` each SafeHandle slot reports `CA1420` (verified), so a consumer assembly with that attribute is unsupported, as it already is for the string and bool slots (inferred). Not spiked on osx-arm64 or linux NativeAOT (inferred to match).
- Suspend routes need the handle only for the synchronous call: **verified** for the legacy method route (`exports/SuspendFunctionExports.kt:225-254` dereferences receiver and arguments before `launchForCSharp`; the generated completion closure does not capture `this`). **Inferred** for the CIR plan route and the Flow/StateFlow routes; the implementation must confirm each.
- No generated wrapper is built over a handle it does not own: **partially verified** (`NugetMarshal.Wrap` passes `INugetHandle` handles as borrowed, `cir/CirMarshalRenderer.kt:343-353`; callback payloads are wrapper-owned per ADR-036's 2026-09-11 amendment). Not every construction site was audited. **Nobody has verified this in full; if one wrapper is built over a borrowed handle, the finalizer double-frees a `StableRef`.** The audit is the implementation's first step.
- Raw `IntPtr` readers (`INugetHandle.Handle`, `cir/CirMarshalRenderer.kt:387-390`; `Native_Equals(_handle, other._handle)`) are kept alive by the code that consumes the pointer: `GC.KeepAlive(wrapper)` after the native call that uses it (for `NugetMarshal.Wrap`, after the caller's `Add`/`Put`, not inside `Wrap`). Unprotected, these are a silent use-after-free under GC pressure.
- **The suspend scope is the in-flight hazard.** `_scopeHandle`'s release is `NugetScopeNative.Cancel` then `Dispose` (`cir/CirClassRenderer.kt:771-781`, verified), and the generated completion closure does not capture the wrapper (verified in generated `BoardingDesk.SettleAsync`). Under a GC-released scope, `await new BoardingDesk().SettleAsync(5)` could have its coroutine cancelled mid-flight. The suspend, Flow and StateFlow completion closures therefore capture the wrapper until completion (decision; not built), so a finalizer only runs with nothing in flight.

## Consequences

- Gate decisions (2026-10-02): adopt SafeHandle; one `NugetKotlinHandle` per package with small subclasses for kinds whose release is not `NugetHandles.release` (scope, subscription token); `INugetHandle.Handle` stays `IntPtr` with the keep-alive at the users of the pointer; in-flight `suspend`/Flow/StateFlow calls keep the wrapper alive until completion.
- Behaviour change when the implementing item lands: an undisposed wrapper's Kotlin object becomes collectible. No API signature changes on public members.
- ROADMAP:298 closes with no extra code and `docs/backlog/callback-payload-wrapper-no-finalizer.md` is deleted. ROADMAP:308 closes only for its wrapper-typed half; its ADR-123 collection-element half (a raw container handle and per-element boxes no wrapper owns) stays open under either contract.
- ADR-003's finalizer line and ADR-121's "No finalizer" sentence get an amendment pointing here.
- Cost: one `SafeHandle` allocation and finalization registration per wrapper (not measured).
- Tests: a `LiveHandles` row for an undisposed wrapper, one for an undisposed callback payload, one for the abandoned wrapper-typed Flow item, an `IntegrationTests` GC-stress test calling members on short-lived wrappers while another thread runs `GC.Collect()`, and one awaiting `new BoardingDesk().SettleAsync(5)` with no local under the same pressure.
- Deferred: shutdown release (finalizers do not run at process exit on .NET Core and later, inferred); a per-wrapper allocation benchmark.
