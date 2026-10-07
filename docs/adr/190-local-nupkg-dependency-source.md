# ADR-190: Local dependency source: a directory or `.nupkg` path, restored project-locally

## Status

Accepted

## Gate

The design gate was decided by a stand-in reviewer agent while the owner was asleep (2026-10-03), not by the owner. The decision most open to being overruled is the string-typed `source` (this ADR) versus a typed `nupkg(file)` form with task-dependency inference (the DSL-shape alternative below). Overrule it by retyping `source`, which breaks `source = "https://..."`.

## Context

Target: a Kotlin author points a `nuget { dependencies { dependency(...) } }` entry at a package
they built locally (a `.nupkg` file, or a directory of them) and binds it, and a rebuild of that
package at the **same version** reaches the generated Kotlin on the next Gradle build. ROADMAP Phase
8 line "Local path / `.nupkg` file source for a dependency", deferred by ADR-044 and ADR-045.

The ROADMAP line cites synthesis D6, but D6
(`docs/research/nuget-plugin-architecture-synthesis.md:127-131`) is the **publish** direction: a C#
consumer iterating against a locally built `.nupkg` of *our* package. That is tracked separately
(ROADMAP "Local-feed dev loop" under Future Improvements). This ADR is the reverse direction only,
and adds no `ProjectReference` binding.

How restore worked before this change (verified by reading, 2026-10-03):

- `NugetDependency.source` is a `Property<String>`, documented as a feed URL
  (`docs/topics/nuget-dsl.md:368`).
- `generateCsproj` flattened every declared `source` into one `<RestoreSources>` element, prefixed
  with nuget.org. There is no generated `NuGet.Config` and no per-package routing (ADR-045
  Alternative 4, deferred).
- `NugetRestoreTask` ran `dotnet restore <interop.csproj>` with one `@InputFile` (the csproj) and
  one `@OutputFile` (`obj/project.assets.json`). Packages land in the user's global packages folder.
- `NugetExtractApiTask` tracks only `assetsFile`; the DLLs it reads are found through
  `project.restore.packagesPath` in that file (`rir/RirParsing.kt`, `deriveDllPaths`) and are not
  task inputs.
- `nugetCompileInterop` is a second restore site with its own csproj and the same `<RestoreSources>`
  logic. Its `dependencySources` is a public `ListProperty<String>`, and the repo fixtures already
  add an absolute directory to it by hand (`test-library/build.gradle.kts:178`,
  `test-companion/build.gradle.kts:37`). The plugin wired the raw DSL strings into it, so a relative
  path would have resolved against `build/nuget-compile/`.

Spike (research memo, scratch dir, .NET SDK 10.0.300, macOS arm64, `NUGET_PACKAGES` pointed at a
scratch folder; a `Probe.Local 1.0.0` package repacked with a different marker method each time).
Verified by spike unless labelled otherwise:

| # | Probe | Observed |
|---|-------|----------|
| T1 | `<RestoreSources>https://api.nuget.org/v3/index.json;/abs/feedA</RestoreSources>` | Restores. A directory is a valid source through the mechanism the plugin already uses |
| T2a | Repack `Probe.Local 1.0.0` with new content, plain `dotnet restore` | "All projects are up-to-date for restore". Extracted DLL is the **old** one. `project.assets.json` byte-identical |
| T2b | Same, `dotnet restore --force` | Restore runs, DLL still **old**, assets file byte-identical |
| T2c | Same, `--force --no-cache` | DLL still **old** |
| T3 | `<RestoreSources>` naming the `.nupkg` file itself | `NU1301: The local source '.../Probe.Local.1.0.0.nupkg' doesn't exist` |
| T4 | `<RestorePackagesPath>/abs/c4/packages</RestorePackagesPath>` | Package extracts there, the global folder is untouched, and `project.restore.packagesPath` in the assets file follows it (so `deriveDllPaths` needs no change) |
| T4b | Repack, plain restore with the project-local folder | Still stale (no-op restore) |
| T4c | Repack, delete `packages/probe.local/1.0.0`, plain restore (no `--force`) | Re-extracts the **new** DLL, and `libraries["Probe.Local/1.0.0"].sha512` in the assets file changes |
| T8 | A local `MimeMapping 4.0.0` beside nuget.org's, both source orders | The local one was restored both times (one run each: observed, not a guarantee) |
| T9 | A folder feed holding the package as `whatever.nupkg` | `NU1101`: a folder feed finds a package by its `<id>.<version>.nupkg` file name |

