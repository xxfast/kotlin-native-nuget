# ADR-084: C#-implemented Kotlin interfaces at general parameter positions: bridge factory, runtime dispatch, cleaner-driven lifetime, round-trip identity

## Status

Accepted

## Context

ADR-039 shipped interface bridging only for the `add*/remove*` subscription route, with a v1 scope
of Unit-returning, arity 0-1 methods and no properties. ADR-040 shipped Kotlin interfaces at
return/parameter positions, but its sub-decision B restricts interface-typed parameters
(`Cat.befriend(pet: Pet)`) to Kotlin-backed wrappers: `NugetMarshal.HandleOf` reflectively reads
`_handle` and throws `NotSupportedException` for a C#-implemented `IPet`
(`BidirectionalTests.Cat_Befriend_CSharpImplementedPet_ThrowsNotSupportedException` pins this
boundary; `BidirectionalTests.CSharpDog_ImplementsIPet` has been skipped since Phase 5 waiting for
this feature).

The ROADMAP item "Support implementing Kotlin interfaces in C# and passing them to Kotlin
producers" is one design with five facets, decided together here because they share a single wire
contract:

1. Interface methods with non-Unit returns (`IPet.Speak(): string`).
2. Interface properties as getter function pointers (`IPet.Name`).
3. Runtime dispatch at interface parameter positions: Kotlin-backed wrapper (`_handle`) vs
   C#-implemented object.
4. Stored lifetime without an `add*/remove*` pair: when Kotlin stores the object indefinitely
   (`befriend` assigns `friend = pet`), who frees the C#-side GCHandles.
5. Round-trip identity: a stored C#-implemented object returned back to C# (`oreo.Friend`) must
   resolve to the original C# object, not a double-bridged wrapper.

### What the repo already proves (verified)

