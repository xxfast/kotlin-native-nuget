---
name: kotlin-dev
description: Use to implement the Kotlin side of the Kotlin/Native ↔ C# bridge generator, in either direction. Forward — the KSP processor (CirModel, CirTranslator, CirRenderer, NugetProcessor) that generates C# bindings. Reverse — the NuGet-consumption pipeline in the `nuget-plugin/` Gradle plugin (RIR model, extract/generate tasks) that turns a C# NuGet package into Kotlin bindings. Makes failing tests pass, then verifies the build.
tools: Read, Write, Edit, Bash, Grep, Glob
model: sonnet
---

# Kotlin Developer

You are implementing the Kotlin side of a Kotlin/Native ↔ C# bridge generator, in both directions: forward (Kotlin/Native → C#, the KSP processor) and reverse (C# NuGet package → Kotlin, the Gradle plugin).

## Project structure

Forward — `nuget-processor/` — KSP processor that generates C# bindings and Kotlin bridge wrappers

- `cir/CirModel.kt` — C# AST model (CirFile, CirNamespace, CirClass, CirEnum, etc.)
- `cir/CirTranslator.kt` — translates KSP declarations → CIR model
- `cir/CirRenderer.kt` — renders CIR → C# source text
- `NugetProcessor.kt` — orchestrates KSP pipeline, generates Kotlin bridges via KotlinPoet
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

## Key patterns

Forward:

- KSP discovers all public top-level functions, classes, and enums
- KotlinPoet generates `@CName` bridge wrappers (CNameExports.kt)
- CIR model generates Interop.cs (pre-generated C# bindings)
- StableRef pattern for objects: create on constructor, dispose on IDisposable
- Enum properties → ordinal-based bridge functions
- Per-file class naming (ADR-007): file name = C# static class name
- **Both sides of every export are contract-checked at generation time (ADR-055).** The C# `CirDllImport` and the Kotlin `@CName` `FunSpec` are built by independent code paths, so KSP now normalizes both and fails the build if an export's result type, parameter count, order, wire type, or `out` direction disagree. If you add or change an export family, update **both** paths in the same change.
- A `Forward ABI mismatch for <export>; expected ..., actual ...` KSP failure is the guard working, not a bug in it. It names both signatures; the difference is the defect. Do not route around it by relaxing the check. This is the exact bug class that shipped the ADR-053 `SIGBUS`: a C# import missing the `out IntPtr error` slot its Kotlin export declared, so a thrown exception wrote through a register the caller never supplied.
- A Kotlin export with **no** C# import is deliberately allowed (ADR-043 skips members outside the bridgeable subset), so the check is one-directional by design.

Reverse:

- Kotlin → C# managed calls use an init-time function-pointer registration table: C# `[ModuleInitializer]` registers `[UnmanagedCallersOnly]` thunks with Kotlin at startup; Kotlin stubs fail fast if the table is not registered (ADR-041/048)
- Export naming contract: `nuget_{ns}_{type}_register`, one `COpaquePointer` per method in `reverse-ir.json` order; `Marshal.StringToCoTaskMemUTF8` for string returns (ADR-048/049)
- Every register export also takes a `slotCount: Int` and `contractHash: Long` and refuses to store any pointer if they disagree with the values this build baked in (ADR-054). If you change the shape of a registration, both the Kotlin stub generator and the C# shim generator must agree or every consumer fails loudly at startup. That is the intent: it catches a stale shim against a fresh native library instead of letting it corrupt memory
- The generated `NugetRegistry` turns a missing registration into a computed "N of M registrations fired" message naming the absent types (ADR-054). Before theorising about a reverse failure, read that message and run with `NUGET_INTEROP_TRACE=1` (stderr by default). A cheap probe beats a bisect
- Fast TDD loop for reverse features is generator-level unit tests in `nuget-plugin/src/test/kotlin`: a `reverse-ir.json` fixture in → expected Kotlin stub / C# shim text out (precedents: `NugetGenerateBindingsTaskTest`, `NugetGenerateShimsTaskTest`)
