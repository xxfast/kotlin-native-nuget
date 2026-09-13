# ADR-131: Suspend route: a sealed base at a return reads through `FromHandle`

## Status

Accepted (2026-09-13)

## Context

A `suspend fun` whose return type is a sealed **base** (`suspend fun next(): Job`, or an eligible
`sealed interface` per ADR-112/125) did not compile on the C# side. The legacy suspend route
(ADR-019, kept legacy by ADR-062, re-keyed to sealed arms by ADR-118) completed its
`TaskCompletionSource<Job>` with `t.SetResult(new Job(resultPtr));`, and ADR-009 renders `Job` as
`public abstract class Job`: CS0144, and the whole `Interop.cs` failed to compile, not just that
member (ROADMAP Phase 3; backlog `suspend-sealed-base-return-abstract-class-cs0144.md`).

ADR-118's 2026-09-11 amendment fixed the nested-**arm** spelling (`Task<Job.Running>` /
`new Job.Running(resultPtr)`) through `KSClassDeclaration.nestedCsName()` and split this case out:
an arm name resolves to a concrete constructor, a base name does not.

### Where the pieces were before this change (all **verified by reading**, `main` at `a4f88c0`, 2026-09-13)

| Concern | Site | What it did |
|---|---|---|
| Return classification | `ForwardLegacyRouteCollections.kt:157-181` `legacyReturnShape` | `if (expanded.arguments.isEmpty()) return Plain` at `:161`: every non-generic return, sealed base included, was `Plain` |
| Shape type | `ForwardLegacyRouteCollections.kt:139-149` `ForwardLegacyReturnShape` | ADR-119's `Plain` / `Marshalled(Collection)` / `Refused(description)` |
| C# class/arm projection | `CirClassTranslator.kt:1454-1527` `asyncMembers` | spells `Task<T>` via `nestedCsName()` (`:1481-1490`), set `asyncResultRead` only for a collection (`:1526`) |
| C# top-level projection | `CirFunctionTranslator.kt:628-707` `translateSuspendFunction` | same |
| C# completion | `CirConcurrencyRenderer.kt:151-180` `resultExtraction` in `renderAsyncMethod` | `asyncResultRead != null` wins at `:164`; nullable object at `:174-177` was `resultPtr == IntPtr.Zero ? null : new T(resultPtr)`; `else` at `:178-179` was `new T(resultPtr)` |
| Kotlin mint | `SuspendFunctionExports.kt:191-209` `buildSuspendMethodBody`, `:163-189` top-level; `resultRefExpression :225-227`; `legacyBoxedResult :236-238` | ADR-128 body: `launchForCSharp(scope, ...) { val result = obj.next(); NugetHandles.retain(result) }`; `result` is the runtime object, i.e. the concrete arm |
| Discriminator | `SealedClassExports.kt:42-46` (`${prefix}_get_type`, `asStableRef<Base>().get()` + `when`), `CirSealedRenderer.kt:52-63` (`internal static Base FromHandle(IntPtr)`) | reads the arm off the handle and constructs it, taking ownership |
| Plan-route precedent | `ForwardCallablePlanner.kt:1956` (`result.sealedAsHandle()`), `:3131-3143` (`sealedAsHandle`), `ForwardCirPlanProjection.kt:1315-1321` (`handleReconstruction`: `viaDiscriminator -> "${csharpType()}.FromHandle(...)"`) | ADR-105: a sealed base at a plan return/parameter/property is planned as its `ObjectHandle(viaDiscriminator = true)` and read through `FromHandle` |
| Classifier | `ForwardBridgeTypeClassifier.kt:205-232` | `SpecializedProtocol(sealed helper, sealedHandle = ObjectHandle(viaDiscriminator = true))` only when `isEligibleSealedType() && qualifiedName in exportedObjectHandles`; else `sealedHandle = null` |

**Verified by the shipped plan route, not new to this ADR**: a `StableRef` minted on the concrete
arm and read as the base resolves the arm at runtime (`ForwardKotlinPlanEmitter.kt:461` mints on
the object, `SealedClassExports.kt:46` reads it as `Base`, `IntegrationTests/SealedInterfaceTests.cs:53`
and `Tier1SealedReturnPlanTest.kt:65` exercise it). The Kotlin half of the suspend route already
does that mint, so only the C# read was wrong.

## Alternatives Considered

### 1. A `Discriminated` return shape, read through `asyncResultRead` (chosen)

Add `ForwardLegacyReturnShape.Discriminated(handle: BridgeType.ObjectHandle, nullable: Boolean)`.
`legacyReturnShape` classifies a `Modifier.SEALED` declaration **before** the `arguments.isEmpty()`
early return, through `classify(type).sealedAsHandle()`:

- `ObjectHandle(viaDiscriminator = true)` → `Discriminated`
- anything else (ineligible sealed interface, out-of-scope sealed class: `sealedHandle == null`)
  → `Refused(expanded.legacyDescription())`, so both halves skip it with the existing
  `SKIPPED_UNSUPPORTED_RETURN` gate rather than emitting `new Mixed(resultPtr)` (CS0246/CS0144).

