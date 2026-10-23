# ADR-240: Forward, the `suspend` and `Flow` routes carry `Instant`, `Duration`, `Uuid`, `ByteArray`, a plain interface and a value class over any underlying

## Status
Accepted

Builds on [ADR-235](235-value-class-wire-underlyings.md) (the wire value classes),
[ADR-229](229-suspend-on-static-owners.md) (the static suspend receiver) and
[ADR-230](230-flow-on-static-owners.md) (the static flow route). Amends ADR-235's Consequences
(content equality).

## Context

The ordinary route already had a wire for each of these types at every position: ticks for
`Instant` and `Duration`, hex-dash text for `Uuid`, a bytes handle for `ByteArray`, the interface
bridge for a plain interface, and the struct's innermost underlying for a value class. The legacy
`suspend` and `Flow` routes classify their own parameters, results and elements
(`ForwardLegacyRouteCollections.kt`), and refused every one of them as a parameter. A bare
`Instant`, `Duration` or `Uuid` result or `Flow` element was worse: it fell through to the user-type
C# speller, which refuses a Kotlin builtin and failed the whole module. A static suspend receiver
(an extension, a value-class member) of any of these types was a named skip.

## Decision

### 1. One parameter shape, the ordinary route's wire

`ForwardLegacyParameterShape.Wire` carries the classified type. A value class is seen through its
`ForwardValueClassView` to its innermost underlying (`planned`), so `PostPrint(ByteArray)` crosses
exactly as a `ByteArray` does and `PostCount(Int)` as an `Int`. Both halves read the one shape:

| Planned | Kotlin slot | C# argument |
|---|---|---|
| `Instant`, `Duration` | `Long` ticks (a `Boolean` has-value slot first when nullable) | `.UtcTicks` / `.Ticks` |
| `Uuid` | `String` | `.ToString()` |
| `ByteArray` | bytes handle | `NugetMarshal.CreateBytes`, released in the `finally` |
| collection | collection handle | `NugetMarshal.CreateList/Set/Map`, released in the `finally` |
| plain interface | bridge handle | under a value class, `NugetMarshal.HandleOf`, a minted transfer handle released in the `finally`; a bare interface parameter is ADR-232's interface shape (`NugetMarshal.Wrap<I>`), which takes it first |
| scalar, `String`, enum, class | the plan route's own | the plan route's own |

Kotlin reads the slot into a local before the coroutine launches (the ordinary route's lowering,
`legacyWireLowering`), so C# may release a minted handle the moment the extern returns. A value
class with a box pair (every one with a record struct, since section 2) crosses a parameter as its
box: C# mints it with `NugetMarshal.Wrap<V>` before the extern, which runs `init`, so a failing
`init` throws exactly `KotlinArgumentException` from the call and no `Task` is returned. A
value-class RECEIVER crosses on its underlying's wire and is re-wrapped inside the launch, so its
failing `init` faults the `Task` with exactly `KotlinArgumentException`. Neither escapes the export.
Verified by `FailingValueClassInit_OnAnAsyncParameter_ThrowsExactlyKotlinArgumentException`; a
`ByteArray` or list argument is copied for the call, verified by
`BytesAndListArguments_AreCopiedForTheCall`. C# unwraps the struct first and refuses a
reference-typed `default(V)` with `ArgumentException`, as ADR-235 does.

### 2. Results and `Flow` elements ride the result wire

`ForwardLegacyReturnShape.Wire` and `ForwardLegacyFlowElementShape.Wire` cover a bare `Instant`,
`Duration` or `Uuid`. The export pins the value as itself, the box ADR-239 reads at every erased
position, and C# reads it back with `NugetMarshal.FromHandle<DateTimeOffset>` (or `TimeSpan`,
`Guid`), which converts over `nuget_unwrap_instant`, `_duration` or `_uuid`. A `Flow` element reads
through the per-member `read:` seam the collection element already uses; `NugetMarshal.FromHandle`
reads the same boxes for a holder with no seam, so a lambda's type argument and a flow element
share one encoding. A null result is the null pointer.

