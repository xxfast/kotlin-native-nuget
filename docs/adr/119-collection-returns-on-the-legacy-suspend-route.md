# ADR-119: Collection returns on the legacy suspend route

## Status

Accepted

## Context

GitHub issue #122, found by consuming `main` from a real project, newly reachable since
[ADR-118](118-suspend-route-sealed-arm-owners-and-overload-numbering.md) started exporting a sealed
arm's `suspend` members:

```kotlin
sealed class Assignment

data class Member(val id: Int)

data class Existing(val members: List<Member>) : Assignment() {
  suspend fun fetch(limit: Int, offset: Int): List<Member> = members
}
```

```csharp
// correct, the property route on this same class
public IReadOnlyList<global::Ns.Member> Members

// wrong, the suspend method route on this same class
public Task<List> FetchAsync(int limit, int offset)
{
    var tcs = new TaskCompletionSource<List>(TaskCreationOptions.RunContinuationsAsynchronously);
```

```
Interop.cs(12626,21): error CS0305: Using the generic type 'List<T>' requires 1 type arguments
```

`packNuget` is green with this in it. The failure only shows when a consumer compiles the generated
`Interop.cs`, which is the worst place for it: the package publishes, and every consumer's build
breaks.

### The mechanical cause, per half

**Verified by reading, and by the red Tier 1 cell** (`Tier1LegacySuspendCollectionReturnTest`,
which rendered `Task<List?>` and `Task<Pair>` before the fix):

- **C# half.** Both suspend projections read the return as a simple name and paste it through
  `KOTLIN_TO_CSHARP_PARAM`: `suspendMembers` in `cir/CirClassTranslator.kt` (class and sealed arm)
  and `translateSuspendFunction` in `cir/CirFunctionTranslator.kt` (top level). `List<Member>`
  resolves to the simple name `List`, misses the 13-entry table, and lands verbatim in
  `Task<List>`, `TaskCompletionSource<List>` and `new List(resultPtr)`. There is no bridgeable-subset
  check on this route at all, so the same fall-through spelled `Task<Pair>`, `Task<Result>`
  (ROADMAP Phase 6, the ADR-108 probe cell) and `Task<Flow>` (ROADMAP Phase 6).
- **Kotlin half.** `exports/SuspendFunctionExports.kt` pins whatever the member returns:
  `StableRef.create(result)`. For `List<Member>` that compiles, and it happens to be the shape
  `nuget_list_get` reads (`asStableRef<List<*>>()`), so the Kotlin side was *nearly* right by
  accident. It is not right for a component that projects at the seam: a `List<Mood>` must leave as
  its int ordinals ([ADR-097](097-enum-collection-components.md)) and a `List<ChartId>` as its
  underlyings ([ADR-081](081-value-class-collection-components.md)), or the C# `FromHandle<int>`
  read throws.

### What the ordinary route does (the mechanism being ported)

**Verified by reading.** The plan-driven synchronous route and the property route already agree on
one contract for a `List<T>` result:

1. The planner admits a collection result when `isBridgeableComponent()` holds
   (`forward/ForwardCallablePlanner.kt:2275`).
2. The Kotlin export pins the result as one `StableRef`, projecting per element only when a
   component needs it (`collectionResultProjection`, `forward/ForwardKotlinPlanEmitter.kt`).
3. The C# side reads it back through `NugetListNative.Count`/`Get`/`Dispose` with
   `collectionComponentRead` per element, spelling the public type `IReadOnlyList<T>` through
   `forwardPublicCsharpType()` / the property projection's `csharpType()`, both of which read the
   classifier's already-qualified `ObjectHandle.csharpType` (`global::Ns.Member`).
4. A nested component reads through the ADR-099 `NugetMarshal.ReadList`/`ReadSet`/`ReadMap`
   helpers, which wrap the same three native calls in a `finally` that disposes the handle
   (`cir/CirMarshalRenderer.kt:407-449`).

[ADR-114](114-collection-parameters-on-legacy-flow-and-suspend-routes.md) ported exactly this
contract to the *parameter* side of the same legacy route, with a classify-then-marshal-or-refuse
shape (`ForwardLegacyParameterShape`). This ADR is its return-side twin.

