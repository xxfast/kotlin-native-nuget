# ADR-162: Per-declaration error containment: one build names every offending declaration, located, by kind

## Status

Accepted, 2026-09-22.

## Context

The forward pipeline holds about 270 raw `error(...)`/`require(...)`/`check(...)` sites (a census in
the research memo, `docs/research/roadmap/per-declaration-error-containment.md`), 36 of them
`else -> error` arms of a `when` over the `BridgeType`/`ForwardPlanSkipReason` sealed models. Every
historical instance of one firing on legal Kotlin (issue #52, ADR-080, ADR-081, ADR-097, the
`fun dispose()` hazard) was an "invariant" that a `when` had not yet learned about a new variant or
route. Before this ADR, the first such failure aborted `NugetProcessor.process()` with one unlocated
`e: [ksp] java.lang.IllegalStateException: ...` line, naming one declaration. Every other offending
declaration on the same API surface stayed invisible until the author fixed that one and built again.
Nothing named a Kotlin file or line, and nothing carried a `[nuget:ERROR_*]` kind a consumer could
grep for.

Two smaller, related gaps were folded into the same fix because they sit on the same path and the
research spikes needed to touch the same files to close them:

- `class Closer { fun dispose() {} }` fired the generic `ERROR_C_ENTRY_POINT_COLLISION` rather than
  the more specific `ERROR_CSHARP_SIGNATURE_COLLISION`: the generated `Dispose()` every handle class
  declares is renderer-owned, not a `CirMethod` in the list ADR-034's signature-collision guard
  groups, so an authored `dispose()` sailed past that guard and the pair surfaced two phases later,
  as two owners of one C entry point.
- `enumParamsUnsupported` (`CirFunctionTranslator`) was the last fatal forward diagnostic that still
  lived outside `ForwardDiagnosticKind`: a bare `logger.error` with no `[nuget:KIND]` tag, so it was
  the one build failure from this processor nobody could grep for by kind.

### Spikes, not argument

Four claims below are load-bearing and were checked by injecting a fault into a scratch worktree
(detached at `651cb7d0`, Windows 11, Gradle 9.1.0, `--console=plain`, every edit reverted afterwards),
not inferred from reading:

1. **A raw, uncaught `error(...)` really does behave as described.** Two matching classes, one
   injected `error("SCRATCH raw throw for ...")` in the C# half's per-declaration loop: one unlocated
   line, first declaration only, no stack trace at default verbosity, the message repeated as
   Gradle's own headline. The second class never appears in the log.
2. **`logger.error(message, symbol)` IS visible on a real console, located, for every failure of the
   round.** Two classes each declaring `fun dispose()`: both `ERROR_C_ENTRY_POINT_COLLISION` lines
   printed, each prefixed `e: [ksp] <path>:<line>: `, the build failed with
   `KSP failed with exit code: PROCESSING_ERROR`. This closes the "is `ERROR_*` visible at all" question
   ADR-100 left explicitly deferred (its "Deferred: `ERROR_*` visibility" note) and removes the case
   for the aggregate-throw alternative below.
3. **A contained failure at plan time must still leave a catalog entry.** Omitting the catalog entry
   for two functions and reporting them with a bare `logger.error` right after the planner ran: the
   Kotlin emitter's own `Forward callable catalog has no entry for ...` throws before the fatal-
   diagnostic gate, masking every later failure in the same round and replacing Gradle's headline
   with a *different*, secondary exception. Leaving a `ForwardCallableCatalogEntry.Skipped` entry
   under the declaration's own symbol instead: all four planted failures (two plan-time, two late)
   were reported in the same round, with the correct `PROCESSING_ERROR` headline.
4. **A late (post-plan) contained failure never reaches the ABI contract.** Catching a raw throw in
   the C# half's per-declaration loop, reporting it, and continuing the loop: the existing
   `NugetProcessor.kt` fatal-diagnostic gate returns before `ForwardAbiContract.assertMatches` runs.
   `Interop.cs` (the C# half) is written with the failed declarations missing; `CNameExports.kt` (the
   Kotlin half) is never written at all, so `packNuget` never runs and no inconsistent package ships.

## Decision

**Fatal, collect all, located, greppable**, on the single sink ADR-064 already established.

### The `guarded` boundary

