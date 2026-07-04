# ADR-047: Per-package namespace filters at the reader CLI — inline stateful flags vs JSON manifest

## Status

Accepted

## Context

ROADMAP Phase 8 line 136 identifies a correctness bug: when the `nugetExtractApi` Gradle task
builds the metadata reader subprocess command, it flattens all bound packages' namespace filters
into a single global `--include`/`--exclude` list. This means Package A's include/exclude filters
are applied to Package B's assemblies, and vice versa.

### Where the flattening happens

`NugetExtractApiTask.extract()` lines 64–66:

```kotlin
val includes: List<String> = namespaceIncludes.get().values.flatten()
val excludes: List<String> = namespaceExcludes.get().values.flatten()
val cmd: List<String> = metadataReaderCommand(dotnet, toolDir, dllPaths, includes, excludes)
```

`namespaceIncludes` and `namespaceExcludes` on the task are `MapProperty<String, List<String>>`
— keyed by package ID, correctly populated by the plugin. The `.values.flatten()` call drops the
package attribution. `metadataReaderCommand` receives flat `List<String>` for both, and emits:

```
--package "Newtonsoft.Json" /dll1
--package "Acme.Lib" /dll2
--include "Newtonsoft.Json"        ← applied to BOTH packages in Program.cs
```

In `Program.cs`, `AssemblyExtractor.Extract` is called with the global `parsed.Includes` and
`parsed.Excludes` for every `(packageId, dllPath)` pair. `IsNamespaceIncluded` therefore applies
the same filter to every assembly regardless of which bound package it belongs to.

### Failure cases

**Case 1 — Package with no filter blocked by another package's include:**
```kotlin
dependency("Newtonsoft.Json") {
    bind { include("Newtonsoft.Json") }  // only want this namespace
}
dependency("Contoso.SDK") {
    bind { }  // no filter — want ALL namespaces
}
```
After flattening: `includes = ["Newtonsoft.Json"]`. The reader applies this globally; `Contoso.SDK`
namespaces like `Contoso.SDK.Auth` are incorrectly excluded.

**Case 2 — Exclude from one package leaks into another:**
```kotlin
dependency("Acme.Lib") {
    bind { exclude("Acme.Lib.Internal") }
}
dependency("Contoso.SDK") {
    bind { }
}
```
After flattening: `excludes = ["Acme.Lib.Internal"]`. If `Contoso.SDK` happens to contain a
namespace matching that prefix, it is incorrectly excluded.

**Case 3 — Include from one package filters another package's output:**
```kotlin
dependency("Pkg.A") { bind { include("Pkg.A.Core") } }
dependency("Pkg.B") { bind { include("Pkg.B.Auth") } }
```
After flattening: `includes = ["Pkg.A.Core", "Pkg.B.Auth"]`. The reader applies BOTH to every
assembly. Namespaces in `Pkg.A.dll` that match neither prefix (e.g. `Pkg.A.Data`) are excluded
even though `Pkg.A.Core` was the only declared filter for that package.

### What is already correct

The data attribution is already per-package throughout the pipeline:

- `NugetBindConfig` stores `include`/`exclude` per package in the DSL (ADR-044)
- `NugetPlugin` wires them as `MapProperty<String, List<String>>` (id → namespaces) into the task
- `deriveDllPaths()` returns `Map<String, List<String>>` (id → dll paths)
- `metadataReaderCommand` already receives `dllPaths: Map<String, List<String>>`

The bug is isolated to the two `.flatten()` calls in `extract()` and to the flat `includes`/`excludes`
parameters in `metadataReaderCommand`. The DLL→package attribution is already available; it does
not need to be threaded through any additional layer.

### CLI contract as defined in ADR-046

ADR-046 specified the CLI as:
```
dotnet NugetMetadataReader.dll \
    --package "Newtonsoft.Json" <path/to/Newtonsoft.Json.dll> \
    --package "Acme.Lib" <path/to/Acme.Lib.dll> \
    --include "Newtonsoft.Json" "Acme.Lib.Core" \
    --exclude "Newtonsoft.Json.Bson"
```

