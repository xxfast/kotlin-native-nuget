# ADR-045: NuGet resolution pipeline — `nugetGen` + `nugetRestore` with `obj/project.assets.json` as the inter-task manifest

## Status

Accepted

## Context

ROADMAP Phase 8 requires a Gradle pipeline that resolves the NuGet packages declared in the
`nuget { dependencies { } }` DSL (ADR-044) before any Kotlin bindings can be generated. The
pipeline must download the package tree, produce a stable, machine-readable manifest that
subsequent tasks (`nugetExtractApi`, `nugetGenerateBindings`) can consume as a pure file input,
and do so correctly across incremental builds.

### What downstream stages need

The next pipeline stage, `nugetExtractApi` (a future ROADMAP item), needs to know:
1. The exact resolved version of every declared package and its transitive dependencies.
2. The absolute file-system paths of the managed `.dll` assemblies for `net8.0` — the inputs to
   the `MetadataReader` dump tool described in ADR-042.
3. The paths of native RID assets (`runtimes/{rid}/native/`) for each Kotlin/Native target, so the
   binding generator can tell Kotlin where to load the shared libraries at runtime.

`dotnet restore` already produces `obj/project.assets.json` which records all of this
information in a stable, documented JSON format. Reusing it as the inter-task handoff manifest
avoids inventing a custom format and keeps the pipeline aligned with NuGet's own tooling contract.

### CocoaPods analog

The CocoaPods plugin (`KotlinCocoapodsPlugin.kt`) uses an identical two-task structure for the
consume direction: `PodGenTask` generates a synthetic `Podfile` and `PodInstallSyntheticTask` runs
`pod install` on it, with a serialized `PodBuildSettingsProperties` file as the inter-task
handoff. The `PodInstallSyntheticTask` is annotated `@DisableCachingByDefault` because CocoaPods
manages its own cache (`Pods/` directory) outside Gradle's knowledge. The NuGet analog is
identical: `dotnet restore` manages the global NuGet package cache (`~/.nuget/packages/`) which
is outside the project and outside Gradle's build cache.

### Constraints from ADR-044 extension model

ADR-044 defines the extension model as implemented. The `NugetExtension` exposes:
- `extension.dependencies: List<NugetDependency>` — the full dependency list
- `NugetDependency`: `id: String`, `version: String?`, `source: String?`, `bind: NugetBindConfig?`

The `nugetGen` task receives these values extracted into Gradle `@Input` properties at
`afterEvaluate` time, per the downstream data contract in ADR-044:
- `dependencyIds: ListProperty<String>` — all declared IDs
- `dependencyVersions: MapProperty<String, String>` — only deps where `version != null`
- `dependencySources: MapProperty<String, String>` — only deps where `source != null`
- `targetFramework: Property<String>` — always `"net8.0"` in v1
- `runtimeIdentifiers: ListProperty<String>` — derived from the Kotlin/Native targets' `konanTarget` names via `KONAN_TO_RID`

### TFM floor rationale

GOALS.md §5.1 specifies ".NET 8+ and C# 12+" as the compatibility floor. `net8.0` is the
current Long-Term Support version (supported through November 2026) and the minimum on which
`[UnmanagedCallersOnly]` and `[ModuleInitializer]` (ADR-041) are well-supported as first-class
features (available since .NET 5, but .NET 6 is end-of-life since November 2024). Setting the
floor at `net8.0` means that any package requiring `net9.0` or later will cause `dotnet restore`
to fail fast with a clear NuGet diagnostic (`NU1202`), surfacing the incompatibility at the
`nugetRestore` step before any binding generation begins — analogous to how CocoaPods writes the
deployment target into the synthetic Podfile so `pod install` fails early when a pod requires a
higher minimum OS.

### `RestoreSources` behavior

When `<RestoreSources>` is specified in a `.csproj`, it **replaces** (not supplements) the
package sources from `nuget.config`. Omitting `<RestoreSources>` lets the developer's local
`nuget.config` sources apply (typically `nuget.org` by default). When one or more dependencies
carry a custom `source` URL, the element must always include `https://api.nuget.org/v3/index.json`
alongside the custom URLs, otherwise publicly-hosted packages would fail to resolve.

