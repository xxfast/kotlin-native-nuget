# ADR-237: Forward, `kotlin.Any` crosses as `object?`: `fun stash(value: Any): Any` is `object Stash(object value)`

## Status
Accepted (2026-10-23)

Amends [ADR-222](222-csharp-implemented-generic-interfaces.md) and
[ADR-224](224-generic-interface-at-every-position.md) (a member's own type parameter on a
C#-implemented interface was refused as "a genuine impossibility"),
[ADR-220](220-generic-value-class-record-struct.md) (a generic value class at an `object` position),
[ADR-208](208-generic-instantiation-member-positions.md) (a star projection was a named skip) and
[ADR-233](233-interface-slot-vocabulary.md) (the slot vocabulary gains `Any`).

## Context

`kotlin.Any` had no C# spelling. A parameter, result, property, collection element, lambda payload
or `Flow` element typed `Any` or `Any?` was a named skip, and so was everything that reduces to it:
a star projection (`Box<*>`, `Flow<*>`) and the erased `Any?` the bridge itself uses for a type
parameter. Three consequences followed. A Kotlin API that traffics in `Any` (a heterogeneous
`List<Any?>`, a `Map<String, Any?>`) could not be called. A star-projected result could not be read.
And an interface member with its own type parameter (`fun <R> sniff(f: (T) -> R): R`) could not be
implemented in C#, because C# would have to be handed an `R` that Kotlin had erased; ADR-222 and
ADR-224 recorded that as impossible.

## Decision

**`Any` and `Any?` are `object?` at every position.** A parameter, result, property, collection
element or map value, lambda payload and result, `Flow` element and star projection all spell the
type the same way. A non-null `Any` is `object`, and `Any?` is `object?`.

**A Kotlin value reaches C# as its runtime C# value.** A boxed `int`, `long`, `double`, `bool` or
other primitive, a `string`, the exported class's wrapper, the C# enum, the value class struct, a
read-only list, set or dictionary for a collection, and a `byte[]` for a `ByteArray`. The original C#
object comes back when Kotlin holds a C#-implemented interface. A Kotlin object with no C# type
arrives as a `KotlinObject`, which the caller disposes and which goes back to Kotlin unchanged.

**A C# object reaches Kotlin as the matching Kotlin value.** The same list, in reverse. A C# object
with no Kotlin counterpart is held by Kotlin as an opaque managed object and comes back as the same
C# object.

**Mechanism.** Every Kotlin value that crosses an `Any` position is a box. The runtime exports a
type tag for a box, and the C# `object` read dispatches on it. A handle-backed value is rebuilt
from a factory table keyed by the Kotlin class name (`KotlinTypes`). A C# object Kotlin holds is a
`NugetManagedObject` released through a `FreeManaged` thunk, which is fail-fast and has no error
slot.

**A member's own type parameter on a C#-implemented interface is declared erased.**
`interface Sniffer<T> { fun <R> sniff(f: (T) -> R): R }` is `object? Sniff(Func<T, object?> f)` on
`ISniffer<T>`, and Kotlin calls the C# implementation at `R = Any?`. The type parameter's single
bound replaces `Any?` when there is one. A Kotlin class that implements the interface keeps its own
typed member (`public virtual R Sniff<R>(Func<T, R> f)`) and implements the erased one explicitly,
so a consumer holding the concrete wrapper still gets a typed result. Three shapes have no single
erased type and keep the interface unimplementable in C#, with a named refusal: a `reified`
parameter, a parameter with more than one bound, and a bound that mentions another type parameter.

**A star projection reads at the parameter's bound,** `object?` when it has none. `Box<*>` is
`Box<object?>`, `Flow<*>` is `KotlinFlow<object?>`, `Satchel<*>` reads its item as `object?`, and
`Outcome<*>` on a generic sealed type is `Outcome<object>`.

**A generic value class at an `Any` position** (`value class Bow<T>(val ribbon: T)`) crosses as its
own struct through a box and unbox pair. The struct implements `INugetGenericValue`, a `{Name}Native`
holder carries the imports, and `Bow<object?>` is registered with the factory tables. It reads back
as `Bow<object?>`, never as the `Bow<string>` that went in. Its `init` runs in Kotlin when the C#
value is rebuilt, and a failure is an `ArgumentException`. A generic value class whose underlying
does not mention its own `T` (`Decal<T>(val serial: Long)`), a non-public underlying, or a bound
that mentions a type parameter has no pair and crosses an `object` position as an opaque
`KotlinObject` that goes back to Kotlin unchanged.

**The Tier 1 C# compile allows unsafe code.** The `object` read needs an unmanaged function pointer,
and the packaged project already sets `AllowUnsafeBlocks`.

## Alternatives Considered

**Reflection (`MakeGenericMethod`) to call a C# `Map<R>` at Kotlin's `R`.** A value-type `R` has no
compiled code under NativeAOT, so the call fails there. Rejected.

**Keep the member-level `<R>` refused.** What ADR-222 shipped. Every interface with one stays
unimplementable in C#, and the `Any?` and star-projected positions stay skipped. Rejected: the
erased spelling loses nothing C# could use, since the only values that can cross are the ones the
`object` wire already carries.

## Consequences

- Positions typed `Any` were skipped before, so no existing call breaks. A hand-written C#
  implementer of an interface with a member-level `<R>` must now implement the erased member the
  interface declares. **Inferred**: the interface used to omit the member, and now lists it.
- `object?` is untyped. Test it with `is`, and dispose a wrapper or `KotlinObject` a read returns, as
  at any other position. A wrapper passed in is borrowed for the call.
- Fixed along the way:
  - `kotlin.Result` is refused explicitly as a value class. Its internal `value` would otherwise plan
    as `object` and generate Kotlin that does not compile. **Inferred**: the refusal is in the
    classifier, and no test crosses a `Result` through `Any`.
  - The "Kotlin builtin with no crossing" diagnostic no longer fires for a lambda that binds.
    **Verified**: the callback payload test now binds `onAny` as `OnAny(Action<object>)`.
  - A `Unit` property rendered `public void X { get; }` (CS1547). It is now a named
    `SKIPPED_UNSUPPORTED_PROPERTY`, since C# has no void property. **Verified** by
    `Tier1UnitPropertyTest` and `UnbridgeableInterface_CSharpImplementation_ThrowsNotSupported`, which
    now uses a `Unit` property as its unbridgeable member.
- A stored-callback `add`/`remove` pair and a `suspend` lambda with an `Any` payload bind as
  `object` too, through the [ADR-239](239-returned-lambdas-and-the-callback-vocabulary.md)
  callback vocabulary, where `Any` is the boxed `T` wire. **Verified** at generation by
  `Tier1RefusedCallbackShapesTest` (`AddAny(Action<object>)`, compiled against the generated C#);
  no runtime row crosses one.

## Evidence

**Verified by test:** `IntegrationTests/AnyWireTests.cs` (each primitive, `string`, null, an exported
class, a C#-implemented interface object coming back as the same instance, an enum, a value class,
a list and a map, in both directions; an `Any` constructor, `var`, member and `Flow`; a
Kotlin-backed and a C#-implemented interface with `Any` slots; a member-level `<R>` on a generic
and a non-generic interface, called at `Int`, `String` and a handle; `Flow<*>` at a result and a
parameter, `Satchel<*>` at a result and a property; `Bow<T>` in, out, round-tripped, with a null
underlying and a failing `init`). `InterfaceParameterTests.cs`, `GenericSealedTests.cs`
(`Outcome<*>`), `InterfaceSlotVocabularyTests.cs` and `UnroutedPositionsTests.cs` (an interface's own
`<T>` erased on `IManifest`). `LeakTests/LiveHandleTests.cs` rows 6d2
`ErasedTypeParameterInterface_Argument_ReturnsToBaseline`, 21a `AnyWire_HandleBackedValues_ReturnToBaseline`,
21b `AnyWire_StarProjectedFlows_ReturnToBaseline` and 21c `AnyWire_GenericValueClass_ReturnsToBaseline`.
The suite is green under NativeAOT.

**Verified by processor test:** the phantom `Decal<T>` case generates no box or unbox export
(`Tier1GenericValueClassTest`).

**Inferred, no test:** a C# object with no Kotlin counterpart (a plain `object` or a POCO) is held
as a managed object and returns as the same instance, read from the generated `WrapAny` and
`ReadAny`. The three refused shapes of an erased `<R>` (`reified`, two bounds, a bound naming a type
parameter) are read from the planner. A use-site `out` projection of a concrete type (`Box<out Cat>`)
is unchanged.

## Amendment 2026-10-24: the box pair is ADR-228's

A generic value class's `object` box pair is the one
[ADR-228](228-generic-value-class-erased-positions.md) gives every generic value class for its
erased positions, so this ADR builds none of its own: one `_box`/`_unbox` export pair per class,
the `INugetGenericValue` struct and its `{Name}Native` holder, with `Bow<object?>` still registered
under the Kotlin name `KotlinTypes` reads. That pair also covers a phantom, so
`Decal<T>(val serial: Long)` crosses an `object` position as its struct and reads back as
`Decal<object?>` rather than as an opaque `KotlinObject`. `Tier1GenericValueClassTest` pins the
phantom's exports, and `Stash_PhantomGenericValueClass_ReadsBackOverObject` reads one back. A
generic value class with no pair (an underlying no erased position carries) is still the opaque
`KotlinObject`.
