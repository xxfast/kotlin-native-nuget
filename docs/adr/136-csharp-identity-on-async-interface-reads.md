# ADR-136: Resolve a C#-implemented interface back to its original instance on the suspend and Flow reads too

## Status
Accepted

## Context

[ADR-084](084-csharp-implemented-interfaces.md) facet 5 gives the synchronous plan route C#-side
round-trip identity: a `Dog : IPet` stored by Kotlin and returned through `oreo.ClosestFriend()` or
`oreo.Friend` comes back as the same `Dog` (`Assert.Same` holds,
`IntegrationTests/BidirectionalTests.cs:106-129`). The read is
`interfaceReturnExpression` (`forward/ForwardCirPlanProjection.kt:1369`):

```csharp
return (NugetMarshal.TryResolveCSharp(nativeResult, out IPet csharpOriginal) ? csharpOriginal : new Pet(nativeResult));
```

`TryResolveCSharp` (`cir/CirMarshalRenderer.kt:534-547`) probes `nuget_csharp_token`, and when the
handle's target is a `NugetCSharpBridge` it disposes the fresh transfer handle and returns the
original managed object out of the token `GCHandle`.

[ADR-133](133-nested-types.md)'s 2026-09-13 amendment fixed the suspend and Flow reads of an
interface to spell the public type as the interface, but wrote the read as a bare wrapper
construction. Every non-synchronous read of an interface today (all **verified by reading**, `main`
at `112475a1`, 2026-09-14):

| Read site | Generator | Emitted C# | Resolves? |
|---|---|---|---|
| Sync plan return / nullable return | `ForwardCirPlanProjection.kt:831-841`, `:929-943` | `TryResolveCSharp(...) ? csharpOriginal : new Pet(nativeResult)` | **yes** |
| Sync plan property getter | `ForwardCirPropertyProjection.kt:275-277`, `:310-311` | same expression | **yes** |
| Top-level `suspend fun f(): Pet` | `CirFunctionTranslator.kt:711-722` → `legacyInterfaceRead("resultPtr")` (`ForwardLegacyRouteCollections.kt:416-418`) → `CirConcurrencyRenderer.kt:164` `t.SetResult(<read>)` | `new Pet(resultPtr)` (nullable: `resultPtr == IntPtr.Zero ? null : new Pet(resultPtr)`) | **no, fresh wrapper** |
| Class member `suspend fun`, same interface | `CirClassTranslator.kt:1643-1658` → same `legacyInterfaceRead` | same | **no, fresh wrapper** |
| `Flow<Pet>` / `StateFlow<Pet>` element | `CirClassTranslator.kt:1169-1184`, `:1364-1372` → `legacyInterfaceElementReadArgument` (`ForwardLegacyRouteCollections.kt:455-460`) → `KotlinFlow<T>._read` (`CirFlowRenderer.kt:117`, `:222`) | `read: static h => new Pet(h)` | **no, fresh wrapper** |
| `List<Pet>` / `Set<Pet>` / `Map<_, Pet>` element | `ForwardCallablePlanner.kt:3419` `isBridgeableComponent` has no `Interface` arm | nothing emitted; `SKIPPED_UNSUPPORTED_TYPE`, pinned by `Tier1InterfaceReturnTest.kt:224-253` | **refused** |
| Sealed read | `CirSealedRenderer` `FromHandle` reads a Kotlin discriminator (`SealedClassExports.kt:42-46`) | `Base.FromHandle(handle)` | **not an interface read**: a sealed arm is a Kotlin class, no C# object can be behind the handle. No sealed arm in `test-library` carries an interface-typed property; if one did, the arm's property getter is the sync property read above, which resolves |

So the asymmetry the ROADMAP names is real, and it is exactly two functions wide:
`legacyInterfaceRead` and `legacyInterfaceElementReadArgument`. The same `Dog` returned through
`Task<IPet>` or emitted by a `Flow<IPet>` today arrives as a second `Pet` wrapper over its own
bridge, so `ReferenceEquals(dog, await cat.ClosestFriendLaterAsync())` is false and every member
call on it round-trips through two bridges.

### Why the token probe already works on those handles (verified by reading)

The probe needs the returned pointer to be a `StableRef` whose target is the bridge object itself,
because `nuget_csharp_token` does `handle.asStableRef<Any>().get() as? NugetCSharpBridge`
(`nuget-runtime/.../NugetRuntime.kt:596-598`). All three routes mint that shape:

