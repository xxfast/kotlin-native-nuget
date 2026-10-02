# ADR-182: One public naming scheme for the DSL filters, the task names and the diagnostic codes, with a schema version on both JSON outputs

## Status

Proposed

## Context

At 1.0.0, semver covers the Gradle DSL, the task names, the diagnostic codes and
`NugetDiagnostics.json` (ROADMAP.md:12), and 0.9.0 is the one release allowed to break them. Three
0.9.0 items (ROADMAP.md:19-21) and the Open decisions line (ROADMAP.md:55) found the names
inconsistent:

- `include` / `exclude` filter Kotlin packages (and, for `exclude`, declarations) in `publish {}`
  (`NugetPublishConfig.kt:66-72`) and C# namespaces in `bind {}` (`NugetBindConfig.kt:14-15`).
  Verified by reading.
- Task names mix `nuget`-first (`nugetGen`, `nugetRestore`, `nugetExtractApi`, ...) with
  verb-first (`packNuget`, `publishNuget`, `publishNugetTo<Name>Repository`), and `nugetGen` does
  not say what it generates (`NugetPlugin.kt:73-654`, `NugetSnapshotVersionTask.kt:122,133`).
  Verified by reading.
- Forward diagnostic codes are `SCREAMING_SNAKE` (`ForwardDiagnosticKind`, 34 entries,
  `ForwardDiagnostic.kt:140-490`); reverse codes serialize as lowercase `snake_case`
  (`RirDiagnosticKind`, 36 entries: 26 `SKIPPED_`, 8 `INFO_`, 2 `ERROR_`; `@SerialName("skipped_...")`, `RirModel.kt:399`), and the C#
  metadata reader writes the lowercase strings itself (`NugetMetadataReader/Program.cs`, 27 sites).
  Neither `NugetDiagnostics.json` (a bare array, `ForwardDiagnosticsFile.kt:51-77`) nor
  `reverse-ir.json` (`RirFile(assemblies)`) carries a version. Verified by reading.

The research memos carry the full inventories and counts:
[include-exclude-rename](../research/roadmap/include-exclude-rename.md),
[task-name-scheme](../research/roadmap/task-name-scheme.md),
[diagnostic-code-scheme](../research/roadmap/diagnostic-code-scheme.md).

The C# fixed types were checked for the same Open decisions line and need no change: every public
fixed type is `Kotlin`-prefixed (`KotlinFlow<T>`, `KotlinStateFlow<T>`, `KotlinMutableStateFlow<T>`,
`KotlinFunc`, `KotlinAction`, `KotlinSuspendFunc`, `KotlinSuspendAction` in the generated
`Interop.cs`; `KotlinException`, `IKotlinException`, `KotlinOptional` and the nine `Kotlin*Exception`
types in `Kotlin.Native.Interop/`), and every `Nuget`-prefixed generated type is `internal`.
Verified by grepping `public`/`internal` type declarations in `nuget-processor/src/main`,
`NugetGenerateShimsTask.kt`, `NugetGenerateBindingsTask.kt` and `Kotlin.Native.Interop/*.cs`; a
type rendered through a computed modifier string would escape that grep.

## Alternatives Considered

### Section 1: which DSL pair to rename

#### 1a. Rename the `bind {}` pair to `includeNamespaces` / `excludeNamespaces` (chosen)

28 sites, all in `bind {}` blocks, tests and six doc lines; no processor message changes; the reverse
direction is experimental behind an opt-in in 0.9.0 (ADR-181), so every `bind {}` user edits that
block in 0.9.0 anyway. The noun says what is filtered.

#### 1b. Rename the `publish {}` pair to `includePackages` / `excludePackages`

