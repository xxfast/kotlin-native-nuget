# ADR-197: Forward, a member function's own type parameter binds on the callable plan: `fun <T>` on a class, object, companion or sealed arm as a C# generic method

## Status
Accepted

Extends [ADR-147](147-generic-class-methods.md) (admission: "own type parameters on the method").
Narrows [ADR-116](116-sealed-subclass-methods-on-the-callable-plan.md): a generic method is no
longer a `SEALED_SUBCLASS_UNROUTED` residual.

## Context

A Kotlin member function that declares its own type parameter
(`class Groomer { fun <T> echo(item: T): T }`) had no C# member on any owner. Every member-owner entry
builder in `forward/ForwardCallablePlanner.kt` returned a structural `GENERIC` skip before planning,
and the only generic-function route, `addGenericFunctionExports` / `translateGenericFunction`, is
called for top-level functions only. **Verified by reading.** A sealed arm shared the gap, and its
ROADMAP item waited on an ordinary class getting a route.

Two mechanisms for `T` already shipped:

- The legacy top-level route: typed width variants (12 Kotlin exports plus `_object`, 6 C# widths),
  exactly one parameter typed `T`. **Verified by reading.**
- ADR-147: `BridgeType.TypeParameter` on the ADR-062 plan, one export, every `T` position crossing
  as a boxed handle through `NugetMarshal.Wrap<T>` / `FromHandle<T>`, both of which have primitive
  branches. The classifier scoped it to a *class's* own type parameter. **Verified by reading.**

Other targets: Kotlin/JVM emits a generic method; ObjC export erases a method's type parameter to
`id`; Swift export does not yet carry generics. C# consumers expect `T Echo<T>(T item)` with
inference at the call site.

## Alternatives Considered

### 1. Extend ADR-147's `TypeParameter` kind to a method-owned type parameter (chosen)
One export per method, boxed-handle wire, the plan's existing projection and emitter. No new ABI
shape, no runtime change, any number of parameters and type parameters, and primitives round-trip
through the shipped `Wrap<T>` / `FromHandle<T>`. Cost: a primitive `T` boxes (one handle per `T`
position), and member and top-level generics use different wires until the top-level route moves.

### 2. Key the legacy width-variant route to class owners
`Echo<int>` would cross as a raw `int`. Cost: 13 exports per method, one `T` parameter and no
others, the object variant's `NotSupportedException` for a builtin, hand-built bodies, and a receiver
handle bolted onto a route the codebase is retiring.

### 3. Keep the named skip
Zero cost; the author writes a non-generic wrapper per type.

## Decision

Alternative 1.

```C#
int n = groomer.Echo(4);                // fun <T> echo(item: T): T
using Tabby cat = groomer.Adopt(oreo);  // fun <T : Furry> adopt(pet: T): T, where T : IFurry
int p = fetch.Pick(7);                  // declared on a sealed arm
```

### Owners

A member function's own type parameter routes on the plan, on ADR-147's boxed-handle `TypeParameter`
wire, on an ordinary class, an object, a companion, a generic class (`Box<T>.map<U>`), an abstract
class (`public abstract T Groom<T>(T item);`), a sealed base (`virtual`) and a sealed arm. A generic
member inherited from an unexported base class binds on the subclass that re-homes it. One predicate,
`isForwardGenericMemberOwner` in `forward/ForwardMemberTypeParameters.kt`, decides the owner; the
planner's structural `GENERIC` skip is kept for every other owner.

### C# shapes

- `public T Echo<T>(T item)`, two parameters `public string Pair<T, U>(T first, U second)`.
- `where T : IFurry` for a bound; `where T : notnull` for `T : Any`.
- `public T? Nothing<T>()` for `T?`.
- A multi-bound `T` binds and lists every bound: `where T : IFurry, ITricky`. It is refused only
  when no parameter mentions it, since no type argument spells it and nothing infers it. (The
  Proposed draft refused every multi-bound `T`.)
- `Result<T>` returns the payload and gains ADR-195's Try twin:
  `bool TryWrap<T>(T item, [MaybeNullWhen(false)] out T value, out Exception? failure)`.
- The C# type parameter name gives way when it equals the member's or an owner's name (CS0694):
  `fun <Hold> hold` renders `THold Hold<THold>(THold item)`.

### Overrides

An `override` prints no `where` clause (restating one is CS0460), and an override that spells a
`T?` prints `where T : default` (CS0115 and CS0453 without it). **Verified by spike** (`dotnet
build`, SDK 10.0.301, `net8.0`) before implementation, and by the Tier 1 consumer compile after. The
rule applies to a planned override and, in `againstSealedBase`, to an arm's method matched against its
base's, which now also matches on generic arity, position and bounds, never on the type parameter's
name, so an arm override that renames the type parameter still renders `public override R
Relay<R>(R item)` and `public override P? Comb<P>(P? pet) where P : default`. An ordinary class
override needed no change, since C# accepts different type parameter names. **Verified by
execution**: `Tier1MemberGenericMethodTest` pins those signatures and the native fact
`RenamedOverride_DispatchesThroughTheBase` dispatches through the base.

### Kotlin side

The generated call names its type arguments: `echo<Any?>(...)`, `adopt<Pet>(...)`, so a `T` only the
return mentions (`fun <T> make(): T?`) resolves. A multi-bound `T` passes `_` and Kotlin infers it
from the argument, which is read as the first bound and smart-cast to the others. **Verified by
execution** (the Kotlin compiled in the green build; `Tier1MemberGenericMethodTest` pins each form).

A bounded read is a checked cast, so a type argument outside a bound C# cannot name throws
`KotlinInvalidCastException` before the body runs, as ADR-015's 2026-10-04 amendment requires of
every bounded `T`. A member's `T` argument goes through `loweredArgument` and `forwardBoundedRead`:
`(pet.asStableRef<Any>().get() as ...Pet)` for a single bound, `as ...Pet?` for a nullable bound,
and a cast chain for a multi-bound `T`. **Verified by execution**: the Tier 1 test "every bounded
member type parameter is read through a checked cast", and the native facts
`BuiltinBound_SatisfyingTypeArgument_UsesTheBoundsApi` and
`BuiltinBound_TypeArgumentOutsideTheKotlinBound_IsRejectedAtTheRead`, where `Portion(3u)`,
`Portion("tuna")`, `Salon.Weigh("tuna")`, `basket.Measure(3u)` and `fetch.Best(oreo, mylo)` throw
`KotlinInvalidCastException`.

### Planning and constraints

`ForwardPublicSignature.typeParameters` carries each own type parameter (C# name, Kotlin name, the
Kotlin type argument). C# constraints come from ADR-015's bound speller, which needs the export
context the planner does not hold, so `withMethodTypeParameterConstraints` fills them in a pass after
planning, once, before either half projects the plan.

### Declined

Each is a named skip, the same structural `GENERIC` reason as before. The warning appends the
specific shape that was refused as ` (here, <reason>)`, with a clause such as "it is declared on an
interface" or "its type parameter `T` is reified" (`forward/ForwardMemberTypeParameters.kt`); the
hint stays the general remedy. No ROADMAP line tracks any of them.

- Generic methods on an interface, an enum, a value class, and extension functions.
- A `reified` type parameter, which the boxed call would instantiate at its erased bound.
- A bound that is itself a type parameter, and an `Enum<T>` bound.
- A lambda or `Flow` anywhere in the signature.
- A generic member that is half of an add/remove pair.
- `Result<List<T>>` and a `T` nested in another type (`List<T>`, `Box<T>`).
- A type parameter that shadows one actually in scope, the owner's or one an `inner` class captures (CS0693).

### Out of scope

The top-level `fun <T>` width-variant route is unchanged and is not migrated onto the plan. The
`suspend` lambda parameter half of the sealed-arm ROADMAP item is not built: no owner has a route for
a `suspend (T) -> R` parameter, so it needs the Phase 7 protocol and stays tracked on the Phase 7
"suspend lambda as a function parameter" line, which now says "including on sealed arms".

### Interactions

Three interactions with routes that landed beside this one were settled when the commit was rebased
onto the full stack. **Verified by execution** by `Tier1MemberGenericNestingTest` (three tests, each
compiling the generated C#):

- An abstract class, or an abstract sealed arm, with a backing wrapper (ADR-009's 2026-10-04
  amendment) overrides each abstract member with a call-through. The wrapper's override of a generic
  abstract member follows the override rule above: no `where` clause, and `where T : default` for a
  `T?` (`backingOverrides()`).
- A generic method on an `inner` class, or on a type nested in a generic owner's holder (ADR-196),
  binds. The shadow refusal counts only type parameters actually in scope: the owner's own plus
  those an `inner` class captures. The C# type parameter name also avoids the captured names. A
  method whose `T` does shadow one is named "(here, its type parameter `T` shadows its owner's)".
- A generic method named like a nested type collides exactly as a plain method of that name does
  (ADR-133, ADR-179 compare rendered C# names), so it gets the same collision diagnostic and no new
  rule.

### Fixed alongside

Two bugs in ADR-195's Try twin, found because a generic class is the first owner to return a
`Result<T>` through it:

- `CirClassRenderer.forwardedArguments` dropped `out` when a `[MarshalAs]` attribute preceded it, so
  any `Result`-returning member of a generic class failed to compile (CS1620, CS0177, CS0269).
- `resultTryOutParameters` omitted `[MaybeNullWhen(false)]` for an unconstrained bare `T`
  (CS8601 at the twin's `default`).

## Consequences

- `fun <T>` on an ordinary class, object, companion, sealed base and sealed arm binds.
  `SEALED_SUBCLASS_UNROUTED` on an arm is left with the `suspend` lambda parameter and any generic
  shape the member route refuses.
- `Tier1UnroutedPositionsTest` (class and object structural cells) and `Tier1SealedArmResidualSkipTest`
  (`pick`) flip from named skip to bound; the `GENERIC` sentence and hint now name the owners and
  positions that bind.
- Member generics box primitives; top-level generics keep typed widths. Moving the top-level route
  onto this mechanism would rename shipped entry points, so it is a separate, breaking change.
- Additive: no shipped entry point changes.

## Evidence

Verified by execution: `:nuget-processor:test` 1724 passed, 0 failed
(`Tier1MemberGenericMethodTest`, which compiles the generated Kotlin and a C# consumer); native
pipeline `IntegrationTests` 3103 passed (`MemberGenericMethodTests`), `LeakTests` 178, seven NativeAOT
shapes. `Pick<int>` round-trips end to end on a sealed arm and `Echo<int>` on a class, which settles
the Proposed draft's open question. Leak rows (`LeakTests/LiveHandleTests.cs`):
`MemberGenericMethod_BoxedTypeParameter_ReturnsToBaseline`,
`MemberGenericMethod_ThrowingMember_ReturnsToBaseline` and
`MemberGenericMethod_BoundCastFails_ReturnsToBaseline`. Fixture:
`test-library/.../membergeneric/Groomers.kt`.
