# ADR-123: Collection elements on the Flow and StateFlow routes

## Status

Accepted

## Context

GitHub issue #127, found by consuming `main` from a real project:

```kotlin
class Hub {
  val items: StateFlow<Set<NodeId>> = MutableStateFlow(emptySet())

  fun visible(kinds: List<Kind>): StateFlow<List<NodeId>> = MutableStateFlow(emptyList())
}
```

```csharp
// wrong, both the flow property and the flow-returning method
public KotlinStateFlow<global::Demo.Kotlin.Collections.Set> Items { get; }
public KotlinStateFlow<global::Demo.Kotlin.Collections.List> Visible(IReadOnlyList<global::Demo.Kind> kinds)
```

```
Interop.cs(1176,62): error CS0234: The type or namespace name 'Kotlin' does not exist in the namespace 'Demo.Kotlin'
```

`packNuget` is green with this in it. The failure only shows when a consumer compiles the generated
`Interop.cs`, the same shape of miss as
[ADR-119](119-collection-returns-on-the-legacy-suspend-route.md) and
[ADR-122](122-handle-parameters-on-the-legacy-routes.md): the package publishes and every
consumer's build breaks.

**The gap is visible on one declaration.** In `visible` the `List<Kind>` *parameter* is already
right (`IReadOnlyList<global::Demo.Kind>`, ADR-114) while the `StateFlow<List<NodeId>>` *element*
on the same signature is not. Same Kotlin type constructor, one position bound and one not.

### The mechanical cause, per half

- **The speller. Verified in source.** `qualifiedElementCsType(type, context)`,
  `cir/CirTypeMapping.kt:225-243`, does three things: a `KOTLIN_TO_CSHARP_PARAM` lookup on the
  *simple* name, `nestedCsName()`, and `mapPackageToNamespace(...)`. For `kotlin.collections.List`
  the scalar table misses (`List` is not a key, `cir/CirTypeMapping.kt:39-53`), the declaration
  *is* a `KSClassDeclaration`, and so it falls through to the namespace mapping.
  `mapPackageToNamespace` (`cir/CirTypeMapping.kt:188-209`) has no notion of a Kotlin builtin
  package: `kotlin.collections` does not start with `com.example.demo`, so `relative` stays
  `kotlin.collections`, each segment is capitalised, and the result is `Demo.Kotlin.Collections`.
  Joined with `nestedCsName()` = `List`, that is the reported
  `global::Demo.Kotlin.Collections.List`. The type argument is dropped in the same expression,
  because the function never reads `type.arguments`. One defect, two symptoms: the function has no
  notion of "this type is generic", so it can neither qualify it correctly nor keep its argument.
- **Three call sites reach it on these routes. Verified in source.** The flow/StateFlow *property*
  (`cir/CirClassTranslator.kt:340-343`, used at `:408-410`), the flow/StateFlow *method return*
  (`:712-713`, used at `:793-795` and `:824`), and ADR-068's `suspend fun` returning `StateFlow`
  (`:1126`, used at `:1153`).
- **The runtime half is broken independently, and would stay broken if only the spelling were
  fixed. Verified in source.**
  - C#: every emission is read as `T value = NugetMarshal.FromHandle<T>(itemPtr);` inside the
    shared `KotlinFlowEnumerator<T>` (`cir/CirFlowRenderer.kt:108`), and `.Value` is
    `NugetMarshal.FromHandle<T>(_readValue())` (`cir/CirFlowRenderer.kt:213`). `FromHandle<T>`
    (`cir/CirMarshalRenderer.kt:129-132`) dispatches nullable-underlying, then primitives, then the
    ADR-094 `Factories` table via `Materialize<T>`, which has no collection branch. **Inferred (not
    run): the exact failure for a collection `T` is a `NotSupportedException` out of
    `Materialize<T>`**, read off those lines.
  - Kotlin: the emission boxes the value as-is, `NugetHandles.retain(value as Any)`
    (`exports/ClassExports.kt:502-504`, used by `buildFlowCollectBody` at `:530` and
    `buildFlowMethodCollectBody` at `:576`, and by the two `_value` bodies at `:603` and `:621`). A
    boxed `List<NodeId>` is *nearly* the shape `nuget_list_count`/`_get` read, exactly as ADR-119
    found for the suspend return, but a component that projects at the seam (a value class to its
    underlying, ADR-081; an enum to its ordinal, ADR-097) must go through
    `collectionResultProjection` or the C# per-element read is decoding the wrong thing.
