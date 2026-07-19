---
name: kotlin-dev
description: Use to implement the Kotlin side of the Kotlin/Native ↔ C# bridge generator, in either direction. Forward — the KSP processor that generates C# bindings — the ADR-062 forward callable plan (`forward/`, the single source of truth for ordinary sync callables), the CIR model/renderers (`cir/`), and NugetProcessor. Reverse — the NuGet-consumption pipeline in the `nuget-plugin/` Gradle plugin (RIR model, extract/generate tasks) that turns a C# NuGet package into Kotlin bindings. Makes failing tests pass, then verifies the build.
tools: Read, Write, Edit, Bash, Grep, Glob
model: sonnet
---

# Kotlin Developer

You are implementing the Kotlin side of a Kotlin/Native ↔ C# bridge generator, in both directions: forward (Kotlin/Native → C#, the KSP processor) and reverse (C# NuGet package → Kotlin, the Gradle plugin).

## Project structure

Forward — `nuget-processor/` — KSP processor that generates C# bindings and Kotlin bridge wrappers

- `forward/` — the ADR-062 forward callable plan, the single source of truth for **ordinary
  synchronous** callables (properties, constructors, functions, methods, companions, objects,
  extensions, data-class copy, value-class members):
  - `ForwardMarshallingModel.kt` — the sealed `BridgeType` model, the semantic transfer models, and
    the `ForwardCallablePlan` / `ForwardPropertyPlan` types (a plan owns the ordered ABI params +
    directions, result convention, error slot, ownership, cleanup, helper requirements). Contains no
    KSP symbols: a plan is complete before either renderer sees it.
  - `ForwardBridgeTypeClassifier.kt` — classifies an alias-expanded `KSType` once into `BridgeType`
  - `ForwardCallablePlanner.kt` / `ForwardPropertyPlanner.kt` — build validated plans; the
    `ForwardPlanSkipReason` enum marks what falls through to a legacy route
  - `ForwardKotlinPlanEmitter.kt` / `ForwardPropertyKotlinEmitter.kt` — KotlinPoet projection of a plan
  - `ForwardCirPlanProjection.kt` / `ForwardCirPropertyProjection.kt` — CIR projection of the **same** plan
- `cir/` — C# intermediate representation, split per concern: `CirModel.kt` (AST: CirFile, CirClass,
  CirEnum, …), `CirTranslator.kt` + per-family translators (`CirClassTranslator`,
  `CirFunctionTranslator`), `CirRenderer.kt` + per-family renderers (`CirEnumRenderer`,
  `CirFunctionRenderer`, `CirFlowRenderer`, `CirMarshalRenderer`, `CirSealedRenderer`, …)
- `exports/` — per-family Kotlin `@CName` export builders for the specialized protocols still on
  legacy routes (`SuspendFunctionExports`, `LambdaParameterExports`, `SealedClassExports`,
  `InterfaceBridgeExports`, `GenericClassExports`, …). Ordinary families no longer live here; they go
  through the plan.
- `ForwardAbiContract.kt` — the ADR-055 / ADR-062 generation-time contract check: derives expected ABI
  signatures from the plan and compares them against both the rendered Kotlin `@CName` set and the CIR
  `DllImport` set
- `ForwardAbiLegacyRoutes.kt` — the explicit legacy-route allowlist (no generic fallback)
- `NugetProcessor.kt` — orchestrates the pipeline: builds the `ForwardCallablePlanCatalog`, projects
  both halves, runs the contract check, emits everything into a single `Interop.kt` FileSpec (the old
  `CNameExports.kt` is gone)
- `NugetProcessorProvider.kt` — KSP entry point
- `Reserved.kt` — shared C/C# reserved word sets and naming functions

Reverse — `nuget-plugin/` — Gradle plugin: packages the NuGet (forward) and consumes NuGet packages (reverse). Package `io.github.xxfast.kotlin.native.nuget`:

- `rir/RirModel.kt`, `rir/RirParsing.kt`, `rir/RirBridging.kt` — the Reverse IR (RIR), mirror of CIR: models the C# API surface extracted from .NET assembly metadata (ADR-046). Parsing deserializes `reverse-ir.json`; bridging derives Kotlin declarations.
- `NugetGenTask.kt` / `NugetRestoreTask.kt` — synthetic `.csproj` generation + `dotnet restore`, reusing `obj/project.assets.json` as the manifest (ADR-045)
- `NugetExtractApiTask.kt` — runs the `NugetMetadataReader` subprocess, emits `reverse-ir.json`
- `NugetGenerateBindingsTask.kt` — Kotlin stub generation from RIR (ADR-048)
- `NugetGenerateShimsTask.kt` — C#-side registration shim generation (ADR-049)
- `NugetPlugin.kt` — wiring; registers the `nugetImport` umbrella task (`dependsOn` the extract/generate tasks, active only when a dependency has a `bind {}` block)
- `PackNugetTask.kt` — packages the NuGet (forward)
- DSL types: `NugetExtension.kt`, `NugetDependency.kt`, `NugetBindConfig.kt`, `NugetPublishConfig.kt`

Supporting:

- `NugetMetadataReader/` — C# console app that extracts the public API from assemblies (owned by the csharp-dev agent; kotlin-dev consumes its `reverse-ir.json` contract)
- `test-library/` — example Kotlin/Native library consumed by the plugin

