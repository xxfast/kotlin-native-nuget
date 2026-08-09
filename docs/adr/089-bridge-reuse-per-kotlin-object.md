# ADR-089: Bridge reuse per Kotlin object: per-interface weak table, resolve/weaken runtime thunks, ctx-guarded eviction

## Status

Accepted

## Context

ADR-085 ships one C# bridge **per crossing**: every time the same plain Kotlin `IFeedable`
implementation crosses at an interface-typed position, `mint{Iface}Bridge` mints a fresh
`StableRef` ctx and a fresh `{Iface}Bridge`. ADR-085 documents the consequence: C#-side
`ReferenceEquals` across two crossings of the same Kotlin object is **not** promised. ADR-086
inherits the same behaviour at slot returns (`nugetHandleOut` calls `nugetMintBridge` for a plain
Kotlin impl, "one bridge per crossing; the ROADMAP 'bridge reuse per Kotlin object' item is
deliberately not advanced here"). This ADR advances it: repeated crossings of the same Kotlin
object should hand C# the **same** bridge instance while C# keeps it alive.

The history that constrains the design: ADR-084's original reuse design (a strong cached handle
per implementation object) was falsified by a cross-runtime strong cycle, and ADR-085's lifetime
sub-decision (b) was rejected for the mirror-image cycle. Any reuse table must therefore hold the
bridge **weakly**, or it re-creates the cycle no GC on either side can break.

### Spike (verified by execution, 2026-08-09)

Scratch net10.0 Release console app (outside the repo). A `Bridge` class holding a
SafeHandle stand-in (`ReleaseHandle` increments a counter, the shape of the generated
`KotlinRefHandle`); a `Mint()` helper returning a `GCHandleType.Weak` handle plus a Normal
(transfer) handle to the same instance; a `Resolve(weak)` helper that promotes
`weak.Target` to a fresh Normal handle or returns `IntPtr.Zero`. Every phase runs in its own
`NoInlining` frame so no bridge reference survives on `Main`'s stack (the first, Debug, run kept
the bridge alive through `Main`'s stack slots: ADR-084's live-stack-frame lesson, reproduced).
Output:

```
[live] resolves=True same=True
[after-free+GC] weak.Target null=True safeHandleReleased=1
[resolve-dead] returns Zero=True
[weak.Free-after-death] ok
[strong-cached] still alive=True released=1
[free-thunk-on-weak] ok
```

So, **verified**:

1. While any managed reference to the bridge is live, the weak handle resolves, and the promoted
   strong handle targets the `ReferenceEquals`-identical instance. Reuse is possible without
   re-minting.
2. A weak handle does **not** root the bridge: once the transfer handle is freed and C# holds no
   reference, GC collects the bridge, `weak.Target` reads null, and the SafeHandle's
   `ReleaseHandle` fires. ADR-085's lifetime posture survives intact.
3. Resolving a dead weak handle returns `IntPtr.Zero` (the mint-fresh signal), and
   `GCHandle.Free` on the dead weak handle succeeds.
4. The counterexample that killed ADR-084's reuse design, re-proven: a cached **Normal** handle
   keeps the bridge alive through `GC.Collect()` + `WaitForPendingFinalizers()` indefinitely
   (`still alive=True`). A strong cache is not an option; this is why the table holds weak
   handles.
5. The ADR-051 shared free-thunk shape (`GCHandle.FromIntPtr(x).Free()`) works on a weak handle,
   dead or alive; no new "free a weak handle" thunk kind is needed.

## Alternatives Considered

### 1. Kotlin-side per-interface table: identity key, weak bridge handle (chosen)

Each `{Iface}Bindings.kt` that plans a bridge gains a table keyed by the implementation object's
identity, valued `(weakBridgeHandle: COpaquePointer, ctx: COpaquePointer)`. `mint{Iface}Bridge`
consults it before minting; `nuget_kotlin_release` evicts it (ctx-guarded, see Decision).

Pros: the stable identity (the Kotlin impl object) lives on the Kotlin side, so the table does
too; the weak handle never roots the bridge (spike point 2), so no cycle; the ABI at the crossing
is unchanged (each crossing still hands C# a fresh strong transfer handle, only its **target** is
reused). Cons: two new interface-agnostic runtime thunks, and a table/finalizer race to guard.

### 2. No reuse (status quo)

