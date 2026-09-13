# ADR-075: Forward — plan collection property getters and setters independently

## Status
Accepted

**Shipped scope note.** The Decision section below covers the getter/setter independence split and the
setter eligibility predicate; both landed exactly as decided. Two more fixes landed in the same change
because the ADR's own recommended fixture (`Visit`, `Chart`, `ChartId.symptomTags`) needed them, and neither
is covered by the Decision section above:

- `ForwardPropertyPlanner`'s `supportedReceiver` check for an **extension property** admitted only
  `ObjectHandle`/`Primitive`/`String` receivers, so a collection-typed extension property on a value class
  (`ChartId.symptomTags`) would have vanished with no diagnostic, the same disappearance class as the
  mutability facet this ADR exists to fix. Widened to also admit a value class whose underlying type is
  `Primitive` or `String`, the predicate `Chart.tags` already uses for its own eligibility.
- `Nullable(Collection)` was unplannable as an ordinary callable **parameter** (constructor, method,
  generated `copy()`), not only as a property setter: `data class Visit(…, val notes: List<String>? = null)`
  generated with no public constructor at all, CS1729 in the consumer. Fixed with the same lowering this
  ADR already had to write for the property setter (Decision 3), reused at the callable-parameter site.

Both are implemented consistently with everything the Decision section does say: same `isWrappableComponent()`
predicate, same wire shapes (`COpaquePointer?`, `value != null ? CreateList(value) : IntPtr.Zero`), same
"`Direct` never `NullableDispatch`" rule for a nullable collection reference. They are omissions in this
ADR's stated coverage, not incorrect claims in it.

## Context

Two ROADMAP "Post-migration hardening" items are one change, because they are two facets of the same
function, `ForwardPropertyPlanner.propertyPlan` (`forward/ForwardPropertyPlanner.kt:139`):

```kotlin
if (!isPlannable(type) || (prop.isMutable && type.unwrapNullable() is BridgeType.Collection)) return null
```

- **Mutability facet (ROADMAP:282).** A `var` with a collection type loses its *whole* property, including
  the getter that used to work. `val nicknames: List<String>` binds; `var nicknames: List<String>` vanishes
  from the C# API with no diagnostic.
- **Nullability facet (ROADMAP:283).** `val notes: List<String>?` is admitted by `isPlannable` (`:222`
  recurses through `Nullable`) and by `ForwardPropertyPlan.validateType` (`:81-89`), then dies in
  `ForwardPropertyKotlinEmitter`'s nullable getter branch (`:72`, `error(...)`), killing the whole KSP run.
  The C# side has the twin hole: `checkedGetter`'s nullable branch (`forward/ForwardCirPropertyProjection.kt:190-199`)
  has no `Collection` case and falls through to `return nativeResult;`, handing back a raw `IntPtr` where
  the declared type is `IReadOnlyList<T>?`.

The getter half of both is a pure totality gap with no design content: route `Nullable(Collection)` through
the existing `nullableHandleBody` on the Kotlin side (`ForwardPropertyKotlinEmitter.kt:62-70`) and put a
null-handle guard in front of the existing `collectionMaterialize` on the C# side
(`ForwardCirPropertyProjection.kt:238`). Reads impose no element-type restrictions: `collectionMaterialize`
loops over the single runtime-generic `NugetMarshal.FromHandle<T>` (**verified**, `cir/CirMarshalRenderer.kt:104`),
so every element type that works in non-null `List<T>` works in `List<T>?`.

The setter half is not a totality gap. It is the `INTO_KOTLIN` direction, where real write-side restrictions
exist, and it needs three decisions: which collection types may be written, what happens to a `var` whose
getter is plannable but setter is not, and whether the skip is reported.

## Alternatives Considered

### Question A — the setter eligibility predicate

#### A1. Reuse ADR-073's `isWrappableComponent()` for every collection kind, including `List` (chosen)

Every component (list/set element, map key **and** value) must satisfy `isWrappableComponent()`:
`String`, `Int`, `Long`, `Float`, `Double`, `Boolean`, or an `ObjectHandle`. A `Nullable(Collection)`
is eligible exactly when its unwrapped `Collection` is: the collection reference's own nullability and its
components' marshallability are independent questions (see Question D).

This is exactly what the generated C# `NugetMarshal.Wrap<T>` can box (**verified**, `cir/CirMarshalRenderer.kt:296-308`:
six `typeof(T) ==` branches, then a reflective `_handle` field lookup, then
`throw new NotSupportedException($"Cannot pass {typeof(T).Name} to a Kotlin collection")`). An enum element
is a C# enum with no `_handle` field, so it reaches the throw; `Char` and the narrow primitives likewise.

