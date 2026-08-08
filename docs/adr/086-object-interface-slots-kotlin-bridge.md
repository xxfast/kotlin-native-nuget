# ADR-086: Object- and interface-typed slots for a Kotlin-implemented C# interface

## Status

Proposed

## Context

ADR-085's v1 slot vocabulary for a Kotlin-implemented bound C# interface is arity 0-2 members
over `Unit`/primitive/`Boolean`/enum/`String`/`String?` (`RirBridging.kt:1034`,
`isKotlinBridgeSlotType`). A member whose parameter or return is a bound-object handle, another
bound interface, a struct, or a generic instantiation returns `false` there, so the whole
interface plans no bridge and every such member is a named `skipped_kotlin_bridge` diagnostic
(ROADMAP Phase 13, the "Object- and collection-typed slots" item). Bound-object-handle parameters
were in ADR-085's original sketch (`GCHandle IntPtr`) and were narrowed out of v1.

Constraints already settled by shipped code (all **verified in repo source**):

- The wire for a bound-object handle, a bound interface, and a closed generic instantiation is
  identical: an ADR-051 GCHandle `IntPtr`, Kotlin `COpaquePointer?` (`cfnType`,
  `NugetGenerateBindingsTask.kt:2349-2386`; ADR-070 Decision 1; ADR-072 Decision 1). `IntPtr.Zero`
  rides `null` (ADR-053).
- Every generated wrapper class owns its `NugetObjectHandle` through
  `createCleaner(this.handle) { it.free() }`, and `free()` dispatches the registered C#
  `freeGcHandleFn` thunk (`NugetGenerateBindingsTask.kt:960`, `:3223`, `:3976`). So a GCHandle
  whose ownership is transferred to Kotlin needs no new lifetime machinery: wrap it and the
  cleaner frees it.
- Interface-typed values already have both lowerings this ADR needs: outbound,
  `Any.nugetHandle(interfaceName)` (owner unwrap or `nugetMintBridge`, ADR-070 D4 + ADR-085);
  inbound, `nuget{Iface}Value(ptr)` (token probe, frees the fresh transfer handle on a hit,
  wraps `{Iface}Handle(ptr)` on a miss), emitted unconditionally for every bound interface
  (`kotlinInterfaceValueHelper`, `NugetGenerateBindingsTask.kt:4943-4958`).
- The reverse pipeline has **no** BCL collection mapping at any position today. `List<int>` in a
  bound API is `skipped_unbound_generic_instantiation`, deliberately (ADR-072; FEATURES.md row 88
  note `[8]`). There is no reverse `List`/`Map`/`Set` vocabulary for a slot to reuse.

The forward mirror offers no precedent to copy: ADR-084's `ForwardInterfaceBridgePlanner` also
defers object/collection slots (**verified**, `ForwardInterfaceBridgePlanner.kt:27`, `:81`). This
ADR is the first object-typed slot in either direction.

## Alternatives Considered

### 1. Bound-object and bound-interface slots on the existing GCHandle wire, receiving side owns; collections stay deferred (chosen)

Widen `isKotlinBridgeSlotType` to admit `RirObjectHandleType` (key in `boundHandleTypes`) and
`RirInterfaceType` (key in `boundInterfaceTypes`) at parameter, return, and property positions,
nullable included. Ownership rule, uniform: **the side that receives the handle owns it.**