Rejected. `exclude` also takes a qualified declaration name (`exclude("com.contoso.api.Shape")`,
issue #53, `docs/topics/nuget-dsl.md:37`, `ForwardPublishedScope.kt:44-51`), so
`excludePackages` would be wrong for half its uses. About 240 sites, including 19 user-facing
processor hint strings ("add include(\"<pkg>\") to nuget { publish { } }"), in the stable direction.

#### 1c. Rename both pairs

Rejected: the cost of 1b plus 1a, and still hits the `excludePackages` misnomer.

### Section 2: task naming scheme

#### 2a. `nuget` + verb + object for every task (chosen)

Seven of thirteen names already fit `nuget` + verb + object (`nugetRestore`, `nugetImport`, `nugetExtractApi`, `nugetGenerateBindings`, `nugetGenerateShims`, `nugetReportDiagnostics`, `nugetCompileInterop`); ten are `nuget`-first, three are verb-first. The Kotlin CocoaPods plugin, which this plugin's
consumption pipeline is modelled on (`docs/research/nuget-plugin-architecture-synthesis.md:57,146`),
prefixes its pipeline with `pod` (`podGen`, `podInstall`, `podImport`) and prefixes its publish
tasks too (`podPublishXCFramework`, inferred from the
[KMP docs](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html)).

#### 2b. Verb-first for every task (`generateNugetRestoreProject`, `restoreNuget`, ...)

Rejected. Gradle-core shaped and keeps `packNuget`, but renames eleven tasks that the ADRs and the
CocoaPods synthesis already use. Churn is the same either way, about 306 against 315 occurrences
(verified by counting, see the task-name memo), so precedent decides.

#### 2c. Rename only `nugetGen`

Rejected: does not give one scheme.

### Section 3: diagnostic code scheme and schema version

#### 3a. `SCREAMING_SNAKE`, four prefixes, severity from prefix, integer `schemaVersion` (chosen)

The forward form is kept and the reverse form moves to it. About 720 forward occurrences against
about 200 reverse ones, forward is the stable direction, and its console shape is ADR-064's
contract.

#### 3b. Lowercase `snake_case` everywhere

Rejected: moves the stable direction to match the experimental one.

#### 3c. Numbered IDs (`KNN0001`, Roslyn style)

Rejected: opaque, and nothing in Gradle, KSP or this plugin suppresses a diagnostic by ID.

#### 3d. Keep `NugetDiagnostics.json` an array and add a header element

Rejected: every consumer would have to skip a non-diagnostic element forever; one clean break in
0.9.0 is cheaper.

### Section 4: what happens to the old names

#### 4a. Fail-fast tombstones for 0.9.x, deleted in 0.10.0 (chosen)

ROADMAP.md:12 puts every break in 0.9.0 and none in 0.10.0 or 1.0.0. A working deprecated alias is
therefore either removed in 0.10.0 (a break) or frozen into 1.0.0's semver surface. A tombstone
that never works breaks nothing that *ran* in 0.9.x when it is deleted, and still tells a stale
build script the new name. The DSL half is airtight (an ERROR-level deprecation already fails
compilation). The task half has one residual: a script that only *configures* the old name
(`tasks.named("packNuget") { dependsOn(...) }`, a `finalizedBy`) succeeds against the tombstone in
0.9.x and fails with `UnknownTaskException` in 0.10.0. Choosing between 4a and 4c is choosing
whether that edge case is acceptable.

#### 4b. Working deprecated aliases

Rejected for the reason above. Not researched: how KGP or AGP handled their own task renames; the release-policy constraint in ROADMAP.md:12 decides this regardless of their precedent.

#### 4c. Clean break, no tombstones

Viable; the migration guide (ROADMAP.md:47) carries the mapping. Rejected only because a tombstone
costs a few lines and saves every CI script a search.

## Decision

### 1. DSL

`bind {}` gets `includeNamespaces(vararg namespace: String)` and
`excludeNamespaces(vararg namespace: String)`. `publish {}` keeps `include` / `exclude` (with
`rootPackage`, `admit` and `exportMarkers` unchanged). After 0.9.0 the bare verbs appear only in
`publish {}`, where they can only mean Kotlin declarations.

```kotlin
nuget {
  publish {
    include("com.contoso.api")
    exclude("com.contoso.api.Legacy")
  }
  dependencies {
    dependency("Test.Text", version = "1.0.0") {
      bind {
        includeNamespaces("Test.Text")
        excludeNamespaces("Test.Text.Internal")
        alias("Test.Text", "sample.text")
      }
    }
  }
}
```

The KSP option keys (`nuget.includePackages`, `nuget.excludePackages`, `NugetPlugin.kt:339-340`)
and the task inputs (`namespaceIncludes`, `namespaceExcludes`, `NugetPlugin.kt:122-123`) are
plumbing and do not change.

### 2. Task names

`nuget` + verb + object, lowerCamelCase, verbs from one set: Generate (writes files), Restore,
Import, Extract, Report, Compile, Pack, Publish.

| Old | New |
|---|---|
| `nugetGen` | `nugetGenerateRestoreProject` |
| `packNuget` | `nugetPack` |
| `publishNuget` | `nugetPublish` |
| `publishNugetTo<Name>Repository` | `nugetPublishTo<Name>Repository` |
| `nugetSnapshotVersion` | `nugetGenerateSnapshotVersion` |
| `nugetSnapshotVersionProps` | `nugetGenerateSnapshotVersionProps` |
| `nugetRestore`, `nugetImport`, `nugetExtractApi`, `nugetGenerateBindings`, `nugetGenerateShims`, `nugetReportDiagnostics`, `nugetCompileInterop` | unchanged |

This supersedes [ADR-165](165-publish-nuget-task.md)'s task-name prefix only: the
`To<Name>Repository` tail and the `publishing` group, the parts that make the publish tasks read like
`maven-publish`'s, are kept. Every name moves into one internal `NugetTaskNames` object; the
string lookups at `NugetPlugin.kt:530,536` and the hints at `NugetExtractApiTask.kt:43,73` read it.

### 3. Diagnostic codes and schema versions

- Codes are `SCREAMING_SNAKE_CASE` in both directions. `RirDiagnosticKind` loses its lowercase
  `@SerialName`s; `Program.cs` writes the uppercase strings.
- One prefix set, severity derived from it, in both directions: `SKIPPED_` (warning, the declaration
  or member is absent), `WARNING_` (warning, generated with a caveat, declares its own verb),
  `INFO_` (note), `ERROR_` (fails the build). `RirDiagnosticKind` gets forward's `init` check and a
  derived `severity`, replacing `kind.name.startsWith(...)` at `NugetGenerateBindingsTask.kt:7135,7321`.
- Codes are unique across directions (verified disjoint today by `comm -12` over both enums) and carry
  no direction marker.
- One console shape in both directions, ADR-064's: `[nuget:<CODE>] <Verb> <location>: <reason>. <hint>`.
  The reverse location is `<packageId>/<Type>.<member>(<sig>)`, so the package id leaves the bracket.
  Today reverse warnings print no code at all and errors print it lowercased after the package id
  (`NugetGenerateBindingsTask.kt:7147,7320-7328`, verified by reading).
- Both files get a root integer `schemaVersion`, each with its own counter, starting at `1`, bumped
  only on a change a reader that ignores unknown keys would misread. `NugetDiagnostics.json` becomes
  `{ "schemaVersion": 1, "diagnostics": [ ... ] }`; `reverse-ir.json` becomes
  `{ "schemaVersion": 1, "assemblies": [ ... ] }`. The name avoids `version`, which beside NuGet
  package ids reads as a package version.
- The plugin's `NugetDiagnostics.json` reader moves from the hand-rolled token walk
  (`NugetReportDiagnosticsTask.kt:112-160`) to kotlinx.serialization, already a plugin runtime
  dependency (`RirParsing.kt:7-9`, verified by reading), and fails fast naming the file when
  `schemaVersion` is absent or unknown. The processor's writer stays hand-rolled (ADR-100's
  no-JSON-dependency reason); its "every value is a string" contract narrows to the diagnostic
  fields.
