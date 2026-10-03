# ADR-184: Configurable target framework: one floor TFM in the DSL, enforced by a TFM-scoped package layout

## Status

Accepted

## Context

Target: a library author sets one .NET target framework, and every place the plugin names a TFM uses it. A C# consumer below that TFM gets `NU1202` at restore; a consumer at or above it builds as today. The reverse direction binds the bound package's `lib/<tfm>/` assembly for that same TFM.

The plugin hardcodes `net8.0` in five places (verified by reading, 2026-10-02):

- `NugetPlugin.kt:88`: the `nugetGen` synthetic `interop.csproj` that `nugetRestore` restores (the line the ROADMAP item cites as `:75`).
- `rir/RirParsing.kt:16`: `assets.targets["net8.0"] ?: return emptyMap()`, the reverse DLL discovery `NugetExtractApiTask.kt:36` calls.
- `PackNugetTask.kt:226`: the nuspec `<group targetFramework="net8.0">`, present on every package because the `Kotlin.Native.Interop` contract is always a dependency (`NugetPackageIdentity.kt:25-28`, ADR-178).
- `NugetCompileInteropTask.kt:89`: the ADR-138 pre-pack compile check.
- `NugetRestoreTask.kt:42`: an error hint.

The forward package has no compiled TFM: generated C# ships as source in `contentFiles/cs/any/` (`PackNugetTask.kt:105-111,243`, ADR-001, ADR-050), with root `build/<id>.targets` and `runtimes/<rid>/native/`, and no `lib/`. The nuspec dependency group is the only place the package states a TFM.

**Verified by scratch spike** (SDK 10.0.301, Windows; full output in `docs/research/roadmap/configurable-target-framework.md`): with today's layout, the dependency group does **not** enforce anything. A `cs/any` package whose group is `net10.0`, consumed by `net8.0`, restores with no NU diagnostic, compiles the source, and silently drops every dependency (`error CS0103: The name 'Dep' does not exist`, zero `Dep` entries in `project.assets.json`). For a real package that is `Interop.cs` compiled without `Kotlin.Native.Interop`. The same silent drop applies today to a net6.0/net7.0 consumer of a net8.0 package (inferred by the same mechanism, not run).

The generated C# uses `ArgumentNullException.ThrowIfNull` (`cir/CirClassRenderer.kt:847`, `forward/ForwardCirPlanProjection.kt:737`), `[ModuleInitializer]` (`cir/CirRuntimeRenderer.kt:34`), `[UnmanagedCallersOnly]` and `delegate* unmanaged` (verified present by reading). Those need net6.0 or later (inferred from API docs, not compiled per TFM), and `Kotlin.Native.Interop` ships only `lib/net8.0` (`Kotlin.Native.Interop/Kotlin.Native.Interop.csproj:3`, verified). The API floor is therefore `net8.0`; the chosen floor is `net10.0`, set by the language floor (Decision).

## Alternatives Considered

### 1. One floor TFM on the extension, TFM-scoped package layout (chosen)

`nuget { targetFramework = "net11.0" }`, a lazy `Property<String>` with convention `"net10.0"`, read by all five sites. `packNuget` stages source under `contentFiles/cs/<tfm>/`, the targets file under `build/<tfm>/`, writes an empty `lib/<tfm>/_._`, and names `<tfm>` in the dependency group.

Pros: one value, one meaning (the lowest .NET a consumer can use); NuGet itself rejects lower consumers with its standard message; the reverse bind reads the same TFM the consumer runs. Cons: the package layout changes even at the default (`cs/any` becomes `cs/net10.0`, root `build/` becomes `build/net10.0/`), which every ADR naming `contentFiles/cs/any` must note.

### 2. Multi-targeting (`targetFrameworks = listOf(...)`)

Rejected. The forward source is TFM-independent and a single lowest group already serves every higher consumer (verified: group `net8.0`, consumer `net10.0`, dependency restored, build succeeded). For reverse it would need one restore and one bound assembly per TFM, with possibly different public APIs, and one Kotlin library cannot expose N stub surfaces.

### 3. Change only the restore TFM (the literal cited line)

Rejected. The nuspec group and the compile check would stay `net8.0`, so a package whose reverse shims were generated against a `lib/net10.0` assembly would advertise `net8.0` consumers it cannot serve.

### 4. Keep `cs/any`, change only the dependency group

Rejected by spike: silent dependency drop for lower consumers (Context). Adding `lib/<tfm>/_._` while keeping `cs/any` is still silent (verified: no NU1202, source compiled, dependency dropped), and `cs/<tfm>` without the placeholder is also silent (verified: no source, no dependency, no NU diagnostic).

### 5. A `build/<id>.targets` `<Error>` on `TargetFramework`

Rejected. Fires only after a restore that has already dropped dependencies, duplicates what `NU1202` says, and is MSBuild version-comparison code the plugin would own.

## Decision

Option 1.

```kotlin
nuget {
  targetFramework = "net11.0" // convention "net10.0"
  publish { packageId = "Acme.Kennel"; version = "1.0.0" }
}
```

A consumer below the floor sees, at restore (verified 2026-10-02 against the packed `TestLibrary` fixture at the default floor, from a `net8.0` scratch consumer):

```
error NU1202: Package TestLibrary 1.0.0-fixture.1790931140404 is not compatible with net8.0 (.NETCoreApp,Version=v8.0).
Package TestLibrary 1.0.0-fixture.1790931140404 supports: net10.0 (.NETCoreApp,Version=v10.0)
```

Decided (2026-10-02, gate):

