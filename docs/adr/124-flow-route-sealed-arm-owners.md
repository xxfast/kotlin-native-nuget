# ADR-124: Flow route: sealed-arm owners

## Status

Accepted

## Context

ROADMAP line 39's `Flow` half, and issue
[#129](https://github.com/xxfast/kotlin-native-nuget/issues/129). A `Flow<T>` or `StateFlow<T>`
declared on a **sealed subclass** does not bind. The method form is named:

```
[nuget:SKIPPED_UNSUPPORTED_COMBINATION] Skipping Job.Watching.labels: it is a FLOW_PROTOCOL member
of a sealed subclass, which has no route yet (ADR-116)
```

The property form is not named at all: it is dropped in silence (see item 4 below).

Both halves of the intersection already work separately. `label()` on the same arm exports since
[ADR-116](116-sealed-subclass-methods-on-the-callable-plan.md), so a sealed arm is a valid owner of
a member route; `StateFlow<Int>` on an *ordinary* class binds as `KotlinStateFlow<int>` since
[ADR-065](065-stateflow-mapping.md), so the flow route exists. Only their intersection is missing.

[ADR-118](118-suspend-route-sealed-arm-owners-and-overload-numbering.md) did the identical re-key
for the **suspend** route one issue earlier. This ADR is its mirror, site for site: it is not a
migration of the flow route onto the ADR-062 callable plan (the plan has no collect protocol, no
scope receiver and no per-item callback), it re-keys the existing specialized legacy route so a
sealed arm is a valid owner on both halves.

### Where the pieces are today

All **Verified by reading** on branch `ir/flow-route-sealed-arms` at `af3c862`.

| Concern | Site | What it does now |
|---|---|---|
| Planner post-process | `forward/ForwardCallablePlanner.kt:1064-1080` | rewrites every `droppedFromCSharp = false` skip except `ABSTRACT` and (ADR-118) `SUSPEND` into `SEALED_SUBCLASS_UNROUTED`; a flow-returning arm method is skipped `FLOW_PROTOCOL` (`:2860-2861`, off the `SpecializedProtocol` names `"flow "` / `"state flow "`) and so is rewritten |
| Kotlin flow property export | `exports/ClassExports.kt:116-224`, inline in `addClassExports`'s `properties.forEach` | `_get_x_collect`, `_get_x_value`, `_get_x_has_value`, `_set_x_value`, under `prefix = cls.simpleName.lowercase()` (`:65`) |
| Kotlin flow method export | `exports/ClassExports.kt:292-443`, inline in `addClassExports` | `${prefix}_${cname}_collect` and the `_value` / `_has_value` / `_set_value` siblings, `cname` carrying the planner's overload suffix (`:295`) |
| Kotlin sealed exports | `exports/SealedClassExports.kt:68-113` | the arm's property loop `continue`s on any unplanned non-lambda property (`:93`); the method half is `callableCatalog.classMethods(subQualifiedName)` only (`:111`), which holds no flow member |
| Property planner silence | `forward/ForwardPropertyPlanner.kt:498-509`, list at `:778-779` | `recordDropped` returns early when the type is a `SpecializedProtocol` in `LEGACY_ROUTED_PROTOCOLS` (`lambda `, `suspend lambda `, `flow `, `state flow `) at a non-extension position, on the assumption "a named legacy route re-emits it", which is false for a sealed arm |
| C# flow method projection | `cir/CirClassTranslator.kt:710-909`, inline in `translateClass`, appended at `:945` | builds the `[DllImport]` set and the `CirMethod(isFlow = true)` per member, including ADR-122's handle parameters (`legacyRouteParameters`, `:751`) and ADR-123's element read (`:747`) |
| C# flow property projection | `cir/CirClassTranslator.kt:322-527`, inline in the property `mapNotNull` | detection `:322-325`, C# type `:421-427`, getter `:429-497`, the `CirProperty` `:512-527`; the getter bakes `ObjectDisposedException(nameof(${cls.simpleName}))` |
| C# sealed projection | `cir/CirClassTranslator.kt:1206-1355` | `translateSealedClass` projects neither: its property `mapNotNull` returns null for any unplanned non-lambda property (`:1257`), and its method half is the plan catalog plus ADR-118's `suspendMembers` |
| C# sealed renderer | `cir/CirSealedRenderer.kt:84-102` | routes **any** `usesLegacyNativeImport()` property (`cir/CirNativeImports.kt:99-107`, which is `isFlow || lambda || suspend lambda`) to the hand-written lambda extern `Native_Get_${nativeName}(IntPtr handle, out IntPtr error)` |
| C# flow property externs | `cir/CirClassRenderer.kt:240-270`, inline in `renderClass` | the `_collect` / `_value` / `_has_value` / `_set_value` `[DllImport]` block, reachable only from an ordinary class |
| Kotlin gates | `NugetProcessor.kt:1338-1355` (`needsFlowImports`), `:1784-1795` (`needsFlowSupport`, feeding `needsScopeHelpers` `:1799-1801`) | both iterate `classes` only, so an arm-only flow surface gets no coroutines `OptIn`, no imports and no shared `nuget_scope_*` / `nuget_job_*` exports |
| Refused-member walk | `NugetProcessor.kt:387-398` | the sealed loop filters `Modifier.SUSPEND`, so an arm's refused *Flow* member is named by nothing |
| Legacy route recognition | `ForwardAbiLegacyRoutes.kt:95-103` | `CirSealedClass` walks `subclasses.flatMap { asyncMembers }` only |
| Hint text | `forward/ForwardDiagnostic.kt:590-593` | "expose an equivalent non-Flow, non-generic member on the sealed subclass instead" |

### Root cause, stated once

Five gaps, one per half of the pipeline, all the same shape: the flow route's owner is hardcoded to
an ordinary class, in the Kotlin emitter (inline in `addClassExports`), in the C# translator
(inline in `translateClass`), in the two Kotlin gates, in the planner's post-process, and in the
sealed renderer, which has no flow arm at all and would emit a lambda-shaped extern if handed a
flow `CirProperty`.

## Alternatives Considered

### 1. Re-key the legacy flow route to sealed arms, mirroring ADR-118 site for site (chosen)

Extract the flow property emitter and the flow method emitter out of `ClassExports.kt` into
`exports/FlowExports.kt` (behaviour-preserving: the callers keep owning membership filtering);
extract `flowMembers` and the flow-property branch out of `translateClass`; add an arm loop beside
ADR-118's in `NugetProcessor.generateCNameWrappers`; carry the pairs on a new
`CirSealedSubclass.flowMembers`; widen `hasSuspendMethods` to "owns a coroutine scope"; give the
sealed renderer a flow-property arm before its lambda arm.

Pros: one copy of each projection, so a flow-bearing arm's C# surface is byte-for-byte an ordinary
class's, including ADR-122's handle parameters and ADR-123's element read; the overload number is
the planner's, so an arm's overload pair numbers by construction; the arm's scope, `IAsyncDisposable`
and `DisposeAsync` come from ADR-118's shape unchanged, so an arm carrying both a `suspend fun` and
a flow member emits exactly one scope.

Cons: the flow route stays legacy text on the C# side (the standing ROADMAP Phase 3 item);
`ClassExports.kt` loses ~200 lines to a move, which is a rebase hazard for anything else editing
that block.

### 2. Migrate the flow route onto the ADR-062 callable plan

The stated long-term direction. Rejected on the same terms ADR-118 rejected it: the plan owns an
ABI parameter list, a result convention and an error slot, and has no notion of a collect
subscription, a per-item callback, a scope receiver or a `_value` sibling. None of that is needed
to close this line.

### 3. Close the method half only, leave the property silent

Rejected. The issue's own example is a property (`val ticks: StateFlow<Int>`), and silence is worse
than a named skip: the method form at least tells the author their member vanished.

### 4. Declared-only properties on the arm

Rejected: it contradicts [ADR-111](111-sealed-subclass-properties-on-the-property-plan.md), whose
`sealedSubclassProperties` is deliberately `getAllProperties()` with `superClass = null`
(`forward/ForwardPropertyPlanner.kt:105-134`), because the generated C# base is abstract and carries
no members. A base-declared flow property has to bind on **every** arm, under each arm's own prefix,
or the C# base advertises nothing and the arms lose it.

### 5. Lift the member onto the sealed base

Forbidden by the issue, and wrong: the member is declared on one arm, and putting it on the base
makes it look available on every arm. The base also has no scope to collect into (its
`Native_Dispose` is per arm).

## Decision

Alternative 1.

### Consumer-facing C# API

```csharp
public abstract class Job : IDisposable, INugetHandle
{
    // A flow-only arm: the scope, IAsyncDisposable and DisposeAsync arrive from the flow route
    // alone, exactly as they do for an ordinary class whose only async member is a flow.
    public sealed class Watching : Job, IAsyncDisposable
    {
        public KotlinStateFlow<int> Ticks { get; }        // job_watching_get_ticks_collect/_value
        public KotlinFlow<string> Labels(string prefix);  // job_watching_labels_collect
        public KotlinFlow<string> Labels(string prefix, int times);  // job_watching_labels_2_collect
        public override void Dispose();
        public ValueTask DisposeAsync();
    }

    // Coexistence: an arm that already carries suspend members gains the flow member on the same
    // scope. One `_scopeHandle`, one `DisposeAsync`.
    public sealed class Running : Job, IAsyncDisposable
    {
        public Task<int> PauseAsync(CancellationToken cancellationToken = default);
        public KotlinStateFlow<int> Beats { get; }        // job_running_get_beats_collect/_value
    }

    // Control: neither a suspend nor a flow member, so no scope and no IAsyncDisposable.
    public sealed class Done : Job { }
}
```

### Naming rule

Unchanged from the ordinary-class route, with the arm's prefix substituted. `prefix` is
`${sealed}_${sub}` (both lowercased), the prefix `SealedClassExports`, `sealedSubclassProperties`
and `translateSealedClass` already mint for the arm's getters, methods and `_dispose`:

| shape | `@CName` / `EntryPoint` | C# extern | C# public |
|---|---|---|---|
| flow property | `${prefix}_get_${name}_collect` (+ `_value`, `_has_value`, `_set_value`) | `Native_Get${Name}Collect` | `${Name}` |
| flow method, 1st | `${prefix}_${name}_collect` | `Native_${Name}Collect` | `${Name}` |
| flow method, n-th | `${prefix}_${name}_${n}_collect` | `Native_${Name}_${n}Collect` | `${Name}` |

The overload suffix composes **before** the protocol tail, and lands on the entry point **and** on
the extern stem. Numbering only the entry point lets an arity-compatible second overload bind to
the first overload's extern: it compiles, and answers the wrong values.

### Bridge mechanism, per change

**A. Kotlin: extract the two per-member emitters.** New file `exports/FlowExports.kt` holding
`addFlowPropertyExports(prop, qualifiedName, prefix, classifier)` (moved out of
`ClassExports.kt:116-224`) and `addFlowMethodExports(method, qualifiedName, prefix, classifier,
callableCatalog)` (moved out of `:292-443`), plus the private body builders they own.
`addClassExports` calls them from its existing loops with `prefix = cls.simpleName.lowercase()`. No
filter moves: the callers keep owning membership filtering, exactly as ADR-118 kept
`filteredMethods` in the caller. **Verified by reading**: the two blocks read only `qualifiedName`,
`prefix`, `classifier` and (the method half) `callableCatalog` off the enclosing function.

**B. Kotlin: the arm loop.** A sibling of ADR-118's loop in `NugetProcessor.generateCNameWrappers`,
under `attributing(subclass)` so ADR-117 names the arm rather than the base:

- **properties: `getAllProperties()`, not declared-only**, matching ADR-111's `superClass = null`
  rule (`ForwardPropertyPlanner.kt:123`, `SealedClassExports.kt:68-70`,
  `CirClassTranslator.kt:1233-1235`). Both halves must use this rule or a C# import arrives with no
  Kotlin export behind it.
- **methods: declared-only** (`parentDeclaration == subclass`), the ADR-116/118 rule
  (`ForwardCallablePlanner.kt:1000`, `CirClassTranslator.kt:1315-1321`).
- both halves additionally apply `classifier.legacyRefusedParameter(...) == null` (ADR-114),
  `legacyRefusedReturn(...) == null` (ADR-119) and `legacyRefusedFlowElement(...) == null`
  (ADR-123), exactly as the ordinary route does (`ClassExports.kt:129, 246-247`,
  `CirClassTranslator.kt:347, 530-541`).

**C. Kotlin: the gates.** `needsFlowImports` (`NugetProcessor.kt:1354`) and `needsFlowSupport`
(`:1795`) each gain the arm walk, mirroring `armsHaveSuspendMethods` (`:1329-1331`). Without the
second, an arm's `GetOrCreateScope()` calls a `nuget_scope_create` that was never exported:
`EntryPointNotFoundException` at the first collect, loud.

The same loop widens `legacyRouteCollectionKinds` (`NugetProcessor.kt:1650-1662`), which gathers
the `nuget_list_*` / `nuget_set_*` / `nuget_map_*` exports a legacy-route member needs. It walked an
arm's *suspend* members only, so an arm's `Flow<Set<T>>` would have called a helper the native
library never exported (`EntryPointNotFoundException` at the first collect, loud). No fixture cell:
the rule is the same one ADR-123 established for an ordinary class, applied to the arm's selector.

**D. Planner: exempt `FLOW_PROTOCOL` from the sealed post-process.**
`ForwardCallablePlanner.kt:1066` gains `&& entry.reason != ForwardPlanSkipReason.FLOW_PROTOCOL`.
`GENERIC`, `CALLBACK_PROTOCOL` and `SUSPEND_CALLBACK_PROTOCOL` keep the rewrite, so ROADMAP line
39's other three kinds keep their diagnostic (issue #129 requirement 5 asks for exactly that).
**Verified by reading** (`:1076-1080`): the rewrite copies `entry.symbol` and `entry.node`, so the
overload number survives whether or not the exemption lands; the exemption is needed for the
*diagnostic*, not for the numbering.

**E. C#: two lifts.** `flowMembers` becomes `internal fun flowMembers(flowMethods, prefix,
libraryName, classifier, tracker, callableCatalog, context): List<CirMember>` and the flow-property
branch becomes `internal fun flowProperty(prop, ownerCsName, context, classifier, tracker):
CirProperty?`. **`ownerCsName` is load-bearing**: the getter bakes
`ObjectDisposedException(nameof(...))` and must name the *arm*, not the base. Both must keep setting
`tracker.needsFlow` / `needsStateFlow` / `needsMutableStateFlow` / `needsAsync`, or `KotlinFlow` /
`KotlinStateFlow` are never emitted (`cir/CirTranslator.kt:616-621, 683`) and the generated file
does not compile. Loud. ADR-122's `Handle` parameter arm and ADR-123's element read move **intact**
inside `flowMembers`; the two ordinary-class fixtures for them (`ObservationRadio.Watch`,
`NodeHub.Items`) must render byte-identically before and after the move.

**F. C#: model and renderer.** `CirSealedSubclass` gains `flowMembers: List<CirMember>`;
`hasSuspendMethods` widens to `asyncMembers.isNotEmpty() || flowMembers.isNotEmpty() ||
properties.any { it.isFlow }`, one boolean so the scope field cannot be emitted twice. The name
stays: `CirClass.hasSuspendMethods` already carries the same widened meaning
(`CirClassTranslator.kt:947-953`), and renaming one half would make the two disagree. The kdoc is
corrected to say "owns a coroutine scope". `CirSealedRenderer.sealedSubclassBlock` gains an
`if (prop.isFlow)` arm **before** the `usesLegacyNativeImport()` arm, calling a
`flowPropertyNativeImports(libraryName, nativePrefix, prop)` lifted out of
`CirClassRenderer.kt:240-270` (the second time ADR-111's lift is made), plus a `require` in the
lambda arm so a flow property can never fall into it silently again; and a
`subclass.flowMembers.forEach { renderMember(it, subclass.name).indentNestedBody() }` beside the
`asyncMembers` loop.

**G. Diagnostics.** The `SEALED_SUBCLASS_UNROUTED` hint (`forward/ForwardDiagnostic.kt:590-593`)
drops `non-Flow`. `warnRefusedLegacyRouteMembers`'s sealed loop (`NugetProcessor.kt:387-398`) swaps
its `Modifier.SUSPEND` filter for `isForwardLegacyAsyncRoute()`
(`forward/ForwardLegacyRouteCollections.kt:420-425`, which already covers Flow and StateFlow
returns), so an arm's flow member with a refused parameter, return or element stays **named**
instead of regressing to silent. That is the exact regression ADR-118 guarded against for suspend,
one route over. The arm's flow *properties* join the same walk for the ADR-123 element refusal.

**H. ABI legacy routes.** `ForwardAbiLegacyRoutes.add(CirSealedClass)` (`:95-103`) walks
`subclasses.flatMap { it.asyncMembers + it.flowMembers }` and the arm's `properties`. Recognition
stays structural (`CirMethod.isFlow`, `CirProperty.isFlow`), never by entry-point name. Feeds
`ForwardAbiLegacyRoutesTest` only; the ABI contract's `csharpLegacy` scraper picks the arm's
`[DllImport]` off the rendered text with no change.

### Fixture

`test-library/.../issue115/JobSample.kt`, the ADR-116/118 fixture, gains one arm and one line:

```kotlin
data class Watching(val id: String) : Job() {
  private val _ticks: MutableStateFlow<Int> = MutableStateFlow(id.length)
  /** The issue's own shape: a StateFlow property getter on an arm, silent today. */
  val ticks: StateFlow<Int> get() = _ticks
  /** A plain Flow at a method return: String in and out on the collect protocol. */
  fun labels(prefix: String): Flow<String> = flow { emit("$prefix$id") }
  /** Overload pair on an arm: `_2` on the entry point AND on the extern stem. */
  fun labels(prefix: String, times: Int): Flow<String> =
    flow { repeat(times) { index -> emit("$prefix#$index") } }
}