- **Marshal-back of a non-Unit result through a function pointer (facet 1's mechanism)** is already
  shipped and green for lambda parameters: `LambdaParameterExports.kt` reinterprets the callback as
  `CFunction<(...) -> COpaquePointer?>`, invokes it, and unwraps
  `resultRef.asStableRef<String>().get()` then disposes; the C# side returns
  `NugetMarshal.WrapString(...)`, which P/Invokes the Kotlin export `nuget_wrap_string` to mint a
  `StableRef<String>` (`CirMarshalRenderer.kt` lines 45-48, `CirClassTranslator.kt` line 1796).
  `ReverseLambdaTests.Cat_DescribeWith_InvokesCSharpLambda` exercises it end to end. **Verified**
  by repo code and a green integration test. This ADR only generalizes the per-slot pattern from
  "one lambda" to "one slot per interface member".
- **Multi-slot flat function-pointer crossing** (one fnPtr + ctx pair per member, anonymous
  `object : Interface` bridge on the Kotlin side) is shipped and green for the ADR-039 route:
  `InterfaceBridgeExports.kt` + `translateInterfaceBridgeMethod` + `InterfaceBridgingTests.cs`
  (5 passing tests against `CatEventSource`). **Verified.** No vtable-struct spike is needed; the
  per-slot mechanism is proven and the slot count is just N instead of 2.
- **A generic StableRef dispose export (`nuget_dispose`) exists** (`CirMarshalRenderer.kt`,
  `NugetListNative.Dispose`). **Verified.**

### What was spiked for this ADR (verified by execution)

The one mechanism nothing in the repo proves is facet 4's release path: a Kotlin-side
`kotlin.native.ref.createCleaner` invoking a C function pointer (the C#-side release callback)
when the bridge object becomes unreachable. Spiked in a scratch Kotlin/Native 2.4.10 macosArm64
project (outside the repo):

```kotlin
val releaseFn: CPointer<CFunction<(COpaquePointer?) -> Unit>> = staticCFunction { _ ->
  releasedCount.incrementAndGet()
}
class Bridge(fn: CPointer<CFunction<(COpaquePointer?) -> Unit>>, ctx: COpaquePointer?) {
  private val cleaner = createCleaner(fn to ctx) { (f, c) -> f.invoke(c) }
}
fun allocateAndDrop(ctx: COpaquePointer) { Bridge(releaseFn, ctx) }
// main: allocateAndDrop(ctx); loop { GC.collect(); usleep(200ms) } until released
```

Output: `released=1 gcRounds=1`. So: **verified** that a cleaner whose argument carries a
`CPointer<CFunction<...>>` plus ctx fires the C callback after one `kotlin.native.runtime.GC
.collect()` round (within 200ms, on the cleaner worker thread), on Kotlin 2.4.10.

The first spike run is equally load-bearing: with the bridge held in a `main`-frame local and set
to `null`, the cleaner did **not** fire within 5s and 1 GC round. A live stack frame roots the
object even after `= null`. Consequence: the deterministic release test must drop the last
reference in a separate function frame before forcing GC, and the docs must not promise prompt
release, only release on a later GC cycle.

### How other targets handle this (from ADR-039's survey, unchanged)

ObjC/Swift export is the closest analogue: a Swift class conforming to an exported Kotlin
`@protocol` is dispatched via `objc_msgSend` (the runtime is the vtable) and ARC ties the
Swift object's lifetime to the Kotlin-side reference. At a raw C boundary both must be made
explicit: N function pointers stand in for `objc_msgSend`, and the cleaner-driven release export
stands in for ARC's release when the Kotlin side drops the last reference. Xamarin's peer-object
table (Java/ObjC object identity map keyed by handle) is the precedent for facet 5.
JVM/JS/Wasm analogues share a heap and are not applicable; not re-surveyed here.

## Alternatives Considered

### 1. Per-interface bridge factory export + handle-path reuse (chosen)

One new export **per interface** (not per call site): `pet_bridge_create(slots..., releasePtr,
releaseCtx, errorOut) -> COpaquePointer?` builds an anonymous `object : Pet` whose members forward
to the C# function pointers, and returns a `StableRef` handle to it. Every existing interface-typed
parameter export (`cat_befriend`) is **reused unchanged**: C# converts the C#-implemented object to
a Kotlin bridge handle first, then goes down the existing handle path.

Pros:
- One export per interface, regardless of how many parameter positions accept it. No signature
  churn on existing exports; the ADR-055 contract only gains new entries.
- Facet 3 dispatch collapses to one shared C# helper (`HandleOf` grows a bridge fallback instead
  of throwing).
- Facet 5 falls out naturally: C# caches "this Dog instance ⇒ this Kotlin bridge handle" in a
  `ConditionalWeakTable`, so passing the same Dog twice reaches Kotlin as the **same** bridge
  object, preserving Kotlin-side `===` identity too.

Cons:
- Two P/Invokes on first pass of a C# object (create bridge, then call the target export).
- The factory's flat parameter list is 2N+3 for an N-member interface (IPet: 7 members = 17
  params). Same verbosity ADR-039 already accepted.

### 2. Per-call-site bridge export (`cat_befriend_bridge(handle, slots..., errorOut)`)

Direct extension of ADR-039's shape: each interface-typed parameter position gets a second export
taking the slot list inline.

Pros: single P/Invoke per call; closest to the accepted ADR-039 pattern.
Cons: duplicates the slot list on every call site (M call sites x 2N params); every future
interface-typed parameter grows the ABI contract; no natural home for identity caching (a new
bridge object per call, so Kotlin `===` breaks when the same Dog is passed twice); facet 5 still
needs the factory-style token anyway. Rejected.

### 3. Vtable struct (single pinned struct of N function pointers)

Rejected by ADR-039 for layout fragility and pinned-handle cost; nothing has changed. Rejected
here for the same reasons, with the added point that the flat-slot mechanism is now proven green
in-repo while a struct crossing has never been exercised.

### Lifetime sub-decision (facet 4)

