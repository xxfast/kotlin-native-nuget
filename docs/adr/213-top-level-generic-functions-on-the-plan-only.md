# ADR-213: Forward, every top-level `fun <T>` binds on the callable plan and the width-variant route is deleted

## Status
Accepted

Supersedes the fallback routing of [ADR-211](211-top-level-generic-functions-on-the-plan.md)
(its Alternative 2 is this ADR). Lifts two refusals of
[ADR-197](197-method-type-parameters-on-the-callable-plan.md) ("Declined": `reified` at top
level, and, through ADR-216, `Box<T>` nesting). Retires the legacy width-variant route of
[ADR-064](064-forward-diagnostics.md)'s 2026-09-13 amendment. Keeps
[ADR-198](198-unspellable-bound-trampoline.md)'s reified-unspellable refusal. Mirrors
[ADR-208](208-generic-instantiation-member-positions.md), which deleted the other legacy generic
top-level route the same way.

## Context

After ADR-211 a top-level `fun <T>` has two wires, chosen by shape. The legacy one
(`exports/GenericFunctionExports.kt` `addGenericFunctionExports`, `cir/CirFunctionTranslator.kt`
`translateGenericFunction`, both deleted by this ADR) takes exactly one parameter typed `T` and a `T` or generated
generic-class return, and emits 12 typed Kotlin exports plus `_object` (C# imports 6 widths plus
`_object` and dispatches on `typeof(T)`). Every other shape is on the ADR-062 plan as one boxed
export. **Verified by reading** (`GenericFunctionExports.kt:41-63`, `NugetProcessor.kt:1880-1886`,
`:3950-3955`).

Two wires cost: a hand-built C# body with its own local-name collision rules
(`Tier1GenericFunctionLocalNamesTest`), its own ABI-slot spelling, its own nullability arm, its own
bound gate (`legacyGenericHasNonTrivialBound`), and a planner predicate that asks the legacy gate
before it plans (`ForwardMemberTypeParameters.kt:23`). The user has decided the breaking change
lands before 1.0.0.

Two shipped fixtures are on the legacy route only because the plan refuses their shape
(`forwardMemberGenericRefusal`, `ForwardMemberTypeParameters.kt:116-200`):

- `inline fun <reified T : Pet> groomPet(pet: T): T` (`cat/Helpers.kt:9`): "its type parameter `T`
  is reified" (`:133`). The legacy `_object` export calls `groomPet((pet ... as Pet))`, so `T`
  reifies at its bound `Pet` today. **Verified by reading** (`GenericFunctionExports.kt:221-232`).
- `fun <T> wrapInBox(value: T): Box<T>` (`cat/Helpers.kt:5`): "its type parameter is nested in
  `Box<T>`" (`:151-158`, `:183-188`). The classifier already spells an open `Box<T>` as an
  ADR-208 applied-spelling `ObjectHandle` (`ForwardBridgeTypeClassifier.kt:698-800`, `closed =
  false` for an argument that mentions a type parameter). **Verified by reading.**

Every other legacy fixture (`identity`, `adoptPet`, `relayPet`, `lead`, `rehearse`, `headline`,
`handBack`, `requirePrize`, `favourite`, `weigh`, `portion`, `nickname`, the keyword-named `put`,
`keep`, `hold`, `pick`, `span`, `seen`, `mark`, and `structuralOnTopLevel`) is a shape the plan
already accepts. **Verified by spike** (below).

## Alternatives Considered

### 1. Full migration, with `reified` admitted at top level and `Box<T>` admitted at every owner (chosen)

`hasLegacyGenericFunctionRoute` is gone; every non-suspend top-level `fun <T>` except ADR-198's
reified-unspellable case enters the catalog. The plan's refusal admits a reified `T` on a top-level
function (reified at its erased bound, which is what the shipped `_object` variant does) and a `T`
carried by an exported generic class (`Box<T>`) at every owner, the latter being ADR-216's
widening of the same gate, which this ADR depends on. One wire, one owner
predicate, every public C# signature unchanged. Cost: 13 entry points per function become one, and
a primitive `T` boxes.

### 2. Full migration, refusing `groomPet` and `wrapInBox` with a named skip

Smaller refusal code, but two shipped bindings vanish and `docs/topics/generics.md:470-486` loses
its headline example. A source break for no mechanism reason: both shapes compile on the plan.

### 3. Keep ADR-211's fallback routing

Zero change. Leaves two wires, and the ROADMAP line open.

### 4. Admit `reified` at every owner, not only top level

One rule for all owners. Rejected for this ADR: a member `reified` has never bound, so admitting it
there is new scope with the silent-erasure risk ADR-197 named, and nothing shipped needs it.
Recorded as an open question, not a deferral of shipped behaviour.

## Decision

Alternative 1.

