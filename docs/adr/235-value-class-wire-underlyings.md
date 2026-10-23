# ADR-235: Forward, a value class over `Instant`, `Duration`, `Uuid`, `ByteArray`, a collection, another value class or a plain interface binds on its underlying's wire

## Status
Accepted

Amends [ADR-171](171-value-classes-at-erased-generic-positions.md) (the value-class underlying
classification refused these seven underlyings by name), [ADR-106](106-uuid-mapping.md),
[ADR-103](103-duration-mapping.md) and [ADR-076](076-instant-mapping.md) (each declined its type as a
collection component). Builds on [ADR-081](081-value-class-collection-components.md),
[ADR-225](225-generic-value-class-members.md) (the error-slot member route) and
[ADR-176](176-interface-collection-components.md).

## Context

Each of the seven underlyings already had a shipped wire at every ordinary position: ticks for
`Instant` and `Duration`, hex-dash text for `Uuid`, a bytes handle for `ByteArray`, a list, set or map
handle for a collection, the struct's own wire for another value class, and the interface bridge for
a plain interface. The value class itself was refused anyway, so `value class SnoozeSpan(val span:
Duration)` was not declared and every member typed with it was a named skip. Separately,
`List<Uuid>`, `Set<Duration>` and `Map<Instant, Uuid>` were declined as collection components, bare
and not only inside a value class.

## Decision

### 1. The struct is positional over the underlying's C# spelling

The underlying's existing C# spelling becomes the struct's one property, and the value class crosses
on the wire that underlying already uses as a parameter. Kotlin re-wraps on the way in, so `init`
runs at the crossing, as for the underlyings ADR-171 already bound.

| Underlying | Struct | Wire |
|---|---|---|
| `Instant` | `record struct SunbeamStart(DateTimeOffset At)` | `long` ticks |
| `Duration` | `record struct SnoozeSpan(TimeSpan Span)` | `long` ticks |
| `Uuid` | `record struct ChipSerial(Guid Id)` | hex-dash text |
| `ByteArray` | `record struct PawPrint(byte[] Pads)` | bytes handle |
| collection | `record struct ToyBasket(IReadOnlyList<string> Toys)` | list handle |
| value class | `record struct ShelterTag(ChipSerial Serial)` | the inner struct's wire, unwrapped to the innermost underlying and re-wrapped outward in Kotlin |
| plain interface | `record struct PurrVoice(IRumbler Rumbler)` | the interface bridge |

Verified by `ValueClassWireUnderlyingTests.cs` and the generated `Interop.cs` declarations.

### 2. Ordinary positions

The struct binds as a top-level, member, constructor or class-property parameter and result,
nullable included (`SunbeamStart?` rides the underlying's own nullable wire, or a null pointer for a
reference wire), and as a `List`, `Set` or `Map` element, key or value. Each element converts once
per side. Verified by the `*_ParamAndReturn_*`, `*_Nullable_*`, `*_CollectionElement_*` and
`Class_*` rows.

### 3. Members, properties and secondary constructors ride the ADR-225 error-slot route

