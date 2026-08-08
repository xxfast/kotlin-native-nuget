# ADR-085: Kotlin-implemented C# interfaces passed back to C#: bridge class, Kotlin-minted slots, SafeHandle lifetime, Kotlin-side identity

## Status

Accepted

## Context

ADR-070 generates a pure Kotlin `interface` per admissible bound C# interface (`IFeedable` from
`TestDependency/Menagerie.cs`) and deliberately kept it pure: no handle member, so a Kotlin class
can implement it. Its Decision 4 lowering, `Any.nugetHandle(interfaceName)`, unwraps any
`NugetHandleOwner` wrapper and `error(...)`s for everything else
(`NugetGenerateBindingsTask.kt:4000-4005`). That `error` branch is the single insertion point this
ADR fills: a plain Kotlin `class Goat : IFeedable` passed at an interface-typed position of the
bound C# API (`Sanctuary.Introduce(IFeedable)`, `Sanctuary.Featured = ...`) must cross as a real
managed `IFeedable`, be storable by C#, and dispatch member calls back into the Kotlin object.

This is the exact inverse of ADR-084 (C# implementing a Kotlin interface). ADR-084 shipped and
corrected four mechanisms this ADR mirrors: flat per-member fnPtr+ctx slots, a factory at the
crossing, transfer-handle ownership (its `ConditionalWeakTable` reuse design was falsified by a
strong-reference cycle; one bridge per crossing shipped instead), and a token probe for round-trip
identity. The reverse-specific novelties are:

1. **Kotlin mints function pointers.** Today the reverse pipeline only *consumes*
   C#-registered `[UnmanagedCallersOnly]` thunks; no generated binding has ever passed a Kotlin
   `staticCFunction` to C#. New emission shape in `NugetGenerateBindingsTask`.
2. **C# calls into consumer Kotlin.** Today reverse-direction C# code is only *called by* Kotlin;
   the generated `IFeedableBridge` is the first C# shim code that invokes Kotlin-supplied pointers.
3. **Lifetime is inverted.** C# (the .NET GC) owns the bridge; when it collects the bridge, a
   finalization path must release the Kotlin `StableRef`. The mirror of ADR-084's `createCleaner`
   spike, and the one mechanism nothing in the repo proved. Spiked below.

### Spike (verified by execution, 2026-08-08)

Scratch Kotlin/Native 2.4.10 macosArm64 `sharedLib` + net10.0 console app (outside the repo).
Kotlin side: `spike_create` mints `StableRef.create(Impl())`; `spike_member_ptr` returns a
`staticCFunction { ctx, amount -> ctx!!.asStableRef<Impl>().get().feed(amount) }` as
`COpaquePointer`; `spike_release(handle)` disposes the StableRef and counts. C# side: a `Bridge`
class holding the handle plus a `delegate* unmanaged<IntPtr, int, int>`, invoking it from the main
thread, a thread-pool thread, 4 concurrent raw threads, and its own finalizer, which then calls
`spike_release`. Output:

```
[main] thread=1 feed(21)=42
[pool] thread=4 pool=True feed(2)=4
[threads] 4 concurrent raw threads ok=4
[finalizer] thread=2 isFinalizer=True feed(100)=200
[finalizer] released, count=1
[finalizer] thread=2 isFinalizer=True feed(100)=200
[finalizer] released, count=2
[main] final released count=2
```

So, **verified**:

- A Kotlin `staticCFunction` with a `StableRef` ctx dispatches a member on the referenced Kotlin
  object when invoked from arbitrary .NET threads: main, pool, raw, and the finalizer thread.
  Kotlin/Native 2.4.10's MM attaches the incoming thread transparently; no init call needed.
- Concurrent entry from 4 simultaneous foreign threads is safe (all 4 calls returned correct
  results and the `AtomicInt` state was consistent).
- A .NET finalizer P/Invoking a Kotlin export that disposes a `StableRef` fires reliably after
  `GC.Collect()` + `GC.WaitForPendingFinalizers()`, on the dedicated finalizer thread (thread 2),
  and calling *into* the Kotlin object from that same finalizer also works.

**Inferred**, not covered by the spike:

- .NET (Core and later) does not run finalizers at process exit, so a bridge still referenced at
  shutdown never releases its StableRef. Documented .NET behaviour
  (https://learn.microsoft.com/dotnet/csharp/programming-guide/classes-and-structs/finalizers);
  acceptable because the process is dying anyway, same posture as ADR-084's "GC-timed, never
  prompt" release.
- `SafeHandle.ReleaseHandle` (critical finalization) is at least as reliable as the plain
  finalizer the spike used. Inferred from .NET docs; the spike proved the plain-finalizer path,
  which is the weaker guarantee, so SafeHandle can only improve it.

## Alternatives Considered

### 1. C#-side bridge class + Kotlin-minted slot pointers + factory thunk (chosen)

`NugetGenerateShimsTask` emits, per admissible bound interface, a `IFeedableBridge : IFeedable`
holding one `delegate* unmanaged[Cdecl]` per member plus one ctx (`IntPtr` of a Kotlin
`StableRef<IFeedable>`), and a `[UnmanagedCallersOnly]` factory thunk in the existing ADR-054
registration slot table. `NugetGenerateBindingsTask` emits one `staticCFunction` per member and a
`nugetHandle()` fallback that mints `StableRef.create(impl)`, calls the factory, and hands the
returned `GCHandle` IntPtr down the **existing, unchanged** interface-parameter path.

Pros: exact mirror of ADR-084 Alternative 1, whose per-slot mechanism is shipped and green; the
existing thunks for `Introduce`/`Featured` are reused unchanged; one factory per interface
regardless of call-site count. Cons: 2N+1-ish factory arity; two crossings on first pass.

### 2. Fixed per-member Kotlin `@CName` dispatch exports instead of minted pointers

`IFeedableBridge.Describe()` P/Invokes a fixed export `nuget_ifeedable_describe(ctx)`. Avoids the
new "Kotlin mints pointers" emission shape entirely; the C ABI is identical.

Pros: fewer moving parts at the crossing (factory takes just ctx); symbols visible to `nm`.
Cons: grows the ADR-055 export contract by one symbol per interface member (the slot-table
design was chosen in ADR-049 precisely to keep the DllImport surface flat); diverges from
ADR-084's proven shape so the two directions stop being mirrors; and the shims side would need
per-member DllImports where today it DllImports only registration entries. Rejected: mechanism
symmetry with ADR-084 is worth more than the marginally simpler factory, and the spike proved the
minted-pointer path outright.

### 3. Reuse ADR-084's forward factory exports

Not possible: those exist only in the forward pipeline (KSP over Kotlin source). The reverse
pipeline generates from `reverse-ir.json` and shares no generated code with it. Rejected on
architecture, not preference.

### Lifetime sub-decision

- **(a) C#-side `SafeHandle` owning the ctx StableRef (chosen).** The bridge holds the ctx in a
  `KotlinRefHandle : SafeHandle` whose `ReleaseHandle` P/Invokes a new Kotlin export
  `nuget_kotlin_release(ctx)` that disposes the StableRef. When .NET collects the bridge, critical
  finalization releases the Kotlin object. Plain-finalizer variant **verified by the spike**;
  SafeHandle upgrade **inferred** (stronger .NET guarantee, same call).
- **(b) Kotlin-side cleaner on the implementing object freeing the bridge GCHandle.** Falsified
  by construction, the mirror of ADR-084's falsified design: the GCHandle strong-roots the bridge,
  the bridge's ctx StableRef strong-roots the Kotlin impl, and the impl's cleaner is what would
  free the GCHandle. A cycle no GC on either side can break. Rejected.
- **(c) Leak.** Rejected; kept only as the staged plan's first-commit interim state.

The factory's returned `GCHandle` is therefore a **transfer handle**, exactly ADR-084's corrected
ownership: the Kotlin call site frees it (via the already-registered ADR-051 GCHandle-free thunk)
after the native call returns. If C# stored the bridge, an ordinary managed reference keeps it
alive; if not, the .NET GC collects it and the SafeHandle releases the StableRef. Consequence,
same as ADR-084 as shipped: **one bridge per crossing**, no reuse table in v1.

### Identity sub-decision (what the reverse promises)

ADR-084 settled for one-side identity: the side that *implemented* the object gets `Assert.Same`.
Mirrored here: **Kotlin-side identity is promised, C#-side is not.**

- A `Goat` stored by C# and read back (`sanctuary.featured`) must resolve to the original Kotlin
  object, `assertSame(goat, sanctuary.featured)`. Mechanism: a probe thunk
  `IFeedableKotlinToken(gcHandle) -> IntPtr` returning the bridge's ctx pointer when the target is a
  generated bridge (marker interface `INugetKotlinBridge { IntPtr NugetToken { get; } }`), else
  `IntPtr.Zero`. Kotlin's interface-return unwrap probes first; on a hit it frees the fresh
  return GCHandle and returns `token.asStableRef<Any>().get()` cast to the interface. The ctx
  StableRef is still owned by the live bridge, so the resolved reference is valid. **Corrected as
  shipped, verified**: the probe is not a shared runtime-registered thunk. It is a **per-interface**
  registered slot, `bridgeTokenFn` beside `createBridgeFn` in `IFeedableBindings`, registered
  through `nuget_test_menagerie_i_feedable_register` alongside the interface's own member slots
  (`test-library/build/nuget-interop/kotlin/nativeMain/test/menagerie/IFeedableBindings.kt`,
  `test-library/build/nuget-interop/csharp/IFeedableRegistration.cs`). This keeps the shared
  `<runtime>` registration's `slotCount`/contract hash untouched; only each admissible interface's
  own contract grows by two slots (factory + token).
- C#-side `ReferenceEquals` across two crossings of the same `Goat` is **not** promised (one
  bridge per crossing). `IFeedable`-typed equality on the C# side observes distinct bridge
  objects, same documented divergence ADR-084 shipped with.

## Decision

Adopt Alternative 1 with lifetime (a) and Kotlin-side-only identity.

### Consumer Kotlin (end state, the outer-loop test's shape)

```kotlin
class Goat : IFeedable {
  var meals = 0; private set
  override fun describe(): String = "a goat"
  override val legs: Int get() = 4
  override fun feed(food: String) { meals++ }
  override var nickname: String? = null
}

val sanctuary = Sanctuary()
val goat = Goat()
assertEquals("introduced a goat with 4 legs", sanctuary.introduce(goat))  // dispatch into Goat
assertEquals(1, goat.meals)                     // if Introduce fed it; C# sees the real object
sanctuary.featured = goat                       // C# stores it
assertSame(goat, sanctuary.featured)            // identity probe: original object, not a handle
```

Inner-loop generator tests assert: the emitted `staticCFunction` slot block per `IFeedable`
member, the `nugetHandle()` fallback branch replacing `error(...)`, the emitted
`IFeedableBridge`/factory thunk C#, and a named skip for an interface with an out-of-scope member.

### Generated Kotlin (`NugetGenerateBindingsTask`)

```kotlin
// Per interface: one staticCFunction per admissible member (no captures; ctx carries the object).
private val feedableDescribeSlot =
  staticCFunction { ctx: COpaquePointer? -> // returns CPointer<ByteVar> (UTF-8, nativeHeap)
    nugetKotlinString(ctx!!.asStableRef<IFeedable>().get().describe())
  }
private val feedableLegsGetSlot =
  staticCFunction { ctx: COpaquePointer? -> ctx!!.asStableRef<IFeedable>().get().legs }
private val feedableFeedSlot =
  staticCFunction { ctx: COpaquePointer?, food: COpaquePointer? ->
    ctx!!.asStableRef<IFeedable>().get().feed(food!!.reinterpret<ByteVar>().toKString())
  }
// nickname: one getter slot (nullable string out), one setter slot (nullable string in).

// Shared exports:
@CName("nuget_kotlin_release")
fun exportKotlinRelease(ctx: COpaquePointer) { ctx.asStableRef<Any>().dispose() }
@CName("nuget_kotlin_string_free")
fun exportKotlinStringFree(ptr: COpaquePointer) { nativeHeap.free(ptr) }

// ADR-070 Decision 4 fallback (replaces the error(...) branch):
internal fun Any.nugetHandle(interfaceName: String): NugetObjectHandle =
  (this as? NugetHandleOwner)?.handle ?: nugetMintBridge(this, interfaceName)
```

`nugetMintBridge` dispatches on the runtime interface (`is IFeedable -> ...` per bound interface
admissible at a parameter position), mints `StableRef.create(impl)`, and invokes the registered
factory thunk. The returned GCHandle is wrapped as an **owned transfer handle**: the call site's
existing prelude/cleanup slots free it after the native call, mirroring ADR-084 commit 3's
`HandleOf(value, out bool owned)`. A value implementing no bridgeable bound interface keeps the
current `error(...)` message.

Slot exception policy: this ADR proposed wrapping each slot body in `try/catch (t: Throwable)` to
fast-fail the process with the member name, mirroring the deliberate fast-fail of the C# thunks
(`NugetGenerateShimsTask.kt` line ~69 comment). **Corrected as shipped**: v1 slot bodies carry no
`try`/`catch` at all (`test-library/build/nuget-interop/kotlin/nativeMain/test/menagerie/IFeedableBindings.kt`'s
`iFeedableDescribe...Slot`/`iFeedableFeed...Slot`/etc. are one-line expression bodies with no
wrapper). An exception thrown inside a v1 member therefore terminates through Kotlin/Native's
ordinary unhandled-callback path, without naming the offending member the way the proposed wrapper
would have. v1 members must not throw; the named per-slot fast-fail wrapper and exception
*propagation* are both deferred (see ROADMAP Phase 13).

### Generated C# (`NugetGenerateShimsTask`)

```csharp
internal interface INugetKotlinBridge { IntPtr NugetToken { get; } }

internal sealed unsafe class IFeedableBridge : Test.Menagerie.IFeedable, INugetKotlinBridge
{
    private readonly KotlinRefHandle _ctx;                       // SafeHandle: ReleaseHandle
    private readonly delegate* unmanaged[Cdecl]<IntPtr, IntPtr> _describe;   //   P/Invokes
    private readonly delegate* unmanaged[Cdecl]<IntPtr, int> _legsGet;       //   nuget_kotlin_release
    private readonly delegate* unmanaged[Cdecl]<IntPtr, IntPtr, void> _feed;
    // + nicknameGet / nicknameSet
    public IntPtr NugetToken => _ctx.DangerousGetHandle();       // identity probe token

    public string Describe()
    {
        IntPtr p = _describe(_ctx.DangerousGetHandle());
        try { return Marshal.PtrToStringUTF8(p)!; } finally { Native.nuget_kotlin_string_free(p); }
    }
    public void Feed(string food)
    {
        IntPtr p = Marshal.StringToCoTaskMemUTF8(food);          // allocator frees its own buffer
        try { _feed(_ctx.DangerousGetHandle(), p); } finally { Marshal.FreeCoTaskMem(p); }
    }
    // ...
}

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
internal static IntPtr CreateIFeedableBridge(IntPtr describe, IntPtr legsGet, IntPtr feed,
    IntPtr nicknameGet, IntPtr nicknameSet, IntPtr ctx) =>
    GCHandle.ToIntPtr(GCHandle.Alloc(new IFeedableBridge(...)));  // transfer handle, freed by Kotlin

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
internal static IntPtr IFeedableKotlinToken(IntPtr h) =>          // per-interface slot, beside the
    GCHandle.FromIntPtr(h).Target is INugetKotlinBridge b ? b.NugetToken : IntPtr.Zero;  // factory
```

Both thunks ride the existing ADR-054 registration slot table and contract hash of **this
interface's own registration** (`IFeedableBindings`/`IFeedableRegistration`), not the shared
`<runtime>` one; slot order comes from one planner shared by the bindings and shims tasks (the reverse mirror of
`ForwardInterfaceBridgePlanner`; slot-order drift is the classic silent-ABI bug and the ADR-055
hash does not cover per-slot meaning).

### Wire types per slot (reuse of the ADR-049/051 reverse vocabulary)

| Member shape | Slot signature (Kotlin view) | Notes |
|---|---|---|
| `string` return | `(ctx) -> CPointer<ByteVar>` | Kotlin allocates UTF-8 on `nativeHeap`; C# reads `PtrToStringUTF8` then calls `nuget_kotlin_string_free`. **Inferred**: the one new marshalling shape (inverse of `StringToCoTaskMemUTF8` → `toKString`+free, which is verified in-repo for the other direction) |
| `string` param | `(ctx, CPointer<ByteVar>) -> ...` | C# allocates CoTaskMem UTF-8, frees it itself after the synchronous slot call returns; Kotlin copies via `toKString`. **Inferred** (composition of shipped pieces) |
| `string?` either way | nullable pointer, `IntPtr.Zero` ⇒ `null` | mirrors ADR-053's shipped null-pointer ride |
| primitive / `bool` / `char` / enum | pass-through / byte / ushort / int ordinal | identical to the shipped thunk ABI table (`csAbiType`, `NugetGenerateShimsTask.kt:229`) |
| bound-object param/return | GCHandle IntPtr (proposed) | **Corrected as shipped**: not in the v1 slot vocabulary. A member with a bound-object-handle parameter or return does not plan a slot and falls to `skipped_kotlin_bridge`; see Scope below |

### Detection rule and diagnostics

A bound interface plans a bridge when it is admissible under ADR-070 **and** every member fits the
slot vocabulary. Out-of-scope members produce an ADR-064-style named diagnostic, never silence:
`skipped_kotlin_bridge(interface, member, reason)` in the reverse diagnostics stream, and
`nugetHandle()` keeps the `error(...)` for that interface with the existing message. C# default
interface methods are already excluded from the generated Kotlin interface
(`skipped_default_interface_method`, ADR-043/070), so a Kotlin implementer has nothing to
override; the recommendation is **no slot for a DIM, the C# bridge simply inherits the default
body** (C# callers through the interface get the DIM). **Inferred**, contingent on ADR-070's
reader skipping the member but binding the rest of the interface; if it drops the whole interface,
DIM-bearing interfaces stay out of scope with the existing diagnostic.

### Scope

**v1:** methods of arity 0-2 returning `Unit`/primitive/`Boolean`/enum/`String`/`String?`
(ADR-084's vocabulary); properties with getter **and setter** slots over the same types.
Divergence from ADR-084 (which deferred `var`): `IFeedable.Nickname { get; set; }` is on the
flagship fixture, so setter slots are in v1 or the contract test cannot run. Parameter types:
primitives, `Boolean`, enum, `String`, `String?`. **Narrowed as shipped**: this ADR proposed
bound-object handles as an admissible parameter/return type too; the shipped slot vocabulary does
not include them (see the wire-types table above), so a member with a bound-object-handle
parameter or return is out of scope and named-skipped, not bridged.

**Deferred (candidate ROADMAP items):**
- Object/collection-typed slot returns and parameters (including bound-object handles, narrowed
  out of v1 above).
- `Task`-returning members (compose with Phase 12 async work).
- Exception propagation from a Kotlin member into C# (v1 fast-fails).
- Bridge reuse per Kotlin object (Kotlin-side weak table keyed by ctx, restoring C#-side
  `ReferenceEquals` across crossings), mirror of ADR-084's dropped `ConditionalWeakTable`.
- A Kotlin class implementing multiple bound interfaces (v1: first match in the `nugetMintBridge`
  dispatch wins; emit a diagnostic on ambiguity).
- Kotlin objects at interface-typed positions of *forward*-direction APIs (out of scope, forward
  pipeline).
- Interface inheritance (`ITagged : IFeedable`): a Kotlin `ITagged` implementer needs the base's
  slots plus its own; v1 plans derived interfaces only if all inherited members are re-planned in
  the derived slot table (single flattened factory). **As shipped**: no derived-interface
  flattening exists yet, so `ITagged` is not Kotlin-implementable in v1 at all; it is a named
  `skipped_kotlin_bridge` diagnostic, never a silent drop.

## Consequences

- First Kotlin-minted function pointers in the reverse pipeline: `NugetGenerateBindingsTask`
  gains a slot-emission shape, and the shared planner spans both generator tasks.
- Two new shared Kotlin exports (`nuget_kotlin_release`, `nuget_kotlin_string_free`, both
  unregistered ordinary exports, not ADR-054 slots) extend the surface every bridge calls into.
  **Corrected as shipped**: the token probe is not a shared registered thunk; each admissible
  interface's own registration (e.g. `IFeedableRegistration`) gains a factory thunk **and** a
  token thunk (`IFeedableKotlinToken`) as two more slots in that interface's own ADR-054 table,
  leaving the shared `<runtime>` registration's slot count and contract hash untouched.
- `nugetHandle()` stops erroring for implementations of bridgeable interfaces; the error text
  remains for non-bridgeable ones (documented boundary, not an API promise).
- Release is GC-timed on the .NET side, never prompt, and not guaranteed at process exit
  (**inferred**, .NET documented behaviour). Deterministic release tests must drop the C#
  reference inside a helper frame, then `GC.Collect()` + `WaitForPendingFinalizers()`, the .NET
  mirror of ADR-084's live-stack-frame lesson.
- The staged plan mirrors ADR-084's: commit 1 factory+slots+fallback (documented interim leak),
  commit 2 SafeHandle release + deterministic release test, commit 3 identity probe.

## Mechanism claims ledger

Verified (by the 2026-08-08 spike, output quoted above, Kotlin/Native 2.4.10 macosArm64 +
.NET 10 host):
1. `staticCFunction` + `StableRef` ctx dispatch from main/pool/raw/finalizer .NET threads.
2. Concurrent entry from 4 simultaneous foreign threads.
3. A .NET finalizer P/Invoking a Kotlin export that disposes a `StableRef`, firing after
   `GC.Collect()`+`WaitForPendingFinalizers()` on the finalizer thread.

Verified (by repo code): slot-table registration and `[UnmanagedCallersOnly]` thunk ABI
(`NugetGenerateShimsTask.kt`); `nugetHandle()` insertion point
(`NugetGenerateBindingsTask.kt:4000`); CoTaskMemUTF8→`toKString` string wire (both tasks).

Verified (by the shipped implementation and `scripts/verify.sh --plugin`, 936 passed / 0 failed,
including the 8 `KotlinGoat*` fixtures in `IntegrationTests/MenagerieRoundTripTests.cs`):
- The `nativeHeap` UTF-8 string-return wire, end to end (`Describe()`, `Rename`). **Corrected from
  the Decision sketch**: an earlier shape considered for this wire, `value.cstr.getPointer(nativeHeap)`,
  does not compile (`CPointer<ByteVar>.getPointer` takes an `AutofreeScope`, not a `NativePlacement`
  like `nativeHeap`). The shipped `nugetKotlinString` instead does `nativeHeap.allocArray(bytes.size + 1)`,
  copies each byte, and writes a trailing NUL
  (`test-library/build/nuget-interop/kotlin/nativeMain/io/github/xxfast/kotlin/native/nuget/internal/NugetRuntime.kt`),
  freed on the C# side via `nuget_kotlin_string_free`.
- `SafeHandle.ReleaseHandle` firing in-process within a few forced `GC.Collect()`/
  `WaitForPendingFinalizers()` rounds (`KotlinGoatBridge_IsReleased_AfterTheTransferHandleIsFreed`),
  and a live bridge surviving a forced collection and still resolving
  (`LiveKotlinGoatBridge_SurvivesACollection_AndStillResolves`).
- Freeing the factory's transfer GCHandle from Kotlin immediately after the native call returns,
  while C# retains its own managed reference if it stored the bridge. Implemented as a
  `nugetTransferScope { ... }` receiver: `handleOf`/`handleOfOrNull` mint and collect the transfer
  handles for the call, freed in a `finally`, nesting with `memScoped` exactly like the string
  prelude (`NugetRuntime.kt`'s `NugetTransferScope`; every `Sanctuary` call taking or setting an
  `IFeedable` wraps its invocation in it).

Inferred (not executed): SafeHandle ≥ plain finalizer reliability (the spike proved the weaker
plain-finalizer guarantee; the shipped code uses `SafeHandle`, and its release fires reliably in
the test suite, but nothing isolates the *SafeHandle-specific* guarantee from the finalizer one);
no finalizers at process exit; DIM members surviving as inherited default bodies on the bridge
(contingent on ADR-070's member-level vs interface-level skip; no DIM-bearing bound interface
fixture exists to exercise this).

**Corrected, not merely inferred**: `kotlin.concurrent.AtomicInt` has no `incrementAndGet`/
`addAndFetch`. The release counter (`nugetKotlinReleaseCount`/`nuget_kotlin_release`) increments
with the same `while (true) { ... compareAndSet(current, current + 1) ... }` loop `NugetObjectHandle.free()`
and `NugetRegistry.record` already use elsewhere in the runtime, not a fetch-and-add primitive that
does not exist on this type.
