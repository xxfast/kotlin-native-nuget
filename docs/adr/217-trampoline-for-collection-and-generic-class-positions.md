# ADR-217: Forward, a `T` whose Kotlin spelling needs a type variable runs under the trampoline at a collection or generic-class position: `fun <T : Enum<T>> sorted(medals: List<T>): List<T>` as `IReadOnlyList<T> Sorted<T>(IReadOnlyList<T> medals) where T : struct, global::System.Enum`

## Status
Accepted

Amends [ADR-212](212-type-parameter-as-collection-element.md) (decision 4: a trampolined `T` in a
collection is no longer refused), [ADR-198](198-unspellable-bound-trampoline.md) (the trampoline
now carries collection and generic-class positions, and an intersection with no bare witness) and
[ADR-147](147-generic-class-methods.md) (a multi-bound owner `T` mentioned only inside a
collection). Lifts the refusal [ADR-216](216-own-type-parameter-in-result-and-generic-class.md)
named "witnessed only inside `Box<T>`, which ADR-217 lifts". ROADMAP Phase 4.

## Context

Some `T`s have no Kotlin spelling outside a type variable. An unspellable bound (`T : Enum<T>`,
an invariant `T : Node<T>`) has no closed type argument within it (ADR-198). An intersection
(`where T : Pet, T : Trainable`) has no single type that names it, and ADR-147 only gets one from
a bare argument (a smart-cast witness). ADR-198's trampoline fixes the first kind at a bare
position: the export's body moves into a local `fun <T> nugetTrampoline() where T : ...`, called at
`Nothing`, where each box is checked against the star-projected bound and cast to `T`.

Every non-bare position of such a `T` was refused or broken on `main` at `5e001177` (**verified
by reading and by the red Tier 1 and xunit rows**):

- `is BridgeType.TypeParameter -> !trampolined` in `isBridgeableComponent`, `isWrappableComponent`
  (`forward/ForwardCallablePlanner.kt`) and `isReadableComponent`
  (`forward/ForwardPropertyPlanner.kt`), and `check(!type.trampolined)` in
  `typeParameterElementCast` (`forward/ForwardKotlinPlanEmitter.kt`).
- An owner's trampolined `T` took the trampoline only for a bare parameter: `trampolineParameters()`
  and `constructorTypeName` used a bare `mentions`, so `Rosette<T : Enum<T>>.rank(items: List<T>)`
  would have had no type variable.
- A multi-bound owner `T` mentioned only inside a collection (`Arena<T>.team(pets: List<T>) where
  T : Pet, T : Trainable`) got no witness and was called on `Arena<*>`, and the generated Kotlin did
  not compile: `Argument type mismatch: actual type is 'List<Pet & Trainable>', but
  'List<CapturedType(*)>' was expected`. **Verified**: `packNuget` failed on it with the red
  fixture. A sibling bare `T` (`fun mix(pets: List<T>, lead: T)`) already compiled.
