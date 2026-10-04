# ADR-198: Forward, a bound with no closed Kotlin spelling binds through a local generic trampoline: `T : Enum<T>` is `where T : struct, global::System.Enum`

## Status
Accepted

Amends [ADR-015](015-generic-type-constraint-mapping.md) (the Enum row and both known limits of its
multi-bound amendment) and [ADR-197](197-method-type-parameters-on-the-callable-plan.md) (an `Enum<T>`
bound is no longer refused). Builds on [ADR-147](147-generic-class-methods.md) and the enum write
route of [ADR-094](094-reflection-free-generic-dispatch.md)'s 2026-10-04 amendment.

## Context

`class Podium<T : Enum<T>>` and `fun <T : Enum<T>> label(x: T): String` broke the build: the
generated Kotlin did not compile on either generic route, and a Tier 1 cell pinned the failure. Both
routes erase `T` to one closed Kotlin type, the bound's spelling, and use it as the owner's type
argument and as the `asStableRef<...>()` read type. `Comparable<Any?>` works for that because
`Comparable` is contravariant. `Enum<E : Enum<E>>` is invariant and self-referencing, so no closed
type is within it; the same holds for `T : Node<T>` with an invariant `Node<T>`.

The C# half dropped the `Enum` bound to `where T : notnull`, which admits `int` and every wrapper.
It also rendered a generic interface bound without its arguments (`where T : IRival`, CS0305).

Constraints: `@CName` exports cannot be generic, and `asStableRef` is `inline reified`, so a
non-reified `T` cannot read a box. A Kotlin enum is a C# `enum`, a value type. An enum's write path
into an erased slot shipped with ADR-094's 2026-10-04 amendment, which this change relies on.

Spikes: kotlinc-native 2.4.10 (mingwX64 executable) and .NET SDK 10.0.301, outside `@CName`
libraries; the shipped shape was then verified end to end below.

## Alternatives Considered

### 1. A local generic trampoline with a phantom `Nothing` argument (chosen)
The export stays non-generic. Its body declares a local generic function that re-declares the
bounds and returns the wire type, then calls it with `Nothing`. Inside, every `T` box is read as
`Any`, checked against each bound's star-projected class, and cast to the local `T`.

Pros: compiles and runs for the constructor, property get and set, `T` and `T?` parameters and
returns, and the function route. **Verified by spike**, then by the native pipeline. No per-enum
code on the Kotlin half. Cons: one local function per affected export.

### 2. A concrete witness enum as the type argument
Rejected: it compiles and then throws on every call (`ClassCastException: class Mood cannot be cast
to class Witness`), at the box read, after an unchecked cast helper, and on a read into `Any`.
**Verified by spike.**

### 3. Star projection (`Podium<*>`, `Enum<*>`)
Rejected: reads work, but a `T` parameter is `Nothing` and a function argument fails with `argument
type mismatch: actual type is 'Enum<CapturedType(*)>'`. **Verified by spike.**

### 4. Per-enum instantiation
Rejected: output grows with enums times declarations and needs a C# dispatch the trampoline does
not. Not spiked.

### 5. A private top-level `_impl` function beside the export
The shape of the Proposed draft. Declined for a local function: `nugetTypedOwner` (ADR-015's
multi-bound amendment) is the precedent for a local generic helper, and a local function cannot
collide with another export's name.

### 6. Named skip for every such declaration
Declined: an honest binding exists.

## Decision

Alternative 1.

### C# constraint

A non-null `Enum<T>` bound maps to `struct, global::System.Enum` and is not reported as dropped. It
leads the list (`struct` first, CS0449). `global::` is required because a user type may be named
`Enum`.

```C#
public class Rosette<T> : IDisposable, INugetHandle where T : struct, global::System.Enum
public static T RequirePrize<T>(T medal) where T : struct, global::System.Enum
public static string Rank<T>(T medal) where T : struct, global::System.Enum   // on `object Judge`, a member's own T (ADR-197)
```

**Verified** by `Tier1EnumSelfBoundTest` and `IntegrationTests/EnumSelfBoundTests.cs`: a generated
enum is accepted; `int` is CS0315; `string`, wrappers and `Medal?` are CS0453.

- `T?` under a `struct` constraint is `Nullable<T>`, so a nullable `T` position emits `Wrap<T?>` and
  `FromHandle<T?>`. The earlier spelling was CS1503 and, where it compiled, read a null back as the
  enum's first entry. Pinned by `Rosette_NullableT_NullReadsBackAsNull_NotTheFirstEntry`.
