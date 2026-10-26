# ADR-230: Forward, a `Flow` family member binds on every owner without a C# instance scope

## Status
Accepted

Amends [ADR-026](026-flow-mapping.md) (the Flow route was instance-keyed: a top-level, `object` or
companion `Flow` return was a named skip), [ADR-065](065-stateflow-mapping.md),
[ADR-071](071-mutable-stateflow-mapping.md) and [ADR-205](205-shared-flow-mapping.md) (the same
owners for the hot kinds), [ADR-215](215-type-parameter-in-lambda-and-flow.md) (a static
`fun <T>` returning a `Flow` kept the `GENERIC` refusal) and
[ADR-229](229-suspend-on-static-owners.md) (a flow result of a static `suspend` member).

## Context

A `Flow`, `StateFlow`, `SharedFlow`, `MutableStateFlow` or `MutableSharedFlow` return or property
bound only where a C# owner could hold the coroutine scope the collection runs on: a class, an
interface, a sealed base or arm. The collect export took the owner's handle and a scope handle from
`GetOrCreateScope()`, and `DisposeAsync()` on the owner cancelled every live collection. Every other
owner dropped the member as `SKIPPED_UNSUPPORTED_RETURN` or `SKIPPED_UNSUPPORTED_PROPERTY`: the file
facade, an `object`, a default-named or named companion, an enum body and enum companion, and a
value class.

None of those owners has anything to dispose. The shipped awaited static flow (a top-level
`suspend fun` returning `Flow`) and the runtime's `nuget_stateflow_collect` already collect on an
ad-hoc root scope when the scope handle is null, and the enumerator's own job handle is already the
cancellation handle for a collection.

## Decision

The instance flow route generalises over a receiver expression with no handle and no scope slot.
A `staticFlowRouteMembers` selector is read by the export emitter, the C# translator and the
diagnostic walk, so the three cannot disagree.

- A cold `Flow<T>`, `StateFlow<T>` or `SharedFlow<T>` return or property exports a `_collect` that
  calls `collectForCSharp(CoroutineScope(Dispatchers.Default), ...)`. The Kotlin member is
  re-invoked on every collection, as on a class.
- A `MutableStateFlow<T>` or `MutableSharedFlow<T>` return or property is held: one acquire export
  returns the flow's own handle and the existing flow-keyed collect, `.Value` write, `EmitAsync` and
  `TryEmit` seams take `NugetKotlinHandle.Null` as the scope. The holder owns that handle.
- A `suspend` member with a flow result keeps the ADR-229 route and now awaits to the same wrapper,
  so an awaited `MutableStateFlow` is settable on every static owner, the top level included.
- A member's own `T` binds through ADR-215's trampoline with no owner, so `fun <T> echo(...): Flow<T>`
  is a `KotlinFlow<T>` method on an `object`, companion, top level, enum body and value class.

| Kotlin owner | Kotlin call | C# placement | Flow `var` setter |
|---|---|---|---|
| top level | package-qualified | static member of the file facade | `set` accessor |
| `object` | `Clock.ticks()` | static member of `public static class Clock` | `set` accessor |
| companion, default or named | owner-qualified, `Hourglass.Sand.grains()` | static member of the owning class | `set` accessor |
| enum body member | `Chime.entries[ordinal].play()` | `this` extension member in `{Enum}Extensions` | `Set<P>(this E, value)` method in `{Enum}Extensions` |
| enum companion member | owner-qualified | static member of `{Enum}Extensions` | `set` accessor |
| non-generic value class member | receiver rebuilt from the value class's wire slot | instance member of the record struct | none, get-only |

An enum body's flow property renders as a C# 14 `extension(Chime receiver)` property, since an
extension method cannot be a property.

Each collection runs on its own root scope, and only its enumerator ends it, by completion, the
token, `break` or `DisposeAsync()`. No owner is disposed, so nothing else cancels it. A `var` flow
property binds its setter on every owner in the table, as on a class
([ADR-026](026-flow-mapping.md) 2026-10-12 amendment; see the 2026-10-26 amendment below).

### Diagnostic names of a named companion

A named companion's skipped members were reported as `Owner.Companion.x` in
`NugetDiagnostics.json` and the planner's lookups, whatever the companion is called. They now read
`Owner.<Name>.x`, the name the author wrote. The two lookups that selected a class's companion members
by that prefix were keyed on the owner alone.

Alternatives rejected:

- A `KotlinFlow` that owns a `NugetScopeHandle` (ADR-208 part E), so `Dispose()` cancels its
  enumerations: no other static surface owns a scope, and it would mint a handle per call even when
  the flow is never enumerated.
- A process-wide library scope: nothing could cancel it.

## Consequences

- A cold static flow holds no handle until it is enumerated, so it needs no disposal. A returned or
  read `MutableStateFlow` or `MutableSharedFlow` holder owns the flow's handle and is disposed.
- The new exports are scraped into the ABI contract with the other legacy routes.
- `TreatPantry.level`, which pinned a `StateFlow` on an `object` as a named skip, binds; the
  fixture's named skip is a `Sequence` property instead.

## Verification

- Forward fixture `purrclock/` (`Chimes.kt` for the top level, `PurrClock`, `Cuckoo`, `Hourglass`;
  `Pendulums.kt` for `Chime` and `Pendulum`) with `IntegrationTests/StaticFlowTests.cs`: each owner,
  each flow kind as a function and a property, a generic `T`, a class element, a fault, a token
  cancellation, a `break`, shared storage between a held return and its property, and a `suspend`
  member with a flow result.
- Tier 1: `Tier1StaticFlowTest`, plus the cells in `Tier1UnroutedPositionsTest`,
  `Tier1FlowVarPropertySetterTest`, `Tier1ObjectPropertyTest`, `Tier1SkipRemarksTest` and
  `Tier1SuspendMutableStateFlowTest` that used to pin the named skips.
- Leak rows in `LeakTests/LiveHandleTests.cs`: `StaticFlow_EveryOwner_Completion_ReturnsToBaseline`,
  `StaticFlow_TightLoop_SynchronousCompletion_ReturnsToBaseline`, `StaticFlow_Fault_ReturnsToBaseline`,
  `StaticFlow_Cancellation_ReturnsToBaseline` and `StaticFlow_HotHolders_ReturnToBaseline`.
- Collectability rows in `LeakTests/CollectabilityTests.cs`:
  `StaticFlow_CollectedToCompletion_TheFlowAndTokenAreCollected` and
  `StaticFlow_CancelledByTheToken_TheFlowAndTokenAreCollected`.

## Amendment (2026-10-22): nullable flow members

A nullable flow return or property (`Flow<T>?`, `StateFlow<T>?`, `SharedFlow<T>?`) on any of these
owners binds as the nullable holder (`KotlinFlow<T>?`), `null` when Kotlin returns null, generic or
not. It reuses ADR-026's 2026-10-09 shape: a `_has_value` probe export with the member's own
parameters, then the ordinary lazy holder. The planner gives a nullable flow return the
`FLOW_PROTOCOL` skip on every static walk (it used to be a `NULLABLE` drop), and a value class's
nullable flow property the same, so the static flow route owns them. A `suspend` member with a
nullable flow result already bound through the acquired route.

Verified by the fixture `purrclock/Absences.kt` with `IntegrationTests/NullableStaticFlowTests.cs`
(a present and an absent member per owner, a property, a function, a generic `T`, a `StateFlow?`, a
`SharedFlow?` and a `suspend` result), the leak row
`NullableStaticFlow_PresentAndAbsent_ReturnToBaseline`, and the Tier 1 cell
`Tier1StaticFlowTest` "a nullable flow on every static owner binds as a nullable holder".
`Tier1NullableParameterDiagnosticTest` now names an unwired nullable return with a nullable suspend
lambda, since `Flow<Int>?` binds. The forward `NugetDiagnostics.json` named the named companion's
member `NapBlanket.Fold.creases` while it was still skipped.

## Amendment 2026-10-22: generic value classes

[ADR-228](228-generic-value-class-erased-positions.md) extends the value-class owner to a generic
value class (`Sticker<T>`, the phantom `Decal<T>`). Its `Flow` and `StateFlow` members and
properties bind on this route, over the class's own `T` or with the struct as the element, and a
`SharedFlow` property reads its replay cache. A `T : Enum<T>` receiver is read through the ADR-198
trampoline.

## Amendment 2026-10-23: nullable collection elements, and enum properties proved end to end

