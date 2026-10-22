# ADR-226: Forward, a nullable-underlying value class and a generic value class bind under `?` and inside collections

## Status
Accepted

Extends [ADR-220](220-generic-value-class-record-struct.md) (the positional generic record struct),
[ADR-228](228-generic-value-class-erased-positions.md) (the per-value-class box/unbox pair) and
[ADR-212](212-type-parameter-as-collection-element.md)'s collection components. ROADMAP Phase 4.

## Context

A function's own `T` inside a generic value class was skipped, named, under `?` (`Sticker<T>?`) and
inside a `List`, `Set` or `Map`. Under `?` the reason was real: `null` and `Sticker(null)` would
share one null pointer. The same collision already existed, silently, for every value class whose
underlying can itself be null. `List<Nick?>` (`value class Nick(val name: String?)`) bound, and
`Nick(null)` came back as `null`. Separately, a value class over a nullable underlying was refused
at a plain parameter or result, and a star-projected `Sticker<*>` parameter was refused because it
has no C# spelling.

## Decision

### 1. A nullable value class over a nullable underlying crosses as a box handle

When the outer is `?` and the substituted underlying admits null (an unconstrained own `T`,
`Sticker<String?>`, `Nick`, `TreatCount`), the slot is one pointer. A null pointer is `null`; a live
handle is [ADR-228](228-generic-value-class-erased-positions.md)'s box of the value class, which can
hold `V(null)`. C# writes it with `NugetMarshal.Wrap<V?>` and reads it with
`NugetMarshal.FromHandle<V?>`; Kotlin reads and retains the boxed value class. `init` runs in Kotlin
when the box is minted, so a C# `Sticker<int>?` holding `-1` throws `ArgumentException` before the
call. It applies at a parameter, result, constructor parameter, property getter and setter and an
extension receiver (`fun Nick?.callName()`, `fun <T> Sticker<T>?.presence()`).

Everything else keeps its wire. A `T : Any` underlying has no null of its own, so `Sticker<T>?` rides
the null pointer exactly like a closed `Sticker<Cat>?`. A primitive or enum underlying keeps
[ADR-079](079-nullable-primitive-enum-underlying-value-classes.md)'s has-value fan-out. A `String` or handle
underlying keeps its own null pointer.

### 2. A nullable underlying binds at a non-null outer

`Nick`, the closed `Sticker<String?>` and `TreatCount(val count: Int?)` bind at every ordinary
position. There is no collision, because the only null is the underlying's. `String?` crosses as the
nullable UTF-8 pointer, and `Int?` as a has-value pair: the struct constructor and a parameter send
`count.HasValue` and `count.GetValueOrDefault()`, and a result returns a has-value flag with the value
out.

### 3. Collection components

A function's own `T` inside a generic value class is a collection component:
`List<Sticker<T>>`, `Set<Sticker<T>>`, `Map<String, Sticker<T>>`, `Map<Sticker<T>, String>`,
`MutableList<Sticker<T>>` and nested lists. A non-null element projects to its underlying, and Kotlin
re-wraps each element with `Sticker(...)`, so `init` runs per element. A nullable element over a
nullable underlying (`List<Sticker<T>?>`, `Map<String, Sticker<T>?>`, `List<Nick?>`,
`List<Sticker<String?>?>`) is the box handle of decision 1 and is not projected, which replaces the
lossy `List<Nick?>` wire. The C# signatures do not change. A nullable map key stays refused.

### 4. A star-projected parameter is a synthesized method type parameter

`fun starred(sticker: Sticker<*>)` binds as `Starred<TSticker>(Sticker<TSticker> sticker)`. The
synthesized parameter is named after the value class, follows the declared parameters and carries no
constraint. Kotlin re-wraps the underlying as a `Sticker<Any?>`, which is a `Sticker<*>`. It applies to class members too (`GapAlbum.Peek<TSticker>`).

### 5. Generic top-level extensions bind in general

`fun <T> Sticker<T>?.presence()` binds as `Presence<T>(this Sticker<T>? receiver)`. The same entry
point now binds any top-level `fun <T> X.f()`, not only a value class receiver.

### 6. Root-cause fix: constraint rewriting kept every declared type parameter only

`withMethodTypeParameterConstraints` rebuilt a method's type parameter list from the declared ones and
dropped any parameter added beyond them, such as the synthesized star parameter. It keeps every
parameter now.

## C# behavior to know

- C# cannot infer `T` through `Nullable<>` (CS0411), so a call that passes a `Sticker<byte?>` to a
  `Sticker<T>?` parameter names it: `IsAbsent<byte?>(new Sticker<byte?>(null))`. Passing `null` needs
  the argument too.
- A `this Nick?` extension needs a `Nick?` receiver. `((Nick?)new Nick(null)).CallName()` works, and
  `NickExtensions.CallName(null)` reaches the `null` case.

## Verification

Tier 1 cells in `Tier1GenericValueClassGapTest` (box handle wire, has-value pair, collection
components, star parameter); integration rows in
`IntegrationTests/GenericValueClassGapTests.cs` over the fixture `stickers/StickerGaps.kt`. Each
open-instantiation row uses a type argument no other test reads as a `Sticker`, so the per-closed-type
registration has to come from the call site. Leak rows in `LeakTests/LiveHandleTests.cs`:
`GenericValueClass_NullableAndCollections_ReturnToBaseline` and
`GenericValueClass_NullableAndCollectionInitThrows_ReturnsToBaseline`, which covers an `init` that
throws when a box is minted and when a set element is re-wrapped after the set handle exists.

## Amendment 2026-10-22: ADR-220 and ADR-228

- [ADR-220](220-generic-value-class-record-struct.md) named `Sticker<T>?` and a collection of
  `Sticker<T>` over a function's own `T` as skipped. They bind now (decisions 1 and 3).
- [ADR-228](228-generic-value-class-erased-positions.md) minted the box for erased positions. It now
  also serves a nullable value class at an ordinary position (decision 1).

## Amendment 2026-10-23: star-projected results

[ADR-234](234-value-class-underlying-widenings.md) applies decision 4 to a result too:
`fun any(): Sticker<*>` binds as `Sticker<TSticker> Any<TSticker>()`.
