# ADR-196: Generic nested types: a generic nested class is `Outer.Inner<T>`, and a generic class owner's nested declarations live on a non-generic static holder `Box` beside `Box<T>`

## Status

Accepted (2026-10-04)

## Context

ADR-133 and ADR-134 declare a Kotlin nested declaration as a C# nested type, and ADR-141 added
`inner class`. Two generic families were left a named `SKIPPED_NESTED_DECLARATION`, and ADR-141's
2026-10-03 amendment recorded them as a deferred capability rather than a language gap.

The shapes are not one question, because Kotlin gives them different semantics:

```kotlin
class Outer { class Inner<T>(val item: T) }          // A: generic nested, non-generic owner
class Box<T>(val item: T) {
  class Lid(val number: Int)                         // B: nested, does NOT capture T; type is `Box.Lid`
  inner class Seal(val turns: Int) { fun peek(): T = item }   // C: inner, captures T
  inner class Tag<U>(val label: U)                   // D: generic inner, captures T
}
enum class Season { WINTER; class Almanac(val year: Int) }    // E: enum owner
```

C# has one nesting rule: every type nested in `Box<T>` captures `T`. So `Box<T>.Lid` in C# would be
a different type per `T`, where Kotlin has exactly one `Box.Lid`.

Constraints, each verified by spike (C# SDK 10.0.301 and kotlinc-native 2.4.10):

- Kotlin rejects type arguments on the owner of a non-inner nested class: `Box<Any>.Lid` is "type
  arguments for outer class are redundant when nested class is referenced". It requires them for an
  inner one: bare `Box.Seal` is "one type argument expected".
- C# rejects `[DllImport]` anywhere inside a generic type, including in a non-generic class nested
  in it and in a static carrier nested in that (CS7042).
- With only `Box<T>` declared, `global::NS.Box.Lid` is CS0305. `nestedCsName()` spells exactly that
  at every type position, and a position outside `Box<T>` (`object Shop { fun spare(): Box.Lid }`)
  has no `T` to supply.
- `public static class Box` and `public class Box<T>` coexist in one namespace (the BCL's `Tuple` /
  `Tuple<T1>` idiom). The static class may hold a class with inline externs, an enum, a static
  class, an interface and a generic class.
- A member `Lid` on `Box<T>` does not collide with a type `Lid` on the holder (no CS0102). A type
  named like the holder does (CS0542).
- `typeof(Box<int>.Seal) == typeof(Box<string>.Seal)` is `False`: nesting under `Box<T>` makes one
  Kotlin class many C# types.

A generic class already binds on one erased route (ADR-147): a `CirClass` with type parameters,
`T` crossing as a boxed handle, the Kotlin receiver spelled `Crate<Any?>` or its bound.

## Alternatives Considered

### 1. `Outer.Inner<T>`; a non-generic static holder for everything nested in a generic class; flatten inner classes onto the holder (chosen)

`Box.Lid` in Kotlin is `Box.Lid` in C#, one type, declared in `public static class Box` beside
`public class Box<T>`. An `inner class` of the generic owner genuinely captures `T`, so it is
flattened onto the same holder with the captured parameters first (`Box.Seal<T>`, `Box.Tag<T, U>`),
its constructor taking a `Box<T>` outer. Nothing nests inside `Box<T>`, so CS7042 and an extern hoist
rewrite never arise.

Pros: the Kotlin scope name is preserved letter for letter; one C# type per Kotlin class; children
keep inline externs because the holder is not generic; no change to `nestedCsName()` or
`nativePrefix`. Cons: IntelliSense shows two `Box` entries, and `Box<int>.Lid` does not compile,
which a C# developer may try first.

### 2. Nest everything under `Box<T>` (`Box<T>.Lid`)

Rejected: one Kotlin class becomes one C# type per `T`, no position outside `Box<T>` can name it
(CS0305), and no extern can live in it (CS7042). It would be faithful for the inner classes only,
and those are covered by Alternative 1 without it.

### 3. Hoist to a namespace-level invented name (`BoxLid`)

Already rejected by ADR-134 Alternative 2: it invents a name no Kotlin scope has. The holder invents
no name.

### 4. Decline the inner classes of a generic owner

The Proposed draft did this on the cost of an owner-chain-aware extern hoist. Rejected once the
flattened spelling was spiked: it needs no hoist rewrite, because the holder is not generic.

### 5. Keep the whole family a named skip

Rejected: a class scoped to a generic type (a builder, options or key class) is common.

## Decision

**A. A generic nested class under a non-generic owner is declared as `Outer.Inner<T>`.** The
candidate gate's generic arm is deleted. The ADR-147 carrier is nested beside it as
`Outer.InnerNative`. A generic `inner class` under a non-generic owner is the same plus ADR-141:
`new Tote.Charm<int>(tote, 7)`, the outer first. A generic class under an `interface` or `object`
owner (`ICage.Bar<T>`, `Registry.Entry<T>`) and a nested generic interface (`Basket.IPicker<T>`)
bind the same way.

**B. Every public non-inner declaration nested in a generic `class` owner is declared on a
non-generic static holder** named as the owner, in the owner's namespace (nested beside the owner
when the owner is itself nested): `class`, `object`, `enum class`, `interface`, `value class`, and a
generic class (`Box.Pair<U>`). Children of a nested generic owner go on a holder nested beside it,
and a non-inner child's own inner class binds in the ordinary shape (`Box.Shelf.Hinge(shelf, ...)`).
The generic `CirClass` carries no nested declarations. The holder is a member-less `CirObject`
marked `isNestedTypeHolder`, rendered by the existing `renderObject`, and emitted only when at least
one child is declared.

