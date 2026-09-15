# Gradle tasks

All tasks the plugin registers live in the `nuget` task group. There are two independent
registration paths: publishing (`publish {}`, requires Kotlin Multiplatform) and consuming
(`dependencies {}`). A project can use either, both, or neither.

## Publishing (Kotlin → C#)

Registered when `nuget { publish { } }` is set **and** the Kotlin Multiplatform plugin is applied.

| Task | Description | Depends on |
|---|---|---|
| `packNuget` | Packages the Kotlin/Native shared library as a NuGet package | the shared-lib link tasks, `kspKotlin{Target}`, `nugetReportDiagnostics`, `nugetCompileInterop`, `nugetGenerateShims` (only if the project also binds a dependency), and `nugetSnapshotVersion`/`nugetSnapshotVersionProps` (only when `snapshot = true`) |
| `nugetReportDiagnostics` | Reports declarations the forward bridge could not generate | `kspKotlin{Target}` |
| `nugetCompileInterop` | Compiles the generated C# bindings with dotnet before packNuget stages them | `kspKotlin{Target}`, `nugetGenerateShims` (only if the project also binds a dependency) |

`packNuget` writes the staged package to `build/nuget/{packageId}.{version}/` and the zipped
`.nupkg` to `build/nuget/{packageId}.{version}.nupkg`. When the project also binds a dependency,
it merges the reverse-direction C# shims from `nugetGenerateShims` into the same
`contentFiles/cs/any/` folder and pins each bound dependency at its exact resolved version in the
`.nuspec` `<dependencies>` block.

`nugetReportDiagnostics` re-emits every skipped-declaration message as a Gradle warning on each
`packNuget` run, so a skipped declaration stays visible even once its generating KSP task is
`UP-TO-DATE`. See [Forward overview](forward-overview.md#where-these-messages-appear).

### `nugetCompileInterop` {id="nugetcompileinterop"}

Before `packNuget` stages the package, `nugetCompileInterop` builds every file it would stage (the
KSP-generated `Interop.cs`, plus any reverse-direction shims) against an exact-version
`PackageReference` for each bound dependency, in an isolated build environment that ignores any
`global.json`, `Directory.Build.props/targets`, `Directory.Packages.props`, or `NuGet.config`
elsewhere in the repo. If a generated file does not compile, `packNuget` fails with the compiler's
own output, for example:

```
[nuget] The generated C# bindings do not compile (dotnet build exit code 1). This is a generator
defect: the package would fail in every consumer's build. Compiler output:
.../Interop.cs(29579,59): error CS0101: The namespace 'TestLibrary' already contains a definition for 'Cat'
Build FAILED.
```

When `dotnet` is not found on `PATH` (or is on `PATH` but can't run), the task logs a warning and
skips the check, so publishing a Kotlin/Native library still needs no .NET SDK. See
[Prerequisites](prerequisites.md).

Registered only when `nuget { publish { snapshot = true } }` is set:

| Task | Description | Writes |
|---|---|---|
| `nugetSnapshotVersion` | Mints a unique snapshot version for this build | `build/nuget-snapshot-version.txt` |
| `nugetSnapshotVersionProps` | Writes the MSBuild props file pinning the current snapshot version | `versionPropsFile`, default `<rootProject>/build/{packageId}Versions.props` |

Both are never up to date, since a snapshot build's whole point is a fresh timestamp every run.
See [The nuget {} DSL](nuget-dsl.md) for `snapshot`/`versionPropsFile`.

KSP's own `kspKotlin{Target}` task (registered by the Kotlin Gradle plugin, not this plugin)
generates `Interop.cs` and the Kotlin bridge wrappers. `packNuget` depends on it but does not
register it.

## Consuming (C# → Kotlin)

Registered as soon as `nuget { dependencies { } }` declares at least one dependency, independent
of `publish {}`, and even of Kotlin Multiplatform being applied.

| Task | Description | Depends on | Writes |
|---|---|---|---|
| `nugetGen` | Generates the synthetic interop.csproj for NuGet dependency resolution | (none) | `build/nuget-interop/interop.csproj` |
| `nugetRestore` | Runs dotnet restore to download declared NuGet packages | `nugetGen` | `build/nuget-interop/obj/project.assets.json` |
| `nugetImport` | IDE-sync umbrella task: resolve NuGet dependencies | `nugetRestore`, plus `nugetExtractApi` / `nugetGenerateBindings` / `nugetGenerateShims` when any dependency binds | (none, umbrella task) |

The following three are registered only when at least one dependency declares a `bind { }` block:

| Task | Description | Depends on | Writes |
|---|---|---|---|
| `nugetExtractApi` | Extracts the public API surface of bound NuGet packages into reverse-ir.json | `nugetRestore` (reads its `project.assets.json`) | `build/nuget-interop/reverse-ir.json` |
| `nugetGenerateBindings` | Generates Kotlin stubs and the C# registration contract from reverse-ir.json | `nugetExtractApi` | `build/nuget-interop/kotlin/` |
| `nugetGenerateShims` | Generates C#-side [UnmanagedCallersOnly] thunks and startup registration shims from reverse-ir.json | `nugetExtractApi` | `build/nuget-interop/csharp/` |

`nugetGenerateBindings`'s output directory is added as a Kotlin source directory on `nativeMain`
(and, per native target, `mingwMain` or `posixMain`), and every `kspKotlin{Target}` task depends on
it, so KSP sees the reverse-generated stubs before it runs. `nugetGenerateShims`'s C# output is
what `packNuget` merges in when a project both publishes and binds a dependency.

See the [nuget {} DSL reference](nuget-dsl.md) for the `publish {}` and
`dependencies { bind { } }` blocks that drive this wiring.