- **The `MutableStateFlow` write seam is already closed. Verified in source.**
  `isMutableStateFlowElementSupported` (`cir/CirTypeMapping.kt:139-146`) returns `false` unless the
  element is a scalar or a `CLASS`/`OBJECT`. A `List` declaration is an `INTERFACE`, so a declared
  `MutableStateFlow<List<T>>` keeps the read-only `KotlinStateFlow<T>` mapping and needs no
  settable `.Value`. This ADR adds no write direction.
- **ADR-068's route has no per-member seam at all. Verified in source.**
  `nuget_stateflow_collect` / `nuget_stateflow_value` (`exports/SuspendStateFlowExports.kt`) are
  emitted **once per module**, keyed on an already-obtained `StateFlow<*>` handle, and box
  `value as Any` generically. There is nowhere to put a per-element projection on that route
  without giving every member its own copy of both exports.

### What the ordinary route does (the mechanism being ported)

**Verified in source.** The property route and the ADR-062 plan route already agree on one contract
for a Kotlin `List<T>`, and ADR-119 ported it to the suspend return:

1. Admission is `isBridgeableComponent()` (`forward/ForwardCallablePlanner.kt`), the ordinary
   route's own return-position rule.
2. The public C# type is `BridgeType.forwardPublicCsharpType()` (`forward/ForwardCsharpTypes.kt`),
   whose `Collection` arm is the `IReadOnlyList<T>` / `IReadOnlySet<T>` /
   `IReadOnlyDictionary<K, V>` contract.
3. The read is `legacyCollectionRead(handle, type)` (`forward/ForwardLegacyRouteCollections.kt`),
   i.e. ADR-099's `componentCollectionRead` at depth 0, which renders
   `NugetMarshal.ReadList`/`ReadSet`/`ReadMap`: the same `nuget_list_*` / `nuget_set_*` /
   `nuget_map_*` exports the property route reads through, behind a `finally` that disposes the
   container handle (ADR-120).
4. The Kotlin side pins one `StableRef` of the per-element-projected result,
   `collectionResultProjection` (`forward/ForwardKotlinPlanEmitter.kt:613`).

ADR-114 is the parameter twin already wired into these same flow builders, which is why the issue
sees the parameter right and the element wrong on one signature. ADR-119 is the return-side twin
one route over. This ADR is the third application of that shape, at the flow *element* position.

## Alternatives Considered

### 1. Classify the flow element, marshal a supported collection, refuse every other generic element by name (chosen)

A new `ForwardLegacyFlowElementShape` (`Plain` / `Marshalled` / `Refused`) beside ADR-114's
parameter shape and ADR-119's return shape:

- an element with no type arguments is `Plain`, the shipped spelling, so every scalar, string,
  enum, object and sealed element renders byte for byte as before;
- a `List`/`Set`/`Map` whose components pass `isBridgeableComponent()` is `Marshalled`: spelled
  `forwardPublicCsharpType()`, read `legacyCollectionRead(...)`, pinned
  `collectionResultProjection(...)`;
- everything else generic is `Refused` by name and the member skips on both halves.

Pros: one contract for a Kotlin `List<T>` in the whole generator, now on four routes; no new helper
and no new wire shape; the refusal arm closes the whole "generic element spelled as a bare name"
family (`Pair`, a nullable collection, a user generic, a nested `Flow`) in one gate rather than one
CS-number at a time.

Cons: an element that used to render as broken C# now vanishes from the surface, named. That is the
issue's own requirement 5, and a present-and-broken member is the thing this ADR exists to stop.

### 2. Fix `qualifiedElementCsType` alone

