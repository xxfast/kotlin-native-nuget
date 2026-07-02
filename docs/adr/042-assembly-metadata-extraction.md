# ADR-042: Assembly metadata extraction — dotnet-SDK-based pipeline for NuGet package API discovery

## Status

Proposed

## Context

Phase 8 (ROADMAP line 123) requires the Gradle plugin to extract the public API surface of a C# NuGet
package from its .NET assembly metadata at build time so that Kotlin stubs and C#-side registration
shims can be generated without any manual annotation from the Kotlin library author.

The pipeline sketch from the synthesis document (`nugetGen` → `nugetRestore` → `nugetExtractApi` →
`nugetGenerateBindings`) contains **two intertwined sub-decisions**:

1. **Resolution** — how the plugin obtains the materialized package tree and its transitive dependency
   graph from a NuGet feed.
2. **Metadata reading** — how the plugin reads the public API surface (types, methods, properties,
   generics, custom attributes) out of the resolved `.dll` assemblies.

These are intertwined because the chosen resolution mechanism determines what artifacts are available
for the metadata-reading step and whether the .NET SDK is already a prerequisite.

### Resolution: what the options are

**Option A — generated `.csproj` + `dotnet restore`**

The plugin generates a throwaway `interop.csproj` containing `<PackageReference>` entries for each
declared NuGet dependency and a `<TargetFramework>` pinned to `net8.0` (the GOALS 5.1 floor). Running
`dotnet restore interop.csproj` then:

- Downloads all packages (direct and transitive) to the local NuGet cache.
- Writes `obj/project.assets.json` — a machine-readable JSON file containing the full transitive
  dependency graph with resolved versions, the exact file-system paths of all resolved `.dll`
  assemblies for the target framework, SHA-512 hashes, and the locations of `runtimes/{rid}/native/`
  payloads.
- Handles NuGet's Lowest Applicable Version resolution algorithm, floating versions, multi-feed
  sources, authenticated feeds, and RID-graph selection — all without the plugin re-implementing any
  of this logic.
- Can be retried with `--force-evaluate` or `--no-http-cache` for self-healing on transient feed
  failures.

This approach is directly analogous to how the CocoaPods plugin generates a synthetic `Podfile` and
calls `pod install` (which resolves and materialises the pod tree), and how spm4Kmp synthesises a
`Package.swift` manifest and calls `swift build`.

