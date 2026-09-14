# `packNuget` compiles the generated C# bindings

- ROADMAP: "`packNuget` does not compile the generated C# bindings, only verify's `GeneratedBindingsCheck` does, so a CS0101/CS0128-class defect in `Interop.cs` ships silently through a green `packNuget` until the next full verify" (Phase 4, as of 2026-09-14)
- Researched: 2026-09-14, 6 of 20 minutes, one scratch-dir spike
- Restatement: Gradle plugin, forward. A library author running `packNuget` gets a failing task with the C# compiler's error text when the generated `Interop.cs` does not compile.
- Verdict: fix. ADR-138 (`docs/adr/138-pack-time-interop-compile-check.md`, Proposed). In flight on `ir/pack-time-compile-check` since 2026-09-14.

## Findings (verified by reading unless marked)

- `packNuget` compiles nothing. `PackNugetTask.kt:62-119` copies `.cs` from `generatedCsDirs` into `contentFiles/cs/any/` (dedupe by name `:94-103`), writes a `.targets` with `AllowUnsafeBlocks` (`:177-184`) and a `.nuspec` with `buildAction="Compile"` (`:221-223`). The C# ships as source, compiled in the consumer's build.
- `generatedCsDirs` = KSP resources dir of the first supported target (`NugetPlugin.kt:428-436`) + `nugetGenerateShims.csharpOutputDir` when bound (`:494`); `dependencyVersions` from `project.assets.json` (`:495-501`).
- `GeneratedBindingsCheck` is a zero-source csproj run by `scripts/verify.sh:71-72`: `net8.0`, `LangVersion 12.0`, `Nullable enable`, `TreatWarningsAsErrors`, `EnableDefaultCompileItems=false`, `GenerateDocumentationFile` + `NoWarn CS1591`; `AllowUnsafeBlocks` from the package `.targets`; bound deps transitively via the nuspec. No `ImplicitUsings`; `Interop.cs` carries its own BCL usings.
- `dotnet` is hard-required for reverse only (`NugetRestoreTask.kt:27`, `NugetExtractApiTask.kt:52`, `requireDotnet()` in `NugetTooling.kt:15-25`). `docs/topics/prerequisites.md:7-12` and `getting-started.md:84-85` promise forward publishing needs no .NET SDK.
- Task-shape precedent: `nugetReportDiagnostics` (ADR-100, `NugetPlugin.kt:445-453`), sibling task in the packNuget `afterEvaluate` block.
- **Spike, verified** (scratch dir, real `test-library` output, SDK 10.0.300): a csproj with `GeneratedBindingsCheck`'s settings plus `AllowUnsafeBlocks` and direct `<Compile Include>` of `Interop.cs` + 47 shims, `MimeMapping [4.0.0]` + `TestDependency [fixture]` from `RestoreSources nuget.org;test-library/build/nuget`: build succeeded, 0 warnings, 2.9s. Planted duplicate `class Cat`: exit 1, `error CS0101` on **stdout**, stderr empty. `NugetRestoreTask` captures stderr only; copying it would swallow the compiler text.
- **Inferred**: the net8.0 ref pack is acquired on demand by a newer SDK; wrong case is a loud restore failure, never a silent verdict.

## Recommendation

New `nugetCompileInterop` task (`NugetCompileInteropTask`, group `nuget`) registered in the packNuget `afterEvaluate` block, `packNuget.dependsOn` it, `dependsOn(kspKotlin<Target>)` and `nugetGenerateShims` when bound. Writes `build/nuget-compile/interop-check.csproj` (GeneratedBindingsCheck properties verbatim + `AllowUnsafeBlocks`, one absolute `<Compile Include>` per deduped file, `[v]` PackageReference per bound package, `RestoreSources` like `NugetGenTask`), runs `dotnet build --nologo -v quiet`, captures stdout+stderr, throws `GradleException` with the compiler output on non-zero exit. `dotnet` absent: warn and return without outputs. `@Internal dotnetSearchPath` so a unit test can force the skip. Rejected: compile inside `PackNugetTask`; hard-require dotnet; reuse the reverse `interop.csproj` (absent for forward-only projects); call Roslyn directly; `check`-only wiring.

## Files an implementation touches

`nuget-plugin/.../NugetCompileInteropTask.kt` (new), `NugetPlugin.kt` (register + dependsOn, hoist bound-deps providers), `PackNugetTask.kt` (extract `internal fun generatedCsFiles`), `nuget-plugin/src/test/.../NugetCompileInteropTaskTest.kt` (new); docs `prerequisites.md`, `getting-started.md`.

## Sample test

ProjectBuilder: apply KMP + plugin, one sharedLib target, `publish {}`, `evaluate()`, assert `packNuget.taskDependencies.getDependencies(packNuget)` contains `nugetCompileInterop`, `generatedCsDirs.files` equal on both tasks, `dependencyVersions` empty. Second case with `bind {}` asserts the shims dir and the `nugetGenerateShims` dependency. `scripts/verify.sh --plugin` covers it; the plain verify runs the task inside `:test-library:packNuget`.

## Deferred scope

Opt-in strict mode (fail when dotnet absent); `check` lifecycle wiring; multi-TFM compile; a negative end-to-end fixture.

## Open what-questions

- Skip-with-warning when `dotnet` is absent, no strict flag in v1. Decided 2026-09-14.
- Not hung off `check`. Decided 2026-09-14.