Teach the speller to recognise `kotlin.collections.*` and render `IReadOnlyList<...>` recursively.
One file, and it satisfies requirements 1, 2 and 4.

Rejected as a half fix: requirement 3 stays broken at runtime, because `FromHandle<T>` has no
collection branch and the Kotlin emission still boxes an unprojected value-class element. The
result would be a signature a consumer can compile and cannot call, which is how this defect
family ships in the first place. A *defensive* change to that function is still wanted, and is part
of the decision below.

### 3. Refuse every generic flow element

Requirement 5 only, roughly three files. Rejected on ADR-119's terms: the precedent is unusually
clean (both spellings on one declaration, the helpers already emitted and already used), so binding
it costs one classification and three expressions, and refusing it would hand a real project a
named hole the property route already answers.

### 4. Spell the element `object`, `IntPtr` or a non-generic `IEnumerable`

Rejected by the issue in as many words. Three routes already spell a Kotlin `List<T>` as
`IReadOnlyList<T>`; a fourth spelling for the same Kotlin type would be worse than the current
error.

### 5. Give ADR-068's suspend-StateFlow route its own per-member exports

The shared `nuget_stateflow_collect` / `nuget_stateflow_value` pair would become one pair per
member so a collection element could be projected there too.

Rejected for this ADR: it multiplies a module-wide export pair by every suspend-StateFlow member to
serve a shape no issue has asked for, and ADR-068 chose the shared pair deliberately. That route
instead **refuses** a non-`Plain` element, which is strictly better than the
`Task<KotlinStateFlow<global::Ns.Kotlin.Collections.List>>` it renders today.

### 6. Move the legacy Flow routes onto the ADR-062 callable plan

ADR-062's stated direction, rejected on the same terms ADR-118, ADR-119 and ADR-122 rejected it:
the plan has no subscription shape, no scope receiver and no callback triple, and none of that is
needed to close the issue.

## Decision

Alternative 1, plus the defensive guard from alternative 2.

### Consumer-facing C# API

```csharp
public class NodeHub : IDisposable, IAsyncDisposable, INugetHandle
{
    public KotlinStateFlow<IReadOnlySet<global::TestLibrary.Issue127.NodeId>> Items { get; }
    public KotlinStateFlow<IReadOnlyList<string>> Plain { get; }
    public KotlinStateFlow<IReadOnlyDictionary<string, int>> Counts { get; }
    public KotlinFlow<IReadOnlyList<global::TestLibrary.Issue127.Kind>> Ticks { get; }
    public KotlinStateFlow<IReadOnlyList<global::TestLibrary.Issue127.NodeId>> Visible(
        IReadOnlyList<global::TestLibrary.Issue127.Kind> kinds);
    // Paired (StateFlow<Pair<String, Int>>) and Maybe (StateFlow<List<String>?>) are absent, named.
}
```

### Classification (`forward/ForwardLegacyRouteCollections.kt`)

```kotlin
internal sealed interface ForwardLegacyFlowElementShape {
  data object Plain : ForwardLegacyFlowElementShape
  data class Marshalled(val type: BridgeType.Collection) : ForwardLegacyFlowElementShape
  data class Refused(val description: String) : ForwardLegacyFlowElementShape
}
```

`legacyFlowElementShape(type)` sits beside `legacyParameterShape` and `legacyReturnShape` and reads
the same way:

- no type arguments: `Plain`. Every shipped flow element keeps its exact rendering;
- a `Collection` whose `isBridgeableComponent()` holds: `Marshalled`. That is the ordinary route's
  own return-position admission, so the two routes cannot disagree about which element types cross;
- anything else, a nullable collection included: `Refused`, quoting the author's own spelling
  through the same `legacyDescription()` ADR-114 uses.

`legacyFlowElement(type)` peels the element off a `Flow`/`StateFlow` type (and returns null for
anything else), `legacyRefusedFlowElement(type)` is the null-or-description form both halves filter
on, and `legacyFlowElementKinds(type)` is the helper-gate contribution.

