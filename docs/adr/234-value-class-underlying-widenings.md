# ADR-234: Forward, a value class over `Char` or a sealed type crosses everywhere, and `Sticker<*>` is a result type parameter

## Status
Accepted

Extends [ADR-228](228-generic-value-class-erased-positions.md) (the erased-position box pair),
[ADR-226](226-generic-value-class-nullable-and-collections.md) (the star-projected parameter) and
[ADR-171](171-value-classes-at-erased-generic-positions.md) (which refused a `Char` underlying).
ROADMAP Phase 4.

## Context

Four shapes were refused or half bound:

- `fun any(): Sticker<*>` was skipped, named, although the same type as a parameter bound.
- A value class over a sealed type (`Clue` over an ineligible sealed interface, `Dossier` over an
  eligible sealed class) bound at ordinary positions but not at an erased one.
- A value class over `Char` or `Char?` had no record struct at all, so every member typed with it
  was a named skip.
- A value class over a nullable underlying (`TreatCount`, `MaybeMonogram`, `Nick`) did not bind as a
  non-null property, and its `MutableStateFlow` setter was missing.

## Decision

### 1. A star- or `out`-projected result takes a caller-supplied type parameter

`fun anySticker(): Sticker<*>` binds as `Sticker<TSticker> AnySticker<TSticker>()`, the rule
ADR-226 gave a star-projected parameter. It covers `Sticker<out Animal>`, a nullable result
(`Sticker<TSticker>?`), top-level functions and class members, and a nullable star-projected
parameter keeps its type parameter too.

A `Sticker<object?>` could not be read when this was decided: there was no boxed-primitive read
for `object`, `kotlin.Any` had no bridge type, and ADR-208 refused `Box<Any>`. ADR-237 has since
given `kotlin.Any` the `object` wire; the type parameter stays, since it keeps the caller's type. A type parameter reuses the read every own-`T`
result already has. A `TSticker` that disagrees with what Kotlin returned fails exactly as a wrong
`as T` does in any `fun <T> f(): T` result.

### 2. A sealed underlying joins the erased-position gate

The erased-position gates admitted an object handle alone. They now also admit the sealed-helper
protocol (ADR-204) and an eligible sealed base. The box pair is unchanged; the unbox reads through
`I<Name>.FromHandle` or the base's `FromHandle`. `Box<Clue>`, a `Flow<Clue>` element, an awaited
`Clue` and a `MutableStateFlow<Clue>` write all bind.

### 3. `Char` and `Char?` are value underlyings

`valueClassUnderlyingRole` classes `Char` and `Char?` as VALUE, like a primitive. The struct property
is `char` or `char?`, the C# constructor runs the Kotlin `init` through the create export as it does
for a `String` underlying, and `Sticker<Char>` stops being an undeclared value class. The value
class binds as an ordinary parameter, result, property, collection element and map key, and at every
erased position. `Char?` rides the ADR-079 has-value pair with the value as a `ushort`.

### 4. A non-null value class over a nullable underlying binds as a property

The property planner resolves a value class whose underlying admits `null` to its box handle even
when the property itself is non-null, so `val count: TreatCount` binds. The
`MutableStateFlow<TreatCount>` setter writes through the same box.

## Consequences

- `Sticker<*>` at an interface member result still skips by name.
- A nullable map key stays refused ([ADR-083](083-nullable-collection-components.md)),
  `Map<Monogram?, V>` included.
- No export changes shape for an existing value class. A `Char` underlying adds the create, box and
  unbox exports every VALUE value class already has.

## Verified claims

The integration tests in `ValueClassUnderlyingWideningTests` run each position (ordinary, `Box`,
`Flow` element, `MutableStateFlow` write, awaited result) for `Monogram`, `MaybeMonogram`, `Clue`,
`Dossier`, `TreatCount` and the closed `Sticker<Char>`, plus the star and `out` results and a
space refused by `init` on the way in.

## Inferred claims

- That `Sticker<object?>` cannot be read, and the reasons given for it in decision 1, come from
  reading the marshalling code, not from a failing probe.
- A wrong `TSticker` failing like a wrong own-`T` cast is argued from the existing own-`T` result,
  not exercised by a test for the star case.

## Amendment: ADR-171, ADR-220, ADR-226, ADR-228

- ADR-171 listed a `Char` underlying and ADR-220 listed `Sticker<Char>` as refused. Both bind now.
- ADR-228 admitted an object-handle underlying at an erased position only. A sealed underlying binds.
- ADR-226 bound a star-projected parameter only. A result binds too.