- Parameter (C# → Kotlin): the C# bridge member mints `GCHandle.Alloc(arg)` and passes it as a
  transfer handle; the Kotlin slot wraps it (`Ferret(NugetObjectHandle(ptr))` or
  `nuget{Iface}Value(ptr)`) and the ordinary cleaner path frees it. C# never frees it.
- Return (Kotlin → C#): the slot returns a **fresh** transfer GCHandle; the C# bridge member
  resolves `.Target` and frees it immediately. Freshness comes from a `dupHandle` thunk for
  handle-backed values and from the ADR-085 factory for minted bridges (details in Decision).

Pros: reuses every shipped mechanism (wire, cleaner, token probe, transfer-handle posture from
ADR-084/085); one ownership sentence covers all four cells; Kotlin implementations may freely
*store* a received object (a borrowed handle could not support that). Cons: one new thunk shape
(dup), one allocation per object crossing.

### 2. Borrowed parameter handles (C# frees after the slot call returns)

Mirrors the string-parameter wire (C# allocates CoTaskMem, frees in `finally`). Rejected: unlike
a string, which the slot *copies* via `toKString`, an object handle is the identity of the value.
A Kotlin implementation that stores the received `Ferret` (the normal reason an interface method
takes one) would hold a wrapper over a freed GCHandle, a silent use-after-free the moment the
member call returns. Borrow semantics also require a non-owning `NugetObjectHandle` variant that
does not exist.

### 3. Borrowed return pointer, no dup thunk

The slot returns the wrapper's own stored GCHandle pointer; the C# bridge resolves `.Target`
without freeing. Rejected: once the slot returns, the Kotlin wrapper that owns that GCHandle is
unreachable, and its cleaner may free the handle concurrently with the C# read
(`GCHandle.FromIntPtr` on a freed handle is undefined). **Inferred**, not spiked: the race window
is real but narrow; the dup design removes it by construction, and if the race were somehow
impossible the dup is merely one redundant allocation, never wrong output. That asymmetry is why
the safe design wins without a spike.

### 4. Collections (`List`/`Map`/`Set`) in this ADR's v1

Rejected on sequencing, not difficulty: the reverse direction cannot marshal a BCL collection at
*any* position today (**verified**: ADR-072 diagnoses `List<int>` as
`skipped_unbound_generic_instantiation`; no `nuget_list_*`-style reverse helpers exist, the
forward `NugetMarshal`/`nuget_list_*` machinery is forward-pipeline-only). A collection-typed
slot would have to invent the entire reverse collection vocabulary as a side effect of an
interface feature. Collections stay a named skip until the Phase 10 "BCL collection
instantiations as Kotlin collections" item lands, and then ride whatever wire that item chooses.

### Dup-thunk placement sub-decision

- **(a) Per-interface registered slot (chosen).** Interfaces whose plan contains at least one
  handle-typed out position register a third extra slot, `dupHandleFn`, beside ADR-085's
  `createBridgeFn`/`bridgeTokenFn`, and their `kotlinBridgeContractHash` tag becomes
  `kotlin_bridge_v2:createBridge+bridgeToken+dupHandle`. Same posture as ADR-085 as shipped: the
  shared `<runtime>` registration's slot count and `NUGET_RUNTIME_CONTRACT_HASH` stay untouched.
- **(b) A second slot on the shared runtime registration.** One thunk total instead of one per
  interface, but it changes `NUGET_RUNTIME_CONTRACT_HASH` for every consumer including ones with
  no interfaces at all, exactly what ADR-085 avoided for the token probe. Rejected for
  consistency; revisit if a third feature needs dup too.

## Decision

Adopt Alternative 1 with dup placement (a). Scope: bound-object handles and bound interfaces,
nullable included, at method parameters (arity ceiling stays 2), method returns, and property
getter/setter slots. Structs, generic instantiations, and collections stay named-skipped.

### Fixture shape and consumer Kotlin (the outer-loop test's shape)

`TestDependency/Menagerie.cs` gains an interface with object- and interface-typed members, e.g.:

```csharp
public interface IKeeper
{
    Ferret? Favorite { get; set; }          // bound-object property, nullable
    Ferret Groom(Ferret pet);               // bound-object param + return
    IFeedable Pair(IFeedable other);        // bound-interface param + return
}
```

```kotlin
class Zookeeper : IKeeper {
  override var favorite: Ferret? = null           // stores a C#-owned object: transfer makes this safe
  override fun groom(pet: Ferret): Ferret { pet.brush(); return pet }
  override fun pair(other: IFeedable): IFeedable = if (other.legs == 4) this@zoo.goat else Goat()
}

val sanctuary = Sanctuary()
sanctuary.hire(Zookeeper())                       // existing ADR-085 crossing
// C# calls keeper.Groom(ferret): the Kotlin body receives a real Ferret wrapper and returns it;
// C# receives its ORIGINAL Ferret instance back (the GCHandle target is the original object).
// C# calls keeper.Pair(goatBridge): nuget IFeedableValue's token probe hands the Kotlin body the
// ORIGINAL Goat (assertSame), and a plain-Kotlin return mints a bridge exactly like ADR-085.
```

### Wire table (extends ADR-085's)

| Slot position | Kotlin slot view | Ownership |
|---|---|---|
| bound-object param | `COpaquePointer?`; body wraps `Ferret(NugetObjectHandle(ptr!!))` (nullable: `ptr?.let { ... }`) | Transfer to Kotlin: C# mints `GCHandle.Alloc(arg)`, the wrapper's cleaner frees it via `freeGcHandleFn`. **Verified components** (wrapper-cleaner path, GCHandle wire); the composition at a slot position is new |
| bound-interface param | `COpaquePointer?`; body `nuget{Iface}Value(ptr!!)` | Transfer to Kotlin. On a token-probe hit the helper already frees the fresh handle and returns the original Kotlin object (**verified**, `kotlinInterfaceValueHelper`); on a miss the `{Iface}Handle` wrapper's cleaner owns it |
| bound-object return / getter | slot returns `COpaquePointer?`; body `dup(result.handle.value)` via `dupHandleFn` | Fresh transfer handle; the C# bridge member does `GCHandle.FromIntPtr(ret)`, casts `.Target`, `Free()`s in `finally`. **Inferred**: standard documented `GCHandle` behaviour, no repo precedent for dup |
| bound-interface return / getter | slot returns `COpaquePointer?`; body `nugetHandleOut(value, "IFace")`: `NugetHandleOwner` → `dup(owner.handle.value)`, plain Kotlin impl → `nugetMintBridge` (already a fresh transfer handle, ADR-085) | Same C# receive-and-free. A plain-Kotlin return mints **one bridge per crossing**; the ROADMAP "bridge reuse per Kotlin object" item is deliberately not advanced here |
| nullable anything | `IntPtr.Zero` ⇄ `null` at every cell above | mirrors ADR-053, **verified** pattern |

### New pieces, all generated

1. `isKotlinBridgeSlotType` gains the bound-set parameters `kotlinBridgePlan` already receives
   (`RirBridging.kt:1083`) and admits `RirObjectHandleType`/`RirInterfaceType` whose
   `RirTypeKey` is bound. An *unbound* object/interface type keeps the named skip.
2. `dupHandleFn`, per interface, conditional: registered only when the plan has a handle-typed
   out position. C# body:
   `[UnmanagedCallersOnly] static IntPtr DupHandle(IntPtr h) => GCHandle.ToIntPtr(GCHandle.Alloc(GCHandle.FromIntPtr(h).Target));`
   **Inferred** (documented `GCHandle` API; ten-line spike recommended before relying on it in
   the walking-skeleton test, though a failure here is loud, not silent).
3. `nugetHandleOut(value, interfaceName)` beside `nugetHandle()` in the generated runtime: the
   out-direction lowering that guarantees freshness (dup for owners, mint for plain impls).
4. `kotlinBridgeContractHash` gains the v2 tag for dup-registering interfaces, so a shim/native
   mismatch stays a loud ADR-054 contract failure.
5. C# bridge members for object cells:

```csharp
public Ferret Groom(Ferret pet)
{
    IntPtr arg = GCHandle.ToIntPtr(GCHandle.Alloc(pet));            // Kotlin's to free (cleaner)
    IntPtr ret = _groom(_ctx.DangerousGetHandle(), arg);
    GCHandle g = GCHandle.FromIntPtr(ret);                          // fresh, ours to free
    try { return (Ferret)g.Target!; } finally { g.Free(); }
}
```

### Identity (extends, does not reopen, ADR-084/085's one-side promises)

- A C# object crossing into a Kotlin slot parameter gets a **fresh Kotlin wrapper per crossing**
  (no Kotlin `===` across two calls passing the same `Ferret`), identical to the shipped posture
  for ordinary reverse returns.
- A C# object returned back out of a slot resolves to the **original managed instance**
  (`GCHandle.Target` is the original), so C#-side `ReferenceEquals` holds for C#-originated
  objects automatically. **Inferred** (definitional `GCHandle` behaviour).
- A Kotlin-implemented interface value round-tripping C# → Kotlin slot parameter resolves to the
  **original Kotlin object** via the existing token probe (**verified** helper).
- A plain Kotlin impl returned from a slot mints a new bridge per crossing; C#-side
  `ReferenceEquals` across crossings is still not promised (unchanged from ADR-085).

### Detection and diagnostics

Unchanged policy: all-or-nothing per interface, and every still-out-of-scope member
(collection, struct, generic instance, unbound object type, `Task`) keeps a named
`skipped_kotlin_bridge` with the hint text widened to name the new vocabulary. The known
cross-package import caveat ROADMAP records for enum-typed slot members applies equally to
object/interface-typed members declared in a different bound namespace than the interface; a
miss is a loud Kotlin compile error.

## Consequences

- First object-typed slot in either direction; ADR-084's forward planner may later mirror this
  shape (out of scope here).
- One new thunk kind (dup) and one new runtime lowering (`nugetHandleOut`); no shared-contract
  change, no new ADR-055 exports.
- Kotlin implementations can now store received bound objects safely (transfer ownership), the
  main behavioural capability this unlocks.
- Deferred, as candidate ROADMAP items: collection-typed slots (blocked on reverse BCL collection
  mapping existing at all, Phase 10); struct-typed slots (need the abiArgs component expansion at
  a slot boundary); generic-instance slots (`Box<int>`, wire-identical but witness resolution at
  a slot position is untested); `Task` members (Phase 12/13, unchanged); bridge reuse per Kotlin
  object (unchanged, ROADMAP Phase 13).

## Mechanism claims ledger

Verified (repo source, cited inline above): GCHandle-IntPtr wire for object/interface/generic
types (`cfnType`); wrapper `createCleaner` → `free()` → `freeGcHandleFn` ownership;
`nuget{Iface}Value` probe-free-or-wrap helper emitted for every bound interface;
`nugetHandle()`/`nugetMintBridge` outbound lowering; per-interface extra-slot registration and
`kotlinBridgeContractHash`; the absence of any reverse collection mapping; ADR-084's forward
planner deferring object slots.

Inferred (documentation, not executed; nobody has verified these against a running bridge):
1. `GCHandle.Alloc(GCHandle.FromIntPtr(h).Target)` yields an independent handle to the same
   object, and freeing the duplicate leaves the original valid (the dup thunk's entire premise;
   documented .NET behaviour, https://learn.microsoft.com/dotnet/api/system.runtime.interopservices.gchandle).
   If wrong, failures are loud (`InvalidOperationException`/invalid-handle), not silent.
2. The cleaner-vs-borrowed-return race that motivates dup (Alternative 3). Unverified in either
   direction; the chosen design is safe whether or not the race is reproducible.
3. `(Ferret)GCHandle.FromIntPtr(ret).Target` then `Free()` in a bridge member is well-ordered
   (the read completes before the free; trivially true in straight-line code, but no repo test
   exercises resolve-then-free in this order yet).
