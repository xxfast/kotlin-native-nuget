# ADR-182: One public naming scheme for the DSL filters, the task names and the diagnostic codes, with a schema version on both JSON outputs

## Status

Accepted

Revised 2026-10-02: section 2 (task names) changed from `nuget<Verb><Object>` for every task to the two-part rule matching Kotlin's Gradle plugin; only `nugetGen` is renamed. Sections 1 and 3 are unchanged.

## Context

At 1.0.0, semver covers the Gradle DSL, the task names, the diagnostic codes and
`NugetDiagnostics.json` (ROADMAP.md:12), and 0.9.0 is the one release allowed to break them. Three
0.9.0 items (ROADMAP.md:19-21) and the Open decisions line (ROADMAP.md:55) found the names
inconsistent:

- `include` / `exclude` filter Kotlin packages (and, for `exclude`, declarations) in `publish {}`
  (`NugetPublishConfig.kt:66-72`) and C# namespaces in `bind {}` (`NugetBindConfig.kt:14-15`).
  Verified by reading.
- `nugetGen` does not say what it generates, and nothing in the task names states a rule for which
  tasks are verb-first (`packNuget`, `publishNuget`, `publishNugetTo<Name>Repository`) and which are
  `nuget`-first (`nugetRestore`, `nugetExtractApi`, ...) (`NugetPlugin.kt:73-654`). Verified by reading.
  Verified by reading.
- Forward diagnostic codes are `SCREAMING_SNAKE` (`ForwardDiagnosticKind`, 34 entries,
  `ForwardDiagnostic.kt:140-490`); reverse codes serialize as lowercase `snake_case`
  (`RirDiagnosticKind`, 36 entries: 26 `SKIPPED_`, 8 `INFO_`, 2 `ERROR_`; `@SerialName("skipped_...")`, `RirModel.kt:399`), and the C#
  metadata reader writes the lowercase strings itself (`NugetMetadataReader/Program.cs`, 27 sites).
  Neither `NugetDiagnostics.json` (a bare array, `ForwardDiagnosticsFile.kt:51-77`) nor
  `reverse-ir.json` (`RirFile(assemblies)`) carries a version. Verified by reading.

The research memos that carried the full inventories and counts (`include-exclude-rename`,
`task-name-scheme`, `diagnostic-code-scheme`) were deleted at close-out.

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

#### 2a. Two-part rule matching Kotlin's Gradle plugin; rename only `nugetGen` (chosen)

Tasks that produce or publish the artifact are verb-first (`packNuget`, `publishNuget`,
`publishNugetTo<Name>Repository`); steps that drive the external .NET/NuGet tool take the `nuget`
prefix (`nugetRestore`, `nugetExtractApi`, `nugetGenerateBindings`, `nugetGenerateShims`,
`nugetCompileInterop`, `nugetReportDiagnostics`, `nugetImport`, `nugetSnapshotVersion`,
`nugetSnapshotVersionProps`). This is the split in kotlin-gradle-plugin 2.4.10 (verified in its
sources): packaging and publishing tasks are verb-first (`assemble<Name>XCFramework`,
`link<Build><Kind><Target>`, `archiveUklib`, `embedAndSign...`, and maven-publish's
`publish<Pub>PublicationTo<Repo>Repository`), while the tasks that wrap an external tool carry the
tool's prefix (CocoaPods `podInstall`, `podGen`, `podPublishXCFramework`; npm `kotlinNpmInstall`).
`pack` is NuGet's own verb (`dotnet pack`), as `assemble` is Gradle's. The names already followed
this split. The one outlier is `nugetGen`, which does not say what it generates, so it becomes
`nugetGenerateRestoreProject`.

#### 2b. `nuget<Verb><Object>` for every task (`nugetPack`, `nugetPublish`, ...)

Rejected. This was the first version of this decision. It rested on one precedent,
`podPublishXCFramework`, which is KGP's tool-wrapper exception, not its packaging convention.
Following it renamed `packNuget`, `publishNuget`, `publishNugetTo<Name>Repository` and the two
snapshot tasks, and broke ADR-165's names, for a uniformity KGP itself does not have.

#### 2c. Verb-first for every task (`generateNugetRestoreProject`, `restoreNuget`, ...)

Rejected. Renames the eleven tool-driving tasks that the ADRs and the CocoaPods synthesis
(`docs/research/nuget-plugin-architecture-synthesis.md:57,146`) already use, against KGP's own
`pod*` and `kotlinNpm*` precedent.

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

#### 4a. Fail-fast tombstones for 0.9.x, deleted in 0.10.0

ROADMAP.md:12 puts every break in 0.9.0 and none in 0.10.0 or 1.0.0. A working deprecated alias is
therefore either removed in 0.10.0 (a break) or frozen into 1.0.0's semver surface. A tombstone
that never works breaks nothing that *ran* in 0.9.x when it is deleted, and still tells a stale
build script the new name. The DSL half is airtight (an ERROR-level deprecation already fails
compilation). The task half has one residual: a script that only *configures* the old name
(`tasks.named("nugetGen") { dependsOn(...) }`, a `finalizedBy`) succeeds against the tombstone in
0.9.x and fails with `UnknownTaskException` in 0.10.0. Choosing between 4a and 4c is choosing
whether that edge case is acceptable. Rejected for the tasks: deleting the tombstones in 0.10.0 is
itself a removal, and 0.10.0 must have no breaks. The DSL half keeps its ERROR-level deprecation,
because deleting a symbol nothing can compile against breaks no build.