### Next ROADMAP item is explicitly out of scope

The next item — "Tooling UX: detect `dotnet` on PATH (or `local.properties` override),
self-heal retry" — is explicitly deferred. v1 of this pipeline performs a minimal PATH check:
the `nugetRestore` task fails fast with a clear message if `dotnet` is not found, but full
tooling detection with install guidance and self-heal retry is implemented in the subsequent
ROADMAP item.

## Alternatives Considered

### 1. Two-task split: `nugetGen` (pure generation) + `nugetRestore` (subprocess) — chosen

Match the CocoaPods `podGen` / `podInstallSynthetic` structure exactly: one task for the pure
XML computation and one for the external-process invocation.

**`NugetGenTask` — pure csproj generation:**
```kotlin
abstract class NugetGenTask : DefaultTask() {
  @get:Input abstract val dependencyIds: ListProperty<String>
  @get:Input abstract val dependencyVersions: MapProperty<String, String>
  @get:Input abstract val dependencySources: MapProperty<String, String>
  @get:Input abstract val targetFramework: Property<String>
  @get:Input abstract val runtimeIdentifiers: ListProperty<String>
  @get:OutputFile abstract val csprojFile: RegularFileProperty
}
```

**`NugetRestoreTask` — dotnet subprocess:**
```kotlin
@DisableCachingByDefault(because = "dotnet restore manages its own package cache; " +
    "the global NuGet cache is outside project scope and not tracked by Gradle's build cache")
abstract class NugetRestoreTask : DefaultTask() {
  @get:InputFile abstract val csprojFile: RegularFileProperty
  @get:OutputFile abstract val assetsFile: RegularFileProperty

  @get:Inject abstract val execOps: ExecOperations
}
```

**Pros:**
- `nugetGen` is a pure function of its inputs and can be Gradle-incremental: unchanged inputs
  → task is up-to-date, csproj is not regenerated.
- `nugetRestore` is clearly `@DisableCachingByDefault` with a documented reason, matching
  `PodInstallSyntheticTask` in the CocoaPods plugin.
- Each task has a single responsibility and a single external call boundary — consistent with the
  "small, single-responsibility tasks" principle from the synthesis document.
- `ExecOperations` injection makes `NugetRestoreTask` configuration-cache safe without capturing
  a `Project` reference.
- The tasks are independently addressable: `./gradlew nugetGen` regenerates just the csproj
  without re-running restore; `./gradlew nugetRestore` runs both.

**Cons:**
- Two task registrations vs one; minor complexity overhead at wiring time.

### 2. Merged `nugetSync` task — rejected

Combine csproj generation and `dotnet restore` into a single task.

**Rejected** because: the csproj generation step is a pure computation whose output is determined
entirely by the extension model. When dependencies haven't changed, Gradle's up-to-date check
should skip csproj regeneration — but merging it with the subprocess invocation forces both steps
to run or be skipped together. If the csproj has not changed but the user explicitly re-runs sync,
the subprocess still runs, but the csproj is unnecessarily recomputed. Splitting the tasks gives
Gradle finer-grained incrementality and aligns with the CocoaPods / synthesis "one task per
external-tool invocation" principle.

### 3. Generate csproj to the project directory (next to `build.gradle.kts`) — rejected

Write `interop.csproj` to the project's root directory alongside the Gradle build files.

**Rejected** because build-time generated artifacts must live under `build/` so that
`./gradlew clean` removes them and `.gitignore` patterns remain simple. Placing a generated file
in the project root would require users to either check it in (stale data risk) or add it to
`.gitignore` manually. `build/nuget-interop/` is the correct location by Gradle convention.

### 4. Per-package `nuget.config` source mapping — deferred

Instead of merging all custom feed URLs into a single `<RestoreSources>` element, generate a
`nuget.config` alongside the `.csproj` with per-package source-mapping entries so that each
package is resolved only from its declared source.