// in Job.Running, the coexistence cell: one scope, not two.
val beats: StateFlow<Int> get() = _beats
```

`Job.Done` stays the control with neither a suspend nor a flow member, so it keeps rendering without
`IAsyncDisposable`.

Deliberately **not** added: a base-declared `StateFlow` on `Job` itself (the all-properties rule is
ADR-111's and is already fixture-covered for ordinary property types), a `suspend fun` returning a
`Flow` (ROADMAP line 112, still a named `SKIPPED_UNSUPPORTED_RETURN` since ADR-119), a sealed
element type (`Flow<Job>`, ADR-122/123 territory), a `MutableStateFlow` write on an arm.

### Tests

- **Tier 1**, new `Tier1SealedArmFlowTest.kt` (shape: `Tier1FlowMethodOverloadTest.kt`, which needs
  `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)`; without it every flow member is
  skipped as an unsupported type and the test passes vacuously). Asserts the arm-prefixed entry
  points, the absence of a base-prefixed one, `KotlinStateFlow<int> Ticks`, `KotlinFlow<string>
  Labels(...)`, two distinct externs `Native_LabelsCollect` / `Native_Labels_2Collect`, `Watching :
  Job, IAsyncDisposable`, `Done` without it, and `compiledClean`.
- **Tier 1 diagnostics**, in the same file: a `FLOW_PROTOCOL` arm member is no longer named
  `SEALED_SUBCLASS_UNROUTED`, and a `GENERIC` one still is. That pair is what keeps this change
  from silently closing issue #129's sibling cases.
- **Tier 1 sealed interface**: one cell in the shape of `issue54/SealedInterfaceSample.kt`'s
  `Pulse`, proving a flow member on an eligible nested arm binds through the same list (see
  Consequences).
- **IntegrationTests**, `SealedSubclassMethodTests.cs`: `Ticks_OnASealedArm_ReadsValueAndCollects`,
  `Labels_OnASealedArm_Collects`, `Labels_SecondOverloadOnAnArm_ReturnsItsOwnEmissions` (asserts the
  *values*, per the silent mis-dispatch hazard), `Watching_IsAsyncDisposable_AndDoneIsNot`,
  `Beats_OnASuspendingArm_Collects`.
- **LeakTests**, row 8e: the same enumerate-to-completion loop row 7 runs for an ordinary flow
  owner, with a sealed arm as the owner. The arm mints nothing new, but it owns its scope through
  its own `_scopeHandle` and drains it in its own `DisposeAsync`, which is the spelling under test.

### File list

Kotlin half: new `exports/FlowExports.kt`; `exports/ClassExports.kt` (call the extracted emitters);
`NugetProcessor.kt` (arm export loop, both gates, the refused walk);
`forward/ForwardCallablePlanner.kt` (the exemption); `forward/ForwardDiagnostic.kt` (the hint).
C# half: `cir/CirClassTranslator.kt` (both lifts, called from `translateSealedClass`);
`cir/CirModel.kt` (`flowMembers`, widened `hasSuspendMethods`); `cir/CirNativeImports.kt` (the
lifted flow-property extern rule); `cir/CirClassRenderer.kt` (call it); `cir/CirSealedRenderer.kt`
(the flow property arm and the `flowMembers` loop); `ForwardAbiLegacyRoutes.kt`.
Fixtures and tests: `issue115/JobSample.kt`, `SealedSubclassMethodTests.cs`,
`LeakTests/LiveHandleTests.cs`, new `tier1/Tier1SealedArmFlowTest.kt`,
`ForwardAbiLegacyRoutesTest.kt`; `FEATURES.md`, `ROADMAP.md`, `docs/adr/README.md`, ADR-116's
amendment block, ADR-118's Consequences placeholder.

### Mechanism claims, labelled

- **Verified by execution** (prior sessions, repo): the flow route's `_2` numbering
  (`Tier1FlowMethodOverloadTest`); ADR-118's identical re-key of the suspend route, including that
  `overloadSuffix` survives the sealed post-process; a flow-only *ordinary* class getting
  `_scopeHandle` / `GetOrCreateScope()` / `IAsyncDisposable` off `CirClass.hasSuspendMethods`
  (`CirClassTranslator.kt:947-953`).
- **Verified by reading** (file:line above): the planner's `FLOW_PROTOCOL` rewrite; the property
  half's silence through `recordDropped`'s `LEGACY_ROUTED_PROTOCOLS` early return; the sealed
  renderer's lambda-extern arm claiming any `isFlow` property; both gates iterating `classes` only;
  the refused walk's `SUSPEND` filter; ADR-111's all-properties rule for arms.
- **Inferred, not run at authoring time**: that the extracted `flowMembers` / `flowProperty` render
  byte-identically for an ordinary class (a pure move, confirmed by diffing the generated
  `Interop.cs` for `ObservationRadio.Watch` and `NodeHub.Items` before and after); that an arm
  carrying both routes emits exactly one `_scopeHandle` (one boolean by construction, and a second
  one would be CS0102 in the consumer's build, loud).

## Implementation notes (2026-09-10, `ir/flow-route-sealed-arms`)

- **The two lifts are pure moves, proved by diffing the generated output.** `Interop.cs` and
  `CNameExports.kt` were regenerated for `test-library` before and after the change: **zero removed
  lines** on either file, every difference an addition (the new `Watching` arm, `Running.Beats`, and
  `JobFactory.Watching`). `ObservationRadio`'s handle-parameter flow method (ADR-122) and
  `NodeHub.Items`' collection element (ADR-123) are byte-identical across the move, which is the one
  thing a refactor of this size has to demonstrate.
- **Sealed interfaces (ADR-112) get the flow route for free**, as ADR-118's suspend route already
  did. Pinned by a Tier 1 cell shaped after `issue54/SealedInterfaceSample.kt`'s `Pulse`:
  `pulse_beat_get_rate_collect` on the Kotlin side and `KotlinStateFlow<int> Rate` on the C# side.
  ADR-118's Consequences placeholder is completed with the same answer.
- **One site beyond the ADR's file list**: `legacyRouteCollectionKinds` (see item C above). Found by
  reading, not by a failure, because no fixture puts a collection element on an arm's flow member.
- The `require` in `CirSealedRenderer`'s lambda-property arm is deliberately unreachable: it exists
  so the ordering hazard in item F cannot come back silently, and it is uncovered by design.
- Coverage on the new and moved code: `FlowExports.kt` 84% of lines, `CirSealedRenderer.kt` 95%. The
  cold regions are the nullable-member `_has_value` and `MutableStateFlow` `_set_value` emitters,
  which no unit test reaches on either route (they are exercised by the real `packNuget` fixtures);
  their coverage profile is exactly what it was before the move.

## Consequences

- Every `Flow<T>` / `StateFlow<T>` a sealed arm declares (at a method return) or carries (at a
  property, declared or inherited) binds on that arm as `KotlinFlow<T>` / `KotlinStateFlow<T>`, off
  the arm's own export prefix, through the same collect and value thunks the ordinary-class route
  uses. The element is spelled exactly as the ordinary route spells it, so ADR-123's collection
  elements and ADR-122's handle parameters come along for free.
- `SEALED_SUBCLASS_UNROUTED` now covers `GENERIC`, `CALLBACK_PROTOCOL` and
  `SUSPEND_CALLBACK_PROTOCOL` arm members only. Issue #129 asked for the flow case only and the
  generic case stays deferred, named.
- A flow-bearing arm becomes `IAsyncDisposable` with a `DisposeAsync` that drains its scope, and its
  `Dispose()` cancels the scope first. This is ADR-118's base/arm asymmetry, unchanged: the sealed
  base stays `: IDisposable, INugetHandle`, so a consumer holding a `Job` pattern-matches to the arm
  before `await using`.
- A flow property declared on the sealed **base** binds on every arm, under each arm's own prefix
  (ADR-111's rule), while a flow **method** on the base binds nowhere (ADR-116's declared-only
  rule). The two halves of the sealed route differ here by design, and this ADR keeps them
  differing rather than picking one.
- The property half of a flow member on an arm stops being silent. `recordDropped`'s
  `LEGACY_ROUTED_PROTOCOLS` early return ("a named legacy route re-emits it") is now true again at
  both positions for `flow ` and `state flow `, so ROADMAP line 48's standing "is this assumption
  true at every position" item has one fewer false case. It stays false for a **lambda** property on
  an arm only in the sense that the lambda route does re-emit that one; nothing to do.
- **Sealed interfaces (ADR-112) get the flow route for free, and so did the suspend route.**
  `rootSealedClasses` filters `isEligibleSealedType()`, which is
  `isSealed && (classKind == CLASS || isEligibleSealedInterface())`
  (`forward/ForwardClassMembership.kt:70-76`), so an eligible sealed interface is in the same
  `sealedClasses` list the new export loop, both widened gates, the refused walk and
  `translateSealedClass` all iterate. An eligible sealed interface's arm binds its flow members
  under this ADR with no further change, and an *ineligible* one is on the ADR-040 interface route,
  where it was before. This also completes the placeholder ADR-118 left in its own Consequences.
  No new ROADMAP line.
- Deferred, unchanged: `Flow`-returning **generic** members and lambda-parameter members on an arm
  (line 39's remaining halves); `SUSPEND_CALLBACK_PROTOCOL` on an arm; a `suspend fun` returning a
  `Flow` (ROADMAP line 112); structural (non-scraped) ABI coverage of the arm's flow imports; the
  arm's flow exports carry no ADR-117 owner tag, like every other `exports/` legacy route
  (ROADMAP.md already records this), so a collision message names the arm through the
  `attributing(subclass)` range rather than through a tag.