```csharp
public static class Teapot                   // holder: Kotlin's `Teapot.` scope
{
    public class Lid : IDisposable, INugetHandle { /* externs inline */ }
    public static class Defaults { public const int Cups = 3; }
    public enum Blend { Green, Black }
    public class Cozy<U> : IDisposable, INugetHandle { /* ... */ }
    public class Strainer<T> : IDisposable, INugetHandle
    {
        public Strainer(global::TestLibrary.Nested.Teapot<T> outer, int mesh);
    }
}
public class Teapot<T> : IDisposable, INugetHandle
{
    public global::TestLibrary.Nested.Teapot.Lid LidAt(int number);
}
```

**C, D. An `inner class` of a generic owner ships, flattened onto the holder.** The captured
parameters come first, then the class's own: `Box<T> { inner class Seal }` is
`new Box.Seal<T>(Box<T> outer, ...)` and `inner class Tag<U>` is `Box.Tag<T, U>`. This is faithful,
because a Kotlin inner class captures `T`. The Kotlin side spells the receiver with erased arguments
on every captured segment (`outer.asStableRef<pkg.Box<Any?>>().get().Seal(turns)`,
`asStableRef<pkg.Box<Any?>.Seal>()`); a non-inner segment above the captured run takes none, since
Kotlin rejects them there. A chain that stops at a non-inner link captures nothing:
`Box<T> { class Shelf { inner class Hinge } }` is `Box.Shelf.Hinge(Box.Shelf outer, ...)`.

**Bridge mechanism.** `capturedTypeParameterOwners()` walks up while the class is `inner` and
collects the generic owners; `forwardTypeParametersInScope()` is captured parameters then own, and
every class-level "is this generic" check in the forward and C# paths counts it instead of
`typeParameters` alone. `forwardOwnerTypeName()` spells the per-segment receiver. One model field was
added, `ObjectHandle.kotlinReadType`, carrying the applied Kotlin spelling the outer handle is read
back as, while `qualifiedName` stays the declaration's name for every lookup keyed on it. No extern
hoist rewrite was needed.

**Bookkeeping.** The holder is recorded at arity 0 for the CS0101 check (`Box` beside `Box<T>` is
legal; `Box` twice is not). Skip remarks for dropped members land on `Box<T>` only, never on the
holder, which shares its Kotlin path but has no members. `nestedOwnerScopeCollision()` skips the
member and companion arm only for a non-sealed generic `class` owner, because the members live on
`Box<T>` and the types on the holder; the CS0542 arm (a child named like its owner) stays.

**Declined, each a named skip.** Reason text is quoted from `NugetProcessor.kt`
(`unsupportedNestedOwnerReason()` and `unsupportedNestedCandidateReason()`); the diagnostic hints in
`ForwardDiagnostic.kt` were reworded to match.

- A generic `interface` owner (`interface Feed<T> { class Entry }`): "a generic `interface` owner
  has no non-generic C# type to hold its nested declarations". The holder would be a static class
  with an interface-style name.
- A generic sealed base or arm owner: "a generic sealed base or arm is declared without its type
  parameters in C#, so it has no holder for nested declarations". The sealed route (ADR-009) renders
  the base without `<T>`. Since the 2026-10-04 amendment to ADR-009 the whole generic sealed
  hierarchy is a named skip, so this reason now names a declaration that is not declared at all.
- A child of a captured inner class (`Box<T> { inner class Seal { inner class Bolt } }`): "an
  `inner class` that captures a generic owner's type parameters is itself generic in C#". A type
  nested in a generic C# class can hold no extern (CS7042).
- An inner class whose own type parameter reuses a captured name (`inner class Echo<T>` in
  `Box<T>`): "its type parameter `T` shadows the captured type parameter of `pkg.Box`". Kotlin
  allows it; the flattened `Box.Echo<T, T>` is CS0692.