This contract is deficient for any scenario where packages have different filter configurations.
The design in ADR-046 assumed a single merged `--include`/`--exclude` was sufficient, but it is
not because `IsNamespaceIncluded` is called per assembly without package awareness.

### Multiple DLLs per package

`deriveDllPaths()` can return multiple DLL paths for a single package ID (e.g., a package shipping
`Acme.Core.dll` and `Acme.Extensions.dll` in `lib/net8.0/`). The current CLI emits:
```
--package "Acme.Lib" /path/a.dll
--package "Acme.Lib" /path/b.dll
```
Any per-package filter must be emitted with each DLL of that package, not only with the first one,
because each `--package` creates an independent entry in the parsed packages list.

## Alternatives Considered

### 1. Inline stateful per-package flags (chosen)

Extend the existing `--package id dll` syntax so that `--include` and `--exclude` following a
`--package` are parsed as belonging to that package. The parser tracks a "current package spec"
and assigns subsequent include/exclude flags to it until the next `--package` begins a new group.

**CLI shape:**
```
dotnet NugetMetadataReader.dll \
    --package "Newtonsoft.Json" /dll1 \
    --include "Newtonsoft.Json" \
    --exclude "Newtonsoft.Json.Bson" \
    --package "Acme.Lib" /dll2 \
    --include "Acme.Lib.Core" \
    --package "Contoso.SDK" /dll3
    ← no --include/--exclude here = all namespaces exposed
```

For a package with multiple DLLs, the include/exclude is repeated for each DLL:
```
--package "Acme.Lib" /dll-a --include "Acme.Lib.Core"
--package "Acme.Lib" /dll-b --include "Acme.Lib.Core"
```

**Kotlin-side (`metadataReaderCommand`) signature change:**
```kotlin
internal fun metadataReaderCommand(
  dotnet: String,
  readerProjectDir: File,
  dllPaths: Map<String, List<String>>,
  includes: Map<String, List<String>>,   // packageId → namespaces (was List<String>)
  excludes: Map<String, List<String>>,   // packageId → namespaces (was List<String>)
): List<String>
```

The function emits include/exclude per DLL for each package:
```kotlin
dllPaths.forEach { (id, paths) ->
  val packageIncludes = includes[id] ?: emptyList()
  val packageExcludes = excludes[id] ?: emptyList()
  paths.forEach { path ->
    add("--package"); add(id); add(path)
    if (packageIncludes.isNotEmpty()) { add("--include"); addAll(packageIncludes) }
    if (packageExcludes.isNotEmpty()) { add("--exclude"); addAll(packageExcludes) }
  }
}
```

**C# parser change — `CliArgs` model:**

```csharp
// replaces (string PackageId, string DllPath) tuple
internal sealed class PackageSpec
{
    public string PackageId { get; }
    public string DllPath { get; }
    public List<string> Includes { get; } = new();
    public List<string> Excludes { get; } = new();
    public PackageSpec(string id, string dll) { PackageId = id; DllPath = dll; }
}
```

**C# parser change — stateful parse loop:**

```csharp
PackageSpec? current = null;
while (i < args.Length)
{
    switch (args[i])
    {
        case "--package":
            if (i + 2 >= args.Length) return null;
            if (current is not null) result.Packages.Add(current);
            current = new PackageSpec(args[i + 1], args[i + 2]);
            i += 3; break;
        case "--include":
            i++;
            while (i < args.Length && !args[i].StartsWith("--"))
                current!.Includes.Add(args[i++]);
            break;
        case "--exclude":
            i++;
            while (i < args.Length && !args[i].StartsWith("--"))
                current!.Excludes.Add(args[i++]);
            break;
        default:
            Console.Error.WriteLine($"error: unknown argument: {args[i]}");
            return null;
    }
}
if (current is not null) result.Packages.Add(current);
```

**C# `Main` change:** pass per-package includes/excludes to `Extract`:
```csharp
var assembly = AssemblyExtractor.Extract(
    pkg.PackageId, pkg.DllPath, pkg.Includes, pkg.Excludes);
```
`AssemblyExtractor.Extract` signature is unchanged (it already takes `IReadOnlyList<string>` for
includes and excludes).

