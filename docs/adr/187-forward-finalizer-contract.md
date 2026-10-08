# ADR-187: Forward, an undisposed generated wrapper releases its Kotlin handle through a `SafeHandle` when the .NET GC collects it

2026-10-03 amendment: the acquired holder returned by a suspend `Flow` call also owns its Flow
handle through `SafeHandle`; explicit `Dispose()` is prompt release and GC finalization is fallback
([ADR-194](194-suspend-returning-flow.md)). The feature adds no cross-platform finalizer evidence.

2026-10-03 amendment: a boxed element is owned by a `NugetKotlinHandle` before its wrapper factory
runs, and the wrapper adopts that same handle, so a factory that throws before constructing a
wrapper no longer leaks the box and a wrapper saved from a construct-then-throw factory still
releases once ([ADR-120](120-live-stableref-counter-and-leak-harness.md), Amendment 3). Verified in
the full verify; not run under NativeAOT at the time (see the 2026-10-07 amendment).

## Status
Accepted. Contract decided at the human gate on 2026-10-02; implemented on the same day. Corrected against the implementation and a spike when it moved to Accepted: the sketch's release call, the `Dispose()` shape, the Flow and StateFlow capture points, and the subscription token decision differ from the Proposed text.

## Context

ADR-003 listed "IDisposable with a destructor/finalizer as safety net"; the generator never emitted one, and ADR-121 described that shipped state ("No finalizer, no .NET GC in the path"). The 1.0.0 memory topic has to state one contract, and two leak items waited on it: the callback-payload wrapper and the abandoned `Flow` item after `DisposeAsync`.

Verified by reading before the change: no forward wrapper had a finalizer, `SafeHandle`, `GC.KeepAlive` or `SuppressFinalize`; a class wrapper was a bare `internal IntPtr _handle` released by `Interlocked.Exchange` plus `Native_Dispose` (`cir/CirClassRenderer.kt`). Handle-owning kinds: class, sealed base and arm, interface container, `KotlinFunc`/`KotlinAction`/`KotlinSuspend*` (`cir/CirFunctionRenderer.kt`), `KotlinStateFlow<T>` (`cir/CirFlowRenderer.kt`), `NugetSubscription` (`cir/CirCallbackRenderer.kt`), and the suspend scope handle.

Verified by ADR-085's spike (`docs/adr/085-kotlin-implemented-csharp-interfaces.md`, K/N 2.4.10 macosArm64, net10.0): a .NET finalizer on the finalizer thread can P/Invoke a Kotlin export that disposes a `StableRef`, with no thread registration. The reverse `KotlinRefHandle : SafeHandle` (`NugetGenerateShimsTask.kt`) already does this in shipped code. Not verified on mingwX64 or linux (inferred to match).

## Alternatives Considered

### 1. `SafeHandle`-owned handle on every wrapper kind (chosen)

`_handle` becomes an `internal class NugetKotlinHandle : SafeHandle` whose `ReleaseHandle` releases the Kotlin object; DllImports that take a wrapper's handle take the `SafeHandle`, so the marshaller keeps it alive for the call. Matches .NET guidance (wrap native resources in `SafeHandle`, https://learn.microsoft.com/dotnet/standard/garbage-collection/implementing-dispose), Kotlin's own Swift/ObjC export (ARC releases the Kotlin object), and this repo's reverse side (ADR-085, ADR-158). Only the small handle object is finalizable, not the wrapper. Cons: a lifetime audit of every raw-`IntPtr` reader was mandatory.

### 2. No finalizer, documented leak (pin and close)

Zero code. `NugetMarshal.LiveHandles` diagnoses an undisposed wrapper. Cons: contradicts .NET guidance and the repo's own reverse side; both leak items stay permanent. It was the fallback if the ownership audit found a handle that cannot be kept alive; the audit did not trigger it.

### 3. Plain `~Wrapper()` plus `GC.KeepAlive(this)` per call

Same audit as option 1, but the whole wrapper is finalizable (larger gen1 promotion, inferred from docs) and the keep-alive discipline is hand-written at every call site. Rejected.

### 4. `#if DEBUG` reporting finalizer