```C#
// unchanged public signatures, one export each (was 13 / 7)
public static T Identity<T>(T value)
public static global::TestLibrary.Cat.Box<T> WrapInBox<T>(T value)
public static T GroomPet<T>(T pet) where T : global::TestLibrary.Cat.IPet
public static T RequirePrize<T>(T medal) where T : struct, global::System.Enum
// newly bound (a Box<T> parameter was refused before)
public static T UnwrapBox<T>(global::TestLibrary.Cat.Box<T> box)
```

### Routing

1. Delete `addGenericFunctionExports`, `legacyGenericRouteParameterIndex`,
   `legacyGenericHasNonTrivialBound`, `hasLegacyGenericFunctionRoute` and `kotlinTypeClass`
   (`exports/GenericFunctionExports.kt`); move `isReifiedWithUnspellableBound` beside
   `hasUnspellableBound` in `forward/`, then delete the file. Delete `translateGenericFunction`
   (`cir/CirFunctionTranslator.kt:239-461`) and its imports.
2. `NugetProcessor.isPlannedTopLevelFunction` is `typeParameters.isEmpty() ||
   !isReifiedWithUnspellableBound()`, applied after the suspend partition: a `suspend fun <T>` is
   split off first and stays unplanned until ADR-214. `isReifiedWithUnspellableBound` asks every
   type parameter, not only the first one the width route read. `genericFunctions` is then only a
   `suspend fun <T>` and the reified-unspellable case; its emitter loop (`:3344-3346`)
   and `CirTranslator.kt:359-370` go, and it survives only as the input to
   `warnUnroutedGenericFunctions` (`:538`), whose `filterNot` (`:543`) is dropped.
3. `isForwardGenericMemberOwner` (`ForwardMemberTypeParameters.kt:23`): `parentDeclaration == null`
   answers `true`.
4. `forwardMemberGenericRefusal`: the reified refusal (`:133`) applies only when `parentDeclaration
   != null`. The `Box<T>` admission in `carriesOwn` is [ADR-216](216-own-type-parameter-in-result-and-generic-class.md)'s
   decision, not this ADR's: this ADR depends on it and lands after it. Without ADR-216, `wrapInBox` has no route
   once the legacy one is deleted. ADR-216 also found that a multi-bound `T` witnessed only inside
   `Box<T>` breaks the Kotlin compile under the naive widening; that refusal is ADR-216's too.

**Verified by spike** (scratch worktree at `main` 5e001177, three edits only:
`hasLegacyGenericFunctionRoute() = false`, the reified refusal gated on `parentDeclaration !=
null`, and the `carriesOwn` widening, so steps 1 to 3 were simulated, not performed; a Tier 1 file
declaring every legacy shape plus `reifiedName` and `unbox`; `:nuget-processor:test --tests
'*Tier1Spike213Test*'`, 2026-10-22):

```
kspErrors=[]  compileErrors=[]  warnings=[]
C# consumer (TreatWarningsAsErrors, net10.0): succeeded=true
EntryPoint count for library_spike__*: before 64, after 30
@CName("library_spike__identity")      ... tier1.spike.identity<Any?>(value_?.asStableRef<Any>()?.get())
@CName("library_spike__wrapInBox")     ... NugetHandles.retain(tier1.spike.wrapInBox<Any?>(...))
@CName("library_spike__groomPet")      ... tier1.spike.groomPet<tier1.spike.Pet>((pet.asStableRef<Any>().get() as tier1.spike.Pet))
@CName("library_spike__requirePrize")  ... fun <T> nugetTrampoline() ... requirePrize<T>(...)
@CName("library_spike__unbox")         ... tier1.spike.unbox<Any?>(box.asStableRef<tier1.spike.Box<Any?>>().get())
```

The consumer called `Identity(4)`, `Identity("x")`, `Identity<int?>(null)`, `WrapInBox(5)`,
`WrapInBox("s")`, `GroomPet(cat)`, `Lead(chain)`, `Headline(cat)`, `RequirePrize(Medal.Gold)`,
`Weigh(3u)`, `Mark(1)`, `Unbox(b)`. The public C# signatures of the 15 legacy-shaped functions are
identical before and after, text for text, except `WrapInBox`'s return, which is spelled
`global::...Box<T>` instead of `Box<T>` (the same type). **Verified by spike** (baseline run of the
same file with the edits reverted). That comparison did not catch a `handle` parameter or a
`Crate<T?>` return; the implementation found both move (see "C# spellings that move").

### Semantics that move

- **Primitive `T` boxes.** `Identity<int>(4)` was one P/Invoke on `..._identity_int`; it is now
  `Wrap<int>` (a `nuget_wrap_int` call minting a `StableRef`), the call, and `FromHandle<int>`
  (`nuget_unwrap_int` plus `nuget_dispose`). **Inferred** from the generated `FromHandle` text in
  the spike output; not measured. No benchmark covers a top-level generic (verified by grep).
  Member generics already pay this (ADR-197).