- A member's multi-bound `T` witnessed only inside a generic class (`fun <T> crown(basket:
  Basket<T>): T where T : Furry, T : Tricky`) was refused by name (ADR-216): the erased read
  `asStableRef<Basket<Furry>>()` cannot infer `T : Furry, T : Tricky`.

One silent-wrong-output pitfall (**verified by reading**): ADR-212's nullable-element read for a
`T` is a plain `FromHandle<T>(h)`. Under `where T : struct, Enum`, `FromHandle<T>` returns
`default!` on `IntPtr.Zero`, which is the enum's first entry, and the lambda still compiles inside
`ReadList<T?>`. ADR-198 already fixed the same bug for a bare `T?`.

## Alternatives Considered

### 1. One type-variable rule: run under the trampoline, cast inside it (chosen)
A `T` that only a type variable can spell gets ADR-198's trampoline, and every value of it, a bare
box, a collection element, or a generic-class handle, is cast to that variable inside the
trampoline body. No ABI, runtime or C# write change.

### 2. A collection-shaped witness for `Arena.team` (`witness0: List<T>?`)
Rejected: it serves the collection case only. `crown(basket: Basket<T>)` would need a third
mechanism (an invariant `Basket<A>` cannot witness `T : A, T : B`), so the rule would live in two
places. The trampoline declaring both bounds serves both.

### 3. Keep the named refusals
Rejected: the casts are one string each and the trampoline exists; the refusals had no wire reason.

### 4. Erase to `IReadOnlyList<global::System.Enum>`
Rejected: loses inference and the `struct, Enum` constraint, and boxes every element to `Enum`.

## Decision

Alternative 1.

```C#
IReadOnlyList<Medal> podium = Judge.Sorted(new[] { Medal.Gold, Medal.Bronze });     // T inferred
IReadOnlyList<Medal?> padded = Judge.Pad(new Medal?[] { Medal.Gold, null });         // null stays null
IReadOnlyDictionary<Medal, int> doubled = Judge.Scores(new Dictionary<Medal, int> { [Medal.Gold] = 1 });
IReadOnlyList<Medal> ranked = rosette.Rank(new[] { Medal.Silver });                  // Rosette<T : Enum<T>> member
using var sash = new Sash<Medal>(new[] { Medal.Gold });                              // constructor
IReadOnlyList<Medal> ordered = Medals.Ordered(new[] { Medal.Gold });                 // top level (ADR-213)
using Bead braided = Chains.Braid(new[] { oreo, mylo });                             // where T : INode<T>
int tricks = arena.Team(new[] { mylo, cookie });                                     // where T : Pet, Trainable
using Tabby crowned = groomer.Crown(basket);                                         // Basket<T>, two bounds
```

1. **The rule.** A `T` needs the trampoline's type variable when nothing else spells it:
   - an unspellable bound, at any position (ADR-198, unchanged for a bare `T`);
   - an owner's intersection `T` that some parameter mentions, bare, inside a collection at any
     depth or inside an open generic class, and no bare parameter witnesses
     (`ForwardKotlinPlanEmitter.needsTypeVariable`);
   - a member's or top-level function's own intersection `T` that some parameter mentions and none
     witnesses, where a bare `T` and a collection of `T` witness and a generic class does not
     (`ForwardMemberTypeParameters.needsTypeVariable`).

   The two halves read different inputs (the plan's owner parameters, the KSP declaration of the
   member's own) but state one rule. **Verified by Tier 1**: `Arena.mix` keeps ADR-147's
   `nugetTypedOwner` shape (it has a bare witness) and `Arena.team` takes the trampoline.
2. **Gates.** All three return `true` for every `TypeParameter`. ADR-212's unconstrained-key refusal
   is unaffected; a non-null `T : Enum<T>` key passes (`Judge.scores`, **verified** end to end).
3. **Kotlin casts.** Before emitting, the plan's parameter types are re-marked for the trampoline
   (`withTypeVariables`, Kotlin-only: the CIR projection and the ABI contract read the plan as
   planned). A `TypeParameter` the trampoline declares casts each box and each element through one
   shared string, `typeVariableCast`: the bound checks, then `bounded as T` (`?` on each for a
   nullable element). `forwardBoundedRead` and `typeParameterElementCast` both call it. An open
   generic class over only such variables is read as `Any` and cast to its declared spelling,
   `(basket.asStableRef<Any>().get() as pkg.Basket<T>)`, since `asStableRef` is reified and a type
   variable is not; the classifier carries that spelling and its type-variable names on
   `BridgeType.ObjectHandle` (`kotlinTypeVariableReadType`, `typeVariables`).
4. **Owner `T` reaches the trampoline.** `mentionsAnywhere` (through a collection's element, key
   and value, and an open generic class's type-variable names) is used by the type-variable rule only, so by `trampolineParameters()` and
   `constructorTypeName`. The ADR-147 witness search in `ownerCall` keeps the bare `mentions`: a
   recursive one there would pass the whole list as `witness0: T?` and does not compile (**verified
   by spike** in the research round).
5. **C# nullable element read.** A `Nullable(TypeParameter)` component whose `valueType` is true
   (an `Enum` constraint) reads `FromHandle<T?>(h)`, ADR-198's bare-`T?` rule per element.
   **Verified** end to end: `Judge_PadsWithNull_AndANullElementStaysNull_NotBronze`.
6. **Runtime.** Each element rides the same `Wrap<T>` / `FromHandle<T>` instantiation at
   `T = Medal` that ADR-198 runs for a bare `T`. A returned element's box is released by the read
   (the memo's open question 3): **verified** by the leak row below.
7. **Unchecked casts.** The trampoline carries its own `@Suppress("UNCHECKED_CAST")`;
   `castsToGenericBound` keeps skipping a trampolined `T`.

### Fixed in the same change: an interface reachable only as a `T` bound

Pre-existing (on the sync plan route since ADR-213): `fun <T : Snoozer> tuckIn(snoozer: T)` with a
C#-implemented `Snoozer` threw `NotSupportedException ... has no bridge for C# implementations`
from `NugetBridge.HandleFor`, because `NugetProcessor`'s reachability walk visited a plan
parameter's interfaces but not a `T`'s bounds. **Verified** by the red xunit row
`TuckIn_CSharpImplementedSnoozer_CrossesThroughATBound`. The walk now adds every non-generic
interface that is a bound of a `T` at a plan parameter (bare, nullable or a collection
component). A `T` result is not walked: it reads back through `FromHandle<T>` at the caller's own
type and needs no bridge.

