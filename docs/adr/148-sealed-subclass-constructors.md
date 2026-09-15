# ADR-148: Sealed subclass constructors

## Status

Accepted

## Context

GitHub issue #222, the other half of #126 / [ADR-122](122-handle-parameters-on-the-legacy-routes.md).
ADR-122 made a sealed arm at a *parameter* position render as the mapped type. That gave the
signature. Nothing gave the instance.

A sealed subclass of kind `CLASS` never exported its public constructor. Every arm shipped with only
the handle constructor ADR-009 mints:

```kotlin
sealed class Nap {
  data class Deep(val minutes: Int) : Nap()
  data object Zoomies : Nap()
}
```

```csharp
public sealed class Deep : Nap
{
    internal Deep(IntPtr handle) : base(handle)
```

There was no diagnostic either. `WARNING_NO_PUBLIC_CONSTRUCTOR` fires for a non-subclass type whose
every constructor is skipped, and it never fired for an arm, because an arm's constructors never
reached the planner to be skipped in the first place. From a consumer's side the constructor simply
did not exist.

Measured on `test-library` before this change: 51 sealed arms are generated and not one had a public
constructor. The reporter's own SDK has 43 arms in the same state, and keeps a hand-written
`open(host: String)` shim purely to work around it.

The route already existed in the same generated file. A **non-sealed** subclass exports its
constructor:

```csharp
public class HighPerch : Roost.Perch
{
    public HighPerch() : base(IntPtr.Zero)
```

So `: base(IntPtr.Zero)` then assigning the inherited handle is a shape the renderer already emits,
already through a base that owns `internal IntPtr _handle`. Only the sealed arm lost it.

### The mechanical cause, in three places

Verified in source, by reading the three sites and then by the generated output before and after.

1. **Plan.** `forward/ForwardCallablePlanner.kt`: `catalog()` collected constructors for `classes`
   only. A sealed subclass is deliberately absent from `classes` (ADR-009 / issue #54 declares it
   under its base), and `sealedSubclassEntries` filters `<init>` out of the arm's member walk.
   Nothing re-added it, so no `ForwardCallablePlan` with origin `CONSTRUCTOR` ever existed for an
   arm.
2. **CIR.** `cir/CirModel.kt`'s `CirSealedSubclass` had no `constructors` field to hold one, and
   `translateSealedClass` never ran the `callableCatalog.constructors(...)` query `translateClass`
   runs, nor the `noPublicConstructor` gate beside it.
3. **Render.** `cir/CirSealedRenderer.kt` emitted `internal ${name}(IntPtr handle) : base(handle)`
   and nothing else.

Each of the three is a place the arm route stopped short of what the class route already does. None
of them is a decision anyone made about constructors: the arm route grew member by member
(ADR-111 properties, ADR-116 methods, ADR-118 suspend, ADR-124 flow) and the constructor was the
member nobody had reached yet.

## Alternatives Considered

### 1. Plan an arm's constructors through the class route's own `constructorEntries` (chosen)

The arm's constructors go through the very same `constructorEntries` an ordinary class's do, with
the export prefix supplied rather than derived. Everything else is inherited by construction: the
ADR-115 opt-in-marker gate, the ADR-091 omitting overloads, the ADR-034 `_$n` numbering, the
ADR-105 `sealedAsHandle()` rewrite for a sealed-typed parameter, the error slot, the wire types.
The C# half is the same `ForwardCirPlanProjection.constructor` projection off the same plan, and the
renderer is the same two lines.

The prefix is the only seam, and it has to be a parameter rather than a derivation: a **nested**
arm's `nativePrefix()` already composes to `nap_deep`, but a **sibling** arm's composes to `label`,
not `flatshape_label`. ADR-125's `${base}_${arm}` rule is what both need, and it is the rule the
arm's properties and methods already export under.

### 2. Add a public `IntPtr` constructor

Rejected, as ADR-122 alternative 4 rejected it. The handle constructors are `internal` deliberately
(ADR-034 / ADR-066), and a consumer has no legitimate source for the pointer. That rejection stands
unchanged here: this ADR adds a constructor *beside* the internal one and leaves the internal one
exactly as it was.

### 3. A static `Create` / `Of` factory beside the internal constructor

Rejected by the issue. A constructor is the natural C# shape for a Kotlin constructor, and the
non-sealed subclass route already proves it is expressible through the inherited handle. A factory
would be a second spelling for one concept, and would make an arm read differently from the
non-subclass data class with the identical parameter list.

### 4. Fix only the `data object` arm

Not a fix. `Zoomies` was already reachable off a return position. `Deep` is the cell.