**Deferred** because per-package source mapping requires generating two files and is a v2
correctness concern (preventing a package from resolving from the wrong feed). In practice,
packages on private feeds are absent from the public feed and vice versa, so the merged
`<RestoreSources>` strategy is correct for all common cases. The more precise `nuget.config`
approach is noted in Future Improvements.

## Decision

Use **Alternative 1: two-task split (`nugetGen` + `nugetRestore`)** following the CocoaPods
`podGen` / `podInstallSynthetic` structural pattern.

### Task graph

```
nugetGen                    reads: dependencyIds, dependencyVersions, dependencySources,
        │                          targetFramework, runtimeIdentifiers (all @Input)
        │                   writes: build/nuget-interop/interop.csproj (@OutputFile)
        │                   cacheable: YES (pure computation; no external process)
        │                   (↔ PodGenTask generates the synthetic Podfile)
        ▼
nugetRestore                reads: interop.csproj (@InputFile)
        │                   writes: build/nuget-interop/obj/project.assets.json (@OutputFile)
        │                   @DisableCachingByDefault (external process; global NuGet cache)
        │                   (↔ PodInstallSyntheticTask runs `pod install`)
        ▼
nugetExtractApi             [future ADR — reads assets.json + DLL paths → reverse-ir.json]
        ▼
nugetGenerateBindings       [future ADR — reads reverse-ir.json → Kotlin stubs + C# shims]
        ▼
nugetImport                 umbrella lifecycle task; dependsOn(nugetRestore) in v1;
                            future: dependsOn(nugetGenerateBindings)
                            (↔ podImport — the IDE Gradle-sync entry point)
```

**Wiring to existing tasks:** none. `nugetGen` and `nugetRestore` are consume-direction tasks
with no dependency on `packNuget`, KSP compilation, or any Kotlin compile task. They run
manually or via `nugetImport`. In a future ADR, `compileKotlin{Target}` will be made to
`dependsOn` `nugetGenerateBindings` so generated Kotlin stubs are compiled as part of the
normal build. That wiring is deferred until the binding-generation stage exists.

**Task registration guard:** if `extension.dependencies` is empty at `afterEvaluate` time, the
`nugetGen`, `nugetRestore`, and `nugetImport` tasks are not registered. An empty dependency list
means there is nothing to resolve, and an empty `.csproj` would produce a useless `assets.json`.

### Synthetic `.csproj` shape

The `nugetGen` task's `@TaskAction` calls a pure `generateCsproj(...)` function (extractable for
unit testing) and writes its output to `csprojFile`:

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <!-- TFM floor: net8.0 per GOALS §5.1. Packages requiring net9.0+ will fail with NU1202. -->
    <TargetFramework>net8.0</TargetFramework>
    <!-- RIDs for all configured Kotlin/Native targets so runtimes/{rid}/native/ payloads
         are fetched for every target this Kotlin library supports. -->
    <RuntimeIdentifiers>osx-arm64;win-x64;linux-x64</RuntimeIdentifiers>
    <!-- Suppress build outputs: this project exists solely for dotnet restore. -->
    <OutputType>Library</OutputType>
    <GenerateAssemblyInfo>false</GenerateAssemblyInfo>
    <!-- Only emitted when at least one dependency carries a custom source URL.
         Must include nuget.org because RestoreSources replaces (not supplements) nuget.config. -->
    <RestoreSources>https://api.nuget.org/v3/index.json;https://pkgs.dev.azure.com/myorg/feed/nuget/v3/index.json</RestoreSources>
  </PropertyGroup>
  <ItemGroup>
    <!-- Version present: emitted with the declared version. -->
    <PackageReference Include="Newtonsoft.Json" Version="13.0.3" />
    <!-- Version absent: emitted without Version; NuGet resolves to latest compatible with net8.0. -->
    <PackageReference Include="Serilog" />
    <!-- Transitive deps are resolved automatically by NuGet; not listed here. -->
  </ItemGroup>