Implementable because the generated `.cs` compiles in the consumer (`contentFiles`, `PackNugetTask.kt`), so the consumer's configuration applies. Frees nothing, and needs option 1's audit the day it frees. Rejected.

## Decision

The forward contract is: **dispose a wrapper to release its Kotlin object promptly; a wrapper dropped without disposing is released when the .NET GC finalizes its handle, eventually, on the finalizer thread, and not at process exit.** The one exception is a stored-callback subscription token (see below). No public member signature changes.

Shape (generated, lifted from the verified TestLibrary `Interop.cs`):

```csharp
internal class NugetKotlinHandle : SafeHandle
{
    internal static readonly NugetKotlinHandle Null = new NugetKotlinHandle(IntPtr.Zero);

    internal NugetKotlinHandle(IntPtr handle) : base(IntPtr.Zero, ownsHandle: true) => SetHandle(handle);

    public override bool IsInvalid => handle == IntPtr.Zero;

    protected override bool ReleaseHandle()
    {
        NugetMarshal.Dispose(handle);   // nuget_dispose
        return true;
    }
}

public class Cat : IDisposable, INugetHandle
{
    internal NugetKotlinHandle _handle = NugetKotlinHandle.Null;
    IntPtr INugetHandle.Handle => _handle.DangerousGetHandle();

    [DllImport(...)] private static extern IntPtr Native_Get_lives(NugetKotlinHandle handle, out IntPtr error);

    public void Dispose()
    {
        NugetKotlinHandle handle = Interlocked.Exchange(ref _handle, NugetKotlinHandle.Null);
        if (handle.IsInvalid) return;
        handle.Dispose();
    }
}
```

`NugetKotlinHandle` is not sealed: the scope handle derives from it.

Mechanism claims and their status:

- **Release.** There is one shared release, `nuget_dispose`, called from `NugetKotlinHandle.ReleaseHandle` (there is no `NugetHandleNative`). It is correct for every kind because the per-type `*_dispose` exports and `nuget_dispose` are all `NugetHandles.release(handle)`. Verified by reading.
- **`Dispose()` shape.** `Dispose()` swaps the field for the shared sentinel `NugetKotlinHandle.Null` (handle zero, never closed) with `Interlocked.Exchange`, then releases the handle it took out. It is idempotent and thread-safe, a disposed wrapper's slot reads zero as before, and the disposed checks became `_handle.IsInvalid` with the same exception and message. A call made after `Dispose()` does **not** get `ObjectDisposedException` from the marshaller: it reads the sentinel, whose zero reaches the member's own disposed check. Verified by the generated output and by `DisposedWrappers_ThenFinalized_AreNotReleasedTwice`.
- **Finalizer-thread release into Kotlin works.** Verified (ADR-085 spike, macOS only), and again in this implementation by the leak rows below on macOS only. Not verified on mingwX64 or linux.
- **Keep-alive for the call.** A `SafeHandle` parameter is kept alive for the whole call when the argument expression is the only reference to the wrapper: **verified by spike** (2026-10-02, osx-arm64, SDK 10.0.300, .NET 10.0.8, JIT only, scratch console app against libSystem). The control, a finalizable holder passed as a raw `IntPtr`, was finalized about 1 ms into a 1 s call. `IntegrationTests/GcStressTests.cs` exercises it under a concurrent `GC.Collect()` loop. **Not verified under NativeAOT, mingwX64 or linux.**
- **Argument edge cases.** All verified by the same spike: a `null` SafeHandle argument throws `ArgumentNullException: SafeHandle cannot be null.`, so a nullable handle argument passes `x?._handle ?? NugetKotlinHandle.Null`; a zero-valued, non-closed SafeHandle passes through as zero and its `Dispose` does not run `ReleaseHandle`; a closed SafeHandle argument throws `ObjectDisposedException`; `DangerousAddRef` then `Dispose()` defers `ReleaseHandle` until `DangerousRelease()`.
- **Marshalling shape.** A `SafeHandle` subclass marshals as a `DllImport` return and parameter in the generated declaration shape under the JIT and NativeAOT: **verified** by an earlier spike (2026-10-02, SDK 10.0.301, ILCompiler 10.0.9, win-x64, kernel32 `CreateEventW` return and `SetEvent`/`WaitForSingleObject` parameters; published `a.exe` printed `aot=True`, `released after Dispose=1`, `released after GC=1001`, no AOT or trim warnings). The generated code uses `DllImport` only and carries no `DisableRuntimeMarshalling`. Under `[assembly: DisableRuntimeMarshalling]` each SafeHandle slot reports `CA1420` (verified), so a consumer assembly with that attribute is unsupported, as it already is for the string and bool slots (inferred).
- **Suspend routes dereference before launching.** Every launching route reads the receiver, scope and arguments on the caller's thread before the coroutine starts, so the handle itself is needed only for the synchronous call. Verified by reading (`exports/SuspendFunctionExports.kt`, `exports/FlowExports.kt`, the runtime's lambda and state-flow exports) and by the in-flight suspend and Flow rows in `GcStressTests`. There is no separate CIR-plan suspend emitter.
- **Ownership audit.** Every generated wrapper is the sole owner of its handle: each pointer that leaves Kotlin is a fresh `NugetHandles.retain`, no export returns a pointer it was passed, and borrowed reads (`NugetMarshal.Wrap`, `HandleOf`) never become wrappers and are never disposed. Verified by reading every construction emitter. The "no finalizer" fallback was not triggered. A dead emitter on the legacy lambda-parameter route (`CirClassTranslator.kt`, `new $outerRetKotlin(nativeHandle, out _)`) has no minting export and is unreachable; if the refusal that guards it is relaxed, it would wrap a non-`StableRef` pointer.
- **Raw `IntPtr` readers.** `INugetHandle.Handle` stays `IntPtr` (`_handle.DangerousGetHandle()`). `GC.KeepAlive(wrapper)` follows the native call that consumes the pointer at the seven sites that read it: the collection element loop, `CreateMap` key and value, boxed lambda arguments, type-parameter cleanup, interface cleanup on the plan and property routes, and the generic function route. Verified by reading and by `ShortLivedCollectionElements_CollectionParameterUnderGcPressure_NeverUseAFreedHandle`.
- **Handle slots that stay `IntPtr`** on purpose: the per-type `*_dispose` imports (still declared, no longer called from C#), the sealed `*_get_type` discriminator, subscription `remove*`, `routes__hold_object`, and the `nuget_*` box, container and scope helpers. `NugetJobCell` also still holds a raw job handle, outside this ADR's scope.

### Suspend scope

`NugetScopeHandle : NugetKotlinHandle`; its release is cancel then dispose. `DisposeWithoutCancel()` releases a scope that was drained, or that lost the creation race and was never published, without cancelling it. `Dispose()` swaps the scope field out and releases it before the object handle.

`DisposeAsync()` swaps both fields out first and the drain closure releases through those same two handle objects (`scopeHandle.DisposeWithoutCancel(); handle.Dispose();`). It uses no `DangerousAddRef`, so a finalizer cannot release the pair before the drain finishes because the closure captures them (verified by reading the generated `DrainAndDisposeAsync`).

### In-flight calls keep their wrapper's scope alive

Under a GC-released scope, `await new BoardingDesk().SettleAsync(5)` could have its coroutine cancelled mid-flight, so every route that outlives the call roots what it needs until completion:

- **Suspend:** an instance member's completion closure calls `GC.KeepAlive(this)`, so the wrapper, and with it the scope, is not finalized while the call is pending. Before the change the closure did not capture `this`, which held for every result kind except the awaited `StateFlow`.
- **Flow:** there is no completion closure. The Flow enumerator stores the collect delegate, which captures the wrapper (it reads `_handle` and `GetOrCreateScope()`); the enumerator is rooted by `NugetFlowCallbacks.Root()` until the terminal callback, so `await foreach (var x in cat.Ticks())` keeps the wrapper alive for the whole collection.
- **Held `MutableStateFlow` and awaited `StateFlow`:** the lambdas used to capture raw scope and flow pointers. They now capture the handle objects (the owned flow handle and the parent's scope handle), not the wrapper. This also changes behaviour, see Consequences.

Verified by reading and by `DroppedWrapper_InFlightSuspendCall_CompletesAndIsNotCancelled` and `DroppedWrapper_InFlightFlow_YieldsEveryItem` under a concurrent `GC.Collect()` loop (JIT, osx-arm64).

### Subscription token

Human gate decision, 2026-10-02, superseding the earlier "subscription token subclass" decision: **a discarded subscription keeps delivering.** `NugetSubscription`'s token has no finalizer release at all; only an explicit `Dispose()` unregisters it and frees the token. A finalizer that unregistered would have silently stopped `cat.AddListener(x => ...)` with the returned `IDisposable` discarded at the next GC, a silent behaviour change on a common C# pattern. Row 16i, `DiscardedSubscription_KeepsDeliveringAfterTheGc_AndKeepsItsToken`, pins both the delivery and that the token stays live after its owner is disposed (today's behaviour). The cost is that a discarded subscription's token `StableRef` is never freed.

## Consequences

- Gate decisions (2026-10-02): adopt SafeHandle; one shared `NugetKotlinHandle` per package with a scope subclass; `INugetHandle.Handle` stays `IntPtr` with the keep-alive at the users of the pointer; in-flight `suspend`/Flow/StateFlow calls keep what they need alive until completion; subscription tokens are not finalizer-released.
- Behaviour change: an undisposed wrapper's Kotlin object becomes collectible. A held or awaited `KotlinStateFlow` used after its own `Dispose()`, or collected after its parent was disposed, now throws `ObjectDisposedException` where it used to pass a released pointer to Kotlin (the second case was found by reading and never reproduced). `KotlinFunc`, `KotlinAction`, `KotlinSuspend*` and a non-suspending sealed arm's `Dispose()` are now atomic.
- The callback-payload leak closes with no extra code (rows 16a and 16b). The abandoned `Flow` item closes only for its wrapper-typed half (row 16c); its ADR-123 collection-element half (a raw container handle and per-element boxes no wrapper owns) stays open under either contract.
- ADR-003's finalizer line and ADR-121's "No finalizer" sentence carry an amendment pointing here.
- Cost: one `SafeHandle` allocation and finalization registration per wrapper (not measured).
- Tests: `LeakTests/LiveHandleTests.cs` rows 16 to 16k (undisposed class wrapper, callback payload on both routes, abandoned wrapper-typed Flow item, sealed arm, interface-typed return, `KotlinFunc`/`KotlinAction`, `KotlinSuspendFunc`, `KotlinStateFlow`, discarded subscription, a wrapper with a suspend scope, and no double release after dispose), and `IntegrationTests/GcStressTests.cs` (receivers, arguments, `Equals`, collection elements, an in-flight suspend call and an in-flight Flow on a dropped wrapper, under a concurrent `GC.Collect()` loop). Verified green in `scripts/verify.sh`: IntegrationTests 2954/2954, LeakTests 151/151.
- Known gaps: the keep-alive and finalizer release were exercised under the JIT on osx-arm64 only (NativeAOT: see the 2026-10-07 amendment); each `GcStressTests` hammer stops at a 2 s budget and reached about 5.6k, 12.8k and 17.3k calls for the receiver, argument and `Equals` tests and 83 for the collection-parameter test (one measurement), against a 100k design target.
- Deferred: shutdown release (finalizers do not run at process exit on .NET Core and later, inferred); a per-wrapper allocation benchmark; removing the now-unused per-type `*_dispose` imports and exports.

## Amendment 2026-10-07: finalizer rows under NativeAOT

The `SafeHandle` keep-alive and finalizer release now run under NativeAOT. `AotLeakTests/`
compiles the `LeakTests` sources, including rows 16 to 16k, and `AotIntegrationTests/` compiles
`IntegrationTests` including `GcStressTests`; both are published with `PublishAot` and run in
`scripts/verify.sh`, `scripts/verify-aot.ps1` and the CI `bridge` job. Verified on win-x64:
`AotLeakTests` 209/209 and `AotIntegrationTests` 3259 passed, 4 skipped, 0 failed. osx-arm64 and
linux-x64 are exercised by CI only (pending at writing). The suites root the test assembly, so
this verifies runtime behaviour, not trimming. This supersedes "the keep-alive and finalizer
release are exercised under the JIT on osx-arm64 only" in Consequences.
