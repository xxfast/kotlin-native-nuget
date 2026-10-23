# ADR-229: Forward, a `suspend` member binds on every owner without a C# instance scope

## Status
Accepted

Amends [ADR-019](019-suspend-function-mapping.md) (the suspend route walked classes and top-level
functions only) and [ADR-214](214-suspend-generic-functions-on-the-suspend-route.md) (an `object`'s
and a companion's suspend members were a named skip, generic or not).

## Context

A `suspend` member bound only where a C# owner could hold its coroutine scope: a class, a sealed
base or arm, and the top level (which launches on an ad-hoc root scope per call). Every other owner
dropped its suspend members as `SKIPPED_UNSUPPORTED_COMBINATION` or a named drop: an `object`, a
default-named or named companion, a suspend extension, an enum body and enum companion, and a
value class.

None of those owners has anything a `Dispose()` could cancel. An `object` is a static class, a
companion is static on its owner, an extension is a static method, an enum entry is a constant and a
value class is a struct. The top-level route already covers exactly that case.

## Decision

The top-level suspend route generalises over a receiver expression. One `_async` export per member,
`launchForCSharp(CoroutineScope(Dispatchers.Default), ...)` per call, no C# scope handle. A
`forwardStaticSuspendRouteMethods` selector is read by the export emitter, the C# translator and the
diagnostic walk, so the three cannot disagree.

| Kotlin owner | Kotlin call | C# placement |
|---|---|---|
| `object Siesta` | `Siesta.minutesFor(...)` | static method on `public static class Siesta` |
| companion, default or named | owner-qualified, `Quilt.knit(...)` | static method on the owning class |
| `suspend fun Swing.rock()`, `Swing?`, `String`, primitive, enum receiver | receiver as the first parameter of the export | `this` extension in `{Receiver}Extensions` |
| enum body member | `NapSpot.entries[ordinal].claim(...)`, receiver crosses as the ordinal | `this` extension in `NapSpotExtensions` |
| enum companion member | owner-qualified | static method in `NapSpotExtensions` |
| non-generic value class member | receiver rebuilt from the value class's wire slot, as the sync route does | instance method on the record struct |

A member's own `T` binds exactly as on a top-level function (ADR-214), including on an extension, an
enum member and a value-class member. The receiver is borrowed across the suspension, as any class
argument on this route is. `Unit` returns are a plain `Task`; `Flow` and `StateFlow` returns follow
the existing suspend-returning-flow tails.

Cancellation is per call through the `CancellationToken`, which cancels the launched job. Nothing
cancels the call otherwise, because no owner is disposed.

Alternatives rejected:

- A process-wide static `CoroutineScope`: nothing could cancel it, so it buys nothing over a root
  scope per call and adds a global.
- Routing through the class suspend route with a synthetic `StableRef` of the `object`: mints and
  leaks a handle per object and gives C# an instance method on a static class (CS0708).
- Rendering a suspend extension as an instance-scope member: it has no owner scope to drain.

## Consequences

- A companion `suspend fun sway(times: Int)` beside an instance `suspend fun sway()` is a legal
  static and instance overload pair in C#. When the parameter lists match, the pair is
  `ERROR_CSHARP_SIGNATURE_COLLISION` from `emitCsharpSignatureCollisions`, as for any other clash.
- An unreachable interface's suspend member is untouched.
- ADR-214's `GENERIC` diagnostic sentence no longer carries a carve-out for an object's suspend
  members; the structural skip it described is gone.
- The new `_async` exports are scraped into the ABI contract with the other legacy routes.

## Verification

- Forward fixture `catnap/` (`Siesta`, `Swing`, `Quilt`, `NapSpot`, `NapLength`, `NapTag`,
  `SwingExtensions.kt`) with `IntegrationTests/StaticSuspendTests.cs`: a result, a `Unit` return, a
  generic `T`, a fault, a token cancellation, a pre-cancelled token, a member that never suspends,
  and the reflected static shape of `MinutesForAsync`.
- Tier 1: `Tier1StaticSuspendTest`, `Tier1SuspendOwnerAuditTest`, `Tier1SuspendReachabilityTest`.
- Leak rows in `LeakTests/LiveHandleTests.cs`: `StaticSuspend_EveryOwner_Completion_ReturnsToBaseline`,
  `StaticSuspend_Fault_ReturnsToBaseline`, `StaticSuspend_Cancellation_ReturnsToBaseline`,
  `StaticSuspend_TightLoop_CompletionBeforeReturn_ReturnsToBaseline`.
- Collectability rows in `LeakTests/CollectabilityTests.cs`:
  `StaticSuspend_ObjectCallCompletes_ItsStateIsCollected`,
  `StaticSuspend_ExtensionCallCompletes_ItsStateAndReceiverAreCollected`,
  `StaticSuspend_ArgumentFailsItsNullGuard_ItsStateIsCollected`.

## Amendment (2026-10-22): a flow result of a static `suspend` member

[ADR-230](230-flow-on-static-owners.md) binds the non-suspend `Flow` family on the same owners. The
awaited flow result is now the same wrapper there: a `suspend` member returning
`MutableStateFlow<T>` awaits to a settable `KotlinMutableStateFlow<T>` on every owner this ADR
covers, the top level included, and `SharedFlow` results follow. The top-level route used to hand
back a read-only `KotlinStateFlow<T>`.

## Amendment 2026-10-22: a generic value class receiver

The value-class row also covers a generic value class ([ADR-225](225-generic-value-class-members.md)).
The receiver crosses as a boxed `T` (a plain scalar for a phantom) and Kotlin rewraps it inside the
coroutine, so a failing `init` faults the returned `Task` instead of throwing from the call. The C#
member is an instance method on the generic record struct, and the box is released when the task
settles.

## Amendment (2026-10-22): wire receivers

[ADR-240](240-async-routes-carry-wire-underlyings.md) widens the receiver row. A `suspend`
extension binds on an `Instant`, `Duration`, `Uuid`, `ByteArray` or plain interface receiver, each
nullable too, on that type's ordinary wire (`suspend fun ByteArray?.padsOrNone()`). A value class
over `Instant`, `Duration`, `Uuid`, `ByteArray`, a collection, an interface or another value class
binds its `suspend` members as instance methods on the struct, and a `default(V)` receiver over a
reference underlying throws `ArgumentException`. Verified by
`NullableAndValueClassExtensionReceivers_Cross`, `Extension_BareTypeReceivers_CrossOnTheirWire`,
`Extension_ValueClassReceivers_CrossOnTheirUnderlyingWire` and
`ValueClassMembers_ReferenceAndStdlibUnderlyings_AreInstanceAsyncMethods`.