- **(a) Kotlin-side cleaner invoking a C# release function pointer (chosen).** The bridge object
  owns a `createCleaner` whose argument is the release fnPtr + ctx; when Kotlin's GC collects the
  bridge, the cleaner calls back into C#, which frees the delegate GCHandles and the object-rooting
  GCHandle. Mirrors `StableRef` ownership in the forward direction and ARC in ObjC export.
  Spike-verified above. No new registration machinery: the release slot is just one more pinned
  delegate, exactly like the member slots.
- **(b) C#-side callback registry with a Kotlin-callable `nuget_callback_free(key)` C# export
  (ADR-036 Alternative 4).** Requires `[UnmanagedCallersOnly]`/registration infrastructure that in
  the forward direction does not exist (it is Phase 8 machinery, and forward consumers may not have
  the reverse pipeline at all). Rejected for the forward direction.
- **(c) Leak (free only at process exit).** Unacceptable for long-lived hosts; kept only as the
  documented interim state of the staged plan's first commit.

### Identity sub-decision (facet 5)

- **(a) Token marker interface + shared probe export (chosen).** The generated bridge object also
  implements a generated marker interface `NugetCSharpBridge { val nugetToken: COpaquePointer }`
  whose token is the `GCHandle` (as `IntPtr`) of the C#-side bridge state rooting the original
  object. A shared export `nuget_csharp_token(handle) -> COpaquePointer?` answers "is this handle
  a C#-backed bridge, and if so whose". Interface-return unwrapping in C# probes first; on a hit it
  disposes the fresh return StableRef and returns the original C# object from
  `GCHandle.FromIntPtr(token).Target`. One extra P/Invoke per interface-typed return; consistent
  with the two-call precedent of ADR-002.
- **(b) Wrap-and-compare on the C# side only.** Cannot work: the returned handle is a fresh
  StableRef to the bridge object; C# has nothing to compare against without a Kotlin-side probe.
- **(c) Skip identity, document double-bridging.** ADR-039's v1 answer. Functionally correct but
  `Assert.Same(dog, oreo.Friend)` fails and every property read round-trips through two bridges.
  Kept only as the staged plan's interim state.

## Decision

Adopt Alternative 1 with lifetime (a) and identity (a). All five facets ride one wire contract:

### Generated Kotlin (per interface reachable at a parameter position)