## Alternatives Considered

### 1. Marshal a collection return through the ordinary route's wire container, refuse every other generic return by name (chosen)

The suspend route classifies its return the way ADR-114 classifies a parameter:

- a supported `List`/`Set`/`Map` (and mutable variants) is **marshalled**: Kotlin pins the
  per-element-projected result exactly as the ordinary route does, and C# reads the awaited handle
  through `NugetMarshal.ReadList<T>` (and kin), spelling the public type through
  `forwardPublicCsharpType()`;
- every other generic return is **refused** by name with a `SKIPPED_UNSUPPORTED_RETURN`.

Pros: one contract for a Kotlin `List<T>` in the whole generator; no new helper, no new wire shape;
the refusal arm closes the whole "unmapped simple name" family (`Pair`, `Result`, `Flow`, a user
generic) in the same gate rather than one at a time.

Cons: a generic return that used to render as broken C# now vanishes from the surface, named. That
is the issue's own requirement 3, and a present-and-broken member is the thing this ADR exists to
stop.

### 2. Refuse every generic suspend return, `List` included

Requirement 3 alone. Rejected: the precedent is unusually clean (both spellings on the same class,
the helpers already emitted and already used), so binding it costs one classification and two
expressions, and refusing it would hand a real project a named hole where the property route
already answers.

### 3. Spell the return as `Task<object>`, `Task<IntPtr>` or a non-generic `IEnumerable`

Rejected by the issue itself: `IReadOnlyList<T>` is the established C# contract for a Kotlin
`List<T>`, and a second spelling for the same Kotlin type would be worse than the current error.

### 4. Move the suspend route onto the callable plan

ADR-062's stated direction, rejected here on the same terms as ADR-118 rejected it: the plan has no
async result shape, no scope receiver and no completion callback, and none of that is needed to
close the issue.

## Decision

Alternative 1.

### Consumer-facing C# API

```csharp
public sealed class Existing : Assignment, IAsyncDisposable
{
    public IReadOnlyList<global::TestLibrary.Issue122.Member> Members { get; }          // property route, unchanged
    public Task<IReadOnlyList<global::TestLibrary.Issue122.Member>> FetchAsync(
        int limit, int offset, CancellationToken cancellationToken = default);            // suspend route, now agrees
}

public sealed class Headcount : IDisposable, IAsyncDisposable, INugetHandle
{
    public Task<IReadOnlyList<string>> TagsAsync(CancellationToken cancellationToken = default);
    public Task<IReadOnlySet<int>> IdsAsync(CancellationToken cancellationToken = default);
    public Task<IReadOnlyDictionary<string, int>> AgesAsync(CancellationToken cancellationToken = default);
    public Task<IReadOnlyList<Temper>> TempersAsync(CancellationToken cancellationToken = default);
    // PairedAsync (Pair<String, Int>) and MaybeAsync (List<String>?) are absent, named.
}

public static class AssignmentSample
{
    public static Task<IReadOnlyList<string>> EveryoneAsync(string prefix, CancellationToken cancellationToken = default);
}
```

### Classification (`forward/ForwardLegacyRouteCollections.kt`)

`ForwardLegacyReturnShape` (`Plain` / `Marshalled(BridgeType.Collection)` / `Refused(description)`)
and `legacyReturnShape(type)`, applied to a `suspend` member's return only:

- no type arguments: `Plain`, the shipped spelling, so every scalar, string, enum and object return
  renders exactly as before;
- a `StateFlow`/`MutableStateFlow` return: `Plain`, because ADR-068 peels it into its own bucket
  before the plain-async path sees it;
- a `Collection` whose `isBridgeableComponent()` holds: `Marshalled`. This is the planner's own
  return-position admission, so the two routes cannot disagree about which element types cross;
- anything else, a nullable collection included: `Refused`, quoting the author's own spelling
  (`Pair<String, Int>`, `List<String>?`) through the same `legacyDescription()` ADR-114 uses.