</Project>
```

**`<RestoreSources>` conditional logic:**
- If ALL `dependencySources` values are absent (all `null`): omit `<RestoreSources>` entirely.
  `dotnet restore` falls back to whatever the developer's `nuget.config` specifies, which
  normally includes `nuget.org`. This is the common case.
- If ANY dependency has a non-null `source`: collect all unique source URLs, prepend
  `https://api.nuget.org/v3/index.json`, deduplicate, emit as `<RestoreSources>` with
  semicolons. This is required because `<RestoreSources>` replaces the `nuget.config` sources
  rather than adding to them.

**Version-less `PackageReference`:** a `dependency("Foo")` declaration with no `version` emits
`<PackageReference Include="Foo"/>` without a `Version` attribute. NuGet 4.0+ in SDK-style
projects interprets this as a floating reference that resolves to the latest version compatible
with the `<TargetFramework>`. This is valid behavior and is documented in the `nugetGen` task's
output comment.

**`<RuntimeIdentifiers>` order:** follows declaration order in `KONAN_TO_RID` map in
`NugetPlugin.kt` filtered by the targets configured in the build. RIDs that the project does
not configure are omitted so `dotnet restore` does not fetch unnecessary native payloads.

### Generated file locations

```
{project.layout.buildDirectory}/
  nuget-interop/
    interop.csproj                  ← nugetGen output (@OutputFile)
    obj/
      project.assets.json           ← nugetRestore output (@OutputFile)
      interop.csproj.nuget.g.props  ← dotnet side-effect; not declared as task output
      interop.csproj.nuget.g.targets ← dotnet side-effect; not declared as task output
```

`nugetGen.csprojFile` is wired as:
```kotlin
task.csprojFile.set(project.layout.buildDirectory.file("nuget-interop/interop.csproj"))
```

`nugetRestore.assetsFile` is wired as:
```kotlin
task.assetsFile.set(project.layout.buildDirectory.file("nuget-interop/obj/project.assets.json"))
```

`dotnet restore` writes `project.assets.json` to `{project-dir}/obj/project.assets.json` where
`{project-dir}` is the directory containing the `.csproj`. Since `interop.csproj` lives at
`build/nuget-interop/interop.csproj`, dotnet naturally writes to `build/nuget-interop/obj/`.
The `nugetRestore` task declares `assetsFile` as `@OutputFile` so Gradle tracks it for
up-to-date checks; the side-effect `.props` and `.targets` files in `obj/` are not tracked.

### `obj/project.assets.json` downstream contract

The `nugetExtractApi` task (future ADR) reads the following from `project.assets.json`:

| JSON path | Content | Usage |
|---|---|---|
| `targets["net8.0"]["{id}/{version}"]["runtime"]` | Map of relative DLL paths → `{}` | Locates managed `.dll` for `MetadataReader` (ADR-042) |
| `targets["net8.0"]["{id}/{version}"]["native"]` | Map of relative native asset paths | Locates `runtimes/{rid}/native/` payloads per Kotlin target |
| `libraries["{id}/{version}"]["path"]` | Package-relative path, e.g. `"newtonsoft.json/13.0.3"` | Prefixed with `packagesPath` to form absolute paths |
| `project.restore.packagesPath` | Absolute path to `~/.nuget/packages` or configured packages dir | Root for all resolved package paths |

**Absolute DLL path derivation** (pseudocode for `nugetExtractApi`):
```
packagesPath = assets["project"]["restore"]["packagesPath"]
for each (key, pkg) in assets["targets"]["net8.0"]:
    id, version = key.split("/")
    libPath = assets["libraries"][key]["path"]   // e.g. "newtonsoft.json/13.0.3"
    for each (dllRelPath, _) in pkg["runtime"]:  // e.g. "lib/net8.0/Newtonsoft.Json.dll"
        absoluteDllPath = packagesPath + "/" + libPath + "/" + dllRelPath
```