```kotlin
// Shared, generated once:
internal interface NugetCSharpBridge { val nugetToken: COpaquePointer }

@CName("nuget_csharp_token")
fun export_nuget_csharp_token(handle: COpaquePointer): COpaquePointer? =
  (handle.asStableRef<Any>().get() as? NugetCSharpBridge)?.nugetToken

// Test/support export so hosts and tests can force the release path deterministically:
@CName("nuget_gc_collect")
fun export_nuget_gc_collect() { kotlin.native.runtime.GC.collect() }

// Per interface (IPet: 3 property getters + 4 methods = 7 slots):
@OptIn(ExperimentalNativeApi::class)
@CName("pet_bridge_create")
fun export_pet_bridge_create(
  nameGetPtr: COpaquePointer, nameGetCtx: COpaquePointer,
  legsGetPtr: COpaquePointer, legsGetCtx: COpaquePointer,
  nicknameGetPtr: COpaquePointer, nicknameGetCtx: COpaquePointer,
  speakPtr: COpaquePointer, speakCtx: COpaquePointer,
  greetPtr: COpaquePointer, greetCtx: COpaquePointer,
  fetchPtr: COpaquePointer, fetchCtx: COpaquePointer,
  napPtr: COpaquePointer, napCtx: COpaquePointer,
  releasePtr: COpaquePointer, releaseCtx: COpaquePointer,
  errorOut: COpaquePointer?,
): COpaquePointer? = try {
  val nameGetFn = nameGetPtr.reinterpret<CFunction<(COpaquePointer) -> COpaquePointer?>>()
  val legsGetFn = legsGetPtr.reinterpret<CFunction<(COpaquePointer) -> Int>>()
  val nicknameGetFn = nicknameGetPtr.reinterpret<CFunction<(COpaquePointer) -> COpaquePointer?>>()
  val speakFn = speakPtr.reinterpret<CFunction<(COpaquePointer) -> COpaquePointer?>>()
  val greetFn = greetPtr.reinterpret<CFunction<(COpaquePointer) -> COpaquePointer?>>()
  val fetchFn = fetchPtr.reinterpret<CFunction<(COpaquePointer?, COpaquePointer) -> COpaquePointer?>>()
  val napFn = napPtr.reinterpret<CFunction<(COpaquePointer) -> Unit>>()
  val releaseFn = releasePtr.reinterpret<CFunction<(COpaquePointer) -> Unit>>()

  val bridge = object : Pet, NugetCSharpBridge {
    override val nugetToken: COpaquePointer = releaseCtx
    @Suppress("unused")
    private val cleaner = createCleaner(releaseFn to releaseCtx) { (fn, ctx) -> fn.invoke(ctx) }
    override val name: String
      get() {
        val ref = nameGetFn.invoke(nameGetCtx)!!
        val value = ref.asStableRef<String>().get()
        ref.asStableRef<Any>().dispose()
        return value
      }
    override val legs: Int get() = legsGetFn.invoke(legsGetCtx)
    override val nickname: String?
      get() {
        val ref = nicknameGetFn.invoke(nicknameGetCtx) ?: return null
        val value = ref.asStableRef<String>().get()
        ref.asStableRef<Any>().dispose()
        return value
      }
    override fun speak(): String { /* same unwrap as name */ }
    override fun greet(): String { /* same unwrap; a slot exists even for defaulted members */ }
    override fun fetch(item: String): String {
      // The argument StableRef is minted here and disposed by the C# reader
      // (`NugetMarshal.FromHandle<string>` disposes on read): one owner, one dispose.
      val itemRef = StableRef.create(item as Any).asCPointer()
      val ref = fetchFn.invoke(itemRef, fetchCtx)!!
      /* same unwrap */
    }
    override fun nap() = napFn.invoke(napCtx)
  }
  StableRef.create(bridge).asCPointer()
} catch (e: Throwable) {
  if (errorOut != null) errorOut.reinterpret<COpaquePointerVar>().pointed.value =
    StableRef.create(buildError(e)).asCPointer()
  null
}
```

Mechanism labels:
- Slot invocation, `StableRef` argument wrapping, and result unwrapping: **verified** (identical
  code shipped in `LambdaParameterExports.kt` / `InterfaceBridgeExports.kt`, green in
  `ReverseLambdaTests` / `InterfaceBridgingTests`).
- `createCleaner` invoking the release fnPtr after one forced GC round: **verified by spike**
  (Kotlin 2.4.10 macosArm64, output `released=1 gcRounds=1`).
- Nullable-String getter via nullable `COpaquePointer?` return with `IntPtr.Zero` ⇒ `null`:
  **inferred** (direct composition of two verified pieces; no shipped slot does this exact shape
  yet).
- The cleaner block running on the cleaner worker thread being a safe context to call a C#
  delegate thunk: **verified for the call itself by the spike** (the C callback executed there);
  that arbitrary C# code is safe on that thread is **inferred** from .NET delegates being callable
  from any native thread.

Slot order is the C# projection's declared-member order (properties first, then methods, both in
KSP declaration order), which must be produced by one shared planner consumed by both the Kotlin
exports side and the C# translator side, exactly as `InterfaceExports.kt` already shares
`ForwardCallablePlanner`. Slot-order drift between the two sides is the classic silent-ABI bug;
the ADR-055 contract hash covers the export name and parameter count, not per-slot meaning, so
the shared planner is the actual defense. **Inferred** design constraint, not yet code.

### Generated C# (shared marshal layer)