Both C# translators set `asyncResultRead` for `Discriminated` off the same `nestedCsName()` string
the route already uses for `Task<...>`:

```C#
t.SetResult(Job.FromHandle(resultPtr));                                   // Task<Job>
t.SetResult(resultPtr == IntPtr.Zero ? null : Job.FromHandle(resultPtr)); // Task<Job?>
```

`renderAsyncMethod` needed no change (`:164` already prefers `asyncResultRead`); this is how
ADR-119 injected the collection read. Kotlin half: no change (`legacyBoxedResult` already returns
bare `result` for every non-`Marshalled` shape; `resultRefExpression` already null-guards).

Pros: one new variant on the type ADR-119 minted for exactly this purpose; no renderer string
tests; every gate that reads `legacyReturnShape` (`legacyRefusedReturn`, both translators, both
Kotlin builders) sees it at once; the ineligible case stops producing uncompilable C#; same speller
as the rest of the route. Cons: the read is spelled relatively (no `global::`), the ADR-118 open
item this ADR keeps open on purpose.

### 2. A name-based branch in `renderAsyncMethod`

Carry a `CirMethod.asyncReturnIsSealedBase` flag (or test the return name against the sealed set)
and add an arm to `resultExtraction`. Rejected: it duplicates the classification the shape type
already owns, leaves the ineligible case uncompilable, and adds a fourth spelling site to a `when`
the repo has been shrinking (ADR-119 chose `asyncResultRead` for the same reason).

### 3. Migrate the suspend route onto the callable plan

The plan already does this at `:1956`. Rejected here as ADR-118 rejected it: it is a route
migration, not a return-shape fix, and moves every suspend member's rendered text.

## Decision

Alternative 1, shipped as described. Scope:

- **In**: sealed class base and eligible sealed interface base (ADR-112/125), non-null and nullable,
  on all three suspend owners (ordinary class, sealed arm, top-level). Ineligible/out-of-scope sealed
  types become `Refused` (named skip, `SKIPPED_UNSUPPORTED_RETURN`).
- **Out**: `global::` qualification on the suspend route (ADR-118's already-open item, unchanged by
  this ADR: the discriminated read is spelled exactly as relatively as every other suspend-route
  return); a `suspend fun` returning a collection of a sealed base (`List<Shape>`, ROADMAP Phase 6)
  is a **separate** mapping decision and was **not** folded in here, since it needs its own unwrap
  in `legacyReturnCollectionKinds`/the collection element read, not this return-position rewrite;
  Flow/StateFlow elements (ADR-123/124 own those, and `legacyFlowElementShape` is a separate
  function).

### Mechanism, as shipped (`ForwardLegacyRouteCollections.kt`)

`legacyReturnShape` gates on the declaration's `Modifier.SEALED` *before* the non-generic early
return (a sealed base carries no type arguments, so it would otherwise fall into `Plain`):

```kotlin
if ((expanded.declaration as? KSClassDeclaration)?.modifiers?.contains(Modifier.SEALED) == true) {
  val unwrapped: BridgeType = classify(type).sealedAsHandle().let {
    if (it is BridgeType.Nullable) it.type else it
  }
  return if (unwrapped is BridgeType.ObjectHandle && unwrapped.viaDiscriminator) {
    ForwardLegacyReturnShape.Discriminated(unwrapped, expanded.isMarkedNullable)
  } else {
    ForwardLegacyReturnShape.Refused(expanded.legacyDescription())
  }
}
```

The gate is the declaration's `sealed` modifier, not the resulting classification: an ordinary
class return is an `ObjectHandle` too, and must keep its `new T(resultPtr)` unchanged. Both C#
translators (`CirClassTranslator.kt:1526-1533`, `CirFunctionTranslator.kt:704-710`) add a
`Discriminated` arm to the `asyncResultRead` `when`, calling the new
`legacyDiscriminatedRead(handle, csharpType, nullable)`:

```kotlin
if (nullable) "$handle == IntPtr.Zero ? null : $csharpType.FromHandle($handle)"
else "$csharpType.FromHandle($handle)"
```

`csharpType` is passed in already spelled by the caller's own `nestedCsName()`, the string the
route already uses for the declared `Task<...>` type, so the two can never drift apart. No change
to `renderAsyncMethod`, `legacyBoxedResult`, or `resultRefExpression`.

### Mechanism claims, labelled

- Kotlin mints the concrete arm; `FromHandle` reads the base and constructs the arm, owning the
  handle: **verified** by the shipped ADR-105 route (sites above), and by execution here
  (`Tier1SuspendSealedBaseReturnTest`, `IntegrationTests/SealedSubclassMethodTests.cs`,
  `IntegrationTests/SealedInterfaceTests.cs`). Not re-spiked, only re-used.
- `renderAsyncMethod` prefers `asyncResultRead`: **verified by reading**
  `CirConcurrencyRenderer.kt:164`, needed no change.
