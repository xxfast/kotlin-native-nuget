# ADR-111: Sealed-subclass properties move onto the ADR-062 property plan

## Status
Accepted

## Context

[ADR-009](009-sealed-class-mapping.md) shipped sealed classes on a hand-rolled route: the Kotlin
exports are spelled in `exports/SealedClassExports.kt` (property loop `:63-163`), the C#
projection in `cir/CirClassTranslator.kt`'s `translateSealedClass` (`:1067-1345`), and the
`DllImport`s as raw text in `cir/CirSealedRenderer.kt` (`:71-84`). Every other class property
has since moved onto the [ADR-062](062-forward-callable-plan.md) property plan
(`ForwardPropertyPlanner` + `ForwardPropertyKotlinEmitter` + `ForwardCirPropertyProjection`),
and the legacy route was never caught up. It is now a fourth copy of the property getter, and
it is the copy that is wrong.

Four defects, each **verified by reading the cited lines** (none reproduced by a fixture; the
ROADMAP items they close say the same):

| # | ROADMAP line | Where | Defect |
|---|---|---|---|
| 17 | 26 | `SealedClassExports.kt:102-111` | The enum branch tests `isEnumType` before nullability and spells `$access.ordinal` unconditionally, so a `val mood: Mood?` emits `.ordinal` on `Mood?`: a Kotlin compile error, not a wrong value. |
| 18 | 27 | `CirClassTranslator.kt:1327` | Nullable reference getter is `Native_Get_x(_handle, out _) == IntPtr.Zero ? null : new T(Native_Get_x(_handle, out _))`. Two calls, two `StableRef.create`, one leaks. The Throwable arm at `:1290-1305` already avoids exactly this and says so in its comment. |
| 19 | 28 | `CirSealedRenderer.kt:80-83` | Only the `_has_value` import carries `[return: MarshalAs(UnmanagedType.I1)]`. The single-call import (`:83`) and the `_value` import (`:81`) do not, so a `Boolean` property, or `Boolean?`'s `_value` call, marshals a 4-byte C# `bool` against Kotlin's 1-byte return. |
| 20 | 29 | `CirClassTranslator.kt:1320-1335` | The scalar, enum, string, reference, `Map` (`:1254`) and `Set` (`:1268`) getters pass `out _` and discard the error slot; only `List`/`MutableList` (`:1225-1253`) and Throwable (`:1290-1305`) read it and throw. A throwing custom getter returns a default. The ADR-081 value-class element re-wrap (`ForwardCirCollectionComponents.kt:125-138`) is also absent: the sealed `List` arm calls `NugetMarshal.FromHandle<T>` bare. |

And a wider gap, **verified**: `translateSealedClass`'s type dispatch (`:1152-1176`) knows only
scalar (`KOTLIN_TO_CSHARP_RETURN`), enum, the six collection kinds, lambda, Throwable, and an
`exportedTypes` class. Anything else is `isReferenceType` and skips with the unactionable
`SKIPPED_UNSUPPORTED_TYPE` hint ROADMAP line 37 already names, so `Instant`, `Duration`, `Uuid`,
a value class, and an interface on a sealed subclass are absent from C#. Not silent (a diagnostic
fires) but wrong: each of those types binds on an ordinary class today. Worse, the Kotlin side
(`SealedClassExports.kt:139-161`, the final `else`) boxes any of them into a `StableRef` and
still emits the export, the orphan-export drift ROADMAP line 51 records for the interface case.

The planner already has every one of these right. **Verified by reading**:

- nullable enum and every other has-value fan-out: `ForwardPropertyGetter.LegacyTwoCall`
  (`ForwardPropertyPlanner.kt:243-248`), projected at `ForwardCirPropertyProjection.kt`
- `bool` marshalling: `marshalBooleanReturn = property.nativeReturnType == "bool"`
  (`CirNativeImports.kt:45`)
