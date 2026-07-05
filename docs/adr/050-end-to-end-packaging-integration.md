# ADR-050: End-to-end packaging integration — sample package selection, real `.nupkg` + local-feed consumption, `contentFiles` shim merge, resolved-version `<dependencies>`, and single-`afterEvaluate` bridging

## Status

Accepted

## Context

ROADMAP Phase 8's capstone (line 139) is "consume a bound NuGet package from Kotlin in
`sample-library` and exercise it end-to-end from `sample-app` (`SampleApp.Tests` round-trip)."
ADR-048 (Kotlin stub generation) and ADR-049 (C# shim generation) built the two generators and
each explicitly **deferred four concrete pieces of integration work to this item**:

1. Extend `PackNugetTask` to merge `build/nuget-interop/csharp/*.cs` (the reverse shims) into the
   packed package's `contentFiles/cs/any/` alongside KSP's forward `Interop.cs`.
2. Emit `<dependencies>` in the generated `.nuspec` for each bound package at its **exact resolved
   version** (read from `project.assets.json`, not the DSL-declared/floating version).
3. Demonstrate a real end-to-end round trip in the samples, exercised from `SampleApp.Tests`.
4. Bridge the two currently-independent `afterEvaluate` blocks in `NugetPlugin.kt`
   (`publish {}`/`packNuget` and `dependencies { bind {} }`/`nugetGenerateShims`) so a project that
   uses both together produces one coherent package.

This ADR does **not** renegotiate ADR-041/043/048/049. It records the decisions made while
implementing those four pieces, and two additional decisions this capstone forced into the open:
**which real package can anchor the demo** (the ROADMAP's "e.g. `Newtonsoft.Json`" turns out to be
unbuildable under the v1 subset), and **how the sample app actually consumes the package** (the
existing dev loop wires raw folder paths, not a real `PackageReference`).

### The round trip being proven

```
C# SampleApp.Tests
  → (forward bridge, KSP-generated Interop.cs)      MimeSample.mimeTypeFor("data.json")
    → Kotlin sample-library                          fun mimeTypeFor(f) = MimeUtility.getMimeMapping(f)
      → (reverse bridge, ADR-048 stub + ADR-049 shim) mimemapping.MimeUtility.getMimeMapping
        → real C# NuGet package (MimeMapping)          MimeUtility.GetMimeMapping("data.json")
        ← "application/json"
```

Both bridge directions and the full resolve→generate→pack→restore→run pipeline are exercised by a
single passing xunit test.

### Prior decisions that constrain this ADR

- **ADR-043**: v1 bridgeable subset — static methods only; `void`/`string`/primitive parameters and
  returns; **overload sets excluded** (the metadata reader groups methods by name and drops any
  group of size > 1, `nuget-metadata-reader/Program.cs`).
- **ADR-048/049**: the two generators and the registration contract (export name, parameter order,
  string ownership) they share via `RirBridging.bridgeableStaticMethods`.
- **ADR-001**: forward `Interop.cs` ships pre-generated in `contentFiles/cs/any/`; "no consumer-side
  build callback." `PackNugetTask` already emits `build/{id}.targets` with `AllowUnsafeBlocks`.
- **ADR-045**: `net8.0` restore floor; `project.assets.json` is the inter-task manifest.

## Alternatives Considered

### 1. Newtonsoft.Json as the worked example (rejected — structurally impossible under v1)

The ROADMAP names `Newtonsoft.Json` as the example. It cannot produce a single binding under the
v1 subset. Every static method on `JsonConvert` is an overload set — `SerializeObject` (8
overloads), `DeserializeObject` (5), `ToString` (25), etc. — and ADR-043's filter
(`nuget-metadata-reader/Program.cs`, `ProcessType`) groups methods **by name only** and skips any
group of size > 1 wholesale. There is no single-overload static method anywhere on `JsonConvert`,
and the other public types take `object`/`JToken` parameters outside the v1 vocabulary. Result:
`nugetGenerateBindings` emits its own documented "No v1-bridgeable methods found" diagnostic for
every type and produces **zero stubs**.

Making Newtonsoft.Json work would require implementing overload-set disambiguation across the
reader and both generators — the Phase 9 "revisit the ADR-043 overload exclusion" item — which is
a much larger feature than this capstone. Recorded here permanently so a future contributor does
not "fix" the demo back to Newtonsoft.Json and rediscover the same dead end.

### 2. MimeMapping.MimeUtility.GetMimeMapping(string): string (chosen)

[`MimeMapping`](https://www.nuget.org/packages/MimeMapping/) (repo
[zone117x/MimeMapping](https://github.com/zone117x/MimeMapping), MIT, `netstandard2.0`, **zero
dependencies**, actively published) exposes exactly:

```csharp
namespace MimeMapping {
  public static class MimeUtility {
    public static string GetMimeMapping(string file);      // ← single overload, string→string: v1-bridgeable
    public static string[] GetExtensions(string mimeType); // ← array return, silently skipped (not v1 vocab)
  }
}
```

`GetMimeMapping(string): string` is a real, published, single-overload static string→string method
that survives the v1 filter unforced. `netstandard2.0` is compatible with the `net8.0` restore
floor. Its zero transitive dependencies keep piece 2 (resolved-version `<dependencies>`) minimal.
An exact version is pinned in `sample-library/build.gradle.kts` (a future release adding a second
`GetMimeMapping` overload would silently drop the stub — the pin plus this note guard against that).

### 3. `PackNugetTask.generatedCsDir: DirectoryProperty` → `ConfigurableFileCollection` (chosen)

Piece 1 needs `packNuget` to copy `.cs` from **two** producers — KSP's per-target output and
`nugetGenerateShims`'s `build/nuget-interop/csharp/`. Making the single `generatedCsDir` a
`generatedCsDirs: ConfigurableFileCollection` (ADR-049 Alternative 4's own suggestion) lets the
plugin wire `task.generatedCsDirs.from(kspOutputDir)` unconditionally and
`task.generatedCsDirs.from(nugetGenerateShims…csharpOutputDir)` only when `bound.isNotEmpty()`.
Rejected: a second `DirectoryProperty` — awkward when there is no bound dependency and does not
generalise to N producers.

### 4. Real `.nupkg` + local-feed `PackageReference` consumption (chosen) vs. manual `<Compile Include>` (rejected for the capstone)

Today `PackNugetTask` stages an *unzipped folder* (`build/nuget/SampleLibrary.1.0.0/`) and both
`sample-app` csprojs reach into it with manual `<Compile Include>` / `<None Include>` element per
file. That interim wiring (ADR-049 Alternative 7) proves nothing about the *packaging*: it bypasses
`contentFiles`, `build/{id}.targets`, and `<dependencies>` entirely — the exact machinery this
capstone exists to validate.

**Decision:** produce a real `.nupkg` and consume it as NuGet intends:

- `PackNugetTask` zips the staged folder into a valid `.nupkg` (an OPC/ZIP package with the
  `.nuspec` at the root plus the `[Content_Types].xml` and `_rels/.rels` OPC parts) written to
  `build/nuget/`.
- A repo `nuget.config` adds `build/nuget/` (via `sample-library`) as a local package source.
- `sample-app/SampleApp/SampleApp.csproj` and `SampleApp.Tests.csproj` drop the manual includes for
  a single `<PackageReference Include="SampleLibrary" Version="1.0.0" />`. NuGet then compiles
  `Interop.cs` + the merged reverse shim from `contentFiles`, imports `build/{id}.targets`
  (`AllowUnsafeBlocks`), copies the `runtimes/{rid}/native/` shared lib, and restores `MimeMapping`
  transitively from the `<dependencies>` entry (piece 2) — with **zero** hand-edited item elements.

This is the genuine "end-to-end" the ROADMAP asks for, and it exercises the `[ModuleInitializer]`
load-bearing argument from ADR-049 Alternative 4 (source compiled into the consumer's own module).
It folds in part of the "Local-feed dev loop" pre-launch item, accepted deliberately (see
Consequences). Native-asset delivery for a framework-dependent test host is a known NuGet gotcha
(`runtimes/{rid}/native/` copy without an explicit RID); if it does not copy on `dotnet test`, the
sample-app test project pins a `RuntimeIdentifier`.

### 5. Resolved-version `<dependencies>` from `project.assets.json` (chosen)

Piece 2: the `.nuspec` gains, per bound package, `<dependency id="MimeMapping" version="[x.y.z]" />`
using nuspec exact-version range syntax, where `x.y.z` is the version **NuGet actually resolved**,
read from `project.assets.json` (a new `deriveResolvedVersions()` beside the existing
`deriveDllPaths()` in `rir/RirParsing.kt`, parsing the same `libraries` keys of the form
`"{id}/{version}"`). The shim's method signatures are frozen against one specific assembly's
metadata, so the consumer must restore that exact assembly — not a floating floor that could
resolve to a version with a different surface. Only direct bound dependencies are pinned; transitive
pinning is out of scope (moot for `MimeMapping`'s zero-dependency graph).

### 6. Single merged `afterEvaluate` block (chosen) vs. cross-block `Provider` handoff (rejected)

Piece 4 exposed a latent bug: the `publish`/`packNuget` block does `requireNotNull(extension.publish)`,
so a KMP project that declares only `dependencies { bind {} }` and no `publish {}` **crashes at
configuration time**. The two blocks are also separate `afterEvaluate` callbacks; because callbacks
fire in registration order and the publish block registers first (inside `withPlugin`), the
`nugetGenerateShims` `TaskProvider` does not yet exist when `packNuget` is configured — so they
cannot cross-reference by reordering alone.

**Decision:** collapse to one `afterEvaluate` that (a) registers the resolve/extract/generate tasks
on `bound.isNotEmpty()` independent of `publish`, (b) replaces the `requireNotNull(publish)` guard
with an early return, registering `packNuget` only when `publish != null`, and (c) wires
`packNuget.generatedCsDirs.from(nugetGenerateShims…)`, `packNuget.dependencyVersions.set(…)`, and
`packNuget.dependsOn(nugetGenerateShims)` only when **both** `publish != null` and
`bound.isNotEmpty()`. This makes publish-only, consume-only, and publish+consume all valid,
composable configurations. Rejected: keeping two blocks and threading a `Provider` across them — the
ordering hazard makes it fragile, and the `requireNotNull` crash still has to be fixed regardless.

## Decision

Use **Alternatives 2, 3, 4, 5, 6**: MimeMapping as the demo package; `generatedCsDirs` as a
`ConfigurableFileCollection` merging KSP + shim output into `contentFiles/cs/any/`; a real `.nupkg`
consumed via a local-feed `PackageReference`; resolved-version exact `<dependencies>` from
`project.assets.json`; and a single merged `afterEvaluate` with early-return guards replacing the
`requireNotNull(publish)` crash.

### Deferred (kept out of this ADR / this feature)

- **dotnet-detection UX** (`local.properties` override, shared locator) and **self-heal retry** on
  transient feed failures — split to a separate ROADMAP follow-up; UX-only, no correctness bearing
  on the round trip.
- **Transitive-dependency version pinning** in `<dependencies>` — only direct bound packages pinned.
- **Overload-set disambiguation** (what would unlock Newtonsoft.Json) — Phase 9.
- Full exception propagation from a C# thunk to a Kotlin exception — Phase 11 (v1 still fast-fails
  per ADR-049).

## Consequences

- `PackNugetTask` gains real `.nupkg` production, a `generatedCsDirs: ConfigurableFileCollection`,
  and a `dependencyVersions` input driving nuspec `<dependencies>`. `rir/RirParsing.kt` gains
  `deriveResolvedVersions()`.
- `NugetPlugin.kt`'s two `afterEvaluate` blocks collapse into one; the `requireNotNull(publish)`
  crash is fixed (behaviour change: a `bind`-only project now configures instead of failing — worth
  a regression test).
- A repo `nuget.config` adds the local feed; `sample-app` csprojs become single-`PackageReference`
  consumers. `sample-library/build.gradle.kts` gains the `dependencies { dependency("MimeMapping")
  { bind {} } }` block; `sample/mime/MimeSample.kt` and `SampleApp.Tests/MimeRoundTripTests.cs` are
  added.
- The "Local-feed dev loop" pre-launch item is partially delivered here (local-feed
  `PackageReference` consumption for this repo's own dev loop).

### Breaking changes

`PackNugetTask.generatedCsDir` → `generatedCsDirs` is a task-API change internal to the plugin (no
published consumers yet). The `requireNotNull(publish)` → early-return change only *relaxes* a
previously-crashing path.

### Bugs surfaced by the first real end-to-end bound-dependency build

Compiling a bound dependency all the way through for the first time (nothing before this item did)
exposed three latent defects, now fixed and regression-tested:

1. **Metadata reader leaked non-public members.** `nuget-metadata-reader/Program.cs` filtered
   visibility with `(attrs & MethodAttributes.Public) == 0`, a buggy bitmask test — `Public` is `0x6`
   inside the 3-bit `MemberAccessMask` (`0x7`), so `internal`/`protected` members yielded a non-zero
   AND and slipped through as public. `MimeMapping.KnownMimeTypes.LookupType` (an internal method) was
   extracted and produced an uncompilable shim. Fixed to `(attrs & MemberAccessMask) == Public` for
   methods, property getters, and setters; covered by a new reader integration test.
2. **`@CName internal` registration functions were not natively exported.** The reverse registration
   `@CName` functions had been made `internal` to hide them from the forward CIR translator (which
   exports public declarations) — but `internal` also suppressed the native symbol, so the C#
   `[ModuleInitializer]` had nothing to bind and every reverse call fast-failed with "bindings are not
   registered." Fixed generally: the forward processor now **skips any `@CName`-annotated function**
   (a `@CName` function is already a C-ABI export and must never be re-wrapped), and the generated
   registration function is `public` again so `@CName` emits the export. Verified via `objdump` that
   `sample.dll` exports both `mimeTypeFor` and `nuget_mimemapping_mime_utility_register`.
3. **mingw link failed on `CoTaskMemFree`.** The ADR-048 `freeManagedString` Windows `actual` calls
   `CoTaskMemFree`; the mingw shared-lib link needs `-lole32`, added to the target's `linkerOpts`.