Source: [dotnet restore — .NET CLI](https://learn.microsoft.com/en-us/dotnet/core/tools/dotnet-restore) ·
[NuGet dependency resolution](https://learn.microsoft.com/en-us/nuget/concepts/dependency-resolution) ·
[project.assets.json explained](https://kimsereylam.com/dotnetcore/2018/08/17/sdk-projects-and-assets-json.html)

**Option B — pure-JVM NuGet v3 HTTP client**

The NuGet v3 protocol is a REST/JSON API (`https://api.nuget.org/v3/index.json` as service index;
direct `.nupkg` downloads from a flat-container URL like
`https://api.nuget.org/v3-flatcontainer/{id}/{version}/{id}.{version}.nupkg`). The protocol is
publicly documented and is implementable over plain HTTP without any .NET tooling. The
plugin's Gradle process could resolve and download packages entirely on the JVM.

Source: [NuGet Server API overview](https://learn.microsoft.com/en-us/nuget/api/overview) ·
[Package Content resource](https://learn.microsoft.com/en-us/nuget/api/package-base-address-resource) ·
[Unofficial NuGet protocol reference](https://joelverhagen.github.io/NuGetUndocs/)

The JVM implementation cost is, however, substantial:

- The Lowest Applicable Version (LAV) resolution algorithm applies four rules (lowest applicable
  version, floating versions, direct-dependency-wins, cousin dependencies); implementing it
  correctly, including edge cases, is non-trivial.
- Multi-feed source merging, authenticated feed credentials, per-package signed-package verification,
  and RID graph selection for `runtimes/{rid}/native/` payloads must all be re-implemented.
- No existing open-source JVM (Java or Kotlin) library implementing a full NuGet v3 client with
  transitive dependency resolution was found as of this research. The official `NuGet.Protocol`
  client library is .NET-only.

### Metadata reading: what the options are

**Option A — bundled dotnet tool using `System.Reflection.Metadata.MetadataReader`**

.NET's `System.Reflection.Metadata` package ships with every .NET SDK installation. Its `PEReader`
and `MetadataReader` classes read the ECMA-335 metadata tables from a `.dll` file on disk **without
loading or executing the assembly** — no CLR is started for the target assembly. The API exposes all
ECMA-335 tables needed to reconstruct the public surface: `TypeDefinitions`, `MethodDefinitions`,
`FieldDefinitions`, `PropertyDefinitions`, `GenericParameters`, `CustomAttributes`, and more.

Source: [MetadataReader Class](https://learn.microsoft.com/en-us/dotnet/api/system.reflection.metadata.metadatareader?view=net-9.0)

```csharp
// Example: enumerate all public type definitions from a DLL, no assembly loading
using var fs = new FileStream("Example.dll", FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
using var peReader = new PEReader(fs);
MetadataReader mr = peReader.GetMetadataReader();

foreach (TypeDefinitionHandle tdefh in mr.TypeDefinitions)
{
    TypeDefinition tdef = mr.GetTypeDefinition(tdefh);
    TypeAttributes attrs = tdef.Attributes;
    if (!attrs.HasFlag(TypeAttributes.Public)) continue;

    string ns = mr.GetString(tdef.Namespace);
    string name = mr.GetString(tdef.Name);
    // enumerate tdef.GetMethods(), tdef.GetProperties(), tdef.GetGenericParameters(), etc.
}
```

A small `dotnet tool` — a `dotnet run`-executable console application — wraps this logic and emits
a JSON reverse-IR model (the analogue of the forward-direction CIR). The Gradle `nugetExtractApi`
task invokes it as a subprocess, passing the list of DLL paths discovered from `project.assets.json`,
and consumes its JSON output for the `nugetGenerateBindings` stage. The tool never executes any code
from the target assemblies; it only reads bytes from the file.

This is the same no-execution principle that the `MetadataLoadContext` API is also designed for:
"Enables you to inspect assemblies without loading them into the main execution context, with
assemblies treated only as metadata so you can read information about their members but cannot
execute any code."

Source: [MetadataLoadContext (higher-level alternative)](https://www.nuget.org/packages/System.Reflection.MetadataLoadContext)

**Option B — pure-JVM ECMA-335 parser**

The ECMA-335 specification (free from Ecma International) fully documents the PE/COFF physical layout,
the CLI metadata stream structure, and all metadata table formats (TypeDef, MethodDef, Field, Property,
GenericParam, CustomAttribute, Blob/String/GUID heaps, etc.). A JVM library could in principle read
all of this from a `.dll` file.

Source: [ECMA-335 standard](https://ecma-international.org/publications-and-standards/standards/ecma-335/)

In practice, however, no mature open-source JVM library for reading .NET CLI metadata was found
during research. The Java ecosystem has general PE-format parsers (e.g. dorkbox/PeParser for
Windows Portable Executable headers) but none that specifically navigate the .NET-specific CLI
metadata tables and heaps needed to reconstruct a type system. The research found only the
`dnlib` library (for .NET, not JVM) and the `System.Reflection.Metadata` package (also .NET-only)
as established CLI-metadata readers.

Source: [dorkbox/PeParser (PE headers only, not CLI metadata)](https://github.com/dorkbox/PeParser) ·
[dnlib (.NET only)](https://github.com/0xd4d/dnlib)

Implementing a correct ECMA-335 metadata reader from scratch on the JVM would require parsing the
PE/COFF header, locating the CLI header and metadata root, decoding the stream directory (#~ or #-,
#Strings, #Blob, #GUID, #US), implementing the compressed integer and signature blob decoders,
handling the full set of 45+ metadata tables, and correctly decoding generic signature blobs —
a project roughly as large as the rest of the plugin combined.

### Prior-art precedent for requiring a native toolchain

Every comparable Kotlin ecosystem plugin that consumes a foreign package registry requires a native
toolchain:

| Plugin | Toolchain required | Our analogue |
|---|---|---|
| CocoaPods plugin (JetBrains) | Ruby + CocoaPods + Xcode | .NET SDK |
| spm4Kmp (community) | Swift toolchain + Xcode | .NET SDK |
| Official SwiftPM import (Alpha) | Swift toolchain | .NET SDK |

No plugin in this space runs prerequisite-free. The CocoaPods plugin's `which pod` detection pattern,
explicit install-guidance error messages, and `--repo-update` self-healing retry are the established
UX template for this kind of prerequisite.

Source: [CocoaPods plugin source — AbstractPodInstallTask.kt](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/cocoapods/tasks/AbstractPodInstallTask.kt)

### The central advantage over CocoaPods and spm4Kmp

Both CocoaPods and spm4Kmp require a **compile step** to recover the API surface of a foreign
package: CocoaPods runs `xcodebuild` to compile the pod; spm4Kmp runs `swift build` to compile a
bridge package. This compile step is slow, non-cacheable, and is the largest pain point in both
pipelines.

NuGet packages ship ECMA-335 metadata inside the `.dll` assembly itself. The API surface is fully
recoverable from the binary **without compilation**. The `nugetExtractApi` task has no equivalent in
CocoaPods or spm4Kmp — it replaces their entire compile stage with a read-only binary inspection.
This means the heavy, slow `nugetRestore` step produces the artifacts that `nugetExtractApi` reads
directly, and no further native compilation is required to get to binding generation.

## Alternatives Considered

### 1. `dotnet restore` + dotnet dump tool (chosen)

Both the resolution and metadata-reading steps are backed by the .NET SDK:

- **`nugetGen`** generates a synthetic `interop.csproj` with `<TargetFramework>net8.0</TargetFramework>`
  and `<PackageReference Include="..." Version="..."/>` entries from the `nuget { dependencies { } }` DSL.
  `<RuntimeIdentifiers>` lists all Kotlin/Native target RIDs to ensure `runtimes/` payloads are
  resolved for all configured targets. The `<TargetFramework>` pin causes `dotnet restore` to fail fast
  on packages that require a higher framework version, matching the CocoaPods deployment-target
  validation pattern.

- **`nugetRestore`** runs `dotnet restore interop.csproj` and declares `obj/project.assets.json` as
  its primary output. The assets file is the inter-task handoff manifest — the NuGet-plugin equivalent
  of `PodBuildSettingsProperties` in the CocoaPods pipeline. It contains the resolved assembly paths
  for `net8.0` (or whichever TFM), which the next task reads.

- **`nugetExtractApi`** invokes a small bundled dotnet tool (shipped as a local tool alongside the
  plugin, pinned in `.config/dotnet-tools.json`, restored once per workspace with
  `dotnet tool restore`). The tool receives the list of managed `.dll` paths from `project.assets.json`,
  opens each with `PEReader` + `MetadataReader`, walks the public type surface (respecting
  `TypeAttributes.Public`, `MethodAttributes.Public`, etc.), and emits a JSON reverse-IR model as its
  stdout. The Gradle task captures this JSON as its primary output, which `nugetGenerateBindings` reads.
  The tool never loads or executes the target assemblies; it reads bytes only.

**Pros:**
- Transitive dependency resolution, multi-feed support, authenticated feeds, version conflict
  resolution, and RID asset selection are all delegated to NuGet's own client — no re-implementation.
- `project.assets.json` is a stable, documented NuGet artifact reused as the inter-task manifest.
- `MetadataReader` is a battle-tested, production-quality ECMA-335 reader shipping with every .NET SDK.
  It handles all encoding edge cases (compressed integers, signature blobs, generic instantiations,
  forwarded types) without custom code.
- No compilation of the target package is required; the `nugetExtractApi` step replaces CocoaPods'
  `xcodebuild` stage entirely.
- Self-healing retry for transient feed failures via `dotnet restore --force-evaluate`.
- Consistent with the project's fail-fast convention: the `<TargetFramework>` pin in the generated
  `.csproj` lets `dotnet restore` surface framework-incompatibility errors immediately.

**Cons:**
- Requires the .NET SDK (8+) installed on the Kotlin developer's machine. This is a non-trivial
  prerequisite for developers who only write Kotlin and do not otherwise need .NET tooling.
- The bundled dotnet tool must be published as part of the plugin's distribution, adding a release
  artifact alongside the Gradle plugin JAR.
- `dotnet restore` and the dump tool invocations are external process calls; they carry the usual
  cross-process overhead and are not natively Gradle-cacheable at the task level (though
  `project.assets.json` and the JSON output file serve as stable up-to-date-check outputs).

### 2. Pure-JVM both halves (no .NET SDK prerequisite)

Implement a NuGet v3 HTTP client in Kotlin/JVM for resolution and a custom ECMA-335 metadata parser
in Kotlin/JVM for reading. The plugin would run entirely within the Gradle JVM process with no
external tool prerequisite.

**Pros:**
- No toolchain prerequisite on the developer's machine — the only requirement remains the JVM/Gradle
  environment already needed to run the Kotlin build. This would be a unique ergonomic advantage over
  every other Kotlin ecosystem plugin that consumes a foreign registry.
- No subprocess overhead; metadata reading and dependency resolution happen in-process.

**Cons:**
- No existing JVM library for NuGet v3 resolution or ECMA-335 CLI metadata reading was found during
  research. Both halves would need to be implemented from scratch.
- Implementing a correct NuGet v3 client is non-trivial: the LAV resolution algorithm has four
  interaction rules; multi-feed merging, authenticated-feed credential providers, version-range
  semantics (e.g., `[1.0.0,2.0.0)`), and RID-graph asset selection (determining which
  `runtimes/{rid}/` payload applies to a given Kotlin/Native target) all must be handled correctly.
- Implementing a correct ECMA-335 metadata reader is equally substantial: PE/COFF physical layout
  parsing, metadata root and stream directory location, compressed integer decoding, all 45+ table
  types, signature blob decoding, and generic instantiation encoding — a project roughly as large as
  the rest of the plugin combined.
- Maintenance burden: NuGet protocol versions and .NET SDK behaviour evolve; a JVM reimplementation
  must track those changes without any upstream test suite or reference implementation to validate
  against.
- Authenticity risk: subtle deviation from NuGet's resolution algorithm could produce a different
  dependency graph than `dotnet restore` would, leading to binding-generation results that diverge
  from what the C# compiler would actually use.

### 3. `dotnet restore` for resolution + pure-JVM ECMA-335 reader

A hybrid: keep `dotnet restore` for resolution (accepting the .NET SDK prerequisite), but implement
the metadata-reading step in Kotlin/JVM to avoid the bundled dump-tool artifact.

**Pros:**
- Eliminates the need to ship and version a separate dotnet tool alongside the plugin.
- Metadata reading happens in-process without a subprocess.

**Cons:**
- The .NET SDK prerequisite is unchanged — the ergonomic argument for a pure-JVM path (no dotnet
  needed) is lost, yet the full implementation cost of an ECMA-335 reader is incurred.
- The bundled dotnet tool is a small, well-scoped program (a few hundred lines using a mature
  library); it is a far smaller investment than a correct ECMA-335 parser from scratch.
- Does not eliminate the heavyweight dependency; only removes the lighter-weight metadata-reading
  subprocess.

## Decision

Use **Alternative 1: `dotnet restore` for resolution and a bundled dotnet dump tool for metadata
reading**.

### Task graph

```
nugetGen              generate synthetic interop.csproj
    │                   <TargetFramework>net8.0</TargetFramework>
    │                   <RuntimeIdentifiers>osx-arm64;win-x64;...</RuntimeIdentifiers>
    │                   <PackageReference Include="..." Version="..."/> per declared dependency
    ▼
nugetRestore          dotnet restore interop.csproj
    │                   → obj/project.assets.json (inter-task manifest; declared as task output)
    │                   resolves transitive closure, downloads to NuGet cache
    ▼
nugetExtractApi       invoke bundled dotnet dump tool
    │                   reads DLL paths from project.assets.json (net8.0 target's libraries)
    │                   PEReader + MetadataReader → walks public TypeDef/MethodDef/PropertyDef
    │                   → reverse-ir.json (declared as task output; input to binding generation)
    ▼
nugetGenerateBindings reverse-ir.json → Kotlin stubs (KotlinPoet) + C# registration shims
    ▼
compileKotlin{Target} stubs participate in normal Kotlin/Native compilation
    ▼
nugetImport           umbrella IDE-sync task (analogous to podImport)
```

### Generated `.csproj` shape (nugetGen)

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net8.0</TargetFramework>
    <!-- RIDs for all configured Kotlin/Native targets -->
    <RuntimeIdentifiers>osx-arm64;osx-x86_64;win-x64;linux-x64</RuntimeIdentifiers>
    <!-- Suppress build outputs; this project exists only for restore -->
    <GenerateAssemblyInfo>false</GenerateAssemblyInfo>
    <OutputType>Library</OutputType>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Newtonsoft.Json" Version="13.0.3" />
    <!-- additional declared dependencies -->
  </ItemGroup>
</Project>
```

The `<TargetFramework>` pin causes `dotnet restore` to fail fast with a clear error if a declared
package requires a higher TFM, surfacing incompatibility at the `nugetRestore` step before any
binding generation begins.

### Dotnet dump tool (nugetExtractApi)

The dump tool is a small `dotnet tool` (a `<OutputType>Exe</OutputType>` console application)
distributed alongside the Gradle plugin. It receives a list of `.dll` paths on stdin or as CLI
arguments and emits a JSON reverse-IR model to stdout. The Gradle task captures the stdout as the
`reverse-ir.json` output file.

Internally the tool uses `System.Reflection.Metadata.PEReader` + `MetadataReader` to iterate the
`TypeDefinitions` table of each assembly, filter by `TypeAttributes.Public`, enumerate methods via
`GetMethods()`, properties via `GetProperties()`, generic parameters via `GetGenericParameters()`,
and custom attributes via `GetCustomAttributes()`. No assembly loading or JIT compilation of the
target code occurs; the operation is a pure binary read of the metadata sections.

### Tooling detection UX (CocoaPods pattern)

The `nugetRestore` and `nugetExtractApi` tasks detect the `dotnet` executable before running:

1. Check `dotnet.executable` in `local.properties` (allows CI and non-standard install locations to
   override).
2. Fall back to `which dotnet` / `where dotnet` on PATH.
3. If not found: fail fast with an explicit message:
   > `.NET SDK 8 or later is required to consume NuGet packages from Kotlin. Install it from
   > https://dot.net/download, then re-run Gradle sync.`
4. If found but version is below 8: fail fast with a targeted diagnostic naming the installed
   version and the minimum requirement.
5. On transient feed failure during `nugetRestore`: automatically retry with `--force-evaluate`
   (analogous to CocoaPods' automatic `--repo-update` retry).

## Consequences

### New Gradle tasks

- `nugetGen` — generates the synthetic `interop.csproj` from the `nuget { dependencies { } }` DSL.
  Inputs: declared dependencies. Output: `interop.csproj`.
- `nugetRestore` — runs `dotnet restore`. Inputs: `interop.csproj`. Output: `obj/project.assets.json`.
  Annotated `@DisableCachingByDefault` (external process, analogous to `PodInstallSyntheticTask`).
- `nugetExtractApi` — invokes the bundled dump tool. Inputs: `project.assets.json`, DLL paths.
  Output: `reverse-ir.json`. Cacheable (deterministic given the same DLLs).
- `nugetImport` — umbrella task; `dependsOn` `nugetRestore` and all downstream tasks (analogous to
  `podImport`). This is the entry point triggered by IDE Gradle sync.

### Dotnet tool distribution

The dump tool is versioned and released alongside the Gradle plugin. The plugin unpacks it to the
Gradle build directory on first use and pins it via a local `.config/dotnet-tools.json` so that
`dotnet tool restore` installs a deterministic version. The tool version is checked at startup; a
mismatch triggers an automatic reinstall.

### Developer machine requirement

Kotlin developers consuming NuGet packages via Phase 8 must have the .NET SDK 8+ installed. This
is documented in the Phase 8 setup guide (analogous to the CocoaPods plugin requiring CocoaPods
and Xcode). CI pipelines that use Phase 8 must include the .NET SDK in their environment.

### What is not required from the dotnet SDK

The Kotlin library **author** (forward direction, Phases 1–7) does not need the .NET SDK on their
machine. Phase 8 (consuming NuGet packages) is an opt-in, and the .NET SDK prerequisite applies
only to that direction.

### Scope

**In v1:**
- Resolution via `dotnet restore` against the public NuGet.org feed; support for custom feed URLs
  via `nuget { source = "..." }` DSL option passed to the generated `.csproj`.
- Metadata reading via the bundled dump tool for all public types in the directly declared packages.
  Transitive packages are resolved by NuGet but not bound by default (opt-in binding selection per
  the synthesis D4 DSL decision).
- Tooling detection with explicit error messages and `--force-evaluate` self-healing retry.
- The `nugetImport` umbrella task wired into IDE sync.

**Deferred:**
- Pure-JVM NuGet v3 client + ECMA-335 reader, dropping the .NET SDK prerequisite entirely (tracked
  under ROADMAP Future Improvements as "Pure-JVM ECMA-335 metadata reader + NuGet v3 client"). This
  is a genuine ergonomic edge — no plugin in this space runs prerequisite-free — and is recommended
  as the long-term direction once the v1 pipeline is stable.
- Authenticated private feed support beyond public NuGet.org (credential providers, Azure Artifacts,
  GitHub Packages).
- Local `.nupkg` or `ProjectReference` source for the dev-loop flow (synthesis D6; tracked under
  ROADMAP Pre-Launch Checklist).
- Per-package signed-package verification integration.
