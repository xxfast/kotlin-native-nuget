# ADR-159: One coroutine scope per instance, owned by the first async level in a chain

## Status

Accepted

## Context

Restatement (the contract): forward, Kotlin declares. A C# consumer of
`class NapLounge : SunShelf() { suspend fun rest(): String }` gets
`public class NapLounge : SunShelf, IAsyncDisposable` with a working `RestAsync()`, `Dispose()` and
`await using`, wherever in the inheritance chain the first async member appears; a class whose only
async member was refused gets no `IAsyncDisposable`; two async overloads that render one C#
signature fail with `ERROR_CSHARP_SIGNATURE_COLLISION` instead of `CS0111`.

Before this ADR, `CirClassRenderer`'s scope emission (`_scopeHandle`, `GetOrCreateScope()`,
`IAsyncDisposable`, `DisposeAsync`) fired only on a base-less class (`cls.superClass == null`),
while the flag driving `Dispose()`'s scope-aware cleanup (`hasSuspendMethods`) came off a raw
`cls.getAllFunctions().any { SUSPEND }` scan that ran regardless of a superclass. The two facts
disagreed the moment a class had both an ancestor and its own async member, and every hierarchy
shape below reached a different failure:

- The first async member on a **derived** class, base has none: the derived class rendered the
  scope-aware `Dispose()`/`DisposeAsync()` but never the `_scopeHandle` field or
  `GetOrCreateScope()` it calls. `error CS0103` three times per async body plus once each in
  `Dispose()`/`DisposeAsync()`.
- The first async member on the **base**, derived class declares none: the raw scan sees the
  inherited member, so the derived class renders a second, non-`override` `DisposeAsync()` next to
  the inherited one. `error CS0108`.
- Both levels declare async members: the derived class's async body calls `GetOrCreateScope()`,
  which was `private` on the base. `error CS0122`, plus the CS0108 above.
- An **abstract** owner: `Dispose()` renders `public abstract void Dispose();` with no
  `DisposeAsync` counterpart, because the `DisposeAsync` block sat in the same branch as the
  concrete `Dispose()` body it has no `Native_Dispose` import to fill. `error CS0535`.
- The **Flow** twin of the base-owns-it shape (base has a `Flow`-returning method, derived declares
  nothing): this one compiled, silently wrong. The derived class's `override Dispose()` **replaces**
  the base's body rather than chaining into it, so the inherited scope's cancel/dispose block never
  ran for a derived instance's sync `Dispose()`. `DisposeAsync()` is inherited and does drain it, so
  only the sync path leaked.
- A class whose **only** async member was refused (an unmarshallable parameter or return): the raw
  scan still saw the declaration and rendered `IAsyncDisposable`/`DisposeAsync`, with no
  `_scopeHandle` block for `Dispose()` to run and (in a coroutine-free module) no `using
  System.Threading.Tasks` either, `error CS0246`/`CS0738`.
- Two `suspend` overloads that render one C# signature (`play(String)` / `play(String?)`, ADR-034's
  key strips reference nullability): the collision guard (`emitCsharpSignatureCollisions`) ran
  before the async and Flow-route methods were assembled onto the class, so it never saw them.
  `error CS0111` in the generated file instead of a build-time diagnostic.
- An `override suspend fun` over a base's `open suspend fun`: the derived class re-projected the
  override as its own C# method beside the inherited one. `error CS0108`, even though Kotlin's own
  dynamic dispatch already reaches the override through the base's export
  (`obj.fill()` on `handle.asStableRef<Base>().get()`).

`ROADMAP.md` carried this as three related lines (the base-less-only emission, the raw
`hasSuspendMethods` scan, and the collision guard gap), all discovered alongside
[ADR-136](136-csharp-identity-on-async-interface-reads.md), plus one line for the ADR-040 interface
backing wrapper never deriving the flag at all.

## Decision

One coroutine scope per instance, owned by the **root-most class in the kept chain that projects a
scope-using member** (a suspend method, a Flow-returning method, or a Flow/StateFlow property).
Every class below the owner reuses that one scope and inherits `DisposeAsync`; a class whose only
async member was refused, or that declares no scope-using member at all, owns nothing.

"Projects" means what reached the generated artifacts, not what the author declared:
`ForwardScopeOwnership.kt`'s `forwardScopeOwner` applies every refusal the async and Flow routes
already apply (an unmarshallable parameter, an unmarshallable return, a generic owner, a refused
opt-in) before asking whether a class in the chain has a scope-using member, and it is the single
selector both the C# renderer and the Kotlin export builder read, closing the disagreement between
them (see Consequences).