`forward/ForwardDiagnosticGuard.kt` adds `guarded(declaration, node, logger, block)`: runs `block()`,
catches `Exception` only (never `Throwable` — `OutOfMemoryError` and `StackOverflowError` are not
facts about one declaration and must still abort the whole round), and on a catch emits one
`ERROR_INTERNAL_GENERATOR_FAILURE` diagnostic naming the declaration, the exception's class and
message, and a hint that names the one line that unblocks the author's build today:
`exclude("<qualified name>")` in `nuget { publish { } }`, plus an ask to report the failure with the
whole message. The block returns `null` on a catch, so the caller's loop can skip this one
declaration and keep going. Deduped on `(declaration, exception detail)` for the round
(`ForwardInternalFailures`, reset with `ForwardDiagnosticSink.reset()`), because the same declaration
is walked by more than one of the boundaries below and would otherwise report the same bug twice.

The boundary is deliberately generic rather than a per-site classification. Classifying today's ~270
sites one by one cannot cover the one that fires next, by construction: it is the site nobody has
classified yet. Converting a *known* user-reachable site into its own named `SKIPPED_*`/`ERROR_*` kind
(as issue #58 did for #52's `No C# property type for SpecializedProtocol(...)`) is still the right
move per site when one is found; `guarded` is the backstop for every site that has not been found yet.

### Three installation families

A single catch around `process()` cannot make anything continue past the first failure, because there
would be nothing left to loop over. The boundary is installed at every place a per-declaration loop
already exists:

1. **Plan time.** `ForwardCallablePlanner`'s per-declaration lambdas and the equivalent property
   planner loops route a caught failure through a new `ForwardPlanSkipReason.INTERNAL_FAILURE`, which
   `ForwardPlanSkipReason.toDiagnosticKind()` maps to `ERROR_INTERNAL_GENERATOR_FAILURE` (the one
   reason that maps to an `ERROR_*` kind by construction: it is not a "cannot express this" decision,
   it is the generator failing at something it was meant to handle). The failure leaves a
   `ForwardCallableCatalogEntry.Skipped` entry under the declaration's own symbol rather than omitting
   it — Spike 3 above is why: an omitted entry crashes the Kotlin emitter before the gate and hides
   every other failure of the round.
2. **Kotlin half.** `generateCNameWrappers` (`NugetProcessor.kt`) gets a local `guardDeclaration(...)`
   wrapper over `guarded`, applied at every per-declaration loop it owns: top-level functions, generic
   functions, classes (exports and companions), enums, sealed classes, objects, value classes,
   reachable interfaces, the interface bridge factory plan, suspend functions, class suspend methods,
   the four sealed-arm legacy routes (suspend, Flow, lambda parameter, stored-callback and
   interface-bridge pairs, each guarded on the arm — the declaration the author would actually change),
   properties, extension functions and extension properties. The four interface-planning loops in
   `process()` (reachable entries, reachable properties, the ADR-113 declaration catalog's entries and
   properties, unexported-supertype properties) are guarded per interface.
3. **C# half.** Each per-declaration `forEach` in `CirTranslator.translate(...)` is guarded the same
   way. `CirRenderer.render(...)` stays a single, ungrounded call: a renderer throw has no `KSNode` in
   hand without threading source nodes through the whole CIR model (see Deferred scope), so it is
   caught only by the whole-round guard below, with `symbol = null`.

A final whole-`process()` catch, its own `try`/`catch` in `process()` that emits directly with
`node = null` (it does not go through `guarded` and does not dedupe; see the
[2026-10-05 amendment](#amendment-2026-10-05-the-failure-arm-and-the-whole-round-catch-are-tested)),
is the last resort for work no declaration owns (the ADR-066 closure, the post-passes, the one
`render()` call), so nothing escapes unlabelled even if a future change adds a phase between these three families.

### Q1: fatal everywhere, not a skip-and-ship

**This corrects the ROADMAP line's own wording.** The line said the fix should let the build
"continue, as the `SKIPPED_*` diagnostics do." Human decision, 2026-09-22: **fail everywhere**, with
the `exclude(...)` hint as the unblocker, for every one of the three families above. Only the plan-time
family is structurally safe as a true skip (F2 in the memo): a plan-time failure happens before
anything downstream has read the catalog entry, so a `Skipped` entry is consistent with every later
consumer, exactly like the `SKIPPED_*` reasons already there. A late (Kotlin-half or C#-half) failure
is not: `ForwardAbiContract.assertMatchesPlan` requires every planned callable's native exports to
exist on *both* halves, so dropping a declaration from only one half at that point would either trip
the `missing`/`mismatch` generator-bug `require`s if they ever ran, or (as Spike 4 showed) simply never
reach them because the round already failed — either way, two different policies for one kind
(fatal at plan time, silently absent later) is worse than one policy applied everywhere. The existing
`hasFatalDiagnostic` gate already stops the round before `CNameExports.kt` or the ABI contract checks
run, so a dependent of a failed declaration needs no treatment at all: nothing downstream of the gate
is reachable.

### Q2: `logger.error` visibility, closed

Spike 2 closes what ADR-100 left explicitly deferred. `logger.error(message, symbol)` is visible on a
real Gradle console (`e: [ksp] <path>:<line>: [nuget:KIND] ...`), for every failure of the round, not
only the first. The aggregate-throw alternative the memo's pass 1 proposed as a belt-and-braces
fallback is therefore dropped: it would have solved a visibility problem that does not exist, and it
would have changed the seam of every existing `ERROR_*` Tier 1 cell. The Tier 1 harness
(`Tier1Harness.kt`) needed no change either: `RecordingKSPLogger` already observes a caught,
re-reported failure the same way it observes any other `logger.error`; only an *uncaught* exception
propagates straight to JUnit, and nothing in this design leaves one uncaught within `process()`.

### `fun dispose()` becomes a signature collision

`emitCsharpSignatureCollisions` (`CirClassTranslator.kt`) now accepts an optional
`reservedSignatures: Set<List<String>>`, passed by its three call sites (the ordinary class route,
the sealed base, and the sealed arm) as `HANDLE_RESERVED_SIGNATURES` — today just `Dispose()` (zero
parameters), because that is the only renderer-owned member an author's own declaration can collide
with. A zero-parameter Kotlin `dispose()` now groups against that reserved signature the same way it
would against another `CirMethod`, and fires `ERROR_CSHARP_SIGNATURE_COLLISION` naming the class (or
arm) and "generated `Dispose`" as the two owners, with the CS0111 wording ADR-034 already uses,
instead of the collision surfacing two phases later as a generic
`ERROR_C_ENTRY_POINT_COLLISION` over the mangled `closer_dispose` symbol. It fires during `translate`,
before the fatal-diagnostic gate, so the ABI contract's duplicate-entry-point guard never runs for
this shape at all. Verified for a class, a sealed base's own `dispose()`, and a sealed arm's own
`dispose()` (which collides with the *inherited* `Dispose()`) — both offending declarations of the
sealed fixture are reported in one round, which is the containment claim itself.

### `enumParamsUnsupported` gets a kind

`CirFunctionTranslator`'s `enumParamsUnsupported` (nine call sites, one per return-shape branch that
cannot cast an enum parameter's ordinal back down) now routes through `ForwardDiagnosticSink` as
`ERROR_UNSUPPORTED_ENUM_PARAMETER_ROUTE`, the same node, the same severity, the same `emptyList()`
behaviour it already had. After this change the sink (`ForwardDiagnosticSink.emit`) is the only
`logger.error(` call left in `nuget-processor/src/main`. Downgrading this to a `SKIPPED_*` (the
function is simply absent from the generated API, nothing else depends on it) was considered and
deferred, not decided against; see Deferred scope.

### The re-emitted console line gets a location

`ForwardDiagnostic.format()` is **not** reordered. It already ends with a trailing
`\n    at <path>:<line>` line, and KSP's own Gradle logger prefixes every `logger.warn`/`logger.error`
call with `<path>:<line>: ` before it ever reaches the console — reordering `format()` to lead with the
location would print it twice on that path (`e: [ksp] <path>:<line>: <path>:<line>: [nuget:...]`).
Instead, `ForwardDiagnosticRecord` and the `NugetDiagnostics.json` writer
(`ForwardDiagnosticsFile.kt`) gain two additive fields, `file` and `line`, carrying the same
`KSNode.location` `format()` already appended as a trailing string, in a shape a second consumer can
use. Both fields are strings in the JSON — `renderForwardDiagnosticsJson`'s reader walks quoted tokens
key, value, so a bare JSON number would shift every following key onto the wrong value — and both are
omitted, not written as `null`, when the diagnostic has no location (a scope-level diagnostic like
`SKIPPED_ALL_DECLARATIONS`). `NugetReportDiagnosticsTask` (`nuget-plugin`) is the one side that
composes the leading `<path>:<line>: ` shape, in `ForwardDiagnosticEntry.consoleLine()`, since it is
the side with no upstream prefix already fighting it: a `nugetReportDiagnostics` re-emit line now
reads `C:/src/Tag.kt:12: [nuget:KIND] ...`, the kotlinc/KSP shape an IDE build window linkifies. Both
fields are optional on read, so a `NugetDiagnostics.json` written by an older processor still parses.

## Alternatives Considered

- **True skip-and-ship for every family** (`SKIPPED_INTERNAL_FAILURE`, warn and omit the declaration):
  consistent only at plan time. For a late failure it breaks the ABI contract's cross-half
  requirement and ships a silently smaller API because of a generator bug, not an author decision.
  Rejected by the Q1 human decision above.
- **One catch around `process()`, as the ROADMAP line's own wording suggested**: gives one label but
  no location and no "keep going", so the build still reports one failure per run — the same defect
  this ADR exists to fix, just with a kind attached.
- **Convert every user-reachable throw site into a named skip, one by one**: the right move per site
  once found (as issue #58 did for #52), but cannot cover the site nobody has classified yet, by
  construction. Complementary to `guarded`, not a substitute for it.
- **Aggregate throw of every formatted `ERROR_*` after the gate**, as a visibility belt-and-braces:
  Spike 2 shows the visibility problem it would solve does not exist, and it would have changed the
  seam of every existing `ERROR_*` Tier 1 cell for no behavioural gain.
- **Omitting a failed declaration from the catalog at plan time**: Spike 3 shows the Kotlin emitter
  throws on the missing entry before the gate, masking every other failure of the round.
- **Reordering `ForwardDiagnostic.format()` to lead with the location**: doubles the prefix KSP's own
  Gradle logger already adds on the `e:`/`w:` path (Spike 2).
- **Gradle Problems API for the re-emit**: still `@Incubating` in Gradle 9.x; not adopted now. The
  additive `file`/`line` fields prepare for it later without committing to it today.

## Consequences

- A library author whose API trips a generator invariant now gets one build that names every
  offending declaration, at its own `file:line`, with a greppable `[nuget:ERROR_INTERNAL_GENERATOR_FAILURE]`
  kind and an `exclude(...)` hint, instead of one unlocated stack-trace line for the first occurrence
  only. The build still fails — nothing inconsistent ever ships because of a generator bug.
- `fun dispose()` on a class, a sealed base, or a sealed arm now reports
  `ERROR_CSHARP_SIGNATURE_COLLISION` (CS0111 wording, naming the method and the generated `Dispose`)
  instead of the generic `ERROR_C_ENTRY_POINT_COLLISION` two phases later. `fun close()` is unaffected.
- `enumParamsUnsupported`'s failure is now `ERROR_UNSUPPORTED_ENUM_PARAMETER_ROUTE`, greppable by kind
  for the first time; behaviour (which functions are refused, and how) is unchanged.
- A `nugetReportDiagnostics` console line now leads with `<path>:<line>: ` when the diagnostic has a
  location, in addition to the existing trailing `at <path>:<line>` inside the message body.
- No fixtures were added to `test-library/`: an `ERROR_*`-triggering declaration there would break
  `packNuget` for every other feature's fixtures in the same module (ADR-117's own reasoning). Every
  new test is Tier 1 in-process (`Tier1EntryPointCollisionTest`'s two new dispose cells,
  `Tier1EnumParameterRouteTest`) or a JVM unit test of the guard itself
  (`ForwardDiagnosticGuardTest`), since no legal Kotlin shape shipped in this repository reaches a raw
  `error(...)` today — every historical one (#52, ADR-080, ADR-081, ADR-097) has since been fixed into
  a named skip, so the guard's own containment behaviour has no live reproducer and is pinned by
  exercising `guarded(...)` directly with an injected failure.
- `ForwardDiagnostic.kt`'s own comment claiming these `KSPLogger` lines "reach no console today" was
  wrong and is corrected: they do, located, for every failure of a round. What ADR-100 actually
  measured and still holds is narrower — a normal, unchanged `packNuget` usually does not run the KSP
  task at all (`FROM-CACHE`, then `UP-TO-DATE`), so on most builds there is nothing here to see, and
  `NugetDiagnostics.json` plus its Gradle re-emit stay the channel a consumer of a cached build
  actually gets. This closes ADR-100's own "Deferred: `ERROR_*` visibility" note.

## Deferred scope

- **True skip-and-ship for a late (Kotlin-half or C#-half) failure.** Would need the failure folded
  back into the catalog and every downstream consumer (interface reachability, the ADR-066 closure,
  both halves) recomputed against the smaller catalog. Not attempted here; today's answer is fatal
  everywhere (Q1).
- **Threading a `KSNode` into the CIR model** so a `CirRenderer` throw can name its own declaration
  instead of falling through to the nodeless whole-round guard. The renderer sites are also the least
  user-reachable family surveyed in the research memo's census.
- **Downgrading `ERROR_UNSUPPORTED_ENUM_PARAMETER_ROUTE` to a `SKIPPED_*`** (the function is simply
  absent, nothing else depends on it, and the translator already returns `emptyList()`). Recorded as a
  follow-up rather than decided against; new ROADMAP line.
- **Gradle Problems API adoption** for the re-emit, and an opt-in `failOnSkippedDeclarations`
  (ADR-100's own deferred item), both of which the additive `file`/`line` JSON fields prepare for
  without committing to either.
- **A Windows-only discrepancy in the number of `w: [ksp] ...` warning lines** on the console at
  default verbosity, found incidentally by the research spikes. Explained by
  [ADR-100's 2026-10-05 amendment](100-forward-diagnostic-delivery.md#amendment-2026-10-05-the-ksp-console-channel-works-it-printed-every-warning-twice):
  not Windows-specific, and the lines were duplicates of the re-emit, now fixed.
- **The `fun dispose()` collision's own location** (closed by the 2026-10-05 amendment below) was reported at the containing class, not the
  offending member: `emitCsharpSignatureCollisions` receives the container's `KSNode`, and giving it a
  per-member node would change the shared guard signature for every one of its producers (classes,
  sealed bases, sealed arms, objects, file classes, extensions). Recorded as its own new ROADMAP line.
- **No test covered the failure arm of the Kotlin-half guards or the whole-round catch.** No legal
  Kotlin shape shipped in this repository reaches either. Closed by the
  [2026-10-05 amendment](#amendment-2026-10-05-the-failure-arm-and-the-whole-round-catch-are-tested).

No new handle kind and no new marshalling path are introduced anywhere in this ADR, so it adds no
`LiveHandleTests.cs` row.

## Amendment (2026-10-05): the `fun dispose()` collision is located at the member

An authored `fun dispose()` that collides with the generated `Dispose()` now reports
`ERROR_CSHARP_SIGNATURE_COLLISION` at the `dispose()` function's own `file:line`, not at the class
header. This holds for an ordinary class, a sealed base, and a sealed arm. The message text is
unchanged.

**Rule.** A collision against a `HANDLE_RESERVED_SIGNATURES` entry is located at the member that
claims the reserved signature. `emitCsharpSignatureCollisions` and `emitCsharpSignatureCollisionsOf`
take an optional `symbolFor: (List<String>) -> KSNode?`, keyed by the collision's signature in the
guard's own encoding and defaulting to the container's `symbol`. This is the `symbolFor` pattern
`emitMemberNameCollisions` already uses. Only the three handle sites (class, sealed base, sealed
arm) pass a lookup, `reservedMemberSymbol(container)`: the first zero-parameter function declared on
that container whose rendered C# name is the reserved name, so a `@CSharpName("Dispose")` member is
found as well as `dispose`. When no such function is declared on the container, for example because
the member is inherited, the lookup returns the container, so the location degrades to the old
behaviour instead of vanishing. The other call sites (objects, interfaces, file classes, extension
groups) are source-compatible and unchanged.

**Still at the container.** Ordinary two-member collisions (two constructors, two methods with one
rendered signature) keep reporting at the container. `CirMethod` carries no declaration node, and a
class site's method list is assembled from four sources (planned, companion, async, flow), so a
signature to declaration map would need threading through each of them. The new `symbolFor`
parameter is the hook a later item can use. The earlier Deferred scope bullet about the dispose
location is closed by this amendment; the `CirMethod` node threading above remains deferred.

**Evidence.** Verified: `Tier1EntryPointCollisionTest` asserts the located line for the class cell
(`Closer.Dispose` at the `fun dispose()` line, not the `class` line) and for both sealed cells
(`Feeding.Dispose` and `Feeding.Ready.Dispose`, each at its own `fun dispose()` line), using an
`endsWith` check on the trailing `Fixture.kt:<line>` so a longer line number cannot match by
prefix. Not covered by a cell: the `@CSharpName("Dispose")` match and the inherited-member fallback
follow from the lookup's code only (inferred).

As with the original change, no new handle kind or marshalling path is introduced, so there is no
`LiveHandleTests.cs` row.

## Amendment (2026-10-05): the failure arm and the whole-round catch are tested {id="amendment-2026-10-05-the-failure-arm-and-the-whole-round-catch-are-tested"}

The Deferred-scope item "no test covers the failure arm of the Kotlin-half guards or the whole-round
catch" is closed. Neither is reachable from legal Kotlin, so both tests drive the real processor
through a seam that production never uses.

**Reading of the rule.** "A shipped test may not inject a throw" means no planted throw in
`src/main`, which is what the research spikes did. A test-only collaborator passed through an
existing or defaulted seam is allowed: the throw lives in `src/test`, and production behaviour does
not change.

**The whole-round catch is not a `guarded` call.** `NugetProcessor.process` wraps `processRound` in
its own `try`/`catch (Exception)` and emits one diagnostic directly, so it has no dedupe and
`ForwardDiagnosticGuardTest` never covered it. It now reports through
`wholeRoundFailureDiagnostic`, whose hint differs from the per-declaration one. The old hint told
the author to `exclude("this Kotlin module")`, which is not an exclude anyone can write. It now says
this is a bug in the bridge generator, that no single declaration owns it so there is nothing to
exclude, and to report the failure with the whole message, which carries its cause. The
per-declaration hint is unchanged.

**Whole-round test (`Tier1WholeRoundGuardTest`, zero production change).** A test provider wraps the
injected `CodeGenerator` and throws `IOException` from the `Interop.cs` write, a failure a real
build can produce (full disk, locked file). That write is inside no per-declaration guard and runs
before the fatal gate. Verified: the failure is reported exactly once as
`ERROR_INTERNAL_GENERATOR_FAILURE` labelled `this Kotlin module`, with no source location and no
`exclude(...)` advice; nothing escapes `process()`; neither `Interop.cs` nor `CNameExports.kt` is
written; the KSP run ends `PROCESSING_ERROR`. The test is not vacuous: narrowing the catch let the
exception escape to JUnit, and with the catch removed KSP2's `execute()` rethrows to the caller
rather than routing to `logger.exception`. That settles which of the two an uncaught `Exception`
does.

**Kotlin-half test (`Tier1KotlinHalfGuardTest`, one defaulted seam).** `NugetProcessor` takes a new
constructor parameter `declarationStep: (KSDeclaration) -> Unit = {}`. `guardDeclaration` calls it
first inside `guarded`, at every one of its call sites (20, including the interface bridge-plan
guard). It is a no-op in every real build. A test passes a step that throws for declarations named
`broken*`, with one broken and one healthy declaration for each kind of loop. Verified: each broken
declaration is reported once per guard it passes through, named and located at its own source; the
healthy declaration after it in the same loop is still reached and reports nothing; nothing falls
to the whole-round catch; `CNameExports.kt` is not written; the run ends `PROCESSING_ERROR`.
`Interop.cs` is still written, because the C# half is generated ahead of the gate and its
translators do not call the step. The step throws a fresh message on every visit so the guard's
dedupe cannot fold two installations into one report.

**Still unproven.** The step reaches only `guardDeclaration`. Not covered by a test of the failure
arm: the direct `guarded(...)` calls in the interface-planning loops of `processRound`, the C#
translator guards in `CirTranslator`, and the planner's internal guard (`planOrSkip`). A future
per-declaration loop that calls neither guard is also invisible to the test, since the step only
proves the sites that call it. The `Error` path (an `Error` is deliberately not caught) is not tested
end to end.