- **`reified` reifies at the declared bound, for every argument.** Unchanged for `groomPet` (the
  shipped `_object` variant already did this). Changed for an unbounded `inline fun <reified T>`
  called with one of the six C# widths: it reified at `Int`/`String`/... on the width export and
  reifies at `Any?` now (`T::class` reads `Any`). **Verified by reading** the two generated calls;
  no fixture has that shape.
- **ABI.** Every `..._{string,byte,...,bool,object}` export is replaced by one unsuffixed
  `library_<pkg>__<fun>` export (ADR-163 stem). The bindings and the native library ship in one
  package, so a consumer rebuilds; only hand-written P/Invoke against the old names breaks.

### C# spellings that move (found in implementation)

The spike compared 15 public signatures; the implementation found two more that the plan spells
differently from the width route. **Verified** by the Tier 1 cells and `scripts/verify.sh`.

- **A parameter named `handle` would have become `handle_`.** `handle` is a plan-owned name
  (`PLAN_OWNED_NAMES`), shifted on every plan-routed callable's public signature; ADR-211 took
  back only `value`. The first `verify.sh` run failed to compile
  `KeywordRoutesTests.Hold_GenericTopLevelRoute_BindsAParameterNamedHandle`
  (`Hold<int>(handle: 4)`, CS1739). So the plan takes `handle` back on a top-level `fun <T>`
  (`ForwardPublicParameter.csharpLabel`): the Kotlin export and the extern keep `handle_`, the
  public label is `handle`, and a static wrapper body declares no `handle` local. Scoped to a
  top-level `fun <T>`, the shape the width route shipped; a non-generic top-level function, a
  member and a constructor keep `handle_` as before (`Tier1ReservedParameterNamesTest`).
  `receiver` and `outer` still shift; no fixture has them at top level.
- **`T?` inside a generic class keeps its `?`.** `fun <T> crateOfMaybe(item: T): Crate<T?>` is
  `Crate<T?> CrateOfMaybe<T>(T item)`, where the width route dropped the `?` (`Crate<T>`). For an
  unconstrained `T` that is the same C# type (`T?` on a value type is `T`), so only the nullable
  annotation moves (`Tier1GenericReturnTypeArgumentTest`).
- A generic-class return is spelled fully qualified (`global::...Crate<T>`), the same type.

### Diagnostics

`warnUnroutedGenericFunctions` keeps the ADR-198 reified-unspellable branch and the
`suspend fun <T>` branch (ROADMAP Phase 4, its own item, ADR-214). Every other refusal is the
plan's `GENERIC` skip with its `(here, ...)` clause. The `GENERIC` hint (`ForwardDiagnostic.kt`)
already names "a `reified` member `T`" since ADR-216, which is what this ADR leaves refused, and
no longer lists `Box<T>`; its text does not change here.

## Breaking changes

- **Export names (ABI, a rebuild).** Top-level generic exports renamed: `library_<pkg>__identity_int`
  and friends become `library_<pkg>__identity`. The bindings and the native library ship in one
  package, so a consumer rebuilds; C# source is unchanged. Only hand-written P/Invoke against the
  old names breaks.
- **A primitive `T` now boxes.** `Identity<int>(4)` crosses as a `Wrap<int>` box and comes back
  through `FromHandle<int>`, about four P/Invokes where there was one. **Inferred**, not measured.
- **An unbounded `reified T` reifies at `Any?`.** `inline fun <reified T> f(value: T)` called with
  one of the six C# widths reified at `Int`/`String`/... on its width export; it reifies at `Any?`
  now (`T::class` reads `Any`). A bounded one reifies at its bound, as the shipped `_object` export
  did. **Verified** (`.reifiedName<Any?>(` and `.groomPet<...Pet>(` in
  `Tier1TopLevelGenericMethodTest`).

## Consequences

- Breaking: see "Breaking changes"; `CHANGELOG`/release notes name them, with `Identity<T>` as the
  example.
- `exports/GenericFunctionExports.kt` and `translateGenericFunction` are deleted. Tier 1 tests that
  pin the width variants are rewritten to the plan's spelling or deleted (memo lists them).
- Depends on ADR-216 for `Box<T>` (`wrapInBox`), which landed first.
- Fixture `fun <T> unwrapBox(box: Box<T>): T` (`cat/Helpers.kt`; not `unbox`, which
  `boxshelf.unbox(Box<Int>)` already owns) and the xunit row `UnwrapBox_RoundTripsWrapInBox`.
- `Reserved.kt` `abiSlotParameterName`, which only the width route used, is deleted with it.
- `docs/topics/generics.md:470-530` and `docs/topics/supported-features.md:94,96` drop the typed
  variants and the `Box<T>` refusal.
- Leak row: `TopLevelGenericMethod_BoxedBox_ReturnsToBaseline` (a `WrapInBox<int>`, an
  `UnwrapBox` and an `Identity<string>`), at the end of `LiveHandleTests`.