```kotlin
open class SunShelf(val spot: String) {
  fun describeLounge(): String = "$spot is warm"
}

class NapLounge(spot: String) : SunShelf(spot) {
  suspend fun rest(cat: String): String {
    delay(10.milliseconds)
    return "$cat napped on $spot"
  }

  fun nappers(): Flow<String> = flowOf("Oreo", "Mylo")
}
```

```C#
public class NapLounge : SunShelf, IAsyncDisposable
{
    internal IntPtr _scopeHandle;

    internal IntPtr GetOrCreateScope() { /* lazy, Interlocked.CompareExchange */ }

    public Task<string> RestAsync(string cat, CancellationToken cancellationToken = default) { ... }
    public KotlinFlow<string> Nappers() { ... }

    public override void Dispose() { /* cancels and disposes _scopeHandle, then Native_Dispose */ }
    public ValueTask DisposeAsync() { /* drains, then Native_Dispose */ }
}
```

`SunShelf` gains nothing: no `_scopeHandle`, no `IAsyncDisposable`. `await using var lounge = new
NapLounge(...)` compiles because the owner, not the base, is `IAsyncDisposable`.

### The base owns it, the subclass reuses it

```kotlin
open class Feeder(val bowls: Int) {
  open suspend fun fill(): String { delay(10.milliseconds); return "$bowls bowls filled" }
}

class TimedFeeder(bowls: Int, val hour: Int) : Feeder(bowls) {
  override suspend fun fill(): String { delay(10.milliseconds); return "$bowls bowls filled at $hour" }
  suspend fun schedule(): Int { delay(10.milliseconds); return hour }
}
```

`Feeder` declares `_scopeHandle`, `GetOrCreateScope()`, `DisposeAsync()` and
`: IDisposable, IAsyncDisposable, INugetHandle`. `TimedFeeder : Feeder` declares none of those a
second time; its own `override void Dispose()` still cancels and disposes the inherited scope
(rule 3 below), and it inherits `DisposeAsync()` unchanged, correct for a derived instance because
every class's `_dispose` export is `NugetHandles.release(handle)`, type-agnostic. `fill()` is **not**
re-projected as a second `FillAsync` on `TimedFeeder`: `Feeder.FillAsync` is the only C# method,
and a `Feeder`-typed reference calling it reaches `TimedFeeder.fill` through Kotlin's own dynamic
dispatch, because the base's export calls `fill()` on `handle.asStableRef<Feeder>().get()`.

### Abstract owner

```kotlin
abstract class Brusher {
  suspend fun groom(cat: String): String { delay(10.milliseconds); return "$cat groomed with ${tool()}" }
  abstract fun tool(): String
}
class MittBrusher : Brusher() { override fun tool(): String = "a brush" }
```

```C#
public abstract class Brusher : IDisposable, IAsyncDisposable, INugetHandle
{
    public abstract void Dispose();
    public abstract ValueTask DisposeAsync();
}
public class MittBrusher : Brusher
{
    public override void Dispose() { ... }
    public override ValueTask DisposeAsync() { ... }
}
```

An abstract owner has no `Native_Dispose` import of its own to drain into, so it can only *declare*
`DisposeAsync`; each concrete class below it carries the full body as `override`, over its own
`Native_Dispose`. `DisposeAsync` follows `Dispose`'s existing abstract/override spelling, which is
the rule that makes `IAsyncDisposable` satisfiable on an abstract owner at all (`CS0535` otherwise).

### The negative control

```kotlin
class NapRegistry {
  suspend fun pair(entry: Pair<String, Int>): Int = entry.second // refused: Pair is unmarshallable
  fun cushions(): Int = 3
}
```

`NapRegistry` renders `: IDisposable, INugetHandle` only, no scope, no `IAsyncDisposable`, and no
`PairAsync` at all: the member is a named `SKIPPED_UNSUPPORTED_INPUT` skip. A raw scan of the
declarations would have handed this class a scope nothing ever creates.

## Rules

1. **Root-most, not nearest.** A scope per level would hide the ancestor's field (`CS0108`) and
   drain twice; one scope, owned once, reused below, matches ADR-021's "one `CoroutineScope` per
   instance" and needs no `DisposeAsyncCore`-per-level pattern, because there is one native handle
   and at most one scope per instance, never per-level resources.