- error slot on every getter: `checkedGetter` (`ForwardCirPropertyProjection.kt:204-296`)
- one call for a nullable reference (`:250-253`)
- ADR-081 re-wrap on collection components (`ForwardCirCollectionComponents.kt:125-138`)
- ADR-105 discriminator reconstruction for a sealed-typed member (`:305-311`)
- `isPlannable` (`ForwardPropertyPlanner.kt:545-575`) admits `Instant`, `Duration`, `Uuid`,
  `Throwable`, `Interface`, readable `Collection`, and value classes over
  String/primitive/enum/handle

## Prior art (to the depth that changes the decision)

- **Swift Export / ObjC export**: Kotlin/Native declares a sealed subclass as an ordinary class
  in the hierarchy and its members go through the same member translation as any class member;
  there is no sealed-specific member route. Inferred from the Kotlin docs
  ([ObjC interop, classes and objects](https://kotlinlang.org/docs/native-objc-interop.html#classes-and-objects)),
  no spike. ADR-009 already records the same. It settles the direction: one member route, the
  subclass is just a class with a discriminator on top.
- Skipped: JVM (a sealed subclass is a plain class, nothing to mirror), JS/Wasm (no handle seam).

## Alternatives Considered

### 1. Plan sealed-subclass properties with `ForwardPropertyPlanner` and project them with the shared emitter and projection (chosen)

Add `sealedSubclassProperties(sealed, subclass)` to the planner, mirroring `classProperties`
(`:93-113`), feed the sealed classes into `catalog()`, and have the three legacy files look each
property up by symbol and delegate. The ADR-009 discriminator, dispose, `equals`/`hashCode`/
`toString` exports and the `FromHandle` switch stay where they are.

Pros: closes all four defects and the type-coverage gap in one move, by deletion rather than by a
fifth spelling; `var` setters, Uuid, Duration, Instant, value classes and interfaces on a sealed
subclass arrive for free; the ABI contract check (`ForwardAbiContract.assertMatchesPlan`) now
covers these exports against the plan instead of only text-to-text.
Cons: renames the presence export (see Consequences); six Tier 1 tests change spelling; a
residual legacy branch survives for lambda properties.

### 2. Four point fixes in the legacy route (rejected)

Patch the enum branch for nullability, collapse the nullable-reference double call, add
`MarshalAs` to the two imports, and thread `out IntPtr error` plus a throw through every arm.

Rejected: leaves the `isReferenceType` skip list and the ADR-081 gap open, and the error-slot fix
is a fourth hand copy of `checkedGetter` in a file that has already drifted from the other three
twice (#38 and ADR-107 both patched this loop after the planner had the answer).

### 3. Migrate only the C# projection, keep the legacy Kotlin exports (rejected)

Have `translateSealedClass` build a `ForwardPropertyPlan` for the projection while
`SealedClassExports` keeps emitting the Kotlin side by hand.

Rejected: two halves of one ABI planned by two different pieces of code. The planner's
`LegacyTwoCall` presence call is the bare `getExport`
(`ForwardPropertyPlanner.kt:244`) while the legacy Kotlin emits `${prefix}_get_x_has_value`, so
the plan would need a per-route naming special case, and the ABI contract check would have to be
taught which universe each export belongs to. Fixes 18, 19, 20 and leaves 17 (a Kotlin-side bug).

## Decision

Adopt alternative 1.

### Planner

`ForwardPropertyPlanner.sealedSubclassProperties(sealed: KSClassDeclaration, subclass: KSClassDeclaration)`
mirrors `classProperties` (**verified** shape at `:93-113`): `subclass.getAllProperties()`,
public filter, `propertyPlan(...)` with `position = CLASS`,
`receiver = ForwardPropertyReceiver.Handle(subQualifiedName)`, `superClass = null`, and

```kotlin
getExport = "${sealedPrefix}_${subPrefix}_get_${prop.simpleName.asString()}",
setExport = "${sealedPrefix}_${subPrefix}_set_${prop.simpleName.asString()}",
```

where the prefixes are the lowercased simple names `translateSealedClass` already uses
(`CirClassTranslator.kt:1075-1080`, **verified**). `getExport`/`setExport` are plain parameters
of `propertyPlan` (`:223-228`, **verified**), so no other planner code changes for the naming.

`catalog(classes, topLevel, extensions)` (`:80-91`) gains a `sealed: List<KSClassDeclaration>`
argument and does `sealed.forEach { s -> s.getSealedSubclasses().forEach { addAll(sealedSubclassProperties(s, it)) } }`.
`NugetProcessor.kt:685` and `ForwardCallablePlanner.catalog` (`:496-498`) thread the
`rootSealedClasses` list (`NugetProcessor.kt:433-437`) through. The ordinary `classes` list
already excludes sealed classes (`:415`, **verified**), so nothing double-plans.

Plan symbol is `"$subQualifiedName.$propName"`, the key `ForwardCallablePlanner.propertyFor`
(`:290`) looks up.

### Kotlin exports

`SealedClassExports.kt:63-163` property loop becomes:

```kotlin
for (prop in properties) {
  val planned = callableCatalog.propertyFor("$subQualifiedName.${prop.simpleName.asString()}")
  if (planned != null) { addForwardPropertyPlanExports(planned); continue }
  // residual legacy branch: lambda-typed properties only (see Scope)
}
```

`addForwardPropertyPlanExports` is the same entry `PropertyExports.kt:20`,
`InterfaceExports.kt:41` and `ExtensionPropertyExports.kt` use (**verified**), so
`SealedClassExports` needs the `callableCatalog` threaded in the way those files already receive
it.

### C# projection

`translateSealedClass` replaces the per-type `when` chains with
`callableCatalog.propertyFor(symbol)?.let { ForwardCirPropertyProjection.classProperty(it) }`
(`ForwardCirPropertyProjection.kt:13-20`, **verified**; the receiver spelling is `_handle`, which
is what the sealed subclass block declares). The lambda arm stays as the fallback.

`CirSealedRenderer.sealedSubclassBlock` renders imports through
`propertyNativeImports(prop)` + `renderDllImport` instead of the two raw `appendLine`s at
`:71-84`. `propertyNativeImports` sets `hasSyncErrorOut` and `marshalBooleanReturn`
(`CirNativeImports.kt:32-58`, **verified**) and adds the setter import when the plan has one.
It `require`s `!usesLegacyNativeImport()` (`:33-35`), which is true only for Flow and lambda
types (`:86-91`, **verified**), so the residual lambda branch keeps its raw import.

Note `propertyNativeImports` is an extension on `CirClass`, using `libraryName` and
`nativePrefix`. The sealed renderer needs the same two values from `CirSealedClass` /
`CirSealedSubclass` (`sealed.libraryName`, `subclass.nativePrefix`, both present, **verified**
at `CirSealedRenderer.kt:80`). Either lift the helper to take those two strings, or give
`CirSealedSubclass` a small adapter. Inferred: no attempt was made to compile either.

### ABI contract

`ForwardAbiLegacyRoutes` keeps `SEALED_CLASS` (`:43`, `:96`) for the discriminator, dispose and
data-class method exports, which have no plan shape. `ForwardAbiContract.assertMatchesPlan`
(`:151-160`, **verified**) already unions `catalog.plans` with `catalog.propertyPlans`, so the
new plans are checked with no change there.

Double counting, **verified not to occur** by construction at `NugetProcessor.kt:760-766`:
`csharpContracts = ordinaryContracts + csharpLegacy(rendered, ordinaryNames)`, and
`csharpLegacy` drops any entry point already in `ordinaryNames` (`ForwardAbiContract.kt:129`).
Today `ForwardAbiContract.csharp` returns `emptyList()` for `CirSealedClass` (`:103`), so the
sealed imports are collected from rendered text only. That stays correct after the migration
whether or not `csharp()` is taught to walk the subclass `CirDllImport` nodes: if it is, the
names move to the ordinary universe and the text collector skips them; if not, the text collector
still finds them, and the `[return: MarshalAs]` line is skipped by the "further attribute lines"
rule at `:119-124`. The `csharpLegacy` `require(signatures.size == 1)` (`:133-136`) fails the
build if the two ever disagree, so a mistake here is loud, not silent.

### Consumer API

Nothing moves for a consumer except what was broken:

```csharp
public sealed class Loaded : LoadState
{
    public Mood? Mood { get; }            // was: Kotlin compile error (17)
    public bool Refreshing { get; }       // now [return: MarshalAs(UnmanagedType.I1)] (19)
    public Owner? Owner { get; }          // one native call, throws on error (18, 20)
    public Guid Id { get; }               // was: SKIPPED_UNSUPPORTED_TYPE
    public TimeSpan Age { get; set; }     // var now has a setter
}
```

## Consequences

- **Export rename.** The nullable-primitive presence export changes from the legacy
  `${prefix}_get_x_has_value` (`SealedClassExports.kt:113`) to the planner's bare
  `${prefix}_get_x` with `${prefix}_get_x_value` unchanged (`ForwardPropertyPlanner.kt:244-245`,
  **verified**). Kotlin and C# are generated together so no consumer sees a mismatch; the
  ADR-054 `contractHash` is not an input here (forward, not reverse).
- **`var` properties on a sealed subclass gain setters**, under the same ADR-075 collection
  gate and ADR-105 `viaDiscriminator` gate as any class. Legacy always emitted `setter = null`
  (`CirClassTranslator.kt:1343`, **verified**).
- **Diagnostics change.** A type the planner cannot plan now reports
  `SKIPPED_UNSUPPORTED_PROPERTY` via `warnDroppedForwardProperties` (`NugetProcessor.kt:176`)
  instead of the legacy `SKIPPED_UNSUPPORTED_TYPE` with the line-37 hint. Lambda properties do
  not double-report: `recordDropped` returns early for `LEGACY_ROUTED_PROTOCOLS`
  (`ForwardPropertyPlanner.kt:449-455`, **verified**).
- **Six Tier 1 tests change spelling**: `Tier1SealedSubclassCrossNamespaceTest`,
  `Tier1SealedListPropertyTest` (asserts `Native_Get_refreshing(_handle, out _)` at `:76`,
  **verified**), `Tier1SealedCollectionPropertyTest`, `Tier1ThrowablePropertyTest`,
  `Tier1NestedSealedSubclassPositionTest`, `ForwardAbiLegacyRoutesTest` (`:23`, `:118` list
  `SEALED_CLASS`, which stays, but its expected import set shrinks).
- **Closes ROADMAP Phase 3 lines 26, 27, 28, 29** and the sealed half of line 37; line 51's
  orphan export disappears with the `else` branch. Line 52 (sealed-subclass methods) is
  untouched: this ADR is properties only.
- **Deletes** roughly 250 lines across the three legacy files; the Kotlin `sealedPropertyGetter`
  helper survives only for the lambda branch.

## Scope

- v1: every property type `ForwardPropertyPlanner.isPlannable` admits, get and set, on a sealed
  subclass (nested or sibling). `data object` subclasses keep `emptyList()`
  (`CirClassTranslator.kt:1083`).
- Residual legacy: lambda-typed properties (`KotlinFunc<...>`), which have no plan shape
  (`LAMBDA_PROPERTY` route, `ForwardAbiLegacyRoutes.kt:152`). They keep the `out _` swallow
  until lambda properties migrate for ordinary classes too.
- Deferred: sealed-subclass methods (ROADMAP line 52), `Flow` properties on a sealed subclass.

## Claims list

Verified by reading the cited lines (no build, no fixture; another agent held Gradle):
the four defects, the type-coverage gap, the planner/emitter/projection capabilities, the
`catalog`/`propertyFor`/`addForwardPropertyPlanExports`/`classProperty` entry points, the
`propertyNativeImports` requirement, the ABI double-count analysis, the recordDropped lambda
suppression, and the sealed-class exclusion from `classes`.

Inferred, must be checked by the implementer:

- `prop.isForwardPlannableMemberOf(subclass, superClass = null)` admits the properties the sealed
  base declares and the subclass inherits, matching today's `getAllProperties()` loop. If it
  instead filters inherited members, a base-declared `val` silently vanishes from every subclass.
  This is the one claim that produces silently wrong output if wrong; test it with a fixture
  whose sealed base declares an abstract `val`.
- `propertyNativeImports` lifting from `CirClass` to the sealed renderer compiles as sketched.
- A sibling (non-nested) subclass's `receiver = Handle(subQualifiedName)` spells
  `asStableRef<Sub>()` correctly on the Kotlin side; the legacy uses the same qualified name
  (`SealedClassExports.kt:71`), so this should hold.
- The Swift/ObjC prior-art sentence, from docs only.

## Post-implementation notes

Shipped as designed, with two corrections against the Proposed text above:

- Only **two** Tier 1 tests changed spelling, not the six the Claims list's "inferred" section did
  not itemise but the surrounding prose implied: the naming and shape changes were narrower in
  practice than the six-test estimate.
- `CirSealedRenderer.sealedSubclassBlock` needed a **setter arm** it never had before this
  migration; the renderer only ever rendered a getter import for a sealed subclass property, so
  the `var` case (`Note` below) required adding that arm rather than reusing an existing one.

The one claim flagged as load-bearing and unverified, `isForwardPlannableMemberOf(sub, null)`
admitting base-declared inherited properties, held: an inherited `val`/`var` declared on the
sealed base binds on every subclass, verified against the `test-library` fixture.

All four defects (ROADMAP lines 26-29) and the `isReferenceType` skip for `Instant`/`Duration`/
`Uuid` (ROADMAP line 37) and the orphan `Shape.Circle.listener` export (ROADMAP line 51) are
closed by this migration; see [FEATURES.md](../../FEATURES.md) and
[interfaces-abstract-sealed.md](../topics/interfaces-abstract-sealed.md) for the consumer-facing
shape. Sealed-subclass **methods** (ROADMAP line 52) remain out of scope, unaffected by this ADR.

### Amendment (2026-09-08): `data object` properties bind too

The migration above left one local skip list in place: `CirClassTranslator.kt` still emptied the
property list for a `data object` sealed subclass unconditionally
(`isDataObject` check, `:1086`), while the Kotlin side (`SealedClassExports.kt`) and the ADR-062
property plan both kept the property. That is a fourth, narrower copy of exactly the local skip
list this ADR's own Decision states "no local skip list decides that any more". A `data object`
overriding a base `abstract val`, or declaring its own `val`, produced an orphan Kotlin export
(`nestedshape_empty_get_*`) with nothing on the C# side to call it, which aborts
`ForwardAbiContract` and stops the whole `packNuget` run cold.

ROADMAP line 26 called this "harmless today (no fixture has such a property)". That severity claim
was false: it was a hard KSP abort, not a silent gap. The wrong label is exactly why no fixture
was built to catch it, which is exactly why a real consumer hit it first instead of CI (issue
#107). The lesson: a "harmless, no fixture exercises it" note on a ROADMAP line is a claim about
test coverage, not about severity, and should never stand in for actually checking what happens
when the code path runs.

Fixed by deleting the `isDataObject` suppression: a `data object` subclass now plans its properties
through the same catalog lookup a `data class` subclass uses. Fixture:
`NestedShapeSample.kt`'s `NestedShape.Empty` (a `data object`) gained `override val sides: Int` and
`val note: String`; `NestedShape.Circle` (the `data class` control) got `override val sides: Int? =
null` to prove the fix does not disturb an arm that already worked. Test:
`IntegrationTests/DataObjectSealedSubclassPropertyTests.cs`.

Still open, and adjacent rather than closed by this: the sealed **base**'s own `abstract val sides`
renders no C# member at all (`CirSealedClass` has no `properties` field), so every assertion above
has to reach through a concrete arm rather than the base type. See ROADMAP.md.