- `RirFile.schemaVersion` is `Int? = null` in Kotlin so inline test fixtures keep parsing; the check
  lives in the `nugetGenerateBindings` and `nugetGenerateShims` task actions.
- `scripts/verify-forward-diagnostics.sh:128-129` iterates the file's root as a list and must read
  `["diagnostics"]` (verified by reading).

Inferred, not verified: Gradle reruns `nugetExtractApi` after a plugin upgrade because the task's
implementation classpath is an up-to-date input, so a stale pre-0.9.0 `reverse-ir.json` should not
reach the schema check. If wrong, the check fires loudly; nothing is silently misread.

Inferred, not verified: a plugin-version mismatch between the `NugetDiagnostics.json` writer and
reader can only come from a stale file, because the plugin pins the processor to its own version
(`NugetPlugin.kt:236-237`, verified by reading); the inference is that KSP's cache key includes the
processor artifact.

Implemented (2026-10-02, this section only; sections 1 and 2, the DSL filter and task renames, land
separately):

- `RirDiagnosticKind` has no `@SerialName` left; the enum name is the wire code. It derives
  `severity` (`RirDiagnosticSeverity`) and `verb` from the prefix, and any other prefix, or a
  `WARNING_` kind with no declared verb, fails at class init. `NugetGenerateBindingsTask` and
  `RirCensus` read the derived severity instead of `kind.name.startsWith(...)`.
