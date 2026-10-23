# ADR-220: Forward, a generic value class renders as a positional `readonly record struct Sticker<T>(T Value)`, and a closed instantiation crosses as its substituted underlying

## Status
Accepted

Extends [ADR-014](014-value-class-mapping.md) and [ADR-035](035-value-class-primary-constructor-validation.md)
(the record-struct mapping), [ADR-077](077-value-classes-at-ordinary-positions.md) (ordinary positions;
the 2026-10-10 amendment's Rule 2 is what keeps erased positions refused) and
[ADR-147](147-generic-class-methods.md) / [ADR-211](211-top-level-generic-functions-on-the-plan.md) (the
bare-`T` wire). Closes the deferral ADR-212 and ADR-216 named "a `T` in a generic value class".
ROADMAP Phase 4.

## Context

```kotlin
value class Sticker<T>(val value: T)
value class Decal<T>(val serial: Long)

fun count(): Sticker<Int> = Sticker(3)
fun <T> stick(value: T): Sticker<T> = Sticker(value)
```

On the stack tip (`ebad6e56`) neither shape had a C# type, and one of them broke the consumer's
build (**verified** by the red fixture `test-library/.../stickers/Stickers.kt`):

- `Sticker<T>`'s underlying classified as `BridgeType.TypeParameter` (or, unconstrained,
  `Nullable(TypeParameter)`, ADR-147's 2026-09-27 amendment), which `valueClassUnderlyingRole`
  maps to `REFUSED`. No struct was declared and every member typed with one was
  `SKIPPED_UNSUPPORTED_TYPE` (`UNDECLARED_VALUE_CLASS`). A member's own `T` inside one was refused
  separately as "its type parameter is nested in `Sticker<T>`".
- **A phantom `Decal<T>(val serial: Long)` was worse.** Its underlying classified as `Long`, so it
  took the VALUE role: C# got a non-generic `Decal` and Kotlin got a `CreateChecked` export,
  `kn_testlibrary_stickers__decal_create`, whose body `Decal(serial).serial` cannot infer `T`. The
  whole module's generated `CNameExports.kt` failed to compile. Any consumer declaring a typed ID
  (`value class Id<T>(val raw: Long)`), the canonical generic value class, hit this.
- The classifier never substituted the use-site argument: `valueClass()` classified the declared
  parameter type, not the instantiated one.

Kotlin itself erases a generic value class's underlying to the type parameter's bound and boxes it
at a generic position ([kotlinlang.org/docs/inline-classes.html](https://kotlinlang.org/docs/inline-classes.html),
inferred from documentation). The bridge never sees that representation: every export is Kotlin
source that constructs through the source-level constructor and reads the source-level property.

## Alternatives Considered

### 1. Positional generic record struct; a closed instantiation crosses as its substituted underlying (chosen)

One C# type for every instantiation, the shape C# developers know from `KeyValuePair<TKey, TValue>`.
`Sticker<Int>` is a value class over `Int` to every planner and emitter, so it rides every route a
non-generic value class over `Int` rides, with no new export shape. Cons: `init` cannot run at C#
construction, and erased positions stay refused until a per-instantiation box pair exists.

### 2. Cross the boxed Kotlin value as a `StableRef` handle, C# `sealed class Sticker<T> : IDisposable`

Rejected: it loses value semantics (`==`, `with`, no disposal) and makes a generic value class the
only value class that is not a struct.

### 3. One non-generic record struct per closed instantiation (`StickerOfInt`)

Rejected: not idiomatic, unbounded name growth, and an own `T` (`Sticker<T>`) has no spelling.

### 4. Option 1 plus a per-instantiation ADR-171 box/unbox pair, so erased positions work too

Deferred, not rejected: an export pair and a `Boxers` key per closed instantiation's runtime type
(`typeof(Sticker<int>)`). No design yet; a ROADMAP line.

## Decision

### 1. Which generic value classes are declared: the `GENERIC` role

`ForwardValueClassUnderlying.GENERIC`: the value class declares type parameters, and its
underlying is one of its own type parameters (`val value: T` or `val value: T?`) or a phantom one
whose underlying already crosses (classifies VALUE or REFERENCE, `val serial: Long`). Anything else
generic (`val items: List<T>`) stays `REFUSED` by name, unchanged. There is no positional gate: a
nested generic value class under a non-generic owner is declared too (ADR-134), pinned in
`Tier1NestedTypesTest` (**verified**). A generic owner stays ADR-133's skip.

`GENERIC` plans nothing on the value class itself: no `_create` (the phantom compile break above),
no ADR-171 box/unbox pair (already excluded for a generic value class) and no members. Each public
own member or secondary constructor is named once, `SKIPPED_UNSUPPORTED_TYPE` ("declared on the
generic value class ..., whose C# record struct carries no members"), because a member would need
its receiver substituted per instantiation, which the value-class member route has no spelling for.

### 2. C#: a positional generic record struct, no constraints, no `CreateChecked`

```csharp
public readonly record struct Sticker<T>(T Value)
public readonly record struct Decal<T>(long Serial)
```

The REFERENCE shape (`cir/CirObjectRenderer.kt`, `renderReferenceValueClass`) with the type
parameters on the header (`CirValueClass.typeParameters`). The VALUE shape's hand-written
constructor calls a per-class `CreateChecked`; a generic struct has one constructor for every `T`
and nothing per instantiation to call. No `where` clause: only Kotlin-declared closed
instantiations appear in signatures, and an own-`T` member carries its bound on its own `where`
(`where T : notnull` for `T : Any`, ADR-147).

A KDoc `[Sticker]` link falls back to `<c>Sticker</c>`, as a generic class's does (ADR-147,
`CirDocLinks.isGenericDeclaration`): a bare `cref="...Sticker"` failed the fixture's
`nugetCompileInterop` with CS1574 under `GenerateDocumentationFile` (**verified**, first
`verify.sh` run).

Consequence: `new Sticker<int>(-1)` constructs in C#, and Kotlin's `init` runs at the first
crossing, when the export re-wraps it with the source-level constructor inside its error-slot
`try`. The integration row `Init_DoesNotRunAtCSharpConstruction_ButAtTheFirstCrossing` pins both
halves (`Bump(new Sticker<int>(-1))` and `Stick(-1)` throw `KotlinArgumentException`). A non-generic
VALUE class runs `init` at C# construction; the asymmetry is documented on the value-classes page.

### 3. The wire: substitute the use-site argument into the underlying

`ForwardBridgeTypeClassifier.genericValueClass()` classifies the matching use-site argument in
place of the declared `T` (re-applying a declared `?`), spells `csharpType` with the C# arguments
(`Sticker<int>`, `Sticker<global::Ns.Cat>`, `Decal<global::Ns.Cat>`), and carries the classified
arguments in `typeArguments`. Downstream:

- Kotlin unwraps with `.value` and re-wraps with the bare `pkg.Sticker(<lowered>)`; neither spells
  type arguments, and Kotlin infers them from the expected type at the call argument, setter or
  collection `map` (**verified**: the Tier 1 JVM compile and the native `test-library` build).
- C# reconstructs with `new Sticker<int>(x)`, positional, no P/Invoke (**verified**, generated).
- Nullability rides unchanged: `Sticker<Int>?` takes ADR-079's has-value pair,
  `Sticker<String>?` and `Sticker<Cat>?` the null pointer (**verified**, Tier 1 and integration).
- `default(Sticker<string>)` into a non-null `Sticker<String>` slot throws the existing
  `valueClassUnderlyingOrThrow` `ArgumentException` in C#, because `hasNullDefault()` reads the
  substituted underlying (**verified**, `DefaultClosedSticker_IntoANonNullStringSlot_ThrowsInCSharp`).
- `List<Sticker<Int>>` binds as `IReadOnlyList<Sticker<int>>`: components project without a box
  (Rule 2), so it was green on the first run and is pinned (**verified**, Tier 1 and integration).
- An instantiation whose substituted underlying no value-class wire carries (`Sticker<Char>`,
  `Sticker<List<Int>>`) is the non-generic refusal exactly: `UNDECLARED_VALUE_CLASS`, named.
- Erased positions stay named skips by construction. A non-empty `typeArguments` fails
  `isErasedTypeArgument` (so `Box<Sticker<Int>>` is refused for its argument) and
  `isLambdaTypeArgument`; a `Flow`/`StateFlow`/`MutableStateFlow` element and an awaited `suspend`
  result are refused as "not the generic type `Crate<Int>`" (**verified**, Tier 1). Carrying the
  arguments matters: with them empty, `Box<Sticker<Int>>` bound and its `Item` would have read
  `FromHandle<Sticker<int>>` with no `Factories` entry.

### 4. A member's own `T` inside the struct

`forwardMemberGenericRefusal` admits a type that is a generic value class with an own-`T`
underlying, instantiated at the member's own `T` (or `T?`) at that position, non-null outer, at the
top of a parameter or the result (`isOwnInsideGenericValueClass`). It is deliberately not a
component of `carriesOwn`, so `List<Sticker<T>>` and `Sticker<T>?` keep the "nested in" refusal.

The substituted underlying is then `TypeParameter` (for `T : Any`) or `Nullable(TypeParameter)`
(unconstrained `T`, or an explicit `Sticker<T?>`, which classifies identically), and the struct
crosses on the bare-`T` handle wire, null pointer in-band:

```kotlin
// fun <T> stick(value: T): Sticker<T>
val result = pkg.stick<Any?>(value_?.asStableRef<Any>()?.get()).value   // then the bare-T result body
// fun <T> peel(sticker: Sticker<T>): T
pkg.peel<Any?>(pkg.Sticker(sticker?.asStableRef<Any>()?.get()))
```

```csharp
public static Sticker<T> Stick<T>(T value)          // ... return new Sticker<T>(NugetMarshal.FromHandle<T>(nativeResult));
public static T Peel<T>(Sticker<T> sticker)         // boxes sticker.Value exactly as a bare T
public static T Firm<T>(Sticker<T> sticker) where T : notnull
public static Sticker<T?> Blank<T>(T? value)
```

New arms: `isOrdinaryValueClassUnderlying` and `underlyingWireType` (planner),
`addValueClassOrdinaryResult` and `valueClassUnderlyingLowering` (Kotlin emitter),
`valueClassUnderlyingWireCs`, `valueClassReconstructionCs`, the call argument and the box
prelude/cleanup read off `.Value` (CIR projection). `hasNullDefault()` is true for a non-null
`TypeParameter` underlying, so `default(Sticker<T>)` into `firm` (`T : Any`) throws in C# instead
of sending a null pointer into a non-null Kotlin slot (**verified**,
`DefaultSticker_IntoABoundOwnTypeParameterSlot_ThrowsInCSharp`). An unconstrained `T` admits null:
`Peel(default(Sticker<string?>))` returns null, exactly like a bare `Identity<string?>(null)`.

A `T` whose bound needs ADR-198's trampoline is bound inside `Sticker<T>` too; see the amendment below.

### Named skips

| Shape | Outcome | Why |
|---|---|---|
| `fun <T> f(): Sticker<T>?` | bound by [ADR-226](226-generic-value-class-nullable-and-collections.md) | over an unconstrained `T` it crosses as the ADR-228 box handle so `null` and `Sticker(null)` stay distinct; over a `T : Any` it rides the null pointer |
| `List<Sticker<T>>` (own `T`) | bound by [ADR-226](226-generic-value-class-nullable-and-collections.md) | the element projects to its underlying and re-wraps in Kotlin |
| `Box<Sticker<Int>>`, lambda payload/result, flow element, awaited `suspend` result or value-class parameter on a `suspend` member | bound by [ADR-228](228-generic-value-class-erased-positions.md) | one box/unbox pair per generic value class serves every instantiation; [ADR-240](240-async-routes-carry-wire-underlyings.md) carries the `suspend` and `Flow` routes |
| own members and secondary constructors of a generic value class | bound by ADR-225 | see the 2026-10-22 amendment below |
| underlying `List<T>` or any non-bare, non-phantom shape | bound by [ADR-235](235-value-class-wire-underlyings.md) (2026-10-23 amendment) | a non-generic value class over a collection, `Instant`, `Duration`, `Uuid`, `ByteArray` or interface bound earlier; a generic one over a collection of its own `T` now binds too |

## Consequences

- Additive surface, no change to any existing export; not Breaking. A module declaring a phantom
  generic value class, which failed to compile its generated Kotlin before, now builds.
- Tier 1 flips: `Tier1GenericInstanceMemberPositionTest` (the `Crate<T>` cell: `held`, `spare`,
  `fetch`, `stow`, `maybeFetch`, `maybeStow`, `listed`, `topCrate`, `topFetch`, `topStow` bind;
  `boxed`, `later`, `laterWith` and the flows stayed skipped here; ADR-228 binds them), `Tier1ValueClassNullableResultTest`
  (`vcgeneric`: struct declared, `maybe`/`label` named, `box()` binds), `Tier1NestedTypesTest`
  (`Basket.Wrap<T>` declared), and the flow-element reason text in
  `Tier1MutableStateFlowGenericElementTest` and `Tier1MutableStateFlowValueClassElementTest`.
- The diagnostics `GENERIC` hint kept its final sentence here (ADR-226 and ADR-228 later bound each shape it names) ("... and a `T` inside a generic
  value class under `?`, inside a `List`, `Set` or `Map`, or at an erased position").
- Deferred here, each a ROADMAP line citing this ADR (since bound by ADR-226 and ADR-228): `V<T>?` or `List<V<T>>` with an own `T`; erased
  positions (a per-instantiation box pair). Own members and secondary constructors were deferred here and bind under ADR-225.

### Corrections to the draft, from execution

- The phantom `T` was a named skip in the draft; it is folded in, and the red fixture
  showed it was not merely unbound but a module-wide Kotlin compile break.
- The draft refused `Sticker<T?>` and required a non-null `T`. An unconstrained `T` is
  `Nullable(TypeParameter)` (ADR-147), so the own-`T` arm takes either underlying nullability under
  a non-null outer; `Sticker<T?>` binds for free.
- The draft listed "nested generic value class" as deferred. It is declared, with no extra code.
- The draft predicted the flow-element reason would read "no box pair". It reads "not the generic
  type `Crate<Int>`", the existing generic-type refusal, because a non-empty `typeArguments` makes
  the instantiation generic to every erased gate.

### Inferred claims, listed

1. That the positional generic record struct compiles on net8.0: the Tier 1 C# compile and the
   integration projects target net10.0 here; a positional generic record struct is C# 10.
2. That Kotlin erases the underlying to the bound (documentation only); nothing here depends on it.

## Amendment 2026-10-22: a bounded own `T` binds inside the struct

A function's own `T` inside `Sticker<T>` binds whatever its bound, on top-level functions and on
class members. The original refusal assumed the re-wrap sat outside ADR-198's trampoline. It does
not: the trampoline wraps the export's whole body, so the unwrap, the call and the result's `.value`
all run inside it, and the re-wrap reads the trampolined `T` like any other.

- `T : Comparable<T>` needs no trampoline. `Comparable` is `in`, so the erased `Comparable<Any?>`
  is within the bound. C# gets `where T : notnull`, and Kotlin checks the bound.
- `T : Enum<T>` and an intersection go through the ADR-198 and ADR-217 trampoline. C# gets
  `where T : struct, global::System.Enum` for the enum bound.
- Fixed: an intersection-bound unwrap failed the Kotlin compile because `readThrough` skipped
  `ValueClass`.
- Fixed: the `default(Sticker<T>)` guard emitted CS0019 for a `struct`-constrained `T`. A default
  `Sticker<Mood>` holds the enum's first entry, so that case has no guard.

The named-skip row for a trampolined bound is gone. Leak row
`GenericValueClass_BoundedOwnTypeParameter_ReturnsToBaseline`. This amendment is **verified** by
the Tier 1 cells in `Tier1GenericValueClassTest` and the integration rows in
`GenericValueClassTests.cs`.

## Amendment 2026-10-22: own members and secondary constructors bind

[ADR-225](225-generic-value-class-members.md) supersedes the statements above that a generic value
class plans "no members" and names each public own member or secondary constructor as
`SKIPPED_UNSUPPORTED_TYPE`. Members, properties, secondary constructors and `suspend` members now
bind on the record struct for every instantiation, phantom ones included, and a member's own `<R>`
binds on any value class. The primary constructor still plans no `_create`, and `init` still runs
at the first crossing for it.

## Amendment 2026-10-22: erased positions bind

[ADR-228](228-generic-value-class-erased-positions.md) supersedes the deferral of erased positions.
`Box<Sticker<Int>>`, a lambda payload or result, a `Flow`, `StateFlow` or `MutableStateFlow`
element, a `Flow` parameter and an awaited `suspend` result or parameter bind, for closed
instantiations, the phantom `Decal<T>` and a function's own `T`. One box/unbox export pair per
generic value class serves every instantiation, and the struct registers each closed type for
reads.

## Amendment 2026-10-22: under `?` and inside collections

[ADR-226](226-generic-value-class-nullable-and-collections.md) supersedes the statement that
`Sticker<T>?` and a `List`, `Set` or `Map` of `Sticker<T>` over a function's own `T` are skipped.
`Sticker<T>?` over an unconstrained `T` crosses as the ADR-228 box handle so `null` and
`Sticker(null)` stay distinct, over a `T : Any` it rides the null pointer, and a collection element
projects to its underlying and re-wraps in Kotlin. A star-projected `Sticker<*>` parameter binds as
a synthesized method type parameter.

## Amendment 2026-10-23: `Sticker<Char>` and star results

[ADR-234](234-value-class-underlying-widenings.md) binds `Sticker<Char>` (a `Char` underlying is a
value underlying) and a star- or `out`-projected result as a caller-supplied method type parameter,
as ADR-226 did for a parameter.

## Amendment 2026-10-23: the named-skips table is historical

Every skip in the "Named skips" table (the last, a generic value class over a `List<T>` or other non-bare
underlying, by the 2026-10-23 ADR-235 amendment) has since been bound: `Sticker<T>?` and `List<Sticker<T>>` by
[ADR-226](226-generic-value-class-nullable-and-collections.md), the erased positions by
[ADR-228](228-generic-value-class-erased-positions.md) with the `suspend` and `Flow` routes carried
by [ADR-240](240-async-routes-carry-wire-underlyings.md), and a non-generic value class over a
collection or another wire underlying by [ADR-235](235-value-class-wire-underlyings.md). The table
above shows each row's outcome now; the Decision prose is left as written.

## Amendment 2026-10-23: a generic value class over a collection, and the `T?` spelling

[ADR-235](235-value-class-wire-underlyings.md)'s 2026-10-23 amendment supersedes the named-skips row
"underlying `List<T>` or any non-bare, non-phantom shape" (unchanged `REFUSED`): `Bundle<T>(val
items: List<T>)`, and a `Set<T>`, `Map<String, T>`, `List<T>?` or `List<T?>` underlying, bind at
every position this ADR and ADR-225, ADR-226 and ADR-228 bind `Sticker<T>`.

Fixed: a generic value class that uses its `T` as `T?` and is closed at a C# value type generated an
`Interop.cs` that did not compile (`Opt<Int>` for `value class Opt<T>(val value: T?)` gave CS1061,
`'int' does not contain a definition for 'HasValue'`), because C# reads an unconstrained `T?` as `T`
for a value type. The type argument now carries the nullability: `Opt<Int>` is `Opt<int?>`,
`Gappy<Int>` (`List<T?>`) is `Gappy<int?>`, `Opt<String>` stays `Opt<string>`, and a function's own
`T` is unchanged. **Verified** by `Tier1NullableUnderlyingSpellingTest` and
`NullableUnderlyingSpellingTests.cs`.