`legacyReturnShape` gains one arm: a `StateFlow` return whose element is not `Plain` is now
`Refused` rather than `Plain`, which is alternative 5's refusal. `legacyRefusedReturn` widens from
`suspend`-only to every legacy async route, so a flow-returning method with a refused element is
filtered by the same single call the suspend route already used.

### C# half (`cir/CirClassTranslator.kt`, `cir/CirFlowRenderer.kt`, `cir/CirModel.kt`)

A `Marshalled` element is spelled `shape.type.forwardPublicCsharpType()` instead of
`qualifiedElementCsType(...)` at the property site and the method site, and its kinds are tracked so
the helper exports are emitted. A `Refused` element drops the member.

The read is a **per-member delegate**, because `KotlinFlowEnumerator<T>` and `KotlinStateFlow<T>`
are shared by every member in the file and cannot be specialised per element:

```csharp
public class KotlinFlow<T> : IAsyncEnumerable<T>
{
    internal readonly Func<IntPtr, T> _read;

    internal KotlinFlow(NugetFlowCollectDelegate startCollect, Func<IntPtr, T>? read = null)
    {
        _startCollect = startCollect;
        _read = read ?? NugetMarshal.FromHandle<T>;
    }
}
```

`KotlinFlowEnumerator<T>` takes the same trailing optional parameter and reads
`T value = _read(itemPtr);`; `KotlinStateFlow<T>` forwards it to its base and reads
`public T Value => _read(_readValue());`. The parameter is trailing, optional and on `internal`
constructors, so this is source-compatible for consumers and **every existing member's generated
text is byte-identical**: a member with a non-collection element passes no lambda and gets
`NugetMarshal.FromHandle<T>` exactly as before. A `Marshalled` element passes
`read: static h => <legacyCollectionRead("h", type)>` as a named argument, carried to the renderers
on a new `CirMethod.flowElementRead` field and inlined into the property getter text.

`KotlinMutableStateFlow<T>` is untouched: its element can never be a collection (the write seam
above rejects an `INTERFACE` declaration), so it keeps its four-parameter constructor and forwards
the default.

### Kotlin half (`exports/ClassExports.kt`)

`itemBoxExpr` and the two `_value` bodies gain the projection: a `Marshalled` element emits
`NugetHandles.retain(collectionResultProjection("value", type) as Any)` instead of
`NugetHandles.retain(value as Any)`. `collectionResultProjection` returns its input unchanged when
no component needs projection, so a `List<String>` element's emitted Kotlin is byte-identical to
today's and a `Set<NodeId>` element becomes
`value.mapTo(mutableSetOf()) { v1 -> v1.value }`. The flow-property loop and the flow-method loop
both filter a refused element out, beside ADR-114's parameter filter.

### Diagnostics (`NugetProcessor.kt`)

`warnRefusedLegacyRouteMembers` gains the flow-element walk. A flow *property* is named
`SKIPPED_UNSUPPORTED_PROPERTY` and a flow-returning *method* (or a suspend member returning a
`StateFlow`) is named `SKIPPED_UNSUPPORTED_RETURN`, both existing kinds
(`forward/ForwardDiagnostic.kt`). One skip per member, whichever gate it hits first, unchanged from
ADR-119.

```
w: [nuget:SKIPPED_UNSUPPORTED_PROPERTY] Skipping NodeHub.paired: a Flow or StateFlow element can be a List/Set/Map, but not the generic type Pair<String, Int>. make the element a non-nullable List/Set/Map, or a non-generic type
```

### Helper gate (`NugetProcessor.kt`, `legacyRouteCollectionKinds`)

