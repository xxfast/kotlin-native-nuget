# Contain raw planner/renderer `error(...)` per declaration, report every one with a location, in one build

- ROADMAP: line text as of 2026-09-21 (Phase 5, `ROADMAP.md:88`): "Any raw `error(...)` thrown out of a planner or renderer aborts KSP at the first occurrence with no source location, so a large API surface with several such shapes surfaces them one build at a time with nothing to grep for."
  Folded in: `ROADMAP.md:87` ("`fun dispose()` colliding with the generated `Dispose` fires the generic `ERROR_C_ENTRY_POINT_COLLISION` rather than the more specific `ERROR_CSHARP_SIGNATURE_COLLISION`."), `ROADMAP.md:58` ("The ADR-100 console re-emit calls `logger.warn(entry.message)` with a single string argument, so the `at <file>:<line>` suffix ... is never attached as Gradle's own source-location metadata"), `ROADMAP.md:236` ("One fatal forward diagnostic still lives outside `ForwardDiagnosticKind`.").
- Researched: 2026-09-21, two passes. Pass 1: about 15 of 20 minutes, source and web reading only. Pass 2 (same day, about 14 of 25 minutes): the spikes pass 1 skipped, run in a scratch worktree at `651cb7d0` on Windows 11, Gradle 9.1.0, `./gradlew :test-library:kspKotlinMingwX64 --console=plain` at default verbosity, plus one scratch Tier 1 cell. See "Spikes run (2026-09-21)". Claims are now **verified by reading**, **verified by spike** (console output quoted), or **inferred**. Two pass-1 recommendations were contradicted by a spike and are corrected below (dated notes in F2, Recommendation 1a, 3 and 6).
- Restatement: forward direction (Kotlin declares, C# consumes), generator-only. A library author whose API trips a generator invariant gets one build that names every offending Kotlin declaration with `file:line` and a greppable `[nuget:ERROR_*]` kind, instead of one unlocated `e: [ksp] java.lang.IllegalStateException: ...` line for the first one only (corrected 2026-09-21: pass 1 said "a bare JVM stack trace"; the spike shows no stack trace at default verbosity, see F4). The build still fails; nothing inconsistent ships.
- Verdict: fix. ADR needed (next free number is 158 as of this checkout, other parallel memos may claim it first); not drafted here.

## Findings

### F1. Phase structure of `NugetProcessor.process()` and where things throw

All **verified by reading** `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/NugetProcessor.kt` unless stated.

1. Collect and scope (`:719` to about `:1064`), then the ADR-066 closure, `ForwardReachabilityClosure(...)` at `:1065`.
2. Plan: `forwardPlanner.catalog(...)` at `:1376`. Inside, `ForwardCallablePlanner.catalog` (`forward/ForwardCallablePlanner.kt:700-779`) is a set of per-declaration loops (`functions.map` `:712`, `extensionFunctions.map` `:734`, `objects.forEach` `:763`, `classes.forEach` `:766`, `valueClasses.forEach` `:767`), then `ForwardPropertyPlanner.catalog` at `:772`. Every callable funnels through the private `planOrSkip` (`ForwardCallablePlanner.kt:2318`), which returns `ForwardCallableCatalogEntry.Skipped` for known-unsupported shapes and calls `.validate()` (`:2155`, `:2524`), which is where `ForwardCallablePlanValidator` (`forward/ForwardMarshallingModel.kt:622`, throws at `:783-789`) fires.
3. The catalog is then consumed before anything is rendered: reachable-interface discovery walks every plan (`:1406-1419`), interface entries are planned per interface (`:1423-1433`), the merged `callableCatalog` is built (`:1434`), and a second ADR-113 declaration catalog is planned (`:1458-1498`).
4. Kotlin half: `generateCNameWrappers(...)` (`:1571`, body from `:1780`) builds the `CNameExports.kt` `FileSpec` in memory.
5. C# half: `generateCSharpBindings(...)` (`:1577`, body `:1682-1757`) calls `translate(...)` (`cir/CirTranslator.kt:118`), which is per-declaration or per-file-group `forEach` loops (`:322`, `:360`, `:368`, `:379`, `:415-448`, `:456`, `:468`, `:475`, `:482`, `:505-553`, `:567`, `:604`, `:649`), then `withSkipRemarks`, `withoutEmptyStaticClasses`, `resolveDocLinks`, one `renderer.render(...)` call for the whole file (`:1746`), and writes `Interop.cs` (`:1748-1755`).
6. Gate: `if (logger.hasFatalDiagnostic) return emptyList()` at `:1587`. `hasFatalDiagnostic` is set by any `logger.error` through `ForwardDiagnosticTrackingLogger` (`forward/ForwardDiagnosticTrackingLogger.kt:20-23`).
7. ABI contract: `ForwardAbiContract.csharp`, `csharpLegacy`, `assertMatches`, `assertMatchesPlan` (`:1592-1629`). Collisions become `ERROR_C_ENTRY_POINT_COLLISION` (`reportEntryPointCollisions`, `:1763`); `missing`/`mismatch` stay generator-bug `require`s (ADR-117 Consequences, `docs/adr/117-forward-abi-collision-names-owning-declarations.md:331`).
8. Only then `cNameExports.writeTo(...)` (`:1630`) and `writeForwardDiagnostics` (`:1636`).

Consequences, **verified by reading**: `Interop.cs` is written before the gate and before the ABI check; `CNameExports.kt` and `NugetDiagnostics.json` are written only on a clean round. There is no `try`/`catch` anywhere on this path: the only real `catch` in `nuget-processor/src/main` that is not inside an emitted string template is `cir/CirClassTranslator.kt:509-516` (catches `IllegalStateException` from `forwardPublicCsharpType()` only to rethrow a better-worded `error(...)`, still unlocated) and `exports/Helpers.kt:70`.

### F2. What "skip this declaration and continue" means late: it does not work, the boundary is a reporting boundary

**Verified by reading**: a plan in the catalog is read by (a) reachable-interface discovery (`NugetProcessor.kt:1406-1419`), (b) `generateCNameWrappers`, (c) `translate`, (d) `ForwardAbiContract.assertMatchesPlan` (`:1619-1629`), which requires every plan's native exports to exist on both halves. Most raw throws sit in the projection and emitter layers (F3), that is, after the catalog is fixed. **Inferred** (still, and no longer load-bearing): dropping a declaration from one half at that point (C# projection threw, Kotlin emitter did not, or the reverse) leaves a Kotlin export with no C# import or the reverse, which would trip the `missing`/`mismatch` generator-bug `require`s in `assertMatches` if they ever ran. A true skip would need the failure to be pushed back into the catalog and everything downstream (interface reachability, ADR-066 closure consumers, both halves) recomputed.

**Verified by spike** (2026-09-21, spike C): a late failure that is caught and reported with `logger.error(msg, declaration)` never reaches `assertMatches`. The prototype dropped two classes from the C# half only (catch plus `return@forEach` in the top-level class loop, `cir/CirTranslator.kt:456`) while the Kotlin half still exported them; the `NugetProcessor.kt:1587` gate returned, the build failed with `KSP failed with exit code: PROCESSING_ERROR` and no secondary exception. After the failed round `build/generated/ksp/mingwX64/mingwX64Main/resources/Interop.cs` exists (without the two classes) and `CNameExports.kt` does not, which confirms the F1 "Consequences" paragraph by execution. The `logger` that `translate` receives is the tracking logger: the gate tripped on a bare `logger.error` that did not go through `ForwardDiagnosticSink`.

**Verified by spike, and it CONTRADICTS pass 1's plan-time shape** (2026-09-21, spikes E and F). Pass 1 said a plan-time failure "happens before anything has read the entry" and recommended wrapping the whole per-declaration lambda. Spike E omitted the catalog entries of two top-level functions (a `filter` ahead of `functions.map`, `forward/ForwardCallablePlanner.kt:712`) and reported both with `logger.error` right after `forwardPlanner.catalog(...)` (`NugetProcessor.kt:1376`). Both errors printed, then the round aborted before the gate:

```
e: [ksp] java.lang.IllegalArgumentException: Forward callable catalog has no entry for io.github.xxfast.kotlin.native.nuget.test.scratch.scratchPlanBoomOne; the emitter is walking a declaration list the planner never saw
...
* What went wrong:
Execution failed for task ':test-library:kspKotlinMingwX64'.
> A failure occurred while executing com.google.devtools.ksp.gradle.KspAAWorkerAction
   > Forward callable catalog has no entry for ...scratchPlanBoomOne; the emitter is walking a declaration list the planner never saw
```

The two late failures planted in the same build were never reported, and Gradle's headline was the secondary exception, not the diagnostics. So a contained plan-time failure must leave an entry in the catalog. Spike F replaced the omission with `ForwardCallableCatalogEntry.Skipped(symbol, <existing reason>, node = function)`: all four failures (two plan-time, two late) were reported in one build and the headline was `KSP failed with exit code: PROCESSING_ERROR`. Side effect observed in spike F: the borrowed skip reason also printed its own `w: [ksp] ... [nuget:SKIPPED_UNSUPPORTED_TYPE] ... its CHAR type combination is not supported` line per function, so the real implementation needs its own `ForwardPlanSkipReason` that the sink maps to the `ERROR_*` kind instead of a `SKIPPED_*` warning.

So the honest meaning of "continue" for a late failure is: catch, report the declaration as a fatal `ERROR_*`, keep iterating so every other failure is also reported, then stop at the existing `:1587` gate. Nothing ships, so dependents of the failed declaration need no treatment at all. The one place a real skip is consistent is plan time: a throw inside `planOrSkip` happens before anything has read the entry, and converting it to a `Skipped` entry is structurally identical to the existing named skips (ADR-064). See open question Q1.

### F3. Census of throw sites in `nuget-processor/src/main/kotlin` (raw ripgrep hits, 2026-09-21, **verified by reading** the grep output; hits include a handful of comment mentions, called out where known; the last column's pattern was `else\s*->\s*(error|throw)`, so it also counts `else -> throw`)

| File | `error(` | `require(`/`requireNotNull(` | `check(`/`checkNotNull(` | `!!` | of which `else -> error` | Class |
|---|---|---|---|---|---|---|
| `forward/ForwardKotlinPlanEmitter.kt` | 14 | 56 | 0 | 5 | 10 | invariant (plan-shape assertions), but historically user-reached: ADR-081 and ADR-097 quote real crashes from here |
| `forward/ForwardMarshallingModel.kt` | 5 | 30 | 0 | 0 | 0 | `:783-789` (`RawCollection`, `RawKSType`, `SpecializedProtocol`, `Unsupported` in a plan) is **user-reachable by shape**: the planner comment at `ForwardCallablePlanner.kt:3047-3049` and `:3720-3722` records exactly this class of crash. Remaining `require`s are invariants |
| `forward/ForwardCallablePlanner.kt` | 7 (2 are comments; real: `:2935`, `:2938`, `:3661`, `:4214`, `:4217`) | 12 | 0 | 7 | 3 | mixed: `:2935/:2938` ("cannot build an input parameter for $type") and `:4214` (specialized protocol with no legacy route) are user-reachable by a new type shape; `:4217` raw KSType is invariant |
| `forward/ForwardCirPlanProjection.kt` | 9 | 12 | 0 | 0 | 5 | `:696/:699` (no call argument for $type), `:1257/:1273` (value-class underlying), `:1521` (cannot classify public type) user-reachable by shape; `:291`, `:402`, `:463`, `:1554` invariant |
| `forward/ForwardCirPropertyProjection.kt` | 5 | 10 | 0 | 0 | 3 | `:708` ("No property wire type") and `:763` ("No C# property type for $this", the issue #52 message) user-reachable; `:279`, `:777` invariant |
| `forward/ForwardPropertyKotlinEmitter.kt` | 9 | 1 | 0 | 4 | 9 | invariant by intent, reachable in practice (ADR-080 quotes `Forward property direct nullable getter is invalid for ...`) |
| `forward/ForwardPropertyPlanner.kt` | 3 | 0 | 0 | 0 | 1 | `:1066`, `:1159` (Throwable into Kotlin), `:1176` (bound interface out of Kotlin): user-reachable by shape |
| `forward/ForwardPropertyPlan.kt` | 1 | 8 | 0 | 0 | 1 | `:150` user-reachable by shape; `require`s invariant |
| `forward/ForwardCsharpTypes.kt` | 2 (1 comment) | 8 | 0 | 0 | 1 | `:53` user-reachable (generic base over unspellable argument, see `CirClassTranslator.kt:506-516`) |
| `forward/ForwardCirCollectionComponents.kt` | 0 | 6 | 0 | 0 | 0 | invariant |
| `forward/ForwardBoundTypes.kt`, `ForwardPublishedScope.kt` | 0 | 3 + 1 | 0 | 0 | 0 | input-file parsing (manifest malformed), not per declaration |
| `forward/ForwardExportOwners.kt` | 1 | 0 | 0 | 0 | 0 | `:105` invariant (no owner tag) |
| `forward/ForwardDiagnostic.kt` | 2 | 2 | 0 | 0 | 1 | invariant (`:402` enum init, `:610` legacy reason has no kind) |
| `cir/CirClassTranslator.kt` | 6 (3 comments; real: `:512`, `:838`, `:844`) | 1 | 2 | 1 | 2 | `:501` `checkNotNull` (star-projected base) and `:512` (unspellable base argument) are **user-reachable and already worded for the user**, just unlocated; `:838/:844` "unreachable" invariant |
| `cir/CirClassRenderer.kt`, `CirNativeImports.kt`, `CirSealedRenderer.kt`, `CirModel.kt`, `CirRenderer.kt`, `CirTypeMapping.kt`, `CirFunctionTranslator.kt` | 0 | 3+4+1+1 | 1+1+1+1 | 1 (`CirFunctionTranslator`) | 0 | invariant; renderer-side, so not attributable to one declaration without a node (see F2 and Recommendation 1c) |
| `cir/CirTranslator.kt` | 0 | 0 | 0 | 2 | 0 | invariant |
| `ForwardAbiContract.kt` | 2 | 9 | 1 | 0 | 0 | invariant after ADR-117 moved the three duplicate guards to diagnostics |
| `NugetProcessor.kt` | 1 (comment) | 3 | 0 | 0 | 0 | invariant |
| `exports/*` (`FlowExports` 1 `require`; `ExtensionPropertyExports`, `InterfaceBridgeFactoryExports`, `ValueClassExports`, `LambdaParameterExports` 1 `!!` each) | 0 | 1 | 0 | 4 | 0 | invariant |
| **Total** | **68** | **172** | **7** | **24** | **36** | |

(`forward/ForwardDiagnosticTrackingLogger.kt` also matches once: it is `override fun error(`, not a throw.)

Reading of the table, **inferred**: there is no clean static line between "invariant" and "user-reachable". Every historical instance (issue #52, ADR-080, ADR-081, ADR-097, the `fun dispose()` `require`) was an "invariant" that a legal Kotlin shape reached because a new `BridgeType` variant or route arrived before every `when` learned about it. That is the argument for a catch-all boundary rather than converting sites one by one: the sites that will fire next are by construction the ones nobody has classified yet. The exhaustive-`when` `else -> error` arms (36) are the highest-risk family.

### F4. How a thrown exception and a `logger.error` reach the console today. **Was the load-bearing unverified claim; now verified by spike.**

**Verified by spike** (2026-09-21, spike A, `./gradlew :test-library:kspKotlinMingwX64 --console=plain`, default verbosity, scratch file `test/scratch/ScratchSpike.kt` with `class ScratchCloserA { fun dispose() {} }` and `class ScratchCloserB { fun dispose() {} }`): `logger.error(message, symbol)` IS visible, carries the location as a text prefix, fails the build, and two failures are both reported in one build. Console, verbatim (paths shortened to `<src>` = `C:/Users/isuru/Developer/KMP/kn-w2/test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/scratch`):

```
e: [ksp] <src>/ScratchSpike.kt:3: [nuget:ERROR_C_ENTRY_POINT_COLLISION] Error io.github.xxfast.kotlin.native.nuget.test.scratch.ScratchCloserA (generated Dispose): Forward ABI duplicate C# import for scratchclosera_dispose; 2 Kotlin declarations export the same C entry point:
  - io.github.xxfast.kotlin.native.nuget.test.scratch.ScratchCloserA (generated Dispose)
    at <src>/ScratchSpike.kt:3
  - io.github.xxfast.kotlin.native.nuget.test.scratch.ScratchCloserA.dispose()
    at <src>/ScratchSpike.kt:4. The C entry point is derived from ... rename one declaration. [scratchclosera_dispose(in pointer, out pointer) -> void, scratchclosera_dispose(in pointer) -> void]
    at <src>/ScratchSpike.kt:3
e: [ksp] <src>/ScratchSpike.kt:7: [nuget:ERROR_C_ENTRY_POINT_COLLISION] Error ...ScratchCloserB (generated Dispose): ...
    at <src>/ScratchSpike.kt:7

> Task :test-library:kspKotlinMingwX64 FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':test-library:kspKotlinMingwX64'.
> A failure occurred while executing com.google.devtools.ksp.gradle.KspAAWorkerAction
   > KSP failed with exit code: PROCESSING_ERROR
```

Observations from the same run, all **verified by spike**:
- The location format is `e: [ksp] <absolute path, forward slashes, drive letter>:<line>: <message>`, and because `ForwardDiagnostic.format()` also appends `\n    at <path>:<line>` the location already appears twice per diagnostic on the console.
- Gradle's own failure report carries none of the diagnostic text, only `KSP failed with exit code: PROCESSING_ERROR`. The `e:` lines are the only place the text exists.
- 155 `w: [ksp] ... [nuget:SKIPPED_*/WARNING_*]` lines were also on the console in a passing run. That does not match ADR-100's S1/S2 finding or the source comment at `forward/ForwardDiagnostic.kt:445-449` ("they reach no console today", `:447`). Not investigated (out of scope, budget); flagged for the human because it may mean `packNuget` now prints every warning twice (KSP line plus ADR-100 re-emit).
- The `e:` lines are not last: in the combined spike F run the plan-time errors were at log line 53 and the late ones at 332 of 364, with `w:` lines between and after. `grep '^e: '` finds them; scrolling to the bottom does not.

**Verified by spike** (spike B, an injected `error("SCRATCH raw throw for ...")` in the top-level class loop at `cir/CirTranslator.kt:456`, two matching classes in the fixture): what an uncaught raw `error(...)` looks like today. One line, no location, no stack trace at default verbosity, first declaration only, and the message repeated as Gradle's headline:

```
e: [ksp] java.lang.IllegalStateException: SCRATCH raw throw for io.github.xxfast.kotlin.native.nuget.test.scratch.ScratchBoomOne

> Task :test-library:kspKotlinMingwX64 FAILED
...
* What went wrong:
Execution failed for task ':test-library:kspKotlinMingwX64'.
> A failure occurred while executing com.google.devtools.ksp.gradle.KspAAWorkerAction
   > SCRATCH raw throw for io.github.xxfast.kotlin.native.nuget.test.scratch.ScratchBoomOne
```

`ScratchBoomTwo` appears nowhere in that log. **Verified by spike** (spike C, the same throw wrapped in `try { ... } catch (e: IllegalStateException) { logger.error("[nuget:ERROR_INTERNAL_GENERATOR_FAILURE] Error <qualified name>: ...", cls); return@forEach }`): both are reported, located, and the build fails through the gate:

```
e: [ksp] <src>/ScratchSpike.kt:3: [nuget:ERROR_INTERNAL_GENERATOR_FAILURE] Error io.github.xxfast.kotlin.native.nuget.test.scratch.ScratchBoomOne: IllegalStateException: SCRATCH raw throw for ...ScratchBoomOne
e: [ksp] <src>/ScratchSpike.kt:7: [nuget:ERROR_INTERNAL_GENERATOR_FAILURE] Error io.github.xxfast.kotlin.native.nuget.test.scratch.ScratchBoomTwo: IllegalStateException: SCRATCH raw throw for ...ScratchBoomTwo
...
   > KSP failed with exit code: PROCESSING_ERROR
```

No real (uninjected) reproducer was found: `val Throwable.x`, `var Throwable.x` and `var failure: Throwable` on a class all bind as named `SKIPPED_UNSUPPORTED_PROPERTY` / `SKIPPED_UNSUPPORTED_INPUT` warnings (spike B0), so `ForwardPropertyPlanner.kt:1159` is guarded as its comment says.

The pass-1 reasoning is kept below for the record; its **inferred** labels on the KSP source reading are now backed by the observed behaviour (the exact `KspGradleLogger` source for the pinned version was still not read).

- **Inferred** (read from KSP `main` on GitHub on 2026-09-21, not from the pinned 2.3.10 jar): `KspGradleLogger.error` prints `e: [ksp] ${decorateMessage(message, symbol)}` to `System.out`, and `decorateMessage` prefixes `"${location.filePath}:${location.lineNumber}: "` when the symbol has a `FileLocation`. That is the entirety of how `KSPLogger.error(message, symbol)` "attaches" a location: it is a text prefix, there is no structured channel. ADR-100 reads the same from the 2.3.10 artifact (`docs/adr/100-forward-diagnostic-delivery.md:70-72`, labelled verified by source reading there).
- **Inferred** (same source): `KotlinSymbolProcessing.execute()` has no `try`/`catch` around `processor.process(resolver)`; its logger wrapper only tracks `hasError`, the loop exits on `hasError`, `onError()` is called, and `ExitCode.PROCESSING_ERROR` is returned. `KspAATask`'s worker then does `throw Exception("KSP failed with exit code: $exitCode")`, carrying none of the logged text. A processor exception instead arrives as `InvocationTargetException`, is passed to `kspGradleLogger.exception(e.targetException)` (prints `e: [ksp] $e`) and is **rethrown**, so its message also rides Gradle's own task-failure report.
- **Verified by reading** ADR-100: warnings printed to the worker's `System.out` never reach the console in this repo's builds (S1/S2, verified by execution there, `docs/adr/100-forward-diagnostic-delivery.md:21-57`, mechanism `:79-86`), and ADR-100 explicitly deferred whether `ERROR_*` text is visible: "Unverified either way, and not fixed here" (`:306-314`). ADR-117 asserts `e: [ksp] <file>:<line>: [nuget:ERROR_C_ENTRY_POINT_COLLISION] ...` (`docs/adr/117-...md:83`, `:328`) but none of its "Verified by execution" items is a console run of a real Gradle build; its evidence is the Tier 1 `kspErrors` seam (`:245-255`, `:374-379` read: in-process cells only, the fixture "must never go into `test-library/`").
- **Inferred**, and the sharpest thing available without a build: the crash quotes in ADR-080 (`docs/adr/080-bare-nullable-enum.md:14`), ADR-081 (`:16`) and ADR-097 (`:65`) carry the `e: [ksp] ` prefix. That prefix is `KspGradleLogger`'s own `println` format on `System.out`, the same call and channel `logger.error` uses; Gradle's rethrown-exception report would not carry it. So in those sessions a worker-stdout error line did reach a console, which points toward "`logger.error` is visible" and weakens the case for the aggregate throw. Caveat: those ADRs do not say whether the quote came from a Gradle build or a direct KSP run.
- Closed 2026-09-21: pass 1 ended this list with "NOBODY HAS VERIFIED that a `logger.error` message from this processor is visible in a real console". Spike A above verifies it for `kspKotlinMingwX64` (the task `packNuget` depends on) on Windows, Gradle 9.1.0, `--console=plain`. Not observable from this environment: the rich console (the agent shell has no TTY, so Gradle falls back to plain) and an IDE build window. Not checked: macOS. ADR-100's "Deferred: `ERROR_*` visibility" (`docs/adr/100-forward-diagnostic-delivery.md:306-314`) can be closed as "visible" by the documenter.

### F5. Tier 1 harness and uncaught exceptions: the memory note still holds

**Verified by spike** (2026-09-21, spike D, scratch cell `Tier1ScratchSpikeTest`, `./gradlew :nuget-processor:test --tests "*Tier1ScratchSpikeTest*" -i`). Uncaught injected throw: `runCatching { Tier1Harness.run(...) }` printed `SPIKE-RAW isFailure=true exception=java.lang.IllegalStateException: SCRATCH uncaught raw throw for tier1.scratch.ScratchRawOne`, so the exception reaches JUnit directly and no `Tier1Result` (hence no `kspErrors`) exists. Caught prototype, two failing classes: `SPIKE-CAUGHT kspErrors.size=2`, both `[nuget:ERROR_INTERNAL_GENERATOR_FAILURE] Error tier1.scratch.ScratchBoomOne/Two: ...`, and `SPIKE-CAUGHT files=[Interop.cs]`. So the existing `kspErrors` seam observes the new kind with no harness change. The Tier 1 strings carry no `<path>:<line>: ` prefix: that prefix is added by KSP's Gradle logger, not by the processor, so a Tier 1 cell can only assert a location through the `at <path>:<line>` suffix that `format()` appends.

**Verified by reading** `nuget-processor/src/test/kotlin/.../tier1/Tier1Harness.kt:217-221`: `KotlinSymbolProcessing(...).execute()` is called with no `try`/`catch`. `RecordingKSPLogger.exception` exists (`Tier1Recorders.kt:33-35`) but KSP never calls it for a processor throw. ADR-117's post-implementation note 3 (`docs/adr/117-...md:365-369`) records, from the implementing run, that "an uncaught exception propagates straight to JUnit, never through `RecordingKSPLogger`". So a raw `error(...)` today can only be asserted with `assertFailsWith` around `Tier1Harness.run`, and no cell does that for a processor throw (`Tier1CoroutinesClasspathPreconditionTest.kt:30` uses it for the harness's own `require`). Q2 is now answered (F4): Recommendation 3 is dropped, nothing escapes `process()` under Recommendations 1 and 2, and the harness needs no change.

### F6. Fold-in `ROADMAP.md:87`, `fun dispose()`

**Verified by spike** (2026-09-21, spike A, console quoted in F4): in a real build `class X { fun dispose() {} }` fires `ERROR_C_ENTRY_POINT_COLLISION` through `logger.error`. It is not a raw throw and not `ERROR_CSHARP_SIGNATURE_COLLISION`. It is located at the class (`ScratchSpike.kt:3`, the "generated Dispose" owner), lists the member at `:4` as the second owner, fails the build, and two such classes are both reported in one build. So the fold-in is a kind and wording upgrade of a diagnostic that already works, not a crash fix; the backlog file name `fun-dispose-crashes-ksp-raw-stack-trace.md` describes a state that no longer exists.

**Verified by reading**: `ERROR_CSHARP_SIGNATURE_COLLISION` for methods is produced by one shared function, `emitCsharpSignatureCollisions(methods, container, symbol, logger)` (`cir/CirClassTranslator.kt:2254-2289`), called for classes (`:1096`), sealed bases (`:1938`), sealed arms (`:2063`), objects (`:2363`), file classes (`CirTranslator.kt:351`) and extensions (`:629`). It groups the container's `CirMethod`s by name plus parameter types. The generated `Dispose()` is not a `CirMethod` in that list (it is renderer-owned), which is why `class Closer { fun dispose() {} }` sails past it and is caught only later by the ABI contract as two owners of `closer_dispose`. `Reserved.kt` holds identifier and parameter-name reservations (`C_RESERVED` `:3`, `CSHARP_RESERVED` `:11`, `PLAN_OWNED_NAMES` `:100`), nothing about member names. `grep '"dispose"'`-style guards do not exist (backlog file, reconfirmed by grep for `emitCsharpSignatureCollisions` call sites and `Reserved.kt`). The existing reproducer is `Tier1EntryPointCollisionTest.kt:58` (`fun dispose names the method and the generated Dispose`).

Semantically this is ADR-034's case exactly: two members of one C# type with the same name and parameter list (CS0111). The specific kind is the right one.

### F7. Fold-ins `ROADMAP.md:236` and `:58`

- **Verified by reading**: `enumParamsUnsupported` is a local function at `cir/CirFunctionTranslator.kt:118-126` (the backlog file says `:91-99`; it has drifted), a bare `logger.error(text, func)` with no `[nuget:KIND]` tag, called from nine return-shape branches (`:129`, `:162`, `:218`, `:267`, `:316`, `:365`, `:414`, `:457`, `:502`). It is the only `logger.error(` call in `nuget-processor/src/main` outside `ForwardDiagnosticSink.emit` (`forward/ForwardDiagnostic.kt:459`). It already continues (returns `emptyList()`), already carries the node, and already trips `hasFatalDiagnostic` through the tracking logger. It only lacks a kind.
- **Verified by reading**: `NugetReportDiagnosticsTask.report()` (`nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetReportDiagnosticsTask.kt:56-60`) prints `entry.message` verbatim. `ForwardDiagnostic.format()` (`forward/ForwardDiagnostic.kt:430-436`) puts the location last, on its own line, as `\n    at <path>:<line>`. The JSON carries four string fields (`ForwardDiagnosticsFile.kt:38-49`), no file or line field.
- **Inferred**: Gradle's `org.gradle.api.logging.Logger` has no overload that takes a source location, so "attach as Gradle's own source-location metadata" can only mean the Problems API (`ProblemSpec.lineInFileLocation(path, line)`), which is still `@Incubating` in Gradle 9.x and prints an "[Incubating] Problems report is available at" line (docs.gradle.org `reporting_problems.html`, `ProblemSpec` javadoc 9.1.0; gradle/gradle#35883). **Inferred**: IntelliJ's build output linkifies an absolute path followed by `:line` in plain text, and the Kotlin-compiler shape `w: <path>:<line>: message` (the shape `KspGradleLogger.decorateMessage` already produces) is the most reliably recognised. Neither was checked in an IDE.
- **Verified by spike** (console only, 2026-09-21): in a plain Gradle console nothing is structured or linkified; the location is the literal text `e: [ksp] C:/abs/path/File.kt:<line>: ` (forward slashes, drive letter) and Gradle's failure report repeats none of it. `[Incubating] Problems report is available at: file:///.../build/reports/problems/problems-report.html` is already printed by these builds today, so the Problems API banner is not a new cost. **Unspiked**: `nugetReportDiagnostics` was not run (it is not on the `kspKotlinMingwX64` path), so how its re-emitted line renders, and whether warnings now appear twice given the visible `w: [ksp]` lines (F4), is unobserved. IntelliJ linkification cannot be observed from a console and stays **inferred**.

### F8. How other processors contain failures

- **Moshi KSP** (**inferred**, read from `square/moshi` `master`, `JsonClassSymbolProcessorProvider.kt`): the `process()` loop does `logger.error("@JsonClass can't be applied to $type ...", type); continue` for validation, and wraps generation per type in `try { ... } catch (e: Exception) { logger.error("Error preparing ${type.simpleName.asString()}: ...") }`, then moves to the next type. That is exactly fatal-and-continue: every type is reported in one round, the round fails.
- **Room, Dagger** (**inferred**, from memory of their XProcessing design, not re-read this session): validation errors are reported per element through the `Messager` with the element attached, processing continues through the round, and the round fails at the end; the javac `Messager` contract is itself "report an error and keep going". Neither turns an internal exception into a silent skip.
- Skipped: KAPT specifics, Dagger's deferral of unresolved types (a different problem: this processor is single-round, `processed` flag at `NugetProcessor.kt:720`).

## Recommendation

End state: **fatal, collect all, located, greppable**, on the single sink ADR-064 established.

1. **New kind `ERROR_INTERNAL_GENERATOR_FAILURE`** in `ForwardDiagnosticKind`, plus a small `guarded(...)` helper beside `ForwardDiagnosticSink` that runs a block, catches `Exception` (not `Throwable`: `OutOfMemoryError`, `StackOverflowError` and KSP's own `Error`s must still abort), and emits one diagnostic: `declaration` = the qualified name, `symbol` = the `KSNode` (so the location is attached), `reason` = exception class plus message, `hint` = "this is a generator bug, not a mistake in your code: add `exclude(\"<qualified name>\")` to `nuget { publish { } }` to unblock, and report it with this message". Installed at three loop families, because a single catch in `process()` cannot continue anything:
   a. (Corrected 2026-09-21 after spikes E and F, see F2.) A contained plan-time failure must leave a `ForwardCallableCatalogEntry.Skipped` entry under the declaration's own symbol, with its node, using a new `ForwardPlanSkipReason` (suggest `INTERNAL_FAILURE`) that the sink maps to `ERROR_INTERNAL_GENERATOR_FAILURE` rather than to a `SKIPPED_*` warning. Omitting the entry makes the Kotlin emitter throw `Forward callable catalog has no entry for ...` before the gate, which hides every later failure and replaces Gradle's headline. That makes `planOrSkip` (`ForwardCallablePlanner.kt:2318`, which already has `symbol` and `node` in hand) the natural catch site; a throw in an entry builder before `planOrSkip` needs the builder to compute the symbol outside the `try` so the catch can still return a `Skipped` entry. Because the entry carries the failure, the separate `internalFailures` list described below is unnecessary: the existing skip-to-diagnostic path emits it. Pass-1 text follows, still valid as the loop inventory. Plan: the per-declaration lambdas inside `ForwardCallablePlanner.catalog` (`:712`, `:734`, `:763`, `:766`, `:767`, plus the sealed and class loops above `:700`), the interface loops in `NugetProcessor.kt:1423-1433` and `:1458-1498`, and the `ForwardPropertyPlanner.catalog` equivalent. Wrap the lambda, not only `planOrSkip`: `classifier.classify(...)` and the entry builders run before `planOrSkip` and can throw too. The planner holds no logger, so follow the existing `droppedPropertySetters` / `droppedProperties` pattern (`NugetProcessor.kt:1440-1449`): record `internalFailures` on the catalog, let `NugetProcessor` emit them.
   b. Kotlin half: the per-declaration loops inside `generateCNameWrappers` (`NugetProcessor.kt:1780` onward and the `exports/*` adders it calls).
   c. C# half: each per-declaration `forEach` in `translate` (F1 item 5). `renderer.render` stays a single call with a whole-round catch whose diagnostic has no node: a renderer throw is not attributable to a declaration without threading nodes into CIR, and the renderer sites are the least user-reachable family in F3.
   The same declaration can fail in both (b) and (c); dedupe on `(declaration, exception message)` inside the helper.
2. **Policy: fatal everywhere, including plan time.** The `:1587` gate already stops before the ABI checks and before `CNameExports.kt`, so dependents need no handling (F2). A generator invariant failing must not quietly ship a package with a hole. Add a final whole-`process()` catch that emits the same kind with no node, so nothing ever escapes unlabelled.
3. **DROPPED 2026-09-21.** Spike A shows `logger.error` text is visible, located, and printed for every failure of the round, so the aggregate throw is not needed and the harness is not touched. Pass-1 text kept for the record: Visibility belt-and-braces, conditional on the F4 check. Run the F4 console check first. If `logger.error` text is visible in a real build, stop at 1 and 2. If it is not, then after the gate decides the round is fatal, throw one `IllegalStateException` whose message is every formatted `ERROR_*` diagnostic of the round joined by newlines: the exception path is the one the repo has seen on a console (`docs/adr/097-enum-collection-components.md:65`, `080`, `081`). That also repairs the visibility of every existing `ERROR_*` kind, and needs the harness to catch around `execute()` and record the message into `kspErrors` so the existing `ERROR_*` cells keep their seam.
4. **`fun dispose()`**: in `emitCsharpSignatureCollisions`, accept an optional set of renderer-owned signatures; the class, sealed-base and sealed-arm call sites (`CirClassTranslator.kt:1096`, `:1938`, `:2063`) pass `Dispose()` for `IDisposable` containers. A zero-parameter `CirMethod` named `Dispose` then emits `ERROR_CSHARP_SIGNATURE_COLLISION` with the member's own node, reason "collides with the generated `Dispose()` every handle class declares", hint "rename it (`close()` binds as `Close()` beside `Dispose()`)". It fires during translate, so the `:1587` gate returns before the ABI contract would report the generic kind. Covers the sealed-arm case the backlog file records as inferred.
5. **`enumParamsUnsupported`**: give it a kind (suggest `ERROR_UNSUPPORTED_ENUM_PARAMETER_ROUTE`) and route it through `ForwardDiagnosticSink.emit`. Message text moves into `reason`/`hint`. After this, the only `logger.error(` in main is the sink's.
6. **Re-emit location (`ROADMAP.md:58`)** (corrected 2026-09-21): do NOT reorder `ForwardDiagnostic.format()`. `format()` feeds `logger.error`/`logger.warn` (`forward/ForwardDiagnostic.kt:455-463`) as well as the JSON, and spike A shows KSP already prefixes `<path>:<line>: ` to every such line, so a leading location inside `format()` would print `e: [ksp] <path>:<line>: <path>:<line>: [nuget:...]`. Instead add the additive `file` and `line` fields to `NugetDiagnostics.json` and `ForwardDiagnosticEntry`, and have `NugetReportDiagnosticsTask.report()` compose `<path>:<line>: <message>` for its own line only. That removes the wide test blast radius pass 1 priced in. Do not adopt the Problems API now. Superseded pass-1 text: reorder `ForwardDiagnostic.format()` so the line **leads** with `<path>:<line>: ` (the kotlinc/KSP shape) instead of trailing `\n    at <path>:<line>`, and add additive `file` and `line` fields to `NugetDiagnostics.json` and `ForwardDiagnosticEntry` so a later Problems API adopter has structured data. Do not adopt the Problems API now (incubating, prints its own report banner).

Price (revised 2026-09-21): about 8 main files, no test-support files, 3 to 4 new or amended test files, docs. One worktree. With `format()` left alone there is no wide test blast radius.

Alternatives rejected:
- True skip (`SKIPPED_INTERNAL_FAILURE`, warn and ship without the declaration): consistent only at plan time; for late failures it breaks `assertMatches` (F2), and it ships a silently smaller API because of a generator bug.
- One catch around `process()` as the ROADMAP line literally says: gives a label but no location and no "continue", so still one failure per build.
- Convert the user-reachable throw sites to named skips one by one: right thing per site when found (issue #58 did it for #52) but cannot cover the next unclassified `else -> error`; complementary, not a substitute.
- Aggregate throw of every `ERROR_*` after the gate: solves a visibility problem spike A shows does not exist, and would change every existing `ERROR_*` cell's seam.
- Omitting a failed declaration from the catalog at plan time: spike E, the emitter throws on the missing entry before the gate and masks every later failure.
- Reordering `format()` to lead with the location: doubles the prefix KSP already adds (spike A).
- Gradle Problems API for the re-emit now: incubating in 9.x.

## Files an implementation touches

Processor (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/`):
- `forward/ForwardDiagnostic.kt` (two new kinds, `guarded` helper, dedupe; `format()` is NOT reordered, revised 2026-09-21)
- `forward/ForwardDiagnosticsFile.kt` (additive `file`/`line` fields)
- `forward/ForwardCallablePlanner.kt` (`planOrSkip` catch returning a `Skipped` entry with a new `ForwardPlanSkipReason`, enum at `:41`; revised 2026-09-21, no `internalFailures` list)
- `forward/ForwardPropertyPlanner.kt` (same)
- `NugetProcessor.kt` (guard the `generateCNameWrappers` loops and the interface loops at `:1423-1433` and `:1458-1498`, whole-round catch; revised 2026-09-21, no catalog failure list to emit and no aggregate throw)
- `cir/CirTranslator.kt` (guard each per-declaration loop)
- `cir/CirClassTranslator.kt` (`emitCsharpSignatureCollisions` reserved signatures, three call sites)
- `cir/CirFunctionTranslator.kt` (`enumParamsUnsupported` through the sink)

Plugin: `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetReportDiagnosticsTask.kt` (parse the two additive fields and compose the leading `<path>:<line>: ` here, revised 2026-09-21).

Tests (revised 2026-09-21: `Tier1Harness.kt` and `Tier1Recorders.kt` are NOT touched, and no test asserting the `at <path>:<line>` suffix changes): `tier1/Tier1EntryPointCollisionTest.kt` (the dispose cell changes kind), a new `ForwardDiagnosticGuardTest`, a new or extended Tier 1 cell for the enum-parameter kind, `nuget-plugin/src/test/.../NugetReportDiagnosticsTaskTest.kt`.

Docs: new ADR, amendments to ADR-064 (kinds), ADR-100 (re-emit line shape, JSON fields, the `ERROR_*` visibility deferral closes), ADR-117 (dispose no longer reaches it), `FEATURES.md` diagnostics rows, the diagnostics topic page, delete `docs/backlog/fun-dispose-crashes-ksp-raw-stack-trace.md` and `docs/backlog/one-fatal-forward-diagnostic-still-lives-outside.md`, strike `ROADMAP.md:58`, `:87`, `:88`, `:236`. No fixtures in `test-library/` (an `ERROR_*` fixture would break `packNuget`, ADR-117 `:249`). No leak rows.

## Sample test

There is no live reproducer for the new kind: every raw throw quoted in the repo (#52, ADR-080, ADR-081, ADR-097) has since been fixed. **Verified by spike** (B0, 2026-09-21): `val Throwable.x`, `var Throwable.x` and a class `var failure: Throwable` do not reach `ForwardPropertyPlanner.kt:1159`; they bind as named skips. Untried candidates: `ForwardPropertyPlanner.kt:1176`, `ForwardCallablePlanner.kt:3661`, `ForwardMarshallingModel.kt:789`. The spikes used an injected throw, which a shipped test cannot, so an end-to-end Tier 1 cell needs a test-only fault seam or a real reproducer; do not block on finding one. In the dispose cell below, the location assertion works because `format()` keeps its `at <path>:<line>` suffix (Tier 1 strings have no KSP prefix, F5). The guaranteed cell is therefore a unit test of the helper, and the dispose cell is the Tier 1 consumer-visible one.

```kotlin
class ForwardDiagnosticGuardTest {
  @Test
  fun `two failing declarations are both reported and neither escapes`() {
    val logger = RecordingKSPLogger()
    ForwardDiagnosticSink.reset()
    listOf("sample.First", "sample.Second").forEach { name ->
      guarded(declaration = name, node = null, logger = logger) {
        error("No C# property type for SpecializedProtocol($name)")
      }
    }
    assertEquals(2, logger.errors.size)
    assertTrue(logger.errors.all { "[nuget:ERROR_INTERNAL_GENERATOR_FAILURE]" in it })
    assertTrue(logger.errors[0].contains("sample.First"))
    assertTrue(logger.errors[1].contains("exclude(\"sample.Second\")"))
  }
}

// Tier1EntryPointCollisionTest, amended cell
@Test
fun `fun dispose collides with the generated Dispose as a C# signature collision`() {
  val result = Tier1Harness.run(
    """
    package sample
    class Closer { fun dispose() {} }
    """.trimIndent(),
  )
  val error = result.kspErrors.single()
  assertContains(error, "[nuget:ERROR_CSHARP_SIGNATURE_COLLISION]")
  assertContains(error, "sample.Closer.dispose")
  assertContains(error, "Closer.kt:2")
  assertFalse(result.kspErrors.any { "ERROR_C_ENTRY_POINT_COLLISION" in it })
  assertNull(result.generatedFiles.keys.firstOrNull { it.endsWith("CNameExports.kt") })
}
```

(`Tier1Harness.run`'s exact single-source overload and the fixture file name should be copied from the existing cell at `Tier1EntryPointCollisionTest.kt:58-64`; the shape above is **inferred** from it.)

## Spikes run (2026-09-21)

All in a scratch worktree detached at `651cb7d0`, Windows 11, Gradle 9.1.0, `--console=plain`, default verbosity; every edit reverted afterwards (`git checkout -- .`, `git clean -fd`). Scratch fixture: `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/scratch/ScratchSpike.kt` (a first attempt under `.../nuget/scratch/` was silently out of scope: `include(...)` at `test-library/build.gradle.kts:204` admits only `...nuget.test` and `dev.other.admitted`). Each `kspKotlinMingwX64` run took 8 to 20 seconds warm.

| Spike | Seam | Result |
|---|---|---|
| A | Two classes with `fun dispose()`, unmodified processor, `:test-library:kspKotlinMingwX64` | `logger.error` visible as `e: [ksp] <path>:<line>: [nuget:ERROR_C_ENTRY_POINT_COLLISION] ...`, both classes reported, build fails with `KSP failed with exit code: PROCESSING_ERROR`. Verifies F4, F6, Q2 |
| B0 | Three `Throwable` property shapes, unmodified processor | No raw throw; named `SKIPPED_*` warnings. No real reproducer found |
| B | Injected `error(...)` in `cir/CirTranslator.kt:456` loop, two matching classes | One unlocated `e: [ksp] java.lang.IllegalStateException: ...` line for the first class only, message repeated in Gradle's "What went wrong", no stack trace. Corrects the restatement |
| C | Same throw wrapped in `try`/`catch` plus `logger.error(msg, cls)` plus `return@forEach` | Both classes reported with location; gate at `NugetProcessor.kt:1587` stops the round; `assertMatches` never runs; partial `Interop.cs` written, no `CNameExports.kt`. Verifies F2 late path and Recommendation 1c |
| D | Scratch Tier 1 cell, one uncaught and one caught case | Uncaught: exception propagates to JUnit, no `Tier1Result`. Caught: `kspErrors.size=2`. Verifies F5; harness needs no change |
| E | Plan-time: catalog entries of two top-level functions omitted, `logger.error` emitted after `catalog(...)` | CONTRADICTS pass 1: the Kotlin emitter throws `Forward callable catalog has no entry for ...` before the gate, masking the two late failures and taking over Gradle's headline |
| F | Same as E but a `Skipped` entry left in the catalog | All four failures (two plan-time, two late) reported in one build, clean `PROCESSING_ERROR` headline; one spurious `SKIPPED_UNSUPPORTED_TYPE` warning per function from the borrowed skip reason |

Not spiked, and why: `nugetReportDiagnostics` re-emit rendering and possible duplicate warnings (budget cut to 25 minutes, not load-bearing); IntelliJ linkification (not observable from a console); rich console, macOS, and `packNuget` end to end (only `kspKotlinMingwX64`, which `packNuget` depends on, was run); a contained failure in the Kotlin half (`generateCNameWrappers`) or in the interface loops (`NugetProcessor.kt:1423-1433`, `:1458-1498`), which by F1 also sit before the same gate but were not exercised; KSP's pinned `KspGradleLogger` source was not read, only its behaviour observed.

## Spike first

Nothing load-bearing remains open before design. Post-implementation checks only:

- After the guards land, plant a failure in each of the three families in one Tier 1 cell (plan, Kotlin half, C# half) and assert all are in `kspErrors`: spike E shows a missing catalog entry is fatal to "collect all", and the Kotlin half and interface loops were not exercised by a spike.
- Confirm the dedupe on `(declaration, exception message)`: a declaration that fails in both halves should print once.
- Run `packNuget` once with a contained failure and check the re-emit line from `NugetReportDiagnosticsTask` reads `<path>:<line>: <message>` and that warnings are not printed twice (Q6).

## Deferred scope

- True skip-and-ship for contained failures (needs the failure folded back into the catalog and ADR-066 consumers recomputed).
- Threading `KSNode` into CIR so a renderer throw can name its declaration.
- Gradle Problems API adoption for the re-emit, and an opt-in `failOnSkippedDeclarations` (ADR-100 `:322`), both of which the additive JSON fields prepare for.
- Converting individual user-reachable throw sites in F3 into named `SKIPPED_*` kinds: do it per site as each is reproduced, under the owning feature's ADR.
- The reverse direction (`NugetGenerateBindingsTask`) has its own throw sites; not surveyed.
- Multi-target re-emit (ADR-100 `:315`), unchanged.

## Open what-questions

- Q1. Should a contained failure fail the build or skip the declaration with a warning? Recommendation: fail (`ERROR_*`), everywhere, with an `exclude(...)` hint as the unblocker. The ROADMAP line's "continues, as the `SKIPPED_*` diagnostics do" reads as wanting a skip; only a plan-time skip is structurally safe (F2), and two policies for one kind is worse than one. Human decision: pending.
- Q2. CLOSED 2026-09-21 by spike A: yes, `logger.error` text is visible in a real Gradle console, located, and every failure of the round is printed. Recommendation 3 is dropped. No human decision needed.
- Q6 (new, 2026-09-21). The spike console shows 155 `w: [ksp] ... [nuget:*]` warning lines at default verbosity, which ADR-100 and `forward/ForwardDiagnostic.kt:447` say cannot happen. One known difference: ADR-100's S1/S2 ran `kspKotlinMacosArm64` on macOS (`docs/adr/100-forward-diagnostic-delivery.md:23`, `:33`), this spike ran `kspKotlinMingwX64` on Windows; `scripts/verify.sh` passes no verbosity flag that would explain it. Is the ADR-100 re-emit now duplicating them in `packNuget`, and should that be its own ROADMAP line rather than part of this item? Recommendation: its own line; this item only needs the re-emit to carry `file`/`line`. Human decision: pending.
- Q3. Should the `fun dispose()` fold-in ship in the same PR? Recommendation: yes, it is one function plus three call sites on the same path, and it removes the most likely real-world trigger of a late failure. Human decision: pending.
- Q4. Is reordering `format()` to lead with `<path>:<line>: ` acceptable given ADR-064's format contract mirrors the reverse `formatDiagnostic()` house style (`ForwardDiagnostic.kt:424-429`)? Recommendation: yes for forward only; reverse has no source location to lead with. Human decision: pending.
- Q5. Name of the enum-parameter kind, and whether it should instead become a `SKIPPED_*` (the function is simply absent, nothing else depends on it, and the translator already returns `emptyList()`). Recommendation: keep it fatal in this item to preserve behaviour, record the downgrade as a follow-up. Human decision: pending.