**Task action change:** stop flattening; pass maps directly:
```kotlin
val cmd: List<String> = metadataReaderCommand(
    dotnet, toolDir, dllPaths,
    namespaceIncludes.get(),
    namespaceExcludes.get(),
)
```

**Pros:**
- `metadataReaderCommand` remains a pure function (no side effects, no file I/O) — testable
  directly in `MetadataReaderCommandTest` without touching the filesystem.
- Minimal contract extension: packages with no filters just omit the adjacent `--include`/`--exclude`
  — their absence signals "all namespaces", which is backward-compatible with the current reader
  behaviour when neither flag is present.
- Arg length is bounded in practice: namespace filter lists are short (the user writes them by hand
  in the build script) and are repeated per DLL, not per class. No practical length limit is hit.
- No new temp file lifecycle to manage in `extract()`.
- Aligns with standard CLI sub-command patterns (e.g., `git` flags that follow a positional
  argument to scope them to that argument).

**Cons:**
- The parser becomes stateful; the order of `--package` and `--include`/`--exclude` arguments
  matters.
- Requires updating all existing `MetadataReaderCommandTest` cases because the function signature
  changes from `List<String>` to `Map<String, List<String>>`.

### 2. JSON manifest file (rejected)

Instead of individual flags, the Kotlin task writes a temporary JSON manifest file containing all
per-package config and passes `--manifest /path/to/manifest.json` as the sole structured argument.

**Manifest schema:**
```json
{
  "packages": [
    {
      "id": "Newtonsoft.Json",
      "dlls": ["/path/to/Newtonsoft.Json.dll"],
      "includes": ["Newtonsoft.Json"],
      "excludes": ["Newtonsoft.Json.Bson"]
    },
    {
      "id": "Acme.Lib",
      "dlls": ["/path/a.dll", "/path/b.dll"],
      "includes": ["Acme.Lib.Core"],
      "excludes": []
    }
  ]
}
```

**Pros:**
- Structured; no argument-order dependency.
- Avoids potential shell argument length limits for very large namespace filter sets.
- The manifest file is inspectable on disk for debugging.
- The C# parser becomes trivial (one `JsonSerializer.Deserialize` call).

**Cons:**
- `metadataReaderCommand` can no longer be a pure function — it must write a file (or the
  manifest writing must happen in the caller). The pure-function property of `metadataReaderCommand`
  is load-bearing: `MetadataReaderCommandTest` tests the CLI contract without any file system.
- Requires a new temp file path managed by the task action (must be deterministic relative to
  `temporaryDir` so it is Gradle-configuration-cache safe and is cleaned up between task runs).
- Adds a new schema (the manifest JSON) that both the Kotlin side and the C# side must maintain,
  analogous to maintaining a proto file without a schema registry.
- The manifest would need its own serialization model on the Kotlin side (a plain data class or
  manual JSON building), adding a dependency on the serialization library being available in the
  command builder layer.
- `nuget-metadata-reader/` must add `System.Text.Json` deserialization for the manifest alongside
  its existing serialization for the output — more code to maintain.

**Rejected** because the pure-function property of `metadataReaderCommand` is the foundation of
the existing test strategy. Adding file I/O there would require integration-style tests (temp
directories, cleanup) for a contract that is currently fully unit-testable. The inline stateful
flag approach achieves correctness with a smaller code change and preserves the existing test
architecture.

## Decision

Use **Alternative 1: inline stateful per-package flags**.

### Changed CLI contract

```
# one entry per (package, dll) pair; --include/--exclude follow immediately and are scoped to it
--package "Newtonsoft.Json" /path/Newtonsoft.Json.dll
--include "Newtonsoft.Json"
--exclude "Newtonsoft.Json.Bson"
--package "Acme.Lib" /path/Acme.Core.dll
--include "Acme.Lib.Core"
--package "Acme.Lib" /path/Acme.Extensions.dll
--include "Acme.Lib.Core"                          ← repeated for each DLL of same package
--package "Contoso.SDK" /path/Contoso.SDK.dll      ← no filters = all namespaces
```