A method, property or secondary constructor declared on one of these value classes binds on the
struct. The receiver crosses on the underlying's wire as parameter zero. A handle-wired receiver
(`ByteArray`, collection, interface) is minted before the call and released in a `finally`, including
when the call throws. A failing receiver `init` throws in C# (`new SnoozeSpan(TimeSpan.FromMinutes(-1))
.Doubled()` throws `KotlinArgumentException`). A secondary constructor builds the underlying in
Kotlin, so its `init` runs at C# construction. Verified by `Members_*` and `SecondaryConstructors_*`.

### 4. `default(V)` is refused for a reference-typed underlying

`default(PawPrint)`, `default(ToyBasket)` and `default(PurrVoice)` carry a null `byte[]`, list or
interface, which Kotlin could never have built. Crossing one, as a parameter, a nullable value, a
collection element or a member receiver, throws `ArgumentException` naming the struct before the
call. `default(SunbeamStart)`, `default(SnoozeSpan)` and `default(ChipSerial)` carry a real
`DateTimeOffset`, `TimeSpan` or `Guid` and cross; their `init` decides whether Kotlin accepts them.
A nested struct over a reference underlying (`PawCast(PawPrint)`) refuses through the same guard
on its innermost underlying, for an outer `default` and for an outer struct over an inner
`default` alike. Verified by `Default_ReferenceUnderlying_IsRefusedAtTheCrossing` and
`Nested_OverAReferenceUnderlying_RefusesDefaultAtTheCrossing`.

### 5. Bare `Instant`, `Duration` and `Uuid` components

`List`, `Set` and `Map` of `Instant`, `Duration` and `Uuid` (and their nullable forms) cross per
element on the existing `nuget_wrap_*` long and string arms: C# projects each `DateTimeOffset` to
`UtcTicks` and each `Guid` to text before the wrap, Kotlin converts back per element, and the read
path reverses it (`NugetMarshal.ReadList<DateTimeOffset>(...)`). Map keys and values mix freely
(`Map<Uuid, Duration>`, `Map<Instant, Uuid>`). Verified by `NarrowStdlibComponentTests.cs`.

### 6. Disposal

A struct over a value-wired underlying owns nothing. A struct over `ByteArray` or a collection copies
the data at the crossing, so there is nothing to dispose. A struct over a plain interface holds an
`IRumbler` that is `IDisposable`: a Kotlin-backed one you read out of the struct is yours to
dispose, and a C#-implemented one you pass in comes back as the same instance. Leak rows
`ValueClassOverByteArray_ParamReturnAndElement_ReturnsToBaseline`,
`ValueClassOverCollection_ParamReturnAndElement_ReturnsToBaseline`,
`ValueClassOverInterface_KotlinBackedAndCSharpImplemented_ReturnsToBaseline`,
`ValueClassMembers_HandleWiredReceivers_ReturnToBaseline` and
`NarrowStdlibComponents_ListSetAndMap_ReturnToBaseline`.

## Consequences

- Amended 2026-10-22 by [ADR-240](240-async-routes-carry-wire-underlyings.md): a `ByteArray`- or
  collection-underlying struct compares its payload by CONTENT, through a generated `Equals(V)`
  and `GetHashCode()`: `byte[]` by sequence, a list element-wise in order, a set
  order-insensitively, a map by the same keys with equal values, a nested struct through its own
  members. An interface-underlying struct keeps reference equality. `default(V)` equals another
  `default(V)` and hashes to 0. Verified by `Collection_StructComparesItsListByContent`,
  `ByteArray_StructComparesItsBytesByContent`, `Set_StructComparesItsSetIgnoringOrder`,
  `Map_StructComparesSameKeysWithEqualValues`, `Interface_StructKeepsReferenceEquality` and
  `Default_ReferencePayloadStructs_EqualEachOtherAndHashToZero`. Before the amendment the payload
  compared by reference, so two structs over equal but distinct lists differed.
- A struct over a `ByteArray` or a collection is admitted as a `Set` element and a `Map` key, which
  the bare `ByteArray` declines (ADR-151 amendment). Since the 2026-10-22 amendment a lookup with a
  freshly built struct over equal bytes matches, and a C# set collapses equal prints before it
  crosses. Verified by `ByteArray_StructInASetAndAMap_MatchesByContent` and
  `Collection_StructInAHashSet_MatchesByContent`.
- `init` does not run at primary construction in C#, only when the struct crosses.
- Amends ADR-171's REFUSED list to `Char`, a nullable non-scalar and a non-exported type, and drops
  the `Instant`/`Duration`/`Uuid` collection-component declines from ADR-076, ADR-103 and ADR-106.

## Amendment (2026-10-22): erased positions, async routes and content equality

[ADR-240](240-async-routes-carry-wire-underlyings.md) gives these value classes the ADR-228 box pair
(the box takes the struct on its innermost underlying's ordinary parameter wire, the unbox returns
it on the ordinary result wire), so `Box<PawPrint>`, a `Flow` element, an awaited `suspend` result
and a `MutableStateFlow` write bind. `default(V)` over a `ByteArray`, collection or interface
underlying is refused before the box crosses, as at an ordinary position. A value class over a
nullable `String` keeps `null` apart from `V(null)`. The `suspend` and `Flow` routes also take these
structs as parameters, and a value class's own `suspend` and `Flow` members bind on the struct.
Verified by `Erased_GenericClass_InstantUuidCollectionAndInterfaceUnderlyings_RoundTrip`,
`Erased_GenericClass_DefaultReferencePayload_IsRefusedBeforeItCrosses`,
`SettableHolders_OfNarrowTypesAndWireValueClasses_ReadAndWrite` and
`ValueClassOverANullableUnderlying_KeepsNullAndAbsentApart`.
