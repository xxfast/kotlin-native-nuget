# Gradle tasks

All tasks the plugin registers live in the `nuget` task group. There are two independent
registration paths: publishing (`publish {}`, requires Kotlin Multiplatform) and consuming
(`dependencies {}`). A project can use either, both, or neither. Descriptions below are the exact
`description` strings each task sets, read from
`nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetPlugin.kt`.

## Publishing (Kotlin → C#)

Registered when `nuget { publish { } }` is set **and** the Kotlin Multiplatform plugin is applied.

| Task | Description | Depends on |
|---|---|---|
| `packNuget` | Packages the Kotlin/Native shared library as a NuGet package | the shared-lib link tasks, `kspKotlin{Target}`, and `nugetGenerateShims` (only if the project also binds a dependency) |

`packNuget` writes the staged package to `build/nuget/{packageId}.{version}/` and the zipped
`.nupkg` to `build/nuget/{packageId}.{version}.nupkg`. It reads the C# KSP generates at
`build/generated/ksp/{firstTarget}/{firstTarget}Main/resources/`, and when the project also binds
a dependency, merges the reverse-direction C# shims from `nugetGenerateShims` into the same
`contentFiles/cs/any/` folder and pins each bound dependency at its exact resolved version in the
`.nuspec` `<dependencies>` block.

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
(and, per native target, `mingwMain` or `posixMain`), and every `kspKotlin{Target}` task is made to
depend on it, so KSP sees the reverse-generated stubs before it runs. `nugetGenerateShims`'s C#
output is what `packNuget` merges in when a project both publishes and binds a dependency.

See the [nuget {} DSL reference](nuget-dsl.md) for the `publish {}` and
`dependencies { bind { } }` blocks that drive this wiring.