2. **A non-owner's `Dispose()` still repeats the scope cleanup block** (cancel then dispose
   `_scopeHandle`), because a derived `override Dispose()` replaces the base's body rather than
   chaining into it (unchanged from before this ADR: `base.Dispose()` chaining was rejected as a
   larger blast radius for the same result, since the base's own `Interlocked.Exchange(ref
   _handle)` would see a zero handle and return early). `DisposeAsync` is inherited unchanged and
   needs no repeating.
3. **`GetOrCreateScope()` widens from `private` to `internal`**, matching `_scopeHandle`'s existing
   `internal`: a class below the owner calls it unqualified from its own async/Flow bodies, which
   was `CS0122` under `private`.
4. **`override suspend fun` is not re-projected** on either the Kotlin or the C# half. The base's
   export already dispatches to the override dynamically; re-projecting it produced a colliding
   second C# method (`CS0108`) and a stray Kotlin export the ABI contract could not see (filtered
   out of the comparison, not flagged). Kept only when the overridden member sits on a *dropped*
   (ADR-101, unexported) base, which has no C# class of its own to carry it.
5. **The ADR-034 signature-collision guard now runs over the async and Flow-route methods.** It used
   to run before they were assembled onto the class, so two `suspend` overloads that render one C#
   signature (reference nullability stripped from the key) reached the generated file as `CS0111`
   instead of failing the round with `ERROR_CSHARP_SIGNATURE_COLLISION`.
6. **`using System.Threading` is unconditional.** Every class's `Dispose()` calls
   `Interlocked.Exchange(ref _handle, ...)` regardless of scope ownership, so a coroutine-free
   module (one ordinary ADR-159-unrelated class, no async member anywhere) did not compile under the
   ADR-138 pack-time gate, which has no implicit usings: `error CS0103: The name 'Interlocked' does
   not exist`. Fixed in the same change because the refused-only class above lands on exactly this
   error once its flag is correctly `false`.

## Alternatives Considered

- **Patch only the base-less-derived-owns-it shape.** Leaves the base-owns-it, both-declare, and
  abstract-owner shapes open, and a later fix to the raw `hasSuspendMethods` scan alone would
  introduce the Flow-leak trap below.
- **Always put the scope on the root of the chain**, whether or not the root projects anything. Hands
  `IAsyncDisposable` and a scope to a base with no async member, the exact shape this ADR removes,
  and the one ADR-118/124 already rejected for a sealed base.
- **A scope per level.** Two `_scopeHandle` fields hide each other (`CS0108`), double drain, no
  benefit over one owner.
- **Full `DisposeAsyncCore` pattern** (each level releases its own resources through a `protected
  virtual` core method). Solves a problem this design does not have (per-level resources): there is
  one native handle and at most one scope per instance, owned at exactly one level.
- **A `Native_Dispose` import on an abstract class, hosting the concrete `DisposeAsync` body there
  instead of `override` per concrete class.** Rejected as putting a native import on an abstract
  class, though it is a closer call than it looks: the Kotlin ABI is unchanged either way, since
  `${prefix}_dispose` is already exported for an abstract class today.
- **Fixing the naive version of the flag** (`hasSuspendMethods` derived from projected members
  alone, with no `ownsScope` split) makes the base-owns-it shape *worse*: the derived class renders
  a scope-agnostic `override Dispose()` that silently drops the inherited scope's cleanup, a leak
  instead of a compile error. This is why the flag splits into `hasSuspendMethods` ("a scope exists
  on this instance") and `ownsScope` ("this class declared it").

## Consequences

- `CirClass` gains `ownsScope` and `overridesDisposeAsync` beside the existing `hasSuspendMethods`
  (renamed in spirit to "has a scope somewhere in the chain", kept as the same field to limit
  churn). `ForwardScopeOwnership.kt` (`forwardScopeOwner`, `forwardSuspendRouteMethods`,
  `forwardClassFlowMethods`/`forwardClassFlowProperties`, `forwardDeclaresScopeMember`) is the one
  place both halves read.
- The Kotlin export builder (`addSuspendClassMethodExports`) and the C# projection used to
  disagree: the Kotlin half filtered on strictly less than the C# half, so a base-owns-it shape
  exported a stray `timedfeeder_fill_async` with no C# import for it (invisible to
  `ForwardAbiContract`, which filters Kotlin exports down to the C# import set). Both now read the
  same selector.
- The ADR-040 interface backing wrapper (`translateInterfaceBackingClass`) now derives its flag
  explicitly rather than defaulting it: still `hasSuspendMethods = false` / `ownsScope = false`,
  because `ForwardCallablePlanner.interfaceEntries` skips every `suspend` interface member and no
  Flow route runs for an interface, so the wrapper genuinely owns no scope today. Left explicit
  (not defaulted) so the day interface async members are admitted, the line to change reads
  `forwardScopeOwner` like every other class. Admitting those members is a separate, deferred item.
- `LiveHandleTests` (`LeakTests/LiveHandleTests.cs`) gains three rows for the superclass-owned
  shape: `SuperclassOwnedScope_SyncDisposeThroughTheSubclass_ReturnsToBaseline` (Row 13, the shape
  that compiled and leaked before this ADR: collect the base's Flow through a derived instance, then
  sync `Dispose()` through the subclass, handle count back to baseline), and
  `SubclassOwnedScope_AfterAnAsyncCall_ReturnsToBaseline` (Row 13a, the mirror image: the subclass
  is the owner, measured after a real async call). `SuperclassOwnedScope_NoSuspensionPoint_TightLoop_ReturnsToBaseline`
  (Row 13b) is the tight-loop row for the same ADR-019 race one level up the inherited scope: a
  `suspend fun` with no suspension point can complete before the P/Invoke that launched it returns
  the job handle, and the scope is warmed once outside the measured window because
  `GetOrCreateScope()` is lazy and its `StableRef` is counted.
- A coroutine-free module (no suspend/Flow member anywhere) now compiles under the ADR-138 pack-time
  gate; before this ADR it failed with `CS0103: Interlocked` because `System.Threading` was gated on
  `tracker.needsAsync`/`needsSubscription` while every class's `Dispose()` already called
  `Interlocked.Exchange`.

## Cross-references

- [ADR-021](021-structured-concurrency.md) deferred "sealed class / abstract class hierarchy scope
  management"; this ADR closes the ordinary-class and abstract-owner half of that for the *forward*
  direction (a sealed arm's own scope ownership was already ADR-118/ADR-124).
- [ADR-101](101-unexported-supertype-skip.md)'s "a derived class inherits `_handle`, `INugetHandle`
  and `IDisposable` from the base" reasoning is unchanged for those three; `IAsyncDisposable` is the
  one member of that set that does **not** always sit on the base, which is the gap this ADR closes.
- [ADR-118](118-suspend-route-sealed-arm-owners-and-overload-numbering.md)'s sealed-arm scope model
  (`hasSuspendMethods` derived from `asyncMembers.isNotEmpty() || flowMembers.isNotEmpty() ||
  properties.any { it.isFlow }`, declared-only) is the shape this ADR generalizes to an ordinary
  class's inheritance chain; an arm still answers through its own declared-only selectors
  (`forwardArmFlowMethods`/`forwardArmFlowProperties`), and an **open** arm that is itself the base
  of an ordinary class is walked by the same `forwardScopeOwner`.
- [ADR-034](034-secondary-constructor-exceptions.md)'s `ERROR_CSHARP_SIGNATURE_COLLISION` guard now
  runs over async and Flow-route methods on both the ordinary-class and sealed-arm routes (rule 5).

## Files

- `nuget-processor/src/main/kotlin/.../forward/ForwardScopeOwnership.kt` (new): the shared selector.
- `.../cir/CirModel.kt`: `CirClass.ownsScope`, `CirClass.overridesDisposeAsync`.
- `.../cir/CirClassTranslator.kt`: `allSuspendMethods` reads `forwardSuspendRouteMethods` instead of
  partitioning a raw scan; `scopeOwner` computed once per class; the collision guard call moved
  below async/Flow-route assembly on both the ordinary-class and sealed-arm routes.
- `.../cir/CirClassRenderer.kt`: `IAsyncDisposable` keyed on `ownsScope` on both the base-less and
  has-a-superclass arms; the scope field/`GetOrCreateScope()` render on a derived owner too;
  `GetOrCreateScope()` widened to `internal`; `renderDispose` takes `ownsScope`/`overridesDisposeAsync`.
- `.../cir/CirTranslator.kt`: `System.Threading` unconditional.
- `.../exports/SuspendFunctionExports.kt`, `.../NugetProcessor.kt`: read the shared selector instead
  of their own raw scans.
- Fixture: `test-library/.../test/lounge/NapLounge.kt`. Tests: `IntegrationTests/SubclassAsyncTests.cs`,
  `Tier1SubclassScopeOwnerTest.kt`, `Tier1RefusedSuspendOrdinaryClassTest.kt`,
  `Tier1CoroutineFreeModuleTest.kt`, a collision cell in `Tier1SuspendMethodOverloadTest.kt`.
  `LeakTests/LiveHandleTests.cs` rows 13, 13a, 13b.