`--include` and `--exclude` are always emitted immediately after the `--package id dll` triple
they belong to. A package with no include/exclude filter emits only the `--package` triple.

### `metadataReaderCommand` signature

```kotlin
internal fun metadataReaderCommand(
  dotnet: String,
  readerProjectDir: File,
  dllPaths: Map<String, List<String>>,
  includes: Map<String, List<String>>,
  excludes: Map<String, List<String>>,
): List<String>
```

### `NugetExtractApiTask.extract()` change

Remove the two `.flatten()` calls and pass the maps directly to `metadataReaderCommand`:

```kotlin
val cmd: List<String> = metadataReaderCommand(
  dotnet, toolDir, dllPaths,
  namespaceIncludes.get(),
  namespaceExcludes.get(),
)
```

### `CliArgs` C# model change

Replace `(string PackageId, string DllPath)` tuple with `PackageSpec(PackageId, DllPath, Includes, Excludes)`.
Replace the flat `List<string> Includes` / `List<string> Excludes` on `CliArgs` with per-entry lists on
`PackageSpec`. The `Parse` method becomes stateful (one `PackageSpec` per `--package` triple).

## Consequences

### Breaking change to the CLI contract

The existing `--include`/`--exclude` global flags after all `--package` flags are no longer
supported. Any external caller following the ADR-046 CLI schema must be updated.

Currently the only caller is `NugetExtractApiTask` (the Gradle plugin). No external consumers of
the metadata reader CLI exist (it is an internal bundled tool). The change is safe.

### Tests to update

**`MetadataReaderCommandTest`** — the function signature changes from flat lists to maps:

All six existing test cases must update the call site:
```kotlin
// old
includes = listOf("Acme.Lib", "Acme.Lib.Core"),
excludes = listOf("Acme.Lib.Internal"),

// new
includes = mapOf("Acme.Lib" to listOf("Acme.Lib", "Acme.Lib.Core")),
excludes = mapOf("Acme.Lib" to listOf("Acme.Lib.Internal")),
```

New test cases to add:

```kotlin
@Test
fun `package A include is emitted adjacent to package A dll, not after package B`()

@Test
fun `package with empty includes emits no --include flag after its --package triple`()

@Test
fun `package with no entry in includes map emits no --include flag`()

@Test
fun `package with multiple dlls repeats include filter for each dll`()

@Test
fun `two packages with different include sets do not cross-contaminate`()
// asserts: pkg A's includes appear only after pkg A's --package,
//          pkg B's includes appear only after pkg B's --package
```

### Tests unchanged

**`NugetExtractApiTaskWiringTest`** — all eight cases remain valid. The task's `@Input` properties
(`namespaceIncludes`, `namespaceExcludes`) are already `MapProperty<String, List<String>>` and are
already wired per-package by the plugin. The wiring tests assert the extension model surface, not
the command-building internals.

**`RirParsingTest`** and **`DllPathDerivationTest`** — unaffected; they test the JSON contract
and assets-file parsing, neither of which changes.

**`NugetExtractApiIntegrationTest`** — unaffected at the assertion level; it calls the reader
via `ProcessBuilder` (which uses the same `metadataReaderCommand` output). The integration test
will automatically use the updated command shape.

### Scope

**In this fix:**
- `metadataReaderCommand()` — updated signature and per-package flag emission.
- `NugetExtractApiTask.extract()` — remove the two `.flatten()` calls.
- `nuget-metadata-reader/Program.cs` — `PackageSpec`, stateful `CliArgs.Parse`, updated `Main`.
- `MetadataReaderCommandTest` — update all existing cases, add five new cases.

**Deferred (unchanged):**
- The `--include`/`--exclude` semantics inside `IsNamespaceIncluded` in `Program.cs` are unchanged:
  empty includes = all namespaces; non-empty includes = whitelist; excludes applied after includes.
- Multiple `bind {}` blocks per dependency (ROADMAP line 128) — the per-package map approach
  supports multiple filter groups per package ID at the task-input level (each `bind {}` could
  contribute additional entries), but the DSL currently allows only one `bind {}` per dependency.
  That extension is a separate ROADMAP item.