```csharp
// Replaces the throwing branch of NugetMarshal.HandleOf (facet 3):
internal static IntPtr HandleOf(object value)
{
    var field = value.GetType().GetField("_handle", ...);
    if (field != null) return (IntPtr)field.GetValue(value)!;
    return NugetBridge.HandleFor(value); // C#-implemented: create or reuse a Kotlin bridge
}

internal static class NugetBridge
{
    private static readonly ConditionalWeakTable<object, BridgeState> States = new();

    internal static IntPtr HandleFor(object impl) =>
        impl switch
        {
            IPet pet => States.GetValue(impl, _ => PetBridgeState.Create(pet)).KotlinHandle,
            _ => throw new NotSupportedException($"{impl.GetType().Name} implements no bridgeable interface."),
        };

    // Facet 5, return-position probe:
    internal static bool TryResolveCSharp<T>(IntPtr handle, out T original) where T : class
    {
        IntPtr token = Native_CSharpToken(handle);          // nuget_csharp_token
        if (token == IntPtr.Zero) { original = null!; return false; }
        NugetMarshal.Dispose(handle);                       // drop the fresh return StableRef
        original = (T)((BridgeState)GCHandle.FromIntPtr(token).Target!).Instance;
        return true;
    }
}

internal sealed class PetBridgeState : BridgeState
{
    internal static PetBridgeState Create(IPet impl)
    {
        var state = new PetBridgeState { Instance = impl };
        IntPtr token = GCHandle.ToIntPtr(state.SelfHandle = GCHandle.Alloc(state));
        NugetObjectCallback nameGet = _ => NugetMarshal.WrapString(impl.Name);
        NugetIntCallback legsGet = _ => impl.Legs;
        NugetObjectCallback nicknameGet = _ => impl.Nickname is null ? IntPtr.Zero : NugetMarshal.WrapString(impl.Nickname);
        NugetObjectCallback speak = _ => NugetMarshal.WrapString(impl.Speak());
        NugetObjectCallback greet = _ => NugetMarshal.WrapString(impl.Greet());
        NugetObjectObjectCallback fetch = (itemPtr, _) =>
        {
            // FromHandle already disposes itemPtr; a second Dispose here (and a third on the
            // Kotlin side) would double-free the StableRef the bridge object minted.
            string item = NugetMarshal.FromHandle<string>(itemPtr);
            return NugetMarshal.WrapString(impl.Fetch(item));
        };
        NugetVoidCallback nap = _ => impl.Nap();
        NugetVoidCallback release = _ => state.FreeAll();   // facet 4: called by the Kotlin cleaner
        state.Pin(nameGet, legsGet, nicknameGet, speak, greet, fetch, nap, release);
        state.KotlinHandle = Native_PetBridgeCreate(
            Ptr(nameGet), token, Ptr(legsGet), token, Ptr(nicknameGet), token,
            Ptr(speak), token, Ptr(greet), token, Ptr(fetch), token, Ptr(nap), token,
            Ptr(release), token, out IntPtr error);
        if (error != IntPtr.Zero) { state.FreeAll(); throw NugetErrorNative.BuildException(error); }
        return state;
    }
}
```

`FreeAll()` frees the per-delegate GCHandles and `SelfHandle`. Freeing the release delegate's own
GCHandle from inside its invocation is safe because the executing delegate is rooted by the call
in progress: **inferred** from .NET GC semantics, not spiked; if wrong, the failure mode is an
`InvalidOperationException` on `Free`, loud rather than silent, so a spike was not spent on it.
The ctx passed to every slot is the same token; the delegates capture `impl` directly (ADR-036
convention), the ctx exists for the release call and the identity token.