`legacyRefusedReturn(func)` is the null-or-description form both halves filter on, and
`legacyReturnCollectionKinds(func)` was the helper-gate contribution (superseded by ADR-127,
deleted 2026-10-03).

### Kotlin half (`exports/SuspendFunctionExports.kt`)

Both builders (top level, and class/sealed arm) filter a refused return out, beside the ADR-114
parameter filter. A marshalled return changes one expression: the pinned value becomes
`collectionResultProjection("result", type)` instead of bare `result`, so a value-class or enum
component leaves as its wire value and everything else is boxed as-is, byte for byte the ordinary
route's result. The `_async` C symbol, the callback protocol and the nullable guard are untouched.

### C# half (`cir/CirClassTranslator.kt`, `cir/CirFunctionTranslator.kt`, `cir/CirConcurrencyRenderer.kt`)

Both projections filter a refused return out (the class projection at the same `filteredMethods`
gate ADR-114 uses, the sealed projection in its own declared-only filter, the top-level one at
entry). A marshalled return sets `asyncReturnType = type.forwardPublicCsharpType()` and a new
`CirMethod.asyncResultRead`, the expression `legacyCollectionRead("resultPtr", type)` renders:

```csharp
t.SetResult(NugetMarshal.ReadList<global::TestLibrary.Issue122.Member>(resultPtr,
    static h1 => NugetMarshal.FromHandle<global::TestLibrary.Issue122.Member>(h1)).AsReadOnly());
```

`legacyCollectionRead` is ADR-099's `componentCollectionRead` at depth 0, made `internal` for it.
The `Read*` helpers are chosen over the ordinary route's inline `Count`/`Get` loop for two reasons:
the awaited handle arrives inside the completion closure, where the inline loop's fixed local names
(`listHandle`, `count`, `result`, `i`) would collide with the closure's own, and the helper's
`finally` is exactly the leak guard that position needs. They are the same `nuget_list_*` /
`nuget_set_*` / `nuget_map_*` exports the property route reads through (requirement 2).

`renderAsyncMethod` gains one arm, `method.asyncResultRead != null -> t.SetResult(<read>)`, placed
before the primitive and nullable arms so a collection never falls through to `new T(resultPtr)`.

### Diagnostics (`NugetProcessor.kt`)

`warnRefusedLegacyRouteParameters` becomes `warnRefusedLegacyRouteMembers`: the same three walks
(classes, sealed arms declared-only, top-level suspend functions) now name a refused return as
`SKIPPED_UNSUPPORTED_RETURN` (existing kind). One skip per member: a member with both a refused
parameter and a refused return is named for the parameter only.

```
w: [nuget:SKIPPED_UNSUPPORTED_RETURN] Skipping Headcount.paired: a suspend member can return a List/Set/Map, but not the generic type Pair<String, Int>. Return a non-nullable List/Set/Map, or a non-generic type.
```

### Helper gate (`NugetProcessor.kt`, `legacyRouteCollectionKinds`)

The ADR-114 disjunct gains the return kinds, and, found by this feature's Tier 1 cell, a walk it
never had: a sealed arm's declared suspend members. ADR-118 put those on the legacy route without
teaching the `needs*Support` gates about them, so a module whose only collection is a sealed arm's
suspend return (or parameter) would have emitted a C# call to `nuget_list_count` against a native
library that never exported it. The issue's own fixture masks this, because its arm also declares a
`List` property, which is why the Tier 1 cell for the gate uses an arm with only the suspend member.

### Scope

Supported:

- `List`, `MutableList`, `Set`, `MutableSet`, `Map`, `MutableMap` at the return position of a
  `suspend` member on a class, a sealed arm, and at top level.
- Components: whatever `isBridgeableComponent()` admits for the ordinary route's return, per element
  through the shared `collectionResultProjection` / `collectionComponentRead` pair (enums, value
  classes, nested collections, nullable components).

Refused, named `SKIPPED_UNSUPPORTED_RETURN`:

- A nullable collection return (`List<T>?`), mirroring ADR-114's nullable-parameter deferral. The
  wire could carry it (the null result pointer already means null on this route); it is refused so
  the two ADRs keep one rule until nullable threading on the legacy routes is done once.