This changes the output of eight existing Tier 1 compile cells whose fixtures have such an
interface (`Pet` in `<T : Pet>` and friends): the interface now gets its backing wrapper and
`HandleFor` arm, whose function pointers need `AllowUnsafeBlocks`, which the packaged `.csproj`
and `nugetCompileInterop` already set. Each cell now passes `allowUnsafe = true`. The change is
correct for those fixtures too: each could be handed a C#-implemented `Pet` and would have thrown
before. ADR-214 fixed the same gap for suspend positions in `legacyCarriedInterfaceNames`; in the
stack the two walks share one rule, `boundInterfaceQualifiedNames()` over the non-generic interface
set, which also walks a suspend position's collection components. A ninth cell, ADR-218's
`a consumer assigns every T setter`, needed the same `allowUnsafe = true` at assembly.

### Leak harness

`EnumSelfBound_CollectionElements_ReturnToBaseline` (`LeakTests/LiveHandleTests.cs`, ADR-217
banner): five iterations inside `AssertNoLeak` of `Judge.Sorted`, `Pad` with a null element,
`Unique`, `Scores`, `Heats`, `Medals.Ordered`, `Rosette<Medal>.Rank` and `Sash<Medal>`'s
constructor and reads.

### Sample tests

- Tier 1: `Tier1TrampolinedCollectionTest` (every signature, the element casts inside
  `nugetTrampoline`, `FromHandle<T?>` for `Pad`, `team` under the trampoline and `mix` without it,
  `crown`'s `as Basket<T>` cast, the `ISnoozer` bridge arm, and a consumer compile).
  `Tier1MemberGenericMethodTest` (`Deck.moods`, `Depot.rank`) and
  `Tier1OwnTypeParameterInGenericClassTest` (`Depot.rank`) flip from refusals to bindings.
- Fixture: `rankings/Medals.kt` (`Judge`, `Rosette.rank`, `Sash`, top-level `ordered`),
  `multibound/Chains.kt` (`attachAll`, `braid`), `multibound/Rehearsals.kt` (`Arena.team`,
  `Arena.mix`), `membergeneric/Groomers.kt` (`crown`), `dozing/Lullaby.kt` (`tuckIn`).
- xunit: `IntegrationTests/TrampolinedCollectionTests.cs`.

## Consequences

- ADR-212 decision 4's trampolined sentence and its Consequences "a trampolined `T` in a
  collection" deferral are struck. ADR-216's "witnessed only inside `Box<T>`" refusal is gone.
- `Arena.team` (pre-existing on `main`, found by this ADR's research) is fixed here, not split out.
  So is its generic-class twin, found by a Tier 1 probe in this lane: `Arena<T>.swap(basket:
  Basket<T>)` under `where T : Pet, T : Trainable` generated `Arena<*>.swap(Box<Pet>)`, which did
  not compile (`Argument type mismatch: actual type is 'Box<Pet>', but 'Box<CapturedType(*)>' was
  expected`). **Verified**: probe red, then green under the trampoline; pinned in
  `Tier1TrampolinedCollectionTest`. No sample fixture or xunit row carries it.
- Found at stack assembly, on top of ADR-218: a trampolined `T` in a collection setter
  (`class Ribbons<A : Enum<A>>(var medals: List<A>)`) now plans a setter, and its Kotlin export
  cast each element `as A` outside any trampoline (`Unresolved reference 'A'`). ADR-218's
  `setterTrampoline()` looked at a bare `T` only; it now finds a trampolined `T` anywhere in the
  value's collection components, and the collection lowering assigns inside the trampoline.
  **Verified**: `Tier1GenericNullableTypeArgumentTest`'s `Duo` fixture went red, then green; pinned
  in `Tier1TypeParameterPropertySetterTest`. That `Duo` cell relied on the setter being refused, so
  it now quotes the `NULLABLE_MAP_KEY` note of a `Map<A, B>` instead.
- The `GENERIC` diagnostic hint is unchanged: none of its named refusals moved.
- Deferred: none.