1. One lazy `Property<String>` at the top level, `nuget { targetFramework = "..." }`, ADR-180 style.
2. The default is `net10.0`, not `net8.0`. .NET 8 and 9 leave support on 2026-11-10 (inferred from the Microsoft support policy, not fetched), and ADR-188 sets the generated code's language floor at C# 14, the default language of `net10.0` (inferred). Validation: exactly `netX.0` with X >= 10; a lower or malformed value fails with a `[nuget]` error naming ADR-188's C# 14 floor.
3. The package layout is TFM-scoped even at the default, so lower consumers get `NU1202`.
4. Single TFM. Platform TFMs, netstandard and .NET Framework are deferred.

Mechanism:

- **Extension.** `targetFramework: Property<String>` on `NugetExtension`, convention `"net10.0"`, top level (it drives reverse restore with no `publish {}` and the forward pack). The plugin reads it through an internal validating provider, so a bad value fails the first task that reads it, with the ADR-180 laziness intact. Accepted: exactly `netX.0`, X >= 10. Rejected with a `[nuget]` error: `net8.0`, `net9.0`, `net10`, `net48`, `netstandard2.0`, `net10.0-windows`.
- **Restore.** `NugetGenTask.targetFramework` is set from the provider. `NugetRestoreTask` gets the value as an `@Input` for its hint text.
- **Reverse discovery.** `deriveDllPaths(assetsJson, ids, targetFramework)`, with no default; `NugetExtractApiTask` gains `@get:Input targetFramework`. Verified by spike: for `<TargetFramework>net10.0</TargetFramework>` the assets file key is `"targets": { "net10.0": ...`. Inferred: the key is the normalized short name, so the exact-form validation keeps the lookup and the csproj value identical. **If this site is missed the bind silently produces nothing** (`?: return emptyMap()`); `DllPathDerivationTest` pins it.
- **Pack.** `PackNugetTask` gains `@get:Input targetFramework`: source under `contentFiles/cs/<tfm>/`, the targets file under `build/<tfm>/<id>.targets`, an empty `lib/<tfm>/_._`, nuspec `<files include="cs/<tfm>/**/*.cs" buildAction="Compile" />` with matching `<file>` entries, and `<group targetFramework="<tfm>">`. `runtimes/<rid>/native/` is unchanged.
  **Found at implementation, verified:** the spike's `S` layout with a **root** `build/<id>.targets` (variant T) does not give `NU1202`. A root `build/` file is an asset for every TFM, so the packed `TestLibrary` restored into a `net8.0` consumer with no diagnostic, imported only the targets file and the native DLL, and compiled none of the source (`Build succeeded` with nothing bound). Spike T had only been run with a `net10.0` consumer. Moving the file to `build/<tfm>/` gives `NU1202` (verified on a repacked copy and on the real `packNuget` output), and a `net10.0` consumer still imports it (`GeneratedBindingsCheck` and `IntegrationTests` compile the unsafe generated code). Root `runtimes/` does not suppress `NU1202`.
- **Compile check.** `generateCheckCsproj` takes the TFM. `LangVersion` moves to `14.0`, ADR-188's floor; `GeneratedBindingsCheck.csproj` moves to `net10.0` / `14.0` so the two property sets still match.

## Consequences

- Breaking: consumers on `net8.0` or `net9.0` can no longer use a package built at the default. They get `NU1202` at restore instead of today's working build; an author can not lower the floor below `net10.0`.
- Package layout change at every TFM, including the default: `contentFiles/cs/any/` becomes `contentFiles/cs/<tfm>/`, `build/<id>.targets` becomes `build/<tfm>/<id>.targets`, and `lib/<tfm>/_._` appears. Consumers at or above the floor see no difference (verified: `GeneratedBindingsCheck`, `IntegrationTests`, `LeakTests`, `MultiPackageTests`, `SharedExceptionTests` and the NativeAOT smoke test, all `net10.0`, against the new layout). ADR-050 and ADR-138 carry dated amendments; the `NugetGenerateBindingsTask` registry diagnostic now names `contentFiles/cs/<tfm>/`.
- The repo's net8.0 consumers (`GeneratedBindingsCheck`, `FirstPublisherConsumer`, `SecondPublisherConsumer`) move to `net10.0`.
- `Kotlin.Native.Interop` stays `lib/net8.0`; a `net10.0` package depends on it unchanged. Bound reverse packages that ship only `lib/net8.0` still restore under the `net10.0` restore project (verified: `TestDependency`).
- `nugetCompileInterop` compiles at the floor and, since the 2026-10-03 amendment, every higher framework through the installed SDK.
- Deferred: multi-targeting, platform-specific TFMs, netstandard, .NET Framework, per-dependency TFMs for reverse.
- Lands with or after ADR-180 (same `NugetExtension.kt` rewrite); textual overlap with ADR-181 and ADR-182 in `NugetPlugin.kt` and the plugin tests.

**Inferred claims in this ADR, not verified:** the net6.0 API floor of the generated code (no per-TFM compile run); the silent drop for today's net6.0/net7.0 consumers (same mechanism as the verified net8.0-under-net10.0 case, not run); the assets.json key being the normalized short name for spellings other than `netX.0`; .NET 8 end-of-support date; C# 14 being the net10.0 default language. None of these changes the chosen mechanism; the first and third are guarded by the `>= 10` and exact-form validation.

## Amendment (2026-10-03): the compile check also covers higher frameworks

`nugetCompileInterop` builds one multi-target project from the configured floor through the major of
the installed .NET SDK, so C# that breaks only on a higher framework fails the pack. The package
still ships one floor TFM, the public DSL still requires `>= net10`, and no future-TFM guarantee is
made. Details, edge cases and evidence are in the ADR-138 amendment of the same date. Verified: the
full `verify.sh --plugin` run passed and the host check logged `net10.0` with SDK 10; a floor `net9.0`
task test through SDK 10 caught a `net10.0`-only error.