The load-bearing fact is T2: **NuGet treats `id + version` as immutable. Once a version is
extracted, no restore flag re-reads the source.** With the old task graph the failure was silent
twice over: `nugetRestore` was up-to-date (the csproj did not change), and even when forced, the
assets file did not change, so `nugetExtractApi` stayed up-to-date and the Kotlin bindings stayed
generated against the old assembly. The repo's own fixtures dodge this by minting a new version per
build; the plugin cannot, because it does not own a foreign package's version.

## Alternatives Considered

### 1. Project-local packages folder, evict by package id, verify the restored bytes (chosen)

When any dependency has a local source, the restore project sets `<RestorePackagesPath>` to
`build/nuget-interop/packages`. Before restore, every extracted package whose id a local feed serves
is deleted; after restore, the extracted `.nupkg` of each locally sourced dependency must hash-match
a `.nupkg` in its source.

- Pro: a same-version rebuild reaches the bindings through ordinary up-to-date checking: the local
  files are restore inputs, eviction forces re-extraction, the assets file's `sha512` changes, and
  `nugetExtractApi` re-runs.
- Pro: no silent failure mode is left. Anything that would still bind the wrong bytes (an eviction
  miss, another feed serving the same id and version) fails the post-restore check.
- Pro: only plugin-owned state under `build/` is deleted. `./gradlew clean` resets it.
- Pro: remote-only projects are untouched (no `RestorePackagesPath`, global folder as before).
- Con: with a local source declared, remote packages are extracted a second time under `build/`.

The draft of this ADR evicted by reading each extracted folder's `.nupkg.metadata` `"source"` and
comparing it with the local feed paths. That comparison was string-shaped (the field holds the path
as written in `<RestoreSources>`), unverified on Windows, and a miss would have done nothing
silently. Eviction by id, from the `.nuspec` inside each local `.nupkg`, does not depend on how
NuGet spells a path, and the hash check turns any remaining miss into a failure.

### 2. Evict from the user's global packages folder

Delete `~/.nuget/packages/<id>/<version>` before restore. Rejected: it mutates state shared with
every other .NET build on the machine, and the repo's own rule is never to hand-edit that cache.

### 3. `dotnet restore --force` / `--no-cache`

Rejected: verified ineffective (T2b, T2c).

### 4. Bypass NuGet: unzip the `.nupkg` and read `lib/<tfm>/` directly

Rejected: loses transitive resolution, NuGet's TFM asset selection, and the resolved-version pin
`packNuget` reads from the assets file (ADR-050). It would be a second resolution pipeline.

### 5. Rewrite the version per build (the fixture trick)

Rejected: the plugin does not own a foreign package's version, and a rewritten version would leak
into the `.nuspec` dependency pin.

### 6. Project-local packages folder always, for every project

A hermetic restore for everyone (ADR-045 "Future Improvements"). Not chosen here: it changes disk
and download behaviour for remote-only users who have no staleness problem. Its own ROADMAP line.

### 7. Accept that a published package can win over the local one

An earlier draft accepted that a package on nuget.org with the same id and version could be
restored instead of the local one, silently. Rejected: that is the exact failure the restatement
rules out. The post-restore hash check makes it loud.

### DSL shape: a typed source (`source = nupkg(file)`, `directory(dir)`, `url("...")`)