- Pro: the property setter surface starts correct instead of inheriting the callable side's known-broken
  `List` shapes. On the callable side `List` parameters are deliberately left on the wider
  `isBridgeableComponent()` (ADR-073 Scope item 1) purely for backward compatibility, and ROADMAP already
  records the damage: `List<String?>`, `List<StoryCode>` and `List<List<String>>` parameters **crash
  `packNuget`** at `elementKotlinTypeName`'s `error(...)`, and `List<Mood>`/`List<Short>`/`List<Char>` bind
  and then throw at first call. **No collection property setter binds today**, so there is no compatibility
  debt to preserve, and nothing to regress by choosing the strict predicate.
- Pro: the Kotlin side agrees. `elementKotlinTypeName` (`forward/ForwardKotlinPlanEmitter.kt:404-410`,
  **verified**) accepts only `String`, `Char`, `Primitive`, `ObjectHandle`, `Enum` and `error(...)`s on
  anything else; `isWrappableComponent()` is a strict subset of that domain, so no admitted setter can
  reach the crash.
- Con: `List` property setters are narrower than `List` *parameters* for the same element type. A
  `fun setTags(tags: List<Mood>)` binds (and throws at runtime); `var tags: List<Mood>` gets a get-only
  property and a diagnostic. That inconsistency is deliberate and is an argument for narrowing the
  parameter side later (already a ROADMAP item), not for widening this one.

#### A2. Reuse `isBridgeableComponent()`, matching the callable `List` parameter branch exactly

- Pro: one rule for `List` at every input position.
- Con: knowingly imports a `packNuget` crash (`var notes: List<String?>`) and a runtime `NotSupportedException`
  (`var moods: List<Mood>`) into a surface that has neither today. Rejected.

#### A3. A property-specific predicate

- Con: nothing about a property setter differs from a callable parameter at the wire; the lowering is the
  same `CreateList`/`CreateMap`/`CreateSet` + `Wrap<T>`. A third predicate would drift. Rejected.

**Reuse is mechanically possible but needs a small extraction.** `isBridgeableComponent()` and
`isWrappableComponent()` are `private` members of `class ForwardCallablePlanner`
(`forward/ForwardCallablePlanner.kt:1211`, `:1250`, **verified**), while `ForwardPropertyPlanner` is a
separate class. Both live in package `...processor.forward`, so the fix is to lift them to file-level
`internal fun BridgeType.…` in that package (their bodies reference no planner state). Do not copy them.

### Question B — a `var` whose getter is plannable but setter is not

#### B1. Emit a get-only C# property (chosen)

- The projection already supports it with **no renderer change**: `ForwardCirPropertyProjection.property()`
  passes `setter = plan.setter?.let { setterBody(...) }` and `CirClassRenderer` has an explicit
  `prop.setter == null` branch that renders a get-only property (**verified**,
  `cir/CirClassRenderer.kt:363`). This is the shape every Kotlin `val` already ships as. Setting
  `plan.setter = null` for an ineligible setter is the entire C#-side change.
- Idiomatic C#: a get-only property is ordinary, reads as `{ get; }` in IntelliSense, and matches how the
  BCL exposes `IReadOnlyList<T>` members.