**Nullable collection elements.** A `MutableStateFlow`, `MutableSharedFlow` or plain `Flow` whose
element is a nullable `List`, `Set` or `Map` (`MutableStateFlow<List<Int>?>`,
`MutableSharedFlow<Set<String>?>`, `Flow<Map<String, Int>?>`) binds on a class, an `object`, the top
level, a held return and an awaited return. The member gets `Value` get and set, `CompareAndSet`,
`EmitAsync`, `TryEmit` and `ReplayCache`. `null` crosses as a null handle, as a nullable `ByteArray`
already did, a projected component (`List<Posture>?`) stays projected behind the null guard, and C#
checks `IntPtr.Zero`, so C# and Kotlin both tell `null` from an empty collection. The refusal was
`legacyFlowElementShape` never unwrapping `Nullable` over a collection; `ForwardLegacyFlowElementShape.Marshalled`
gained a `nullable` flag. A `StateFlow<List<Enum>>?` member skipped the collection
projection (`stateFlowNullableBox`) and now projects it. The two diagnostic hints that read
"non-nullable" now read "List/Set/Map, nullable or not". An awaited `MutableStateFlow<T?>` is
settable ([ADR-071](071-mutable-stateflow-mapping.md) 2026-10-23 amendment).

**Enum properties.** The enum body's flow property route was generated before this amendment; this
adds the proof. A flow property on an enum (a body `val`, a constructor `val`, an `abstract val`
overridden in each entry's body) of every flow kind, plain and hot, read-only and held, is an
extension property on `{Enum}Extensions` (`Tail.Oreo.Height`). Each entry keeps its own storage and
a held holder is settable. A C# 14 extension property is not reachable through a namespace alias,
so a call site imports the namespace or `using static`s the extensions class. The setter of a `var`
flow property on an enum was a named `SKIPPED_UNSUPPORTED_INPUT` here, as on an `object`, a companion and
a value class. The 2026-10-26 amendment binds it.

**Verification.** Fixtures `purrclock/SockDrawers.kt`, `purrclock/Tails.kt` and
`issue127/Issue127Sample.kt`. `IntegrationTests/NullableCollectionFlowTests.cs`,
`EnumFlowPropertyTests.cs`, `Issue127Tests.cs` (cell 7) and
`SuspendFlowTests.NullableCollectionElement_YieldsNull`. Leak rows
`NullableCollectionFlows_SettableHolders_ReturnToBaseline` and
`EnumFlowProperties_EveryKind_ReturnToBaseline` (which `using static`s `TailExtensions`). Tier 1:
`Tier1MutableFlowNullableCollectionElementTest`, `Tier1EnumFlowPropertyTest`.

## Amendment 2026-10-26: a flow `var` setter on every static owner

A `var` flow property on the top level, an `object`, a default or named companion, an enum body
member and an enum companion binds its setter, where it used to bind get-only and name the dropped
setter `SKIPPED_UNSUPPORTED_INPUT`. This covers `Flow`, `StateFlow`, `SharedFlow`,
`MutableStateFlow` and `MutableSharedFlow`, nullable included, and completes the setter
[ADR-233](233-interface-slot-vocabulary.md) gave a class, an interface, a sealed base and a sealed
arm. A flow `var` on a sealed `object` arm binds its setter too, through the arm's singleton handle.

**Exports.** `<prefix>_set_<p>` takes the flow, plus a sibling `_set_<p>_source` for a cold flow
made in C# (`KotlinFlow<T>.From`), which crosses through [ADR-210](210-flow-and-suspend-lambda-parameters.md)'s
seam. A static setter also carries ADR-030's error slot (`out IntPtr error`), because assigning an
object's or a file's property can run its initialiser, and a Kotlin throw there surfaces as the
usual bridged exception. The instance `_set_<p>` has no error slot, since its receiver is already
initialised.

**C# placement.** A `set` accessor on the existing static property for the top level, an `object`,
a companion and an enum companion:

```C#
Pantry.Dial = KotlinMutableStateFlow<int>.Create(9);
Feedings.FeedingTimes = KotlinFlow<int>.From(Each(6, 18));
```

An enum body member is a C# 14 extension property, and an extension property cannot be settable. Its
setter is the `Set<P>` method [ADR-006](006-enum-mapping.md) gives an ordinary enum `var`, in
`{Enum}Extensions`:

```C#
Meal.Dinner.SetMenu(KotlinFlow<string>.From(Each("chicken")));   // Meal.Breakfast.Menu is untouched
```

**Verification.** Fixture `cattery/Feedings.kt`. `IntegrationTests/FlowVarSetterOwnerTests.cs`.
Leak row 20h in `LeakTests/LiveHandleTests.cs`. Tier 1: `Tier1FlowVarPropertySetterTest`.