- `Job` (base) is `IDisposable` only; sync `Dispose()` on a suspending arm reached through the base
  releases scope and handle: **verified by reading** and by execution
  (`NextLaterAsync_SealedBaseAtASuspendReturnOnAnArm_DiscriminatesToTheOtherArm` uses `using`, not
  `await using`, on the base-typed local, and passes).
- An **ineligible** sealed type (a second superclass, ADR-125's `Odd`/`Rhythm` shape) at a suspend
  return is `Refused` and reports `SKIPPED_UNSUPPORTED_RETURN`, naming the offending type: **verified
  by execution**, `Tier1SuspendSealedBaseReturnTest`'s `an ineligible sealed base at a suspend
  return is refused by name` cell. Before this change the same fixture rendered `Task<Mixed>`
  against a type declared only as `IMixed`, CS0246; this is a second compile-time failure the ADR
  draft only inferred, now confirmed by the negative-control cell.
- The `List<Shape>` suspend-return fold-in (ROADMAP Phase 6, formerly proposed as an in-scope item
  of this ADR) was **not attempted**: it needs `.sealedAsHandle()` applied inside
  `legacyReturnCollectionKinds`/the collection element read, a different code path from the one this
  ADR changed, and remains an open ROADMAP line.

### Fixture and tests, as shipped

`test-library/.../issue115/JobSample.kt`: `Job.Running.nextLater(): Job` (answers with a *different*
arm than the receiver), `Job.Running.nextOrNullLater(): Job?`, `Job.Running.nextOrThrowLater(step):
Job` (the throw path, no result minted), `JobFactory.nextLater(progress): Job` (dispatches across
all three arm shapes by input), top-level `anyNextLater(progress): Job`.
`test-library/.../issue54/SealedInterfaceSample.kt`: `Monitor.nextPulseLater(bpm): Pulse`, the
sealed-**interface** twin, same fix through the same `CirSealedRenderer`-generated `FromHandle`.

Tier 1: `Tier1SuspendSealedBaseReturnTest` (8 cells: class-route base, nullable twin, top-level
route, sealed-interface base, the arm negative control that must keep `new Shape.Circle(resultPtr)`,
the ineligible-interface refusal, plus the Kotlin-compiles-clean and count-of-three-`FromHandle`
controls). Integration: `IntegrationTests/SealedSubclassMethodTests.cs`
(`NextLaterAsync_SealedBaseAtASuspendReturnOnAnArm_DiscriminatesToTheOtherArm`,
`NextLaterAsync_SealedBaseOnAnOrdinaryClass_DispatchesPerArm`,
`NextOrNullLaterAsync_NullableSealedBase_ReadsNullAsNullAndAnArmAsTheArm`,
`NextOrThrowLaterAsync_SealedBaseSuspendThatThrows_PropagatesAndStillBinds`,
`AnyNextLaterAsync_TopLevelSuspendReturningTheSealedBase_UsesFromHandle`),
`IntegrationTests/SealedInterfaceTests.cs`
(`NextPulseLaterAsync_SealedInterfaceBaseAtASuspendReturn_UsesFromHandle`).
`LeakTests/LiveHandleTests.cs` rows 9e (`Suspend_ReturningTheSealedBase_ReturnsToBaseline`), 9f
(`Suspend_ReturningTheNullableSealedBase_ReturnsToBaseline`), 9g
(`Suspend_ReturningTheSealedBase_Throws_ReturnsToBaseline`, a 5000-iteration tight loop, the first
suspend-throw leak row in the file).

Verify: `scripts/verify.sh` green, 1731 passed / 0 skipped / 0 failed (`IntegrationTests`), 31 passed
(`LeakTests`).

## Consequences

- `suspend fun next(): Job` binds as `Task<Job>`, completes with the concrete arm read through
  `Job.FromHandle(resultPtr)`; `Interop.cs` compiles. The ROADMAP Phase 3 line naming this defect
  closes; its backlog file is deleted.
- Named skip, unchanged behaviour otherwise: a suspend return of an ineligible sealed type is
  `SKIPPED_UNSUPPORTED_RETURN` where it was previously silently uncompilable.
- ADR-118 gets a dated amendment note pointing here, closing the item its own amendment recorded as
  "split out, not this amendment's". ADR-105 scope is unchanged; ADR-119's own `Refused` return of
  a *collection* of a sealed base is unaffected and stays a separate, open ROADMAP line.
- `LeakTests` baseline count moves by the three new rows (9e/9f/9g) only; no existing mint changes.
- Still open, deliberately: `global::` qualification on the suspend route (ADR-118); the
  `List<Shape>` suspend-return fold-in (ROADMAP Phase 6); `legacyReturnCollectionKinds`'s
  `else -> emptySequence()` branch (`ForwardLegacyRouteCollections.kt:~340`) has no test coverage on
  the non-`Marshalled` path, a residual noticed while reading this feature's code but not exercised
  by its fixture (ROADMAP Phase 4).