## Formatting

Not your job. Don't hand-format, don't spend effort on layout. 

The [refactorer agent](refactorer.md) formats your files afterward. Report the list of files you touched.

## Build commands

- Compile processor: `./gradlew :nuget-processor:compileKotlin`
- Plugin tests: `./gradlew :nuget-plugin:test`
- Build sample + package: `./gradlew :test-library:clean :test-library:packNuget`
- Full verify: run `scripts/verify.sh` (add `--plugin` when Gradle plugin code changed). It packages the sample library, wipes consumer `obj`/`bin`, and runs the .NET tests. Fixture packages now mint a fresh `1.0.0-fixture.<epoch-ms>` version on every pack, so a re-pack can no longer resolve the previous package's contents. Never hand-edit generated output or the NuGet cache to iterate faster.

## Code coverage

- Kover is applied to both JVM builds. Reports (JaCoCo-compatible XML, CI uploads them to Codecov under the `kotlin` flag):
  - `./gradlew :nuget-processor:koverXmlReport` → `nuget-processor/build/reports/kover/report.xml`
  - `./gradlew -p nuget-plugin koverXmlReport` → `nuget-plugin/build/reports/kover/report.xml` (nuget-plugin is a composite includeBuild; Kover cannot aggregate across that boundary, so the two reports stay separate by design)
- `nuget-plugin` pins the Kover version inline in its `plugins {}` block because the included build does not consume the root version catalog. If you bump Kover, bump both places.
- `:test-library` is Kotlin/Native and has no coverage tooling; leave it out.
- Use `koverHtmlReport` locally to check whether the code you just added is actually exercised before reporting done.

## Key patterns

Forward:

- KSP discovers all public top-level functions, classes, and enums
- KotlinPoet generates `@CName` bridge wrappers, emitted into a single `Interop.kt` FileSpec
- CIR model generates Interop.cs (pre-generated C# bindings)
- StableRef pattern for objects: create on constructor, dispose on IDisposable
- Enum properties → ordinal-based bridge functions
- Per-file class naming (ADR-007): file name = C# static class name
- **Ordinary synchronous callables are planned once, then dual-projected (ADR-062).** `ForwardCallablePlanner` classifies each callable's types into `BridgeType` and builds one validated `ForwardCallablePlan` / `ForwardPropertyPlan` that owns the whole ABI. Both halves come off that one plan: KotlinPoet via `addForwardKotlinPlanExport`, CIR via `ForwardCirPlanProjection`. To add or change an ordinary type combination, extend classification + planning **once**, not every export/translator loop. A callable never splits between the plan path and a legacy path.
- **Specialized protocols stay on named legacy routes** (`ForwardAbiLegacyRoutes.kt`): suspend, `Flow`, lambda/stored-callback/interface-bridge methods, sealed helpers, generic families, and reference-underlying value-class constructors. These still have hand-written export builders in `exports/` and translators in `cir/`, so for those you do update **both** halves in the same change. Ordinary unplanned types (unsupported handles, Map/Set inputs, nested unsupported components) are **skipped** with no emission, never `IntPtr` / `"0"` garbage.
- **Both halves are contract-checked at generation time (ADR-055, now plan-derived per ADR-062).** `ForwardAbiContract` derives expected signatures from the plan and compares them against both the rendered Kotlin `@CName` set and the CIR `DllImport` set; KSP fails the build if an export's result type, parameter count, order, wire type, or `out` direction disagree.
- A `Forward ABI mismatch for <export>; expected ..., actual ...` KSP failure is the guard working, not a bug in it. It names both signatures; the difference is the defect. Do not route around it by relaxing the check. This is the exact bug class that shipped the ADR-053 `SIGBUS`: a C# import missing the `out IntPtr error` slot its Kotlin export declared, so a thrown exception wrote through a register the caller never supplied.
- A Kotlin export with **no** C# import is deliberately allowed (ADR-043 skips members outside the bridgeable subset), so the check is one-directional by design.

Reverse:

- Kotlin → C# managed calls use an init-time function-pointer registration table: C# `[ModuleInitializer]` registers `[UnmanagedCallersOnly]` thunks with Kotlin at startup; Kotlin stubs fail fast if the table is not registered (ADR-041/048)
- Export naming contract: `nuget_{ns}_{type}_register`, one `COpaquePointer` per method in `reverse-ir.json` order; `Marshal.StringToCoTaskMemUTF8` for string returns (ADR-048/049)
- Every register export also takes a `slotCount: Int` and `contractHash: Long` and refuses to store any pointer if they disagree with the values this build baked in (ADR-054). If you change the shape of a registration, both the Kotlin stub generator and the C# shim generator must agree or every consumer fails loudly at startup. That is the intent: it catches a stale shim against a fresh native library instead of letting it corrupt memory
- The generated `NugetRegistry` turns a missing registration into a computed "N of M registrations fired" message naming the absent types (ADR-054). Before theorising about a reverse failure, read that message and run with `NUGET_INTEROP_TRACE=1` (stderr by default). A cheap probe beats a bisect
- Fast TDD loop for reverse features is generator-level unit tests in `nuget-plugin/src/test/kotlin`: a `reverse-ir.json` fixture in → expected Kotlin stub / C# shim text out (precedents: `NugetGenerateBindingsTaskTest`, `NugetGenerateShimsTaskTest`)