The ADR-114/119 disjunct gains the flow element's kinds, for both a flow property and a
flow-returning method. Without it the generated C# calls `nuget_set_count` against a native library
that never exported it, and the symptom is an `EntryPointNotFoundException` at first read rather
than a build error. ADR-119's implementation notes record that exact miss for sealed arms; this is
the same miss one position over, and it is real here because no declaration scan reads a flow
element (`classesHaveSets` walks property types, and a property's type is `StateFlow`, not `Set`).

### The defensive guard (`cir/CirTypeMapping.kt`)

`qualifiedElementCsType` gains a `check(...)` that no `kotlin.*` / `kotlinx.*` package reaches
`mapPackageToNamespace`, naming the type and this ADR. Every caller must gate first. That is what
turns the issue's requirement 4 from "a test we remember to keep" into a shape the generator cannot
emit: a Kotlin builtin arriving at the user-type speller is a defect at *every* call site, not only
the flow one, and the value-class underlying site (`cir/CirClassTranslator.kt:2019`) would hit it
the day someone writes `value class Tags(val v: List<String>)`.

### Scope

Bound:

- `List`, `MutableList`, `Set`, `MutableSet`, `Map`, `MutableMap` as the element of a `Flow` or
  `StateFlow`, on a property and on a method return.
- Components: whatever `isBridgeableComponent()` admits, per element through the shared
  `collectionResultProjection` / `componentCollectionRead` pair (enums, value classes, nested
  collections, nullable components).
- A collection parameter and a collection element on the same declaration.

Refused, named:

- A nullable collection element (`StateFlow<List<String>?>`), keeping ADR-114's and ADR-119's
  identical deferral. One rule across four routes.
- A collection of a sealed base (`StateFlow<List<Shape>>`): the classifier mints
  `SpecializedProtocol` for it and only the plan routes unwrap that (ADR-105).
- Every other generic element: `Pair`, `Result<T>`, a user generic, a nested `Flow`. All of these
  previously rendered a bare unqualified generic name, CS0305 rather than CS0234, and the issue
  does not name them; they fall out of the same gate.
- Any collection element on ADR-068's `suspend fun` returning `StateFlow` (alternative 5).

Not touched:

- The settable `.Value` write seam. A `MutableStateFlow<List<T>>` keeps its read-only mapping.
- A bare enum element (`StateFlow<Mood>`), which is admitted and still broken at runtime because
  `FromHandle<T>` has no enum branch. This design routes a *collection* element around
  `FromHandle<T>` through the per-member delegate rather than adding a branch to it, so
  `docs/backlog/fromhandle-no-enum-branch.md` stays open and untouched.
- `docs/backlog/two-divergent-public-csharp-type-spellers.md`. This adds a third consumer of
  `forwardPublicCsharpType()` and leaves `ForwardCirPropertyProjection`'s private copy alone, so it
  narrows the divergence in spirit and closes nothing. Said here rather than silently widening it.

## Consequences

- A `Flow`/`StateFlow` of a supported collection binds with the same C# spelling and the same
  runtime helpers the property, plan and suspend routes use, on both owners. The consumer-side
  proof is `IntegrationTests/Issue127Tests.cs`, which reads every element of every emission and
  compiles under `GeneratedBindingsCheck`'s `TreatWarningsAsErrors`.
- Breaking for nobody who was compiling. Every element this ADR refuses, and every element it
  binds, previously rendered C# that failed the consumer's build.
- The flow ABI's C# half gains a per-member read delegate. It is optional, trailing and on
  `internal` constructors, and the shipped `cat` / `catcam` flow members' generated text does not
  move.
- The leak surface is the reason for `LeakTests/LiveHandleTests.cs` row 8d. Each emission and each
  `.Value` read mints a fresh `StableRef` for the container plus one box per element, and none of
  it is released by the flow enumerator: the container handle goes in `ReadList`/`ReadSet`'s
  `finally` and the element boxes are owned by whatever the read returns. A read lambda wired wrong
  leaks one handle per emission, which shows up nowhere else.
- ROADMAP: the Phase 6 line about generic type arguments on the legacy routes narrows a third time
  (ADR-114, ADR-119, now ADR-123).

### Inferred claims

Everything else is verified by source reading, with the file and line inline.

1. **`FromHandle<T>` fails a collection `T` with `NotSupportedException` from `Materialize<T>`.**
   Read off `cir/CirMarshalRenderer.kt:129-132`; not run, because the shipped generator never
   produced a compiling collection-element member to run it against.
