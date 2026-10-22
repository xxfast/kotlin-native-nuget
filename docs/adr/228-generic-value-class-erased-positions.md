# ADR-228: Forward, a generic value class crosses at every erased position

## Status
Accepted

Extends [ADR-220](220-generic-value-class-record-struct.md) (the positional generic record struct,
bound at ordinary positions only) and [ADR-171](171-value-classes-at-erased-generic-positions.md)
(the per-value-class box/unbox pair for a non-generic value class). Builds on
[ADR-199](199-generic-sealed-hierarchies.md)'s per-closed-type `NugetFactory<T>` slot and
[ADR-230](230-flow-on-static-owners.md)'s flow route. ROADMAP Phase 4.

## Context

`Box<Sticker<Int>>`, a lambda payload or result, a `Flow` element and an awaited `suspend` result
were skipped, named, for a generic value class. ADR-171's pair is keyed by the C# runtime type, and
a generic struct has no single type: `Sticker<int>` and `Sticker<Cat>` are different keys, and an
own-`T` `Sticker<T>` has a closed type only at run time. Separately, ADR-171 excluded a value class
whose underlying is itself nullable (`value class Nick(val name: String?)`), and a generic value
class's `Flow` members were dropped with no diagnostic.

## Decision

### 1. One box/unbox export pair per generic value class

Kotlin/Native boxes every instantiation of `Sticker<X>` as one runtime class (spiked on 2.4.20:
`Box(Sticker(3)).item is Sticker<*>`, and `Sticker(null)` boxes to a non-null object). So one pair
serves every instantiation, including a function's own `T`. The box export takes the underlying on
its bare-`T` wire and runs `init` when it re-wraps; the unbox export returns the underlying. A
phantom `Decal<T>` re-wraps as `Decal<Nothing>` and unboxes its own `Long`. A `null` `Sticker?`
stays a null pointer and a present `Sticker(null)` a live handle, so the two stay distinct.

### 2. The struct dispatches through `INugetGenericValue`

The record struct implements an internal `INugetGenericValue { IntPtr NugetBox(); }`.
`NugetMarshal.Wrap<T>` tests `value is INugetGenericValue` before the ADR-171 `Boxers` lookup and
keeps the rule that `owned` is false while the box export can throw. The inner `T` is wrapped with
`Wrap<T>` inside the struct, not `Wrap<object>`, because `Wrap<T>` dispatches primitives on the
static `typeof(T)`.

### 3. Reads go through a per-closed-type registration

The read side uses ADR-199's `NugetFactory<Sticker<T>>` slot. Each generic value class gets an
idempotent `NugetRegister()` that fills the slot for `Sticker<T>` and `Sticker<T>?`. It is called
from the static constructor, from `NugetBox`, and from every generated member that hands one out at
an erased position (a `Box<Sticker<int>>` result, a `KotlinFlow<Sticker<T>>`, a
`Func<..., Sticker<int>>`, a `Task<Sticker<int>>`, a settable `MutableStateFlow`). The call
is needed: a Kotlin-made `Sticker<ulong>` can be read before C# ever constructs one, and an
unregistered closed type misses the slot (spiked under JIT and NativeAOT). The call is static, so
it is trimming- and AOT-safe without reflection.

### 4. A nullable underlying uses the same pair

`Nick` keeps its ADR-171 `Boxers` and `Factories` entries. Only the pair's wire changes: the box
takes the nullable underlying and the unbox returns it. `Nick(null)` and `null` stay distinct.

### 5. The erased-position gates

`isErasedTypeArgument`, `isLambdaTypeArgument`, the `Flow`/`StateFlow`/`MutableStateFlow`/
`SharedFlow` element check and the awaited `suspend` result check admit a value class whatever its
type arguments. Writes cross too: a `MutableStateFlow<Sticker<Int>>` setter and a `Flow` parameter
box each C# element. A function's own `T` inside the struct is admitted as a component, so
`fun <T> boxedOf(v: T): Box<Sticker<T>>` binds.

### 6. Root-cause fix: every property goes through one naming pass

A generic value class's properties skipped the unrouted-position naming pass, so
`val glow: StateFlow<T>` was dropped with no diagnostic. Every entry now goes through the one
pass. Such a property, and a `Flow` member, now bind on the ADR-230 route.

## Verification

Integration rows in `IntegrationTests/GenericValueClassErasedTests.cs` cover a Kotlin-made value
read before C# constructs it (`Sticker<long>`, `Sticker<short>`, `Sticker<double>`,
`Sticker<float>`, `Sticker<bool>`, `Sticker<ulong>`), C#-built boxes, `null` versus
`Sticker(null)` and `Nick(null)`, own `T`, an object-handle and a phantom instantiation, lambdas in
both directions, `Flow`, `StateFlow`, `MutableStateFlow` writes, a `Flow` parameter, `suspend`
parameters and results, and flow members on the struct (including an enum self-bound `T`). Leak
rows in `LeakTests/LiveHandleTests.cs`: `GenericValueClass_ErasedPositions_ReturnToBaseline`,
`GenericValueClass_ErasedPositionInitThrows_ReturnsToBaseline` and
`GenericValueClass_AsyncErasedPositions_ReturnToBaseline`. The Kotlin/Native boxing and the
unregistered-type miss were spiked in a scratch program; the generated struct is read from the
verified `Interop.cs`.

## Amendment 2026-10-22: ADR-220, ADR-171 and ADR-230

- [ADR-237](237-kotlin-any-as-object.md) crosses a generic value class at an `Any` position
  through this pair: the struct's `INugetGenericValue` box on the way in, and `Bow<object?>`, the
  `KotlinTypes` key for its Kotlin name, on the way out. `Boxers` keys no generic struct.
- [ADR-220](220-generic-value-class-record-struct.md) deferred "erased positions". They bind now.
- [ADR-171](171-value-classes-at-erased-generic-positions.md) excluded a value class with a
  nullable underlying from its pair. It joins the pair (decision 4).
- [ADR-230](230-flow-on-static-owners.md) bound a flow on a non-generic value class. A generic
  value class's `Flow` members and properties bind on the same route, and an enum-bound `T` is read
  through the ADR-198 trampoline.

## Amendment 2026-10-22: the box serves `V?` at ordinary positions

[ADR-226](226-generic-value-class-nullable-and-collections.md) reuses this box and unbox pair for a
nullable value class whose underlying can be null (`Nick?`, `Sticker<T>?` over an unconstrained
`T`) at a parameter, result, constructor, property and extension receiver, and as a collection
element. A null pointer is `null` and a live handle is the boxed value class.

## Amendment 2026-10-23: sealed and `Char` underlyings

[ADR-234](234-value-class-underlying-widenings.md) admits a value class over a sealed type (the
sealed-helper protocol or an eligible sealed base) and over `Char` or `Char?` at the erased-position
gates, so `Box<Clue>` and `Flow<Monogram>` bind.