- An inner class of a multi-bound generic owner (`class Arena<T> where T : Comparable<T>, T : Pet {
  inner class Lane }`): "it captures the multi-bound type parameter `T` of `pkg.Arena`, which has no
  single erased type to read the outer instance back as". A multi-bound parameter is read back
  star-projected, and Kotlin prohibits a `T`-taking member on `Arena<*>.Lane`. Verified by a Tier 1
  cell. `Arena.Gate`, a non-inner child, binds on the holder as usual.
- An `enum class` owner: "an `enum class` owner has no C# declaration block to nest a type into".
  Unchanged.
- A nested generic `value class` (`Basket.Wrap<T>`) is refused as a top-level one is
  (`SKIPPED_UNSUPPORTED_TYPE`), not as a nested declaration.
- A member that returns a captured inner class (`fun latchAt(): Latch`) is refused named at
  the member as a generic reference, under ADR-147's admission rule: a closed generic reference is
  refused at every member position. The declaration binds; the member does not.

The hint for the captured-inner shapes (shadowed and multi-bound) is the inner-class one: drop `inner`, take the outer
instance as a constructor parameter, and move the class to the top level of its file. The
owner-kind skips (generic `interface`, generic sealed, `enum class`) say to move it to the top level
of its file.

**Known limits outside this decision, since closed.** Two bugs in the generic sealed route
predated this ADR and were found while testing the generic sealed owner. A generic sealed base
emitted `asStableRef<Outcome>()` with no type argument (`exports/SealedClassExports.kt:52`), so the
generated Kotlin did not compile. A generic sealed arm `Ok<T>` rendered its constructor as `Ok(T v)`
inside a non-generic C# class (CS0246). Both were verified. A generic sealed hierarchy is now a
named skip on both halves, reported once as `SKIPPED_UNSUPPORTED_TYPE` with its arms listed, so
neither bug can be reached; binding it is a ROADMAP item, not a fix. See the 2026-10-04 amendment
to [ADR-009](009-sealed-class-mapping.md).

## Consequences

- ROADMAP's Phase 4 line for generic nested types closes. No line is added: the declined shapes
  above are decisions recorded here.
- `SKIPPED_NESTED_DECLARATION` survives for: an `enum class` owner, a generic `interface` owner, a
  generic sealed base or arm owner, a child of a captured inner class, an inner class that shadows a
  captured parameter or captures a multi-bound one, a `value class` owner, and a nested sealed hierarchy candidate.
- A consumer sees a static `Box` beside `Box<T>` whenever the Kotlin class has declared nested
  declarations. Additive: no existing C# name changes. `typeof(Box<>).GetNestedType("Lid")` is
  `null` by design; the type is `Box.Lid`.
- `IntegrationTests/GenericDependencyOwnerTests` flips: `ParcelDesk.Lid()` now exists and returns
  `Parcel.Lid`.
- ADR-133's and ADR-134's generic-owner deferral, and ADR-141's amendment, point here. ADR-134
  Alternative 2 stands: nothing is hoisted to an invented name.
- Companion statics of a generic class would fit the holder. Not decided here.

## Evidence

Verified by build: `:nuget-processor:test` 1677 passed, 0 failed; the native pipeline's
`IntegrationTests` 3075 passed and `LeakTests` 173 passed (assembled stack); seven AOT shapes.

Fixture `test-library/.../nested/GenericNested.kt`: `Tote.Purse<T>`, `Tote.Charm<T>`, `Teapot<T>`
with `Lid`, `Defaults`, `Blend`, `Cozy<U>`, `Strainer` and `Infuser<U>` (flattened to
`Teapot.Strainer<T>` and `Teapot.Infuser<T, U>`), and `TeaShop`. Consumer usage in
`IntegrationTests/GenericNestedTypesTests.cs`. Tier 1 cells in `Tier1NestedTypesTest` cover each
declined shape, the holder for a nested generic owner, the CS0542 error and the interface and object
owners. Leak rows in `LeakTests/LiveHandleTests.cs`: 1l
(`GenericNestedConstructor_UsingDispose_ReturnsToBaseline`), 1m
(`HolderNestedConstructor_UsingDispose_ReturnsToBaseline`), 1n
(`GenericInnerConstructor_OuterDisposedFirst_ReturnsToBaseline`) and 1o
(`InnerOfGenericOwnerConstructor_OuterDisposedFirst_ReturnsToBaseline`).

Verified by spike (C# SDK 10.0.301, kotlinc-native 2.4.10): every constraint listed in Context,
including the flattened `new Box.Seal<int>(box, 2)` over `outer.asStableRef<Box<Any>>().get().Seal(turns)`
(`kn-b5/spike/generic-nested/flat/Program.cs`).