### 5. Skip the methods that take an arm with a `SKIPPED_*`

Rejected by the issue. The signature is correct; the parameter being unconstructible is the defect,
not the method. Skipping would hide it.

### 6. Give an arm a `copy` too, since `constructorEntries` plans one for a data class

Deliberately off. The sealed route renders no `Copy` member on an arm, so a planned `copy` entry
would be a Kotlin export with no C# import behind it. `constructorEntries` therefore takes a `copy`
flag, false on the arm path. Widening the arm surface to `copy` is a separate decision with its own
fixture, not a side effect of this one.

## Decision

A sealed subclass of kind `CLASS`, nested or sibling, `data` or plain, `open` or final, whose public
constructor's parameters are all bridgeable, exports that constructor, spelled exactly like a
non-subclass class with the same parameters.

`object` and `data object` arms are unchanged: Kotlin gives them no public constructor to export, so
the planner's `classKind == ClassKind.CLASS` filter never reaches them and their generated block is
byte-identical to what shipped.

Where every public constructor is skipped for a stated reason, `WARNING_NO_PUBLIC_CONSTRUCTOR` fires
naming the arm and the reason, and the consumer-facing `<remarks>` twin renders on the arm, exactly
as both already do for a non-subclass type.

### Consumer-facing C# API

```csharp
public abstract class Nap : IDisposable, INugetHandle
{
    internal IntPtr _handle;

    public sealed class Deep : Nap
    {
        internal Deep(IntPtr handle) : base(handle)
        {
        }

        [DllImport("test", EntryPoint = "nap_deep_create")]
        private static extern IntPtr Native_Create(int minutes, out IntPtr error);

        public Deep(int minutes) : base(IntPtr.Zero)
        {
            IntPtr handle = Native_Create(minutes, out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
            _handle = handle;
        }
```

The handle lands on the sealed base's `internal IntPtr _handle`. A nested arm sits inside the base's
braces and a sibling arm sits in the same generated file, so the field is assignable from both.

### Plan (`forward/ForwardCallablePlanner.kt`)

`constructorEntries` gains two defaulted parameters: `prefix`, defaulting to `cls.nativePrefix()`
so every existing caller is unchanged, and `copy`, defaulting to true for the same reason.
`catalog()` gains one loop beside the existing `classes.forEach { constructorEntries(it) }`, over
each sealed base's `CLASS`-kind arms, passing `${sealed.nativePrefix()}_${arm.lowercase()}` and
`copy = false`. An abstract intermediate arm returns empty from the first line of
`constructorEntries`, as an abstract class already does.

### Kotlin half (`exports/SealedClassExports.kt`)

One `callableCatalog.constructors(subQualifiedName).forEach { addForwardKotlinPlanExport(it) }` in
the arm loop, the same line `ClassExports` already has. The `CONSTRUCTOR` origin renders
`NugetHandles.retain(<target>(args))` with the target being the arm's qualified Kotlin name, which
spells `Nap.Deep(minutes)` for a nested arm and `Label(text)` for a sibling one.

### C# half (`cir/CirModel.kt`, `cir/CirClassTranslator.kt`, `cir/CirNativeImports.kt`)

`CirSealedSubclass` gains `constructors: List<CirConstructor>` and `remarks: String?`.
`translateSealedClass` populates both off the same catalog query, the same
`ForwardCirPlanProjection.constructor` projection and the same `warnNoPublicConstructor` /
`noPublicConstructorRemark` pair `translateClass` uses.

`CirClass.constructorNativeImport` is split into a free
`constructorNativeImport(libraryName, nativePrefix, ctor)`, the same ADR-111 split
`propertyNativeImports` already carries, and `CirSealedSubclass.ordinaryNativeImports` adds the
arm's `_create` imports. That is what puts them in front of the ADR-055/ADR-078 contract check as
structural nodes rather than scraped text.

### Render (`cir/CirClassRenderer.kt`, `cir/CirSealedRenderer.kt`)

The `[DllImport]` plus constructor block is extracted into
`StringBuilder.renderConstructorMember(libraryName, nativePrefix, className, ctor, hasSuperClass)`.
`renderClassConstructor` calls it; `sealedSubclassBlock` calls it once per arm constructor with
`hasSuperClass = true`, re-indented one level like every other arm member. One body, two callers,
so the two spellings cannot drift.

### Scope

- In: sealed **class** bases and eligible sealed **interface** bases (both reach
  `translateSealedClass`), nested and sibling arms, primary and secondary constructors, ADR-091
  omitting overloads, ADR-105 sealed-typed constructor parameters.