The CocoaPods shape (`pod("X") { source = path(...) }`, cited in ADR-044). Gives a
`Provider<RegularFile>` with task-dependency inference. Not chosen: it retypes `source` and breaks
`source = "https://..."`, for a second concept where one already fits. NuGet's own notion of a
source is "a URL or a path" (the repo's root `nuget.config`; T1).

## Decision

**DSL.** `source` keeps its type and gains two meanings:

```kotlin
nuget {
  dependencies {
    // a directory of .nupkg files (a NuGet folder feed); the version is required
    dependency("Acme.Text", "1.2.0") {
      source = "../acme/artifacts"
      bind { }
    }

    // one package file; the version is read from the file when omitted
    dependency("Acme.Local") {
      source = "../acme/bin/Release/Acme.Local.1.0.0.nupkg"
      bind { }
    }
  }
}
```

**Resolution, at wiring time.** The plugin resolves each `source` against `layout.projectDirectory`
when it wires the tasks, never with `project.file(...)` at task run time (the configuration cache is
on in this repo, `gradle.properties:12`). A value containing `://` is left alone. The resolved local
paths feed an `@InputFiles` collection.

**Classification** (`NugetLocalSource.kt`, `classifySource`): a value starting `file://` fails fast
("use a plain path"); any other value containing `://` is remote; a name ending `.nupkg` is a
package file; anything else is a directory. A local path holding `;` fails, since `<RestoreSources>`
is `;`-separated.

**Mechanism.**

1. `nugetGenerateRestoreProject`
   - New `@InputFiles localSources`: every local `.nupkg` file and directory.
   - The staged feed `build/nuget-interop/feed/` is an `@OutputDirectory`, synced on every run
     (cleared, then refilled), so a removed dependency leaves no package behind.
   - Directory sources go into `<RestoreSources>` as absolute paths. A missing directory fails,
     naming the dependency and the resolved path.
   - A `.nupkg` file is not a source (T3). The task reads `<id>` and `<version>` from its single
     root-level `*.nuspec` entry, fails if the id differs from the dependency id (case-insensitive),
     fails if a declared `version` differs, and copies the file to `feed/<id>.<version>.nupkg` (T9).
     The staged directory is the source; the version read from the file becomes the
     `PackageReference` version. A missing file fails, naming the dependency and the resolved path.
   - When any local source is declared, the csproj carries `<RestorePackagesPath>` pointing at
     `build/nuget-interop/packages` (T4). Paths written into the csproj are XML-escaped.
2. `nugetRestore`
   - New `@InputFiles localFeeds`: the staged feed plus every directory source. This is what re-runs
     the task after a same-version rebuild.
   - New `@OutputDirectory @Optional packagesDir` (only set when a local source is declared), so
     deleting it also re-runs restore.
   - **Evict by id** before `dotnet restore`: read the `.nuspec` id of every `.nupkg` in every local
     feed (any depth, so hierarchical folder feeds are covered), then delete each child of
     `packages/` whose name equals one of those ids ignoring case, and `check` the delete succeeded.
   - **Verify** after restore: for each dependency with a local source, find its extracted folder
     through `project.restore.packagesPath` and `libraries[key].path` in the assets file
     (`derivePackageFolders`, beside `deriveDllPaths`). Its single `*.nupkg` must have the same
     sha512 as the source file, or as one `.nupkg` in the source directory. Otherwise the task
     fails, naming the dependency, the restored file and the source.
3. `nugetExtractApi`: no input change. The assets file's `sha512` changes when the package bytes
   change (T4c), so it re-runs. Its "DLL not found" message now names both possible packages
   folders.
4. `nugetCompileInterop`: the plugin now wires it the **resolved** feeds (directories absolute,
   every `.nupkg` source replaced by the staged feed) and, only when the DSL declares a local
   source, the same `packagesDir`. The packages path comes from the DSL, not from the task's own
   `dependencySources`, so the repo fixtures that add an absolute directory there by hand stay on
   the global folder. Sharing the folder with `nugetRestore` means the check compiles against the
   copy `nugetRestore` just evicted and re-extracted.

Every failure that remains is loud: an eviction that cannot delete (a locked DLL, typical on
Windows), a stale copy that survives eviction, another feed winning the race for the same id and
version, a missing local path, a nuspec id or version mismatch.

### Verification record

Verified by running, 2026-10-03, macOS arm64, .NET SDK 10.0.300:

- **Same-version repack is rebound, both forms.** `NugetRestoreIntegrationTest` "a same-version
  repack of a nupkg file source is rebound" and "... in a directory source is rebound" pack
  `Probe.Local 1.0.0` with `MarkerOne` with real `dotnet pack`, drive the
  `nugetGenerateRestoreProject`, `nugetRestore` and `nugetExtractApi` task actions, repack with
  `MarkerTwo` under the same version, run them again, and assert the extracted `reverse-ir.json`
  names `MarkerTwo` and not `MarkerOne`. Before eviction existed, both failed with the second
  extraction still naming `MarkerOne`; with eviction they pass.
- **The post-restore check is a real backstop.** With eviction disabled by hand, both repack tests
  fail in `verifyLocalPackages` ("restored '.../probe.local.1.0.0.nupkg', which matches no .nupkg in
  its source ..."), not silently.
- **Deleting the id folder forces re-extraction with a plain restore** (no `--force`): the same two
  tests.
- **The extracted folder keeps its `.nupkg`**, lowercased:
  `packages/probe.local/1.0.0/probe.local.1.0.0.nupkg`, beside `.nupkg.metadata`,
  `probe.local.1.0.0.nupkg.sha512` and `probe.local.nuspec`. Asserted by the repack tests and seen
  by listing the folder.
- **Another feed winning is loud.** "a directory source that does not hold the package fails even
  when nuget org does": `Newtonsoft.Json 13.0.3` with an empty directory source restores from
  nuget.org, and the task fails naming the dependency and the directory.
- **Configuration cache, up-to-date checks.** A throwaway two-project Gradle build outside the repo
  (`pluginManagement { includeBuild(".../nuget-plugin") }`, `org.gradle.configuration-cache=true`)
  ran `:app:nugetGenerateBindings` with a `.nupkg` file source and then a directory source. In both
  forms `nugetGenerateRestoreProject`, `nugetRestore`, `nugetExtractApi` and `nugetGenerateBindings`
  executed after a same-version repack and were all UP-TO-DATE on the next run, with "Configuration
  cache entry reused" each time.
- Classification, staging, the id and version mismatch failures, the missing-path failure, XML
  escaping, and the wiring of inputs and the packages folder: `NugetGenerateRestoreProjectTaskTest`,
  `NugetDslLazinessTest`, `NugetCompileInteropTaskTest`.

**Unverified:**

- Windows and Linux. The repack tests skip silently without `dotnet` (`findDotnet() ?: return`), so
  a green CI tick is not evidence; this stays Unverified until a Windows CI log shows them
  executing. A locked DLL during eviction is expected to fail the `check`, not to be worked around.
- Which feed wins when two serve the same id and version. Now loud when it is the wrong one, still
  unspecified.
- Signed packages in a local feed.
- Whether a `Property<String>` fed from a task-output provider makes `nugetRestore` depend on the
  producing task. `source` is resolved to a string at wiring time, so inferred: it does not, and a
  build that produces the `.nupkg` in the same Gradle build needs an explicit `dependsOn`.
- `nugetCompileInterop` for a project that declares a `.nupkg` file source but does not bind it:
  nothing makes the check depend on `nugetGenerateRestoreProject`, so a cold build would restore
  against a missing staged feed. Inferred to fail loudly (`NU1301`), not verified. An unbound
  dependency is not referenced by the check's csproj, so inferred harmless in practice.

## Consequences

- `source` documentation changes from "feed URL" to "feed URL, directory, or `.nupkg` file".
- A project with a local source gets `build/nuget-interop/feed/` and
  `build/nuget-interop/packages/`; remote packages are extracted there too. A project without one
  gets an empty `feed/` directory and nothing else.
- A directory source needs a declared `version`. Without one, restore fails with `NU1015` (a
  `PackageReference` with no version), as it did before this change for any version-less dependency.
- Both restore sites render `<RestoreSources>` and `<RestorePackagesPath>` through one function
  (`restoreLines`).
- `packNuget` pins a bound dependency at the resolved version (ADR-050). For a local source that
  version may not exist on any feed the C# consumer can reach. Nothing warns about it yet.
- Deferred, each its own ROADMAP line: a per-dependency `source` exclusive for its id via package
  source mapping; a `packNuget` warning when a bound dependency came from a local source; a lazy
  file source with task-dependency inference; a project-local packages folder for every project.

## Amendment (2026-10-07): the same-version repack tests fail loudly on CI

The same-version repack tests no longer skip silently when `dotnet` is absent: the shared helper
`dotnetForTest()` fails the test under the `CI` environment variable and otherwise aborts through
JUnit assumptions (reported skipped, never passed), so the Windows `generators` leg, which installs
the SDK with setup-dotnet, visibly runs them (verified from the change's source).