- A `T` property is get-only (ADR-147's v1 rule).
- A foreign C# enum (`DayOfWeek`) satisfies the constraint, since C# cannot say "a generated enum".
  It has no box, so the call throws a catchable `NotSupportedException` before anything crosses,
  and the route keeps answering. **Verified** (`ForeignEnum_SatisfiesTheConstraint_AndThrowsCatchably`).
- A nullable `Enum<T>?` bound is dropped as before (`INFO_DROPPED_BOUND`): no C# constraint is both a
  value type and nullable.

### Kotlin trampoline

A type parameter has no closed spelling when a bound names it outside a contravariant position
(`hasUnspellableBound`: `Enum<T>`, an invariant `Node<T>`). On such an export the Kotlin half is a
local generic function `nugetTrampoline` inside the export, called with `Nothing`:

```kotlin
@CName("kn_..._rankings__rosette_outranks")
public fun export_..._rosette_outranks(handle: COpaquePointer, other: COpaquePointer, errorOut: COpaquePointer?): Boolean {
  @Suppress("UNCHECKED_CAST")
  fun <T> nugetTrampoline(): kotlin.Boolean where T : kotlin.Enum<T> {
    return try {
      (handle.asStableRef<Any>().get() as Rosette<T>).outranks(
        other.asStableRef<Any>().get().let { bounded -> bounded as kotlin.Enum<*>; bounded as T })
    } catch (e: Throwable) { /* the route's existing error-out body */ }
  }
  return nugetTrampoline<Nothing>()
}
```

- The local function returns the wire type, never `T`: with `Nothing` a `T` return is typed
  `Nothing`.
- Every read casts to each bound's star-projected class before `as T`, so the bound stays checked: a
  non-enum box fails inside the `try` as a catchable `ClassCastException`, which C# sees as
  `KotlinInvalidCastException`. A different enum is not detected, since the cast checks only the
  erased `Enum` class; C# typing prevents it. **Verified by spike only** (ordinals compared); no
  shipped test pins it.
- A read of an existing instance (a getter, `equals`, `hashCode`, `toString`) takes no `T` and keeps
  the star-projected `Owner<*>` receiver.
- Every other bound keeps its ADR-015 spelling and checked read, and its export text is unchanged.
  A multi-bound parameter on a class that also has an unspellable one is trampolined too
  (`isTrampolined`), because the trampoline's casts and ADR-015's witness-typed receiver cannot meet
  in one call.

The trampoline covers the generic class route (constructor, members, properties, `copy` of a data
class), the generic function route, and a member function's own type parameter on a class, object
and sealed arm (ADR-197), where the call names the trampoline's `T` instead of an erased argument.

### Invariant self-referencing bounds and generic interface bounds

`T : Node<T>` round-trips through the same trampoline. `Chain<T> where T : Node<T>, T : Pet` binds as
`where T : INode<T>, IPet`. A generic interface bound now renders its type arguments in C#
(`where T : IRival<T>`, was CS0305), single or multi-bound, mapped through the class's renamed C#
parameter (`A` becomes `TA` where a member forces it). A bound C# cannot spell (a use-site projection,
a builtin collection argument) is dropped from the `where` clause with `INFO_DROPPED_BOUND` naming it.

### Unchecked-cast warnings

An export that reads a non-trampolined `T` through a cast to a generic bound (`as
kotlin.Comparable<Any?>`) carries a per-declaration `@Suppress("UNCHECKED_CAST")`. The native build
log has none left (**verified**); ADR-015's builtin amendment had accepted the warning.

### Fixed alongside: `fun <T> describe(x: T): String`

A legacy-route function whose return is neither `T` nor a generic class crashed generation with
`ERROR_INTERNAL_GENERATOR_FAILURE`: the Kotlin half read the argument as a generic-class handle and
the C# half as a `T`, and the ABI contract check stopped the disagreement. `legacyGenericRouteParameterIndex`
now routes only a return of `T` or of a generated generic class over `T`. A `String`, `Unit` or
builtin `List<T>` return is refused on both halves and named by `warnUnroutedGenericFunctions`.

### Declined

Each is a named skip, and no ROADMAP line tracks any of them.

- `inline fun <reified T : Enum<T>>`: `SKIPPED_UNSUPPORTED_COMBINATION`. The trampoline's `T` is not
  reified and neither is `Nothing`, so no argument the export can name is legal. A language gap.
- `suspend` and `Flow` members on such an owner keep the generic-owner skip of ADR-147.
- An `inner` class capturing the Enum-bound `T`: ADR-196's skip, whose message now says
  "self-referencing-bounded".
- `T : Pet, T : Enum<T>` binds as `struct, global::System.Enum, IPet`, but a C# enum cannot
  implement `IPet`, so no type satisfies it.
- A member `T` bounded by another type parameter stays refused (ADR-197).

## Consequences

- `Tier1BuiltinGenericBoundTest`'s Enum cell flips from pinned failure to compiled clean.
- ADR-015's Enum row and both known limits of its multi-bound amendment close here; ADR-197's
  declined list loses the `Enum<T>` bound.
- C# consumers get the enum-only constraint, so `int`, `string` and wrappers fail at compile time
  instead of at the call.
- Additive: no shipped entry point or symbol changes. Exports with only spellable bounds keep their
  text apart from the `@Suppress` annotation.

## Evidence

Verified by execution on the assembled stack: `:nuget-processor:test` 1739 passed, 0 failed
(`Tier1EnumSelfBoundTest`, `Tier1EnumSelfBoundInteractionTest`, which compile the generated Kotlin
and a C# consumer, and the updated `Tier1BuiltinGenericBoundTest`, `Tier1MultiBoundGenericTest` and
`Tier1MemberGenericMethodTest`); native pipeline `IntegrationTests` 3123 passed
(`EnumSelfBoundTests`, `EnumGenericRouteInteractionTests`), `LeakTests` 183, seven NativeAOT shapes.
Leak rows (`LeakTests/LiveHandleTests.cs`): `EnumSelfBound_ClassAndFunctionRoute_ReturnsToBaseline`,
`EnumSelfBound_ThrowPaths_ReturnToBaseline` and `InvariantRecursiveBound_ReturnsToBaseline`.
Fixtures: `test-library/.../rankings/Medals.kt` (`Rosette`, `requirePrize`, `Judge`) and
`.../multibound/Chains.kt` (`Chain`, `lead`).

Found and not fixed: a function returning a generic abstract class at a closed type
(`fun stock(): Shelf<String>`) generates `new Shelf<string>(handle, out _)` (CS0144). A backing
wrapper cannot nest in the generic class (CS7042), so it needs the non-generic holder. Pinned by a
known-limit cell in `Tier1EnumSelfBoundInteractionTest`; tracked in ROADMAP Phase 4.