#### 4b. Working deprecated aliases

Rejected for the reason above. Not researched: how KGP or AGP handled their own task renames; the release-policy constraint in ROADMAP.md:12 decides this regardless of their precedent.

#### 4c. Clean break for the tasks, no tombstones (chosen)

The migration guide (ROADMAP.md:47) carries the mapping, and Gradle's own "Task 'nugetGen' not
found" fails the build at the first invocation in 0.9.0, the one release allowed to break. A
tombstone would save a CI script a search but would have to be deleted in 0.10.0, which allows no
breaks; it would also make `tasks.named("nugetGen")` configure cleanly in 0.9.x and fail in 0.10.0.

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

The `ListProperty` behind each function takes the same name (`includeNamespaces`,
`excludeNamespaces`), keeping the file's property-plus-same-named-function idiom; the old `include` /
`exclude` properties are removed outright, since ADR-180 already broke them (`List<String>` to
`ListProperty<String>`) in this same 0.9.0.

The KSP option keys (`nuget.includePackages`, `nuget.excludePackages`, `NugetPlugin.kt:339-340`)
and the task inputs (`namespaceIncludes`, `namespaceExcludes`, `NugetPlugin.kt:122-123`) are
plumbing and do not change.

### 2. Task names

Two-part rule, as in Kotlin's Gradle plugin:

- Tasks that produce or publish the artifact are verb-first: `packNuget`, `publishNuget`,
  `publishNugetTo<Name>Repository`.
- Steps that drive the external .NET/NuGet tool take the `nuget` prefix: `nugetRestore`,
  `nugetExtractApi`, `nugetGenerateBindings`, `nugetGenerateShims`, `nugetCompileInterop`,
  `nugetReportDiagnostics`, `nugetImport`, `nugetSnapshotVersion`, `nugetSnapshotVersionProps`.

The only rename:

| Old | New |
|---|---|
| `nugetGen` | `nugetGenerateRestoreProject` |

[ADR-165](165-publish-nuget-task.md)'s task names stand. Every name moves into one internal
`NugetTaskNames` object; the string lookups at `NugetPlugin.kt:530,536` and the hints at
`NugetExtractApiTask.kt:43,73` read it.

The task class renames with its task, because a build script can name it in
`tasks.withType<...>()` and ADR-183 freezes it as public API:

| Old class | New class |
|---|---|
| `NugetGenTask` | `NugetGenerateRestoreProjectTask` |

`PackNugetTask`, `PublishNugetTask`, `NugetSnapshotVersionTask` and `NugetSnapshotVersionPropsTask`
keep their names.

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

Implemented (2026-10-02, diagnostics half; the DSL filter and task renames follow below):

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

- The functions `NugetBindConfig.include` / `exclude` stay in 0.9.x as
  `@Deprecated(level = DeprecationLevel.ERROR, ReplaceWith("includeNamespaces(*namespace)"))`
  (and the `exclude` twin), and are deleted in 0.10.0.
- `nugetGen` is a clean break (4c): no task is registered under the old name, and the old task
  class is gone. A stub would have to be removed in 0.10.0, which must have no breaks.
- Diagnostic codes and the JSON files get no compatibility shim; `schemaVersion` is the signal.

Implemented (2026-10-02, sections 1, 2 and 4, the DSL filter and task-name half):

- `NugetBindConfig` has `includeNamespaces` / `excludeNamespaces` (property and vararg function);
  the old functions are ERROR-level deprecations forwarding to them, asserted reflectively in
  `NugetTaskNamesTest`. Fixture build scripts, README and the docs pages use the new names.
- `NugetTaskNames` holds every task name; every `register(...)` / `named(...)` in the plugin and the
  messages that name a task (`NugetExtractApiTask`, `NugetPackTask`, `NugetPusher`, the task
  descriptions) read it. `NugetTaskNamesTest` asserts all thirteen names are registered, that the
  packaging tasks are verb-first and the tool-driving ones `nuget`-prefixed, and that `nugetGen` is gone.
- `NugetGenTask`, its file and its tests renamed as in the table above.

## Consequences

- Breaking in 0.9.0: every `bind { include(...) }` (stale scripts fail to compile with a quick-fix),
  every `nugetGen` invocation (Gradle's "Task not found"; the migration guide
  names the new task), every `tasks.withType<NugetGenTask>()`, every reverse
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
  block (`NugetPlugin.kt:89`); the internalise + `explicitApi()` item decides whether `NugetGenTask`
  stays public (if it does, it renames to `NugetGenerateRestoreProjectTask` with its task) and touches the `rir`
  package. Land ADR-180 first, or share a worktree.
- Deferred: a published JSON Schema (`$schema`) for either file; the producing plugin version inside
  `NugetDiagnostics.json`; a `schemaVersion` on the dogfood `*.census.json`; renaming the JSON field
  `kind` to `code`.