Keep one bridge per crossing. Pros: nothing to build. Cons: C#-side `ReferenceEquals` and
handle-identity-based dedup (e.g. storing bridges in a `HashSet` keyed by reference) silently
misbehave; ADR-085/086 both had to document the divergence. Rejected: this ADR exists because the
divergence was accepted only as a v1 staging posture.

### 3. Strong cached handle per impl (falsified)

A `GCHandle.Alloc(bridge)` cached in the Kotlin table. Falsified twice before this ADR (ADR-084's
`ConditionalWeakTable` design, ADR-085 lifetime (b)) and re-falsified by the spike's
counterexample (point 4): the strong handle roots the bridge, the bridge's ctx StableRef roots
the impl, the impl's table entry holds the strong handle. Neither GC can break the cycle; the
bridge and the Kotlin object leak forever. Rejected.

### 4. C#-side table (`ConditionalWeakTable` in the shims)

Mirror of ADR-084's forward design. Rejected on mechanism: C# has nothing stable to key on. Each
mint creates a fresh ctx `StableRef`, so the ctx IntPtr differs per mint, and the Kotlin impl
object itself is not a managed key. The only side that can ask "have I seen this object before?"
is Kotlin.

## Decision

Adopt Alternative 1.

### Consumer-visible promise

```csharp
var goat = /* Kotlin passes the same Goat twice */;
sanctuary.Introduce(goat);          // crossing 1 mints IFeedableBridge #1
sanctuary.Featured = goat;          // crossing 2 resolves the SAME bridge instance
ReferenceEquals(sanctuary.Featured, storedFromIntroduce);   // true, while C# kept it alive
```

- C#-side `ReferenceEquals` across crossings of the same Kotlin object holds **while C# keeps
  the bridge alive** (an ordinary managed reference suffices).
- If C# drops every reference and the .NET GC collects the bridge, a later crossing yields a
  **new** bridge instance. GC-timed, never prompt, same posture as every release in ADR-084/085.
- Kotlin-side identity (ADR-085's token probe resolving to the original Kotlin object) is
  unchanged; this ADR only upgrades the C#-side half of the identity story.

### Bridge mechanism

Two new **interface-agnostic** thunks ride the shared `<runtime>` registration
(`nuget_runtime_register`), beside the existing ADR-051 `freeGcHandle`:

```csharp
// Weak GCHandle to the same target as [strong]; the Kotlin table stores this. Verified (spike
// point 2): it does not root the target.
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
internal static IntPtr WeakenGcHandle(IntPtr strong) =>
    GCHandle.ToIntPtr(GCHandle.Alloc(GCHandle.FromIntPtr(strong).Target, GCHandleType.Weak));

// Fresh strong TRANSFER handle to the weak handle's target, or Zero when it was collected.
// Verified (spike points 1 and 3).
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
internal static IntPtr ResolveGcHandle(IntPtr weak) =>
    GCHandle.FromIntPtr(weak).Target is object target
        ? GCHandle.ToIntPtr(GCHandle.Alloc(target))
        : IntPtr.Zero;
```

**Runtime-contract note**: the shared `<runtime>` registration grows from 1 slot to 3, so
`NUGET_RUNTIME_CONTRACT_HASH` (`RirBridging.kt`, currently
`fnv1a64("runtime:freeGcHandle(handle:COpaquePointer):Unit")`) changes to a string naming all
three slots. A stale shim against a new native library (or the reverse) fails the ADR-054
`slotCount`/`contractHash` check loudly at startup. This is deliberately **not** a bridge-tag
change: the per-interface contract tag stays `kotlin_bridge_v2` (uniform since ADR-087 stage 2),
because no per-interface slot signature moves; only the shared runtime contract does.

### Kotlin side: the table and the mint path

Per plannable interface, in `{Iface}Bindings.kt` (beside `mint{Iface}Bridge`, which already
exists per interface, so the table is naturally partitioned per interface: one Kotlin object
implementing two bound interfaces holds up to one live bridge per interface, which composes with
the target-keyed `nugetMintBridge(value, interfaceName)` dispatch as shipped):

```kotlin
private class BridgeEntry(val weakBridge: COpaquePointer, val ctx: COpaquePointer)
private class IdentityKey(val ref: Any) {
  override fun equals(other: Any?): Boolean = other is IdentityKey && other.ref === ref
  override fun hashCode(): Int = ref.identityHashCode()
}
private val bridgeTable = mutableMapOf<IdentityKey, BridgeEntry>()
```

`mint{Iface}Bridge(impl)` becomes:

1. Under the table guard: look up `IdentityKey(impl)`. On a hit, call `resolveGcHandleFn(entry.weakBridge)`.
   - Non-zero: return it. It is a fresh strong **transfer** handle, exactly what the factory used
     to return, so the call site's existing `nugetTransferScope` free-after-call is unchanged.
     The ABI and ownership rules of ADR-085/086 do not move.
   - Zero (bridge collected): free the dead weak handle via the existing `freeGcHandleFn`
     (verified, spike point 5), evict the entry, fall through to mint.
2. Mint as today (fresh `StableRef` ctx, factory call), then `weakenGcHandleFn(strongHandle)` and
   store `BridgeEntry(weak, ctx)` before returning the strong handle.

The table key is strong, and that is safe: while the bridge lives, its ctx `StableRef` roots the
impl anyway, so the entry adds no rooting; when the bridge dies, the release path below removes
the entry. The bridge side of the pairing is weak (spike point 2), so no cycle exists in either
direction.

### Release path: ctx-guarded eviction in `nuget_kotlin_release`

`KotlinRefHandle.ReleaseHandle` P/Invokes `nuget_kotlin_release(ctx)` on the .NET finalizer
thread. That export must now, before disposing the StableRef:

1. Resolve the impl from ctx, look up its table entry, and evict it **only if
   `entry.ctx == ctx`**. The guard closes the late-finalizer race: bridge #1 dies, a crossing
   observes the dead weak handle and mints bridge #2 (new ctx, entry replaced), and only then
   does bridge #1's finalizer run. An unguarded eviction would remove bridge #2's live entry
   (benign for correctness, it just re-mints later, but it leaks bridge #2's weak GCHandle). With
   the guard, the stale release is a no-op on the table.
2. Free the evicted entry's weak GCHandle via `freeGcHandleFn` (verified on weak handles, spike
   point 5). **Inferred** (not spiked): this is a Kotlin-to-C# thunk call made *inside* a
   C#-to-Kotlin P/Invoke on the finalizer thread. ADR-085's spike verified calling into Kotlin
   from the finalizer thread and calling back into the Kotlin object from there, but not this
   exact re-entry shape. If it proves problematic, the fallback is lazy freeing: leave the dead
   weak handle in the evicted entry's place and free it on the next mint for that impl (or leak
   one weak-handle table slot per dead bridge, bounded and small); the design does not depend on
   which variant ships.
