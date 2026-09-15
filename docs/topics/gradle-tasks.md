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
| `packNuget` | Packages the Kotlin/Native shared library as a NuGet package | the shared-lib link tasks, `kspKotlin{Target}`, `nugetReportDiagnostics`, `nugetCompileInterop`, `nugetGenerateShims` (only if the project also binds a dependency), and `nugetSnapshotVersion`/`nugetSnapshotVersionProps` (only when `snapshot = true`) |
| `nugetReportDiagnostics` | Reports declarations the forward bridge could not generate | `kspKotlin{Target}` |
| `nugetCompileInterop` | Compiles the generated C# bindings with dotnet before packNuget stages them | `kspKotlin{Target}`, `nugetGenerateShims` (only if the project also binds a dependency) |

`packNuget` writes the staged package to `build/nuget/{packageId}.{version}/` and the zipped
`.nupkg` to `build/nuget/{packageId}.{version}.nupkg`. It reads the C# KSP generates at
`build/generated/ksp/{firstTarget}/{firstTarget}Main/resources/`, and when the project also binds
a dependency, merges the reverse-direction C# shims from `nugetGenerateShims` into the same
`contentFiles/cs/any/` folder and pins each bound dependency at its exact resolved version in the
`.nuspec` `<dependencies>` block.

`nugetReportDiagnostics` reads `NugetDiagnostics.json` from the same KSP resources directory
(a declared KSP task output, so it is present even when `kspKotlin{Target}` reports `FROM-CACHE` or
`UP-TO-DATE`) and re-emits each pre-rendered message as a Gradle warning, so a skipped declaration
is visible on every `packNuget`, not only the first. It is never up to date itself. See
[Forward overview](forward-overview.md#where-these-messages-appear) and
[ADR-100](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/100-forward-diagnostic-delivery.md).

### `nugetCompileInterop` {id="nugetcompileinterop"}

`nugetCompileInterop` writes a throwaway `interop-check.csproj` to `build/nuget-compile/`, with one
`<Compile Include>` per file `packNuget` would stage (the KSP-generated `Interop.cs`, plus any
reverse-direction shims from `nugetGenerateShims`) and one exact-version `PackageReference` per
bound dependency:

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net8.0</TargetFramework>
    <LangVersion>12.0</LangVersion>
    <Nullable>enable</Nullable>
    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
    <AllowUnsafeBlocks>true</AllowUnsafeBlocks>
    <GenerateDocumentationFile>true</GenerateDocumentationFile>
    <NoWarn>$(NoWarn);CS1591</NoWarn>
  </PropertyGroup>
  <ItemGroup>
    <Compile Include="/path/to/build/generated/ksp/macosArm64/macosArm64Main/resources/Interop.cs" />
    <!-- one line per generated or shim .cs file -->
  </ItemGroup>
  <ItemGroup>
    <PackageReference Include="MimeMapping" Version="[4.0.0]" />
    <PackageReference Include="TestDependency" Version="[1.0.0-fixture.1789351141258]" />
  </ItemGroup>
</Project>
```

Beside the csproj it also writes a `global.json` containing `{}` and a `NuGet.config` that clears
the package sources and adds `nuget.org`, and it builds with
`-p:ImportDirectoryBuildProps=false -p:ImportDirectoryBuildTargets=false -p:ImportDirectoryPackagesProps=false`
and `-p:RestoreConfigFile` pointing at that config. The check therefore picks its own SDK and feeds:
a `global.json`, `Directory.Build.props`, `Directory.Build.targets`, `Directory.Packages.props` or
`NuGet.config` anywhere above `build/nuget-compile/` cannot change its result.

It then runs `dotnet build` against it. If a generated file does not compile, `packNuget` fails
with the compiler's own output, for example:

```
[nuget] The generated C# bindings do not compile (dotnet build exit code 1). This is a generator
defect: the package would fail in every consumer's build. Compiler output:
.../Interop.cs(29579,59): error CS0101: The namespace 'TestLibrary' already contains a definition for 'Cat'
Build FAILED.
```

When `dotnet` is not found on `PATH`, the task logs a warning and returns without writing or
compiling anything, so publishing a Kotlin/Native library still needs no .NET SDK. An SDK that is on
`PATH` but cannot run (`dotnet --version` fails) skips the check with the same kind of warning,
rather than reporting a compile failure. See
[Prerequisites](prerequisites.md) and
[ADR-138](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/138-pack-time-interop-compile-check.md).

Registered only when `nuget { publish { snapshot = true } }` is set:

| Task | Description | Writes |
|---|---|---|
| `nugetSnapshotVersion` | Mints a unique snapshot version for this build | `build/nuget-snapshot-version.txt` |
| `nugetSnapshotVersionProps` | Writes the MSBuild props file pinning the current snapshot version | `versionPropsFile`, default `<rootProject>/build/{packageId}Versions.props` |

Both are `@DisableCachingByDefault` and never up to date, since a snapshot build's whole point is a
fresh timestamp every run. See [The nuget {} DSL](nuget-dsl.md) for `snapshot`/`versionPropsFile`
and [Publish a Kotlin/Native library as NuGet](publish-kotlin-library-as-nuget.md) for the consumer
side.

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