- A collection of a sealed base (`List<Shape>`): the classifier mints `SpecializedProtocol` for it
  and only the plan routes unwrap that (ADR-105). Refused rather than half-bound.
- Every other generic return: `Pair`, `Result<T>` (closes the ADR-108 probe cell, which had been
  `@XFail` since 2026-09-07), a user generic, and `Flow<T>` (previously rendered `Task<Flow>`, ROADMAP
  Phase 6 keeps the mapping itself).

Not touched:

- The non-generic object return on this route still spells a nested sealed arm by simple name
  (ROADMAP Phase 3). Different defect, same file.
- The Kotlin `limit: Int?` parameter nullability the issue mentions and deliberately excludes.

## Consequences

- A `suspend fun` returning a supported collection binds with the same C# spelling and the same
  runtime helpers the property route uses, on all three owners. The consumer-side proof is
  `IntegrationTests/Issue122Tests.cs`, which compares `Existing.Members` and
  `Existing.FetchAsync` element for element and compiles under `GeneratedBindingsCheck`'s
  `TreatWarningsAsErrors` (requirement 4).
- Breaking for nobody: every previously *compiling* suspend shape is untouched, and every shape this
  ADR refuses was emitting non-compiling C#.
- ROADMAP: the `suspend fun load(): Result<T>` item (Phase 6) closes as a named skip. The
  `hasSuspendMethods` no-refusal-check item and the `Task<Flow>` item are re-worded to include this
  ADR's refusal.
- Amended 2026-09-10: [ADR-123](123-collection-elements-on-the-flow-routes.md) applies this same
  classify-then-marshal-or-refuse shape to the third route, the `Flow`/`StateFlow` *element*
  position, reusing `legacyCollectionRead` and `collectionResultProjection` verbatim.

## Amendment (2026-09-28): the legacy suspend route now reads a refusal instead of spelling the type

Found while closing ROADMAP Phase 4's top-level-suspend-reachability item, alongside
[ADR-066](066-forward-export-reachability-closure.md)'s 2026-09-28 amendment (the closure's own
input was missing top-level `suspend fun`): even once the closure records that a type is out of
scope, `legacyReturnShape`'s `Plain` fall-through never consulted that record, so a refused type
(closure-refused or genuinely unbridgeable) was still spelled bare and completed with
`new T(resultPtr, out _)` on both the top-level and class/sealed-arm suspend halves, an undeclared
C# type. Fixed:

- **Unbridgeable return, refused on both halves.** Before the `Plain` fall-through, `legacyReturnShape`
  now classifies the (nullable-unwrapped) return; a `BridgeType.Unsupported` result is `Refused`,
  named `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` (carrying the closure's `admit(...)` hint) when the
  refusal is a dependency-scope one, and the existing generic-kind wording otherwise. This applies
  identically to a top-level, class, and sealed-arm suspend return; the ROADMAP line this closes had
  called the class-level case "already fine" (reached through `getAllFunctions()`), which held only
  for admission, not for this route reading the admission result.
- **Suspend parameters get the same dependency wording.** A refused suspend parameter that is
  `Unsupported` for a dependency-scope reason now also reads `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`
  with the `admit(...)` hint, instead of the generic `SKIPPED_UNSUPPORTED_INPUT` naming "not a
  class/object/sealed-type handle" for a type that in fact is one, just out of scope. An in-scope
  dependency parameter now binds normally (`SqueakOfAsync(Mousetoy)`).
- **New return shapes**, chosen ahead of the `Plain` fall-through:
  - `Handle`: an admitted class/object handle, spelled fully qualified
    (`Task<global::…Mousetoy>`, `new global::…Mousetoy(resultPtr, out _)`) rather than the bare
    simple name every existing suspend class return used before this amendment. This is the same
    type, only respelled; every shipped suspend-class-return fixture now emits `global::…`.
  - `ValueClass`: chosen only when the classified type has both a `NugetBox` and a `NugetUnbox`
    (one shared predicate the planner and this route both call, so they cannot disagree). Completes
    with `global::Ns.T.NugetUnbox(resultPtr)`, null-guarded when nullable. A value class without
    that pair still falls through to `Refused`, by name; this is where
    [ADR-154](154-forward-dependency-type-admission.md)'s value-class gate now fires on the suspend
    route too.
  - `Enum`: boxed by ordinal on the Kotlin side (unchanged), read on the C# side with
    `NugetMarshal.FromHandle<int>(resultPtr)` and cast to the fully qualified enum type, null-guarded
    when nullable. Before this amendment an enum suspend return did not compile at all
    (`new Mood(resultPtr, out _)`, a record struct with no such constructor); this covers a
    dependency enum too.
- **A top-level suspend extension has no route**, and is now a named `SKIPPED_UNSUPPORTED_COMBINATION`
  skip instead of silently dropped with no diagnostic at all.

An out-of-scope `Flow<Dep>` element (return or property) folds into the same fix and gets the same
named `admit(...)` skip.

## Implementation notes (2026-09-09)

- The ADR-108 probe cell `suspend method returning Result either binds correctly or skips named`
  passed unexpectedly the moment the refusal landed, and the `@XFail` extension failed the build
  for it, as designed. The marker is removed; the cell now pins the "skips named" arm.
- `componentCollectionRead` (`forward/ForwardCirCollectionComponents.kt`) is `internal` with
  `depth = 0` defaulted. Its top-level output names its lambda `h1`, the nesting-level-1 spelling,
  which is harmless and left as is rather than threading a depth-minus-one through the helper.
- Fixture names: `Roster` and `Mood` were taken by the `clinic` and `cat` packages, and export
  prefixes are unqualified, so the fixture uses `Headcount` and `Temper`. The consumer test reaches
  the arm through an `AssignmentFactory` (a sealed arm has no public C# constructor, the
  `JobFactory` precedent), and the top-level `everyone` takes a `String`, because an object-typed
  parameter on the legacy suspend route still renders `IntPtr` (ROADMAP Phase 6), which the first
  verify run confirmed with `CS1503: cannot convert from Headcount to nint`.

## Amendment (2026-09-29): nullable collection returns and sealed-base components

The two shapes the Scope section refused "until nullable threading on the legacy routes is done
once" and "rather than half-bound" now bind, because neither needs a new wire:

- **A nullable collection return** (`suspend fun maybe(): List<String>?`, and `Set`/`Map`/mutable
  variants) is `Marshalled` with `nullable = true`, declared `Task<IReadOnlyList<string>?>` and
  completed with `resultPtr == IntPtr.Zero ? null : NugetMarshal.ReadList<string>(...)`. The Kotlin
  half was already right: `resultRefExpression` pins `if (result == null) null else
  NugetHandles.retain(<projection>)` for any nullable return (issue #108). This decouples the return
  side from ADR-114's nullable-*parameter* deferral, which stands unchanged: the two positions share
  no wire. A `typealias` to a nullable collection binds the same way.
- **A collection of a sealed base** (`suspend fun sketch(): List<Shape>`) is classified through
  ADR-105's `sealedAsHandle()` before the `isBridgeableComponent()` admission, as the plan routes
  classify it. The element becomes `ObjectHandle(viaDiscriminator = true)` and C# reads each element
  with `NugetMarshal.FromHandle<global::Ns.Shape>(h1)`, which resolves through the sealed base's
  `Factories` entry to `Shape.FromHandle`. An ineligible sealed type stays refused, named.

Both bind on a class, a sealed arm or base, and at top level. The Flow element position (ADR-123)
gains the sealed half through the same one-token change (`Flow<List<Shape>>` is
`KotlinFlow<IReadOnlyList<Shape>>`); `Flow<List<T>?>` stays refused. Still refused, named
`SKIPPED_UNSUPPORTED_RETURN`: `Pair`, `Result<T>`, a user generic, `Flow<T>`.

Leak evidence: `LeakTests/LiveHandleTests.cs` rows 9p
(`Suspend_ReturningAListOfTheSealedBase_ReturnsToBaseline`) and 9q
(`Suspend_ReturningANullableCollection_ReturnsToBaseline`).