`BridgeState.KotlinHandle` (the factory's StableRef) is owned by the C# state and disposed via
`nuget_dispose` from `BridgeState`'s finalizer, so the Kotlin bridge object becomes collectible
once both Kotlin drops its own references and C# drops the implementing object. Until the
finalizer runs, the bridge stays alive; that is a delayed release, not a leak. **Inferred.**

**Falsified while implementing commit 2.** That finalizer can never run: the state is reachable
from a strong root at all times, through `pins -> release delegate -> state` and through
`pins -> slot delegates -> impl -> ConditionalWeakTable[impl] -> state`. Both chains are cut only
by `FreeAll`, which only the cleaner calls, which needs `KotlinHandle` disposed first: a cycle.
Making the roots weak instead is not an option, because Kotlin holding the bridge *must* keep the
implementing C# object alive (the ARC analogue), which is exactly what the strong `pins -> impl`
chain provides.

So the ownership rule is inverted: the C# state must **not** hold a strong reference to the Kotlin
bridge at all. The factory's StableRef is a *transfer* handle, valid for the call that consumes it;
Kotlin's own reference is then the only root, and its collection drives the release. Commit 2 shipped the mechanism with an explicit hand-back; **commit 3 makes it automatic**:
`HandleOf(value, out bool owned)` reports whether it minted a bridge handle or read a wrapper's own
`_handle`, and the interface-parameter call site disposes the minted one after the native call
returns, reusing the same prelude/cleanup slots collection parameters already use
(`ForwardCirPlanProjection`). Nothing is left for a host to call.

Two consequences of the inversion, both **verified** by the shipped tests:
- The `ConditionalWeakTable` reuse is gone: one bridge is built per *crossing*, because a cached
  handle is a disposed handle by the time the next crossing asks for it. The ADR's claim that the
  same C# object "reaches Kotlin as the same bridge object, preserving Kotlin-side `===` identity
  too" is therefore **false as shipped**. C#-side identity does not depend on it (it comes from
  the token, facet 5), and no test asserts Kotlin-side `===`. Restoring it would need a Kotlin-side
  weak table keyed by token, which is not in this ADR.
- The property *setter* position (`cat.Friend = dog`) is released too. It needed the same
  prelude/cleanup slots the parameter path uses, which that projection did not have; adding them
  also freed the collection setter's `CreateList`/`CreateMap`/`CreateSet` handle, a pre-existing
  leak of the identical shape that came along for free with the shared mechanism.

### Consumer C# (end state)

```csharp
private class Dog : IPet
{
    public string Name { get; }
    public int Legs => 4;
    public string? Nickname => null;
    public Dog(string name) { Name = name; }
    public string Speak() => "Woof!";
    public string Greet() => $"Hi, I'm {Name} the dog";
    public string Fetch(string item) => $"{Name} enthusiastically fetches the {item}";
    public void Nap() { }
    public void Dispose() { }
}

using var oreo = new Cat("Oreo", 9);
var rex = new Dog("Rex");

oreo.Befriend(rex);                       // facet 3: bridge path, no NotSupportedException
using IPet? friend = oreo.Friend;         // facet 5: probe hits, no double bridge
Assert.Same(rex, friend);
Assert.Equal("Woof!", oreo.ClosestFriend().Speak());  // facets 1+2: Kotlin dispatches into Dog
```

### Detection rule