A value class over an ADR-235 WIRE underlying gets the ADR-228 box/unbox pair: the box takes the
struct on its innermost underlying's ordinary parameter wire (a bytes, collection or interface handle
minted and released around the extern), the unbox returns it on the ordinary result wire, re-wrapped
through its view. So it crosses at every erased position (`Box<PawPrint>`, a flow element, an
awaited result, a `MutableStateFlow` write) as every boxed value class does, and a value class over
a nullable `String` keeps `null` apart from `Note(null)` (ADR-228).

A read-only `StateFlow` or `SharedFlow` member or property boxes its `.Value` and its replay cache
the same way, through its own per-member exports, and a `suspend` member awaiting a read-only
`StateFlow` of one reads through the per-member `_value`/`_collect` pair an awaited collection
already uses. A `MutableStateFlow` or `MutableSharedFlow` of a bare `Instant`, `Duration` or `Uuid`
writes on the same ticks or text wire (`MutableStateFlowElement.Narrow`), and one of a bare
`ByteArray`, a collection or a plain interface writes the handle the ordinary route mints for it
(`MutableStateFlowElement.Minted`): C# creates it before the setter, `CompareAndSet` or emit
extern and releases it in a `finally`, and Kotlin reads it (copying a container) before the export
returns. Rows `SettableHolders_OfBytesCollectionsAndInterfaces_*`, leak row
`AsyncWireUnderlyings_SettableHandleHolders_ReturnToBaseline`. A `suspend` member awaiting a
`MutableStateFlow` or `MutableSharedFlow` of one of these is settable the same way: the awaited
flow reads through its own per-member `_value`/`_collect` pair and writes through the held
route's flow-keyed setter, `CompareAndSet` and emit exports. Row
`AwaitedSettableHolders_OfBytesCollectionsAndInterfaces_CSharpWritesKotlinSees`, leak row
`AsyncWireUnderlyings_AwaitedSettableHandleHolders_ReturnToBaseline`.

### 3. Receivers

`ForwardStaticSuspendReceiver.Wire` is the static suspend and static flow receiver for the same
types, nullable included (`suspend fun Instant?.stampOrEpoch()`, `suspend fun ByteArray?.padsOrNone()`,
`suspend fun Doorbell?.chimeOrSilence()`). A value class's own `suspend` and `Flow` members bind on
that receiver over the struct's underlying (a bare collection underlying included), as instance
methods on the record struct; `default(PostBundle).TopAsync()` throws `ArgumentException`.

### 4. Content equality

A record struct over a `ByteArray` or a collection generates `Equals(V)` and `GetHashCode()`
(`CirContentEquality.kt`): `byte[]` by `SequenceEqual`, a list element-wise in order, a set
order-insensitively (`Contains` both ways by count, an order-insensitive hash sum), a map by the same
keys with equal values. A nested component recurses; a nested struct compares through its own
members. An interface underlying keeps reference equality. `default(V)` has a null payload, equals
another `default(V)` and hashes to 0.

## Consequences

- Verified by `IntegrationTests/AsyncWireUnderlyingTests.cs`, `AsyncWireUnderlyingResultTests.cs`
  and the equality rows of `ValueClassWireUnderlyingTests.cs`; leak rows
  `AsyncWireUnderlyings_HandleBacked_Completion_ReturnsToBaseline`,
  `AsyncWireUnderlyings_HandleBacked_Fault_ReturnsToBaseline` and
  `AsyncWireUnderlyings_HandleBacked_Cancellation_ReturnsToBaseline`.
- A struct over a `ByteArray` or a collection now works as a C# dictionary key and set element by
  content (ADR-235 amendment).
- A class owner mints its coroutine scope on its first `suspend` call and holds it until disposed,
  so the leak rows warm it before the baseline, as the other class-route rows do: the first call
  inside the window read as a constant +1 handle, not a per-crossing leak.