3. Dispose the StableRef, as today.

### Concurrency

The table is written from arbitrary caller threads (mint) and from the .NET finalizer thread
(release). **Recommended: guard every table access with a lock.** It is cheap (registration-scale
plus one acquisition per interface-typed crossing, nowhere near the per-member hot path), and the
alternative (reasoning about a lock-free map under two runtimes' schedulers) is not worth it.
**Inferred** (not verified against a Kotlin/Native build): the concrete primitive. The generated
runtime already uses `AtomicInt.compareAndSet` loops elsewhere (`NugetObjectHandle.free`,
`NugetRegistry.record`), so a small CAS spinlock in the same style needs no new dependency; if
`kotlin.concurrent` offers a real mutex on all targets by implementation time, prefer it.

### Inferred claims ledger (this ADR)

- `kotlin.native.identityHashCode` availability and its exact package/opt-in requirement
  (`@ExperimentalNativeApi` is expected; the generated files already opt in for `@CName`).
  **Inferred from documentation, not verified against a Kotlin/Native build.** If it is
  unavailable on some target, the fallback key is a boxed sequence number stored per impl via a
  side map keyed by `===`-scanning, which is unacceptable at scale, so verify this first during
  implementation; the design assumes it holds.
- The finalizer-thread re-entry shape in the release path (step 2 above), with its named
  fallback.
- The lock primitive choice (recommendation above).

Everything else load-bearing (weak handle does not root; promote-while-live preserves
`ReferenceEquals`; Zero on dead; free-on-dead; strong cache roots forever; the shared free thunk
works on weak handles) is **verified** by the spike quoted in Context.

## Scope

- Applies to every path that mints a bridge for a plain Kotlin impl: ADR-085's parameter-position
  crossings (`nugetHandle` fallback through `nugetMintBridge`) **and** ADR-086's slot-return path
  (`nugetHandleOut` calls the same `nugetMintBridge`, so it inherits reuse with no extra work).
  ADR-086's documented "a plain Kotlin impl returned from a slot mints a new bridge per crossing"
  divergence is superseded by this ADR.
- Per-interface tables: a Kotlin object crossing at both `IFeedable` and `ITagged` positions
  holds one bridge per interface, each independently reused. Cross-interface bridge unification
  is out of scope (the C# positions declare different types; nothing observes it).
- Forward-position crossings (ADR-088) are out of scope here; they ride their own pipeline.

## Consequences

- ADR-085's and ADR-086's "one bridge per crossing / C#-side `ReferenceEquals` not promised"
  consequences are superseded for live bridges; the fresh-bridge-after-GC caveat replaces them
  and must be documented as the consumer-facing rule.
- The shared `<runtime>` registration grows from 1 to 3 slots and its contract hash changes:
  every consumer rebuild regenerates both sides together (the normal path), and mixed-build
  states fail the ADR-054 check loudly instead of mis-assigning pointers.
- `nuget_kotlin_release` gains table-eviction work; it remains registration/finalization-scale,
  never per-member-call-scale.
- Deterministic tests for "fresh bridge after collection" must drop the C# reference inside a
  helper frame before forcing GC (the live-stack-frame lesson, re-confirmed by this ADR's own
  spike misfiring in Debug until phases moved into `NoInlining` helpers).

## Implementation Addendum (2026-08-09)

**`kotlin.native.identityHashCode` moves from Inferred to Verified.** A konanc probe confirmed it is
an extension on `Any?` (not `Any`, so it accepts a null receiver and returns `0` rather than
throwing), gated behind `@OptIn(ExperimentalNativeApi::class)`. Shipped exactly as designed:
`NugetIdentityKey.hashCode()` (`NugetGenerateBindingsTask.kt`, generated into every consumer's
`NugetRuntime.kt`) carries the same opt-in the generated files already needed for `@CName`.

**The finalizer re-entry design in the Decision's release path is deliberately unshipped.** The
Decision sketched an *eager* free (step 2 of the release path: free the evicted entry's weak handle
from inside `nuget_kotlin_release`, on the .NET finalizer thread, itself inside a C#-to-Kotlin
P/Invoke) and named the lazy-free fallback only as a hedge. The shipped design goes straight to the
hedge: a dead weak handle found by `evict()` is queued on `nugetPendingWeakFrees` (a lock-free
`AtomicReference<List<COpaquePointer>>`) rather than freed on the spot, and the queue drains on the
next mint for *any* interface, which always runs on an ordinary caller thread. The one exception is
`resolve()` detecting a bridge already dead: that frees its own now-useless weak handle eagerly, but
on the calling thread, never inside the finalizer-thread release path. The finalizer re-entry shape
this ADR flagged as unspiked was never exercised; the design does not depend on it.

**The lock resolved to a CAS spinlock on `AtomicInt`**, per the ADR's own recommendation and in the
same style as `NugetObjectHandle.free`/`NugetRegistry.record`: `NugetBridgeTable.locked {}`
busy-waits on `lock.compareAndSet(0, 1)` around a critical section that is at most a map lookup plus
one GCHandle thunk call.

**A race beyond anything the Decision named was found and closed: concurrent mint for the same
implementation object.** Two threads racing `mint{Iface}Bridge(impl)` for the same `impl` can both
miss the reuse table (both see no entry), both mint a real, valid bridge, and both call
`store(impl, ...)`. `NugetBridgeTable.store` handles this explicitly: `entries.put(...)` returns the
previous entry when one exists, and if the racing store *did* overwrite a fresher one, the loser's
weak handle is queued for eviction via the same `nugetPendingWeakFrees` path rather than leaked or
silently dropped. Both minted bridges are individually valid C# objects; only the one nobody will
resolve through the table again has its weak handle reclaimed.

**Consumer promise verified end to end.** `MenagerieRoundTripTests.cs`'s
`KotlinGoatRememberedTwice_SameInstanceCrossingIsReused` crosses the same Kotlin `Goat` into
`Sanctuary.Remember` twice while the first bridge stays referenced, and asserts the second crossing
resolves to the same C#-side instance; its sibling
`KotlinGoatsRememberedTwice_DifferentInstancesAreNeverSame` pins that two distinct `Goat` instances
are never conflated by identity-keyed reuse. `LiveKotlinGoatBridge_SurvivesACollection_AndStillResolves`
(pre-existing, ADR-085) continues to pass unchanged: a live bridge survives forced GC rounds and
still resolves.