- Reverse warnings and errors share one `formatDiagnostic`. A real `packNuget` prints, for example,
  `[nuget:SKIPPED_INDEXER] Skipping MimeMapping/MimeUtility.TypeMap(TypeMap): indexer ...`; the old
  `w: ` text prefix is gone, as on the forward re-emitter, where Gradle's WARN level comes from the
  logger call.
- `NugetDiagnostics.json` writes `{ "schemaVersion": 1, "diagnostics": [ ... ] }` (still hand-rolled);
  `parseForwardDiagnostics` checks the version on the parsed `JsonElement` before it decodes any
  entry, so a pre-0.9.0 bare array fails as "no schemaVersion", naming the file.
  `RirFile.requireCurrentSchema(source)` is the reverse check, called by both task actions.
- `RirDiagnosticKindTest` reads `ForwardDiagnostic.kt` as text (path passed in by the plugin's
  `test` task) and asserts no code is shared. It also asserts that it extracted at least 30 forward
  codes covering all four prefixes, so the check cannot pass on an empty match.
- The memo missed two more readers of the bare array: `IntegrationTests/DependencyAdmissionTests.cs`
  and `SealedSubclassMethodTests.cs` called `RootElement.EnumerateArray()`. Both now read
  `["diagnostics"]` and assert `schemaVersion` 1. `BoxesRoundTripTests.cs` asserts it on
  `reverse-ir.json`.
- Only the casing changed in the dogfood census goldens (`*.census.json`, `SUMMARY.md`); every
  count is unchanged.

### 4. Old names

- `NugetBindConfig.include` / `exclude` stay in 0.9.x as
  `@Deprecated(level = DeprecationLevel.ERROR, ReplaceWith("includeNamespaces(*namespace)"))`
  (and the `exclude` twin), and are deleted in 0.10.0.
- `packNuget` and `publishNuget` stay in 0.9.x as tasks whose action throws
  "`packNuget` was renamed to `nugetPack` in 0.9.0", and are deleted in 0.10.0. The other renamed
  tasks get no tombstone.
- Diagnostic codes and the JSON files get no compatibility shim; `schemaVersion` is the signal.

## Consequences

- Breaking in 0.9.0: every `bind { include(...) }` (stale scripts fail to compile with a quick-fix),
  every `packNuget` / `publishNuget` invocation in CI (fails naming the new task), every reverse
  diagnostic consumer matching lowercase codes, and every `NugetDiagnostics.json` reader expecting an
  array. The 1.0.0 migration guide lists all four.
- Docs: `docs/topics` (`nuget-dsl.md`, `declaring-dependencies.md`, `bind-nuget-package-for-kotlin.md`,
  nine pages naming `packNuget`, three naming reverse codes, `forward-overview.md`'s
  `NugetDiagnostics.json` section), `README.md`, `AGENTS.md`, `.github/workflows`, `scripts/`,
  and the agent skill files under `.claude/` and `.codex/`. Accepted ADRs are history and are not
  rewritten.
- The 1.0.0 stability policy names `schemaVersion` as the compatibility signal for both files and
  says additive fields never bump it.
- Overlaps: ADR-180 rewrites `NugetBindConfig.kt`, the `afterEvaluate` blocks that register every
  task, and `NugetPlugin.kt:122-123`; the configurable-TFM item edits the `nugetGen` registration
  block (`NugetPlugin.kt:89`); the internalise + `explicitApi()` item decides whether `PackNugetTask`,
  `PublishNugetTask` and `NugetGenTask` stay public (if they do, they rename to `NugetPackTask`,
  `NugetPublishTask`, `NugetGenerateRestoreProjectTask` with their tasks) and touches the `rir`
  package. Land ADR-180 first, or share a worktree.
- Deferred: a published JSON Schema (`$schema`) for either file; the producing plugin version inside
  `NugetDiagnostics.json`; a `schemaVersion` on the dogfood `*.census.json`; renaming the JSON field
  `kind` to `code`.
