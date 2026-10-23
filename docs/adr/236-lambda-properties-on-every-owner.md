# ADR-236: Forward, a lambda-typed property binds on every owner as a BCL delegate

## Status
Accepted

Amends [ADR-160](160-callback-parameter-on-the-forward-plan.md) (a lambda property bound on an
ordinary class and a sealed arm only, read-only and non-null), [ADR-210](210-flow-and-suspend-lambda-parameters.md)
(the `suspend` lambda property shape), [ADR-039](039-interface-bridging.md) (a listener's property
vocabulary), [ADR-075](075-collection-property-getter-setter-independence.md) and
[ADR-107](107-throwable-property-mapping.md) (a lambda-typed object property was a named
`SKIPPED_UNSUPPORTED_PROPERTY`, a `var` lambda setter a partial skip).

**Breaking** for every lambda property that bound before. See [Breaking](#breaking).

## Context

A lambda-typed property bound only where the legacy class route reached: an ordinary class and a
sealed arm, non-null, and get-only (a `var`'s setter was a named skip). An interface, an `object`,
a companion, the top level, an enum, a generic class and a sealed base named the property in
`SKIPPED_UNSUPPORTED_PROPERTY` and generated nothing. A nullable lambda property was refused whole.

The property type was a wrapper (`KotlinFunc<A, R>`, `KotlinAction<A>`, `KotlinSuspendFunc<A, R>`).
A setter needs to accept a C# lambda, so the first design kept the wrapper types and gave them
implicit conversions from the BCL delegate.

## Decision

A lambda-typed property binds on every owner, nullable and `suspend` included, and a `var` gets a
setter. The C# property type is the BCL delegate:

| Kotlin | C# |
|---|---|
| `(A) -> R` | `Func<A, R>` |
| `(A) -> Unit` | `Action<A>` |
| `suspend (A) -> R` | `Func<A, CancellationToken, Task<R>>` |
| `suspend (A) -> Unit` | `Func<A, CancellationToken, Task>` |
| any of these, nullable | the same type with `?` |

Owners: an ordinary class, a sealed base and its arms, an `object`, a default and a named companion,
the top level, an enum body and an enum companion, a generic class (payloads typed by the closed
`T`), a Kotlin-backed interface (read and written through its backing wrapper), a C# implementation
of a Kotlin interface (Kotlin reads and writes the C# property through the bridge), and an ADR-039
listener interface (Kotlin reads the listener's lambda properties through the subscription and writes
a `var`). A generic payload such as `() -> Tote<Tote<Skein>>` binds with the nested instantiation
filled.

C# placement follows the owner: static on the static class for an `object` and the top level, static
on the owning class for a companion (named companions included), static on `{Enum}Extensions` for an
enum companion. An enum body property cannot be a C# property: it is a `Name()` getter extension and,
for a `var`, a `SetName(...)` extension (`Stride.Trot.OnStride()`, `Stride.Gallop.SetOnTrip(f)`).

### Getter

The getter returns a delegate bound to the Kotlin lambda. Each read mints a fresh native handle that
the delegate's target releases when it is collected, so there is nothing to dispose.

### Setter

The setter takes any delegate of the property's type, with no cast (`landing.OnLap = i => ...`).
Three cases:

- A delegate that came from a Kotlin getter goes back as the same Kotlin lambda, with no C# hop.
- A C# delegate is minted into a Kotlin lambda through new runtime exports:
  `nuget_func_from_managed` and `nuget_suspend_func_from_managed` take the delegate's invoke and
  release thunks and a `GCHandle` key; `nuget_handle_retain` keeps a borrowed handle alive for the
  Kotlin property that now holds it. A Kotlin cleaner releases the key when the lambda is replaced
  or cleared, so a replaced or cleared delegate becomes collectable.
- `null` clears a nullable property.

A `suspend` setter adapts the delegate's `Task` the way ADR-152's `awaitForKotlin` does.

### Why a BCL delegate and not a wrapper

C# never applies a user-defined conversion to a lambda literal (CS1660), so with a `KotlinFunc<..>`
property type, `landing.OnLap = i => ...` does not compile: the consumer would have to write
`(KotlinAction<int>)(i => ...)` or `new KotlinAction<int>(...)`. The property type has to be the
delegate itself for the literal to convert. The wrapper types still exist for lambdas returned from
a function and for a lambda inside a generic result; only the property type changed.

### Alternatives rejected

- Keep the wrapper type and add implicit conversions from the BCL delegate: it compiles for a method
  group or a typed delegate variable but not for a lambda literal (CS1660), which is the common case.
- A separate `SetOnLap(Func<..>)` method beside a get-only property: two spellings for one Kotlin
  property, and a `Set` method on a `var` is not what a C# consumer expects.
- Get-only on the new owners: a `var` lambda is the only way for C# to hand Kotlin a callback
  without a stored-callback pair, and Kotlin already calls whatever the property holds.

## Breaking

A lambda property that already bound (a non-null lambda on an ordinary class or a sealed arm) changes
type:

- `KotlinFunc<A, R>` becomes `Func<A, R>`, `KotlinAction<A>` becomes `Action<A>`, `KotlinSuspendFunc<A, R>`
  becomes `Func<A, CancellationToken, Task<R>>`, `KotlinSuspendAction<A>` becomes
  `Func<A, CancellationToken, Task>`.
- `using var f = obj.Prop;` no longer compiles (a delegate is not `IDisposable`). Drop the `using`.
- `await f.InvokeAsync(args)` becomes `await f.Invoke(args, token)`; pass `CancellationToken.None`
  when there is none.
- Reflection on `KotlinFunc<..>` as the property type fails; compare against `Func<..>`.
- A sealed-arm getter now carries the error slot, so a getter that throws raises a `KotlinException`
  instead of crashing.
- A `var` lambda property now has a setter, and a nullable one binds instead of being skipped.

A function that returns a lambda (`fun adder(n: Int): (Int) -> Int`) still returns the wrapper.

## Consequences

- The per-owner legacy lambda-property carrier (`ForwardLegacyLambdaProperty.kt`) is gone. A lambda
  property is planned and reported by the ordinary property planner like any other property, so the
  report and the emitters cannot disagree.
- Three runtime exports join the fixed `nuget_*` ABI (`scripts/verify-runtime-exports.sh` lists
  them).
- A listener or implemented-interface property of lambda type joins the slot vocabulary of ADR-039
  and ADR-084; the whole-pair refusal for an unsupported property type no longer applies to it.
- A Kotlin throw from a delegate invoked through the getter surfaces as the usual `KotlinException`.
  A C# delegate that throws while Kotlin calls it follows ADR-161.

## Verification

- Forward fixture `zoomrun/` (`Landing`, `ZoomrunSample`, `Racetrack`, `Burrow`, `Stride`, `Relay`,
  `YarnBin`, `YarnShop`, `Pouncer`, `Chase`, `Stopwatch`, `LaserTower`) with
  `IntegrationTests/LambdaPropertyOwnerTests.cs`: a `val` invoking Kotlin, a `var` taking a C#
  delegate that Kotlin then calls, a nullable `var` round-tripping `null`, a `suspend` `val` and
  nullable `var`, on every owner above, plus a sealed base and arm, an ADR-039 listener and a
  C#-implemented `IPouncer`.
- Existing rows moved to the delegate shape: `LambdaTests`, `SuspendLambdaTests`,
  `LambdaTypeArgumentTests`.
- Tier 1: `Tier1LambdaPropertyOwnersTest`, `Tier1LambdaPropertyReportTest`,
  `Tier1EnumMemberPropertyPlanTest`, `Tier1SealedSubclassPropertyPlanTest`,
  `Tier1UnitLambdaPropertyTest`.
- Leak rows in `LeakTests/LiveHandleTests.cs`: `LambdaProperty_EveryOwner_ReadAndWrite_ReturnsToBaseline`,
  `LambdaProperty_Interface_BothHalves_ReturnsToBaseline`,
  `LambdaProperty_SuspendStateAndSharedFlowResult_ReturnsToBaseline`.
- Collectability rows in `LeakTests/CollectabilityTests.cs`:
  `LambdaProperty_ObjectNullableVar_ClearedDelegateIsCollected`,
  `LambdaProperty_CompanionVar_ReplacedDelegateIsCollected`,
  `LambdaProperty_TopLevelNullableVar_ClearedDelegateIsCollected`,
  `LambdaProperty_EnumBodyVar_ReplacedDelegateIsCollected`,
  `LambdaProperty_GenericClassVar_ReplacedDelegateIsCollected`,
  `LambdaProperty_KotlinBackedInterfaceVar_ClearedDelegateIsCollected`,
  `LambdaProperty_SuspendStateFlowResult_DelegateIsCollectedOnceTheFlowIsDisposed`.