This derivation uses only the `project.assets.json` file and the global packages folder path; no
filesystem discovery is needed. The `nugetExtractApi` task declares `assetsFile` as
`@InputFile` (matching `nugetRestore`'s `@OutputFile`) so Gradle tracks the dependency.

### Gradle correctness

**`nugetGen` up-to-date check:**
All inputs are `@Input`-annotated Gradle properties (strings, lists, maps). Gradle computes a
hash of the input values and skips task execution when inputs are unchanged. The output
`interop.csproj` is an `@OutputFile`; if it is deleted or modified externally, Gradle re-runs
the task. The task is eligible for the Gradle build cache.

**`nugetRestore` up-to-date check:**
The input `csprojFile` is `@InputFile`; the output `assetsFile` is `@OutputFile`. When both are
present and the csproj has not changed, Gradle considers the task up-to-date and skips
`dotnet restore`. This is correct for the common case: if the dependency list hasn't changed,
the packages are already present in the global cache and the `assets.json` is valid.

**The global cache problem:**
`dotnet restore` downloads packages to `~/.nuget/packages/` (the global NuGet cache), which is
outside the project directory and outside Gradle's file tracking. If a developer manually
deletes `~/.nuget/packages/` but the `project.assets.json` file is still present and the
`interop.csproj` hasn't changed, Gradle considers `nugetRestore` up-to-date even though the
actual DLL files are gone. The same problem exists in the CocoaPods plugin (cleared CocoaPods
cache + untouched Pods/ directory). The resolution: `./gradlew nugetRestore --rerun-tasks`
or deletion of `build/nuget-interop/obj/project.assets.json` forces a re-restore. A helper
message in the `nugetExtractApi` task (when DLL paths from `assets.json` don't exist on disk)
can guide the user to re-run restore.

**`@DisableCachingByDefault`:**
`nugetRestore` must be annotated `@DisableCachingByDefault` (as `PodInstallSyntheticTask` is)
because the task's meaningful output — the downloaded packages — lives outside the project scope
and cannot be stored in Gradle's build cache. Storing only `project.assets.json` in the cache
would produce a cache hit that re-creates the JSON file without downloading the actual packages,
leaving downstream tasks with a valid-looking manifest pointing to absent DLLs.

**Configuration-cache safety:**
`NugetGenTask` has no external state; its `@TaskAction` reads only Gradle `Property` values.
It is configuration-cache safe by construction.
`NugetRestoreTask` must use `@get:Inject abstract val execOps: ExecOperations` (Gradle's
injectable service for running external processes) instead of `project.exec { }`, which would
capture a `Project` reference and break configuration cache. The task action uses
`execOps.exec { ... }`.

### Error UX

The project convention (CLAUDE.md) requires fail-fast with explicit messages.

**`nugetGen` failures:** the `generateCsproj(...)` function is a pure XML builder with no
failure modes beyond malformed input. The Gradle `@Input` mechanism validates that required
properties are set before the task runs.

**`nugetRestore` failures — dotnet not found (v1 minimal check):**
```kotlin
val dotnet: String = requireNotNull(findExecutable("dotnet")) {
    "[nuget] dotnet is required to restore NuGet packages but was not found on PATH. " +
    "Install the .NET SDK 8 or later from https://dot.net/download, then re-run Gradle sync. " +
    "(Full tooling detection with local.properties override is coming in the next pipeline item.)"
}
```

**`nugetRestore` failures — non-zero exit code:**
The task captures `dotnet restore` stderr. On a non-zero exit, it throws:
```kotlin
throw GradleException(
    "[nuget] dotnet restore failed (exit code $exitCode).\n" +
    stderr.trimEnd() + "\n\n" +
    "If this is a transient network error, re-run with --rerun-tasks. " +
    "If a package requires a higher .NET version than net8.0, use an older compatible version."
)
```

**NU1202 (TFM incompatibility):** `dotnet restore` already produces a human-readable error:
```
error NU1202: Package SomeLib 5.0.0 is not compatible with net8.0 (.NETCoreApp,Version=v8.0).
Package SomeLib 5.0.0 supports: net9.0 (.NETCoreApp,Version=v9.0)
```
This is passed through verbatim in the stderr payload above. No additional post-processing of
NU1202 is implemented in v1 — the error message is already actionable.

**NU1101 (missing package):**
```
error NU1101: Unable to find package SomeLib. No packages exist with this id in source(s): nuget.org
```
Also passed through verbatim. The existing "check your dependency id and re-run" guidance from
NuGet is sufficient.

### `afterEvaluate` wiring in `NugetPlugin`

The registration follows the same `afterEvaluate` pattern as the existing `packNuget` wiring:

```kotlin
project.afterEvaluate {
    val deps: List<NugetDependency> = extension.dependencies
    if (deps.isEmpty()) return@afterEvaluate

    val interopDir: Provider<Directory> = project.layout.buildDirectory.dir("nuget-interop")

    val nugetGen = project.tasks.register("nugetGen", NugetGenTask::class.java) { task ->
        task.group = "nuget"
        task.description = "Generates the synthetic interop.csproj for NuGet dependency resolution"
        task.dependencyIds.set(deps.map { it.id })
        task.dependencyVersions.set(deps.filter { it.version != null }.associate { it.id to it.version!! })
        task.dependencySources.set(deps.filter { it.source != null }.associate { it.id to it.source!! })
        task.targetFramework.set("net8.0")
        task.runtimeIdentifiers.set(supportedTargets.map { KONAN_TO_RID[it.konanTarget.name]!! })
        task.csprojFile.set(interopDir.map { it.file("interop.csproj") })
    }

    val nugetRestore = project.tasks.register("nugetRestore", NugetRestoreTask::class.java) { task ->
        task.group = "nuget"
        task.description = "Runs dotnet restore to download declared NuGet packages"
        task.csprojFile.set(nugetGen.flatMap { it.csprojFile })
        task.assetsFile.set(interopDir.map { it.file("obj/project.assets.json") })
    }

    project.tasks.register("nugetImport") { task ->
        task.group = "nuget"
        task.description = "IDE-sync umbrella task: resolve NuGet dependencies (and, in future, generate bindings)"
        task.dependsOn(nugetRestore)
    }
}
```

### Testing strategy

**Unit tests (no dotnet required — run on every CI job):**

The csproj generation is extracted as a pure function `fun generateCsproj(...): String`, testable
without any Gradle infrastructure. Tests follow the repo's fail-first TDD convention:

```kotlin
class NugetGenTaskTest {
    @Test
    fun `csproj contains PackageReference for each declared dependency`() { ... }

    @Test
    fun `csproj includes version when declared`() { ... }

    @Test
    fun `csproj omits Version attribute when dependency has no version`() { ... }

    @Test
    fun `csproj omits RestoreSources when no custom sources declared`() { ... }

    @Test
    fun `csproj includes nuget.org plus custom sources when any source is present`() { ... }

    @Test
    fun `csproj includes RuntimeIdentifiers from configured Kotlin targets`() { ... }

    @Test
    fun `csproj always pins TargetFramework to net8.0`() { ... }
}
```

Gradle `ProjectBuilder` tests verify task registration and input wiring:
```kotlin
class NugetPluginTest {
    @Test
    fun `nugetGen task is registered when dependencies block is non-empty`() { ... }

    @Test
    fun `nugetGen task is not registered when dependencies block is empty`() { ... }

    @Test
    fun `nugetRestore depends on nugetGen`() { ... }

    @Test
    fun `nugetImport depends on nugetRestore`() { ... }

    @Test
    fun `nugetGen csprojFile points into build nuget-interop`() { ... }

    @Test
    fun `nugetRestore assetsFile points to obj project.assets.json`() { ... }
}
```

**Integration test (requires `dotnet` on PATH — skipped otherwise):**

A single integration test class, gated by a `dotnet`-discovery check:

```kotlin
class NugetRestoreIntegrationTest {
    private fun findDotnet(): String? = ProcessBuilder("which", "dotnet")
        .start().inputStream.bufferedReader().readLine()?.takeIf { it.isNotBlank() }

    @Test
    fun `dotnet restore produces valid project assets json for Newtonsoft Json`() {
        val dotnet = findDotnet() ?: return   // skip when dotnet is absent
        // write a minimal csproj to a temp dir
        // run: dotnet restore <csproj>
        // assert: obj/project.assets.json exists + contains "Newtonsoft.Json" in targets["net8.0"]
    }

    @Test
    fun `dotnet restore fails with NU1202 for a package targeting net9.0 only`() {
        val dotnet = findDotnet() ?: return
        // write a csproj with a package that is net9.0-only
        // run: dotnet restore <csproj>
        // assert: exit code != 0 AND stderr contains "NU1202"
    }
}
```

The integration tests are explicitly not wired into the default `test` Gradle task in v1; they
are run manually or in a CI job that has the .NET SDK installed. A future ADR or task will add
the skip-condition as a Gradle `@TaskAction` precondition rather than a plain `return` guard.

## Consequences

### New Gradle tasks

- `nugetGen` (`NugetGenTask`) — registered when `extension.dependencies` is non-empty. Inputs:
  dependency metadata + `targetFramework` + `runtimeIdentifiers`. Output: `interop.csproj`.
  Cacheable; up-to-date-checked by Gradle's input hash.
- `nugetRestore` (`NugetRestoreTask`) — registered when `extension.dependencies` is non-empty.
  Input: `interop.csproj`. Output: `obj/project.assets.json`. `@DisableCachingByDefault`; runs
  `dotnet restore` via `ExecOperations` (configuration-cache safe).
- `nugetImport` (lifecycle `DefaultTask`) — umbrella IDE-sync entry point. `dependsOn`
  `nugetRestore` in v1; will gain `dependsOn(nugetGenerateBindings)` in a future ADR.

### No changes to existing tasks

`packNuget`, KSP processing, and all forward-direction tasks are unaffected. The new tasks are
additive and in the consume direction only.

### Developer-machine requirement (v1)

`dotnet` must be on PATH to run `nugetRestore`. A missing `dotnet` causes a fail-fast
`IllegalStateException` with an install URL. Full tooling detection with `local.properties`
override, version checking, and self-heal retry is the next ROADMAP item.

### `build/nuget-interop/` directory

This directory is created by `nugetGen` and populated by `nugetRestore`. It is not committed
to source control (add `build/` to `.gitignore` as standard Gradle convention). `./gradlew clean`
deletes it as part of the standard `build/` cleanup.

### Scope

**In v1:**
- `nugetGen` generates the `interop.csproj` from the extension model per the exact csproj shape
  above, including conditional `<RestoreSources>` logic and version-less `PackageReference`.
- `nugetRestore` runs `dotnet restore`, captures stderr, and throws `GradleException` on
  non-zero exit with the stderr payload and a retry hint. Minimal PATH check for `dotnet`.
- `nugetImport` as umbrella IDE-sync task.
- Task-level up-to-date checks via `@InputFile` / `@OutputFile` declarations.
- Unit tests for `generateCsproj(...)` (pure function) + `ProjectBuilder` tests for task
  registration and wiring.
- One integration test class (skipped when `dotnet` is absent) covering the happy path and
  NU1202 failure.

**Deferred:**
- Full tooling UX: `local.properties` dotnet path override, SDK version check, self-heal
  `--force-evaluate` retry on transient feed failures (next ROADMAP item).
- `nugetImport` wired into `compileKotlin{Target}` dependencies (blocked on binding generation
  ADR; the generated Kotlin stubs must exist before Kotlin compilation can use them).
- Per-package `nuget.config` source mapping for precise feed routing (v2; deferred per
  Alternative 4).
- Local path / `.nupkg` file source for a dependency (synthesis D6, ADR-044 deferred scope).
- The `--packages` flag to redirect the global NuGet cache into the Gradle build directory for
  fully-hermetic builds (useful in restricted-network CI; tracked under Future Improvements).
