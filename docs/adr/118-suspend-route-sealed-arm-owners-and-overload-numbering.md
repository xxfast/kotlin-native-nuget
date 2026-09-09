# ADR-118: Suspend route: sealed-arm owners and overload numbering

## Status

Accepted

### Gate decisions (2026-09-09)

Accepted at the gate: both ROADMAP lines in one change, the implementing PR carries `Fixes #115`,
top-level suspend overload numbering becomes a ROADMAP line (not built here). The gate also folded
the three questions this ADR first left open into implementation instructions (see "Implementation
instructions folded from the gate" under Decision) and added one fixture cell: a same-arity,
same-wire-shape suspend overload pair, the only cell that pins the extern-**name** numbering.

## Context

Two ROADMAP lines, one change, both on the legacy suspend route (ADR-019, kept as a specialized
legacy route by ADR-062's table row "Suspend functions / methods → async registration protocol"):

1. **ROADMAP line 54 (Phase 4).** Two `suspend` overloads on one class collide on one C symbol.
   `addSuspendClassMethodExports` (`SuspendFunctionExports.kt:105-106`) composes
   `${prefix}_${cname}_async` off the bare method name, and both C# projections
   (`CirClassTranslator.kt` `asyncMembers` `:679-729` and `suspendStateFlowMembers` `:738-785`)
   compose `${prefix}_${cname}_async` and `Native_${csMethodName}Async` the same way. The flow
   route had the identical defect and fixed it in [#97](https://github.com/xxfast/kotlin-native-nuget/issues/97)
   by reading `ForwardCallablePlanCatalog.overloadSuffix(method)` on both halves
   (`ClassExports.kt:273`, `CirClassTranslator.kt:791`). **Verified by execution**:
   `Tier1EntryPointCollisionTest`'s `Radio` cell (`suspend fun play(Player)` / `play(Track)`) is
   green today *because* it collides, reporting `ERROR_C_ENTRY_POINT_COLLISION` on
   `radio_play_async` naming both owners (ADR-117).
2. **ROADMAP line 39, suspend half (Phase 3).** A `suspend fun` declared on a sealed subclass is
   absent from C# and named `SKIPPED_UNSUPPORTED_COMBINATION` (`SEALED_SUBCLASS_UNROUTED`,
   detail `SUSPEND`) since ADR-116, which priced this as its option (b) and deferred it. Issue
   [#115](https://github.com/xxfast/kotlin-native-nuget/issues/115) stays open on it
   (`Job.Running.pause()`).

This ADR is **not** a migration of the suspend route onto the callable plan. It re-keys the
existing route so a sealed arm is a valid owner, and threads the planner's overload number through
the route's three composition sites, exactly as #97 did for the flow route.

### Where the pieces are today (all **Verified by reading** on branch `ir/suspend-route-sealed-arms-and-overloads`)

| Concern | Site | What it does now |
|---|---|---|
| Overload counter | `ForwardCallablePlanner.kt:878-895` (`classEntries`), `:986-1003` (`sealedSubclassEntries`), `:747-773` (value class), `:815-826` (interface, no counter) | `occurrences.merge(name, 1, Int::plus)` runs *before* the structural `when`; the numbered `symbol` (`$owner.$name$suffix`) is what `Skipped(symbol, SUSPEND, node = method)` carries |
| Reading the number back | `ForwardCallablePlanCatalog.overloadSuffix` `:466-472` | finds the entry by `node ===`, strips the simple name off `symbol.substringAfterLast('.')`, returns `""` or `_$n`; **lenient**: an unseen declaration returns `""` |
| Sealed post-process | `ForwardCallablePlanner.kt:1044-1058` | every `droppedFromCSharp = false` skip except `ABSTRACT` is rewritten to `Skipped(entry.symbol, SEALED_SUBCLASS_UNROUTED, node = entry.node, detail = entry.reason.name)`; the **numbered symbol and node are copied**, so `overloadSuffix` still answers for the rewritten entry |
| Kotlin export | `SuspendFunctionExports.kt:71-132` | prefix `cls.simpleName.lowercase()` (`:77`), members from `getAllFunctions()` (`:79`), receiver `handle.asStableRef<$qualifiedName>().get()` (`:176`), scope from `scopeHandle` (`:177`), ADR-117 owner tag on the `FunSpec` (`:107`) |
| Kotlin gates | `NugetProcessor.kt:1218-1220` (`hasSuspendFunctions`, coroutine imports), `:1336-1341` (per-class export loop), `:1633-1637` (`needsScopeHelpers`, the shared `nuget_scope_*`/`nuget_job_*` exports) | all three iterate `classes` only |
| ADR-114 refused-parameter diagnostic | `NugetProcessor.kt:297-311` | iterates `classes` and `suspendFunctions` only |
| C# projection | `CirClassTranslator.kt:679-785`, inside `translateClass` | builds a `CirDllImport` + `CirMethod(isAsync = true)` pair per member and appends both to `companionMembers` (`:1013`); sets `tracker.needsAsync` (`:677`) |
| C# renderer | `CirClassRenderer.kt:319-321` (`renderMember` over `companionMembers`), `CirConcurrencyRenderer.kt:57-171` (`renderAsyncMethod`) | the async body calls `_handle`, `GetOrCreateScope()` and `method.nativeName`; `GetOrCreateScope()`/`_scopeHandle`/`IAsyncDisposable`/`DisposeAsync` render only when `CirClass.hasSuspendMethods` (`CirClassRenderer.kt:196-225`, `renderDispose :611-679`) |
| Sealed C# node and renderer | `CirModel.kt:111-130`, `CirSealedRenderer.kt:60-131` | `CirSealedSubclass` has `properties`, `methods: List<CirMethod>` (plan-projected), no async carrier, no scope; the base renders `: IDisposable, INugetHandle` with `_handle` only; the arm's `Dispose` is hand-rolled (`:114-125`) |
| Legacy route recognition | `ForwardAbiLegacyRoutes.kt:67-96, 134-147` | structural (`CirMethod.isAsync` → `SUSPEND_METHOD`), never by entry-point name; `CirSealedClass` adds `SEALED_CLASS` and does not walk its subclasses; consumed only by `ForwardAbiLegacyRoutesTest` (no processor/contract consumer) |
| ABI contract | `ForwardAbiContract.csharpLegacy` `:188-224` | scrapes every `[DllImport]` in the rendered `Interop.cs`, regardless of which class declares it; `ForwardAbiContract.kotlin` (`:226-229`) filters Kotlin exports to the C# import set, so an **extra** Kotlin export is dropped from the comparison, not flagged |
| Owner text | `ForwardExportOwners.kt:146` | `declaration.qualifiedName`, so an arm's method names as `pkg.Job.Running.pause(...)` with no change |

## Alternatives Considered

### 1. Re-key the legacy suspend route to sealed arms, number on both halves, scope per arm (chosen)

Thread `overloadSuffix` through the three composition sites; give `addSuspendClassMethodExports`
a `prefix` and a declared-only filter; extract the two C# projections out of `translateClass` into
a function `translateSealedClass` can call; carry the pairs on a new `CirSealedSubclass.asyncMembers`;
give a suspending **arm** its own `_scopeHandle`/`GetOrCreateScope()`/`IAsyncDisposable`, reusing
`renderDispose`.

Pros: one copy of the suspend projection (extracted, not duplicated); the arm's C# shape is exactly
an ordinary class's (`Task<T>`, `CancellationToken`, exception marshalling, `DisposeAsync` drain);
the numbering is the planner's, so a same-name plain-suspend and StateFlow-suspend pair share one
counter by construction; ADR-117's owner naming works unchanged.

Cons: `renderDispose` reuse changes the text of every arm's `Dispose` body (the
`Interlocked.Exchange` form replaces the `if (_handle != IntPtr.Zero)` form). **Verified by grep**:
no test under `nuget-processor/src/test` or `IntegrationTests` pins the string
`Native_Dispose(_handle)`, so nothing flips on that text. The route stays legacy text on the C#
side, one more `csharpLegacy`-scraped import family (the same standing ROADMAP Phase 3 item that
already covers the sealed property and method imports).

### 2. Scope on the sealed base instead of per arm

Put `_scopeHandle` and `GetOrCreateScope()` on the C# base (`Job`) next to `_handle`, gated by a
new `CirSealedClass.hasSuspendMethods`.

Pros: one field for all arms, mirrors where `_handle` lives.
Cons: the base's `Dispose()` is `abstract` and `Native_Dispose` is **per arm**
(`job_running_dispose`), so `DisposeAsync`'s drain-then-dispose closure cannot be rendered on the
base without a virtual hook into each arm's dispose import; `IAsyncDisposable` on the base would
advertise `DisposeAsync` on arms that never suspend. It also makes the sealed renderer carry a
second copy of `CirClassRenderer.kt:205-225`, whereas per-arm reuse can call `renderDispose` as is.
Rejected.

### 3. Number only, leave arms deferred (line 54 alone)

Thread `overloadSuffix` through the three sites and stop. Pros: three files, no new C# shape.
Cons: leaves #115 open and ROADMAP line 39's suspend half where ADR-116 left it, and the second
part of the work (arm owners) touches the same three functions again a second time. Priced in
"Scope options, priced in files" under Decision, not chosen as the ADR's scope.

### 4. Move the suspend route onto the callable plan

ADR-062's stated long-term direction. Rejected here on the task's own terms: the route stays a
specialized legacy route; the plan has no async result shape, no scope receiver and no completion
callback, and none of that is needed to close the two lines.

## Decision

Alternative 1. The suspend route gains two inputs it lacked, the owner's export prefix and the
planner's overload number, and a sealed arm becomes a valid owner on both halves.

### Consumer-facing C# API

```csharp
// ROADMAP line 54: two suspend overloads on an ordinary class, a natural C# overload set.
public sealed class AsyncCatService : IDisposable, IAsyncDisposable, INugetHandle
{
    public Task<Cat> FetchCatAsync(string name, CancellationToken cancellationToken = default);
    public Task<Cat> FetchCatAsync(string name, int lives, CancellationToken cancellationToken = default);
    //   externs: Native_FetchCatAsync  -> asynccatservice_fetchCat_async
    //            Native_FetchCat_2Async -> asynccatservice_fetchCat_2_async
}

// ROADMAP line 39, suspend half: an async method on the arm, exported with the arm's prefix.
public abstract class Job : IDisposable, INugetHandle
{
    public sealed class Running : Job, IAsyncDisposable
    {
        public Task<int> PauseAsync(CancellationToken cancellationToken = default);
        public Task<int> PauseAsync(int millis, CancellationToken cancellationToken = default);
        public Task<string> ResumeAsync(string prefix, CancellationToken cancellationToken = default);
        public override void Dispose();          // cancels + disposes the arm's scope, then job_running_dispose
        public ValueTask DisposeAsync();         // drains the scope first, the ordinary-class shape
        //   externs: Native_PauseAsync   -> job_running_pause_async
        //            Native_Pause_2Async -> job_running_pause_2_async
        //            Native_ResumeAsync  -> job_running_resume_async
    }

    public sealed class Idle : Job, IAsyncDisposable
    {
        public Task<string> NapAsync(CancellationToken cancellationToken = default);   // data object arm
    }

    public sealed class Done : Job { /* unchanged: no suspend member, no scope, no IAsyncDisposable */ }
}
```

```csharp
// Consumer
await using var running = (Job.Running)JobSample.AnyJob(40);
int paused = await running.PauseAsync();
int again  = await running.PauseAsync(250);
```

### Naming rule

The overload suffix composes **before** the protocol tail, exactly as the flow route's
`radio_play_2_collect` (`Tier1FlowMethodOverloadTest`, **Verified by execution**):

| occurrence | `@CName` / `EntryPoint` | C# extern | C# public |
|---|---|---|---|
| 1st | `${prefix}_${name}_async` | `Native_${Name}Async` | `${Name}Async` |
| n-th (n ≥ 2) | `${prefix}_${name}_${n}_async` | `Native_${Name}_${n}Async` | `${Name}Async` |

where `prefix` is `cls.simpleName.lowercase()` for an ordinary class (unchanged) and
`${sealed}_${sub}` (both lowercased) for a sealed arm, the prefix `SealedClassExports`,
`sealedSubclassEntries` (`ForwardCallablePlanner.kt:963`) and `translateSealedClass`
(`CirClassTranslator.kt:1117`) already mint for the arm's getters, methods and `_dispose`.

### Bridge mechanism, per change

**A. Numbering, Kotlin half.** `addSuspendClassMethodExports` takes the `ForwardCallablePlanCatalog`
and composes `"${prefix}_${cname}${callableCatalog.overloadSuffix(method)}_async"` at both
`SuspendFunctionExports.kt:105-106`. The call `obj.$methodName(...)` (`:187/:190`) keeps the bare
name, as the flow route does ("the symbol carries the overload suffix; the Kotlin call site must
not"). `addSuspendFunctionExports` (top level) is untouched. ADR-095 numbers **non-suspend**
top-level functions through `topLevelEntry`; a top-level `suspend fun` is **Inferred** never to
reach the catalog at all (the processor keeps it on a separate `suspendFunctions` list,
`NugetProcessor.kt:1329`, and the planner's four `SUSPEND` skip sites are all member routes; if it
did reach `topLevelEntry`, `planOrSkip` would plan a synchronous export of a suspend body, which
cannot compile per ADR-068's note, and the build is green). Not checked this session; the gap is
recorded under Deferred.

- **Verified by reading** (`ForwardCallablePlanner.kt:878-895`, six straight lines): in
  `classEntries`, `occurrence` and `symbol` are computed *before* the structural `when`, and
  `Skipped(symbol, SUSPEND, node = method)` carries that numbered symbol; `overloadSuffix`
  (`:466-472`) then returns `_2` for the second `suspend fun play`. The FLOW route's skip carries the
  same `symbol` variable through `planOrSkip`, and its `_2` is **Verified by execution** by
  `Tier1FlowMethodOverloadTest`. **Not executed for the SUSPEND skip in this session** (the only
  runner is a Gradle test round, out of bounds for this research). If this reading were wrong the
  failure is **loud, not silent**: both overloads would keep `radio_play_async` and
  `ERROR_C_ENTRY_POINT_COLLISION` fires, which the existing `Radio` cell proves by execution. The
  implementing PR's first Tier 1 run settles it; a one-line unit assertion on
  `overloadSuffix(secondSuspendOverload) == "_2"` is cheap insurance.
- **Verified by reading**: the value-class route (`:747-773`) numbers its `SUSPEND` skip the same
  way; the interface route (`:815-826`) has **no counter** at all (ROADMAP Phase 4 backlog item
  "interface route has no overload numbering"), so a suspend overload pair on an interface stays
  out of scope here.

**B. Numbering, C# half.** Both projections read the same suffix and put it on **both** fields of
the pair:

```kotlin
val suffix: String = callableCatalog.overloadSuffix(method)
val nativeStem: String = "Native_$csMethodName${suffix}Async"
CirDllImport(entryPoint = "${prefix}_${cname}${suffix}_async", name = nativeStem, ...)
CirMethod(name = "${csMethodName}Async", nativeName = nativeStem, isAsync = true, ...)
```

- **Verified by reading**: `CirMethod.nativeName` defaults to `"Native_$name"` (`CirModel.kt:372`)
  and `renderAsyncMethod` calls `method.nativeName` (`CirConcurrencyRenderer.kt:63, 161`), while
  the extern's C# name is the *separate* `CirDllImport.name`. **This is the one silent-wrong-output
  hazard in the feature**: if the suffix lands on the import and not on `nativeName`, the second
  overload's body calls `Native_PlayAsync(_handle, scope, track, cb, ud)`, which resolves by arity to
  the *first* overload's extern, compiles, runs, and returns the first overload's result. The fixture
  therefore returns **distinguishable values** from each overload and the integration test asserts
  the second overload's value, not only its presence.
- **Verified by reading** (`CirClassTranslator.kt:526-534, 738-785`): `suspendStateFlowMembers` is
  a separate `flatMap` with its own `cname`/`Native_` composition, partitioned off
  `allSuspendMethods` before `asyncMembers`. The Kotlin half is shared (ADR-068: "the `_async` export
  is byte-for-byte `asyncMembers`'s shape; `SuspendFunctionExports.kt` needs no change"). So
  `suspendStateFlowMembers` **must** number too, or a `suspend fun watch(): StateFlow<Int>` /
  `watch(seed: Int): StateFlow<Int>` pair keeps colliding on the C# side after the Kotlin side is
  fixed. Line 54 names both; both ride along.
- Consequence, stated not tested: a same-name plain-suspend and StateFlow-suspend pair
  (`suspend fun a(): Int` / `suspend fun a(x: Int): StateFlow<Int>`) shares **one** counter, since the
  planner numbers over every declared member regardless of which C# `flatMap` later claims it.

**C. Planner: exempt `SUSPEND` from the sealed post-process.** `ForwardCallablePlanner.kt:1050-1051`
becomes

```kotlin
val isUnrouted: Boolean = !entry.reason.droppedFromCSharp &&
    entry.reason != ForwardPlanSkipReason.ABSTRACT &&
    entry.reason != ForwardPlanSkipReason.SUSPEND   // ADR-118: the suspend route is keyed to arms now
```

- **Verified by reading** (`:1052-1057`): the rewrite copies `entry.symbol` and `entry.node`, so the
  overload number survives the post-process **whether or not** the exemption lands; the exemption
  is needed for the *diagnostic* (a routed `SUSPEND` skip must stay silent like an ordinary class's,
  not be named a drop), not for the numbering. `sealedSubclassEntries` (`:986-1003`, prefix at
  `:963`, declared-only filter at `:976`) is a separate
  counter function from `classEntries`, which is why the arm overload pair is a fixture cell.
- `SUSPEND_CALLBACK_PROTOCOL` (a suspend member with a callback-protocol shape) is **not**
  exempted: no arm route emits it. Left as `SEALED_SUBCLASS_UNROUTED`, detail unchanged.

**D. Kotlin: re-key the route to arms.** `addSuspendClassMethodExports(cls, classifier,
callableCatalog, prefix: String = cls.simpleName.lowercase(), declaredOnly: Boolean = false)`.
`NugetProcessor.kt:1336-1341` gains a second loop:

```kotlin
sealedClasses.forEach { sealed ->
  sealed.getSealedSubclasses().forEach { sub ->
    attributing(sub) {
      // MUST be declared-only: the same `parentDeclaration == subclass` predicate the planner's
      // `sealedSubclassEntries` uses (`ForwardCallablePlanner.kt:976`). See the bullet below.
      if (sub.declaresSuspendMember()) builder.addSuspendClassMethodExports(
        sub, forwardClassifier, callableCatalog,
        prefix = "${sealed.simpleName.asString().lowercase()}_${sub.simpleName.asString().lowercase()}",
        declaredOnly = true,
      )
    }
  }

  // where
  fun KSClassDeclaration.declaresSuspendMember(): Boolean = getAllFunctions()
    .any { it.parentDeclaration == this && it.modifiers.contains(Modifier.SUSPEND) }
}
```

- **Declared-only is a MUST on the Kotlin arm loop**, spelled as `parentDeclaration == subclass`
  in the builder's filter chain (`SuspendFunctionExports.kt:79-86`) when `declaredOnly` is set, and
  in `declaresSuspendMember()` above, so the export loop, the gates and the refused walk all agree
  with the planner. The planner's `sealedSubclassEntries` is `parentDeclaration == subclass`
  (`:976`), while the builder reads `getAllFunctions()` (`:79`). An
  inherited `open suspend fun` the arm does not override would otherwise export under the arm's
  prefix with `overloadSuffix` returning `""` on its lenient path, colliding with a declared
  same-name member, and **the contract check would not catch it**: `ForwardAbiContract.kotlin`
  (`:226-229`) filters Kotlin exports to the C# import set, so an extra Kotlin export vanishes from
  the comparison (**Verified by reading**). The C# side (item F) is declared-only for the same
  reason.
- Receiver: `handle.asStableRef<$qualifiedName>().get()` with `qualifiedName = sub.qualifiedName`
  (`pkg.Job.Running`, `pkg.Job.Idle`). **Verified by the shipped sealed route**:
  `SealedClassExports.kt:41, 63, 89` already spell `asStableRef<$subQualifiedName>` for nested
  `data class` and `data object` arms (`Job.Idle`, `FlatShape.Loaf`) and compile on every CI target.
  A `data object` arm is a `KSClassDeclaration` in `getSealedSubclasses()` and an object type is a
  valid `StableRef` type argument, so `Idle` takes the same handle receiver, not a static.
- Scope: the arm's `_scopeHandle` (item G) crosses as `scopeHandle`, unchanged protocol.
- Owner tag (ADR-117): unchanged, `ForwardExportOwnerTag(declaration = method)` names
  `pkg.Job.Running.pause(Int)` via `ForwardExportOwners.kt:146`'s `qualifiedName` (**Verified by
  reading**).
- **Gates widen** (all three iterate `classes` only today, **Verified by reading**):
  `hasSuspendFunctions` (`:1218-1220`, coroutine/cinterop imports) and `needsScopeHelpers`
  (`:1633-1637`, the shared `nuget_scope_create`/`_cancel`/`_dispose`/`_drain` and
  `nuget_job_cancel`/`_dispose` exports) each add `|| sealedClasses.any { it.getSealedSubclasses().any { sub -> sub.declaresSuspendMember() } }`.
  Without the second, an arm's `GetOrCreateScope()` calls `nuget_scope_create` and the symbol does
  not exist: `EntryPointNotFoundException` at first call, loud.
- **ADR-114 refused-parameter diagnostic** (`NugetProcessor.kt:297-311`) gains the same sealed-arm
  loop. **Required, not nice-to-have**: today a suspend member with a refused generic parameter on
  an arm is named `SEALED_SUBCLASS_UNROUTED`; after item C it is a routed `SUSPEND` skip, the Kotlin
  builder (`:85`) and the C# translator (`CirClassTranslator.kt:513-515`) both filter it silently via
  `legacyRefusedParameter`, and this walk is the only thing left that names it. Without the loop
  the member regresses from named to silent.

**E. C#: extract the projection.** `asyncMembers` and `suspendStateFlowMembers`
(`CirClassTranslator.kt:679-785`) move out of `translateClass` into one `internal fun
suspendMembers(suspendMethods: List<KSFunctionDeclaration>, prefix: String, libraryName: String,
classifier, tracker, callableCatalog, context): List<CirMember>` that performs the ADR-068
StateFlow partition (`:530-534`) internally and sets `tracker.needsAsync`/`needsFlow`/
`needsStateFlow`/`needsSuspendStateFlow` exactly as `:544-550, :677` do. `translateClass` calls it
with its existing inputs (behaviour-preserving); `translateSealedClass` calls it per arm with the
arm's declared-only suspend members and `subPrefix`.

- `translateSealedClass` (`:1099-1108`) gains a `classifier: ForwardBridgeTypeClassifier` parameter
  (it has none today; `legacyRouteParameters` needs it) and its one call site
  `CirTranslator.kt:453` passes the classifier `translateClass` already receives (**Verified by
  reading**).
- `tracker.needsAsync = true` must be reached from the sealed path, or `NugetAsyncCallback`,
  `NugetThunks`, `NugetScopeNative` and `NugetJobNative` are never emitted (`CirTranslator.kt:610-612,
  673`, **Verified by reading**) and the rendered arm does not compile. Loud.

**F. C#: carry and render.** `CirSealedSubclass` gains
`asyncMembers: List<CirMember> = emptyList()` (the import/method pair cannot ride
`methods: List<CirMethod>`; the ordinary class carries the same pair on `companionMembers`) and
`hasSuspendMethods: Boolean = false`. `sealedSubclassBlock` (`CirSealedRenderer.kt:60-131`) renders
them after the plan methods, inside the same `indentNestedBody()` re-indent ADR-116 used:

```kotlin
subclass.asyncMembers.forEach { member ->
  append(buildString { renderMember(member, subclass.name) }.indentNestedBody())
}
```

`renderMember` (`CirClassRenderer.kt:439-449`) dispatches `CirDllImport` → `renderDllImport` and
`CirMethod` → `renderMethod` → `renderAsyncMethod` (`:498-499`), so the arm's async body is
byte-for-byte an ordinary class's, at +4 indent (**Verified by reading**; the ADR-116 property and
method arms already prove `indentNestedBody()` over `renderDllImport`/`renderMethod` output).

**G. C#: scope per arm.** When `subclass.hasSuspendMethods`:

- header `public sealed class Running : Job, IAsyncDisposable` (the base keeps
  `: IDisposable, INugetHandle`; `Done`, which has no suspend member, is unchanged);
- the `_scopeHandle` field and `GetOrCreateScope()` block, lifted out of
  `CirClassRenderer.kt:205-225` into a shared `renderScopeMembers()` both renderers call;
- the arm's hand-rolled `Dispose` + `Native_Dispose` import (`CirSealedRenderer.kt:114-125`) is
  replaced by `renderDispose(disposeImport, isAbstract = false, hasSuperClass = true,
  hasSuspendMethods = subclass.hasSuspendMethods)` inside `indentNestedBody()`, where
  `disposeImport` is a `CirDllImport(entryPoint = "${subclass.nativePrefix}_dispose", name =
  "Native_Dispose", parameters = [handle], returnType = "void")`. `renderDispose` already emits
  `public override void Dispose()` when `hasSuperClass` (the base declares `public abstract void
  Dispose()`), the scope cancel/dispose when `hasSuspendMethods`, and the `DisposeAsync` drain
  (**Verified by reading**, `:611-679`). Using it for **every** arm (not only suspending ones) keeps
  one dispose rule; the text of a non-suspending arm's `Dispose` changes to the
  `Interlocked.Exchange` form, and no test pins the old text (grep above).

**H. Legacy route recognition.** `ForwardAbiLegacyRoutes.add(CirSealedClass)`
(`ForwardAbiLegacyRoutes.kt:96`) additionally walks
`declaration.subclasses.flatMap { it.asyncMembers }` through the existing
`add(member, SUSPEND_METHOD)`. Recognition is structural (`CirMethod.isAsync`), never by
entry-point name, so `job_running_pause_async` needs no name rule (**Verified by reading**,
`:134-147`). This only feeds `ForwardAbiLegacyRoutesTest`; the ABI contract's `csharpLegacy`
scraper picks the arm's `[DllImport]` off the rendered text with no change (**Verified by reading**,
`ForwardAbiContract.kt:188-206`).

**I. Diagnostics text.** `NugetProcessor.kt:172-174` ("suspend members follow ROADMAP line 54") and
`ForwardDiagnostic.kt:568-571` ("non-suspend, non-Flow, non-generic") both hardcode `suspend` in
the `SEALED_SUBCLASS_UNROUTED` wording; both drop the word.

### Fixture (cross every seam once)

`test-library/.../issue115/JobSample.kt` (kdoc's `pause` bullet rewritten):

```kotlin
data class Running(val progress: Int) : Job() {
  /** suspend, no conversion: binds as Task<int> (was: absent, named). */
  suspend fun pause(): Int = progress
  /** suspend overload pair on an ARM: `_2` through sealedSubclassEntries + the post-process copy. */
  suspend fun pause(millis: Int): Int = progress + millis       // distinguishable from pause()
  /** suspend with String in and out: the UTF8 pair on the async route. */
  suspend fun resume(prefix: String): String = "$prefix$progress"
}
data object Idle : Job() {
  /** suspend on a data object arm: handle receiver, arm-prefixed export. */
  suspend fun nap(): String = "napping"
}
```

`cat/AsyncCatService.kt` (line 54, ordinary class; the class-method suspend fixture, so the
natural home; `AsyncFunctions.kt` is top level and does not number today):

```kotlin
suspend fun fetchCat(name: String, lives: Int): Cat   // overload of fetchCat(name); returns a Cat whose state differs observably
```

`cat/CatMoodTracker.kt` (line 54 names the StateFlow variant; separate C# `flatMap`):

```kotlin
suspend fun awaitMoodReport(prefix: String): StateFlow<String>   // overload of awaitMoodReport()
```

**Same-arity, same-wire-shape pair (gate addition).** Every pair above is arity-distinct, so an
implementation that numbered the entry point but left `CirDllImport.name` / `CirMethod.nativeName`
at `Native_FetchCatAsync` would still compile (different arities overload in C#) and would fail
only at the C symbol, loudly. The `Radio.play(Player)` / `play(Track)` shape is the one that pins
the extern-**name** numbering: two handle-typed parameters, one arity, same wire shape
(`IntPtr`), so an unnumbered extern name is CS0111 (the defect ADR-090's amendment found on the
plan route). Hosted in a **new small class** rather than `AsyncCatService`: the pair needs a second
exported handle type declared beside it, and `AsyncCatService`'s members are consumed by many
existing tests. New file `cat/AsyncCatSitter.kt`:

```kotlin
class Treat(val name: String)

class AsyncCatSitter {
  /** Same arity, same wire shape (two handles): pins Native_FeedAsync vs Native_Feed_2Async. */
  suspend fun feed(cat: Cat): String = "fed ${cat.name}"
  suspend fun feed(treat: Treat): String = "gave ${treat.name}"   // distinguishable result
}
```

Kotlin: `asynccatsitter_feed_async` / `asynccatsitter_feed_2_async`; C#: `Native_FeedAsync` /
`Native_Feed_2Async`, both `FeedAsync`. The integration test awaits both and asserts the second's
string.

**Base `open suspend fun` (for the declared-only assertion).** `Job` gains
`open suspend fun rest(): String = "resting"` that **no arm overrides**, so the declared-only
filter has something to filter: no arm may render `RestAsync`, and the Kotlin side may emit no
`job_running_rest_async`.

Not added: a same-name plain/StateFlow suspend pair (a consequence of one counter, stated above,
not a seam); a suspend overload pair on a `data object` arm (same counter path as `Running`'s).
The arm overload pair **is** a needed cell, not speculation: `sealedSubclassEntries` is its own
counter function and the post-process copy is the path that must preserve the number.

### Tests that flip

- `IntegrationTests/SealedSubclassMethodTests.cs:278-281`
  (`Pause_SuspendMemberOnASealedArm_IsAbsentUnderEitherSpelling`) inverts: `PauseAsync` present
  on `Job.Running`, both overloads, `Task<int>`; new runtime cells `await PauseAsync()` /
  `await PauseAsync(250)` asserting the **second overload's value**, `ResumeAsync("x")`,
  `Idle.NapAsync()`, `IAsyncDisposable` on `Running`/`Idle` and not on `Done`, cancellation via
  the token (the `SuspendFunctionTests.cs` shape).
- `SealedSubclassMethodTests.cs:402-404`
  (`Pause_SuspendMemberOnASealedArm_IsNamedAsAnUnsupportedCombination`) inverts to an absence
  assertion: no `issue115` entry for `Job.Running.pause`; `pickNested`'s `SKIPPED_UNSUPPORTED_TYPE`
  cell is unaffected.
- `Tier1EntryPointCollisionTest`'s `Radio` cell (ADR-117) **moves**: the overload pair becomes a
  green numbering cell in a new `Tier1SuspendMethodOverloadTest` mirroring
  `Tier1FlowMethodOverloadTest` (`radio_play_async` + `radio_play_2_async` on the Kotlin side,
  `Native_PlayAsync` + `Native_Play_2Async` in `Interop.cs`, one arm cell `job_running_pause_async`
  + `job_running_pause_2_async`). ADR-117's suspend-route owner tag stays exercised end-to-end by a
  re-shaped collision cell: `class Radio { suspend fun play(p: Player): Int }` against a top-level
  `suspend fun radio_play(): Int` in the same package. `toCName` is the identity apart from
  C-reserved names (**Verified by reading**, `Reserved.kt:25-28`), so the top-level route exports
  `radio_play_async` too; both builders tag their `FunSpec` with the declaration, so the message
  names `Radio.play(Player)` and `radio_play()`. As ADR-117's dispose cell does, assert the owner
  texts and the symbol, not the guard: the two imports differ in signature (`handle, scopeHandle`
  vs none), so the guard that fires is expected to be `csharpLegacy`'s
  `CONFLICTING_LEGACY_IMPORTS` (**Inferred**, not run; ADR-117 recorded that no Tier 1 cell reaches
  that guard today, so this cell would be the first).
- `ForwardSkippedCallableWarningTest.kt:135` does not flip: `SEALED_SUBCLASS_UNROUTED` remains a
  real-drop reason for Flow/lambda/generic members.
- `ForwardAbiLegacyRoutesTest`: one `CirSealedClass` with an `asyncMembers` pair expecting
  `SUSPEND_METHOD`.
- `FEATURES.md` sealed row: "a `suspend`, `Flow`-returning, lambda-parameter, or generic method on
  an arm is absent" loses `suspend`; the `suspend fun` row gains the overload and arm sentences.
  ADR-116 gets an amendment note pointing here; ROADMAP lines 39 (suspend half) and 54 close;
  the PR carries `Fixes #115`.
- Reflection assertions pinning declared-only (`SealedSubclassMethodTests.cs`):
  `typeof(Job.Running).GetMethod("RestAsync")` is null on every arm (`rest` is base-declared and
  not overridden), `typeof(Job.Running).GetMethod("DescribeAsync")` is null (`describe` is a
  non-suspend base member and must not surface as async under any spelling), and no
  `job_running_rest_async` / `job_idle_rest_async` entry point exists in the generated Kotlin
  (Tier 1 absence assertion).
- `SuspendFunctionTests.cs` (or a new `AsyncCatSitterTests.cs`): `await sitter.FeedAsync(cat)` and
  `await sitter.FeedAsync(treat)` return their own strings; reflection sees two `FeedAsync`
  overloads and two distinct private externs `Native_FeedAsync` / `Native_Feed_2Async`.

### File list

Kotlin half: `exports/SuspendFunctionExports.kt` (prefix, declared-only, suffix at `:105-106`),
`NugetProcessor.kt` (`:297-311` refused walk, `:1218` import gate, `:1336-1341` export loop, `:1633`
scope-helper gate, `:172` wording), `forward/ForwardCallablePlanner.kt` (`:1050` exemption),
`forward/ForwardDiagnostic.kt` (`:568` hint).
C# half: `cir/CirClassTranslator.kt` (extract `suspendMembers`, suffix on both fields, call from
`translateSealedClass`, `classifier` parameter), `cir/CirTranslator.kt:453` (pass classifier),
`cir/CirModel.kt` (`CirSealedSubclass.asyncMembers`, `hasSuspendMethods`),
`cir/CirSealedRenderer.kt` (render pair, scope members, `renderDispose` reuse, `IAsyncDisposable`
header), `cir/CirClassRenderer.kt` (lift `:205-225` into `renderScopeMembers()`),
`ForwardAbiLegacyRoutes.kt:96`.
Fixtures and tests: `JobSample.kt` (plus base `open suspend fun rest()`), `AsyncCatService.kt`,
`CatMoodTracker.kt`, new `cat/AsyncCatSitter.kt` (`Treat` + the same-arity pair),
`SealedSubclassMethodTests.cs`, `SuspendFunctionTests.cs` (overload cells),
`Tier1EntryPointCollisionTest.kt`, new `Tier1SuspendMethodOverloadTest.kt`,
`ForwardAbiLegacyRoutesTest.kt`; `FEATURES.md`, `ROADMAP.md`, `docs/adr/README.md`, ADR-116 note.

### Scope options, priced in files

| Scope | Source files | Fixtures / tests |
|---|---|---|
| Line 54 alone (Alternative 3) | 3: `SuspendFunctionExports.kt` (catalog parameter + suffix at `:105-106`), `CirClassTranslator.kt` (both `flatMap`s, both fields), `NugetProcessor.kt:1340` (pass the catalog) | `AsyncCatService.kt`, `CatMoodTracker.kt`; Radio cell re-shape, new `Tier1SuspendMethodOverloadTest`, `SuspendFunctionTests.cs` cells |
| Line 54 + arms (chosen) | 11: the three above plus `ForwardCallablePlanner.kt`, `ForwardDiagnostic.kt`, `CirModel.kt`, `CirSealedRenderer.kt`, `CirClassRenderer.kt`, `CirTranslator.kt`, `ForwardAbiLegacyRoutes.kt`, and three more `NugetProcessor.kt` sites (`:297`, `:1218`, `:1633`) | plus `JobSample.kt`, `SealedSubclassMethodTests.cs`, `ForwardAbiLegacyRoutesTest.kt` |
| `suspendStateFlowMembers` riding along | 0 extra: the same `CirClassTranslator.kt` extraction covers both `flatMap`s | one fixture line in `CatMoodTracker.kt` |

The StateFlow variant is not optional under line 54's wording ("`asyncMembers` and
`suspendStateFlowMembers`"), and leaving it out would fix the Kotlin half of a
`suspend ...: StateFlow` pair while its C# half still collides.

### Implementation instructions folded from the gate

1. **`renderDispose` reuse.** Reuse `renderDispose(...)` for **every** arm only if its rendered
   output for a *non-suspending* arm is byte-identical to today's hand-rolled block
   (`CirSealedRenderer.kt:114-125`); it is not expected to be (the `Interlocked.Exchange` form
   differs from the `if (_handle != IntPtr.Zero)` form, **Verified by reading**), in which case use
   `renderDispose` for **suspending arms only** and keep the hand-rolled block for the rest. The
   implementer reports which branch was taken in the ADR's implementation notes.
2. **Radio collision cell asserts the guard by name.** The re-shaped Tier 1 cell
   (`Radio.play(Player)` vs top-level `suspend fun radio_play()`) asserts
   `CONFLICTING_LEGACY_IMPORTS` **by name** in addition to `radio_play_async` and both owner texts.
   This closes ADR-117's recorded residual that no Tier 1 cell reaches the legacy-import-conflict
   guard through a real KSP round. If a different guard fires (**Inferred** which one does; not
   run), the implementer records the actual guard in the implementation notes and the assertion
   follows the observed guard, since the point of the cell is the reach, not a prediction.
3. **Sealed interfaces.** The implementer checks whether `sealedClasses`
   (`NugetProcessor.kt:723-724`, `rootSealedClasses + dependenciesIn(SEALED_CLASS)`) includes
   ADR-112's eligible sealed **interfaces**, and the Consequences below are completed with the
   answer: either a sealed-interface arm's `suspend fun` binds for free under this ADR (the
   planner's `sealedSubclassEntries` loop at `:510` runs off the same list, so the two halves agree
   either way), or it stays `SEALED_SUBCLASS_UNROUTED` and becomes a named ROADMAP line. One
   Tier 1 cell on an ADR-112 fixture (`Pulse`-style) pins whichever holds.

### Mechanism claims, labelled

- **Verified by execution** (repo, prior sessions): the suspend collision itself (`Radio` cell); the
  flow route's `_2` numbering (`Tier1FlowMethodOverloadTest`); nested `data class`/`data object`
  arms as `asStableRef` type arguments (sealed route on all CI targets).
- **Verified by reading** (this session, file:line above): planner numbers the `SUSPEND` skip before
  the structural `when`; post-process copies symbol and node; `overloadSuffix`'s lenient `""` path;
  `CirMethod.nativeName` and `CirDllImport.name` are independent fields; `renderAsyncMethod` calls
  `GetOrCreateScope()`, `_handle` and `method.nativeName`; the three Kotlin gates and the refused
  walk iterate `classes` only; `ForwardAbiContract.kotlin` filters to the C# set;
  `ForwardAbiLegacyRoutes` is structural and unconsumed by the contract; `toCName` identity;
  `translateSealedClass` has no classifier; no test pins `Native_Dispose(_handle)`.
- **Inferred, not run**: which guard the re-shaped collision cell trips (`CONFLICTING_LEGACY_IMPORTS`
  expected); that `renderDispose`'s `Interlocked.Exchange` body inside `indentNestedBody()` needs no
  indentation special case beyond what ADR-116's `renderMethod` reuse needed.
- **Not verified, said plainly**: `overloadSuffix` on a `SUSPEND` skip was not executed here. If
  wrong, the failure is loud (the existing collision), not silent, so it did not meet the mandatory
  spike bar; the first Tier 1 run of the implementing PR is the spike.

## Consequences

- Two `suspend` overloads (plain or StateFlow-returning) on one class or one arm bind as one C#
  overload set; the second's symbols are `${prefix}_${name}_2_async` / `Native_${Name}_2Async`.
- Every `suspend fun` a sealed arm **declares** binds as `${Name}Async` on the arm, with the arm
  gaining `_scopeHandle`, `GetOrCreateScope()`, `IAsyncDisposable`, `DisposeAsync`, and a
  scope-aware `Dispose`; a non-suspending arm's `Dispose` body changes text only.
- `SEALED_SUBCLASS_UNROUTED` no longer covers `SUSPEND`; the ADR-114 refused-parameter walk now
  names an arm's refused suspend member, as it does an ordinary class's.
- **Consumer-visible asymmetry, stated plainly.** An arm that declares a `suspend fun` is
  `IAsyncDisposable`; the sealed **base** is not (it stays `: IDisposable, INugetHandle`), and an
  arm with no suspend member (`Done`) is not either. A consumer holding a `Job` must pattern-match
  to the concrete arm before `await using` / `DisposeAsync()`:

  ```csharp
  Job job = JobSample.AnyJob(40);
  if (job is Job.Running running) await running.DisposeAsync();   // drains the arm's scope first
  else job.Dispose();                                             // sync path, unchanged on every arm
  ```

  Synchronous `Dispose()` through the base still works on every arm and, on a suspending arm,
  cancels and disposes the scope before `Native_Dispose` (the ordinary-class `Dispose` shape).
  This is the same asymmetry an ordinary class hierarchy already has (`CirClassRenderer.kt:196`:
  only a class with suspend members declares `IAsyncDisposable`), surfaced here at the base/arm
  seam. Alternative 2 (scope on the base) was rejected precisely because putting
  `IAsyncDisposable` on the base would advertise `DisposeAsync` on arms that never suspend.
- Sealed interfaces (ADR-112): **an eligible sealed interface's arms get `suspend` members for free
  through the same `sealedClasses` list.** `rootSealedClasses` filters `isEligibleSealedType()`,
  which is `isSealed && (classKind == CLASS || isEligibleSealedInterface())`
  (`ForwardClassMembership.kt:70-76`), so an eligible sealed interface is in the very list the new
  Kotlin export loop, both widened gates, the refused-parameter walk and `translateSealedClass`
  iterate; an *ineligible* one stays on the ADR-040 interface route, where it was before. No ROADMAP
  line. Answered in the implementation notes below and re-confirmed by
  [ADR-124](124-flow-route-sealed-arm-owners.md), which asked the same question of the flow route
  and pinned it with a Tier 1 cell.
- Pre-existing, noticed, **not fixed** here, each a ROADMAP candidate with its file:line (all
  **Inferred** from reading unless marked):
  1. Async members ride `companionMembers` and bypass `emitCsharpSignatureCollisions`
     (`CirClassTranslator.kt:1013`, `:1195`): two suspend overloads differing only in reference
     nullability render CS0111 in the generated file instead of failing the round.
  2. `asyncMembers` maps a `suspend fun` returning plain `Flow<T>` to `Task<Flow>`, an undefined C#
     type (ADR-068's own note at `:527-529`); ROADMAP line 118 per ADR-068's summary; arms inherit it.
  3. Ordinary-class asymmetry: the Kotlin builder reads `getAllFunctions()` (`:79`) while the C#
     side is `isForwardMemberOf` (`CirClassTranslator.kt:519`), so an inherited suspend member emits a dead
     `${sub}_${name}_async` export that the contract's Kotlin filter drops silently. Harmless today.
  4. `asyncMembers`' return spelling is the simple name through `KOTLIN_TO_CSHARP_PARAM`
     (`:683-693`): a suspend method on an **ordinary** class returning a *nested* arm (`Job.Running`)
     renders `new Running(resultPtr)`, unresolvable outside the base (ADR-009's nested amendment
     fixed plan routes only). Inside the arm itself it resolves, so the fixture does not hit it.
  5. The interface route has no overload counter (`:815-826`), a standing Phase 4 item.
  6. The top-level suspend route (`addSuspendFunctionExports`, `SuspendFunctionExports.kt:57-58`)
     composes `${cname}_async` with no suffix and has no planner entry to read one from
     (**Verified by reading**); two top-level `suspend` overloads in one package collide exactly
     as line 54's class case did. **Gate decision: a ROADMAP line, not built here.** The fix is
     ADR-095's per-`(package, name)` counter applied to the suspend top-level route, which first
     needs a planner entry (a `SUSPEND` skip in `topLevelEntry`) for `overloadSuffix` to read.
- Deferred, each a ROADMAP line: **top-level suspend overload numbering** (item 6, gate-decided);
  `Flow`-returning, lambda-parameter and generic members on a sealed arm (line 39's other halves,
  still `SEALED_SUBCLASS_UNROUTED`); `SUSPEND_CALLBACK_PROTOCOL` on an arm; suspend overloads on
  interfaces (item 5); the four remaining pre-existing items above (1-4) as ROADMAP candidates
  with their file:line; structural (non-scraped) ABI coverage of the arm's async imports (the
  existing Phase 3 item); Swift Export comparison not consulted this session (budget spent on the
  spikes above).
## Implementation notes (2026-09-09, `ir/suspend-route-sealed-arms-and-overloads`)

Answers to the three instructions the gate folded in, plus the claims execution moved.

1. **`renderDispose` reuse: suspending arms only.** As the gate expected, `renderDispose`'s
   `Interlocked.Exchange` body is not the arm's shipped `if (_handle != IntPtr.Zero)` block, so
   reusing it for every arm would have rewritten the dispose text of every non-suspending arm in the
   repository for no behavioural gain. `sealedSubclassBlock` branches on
   `subclass.hasSuspendMethods`: a suspending arm renders `renderDispose(disposeImport,
   hasSuperClass = true, hasSuspendMethods = true)` (scope cancel/dispose, then `Native_Dispose`,
   plus `DisposeAsync` and the drain closure) inside `indentNestedBody()`; every other arm keeps its
   hand-rolled block byte for byte.
2. **The re-shaped Tier 1 collision cell trips `DUPLICATE_CSHARP_IMPORT`, not
   `CONFLICTING_LEGACY_IMPORTS`.** The ADR's Inferred prediction above is **wrong, verified by
   execution**: `Radio.play(Player)` against a top-level `suspend fun radio_play()` fails with
   `Forward ABI duplicate C# import for radio_play_async`, naming both owners with their own
   `file:line`, even though the two imports differ in signature. The assertion follows the observed
   guard per the gate's instruction. **ADR-117's residual therefore stays open**: no Tier 1 cell
   reaches `CONFLICTING_LEGACY_IMPORTS` through a real KSP round, and this ADR did not close it.
3. **Sealed interfaces (ADR-112) get `suspend` arms for free.** `rootSealedClasses`
   (`NugetProcessor.kt:555-559`) filters `isEligibleSealedType()`, which is
   `isSealed && (classKind == CLASS || isEligibleSealedInterface())`
   (`ForwardClassMembership.kt:70-76`), so an eligible sealed interface is in the same
   `sealedClasses` list the new Kotlin export loop, both widened gates, the refused-parameter walk
   and `translateSealedClass` all iterate. An eligible sealed interface's arm binds its `suspend
   fun` under this ADR with no further change, and an *ineligible* one is on the ADR-040 interface
   route, where it was before. No new ROADMAP line. (Not pinned by a dedicated Tier 1 cell: the
   list identity is the mechanism, and one list feeds both halves.)

Two more claims settled by execution:

- **The planner numbers a `SUSPEND` skip on an arm, and the sealed post-process preserves it.**
  Confirmed end to end: `Job.Running.pause(millis)` renders `job_running_pause_2_async` /
  `Native_Pause_2Async`. The "Not verified, said plainly" entry above is now Verified by execution.
- **`CirSealedSubclass.hasSuspendMethods` is `asyncMembers.isNotEmpty()`, not the ordinary class's
  `getAllFunctions().any { SUSPEND }` scan.** The ordinary-class spelling (`CirClassTranslator.kt`,
  `CirClass.hasSuspendMethods`) is neither declared-only nor ADR-114-refusal-aware, so on an arm it
  would have given `Job.Done` a scope (base-declared `rest`) and given a refused-parameter-only arm
  an `IAsyncDisposable`, `_scopeHandle` and `DisposeAsync` that no method on it uses. Deriving the
  flag from what actually projected keeps the rendered surface and the emitted externs in step.

Sites that changed beyond the ADR's file list: none. `warnRefusedLegacyRouteParameters` gained a
`sealedClasses` parameter (the ADR named the walk but not the signature), and the scope-member lift
became two functions (`renderScopeHandleField`, `renderGetOrCreateScope`) rather than one, because
the ordinary class renders the field and the method at different points in its body.

