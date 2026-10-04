# ADR-199: Forward, a generic sealed hierarchy binds as `Outcome<T>` with its arms on the non-generic holder (`Outcome.Ok<T>`); an arm that fixes a variant argument is generic over it (`Outcome.Err<T>`)

## Status
Accepted

Amends [ADR-009](009-sealed-class-mapping.md) (the 2026-10-04 "generic sealed hierarchies are a
named skip" amendment), [ADR-112](112-sealed-interface-mapping.md) (eligibility no longer requires
a non-generic interface), [ADR-196](196-generic-nested-types.md) (the "generic sealed base or arm
owner" skip) and [ADR-147](147-generic-class-methods.md) (a closed generic reference binds at a
member position when it names a sealed hierarchy). Builds on ADR-196's holder, ADR-147's erased
`T` wire, [ADR-198](198-unspellable-bound-trampoline.md)'s trampoline, [ADR-094](094-reflection-free-generic-dispatch.md)'s
`Factories`, and the generic abstract `Backing<T>` wrapper on the holder (the sibling change that
closes ROADMAP Phase 4's `fun stock(): Shelf<String>` line).

## Context

`sealed class Outcome<out T>` with `Ok<T>` and `Err : Outcome<Nothing>` is the most common shape a
Kotlin library returns, and today neither it nor any member typed with it exists in C#
(`NugetProcessor.kt:1759-1790`, **verified by reading**).

What the code does today, each **verified by reading**:

- The skip is a partition on `typeParameters.isNotEmpty()` (`NugetProcessor.kt:1765-1768`), the
  index `ForwardGenericSealedHierarchies` (`forward/ForwardClassMembership.kt:804`), and the hints
  in `forward/ForwardDiagnostic.kt:1130-1178`.
- The classifier's sealed branch (`forward/ForwardBridgeTypeClassifier.kt:323-349`) yields
  `ObjectHandle(viaDiscriminator = true)`; reconstruction is the text
  `"${csharpType()}.FromHandle($wireValue)"` (`forward/ForwardCirPlanProjection.kt:1828-1832`,
  twin at `forward/ForwardCirPropertyProjection.kt:482`).
- Any other class with type parameters in scope is `SpecializedProtocol("generic declaration")`
  at every position (`ForwardBridgeTypeClassifier.kt:365-367`). The only place a closed generic
  class reference binds today is a top-level function's return, on the legacy route
  (`cir/CirFunctionTranslator.kt:478-558`). So `Outcome<Int>` at a parameter, a property or a
  class-member return is new ground, not only the sealed half.
- A collection element is read through `NugetMarshal.FromHandle<T>`
  (`forward/ForwardCirCollectionComponents.kt:132`), which looks `typeof(T)` up in
  `Factories` (`cir/CirMarshalRenderer.kt:141-216`). `factoryEntries` registers no generic type
  (`cir/CirTranslator.kt:1219-1310`), so `List<Outcome<Int>>` has no key today.
- The discriminator is `asStableRef<Outcome>()` with no argument (`exports/SealedClassExports.kt:52`).
- `csTypeArgument` refuses any type argument that itself has arguments (`cir/CirTypeMapping.kt:634`).

Constraints, each **verified by spike** (.NET SDK 10.0.300, kotlinc-native 2.4.10, macOS arm64,
scratch projects outside the repo; commands and output under Evidence):

- C1. `public static class Outcome` beside `public abstract class Outcome<T>` holds generic arms
  `Outcome.Ok<T> : Outcome<T>`; `o is Outcome.Ok<int> ok` and a `switch` over `Outcome<T>` inside a
  generic method both compile and match.
- C2. A `[DllImport]` in a generic arm nested in the non-generic holder is CS7042. So is one in an
  arm nested in `Outcome<T>`. A sibling `Outcome.OkNative` static class on the holder is legal.
- C3. C# has no shape that makes one non-generic `Err` an `Outcome<int>`: a class deriving
  `D<Nothing>` is CS0029 to `D<int>`; a covariant interface `IC<out T>` converts `IC<Nothing>` to
  `IC<object>` only, and is CS0029 to `IC<int>` and to `IC<string>`.
- C4. A non-generic arm `IntOk : Outcome<int>` constructed inside generic code needs
  `(Outcome<T>)(object)new Outcome.IntOk(h)`. It succeeds at `T = int` and throws
  `InvalidCastException` at `T = object` and at `T = int?`. Kotlin allows exactly those two under
  `out T` (`IntOk` is an `Outcome<Any>` and an `Outcome<Int?>`).
- C5. An arm must restate the base's constraint (CS0314 otherwise).
- C6. A marker class used as a type argument fails a bound: `Pen.Empty<KotlinNothing>` under
  `where T : IPet` is CS0311.
- C7. `static class Pending` beside `abstract class Pending<T>` inside the holder is legal, and an
  `internal sealed class Backing<T> : F.Pending<T>` inside that static class compiles.
- K1. Kotlin accepts every arm shape below, including an arm with an extra parameter
  (`Both<T, U> : Outcome<T>`) and one passing a compound (`Many<T> : Outcome<List<T>>`).
- K2. `when (handle.asStableRef<Outcome<*>>().get()) { is Outcome.Ok<*> -> 0 ... }` is exhaustive
  with no `else`, for a class, a `T : Enum<T>` base and a sealed interface, and returns the right
  ordinal at run time, including for a non-arm subclass of an abstract arm.
- K3. An arm built erased (`Outcome.Ok<Any>(box)`; the build uses `Ok<Any?>`, see Kotlin half) and read back as
  `asStableRef<Outcome<Int>>().get()` behaves as an `Outcome<Int>` (`"ok 8"`). A wrongly typed
  payload fails at first use with a catchable `ClassCastException`.
- K4. Under an invariant parameter a `Nothing` arm is not an `Outcome<Int>` (compile error).

## Alternatives Considered

### 1. Arms on the holder, generic over what they forward plus a phantom per fixed variant argument (chosen)
`Outcome.Ok<T>`, `Outcome.Err<T>`, `Outcome<T>.FromHandle`. Pros: spells Kotlin's `Outcome.Ok<Int>`
letter for letter, reuses ADR-196's holder, total under variance (no cast can fail), the phantom
arm is the idiom C# result types already use. Cons: `Err<int>` and `Err<string>` are two C# types
over one Kotlin class; an arm-typed `Nothing` position needs a marker type.

### 2. Arms nested in the generic base (`Outcome<int>.Ok`)
Rejected. Every arm captures `T` for free, but an arm's own parameter list is lost (`Flip<X, Y> :
Either<Y, X>` has no spelling), every extern needs hoisting out of two generic levels (C2), and it
contradicts ADR-196 Alternative 2, which rejected nesting under `Box<T>`.

### 3. Non-generic `Err : Outcome<KotlinNothing>` for every fixed arm
Rejected: C3. A value typed `Outcome<int>` could never be the `Err` arm, which is the whole point
of the hierarchy.

### 4. A covariant interface `IOutcome<out T>` as the public type
Rejected: C3 (no bottom type, and C# variance does not apply to value-type arguments, so
`IOutcome<int>` never receives it). It would also change the sealed route's shape from ADR-009's
abstract class.

### 5. Every arm generic over the base's whole parameter list, always
Rejected as the default: under an invariant parameter it invents `Cell.IntCell<string>`, a type
Kotlin forbids (K4), and a consumer could construct one and pass it to a `Cell<String>` parameter.
It is the fallback rule inside Alternative 1 for variant parameters only, where Kotlin allows it.

### 6. Keep the named skip
Rejected by the owner: this item closes Phase 4.

## Decision

Alternative 1.

### Consumer API

```kotlin
package io.github.xxfast.kotlin.native.nuget.test.outcome

sealed class Outcome<out T> {
  data class Ok<T>(val value: T) : Outcome<T>()
  data class Err(val message: String) : Outcome<Nothing>()
  data object Loading : Outcome<Nothing>()
  abstract class Pending<T> : Outcome<T>() { abstract fun eta(): Int }
  class Detail(val note: String)
  open fun label(): String = "outcome"
}
class Later<T>(val item: T) : Outcome.Pending<T>() { override fun eta(): Int = 3 }

sealed class Cell<T> {                       // invariant
  class Full<T>(val item: T) : Cell<T>()
  class IntCell(val number: Int) : Cell<Int>()
}

object OutcomeDesk {
  fun fetch(id: Int): Outcome<Int> = if (id > 0) Outcome.Ok(id) else Outcome.Err("no $id")
  fun name(id: Int): Outcome<String>? = if (id > 0) Outcome.Ok("Oreo") else null
  fun all(): List<Outcome<Int>> = listOf(Outcome.Ok(1), Outcome.Err("two"), Outcome.Loading)
  fun describe(outcome: Outcome<Int>): String = when (outcome) {
    is Outcome.Ok -> "ok ${outcome.value}"
    is Outcome.Err -> "err ${outcome.message}"
    else -> outcome.label()
  }
  fun later(): Outcome<Int> = Later(7)
  fun fail(): Outcome.Err = Outcome.Err("boom")
  fun cell(): Cell<Int> = Cell.IntCell(4)
  var last: Outcome<String> = Outcome.Loading
}
```

```csharp
public static class Outcome                       // ADR-196 holder
{
    public sealed class Ok<T> : Outcome<T> { public Ok(T value); public T Value { get; } }
    public sealed class Err<T> : Outcome<T> { public Err(string message); public string Message { get; } }
    public sealed class Loading<T> : Outcome<T> { }      // object arm: received, never constructed
    public abstract class Pending<T> : Outcome<T> { public abstract int Eta(); }
    public static class Pending { internal sealed class Backing<T> : Outcome.Pending<T> { } }
    public class Detail : IDisposable, INugetHandle { }
    internal static class OutcomeNative { /* get_type and the base's externs */ }
    internal static class OkNative { /* the arm's externs */ }
}
public abstract class Outcome<T> : IDisposable, INugetHandle
{
    public virtual string Label();
    internal static Outcome<T> FromHandle(IntPtr handle);   // not generic: T is the class's
}

public static class Cell
{
    public sealed class Full<T> : Cell<T> { }
    public sealed class IntCell : Cell<int> { }   // invariant: exactly Kotlin's shape
}
public abstract class Cell<T> : IDisposable, INugetHandle { }
```

```csharp
using Outcome<int> outcome = OutcomeDesk.Fetch(3);
string text = outcome switch
{
    Outcome.Ok<int> ok => $"ok {ok.Value}",
    Outcome.Err<int> err => err.Message,
    Outcome.Loading<int> => "loading",
    _ => outcome.Label(),
};
using var mine = new Outcome.Err<int>("mine");
Assert.Equal("err mine", OutcomeDesk.Describe(mine));
```

### The arm rule

Read the arm's supertype reference to the sealed base, `Base<a1..an>`, against the base's
parameters `P1..Pn`, left to right. The C# arm's type parameter list starts empty.

1. `ai` is one of the arm's own type parameters, bare, not yet listed: list it under its own name.
   The base argument is that parameter (`Ok<T> : Outcome<T>`, `Flip<X, Y> : Either<Y, X>`).
2. Otherwise `ai` is fixed (a closed type, `Nothing`, a compound, or a repeat). If `Pi` is invariant
   and `ai` has a closed C# spelling, the base argument is that spelling and nothing is listed
   (`IntCell : Cell<int>`). Otherwise list a **phantom** parameter named `Pi` (renamed as ADR-198
   renames a clashing parameter) and pass it (`Err<T> : Outcome<T>`).
3. An own parameter never listed is **unrecoverable** (`U` in `Both<T, U> : Outcome<T>`): the arm
   is declared without it (`Outcome.Both<T>`), and every constructor and member of the arm that
   names it is a named skip on the existing per-member route. C# cannot recover `U` from an
   `Outcome<T>`, and guessing `object` would make `is Outcome.Both<int, string>` silently false.

Each arm restates the `where` clauses of the base parameters it lists (C5). `FromHandle` is a
static, non-generic member of `Outcome<T>`, so every existing reconstruction site keeps its text:
`global::Ns.Outcome<int>.FromHandle(nativeResult)`. It constructs `new Outcome.Arm<...>(handle)`
with each listed parameter mapped back to the `Pi` it came from, and casts through `object` only
for a rule-2 closed arm. That cast cannot fail on a value Kotlin typed honestly (C4, K4).

Arm kinds: an `object` or `data object` arm follows the rule like a class and, as today, has no public
constructor or instance accessor (**verified by reading** `SealedSubclassObjectTests.cs`: an object
arm is only ever received); a `data` arm keeps `Equals`, `GetHashCode`, `ToString` over a
star-projected receiver; an `open` arm is `public class`; an `abstract` arm is `public abstract
class` and reconstructs through a wrapper on the arm's own holder when the C# arm is generic
(`Outcome.Pending.Backing<T>`, C7), or nested in the arm when it is not (ADR-009, unchanged); a
sibling (top-level) arm is declared at namespace level with the same parameter list.

An **intermediate sealed arm** (`sealed class Fault<out T> : Outcome<T>()`) follows the rule like any
arm and also owns a discriminator of its own: `Outcome<T>.FromHandle` delegates to
`Outcome.Fault<T>.FromHandle`, which switches over the intermediate's own arms. It is abstract and
has no public constructor, because Kotlin cannot construct a sealed arm either. A phantom carries
down: `sealed class Lapse : Outcome<Nothing>()` is `Outcome.Lapse<T>`, and its arms are
`Outcome.Lapse.Stall<T>` and `Outcome.Lapse.Gone<T>`. An intermediate that closes an invariant
argument stays non-generic, as rule 2 says: `sealed class Spare : Cell<Int>()` is `Cell.Spare : Cell<int>`
with `Cell.Spare.Last`. **Verified** by `IntermediateArmFixingTheArgument_CarriesThePhantomDown`,
`IntermediateArmClosingAnInvariantArgument_StaysNonGeneric` and the generated output.

An ADR-157 enum arm's box follows the rule too, once the box takes a generic `Equals` and a
star-projected base. An enum bound forces an invariant base (`out T : Enum<T>` does not compile in
Kotlin), so the fixed arm closes over the enum: `Showcase.Scratched : Showcase<Ribbon>`, and the
forwarding arm is `Showcase.Placed<T> where T : struct, System.Enum`. **Verified.** With two
variant parameters, a permuting arm lists its parameters in base order: `Duel.Flip<Y, X> : Duel<Y, X>`
for `Flip<X, Y> : Duel<Y, X>`.

### Positions

A reference to a generic sealed base or arm binds at every position a non-generic one does:
parameter, return, property, constructor parameter, nullable, and `List`/`Set`/`Map` component.
The classifier yields the same `ObjectHandle` as today with `csharpType` carrying the arguments
(`global::Ns.Outcome<int>`), `viaDiscriminator` for the base, and `kotlinReadType` (the ADR-196
field) carrying the applied Kotlin type the parameter is read as (`pkg.Outcome<kotlin.Int>`, K3).

An argument is admissible when `NugetMarshal.FromHandle<T>` can read it: a primitive, `Char`,
`String`, an exported class, object, interface or enum, a boxed value class, any of those nullable,
a type parameter in scope (`Outcome<T>` inside the hierarchy or a generic owner, erased to its bound
on the Kotlin side exactly as ADR-147 erases the owner), and another closed generic sealed
instantiation. `Nothing` is spelled with a marker class, `KotlinNothing` (uninstantiable). At an
arm-typed position a phantom takes the arm's own fixed argument: `fun fail(): Outcome.Err` is
`Outcome.Err<KotlinNothing> Fail()`.

Named skips, each a language gap rather than a deferral:

- A use-site projection (`Outcome<*>`, `Outcome<out Number>`): `SKIPPED_SEALED_POSITION`, "C# has
  no projection of a generic class".
- An argument the erased wire cannot read (`Outcome<List<Int>>`, a lambda, a `Flow`):
  `SKIPPED_SEALED_POSITION` naming the argument. This is ADR-147's existing rule for `T`.
- `Nothing` where the base's C# constraint rejects the marker (C6): `SKIPPED_SEALED_POSITION`,
  "`KotlinNothing` does not satisfy `where T : IPet`".

`suspend`, `Flow` and stored-callback members declared on a generic base or arm keep ADR-147's
`GENERIC_OWNER_LEGACY_ROUTE` skip, and a `T` nested in another type keeps ADR-147's skip: both are
rules for every generic owner, unchanged here.

A consumer cannot convert between instantiations (`Outcome.Err<KotlinNothing>` to `Outcome<int>`):
that is C# invariance, the rule `List<string>` to `List<object>` already teaches.

### Erased positions and `Factories`

A closed instantiation read through an erased slot (`List<Outcome<Int>>`, `Flow<Outcome<Int>>`,
`Hamper<Outcome<Int>>`) needs a `Factories` key. Two parts:

- One static entry per closed instantiation the processor saw at a position:
  `[typeof(global::Ns.Outcome<int>)] = static h => global::Ns.Outcome<int>.FromHandle(h)`.
- A per-type slot for instantiations only the consumer chose (`new Hamper<Outcome<long>>(x)`; no Kotlin position names `Outcome<Long>`. An instantiation Kotlin does name, such as `Outcome<string>`, gets a static entry and never reaches the slot):
  `internal static class NugetFactory<T> { internal static Func<NugetKotlinHandle, object>? Create; }`,
  set by the static constructor of `Outcome<T>` and of each generic arm, and read by
  `Materialize<T>` after a `Factories` miss. **Verified by spike**: a read before any instance
  exists misses, and a read after `new Outcome.Ok<string>("a")` or after a `FromHandle` hits. A
  consumer cannot put an `Outcome<string>` into a Kotlin slot without first holding one.
  **Verified under NativeAOT** (AotSmokeTest shape 8, `GenericSealedSlotStep`), including an arm-typed
  instantiation, `Hamper<Outcome.Ok<short>>`, which fills the arm's own slot. A static constructor on a
  generic instantiation and a static field on a generic class are ordinary code with no reflection,
  as the ADR-094 argument expected.

`csTypeArgument` (`cir/CirTypeMapping.kt:634`) and its plan twin (`forward/ForwardCsharpTypes.kt:191`)
admit a closed generic sealed instantiation as a type argument. That sufficed only for a lambda *property*. `Flow<Outcome<Int>>` needed a new element speller, and a per-call lambda (`fun retry(): (Outcome<Int>) -> Outcome<Int>`) goes through the ADR-160 plan callback. **Verified**: `FlowElement_DiscriminatesEachArm` and `ReturnedLambda_TakesAndReturnsAnOutcome` bind and pass.

### Kotlin half

- Discriminator: `handle.asStableRef<pkg.Outcome<*>>().get()` and `is pkg.Outcome.Ok<*>`, one star
  per arm parameter (K2). No trampoline: a read needs none (ADR-198).
- Base and arm members: the ADR-147 class route's receiver spelling (`forwardOwnerTypeName()`,
  `Outcome.Ok<Any?>`), the ADR-198 trampoline when a bound is unspellable, and
  [ADR-094](094-reflection-free-generic-dispatch.md)'s enum write for an enum-bound slot. An arm
  constructor is `pkg.Outcome.Ok<Any?>(value_?.asStableRef<Any>()?.get())` and a member reads the arm as
  `asStableRef<pkg.Outcome.Ok<Any?>>().get().value`, a nullable read (K3). **Verified** in the generated Kotlin.
- A closed parameter is read at its written type: `o.asStableRef<pkg.Outcome<kotlin.Int>>().get()`.
  The cast is unchecked by nature; a payload of the wrong type surfaces as a catchable
  `ClassCastException` at first use (K3), which C# typing prevents.

### Nested declarations and sealed interfaces

A declaration beside the arms goes on the holder (`Outcome.Detail`), which ADR-196 already
decides. One inside a generic C# arm goes on that arm's holder (`Outcome.Ok.Trace`, C7); an
`inner class` of a generic base or arm is flattened as ADR-196 C/D. ADR-196's "generic sealed base
or arm owner" skip is deleted. An eligible generic `sealed interface` (ADR-112) takes this route
unchanged: `public abstract class Res<T>` plus holder `Res` (K2).

### Diagnostics

`SKIPPED_UNSUPPORTED_TYPE` on the base, the `genericSealedHint` and `ForwardGenericSealedHierarchies`
are deleted. An arm named like its base is a **new** `ERROR_CSHARP_NAME_COLLISION` raised in the
translator (CS0542 on the holder, verified by spike). ADR-196's nested-type check never sees arms, so
it does not cover this case; `Tier1GenericSealedShapesTest` pins it.

## Consequences

- ROADMAP Phase 4's generic sealed line closes with no new line.
- Additive: no shipped C# name or export symbol changes. `Tier1GenericSealedSkipTest` became `Tier1GenericSealedTest`.
- A consumer sees `Outcome` and `Outcome<T>`, as with every ADR-196 holder.
- `Outcome.Err<int>` and `Outcome.Err<string>` are distinct C# types over one Kotlin class.
- Exhaustiveness is still CS8509, a warning (ADR-009).
- `NugetMarshal.Materialize<T>` gains one static-field read on a miss.

## Claims the build settled

The design carried five inferred claims. The run settled four; one remains inferred.

1. **True.** NativeAOT keeps the `NugetFactory<T>` slot working: AotSmokeTest shape 8 passes, including
   the arm-typed instantiation `Hamper<Outcome.Ok<short>>` filling the arm's own slot.
2. **True.** A non-arm generic subclass of a generic arm (`Later<T> : Outcome.Pending<T>`) renders its
   base as `Outcome.Pending<T>` on the ordinary class route and reconstructs through
   `Outcome.Pending.Backing<T>`.
3. **False for an intermediate arm as written, true for an enum arm.** An intermediate sealed arm needs
   its own discriminator, delegated from the parent, and a phantom carries down to its own arms (see
   the arm kinds above). An ADR-157 enum arm follows the rule once the box has a generic `Equals` and
   a star-projected base.
4. **False as written.** `csTypeArgument` admitting a closed instantiation sufficed only for a lambda
   property. `Flow<Outcome<Int>>` needed a new element speller, and a per-call lambda goes through the
   ADR-160 plan callback. Both bind now.
5. **Still inferred.** The ObjC export names its `Nothing` stand-in `KotlinNothing` (recalled, not
   looked up). Only the name's precedent rests on it.

The test author also settled these readings, now fact: `Duel.Flip<Y, X>` lists the permuted
parameters in base order; `out T : Enum<T>` does not compile in Kotlin, so an enum-bounded base is
invariant with a closed arm; and the fixture's erased-slot class is `Hamper`, whose slot proof uses
`Hamper<Outcome<long>>` because an instantiation Kotlin names gets a static entry and never reaches
the slot.

## Evidence

Scratch projects under `$TMPDIR`, never in the repo. `dotnet build` then `dotnet run`:

```
C1/C4/C5  holder shape, FromHandle, patterns     -> 0 Warning(s); "ok 0", "err m", True,
          "OkErrIntOkErrLoadingBacking`1",
          "ICE: Unable to cast object of type 'IntOk' to type 'Spike.Outcome`1[System.Object]'",
          "ICE: ... 'Spike.Outcome`1[System.Nullable`1[System.Int32]]'"
C2        extern in a generic arm on the holder  -> CS7042 (twice: holder arm, arm nested in B<T>)
C3        CErr : IC<Nothing> to IC<int>/IC<string> -> CS0029, CS0029; to IC<object> compiles
          D.Err : D<Nothing> to D<int>           -> CS0029; `D<string> is D.IntOk` -> CS0184
C5        arm without the base's constraint      -> CS0314
C6        Pen.Empty<KotlinNothing>, T : IPet     -> CS0311
C7        static Pending beside Pending<T>, Backing<T> inside -> compiles
          arm named like the holder              -> CS0542
slot      Materialize before any instance        -> "1 miss before any instance"
          after construct / after FromHandle     -> hits; static entry for Outcome<int> hits
```

`kotlinc-native 2.4.10 probe.kt -e probe.main`, then `./probe.kexe`:

```
K1/K2/K3  0 / "ok 8" / "ok 8" / 1 / "boom" / 6 / 0 / 1 / true / "caught ClassCastException"
K4        neg.kt:3:20: error: initializer type mismatch: expected 'Cell<Int>', actual 'Cell.Empty'
```

The spikes above ran before implementation. The built output was then checked end to end: `:nuget-processor:test` (1774 passed) and `scripts/verify.sh` (IntegrationTests 3183, LeakTests 189, 8 AOT shapes, ContractTests 4), with the generated C# and Kotlin read from the verified run. Generated-output claims in the sections above marked **Verified** rest on that run.
