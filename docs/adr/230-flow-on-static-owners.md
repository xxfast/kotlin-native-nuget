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

| Kotlin owner | Kotlin call | C# placement |
|---|---|---|
| top level | package-qualified | static member of the file facade |
| `object` | `Clock.ticks()` | static member of `public static class Clock` |
| companion, default or named | owner-qualified, `Hourglass.Sand.grains()` | static member of the owning class |
| enum body member | `Chime.entries[ordinal].play()` | `this` extension member in `{Enum}Extensions` |
| enum companion member | owner-qualified | static member of `{Enum}Extensions` |
| non-generic value class member | receiver rebuilt from the value class's wire slot | instance member of the record struct |

An enum body's flow property renders as a C# 14 `extension(Chime receiver)` property, since an
extension method cannot be a property.

Each collection runs on its own root scope, and only its enumerator ends it, by completion, the
token, `break` or `DisposeAsync()`. No owner is disposed, so nothing else cancels it. A `var` flow
property binds get-only and names its dropped setter, as on a class
([ADR-026](026-flow-mapping.md) 2026-10-12 amendment).

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

## Amendment 2026-10-22: generic value classes

[ADR-228](228-generic-value-class-erased-positions.md) extends the value-class owner to a generic
value class (`Sticker<T>`, the phantom `Decal<T>`). Its `Flow` and `StateFlow` members and
properties bind on this route, over the class's own `T` or with the struct as the element, and a
`SharedFlow` property reads its replay cache. A `T : Enum<T>` receiver is read through the ADR-198
trampoline.