- Precedent inside the toolchain: Kotlin/Native's ObjC export projects a Kotlin `val` as
  `@property (readonly)` and a `var` as readwrite, and the readonly projection is the established way to
  express "no setter here" without dropping the member (**inferred** from the ObjC export docs
  https://kotlinlang.org/docs/native-objc-interop.html; not verified against a built framework this session).
  The repo's own `val` collection properties are the closer and stronger precedent, and that one is verified.
- Con: a C# consumer of `var nicknames: MutableList<Mood>` sees a property they cannot assign, with no
  in-IDE explanation. Mitigated by Question C.

#### B2. Emit a setter that throws `NotSupportedException`

- Pro: the declared C# shape matches the Kotlin declaration exactly.
- Con: converts a build-time-known fact into a runtime failure. The repo does have one throwing member
  (ADR-040 sub-decision B, `NugetMarshal.HandleOf` throws for a C#-implemented `IFoo`), but that condition
  is *only* detectable at runtime. Everything the planner knows statically is skipped with an ADR-064
  diagnostic instead. Rejected as inconsistent with ADR-064.

#### B3. Keep skipping the whole property (status quo)

- Con: this is the regression ROADMAP:282 exists to fix. Rejected.

**Known adjacent hazard, out of scope but named so it is not created by accident:** Kotlin lets a `var`
override a `val`. If an interface declares `val items: List<Int>` (get-only in C#) and an implementing class
declares `override var items: List<Int>`, the class projection would emit an `override` property that adds a
set accessor, which is **CS0546** in C#. Both projections run the same predicate on the same declared type,
so this can only arise from a `val`/`var` override mismatch, which is pre-existing and not collection-specific.
Do not put that shape in the fixture.

**2026-09-07 amendment: the hazard above misattributed the failing case.** An interface `val` widened by an
implementing class's `override var` never fails: the C# projection of a class member that only implements an
interface member renders `virtual`, not `override`, and C# lets a `virtual` declaration carry a setter the
interface never asked for. Pinned by `Clicker : Counter { override var count }`
(`test-library/src/nativeMain/kotlin/.../test/cat/Clicker.kt`): the generated `Clicker` gets
`public virtual int Count { get; set; }` against `ICounter`'s get-only `int Count { get; }`, and it compiles.

The real CS0546 needs an exported **base class** in the chain, not just an interface: `Pet.vibe` (interface
`val`) widened by `Animal.vibe` (`override val`, renders `public virtual string Vibe` get-only), then widened
again by `Cat.vibe` (`override var`). `Cat.Vibe` would render `public override string Vibe { get; set; }`,
which fails `CS0546` because `Animal.Vibe` has no overridable set accessor.

The shipped fix (`ForwardPropertyPlanner.kt`, not the renderer) refuses the setter: a property planned with
an exported base class and `override` inherits the base member's accessor set, and since Kotlin admits only
`val` → `var` across an override, the derived setter is dropped through `ForwardDroppedPropertySetter` and no
`_set_` export is minted for it. The overridee lookup uses KSP's `findOverridee()` first, **verified** by a
Tier 1 probe to return the class-chain member (`Animal.vibe` for `Cat.vibe`, not `Pet.vibe`), falling back to
a walk over the base's `getAllProperties()` for the shape where the base does not redeclare the property (an
abstract base inheriting the interface `val` directly); that fallback shape is Tier 1 covered only, not
fixture-covered end to end. The mirror direction, a class narrowing a base's `var` to `val`, is already a
Kotlin compile error and needs no handling here.

The base **`open val`** half of this paragraph was fixed on 2026-09-10 (`Modifier.OPEN` is now read, so an
`override` of it compiles); see ADR-101's amendment of that date.

**2026-09-10 amendment: an unimplemented base `abstract val` / `abstract var` renders as a C# abstract
property, and the symptom label above was wrong.** This paragraph used to say the shape fails `CS0115`
(inherited from the *method* route, where a class-declared `abstract fun` is dropped from C# entirely). It
fails **`CS0506`**. The property route had no abstract path, but it did have a plan: an abstract property was
planned, exported and rendered like any other, as a concrete non-virtual property with a live getter body, so
the subclass's `public override` had nothing overridable to bind to.

The rule now: a property the class declares without implementing renders `public abstract T Name { get; }`,
or `{ get; set; }` when the plan carries a setter. `CirProperty.isAbstract` (set from KSP's `isAbstract()` at
the `CirClassTranslator` property call site, threaded through `ForwardCirPropertyProjection.classProperty`)
takes an early branch in `renderProperty` ahead of every body-shaped arm. That branch ignores `isVirtual`
deliberately: `abstract virtual` is `CS0503`, while `abstract override` is legal C#.

The plan, the Kotlin `<prefix>_get_<name>` / `_set_<name>` exports and the matching `DllImport`s stay on the
abstract base. Nothing there calls them (a base-typed C# reference always dispatches to the subclass's
override), but they are valid and they keep `ForwardAbiContract` green: the contract takes the planned names
as the expected set, so suppressing the export while keeping the plan would report the Kotlin projection
missing one. Pinned by `Tier1AbstractPropertyTest`, `CirOrdinaryRendererTest`, the `orchestra` fixture
(`Instrument` / `Violin`) and `IntegrationTests/AbstractPropertyTests.cs`.

Separate, and now fixed by the second 2026-09-11 amendment below: the abstract **method** walk, which
dropped a class-declared `abstract fun` entirely and rendered an inherited concrete member abstract when
no plan covered it. So does an abstract class inheriting an interface
`val` it does not implement: that property is never planned, so there is no `CirProperty` to flag, and it
vanishes from C# while `: IPet` stays in the base list (`CS0535`). That last one closed on 2026-09-11, below.

**2026-09-11 amendment: an interface property an abstract class inherits without implementing now renders
abstract on that class, off the declaration walk and with no import.** The paragraph above closes: the shape
really did fail `CS0535` on the generated class itself (before any consumer subclass), plus `CS0115` on a
subclass `override` with no base member to bind to.

The inherited case takes the *translator walk*, not the plan, exactly as the abstract **method** mirror does.
`isForwardPlannableMemberOf` keeps an unimplemented inherited member out of the planner (a bridge getter would
have nothing to dispatch to), so `CirClassTranslator`'s property walk now has an arm beside the method walk's:
a property whose `parentDeclaration` is not this class and whose `isAbstract()` is true becomes
`CirProperty(isAbstract = true, hasNativeImport = false)`.

Two details are load-bearing:

- The C# type is read off the **ADR-113 interface declaration catalog** (`propertyFor("<iface>.<name>")`,
  spelled through `ForwardCirPropertyProjection.publicType`), the same plan `translateInterface` spells
  `IFoo`'s member from. Hand-spelling it here, as the method mirror still does for its return and parameter
  types, is the `CS0738` drift ADR-113 closed. No plan for the interface member means `IFoo` does not declare
  it either, so the class declares nothing and the arm answers null.
- The new `CirProperty.hasNativeImport` gates the getter/setter `DllImport` in both `CirClassRenderer` and
  `CirNativeImports`. There is no export behind a declaration-only property, and the ADR-055 contract check
  compares the C# import set against the planned exports: an ungated import is reported as unplanned. The
  gate is the fix there, never an allow-list entry.

This is the opposite trade from the class-own abstract case above, which keeps its plan, its exports and its
imports because it *has* a plan. Both render the same bodiless `public abstract T Name { get; }` /
`{ get; set; }`; only the import set differs. The setter follows the interface plan's setter, so an interface
`var` gives the base `{ get; set; }` and the implementing subclass's own setter is not `CS0546`.

Out of scope, unchanged: an **unexported** interface (ADR-101 drops `: IFoo` from the base list, and there is
no declaration plan to spell the member from, so a subclass `override` still hits `CS0115`; closed by the
2026-09-13 amendment below), and the same shape on an `abstract` sealed arm. Pinned by
`Tier1AbstractInterfacePropertyTest`, the `aviary` fixture (`Feathered` / `Bird` / `Finch`) and
`IntegrationTests/AbstractInterfacePropertyTests.cs`.

### Question D — is a nullable collection setter (`var notes: List<String>?`) in v1?

#### D1. Yes, as an ordinary `ForwardPropertySetter.Direct` with a nullable pointer (chosen)

An earlier draft of this ADR deferred it on the grounds that `nuget_list_add`/`nuget_map_put`/`nuget_set_add`
take a non-null `COpaquePointer`. **That reasoning was wrong and is retracted.** Those exports constrain
*elements*: they are reached once per element while filling the handle. When the collection reference itself
is null, no handle is created and none of them is ever called. The two constraints are unrelated.

Everything the nullable variant needs already exists, or is being written by this change anyway:

- **Wire type**: `kotlinInputType` already maps `Nullable(Collection)` to `COpaquePointer?`
  (**verified**, `forward/ForwardPropertyKotlinEmitter.kt:197-206`: the `is BridgeType.Nullable` arm recurses
  and `.copy(nullable = true)`, and `Collection` is already in the pointer branch alongside
  `ObjectHandle`/`Interface`).
- **Setter shape**: `ForwardPropertySetter.NullableDispatch` (two exports, `set_x` + `set_x_null`) is built
  **only** for `Nullable(Primitive)` (**verified**, `forward/ForwardPropertyPlanner.kt:149-151`), because a
  primitive has no null representation on the wire. A pointer does: `IntPtr.Zero`. Nullable `String`,
  `ObjectHandle` and `Interface` all already take the single `Direct` route with a nullable wire value, and
  `Nullable(Collection)` joins them. **No new export, no ABI shape change beyond the setter itself.**
  Consequently the C# emitter's `value.HasValue` in the `NullableDispatch` branch
  (`forward/ForwardCirPropertyProjection.kt:161`) needs **no** widening: that branch is unreachable for
  anything but `Nullable(Primitive)`, where `HasValue` is the correct C# for a `Nullable<T>` (**verified**
  by the planner's construction site above). Do not "fix" it as part of this change.
- **Kotlin lowering**: `valueExpression()` `error(...)`s on `Collection` in **both** its nullable and its
  non-nullable arm (**verified**, `:182-195`), so the collection setter lowering is being written from
  scratch here regardless. The nullable variant is the same expression with `?.`, adjacent to the null-safe
  idiom already shipping one line above it (`value?.asStableRef<…>()?.get()`, `:185-186`).
- **Null arrival**: a C# `IntPtr.Zero` arrives as Kotlin `null` in a `COpaquePointer?` parameter. This is not
  new machinery: it is exactly the shipped nullable-`ObjectHandle` property setter route, whose C# side emits
  `$name?._handle ?? IntPtr.Zero` (**verified**, `ForwardCirPropertyProjection.kt:313`) against that same
  `COpaquePointer?` parameter and that same `value?.` lowering.

So the Kotlin side needs **nothing beyond a null-guarded `valueExpression()` branch**. A null
`COpaquePointer` never reaches `asStableRef` because `?.` short-circuits, and the resulting expression's
static type is `List<String>?`, which is the property's own type.

- Pro: `List<T>?` is the shape the downstream report (NYTimes-KMP) actually hit, and a get-only projection
  for it would leave the reporter's workaround in place for no mechanism reason.
- Con: one more shape in a change that is already wide. Small, given every piece above already exists.

#### D2. No, defer nullable collection setters to a follow-up

- Con: the only justification was a constraint that does not apply. Rejected.

#### D3. Give it its own `NullableDispatch` pair (`set_notes` + `set_notes_null`)

- Con: two exports where one suffices, and it would diverge from every other nullable reference-typed setter
  in the file. `NullableDispatch` exists for primitives, which have no null wire value. Rejected.

### Question C — diagnostics

#### C1. Reuse `SKIPPED_UNSUPPORTED_INPUT`, with setter-specific wording, and wire the property planner to the existing sink (chosen)

The kind fits: a setter value is an input position, and `ForwardPlanSkipReason.COLLECTION` already maps to
`SKIPPED_UNSUPPORTED_INPUT` (**verified**, `forward/ForwardDiagnostic.kt:136`). No new kind is needed. What
*is* new is that the skip is partial: the message must say the property still binds read-only, not that it
was dropped. `ForwardDiagnostic` carries free-form `reason`/`hint`, so this is wording, not structure.

**The property planner has no diagnostic channel today.** `ForwardPropertyPlanner.catalog()` collects with
`mapNotNull` and has no `Skipped` variant, and `ForwardCallablePlanCatalog.droppedCallables` filters
`entries`, which holds callables only (**verified**, `forward/ForwardCallablePlanner.kt:125-128`). So today a
property skipped at `:139` is silent to the user, and the "either plan the setter or emit a
setter-specific diagnostic" half of ROADMAP:282 cannot be satisfied without new plumbing.

The plumbing is small and does not depend on ROADMAP:281. The *sink* already reaches the user:
`warnDroppedForwardCallables` builds `ForwardDiagnostic`s and calls `ForwardDiagnosticSink.emit(diagnostics, logger)`
against the real `KSPLogger` (**verified**, `NugetProcessor.kt:104-118`). Add a parallel
`droppedPropertySetters` list to the catalog (or a second emit) and feed the same sink. ROADMAP:281 is about
*callable* skips whose `droppedFromCSharp` is false; it is not a prerequisite.

#### C2. A new `SKIPPED_UNSUPPORTED_SETTER` kind

- Pro: greppable, and unambiguous that the member partially survived.
- Con: ADR-064's kinds are already positional (`_INPUT`/`_RETURN`/`_COMBINATION`), and a setter is an input.
  A new kind buys nothing the `reason` text does not. Rejected, but noted: if partial skips later appear
  elsewhere, revisit.

#### C3. Skip silently

- Rejected. A property that silently loses its setter is exactly the class of disappearance ADR-064 exists
  to stop.

## Decision

Plan the getter and the setter of a property independently.

**1. Getter (both facets).** Delete the `prop.isMutable && … is BridgeType.Collection` clause from `:139`.
Add the two missing `Collection` routes so `Nullable(Collection)` is total:

- Kotlin, `ForwardPropertyKotlinEmitter.addGetter`, nullable branch: add `is BridgeType.Collection` alongside
  `ObjectHandle`/`Interface`, returning `COpaquePointer?` via the existing `nullableHandleBody`
  (**verified** that route exists and is used by nullable handles, `:62-70`).
- C#, `ForwardCirPropertyProjection.checkedGetter`, `is BridgeType.Nullable` branch: add a `Collection`
  case emitting `if (nativeResult == IntPtr.Zero) return null;` before the existing `collectionMaterialize(inner)`
  (**verified** that `collectionMaterialize` exists at `:238` and is already used by the non-null branch).

Getter eligibility is unchanged from the non-null collection getter: no element-type restriction
(**verified**, `FromHandle<T>` is a single runtime-generic helper).

> **Amendment (2026-09-04, refs [#52](https://github.com/xxfast/kotlin-native-nuget/issues/52)):**
> "no element-type restriction" was true of marshalling and false of spelling. `FromHandle<T>` is
> runtime-generic, but the C# read still has to name `T`, and `csharpType()` has no spelling for a
> sealed helper (class or interface), a bound interface, or a raw/unsupported type. A `List<Shape>`
> property with `Shape` sealed therefore passed `isPlannable` and crashed the projection with
> `No C# property type for SpecializedProtocol(...)`, where the bare `Shape?` spelling already
> skipped with `SKIPPED_UNSUPPORTED_PROPERTY`. A collection property now plans only when every
> component (element, or map key and value, recursively) has a C# spelling, and the diagnostic
> names the failing slot: `Collection (element type sealed helper sample.Shape)`. The read gate is
> still deliberately wider than the setter's `isWrappableComponent` above: `Instant`, `Duration`
> and interface components keep reading exactly as before.

**2. Setter eligibility.** A setter is planned when **all** hold:

- the declared type is a `BridgeType.Collection` of any kind (`LIST`, `MUTABLE_LIST`, `MAP`, `MUTABLE_MAP`,
  `SET`, `MUTABLE_SET`), **or a `Nullable` of one**; and
- every component satisfies the extracted `isWrappableComponent()` — element for list/set, key **and** value
  for map.

Nullability of the collection reference is orthogonal to component marshallability (Question D):
`var notes: List<String>?` is eligible, `var aliases: List<String?>` is not, and `var x: List<String?>?` is
not, for the element's sake only. A nullable collection setter takes the ordinary
`ForwardPropertySetter.Direct` route with a `COpaquePointer?` value, never `NullableDispatch`.

Otherwise the property is planned with `setter = null`, and one `SKIPPED_UNSUPPORTED_INPUT` diagnostic is
emitted naming the property, its Kotlin source node, the offending component type, and stating that the C#
property is read-only.

**3. Setter lowering.** Both sides need a new `Collection` branch, mirroring the callable path exactly:

- Kotlin, `ForwardPropertyKotlinEmitter.valueExpression()` (`:182-196`) currently `error(...)`s on
  `Collection` (**verified**). Add the six-kind lowering already written for parameters in
  `ForwardKotlinPlanEmitter.loweredArgument()` (`:664-695`, **verified**), e.g. for `LIST`:
  `value.asStableRef<MutableList<Any?>>().get().map { it as kotlin.String }`. Prefer factoring
  `loweredArgument`'s collection block out to a shared internal function over copying it; the two emitters
  already diverge and a second copy will drift. Add the same expression, `?.`-guarded, to the **nullable**
  arm: `value?.asStableRef<MutableList<Any?>>()?.get()?.map { it as kotlin.String }`. That is the whole
  Kotlin-side nullable story (Question D); the parameter type is already `COpaquePointer?`.
- C#, `ForwardCirPropertyProjection`: `setterNativeType` must return `IntPtr` for a `Collection`
  (it currently falls through to `wireType().csharpWireType()`, which is already `POINTER` → `IntPtr`, so
  this is likely no change; **verified** by reading `:300-305` and `:325-331`), and `valueArgument()` must
  gain a `Collection` branch. `valueArgument()` today falls through to `else -> name` (**verified**, `:308-321`),
  which would pass an `IReadOnlyList<T>` where the `DllImport` declares `IntPtr` — a C# compile error, not a
  silent miscompile, but it must be fixed. The branch is `NugetMarshal.CreateList(value)` /
  `CreateMap(value)` / `CreateSet(value)` per kind. For a **nullable** collection it is a call-site
  conditional, not a nullable-returning wrapper and no change to `CreateList`/`CreateMap`/`CreateSet`:
  `value != null ? NugetMarshal.CreateList(value) : IntPtr.Zero`. That is character-for-character the shape
  of the nullable-`Interface` arm three lines above it in the same function
  (`$name != null ? NugetMarshal.HandleOf($name) : IntPtr.Zero`, **verified** `:314-318`).

**4. Wrap-export gating — the trap.** `needsCollectionParamWrap`, which gates emission of the six Kotlin
`nuget_wrap_*` exports, is computed **only from `callableCatalog.plans`** (**verified**,
`NugetProcessor.kt:893-895`). Property plans are a separate list. A class whose only collection *input* is a
property setter would therefore generate C# calling `nuget_wrap_string` against a native library that never
exported it. The gate must be widened to include property plans that carry a planned collection setter.
The list/map/set *helper* exports are already type-occurrence-driven (`classesHaveLists` and friends,
`NugetProcessor.kt:789-845`, **verified**) and need no change, because a collection-typed property already
flips them through its getter.

**Expected consumer-side C#** for the recommended fixture:

```csharp
// data class Visit(val patient: String, val symptoms: List<String>, val notes: List<String>? = null)
public sealed class Visit : IDisposable
{
    public string Patient { get; }
    public IReadOnlyList<string> Symptoms { get; }
    public IReadOnlyList<string>? Notes { get; }   // null handle -> null, not IntPtr
}

// class Chart {
//   var tags: List<String>; var counts: Map<String, Int>; var seen: MutableList<Cat>
//   var notes: List<String>?; var moods: List<Mood>; var aliases: List<String?>
// }
public sealed class Chart : IDisposable
{
    public IReadOnlyList<string> Tags { get; set; }                 // wrappable element
    public IReadOnlyDictionary<string, int> Counts { get; set; }    // wrappable key + value
    public IList<Cat> Seen { get; set; }                            // ObjectHandle element
    public IReadOnlyList<string>? Notes { get; set; }               // null <-> IntPtr.Zero, Direct setter
    public IReadOnlyList<Mood> Moods { get; }                       // get-only: enum element not wrappable
    public IReadOnlyList<string?> Aliases { get; }                  // get-only: nullable *element*
}
```

with, at generation time:

```
w: [nuget:SKIPPED_UNSUPPORTED_INPUT] Chart.moods: its setter is not generated because the element type
   Mood cannot be written into a Kotlin collection; the C# property Moods is read-only
```

**5. Mutable collection property types are in scope and behave correctly.** ADR-073's "mutable collection
parameters do not write back" does **not** recur here. The parameter case is lossy because Kotlin receives a
detached copy and the caller never sees mutations. A property *setter* is an assignment: the C# consumer
writes `chart.Seen = list;` and the Kotlin `var` is reassigned to a freshly built `MutableList` with the same
contents. That is complete, honest value semantics. The remaining sharp edge is the getter, where the
returned `IList<T>` is a detached copy and mutating it does not reach Kotlin — pre-existing for every mutable
collection return, and now at least *repairable* by the consumer, because the property is settable. Document
the read-modify-write idiom; do not special-case mutable kinds in the predicate.

## Consequences

- `var` collection properties stop disappearing. Every one of them regains its getter; those with wrappable
  components also gain a setter.
- `val`/`var` nullable collection properties stop crashing `packNuget`.
- The forward ABI grows new `{class}_set_{prop}` exports for eligible mutable collection properties, so the
  ADR-054 contract hash changes. Nothing pre-existing changes shape.
- The property planner gains its first diagnostic channel. Feeding it into the existing
  `ForwardDiagnosticSink` is a prerequisite of this change and partially anticipates ROADMAP:281, which stays
  open for callables.
- `isBridgeableComponent()` / `isWrappableComponent()` move from `ForwardCallablePlanner` privates to
  package-level `internal`. No behaviour change for callables.
- Deferred: enum, `Char`,
  narrow-primitive, nullable, value-class and nested-collection components at a setter position (they follow
  the open ROADMAP write-side items and will widen automatically when `isWrappableComponent()` widens);
  extension-property collection setters follow the same predicate and need no separate decision, but should
  be fixture-covered.
- The temporary handle built by `CreateList`/`CreateMap`/`CreateSet` in a setter body leaks if the Kotlin
  setter throws, exactly as it does for a collection parameter today (ROADMAP's open `try`/`finally` item).
  This change inherits that bug; it does not introduce or fix it.

## Inferred claims in this ADR

Everything labelled **verified** above was read in repo source this session. The following are **inferred**:

1. The ObjC-export `readonly`/readwrite property precedent (from documentation, not a built framework).
2. ~~That the generated Kotlin setter bodies and the widened `nuget_wrap_*` gate compile on the real konanc
   toolchain, and that a collection setter round-trips end to end. No Kotlin/Native build was run this
   session. The walking-skeleton integration test must confirm it.~~ **Verified.** `scripts/verify.sh` is
   green (796 passed) against the real generated `Interop.cs`, including every setter shape in
   `IntegrationTests/CollectionPropertyIndependenceTests.cs`: `Chart.Tags`/`Counts`/`Seen`/`Codes` round-trip,
   `Chart.Notes` round-trips both an assigned list and an assigned `null`, and `ChartId.SetSymptomTags`
   round-trips over a by-value receiver.
3. That `setterNativeType` needs no change for `Collection` (its `else` branch already routes through
   `wireType()`, which maps `Collection` to `POINTER`). Read, not compiled.

**2026-09-11 amendment: the abstract method walk now keys on the same body-based predicate as the
property route.** `CirClassTranslator.kt`'s abstract method walk asked `parentDeclaration == cls ||
Modifier.OVERRIDE` and treated the answer as "has an implementation". Both directions were wrong. A
class's own `abstract fun` counted as implemented, so it reached neither the plan (the planner skips
`ABSTRACT`) nor the declaration walk, and vanished from C#; a subclass's `public override` was then
CS0115. An *inherited* member that does have a body but reached no plan (a generic interface default,
a refused parameter type, a base this ADR's siblings dropped) counted as unimplemented and rendered
`public abstract`, which a further C# subclass could not override (CS0534), and whose naive type
spelling could be CS0246 in the generated file itself.

The walk is now `if (!method.isAbstract) return@mapNotNull null`, KSP's `KSFunctionDeclaration.isAbstract`,
which is exactly what `prop.isAbstract()` gives the property route above and what
`isForwardPlannableMemberOf` already used. Bodiless renders `abstract`; a body that reached no plan is
dropped with the planner's existing warning, the same as on a concrete class. No export changes hands:
an abstract C# method has no `DllImport`.

Not fixed, noted: the abstract path's type mapping is still by simple name, so a class-declared
`abstract fun` using a cross-namespace or collection type renders that type unqualified. Evidence:
`Tier1AbstractMethodTest` and the `test/garage/` fixtures (`Vehicle`/`Truck`, `Register`/`Vault`/`StrongRoom`).

**2026-09-13 amendment: the unexported-interface case scoped out above now renders the same
`public abstract` declaration too, off a separately-planned catalog, with a named skip when the
member's own type is unbridgeable.** The 2026-09-11 amendment's declaration walk reads the C# type
off the ADR-113 interface declaration catalog, which is planned only for the exported `interfaces`
bucket, so an exported abstract class inheriting `val`/`var` from an interface ADR-101 drops from
the base list found no plan, `inheritedAbstractProperty` answered null, and the member vanished with
no diagnostic while a concrete subclass's `override` still rendered `public override`, `CS0115` on
the generated file itself.

The fix plans the gap: at the ADR-113 declaration-catalog construction site (`NugetProcessor.kt`),
every unexported `ClassKind.INTERFACE` supertype of an exported class (`getAllSuperTypes()`,
transitive, deduplicated by qualified name) is now planned too, through a third
`ForwardPropertyPlanner` whose `droppedProperties` stay deliberately unmerged into the warned set,
the same reason the ADR-113 planner's own drop channel is unmerged: merging would warn on every
concretely-implemented member of the unexported interface as well, not only the unimplemented one.
`inheritedAbstractProperty` looks the member up on this catalog exactly as it already did for the
exported case, so the base and the override read the same plan and cannot drift (`CS1715`/`CS0534`).
On a miss, and only when the owner is an unexported `INTERFACE`, a new `emitInheritedAbstractPropertySkip`
emits one named `SKIPPED_UNSUPPORTED_PROPERTY` at `<class>.<name>` (the class, not the interface,
since the class is where the member is missing from), classifying the property's own type the same
way the property planner would have. For the fixture's `val lining: Nesting.Lining`, `Lining` being a
nested class classifies as `ForwardPlanSkipReason.UNDECLARED_CLASS`, so the message reads "its type
`Lining` is a nested class or object never declared in C#". The diagnostic's *kind* is hardcoded to
`SKIPPED_UNSUPPORTED_PROPERTY` rather than read off `reason.toDiagnosticKind()`: that helper `error()`s
on the legacy-route reasons (`GENERIC`, `FLOW_PROTOCOL`, `CALLBACK_PROTOCOL`, …) a property can
genuinely hold, and the kind here is positional (it names where the drop happened), not
reason-derived. An unexported abstract **base class** owner is deliberately left silent by this same
miss site: its members are not planned anywhere, so a miss there says nothing about bridgeability and
would invent a reason for an ordinary type.

Pinned by `Tier1AbstractUnexportedInterfacePropertyTest`, the fixture `hidden/Nesting.kt`
(unexported: `val material: String`, `var height: Int`, `val lining: Nesting.Lining` where `Lining`
is a nested class, deliberately unbridgeable) plus `aviary/Nester.kt` (`abstract class Nester :
Nesting`, `class Wren : Nester` overriding all three), and
`IntegrationTests/AbstractUnexportedInterfacePropertyTests.cs`, which also compiles a pure C#
`PaperWren : Nester` subclass (`base(IntPtr.Zero)`) to prove the generated declaration is itself
overridable from C#, not only from a further Kotlin subclass.

No export, handle kind or ABI change; `LiveHandleTests` gains no row. Still open, **Inferred** (read,
not exercised by a fixture): whether KSP's `getAllProperties()` surfaces an abstract `val` from an
interface declared in a separate **dependency module** the same way it does for the same-module case
this fixture covers (Tier 1 here uses two same-module packages only).