Every non-generic interface-typed parameter position already routed through `HandleOf` gains the
bridge fallback automatically; the factory export is generated for each interface reachable at
such a position (reusing ADR-066's reachability computation). The ADR-039 `add*/remove*` route is
unchanged in v1 and converges on the factory in a later commit (its pair detection keeps the
`IDisposable` subscription surface, but the bridge construction can share the factory slots).

### Scope

**In v1 (per staged plan below):** methods of arity 0-2 with `Unit`, primitive, `Boolean`, enum,
`String`, and `String?` returns; `val` property getters of the same types; the cleaner release
path; the identity probe. Parameter types: the ADR-036/039 table (primitives, `Boolean`, enum,
`String`, object handles).

**Deferred:** `var` property setters (second slot each); object-typed and collection-typed slot
returns; `suspend` members (compose with ADR-019); exception propagation from a C# member into
Kotlin (ROADMAP Phase 7 line, mirrors ADR-024; v1 members must not throw); generic interfaces;
converging the ADR-039 subscription route onto the factory; multiple bridgeable interfaces on one
C# class (v1: first match wins, diagnostic on ambiguity).

## Consequences

### Implementation notes from commit 1

- The delegate types are named `NugetBridge{params}{result}Callback` (`NugetBridgeObjectCallback`,
  `NugetBridgeObjectObjectCallback`, `NugetBridgeVoidCallback`), not the `NugetObjectCallback`
  spelling sketched above: ADR-039's subscription route already owns `Nuget{args}VoidCallback` in
  the same shared `CirCallbackDelegateHelper`, and a returning delegate must not collide with a
  void one of the same argument shape.
- The shared slot planner is `forward/ForwardInterfaceBridgePlanner.kt`; the two projections are
  `exports/InterfaceBridgeFactoryExports.kt` and `cir/CirBridgeRenderer.kt`. An interface with any
  out-of-scope member (a `var`, an object/collection slot, `suspend`, generics) plans to `null`:
  no factory, no bridge state, and `HandleOf` keeps ADR-040's throw for it.

### Staged shipping plan

1. **Commit 1 (satisfies the parent ROADMAP checkbox):** `pet_bridge_create` factory with full
   member slots (facets 1 + 2), `HandleOf` bridge fallback (facet 3), no release (documented
   interim leak), no identity probe (returns double-bridge). Un-skips and extends
   `CSharpDog_ImplementsIPet`; replaces
   `Cat_Befriend_CSharpImplementedPet_ThrowsNotSupportedException` with a success-path test.
2. **Commit 2 (facet 4):** release slot + `createCleaner` in the bridge object, `nuget_gc_collect`
   export, deterministic release test (drop the C# and Kotlin references **in a helper method
   frame**, then force Kotlin GC and assert the release callback fired; the spike showed a live
   frame local defeats this). Shipped, with the transfer-handle correction above: the release is
   observable through `NugetBridgeState.ReleasedCount`, which `FreeAll` increments after freeing
   the delegate pins and the self handle. The automatic transfer-handle disposal at the call site
   is deferred with facet 5.
3. **Commit 3 (facet 5):** `NugetCSharpBridge` marker + `nuget_csharp_token` probe; `Assert.Same`
   round-trip tests. Shipped, with two corrections to the sketch above: the token is a `GCHandle`
   to the **implementing object itself**, not to the `BridgeState` (so the shared probe helper
   needs no bridge-state type and can live in `NugetMarshal`, which every module with an interface
   return already has, including modules whose interfaces did not plan a factory); and the
   `ConditionalWeakTable` reuse is dropped for the transfer-handle reason above, so "pass the same
   Dog to two Kotlin sinks, Kotlin sees one object" is **not** shipped. The C#-side round trip
   (`Assert.Same(dog, oreo.ClosestFriend())`, repeated crossings, mixed C#/Kotlin stores) is.

### Tests that change

- `BidirectionalTests.CSharpDog_ImplementsIPet`: un-skipped and extended to actually cross the
  bridge (its current body never touches Kotlin).
- `BidirectionalTests.Cat_Befriend_CSharpImplementedPet_ThrowsNotSupportedException`: inverted;
  the pinned v1 boundary it documents is removed by this ADR.
- `InterfaceReturnTests`: gains identity assertions in Commit 3.

### Breaking changes

None to existing consumers. `NugetMarshal.HandleOf` stops throwing for C#-implemented interfaces
(that exception was a documented boundary, not an API promise). New exports extend the ADR-055
contract table.

### Latent gaps observed while researching (not fixed here)

- The ADR-039 route (`InterfaceBridgeExports.kt` and `translateInterfaceBridgeMethod`) silently
  includes non-Unit-returning methods in its slot list but hardcodes `-> Unit` CFunctions and
  `void` delegates; a subscription interface with a returning method produces generated Kotlin
  that fails to compile (`override` return mismatch) with no KSP diagnostic naming the cause.
- The same route ignores interface properties entirely (`getAllFunctions` only), so a
  property-bearing subscription interface also produces non-compiling generated Kotlin, again
  with no diagnostic. Both should either be fixed by converging on this ADR's full-member slot
  planner or be given ADR-064-style diagnostics.