- Out, unchanged: `object` / `data object` arms, abstract intermediate arms, an arm's `copy`, and
  the internal handle constructor, which stays exactly as it is on every arm.

### The `int` to `nint` silent bind

ADR-134's 2026-09-11 amendment recorded the hazard for `Purr.On`: in .NET 7+ `IntPtr` is `nint`,
`int` converts to `nint` implicitly, and the generated `Interop.cs` compiles *into* the consumer
assembly, so `internal On(IntPtr handle)` is visible there. `new Purr.On(9)` therefore compiled
clean, bound to the internal handle constructor with 9 as a fabricated stable-ref address, and
access-violated on first use.

For an arm whose first parameter is an `int`-convertible scalar, an exported exact-match constructor
removes the hazard: an exact `int` match beats a user-defined implicit conversion, so positional
`new Nap.Deep(12)` now resolves to the real constructor. The hazard is **not** removed in general.
It survives for every `object` arm, for every arm whose constructors were all refused, and for any
arm whose exported constructor does not offer an exact one-argument match. The existing ROADMAP
backlog item for the silent bind stays open, narrowed by this ADR rather than closed.

This is also why `IntegrationTests/SealedSubclassConstructorTests.cs` and the `LeakTests` row spell
every construction with a named argument. Positional would have compiled against the internal
constructor before the fix and dereferenced pointer 12, taking the test host down instead of failing
a test.

## Consequences

- Additive. 26 of `test-library`'s 51 generated arms gained a public constructor; the remaining 25
  are `object` arms, abstract intermediate arms, and the two arms whose constructors are genuinely
  refused (`FlatShape.Groomed`, opt-in-marked parameter type, and `Issue56LoadState.Failure`, a
  nullable one). No entry point that shipped before changed, because every arm export is minted
  under the arm prefix the arm's properties and methods already used.
- `WARNING_NO_PUBLIC_CONSTRUCTOR` now fires for a sealed arm. Two new lines appear in
  `test-library`'s pack log (`FlatShape.Groomed`, `Issue56LoadState.Failure`), both of which were
  silent drops before.
- `Tier1SiblingSealedSubclassTest`'s "constructed only through the sealed route" cell is amended,
  not deleted: issue #110's invariant was that the arm is **declared once**, and that is unmoved.
  What changed is that the arm now also has a public constructor, under the sealed prefix. The
  export-prefix cell in the same class (`no export_label_create`) is untouched and still passes,
  which is what proves the arm did not quietly regain a plain-class route.
- Cost: 7 source files (`ForwardCallablePlanner.kt`, `SealedClassExports.kt`, `CirModel.kt`,
  `CirClassTranslator.kt`, `CirNativeImports.kt`, `CirClassRenderer.kt`, `CirSealedRenderer.kt`),
  three new Tier 1 test files, one amended one, one xunit file, one leak row, two fixture files,
  this ADR. Small, because the class route already owned every rule: the change is three wirings
  and one extracted renderer helper.

## Verified claims

- The three drop sites, read in source before the change and confirmed by the generated output
  after it.
- 51 arms generated for `test-library`, 0 with a public constructor before, 26 with one after.
  Counted from the packed `Interop.cs` on both sides.
- `FlatShape.Groomed` warns with `OPT_IN_MARKER_TYPE` and carries the `<remarks>` twin; its block
  has the internal handle constructor and no public one. Read out of the pack log and the packed
  `Interop.cs`.
- A sealed-typed constructor parameter on an arm takes the ADR-105 rewrite:
  `public Framed(global::Interop.Shape inner, string label)` on the C# half and
  `inner.asStableRef<Shape>().get()` on the Kotlin half. Pinned by
  `Tier1SealedSubclassConstructorSealedParameterTest`.
- The round trip: construct in C#, pass to a Kotlin method taking the arm and to one taking the
  base, read the payload back. `IntegrationTests/SealedSubclassConstructorTests.cs`, 9 facts, plus a
  `LeakTests` row proving the new mint path returns to baseline.
- The `int` to `nint` resolution claim: a positional `new Nap.Deep(12)` binds to the exported
  `Deep(int)` and not to the internal `Deep(IntPtr)`. Pinned by the
  `Deep_PositionalArgument_BindsToTheExportedConstructorNotTheHandleOne` fact, which reads the
  payload back through Kotlin, so a bind to the handle constructor would crash rather than pass.

## Inferred claims

1. **"No released entry point changed."** Inferred from the prefix rule rather than from a byte
   diff of a released package: every new export is `${base}_${arm}_create...`, a name no arm route
   minted before, and the existing exports are produced by untouched code paths. A byte diff across
   a release boundary was not run.