- Sync plan route: `NugetHandles.retain(result)` via `nullableHandleResultBody`
  (`ForwardKotlinPlanEmitter.kt:697-701`); this is the route the shipped `Assert.Same` tests prove.
- Suspend route: `NugetHandles.retain(result)` / `if (result == null) null else NugetHandles.retain(result)`
  (`SuspendFunctionExports.kt:222-224`, `legacyBoxedResult` leaves a non-collection result bare, `:234-236`).
- Flow route: `NugetHandles.retain(value as Any)` per emission (`FlowExports.kt:439-441`).

`NugetHandles.retain` is `StableRef.create(value).asCPointer()` plus the ADR-120 live counter
(`NugetRuntime.kt:51-54`). Same pointer shape, same target, so the probe that resolves the sync
return resolves these. The C# side already re-enters Kotlin from inside `_read` (the default
`FromHandle<T>` calls `nuget_unwrap_*` and `Native_dispose` there), so calling
`Native_csharp_token` and `Native_dispose` from the same place adds no new re-entry shape.

## Alternatives Considered

### 1. Route the two legacy reads through the plan route's expression (chosen)

Give `interfaceReturnExpression` a handle-name parameter (defaulting to `nativeResult`, so the
three existing callers and the `ForwardInterfaceCirProjectionTest` /
`Tier1InterfaceBridgeFactoryTest` pins are untouched) and have `legacyInterfaceRead` and
`legacyInterfaceElementReadArgument` call it instead of spelling `new ${backingType}(h)`.

- Pro: one resolve-then-wrap expression in the whole generator; the two legacy reads become
  byte-for-byte the sync read with a different handle local. Two generator functions change, no
  runtime, no Kotlin emission, no ABI, no contract hash.
- Con: still a per-site expression rather than a `NugetMarshal` primitive; a future fourth read
  site (a `StateFlow<Interface>` suspend return, a collection of interfaces if ever admitted) has
  to remember to use it. That is the shape every interface read already has today, so this ADR
  does not make it worse.

### 2. Teach `NugetMarshal.FromHandle<T>` / `Materialize<T>` to resolve

Register the interface in `Factories` under `typeof(IPet)` with a lambda that probes and falls
back to the wrapper, so the default `_read` and `FromHandle<TResult>` paths resolve for free.

- Pro: no per-member `read:` argument needed for an interface element; the generic-function
  route (`CirFunctionRenderer.kt:286`, `FromHandle<TResult>`) would resolve too.
- Con: `factoryEntries` (`CirTranslator.kt:829-850`) deliberately registers nothing for an
  interface, and every ADR-094/123 design leaves `FromHandle<T>` collection-and-interface-blind on
  purpose (the per-member `read:` delegate exists precisely so the shared `KotlinFlow<T>` never
  specialises). Widening the factory table touches the AOT/trimming table and the reflection-free
  guarantee for a two-function fix. Rejected as disproportionate; can be revisited if a generic
  route ever needs it.

### 3. Leave the asymmetry, document it

The state of `main`, already documented in ADR-040, ADR-084 and ADR-133 amendments and
`FEATURES.md:169`.

- Con: violates [GOALS.md](../../GOALS.md) "feels like C#". A consumer who stores an object and
  gets it back over `await` expects their object back, exactly as they do from a plain return.
  The double-bridge also means the consumer must `Dispose()` a wrapper over their own object.

## Decision

Alternative 1.

### Generated C#, after

Top-level or member suspend return (`CirConcurrencyRenderer.kt:164`, `asyncResultRead`):

```csharp
t.SetResult((NugetMarshal.TryResolveCSharp(resultPtr, out global::TestLibrary.Cat.IPet csharpOriginal) ? csharpOriginal : new global::TestLibrary.Cat.Pet(resultPtr)));
```

Nullable suspend return:

```csharp
t.SetResult(resultPtr == IntPtr.Zero ? null : (NugetMarshal.TryResolveCSharp(resultPtr, out global::TestLibrary.Cat.IPet csharpOriginal) ? csharpOriginal : new global::TestLibrary.Cat.Pet(resultPtr)));
```

Flow / StateFlow element (`read:` argument to `KotlinFlow<T>` / `KotlinStateFlow<T>`):

```csharp
read: static h => (NugetMarshal.TryResolveCSharp(h, out global::TestLibrary.Cat.IPet csharpOriginal) ? csharpOriginal : new global::TestLibrary.Cat.Pet(h))
```

**Inferred (not compiled yet):** the nullable-element form
`static h => h == IntPtr.Zero ? null : (TryResolveCSharp(...) ? csharpOriginal : new Pet(h))`
compiles against `Func<IntPtr, IPet?>`. The same conditional shape already compiles on the sync
nullable return (`docs/topics/interfaces-abstract-sealed.md:3437`), and the inner conditional's
natural type is `IPet` (the wrapper converts implicitly to the interface, not the reverse), so the
only new element is being the body of a `static` lambda; `out` declarations are legal in lambda
bodies. If this is wrong it fails at `GeneratedBindingsCheck`, not silently.

### Ownership

Unchanged from ADR-084 facet 5. `TryResolveCSharp` disposes the transfer handle it resolved (the
original is reached through the token, not the handle), so the handle Kotlin minted per
completion or per emission is freed on the read. When the target is Kotlin-backed the wrapper takes
ownership as today. Net handle count returns to baseline either way; a `LeakTests/LiveHandleTests.cs`
row per route pins that, since the freeing site moves from the consumer's `using` to the read.

### Consumer contract

```csharp
[Fact]
public async Task StoredCSharpPet_RoundTripsToTheOriginalInstance_OverTask()
{
    using var oreo = new Cat("Oreo", 9);
    using IPet dog = new Dog("Rex");

    oreo.Befriend(dog);

    IPet later = await oreo.ClosestFriendLaterAsync();
    Assert.Same(dog, later);
    Assert.Equal("Woof!", later.Speak());
}

[Fact]
public async Task StoredCSharpPet_RoundTripsToTheOriginalInstance_OverFlow()
{
    using var oreo = new Cat("Oreo", 9);
    using IPet dog = new Dog("Rex");

    oreo.Befriend(dog);

    var seen = new List<IPet>();
    await foreach (IPet pet in oreo.Friends()) seen.Add(pet);

    Assert.Same(dog, Assert.Single(seen));
}
```

Same posture as ADR-084: C#-side identity only. Kotlin-side `===` across two crossings of the same
`Dog` is still not preserved (one bridge per crossing), and two reads of a Kotlin-backed `Pet`
still produce two wrappers (ADR-005).

### Fixture

`test-library/.../cat/Cat.kt` gains `suspend fun closestFriendLater(): Pet = closestFriend()` and
`fun friends(): Flow<Pet> = flowOf(friend ?: this)`, beside the existing `befriend`/`closestFriend`
pair, so the new facts sit next to `StoredCSharpPet_RoundTripsToTheOriginalInstance` in
`IntegrationTests/BidirectionalTests.cs` and reuse its `Dog`. `Aviary.Keeper` (`NestedTypesTests.cs`,
`CountingKeeper`) already has both a suspend and a Flow return; it can carry the nested twin if
wanted, but the top-level `Pet` pair is the minimum.

## Consequences

- Files an implementation touches: `forward/ForwardCirPlanProjection.kt` (handle parameter on
  `interfaceReturnExpression`), `forward/ForwardLegacyRouteCollections.kt` (`legacyInterfaceRead`,
  `legacyInterfaceElementReadArgument`), `test-library/.../cat/Cat.kt`,
  `IntegrationTests/BidirectionalTests.cs`, `LeakTests/LiveHandleTests.cs`, plus a Tier 1 pin on the
  emitted read text (none exists today: `Tier1NestedTypesTest.kt:660-700` pins the `Task<...IKeeper>`
  signature only). Docs: `FEATURES.md:169`, `docs/topics/coroutines-and-flow.md:262-264`, and the
  "identity asymmetry" amendment paragraphs in ADR-040 (`:1024`), ADR-084 (`:524`) and ADR-133
  (`:302`) become historical.
- No Kotlin emission, runtime export, ABI or contract-hash change. `nuget_csharp_token` is already
  in `ForwardAbiContract.kt:493`.
- Behaviour change on shipped members: a `Task<IFoo>` or `Flow<IFoo>` that used to hand back a
  wrapper over a C# object now hands back the object. A consumer that disposed that wrapper now
  disposes their own object's `Dispose()` instead, which is what the sync route already does.
- Deferred: `List<Interface>` stays refused (ADR-040 scope, unchanged); a `suspend fun` returning
  `StateFlow<Interface>` is a separate ROADMAP line (still bare-spelled, ADR-133 amendment) and
  gets the resolving read for free once it takes the `flowElementRead` path; the generic-function
  route's `FromHandle<TResult>` (`CirFunctionRenderer.kt:286`) does not resolve and has no
  interface fixture, left with Alternative 2.
