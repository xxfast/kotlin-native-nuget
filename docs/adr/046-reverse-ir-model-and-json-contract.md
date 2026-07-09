# ADR-046: Reverse IR model and `reverse-ir.json` contract — RIR data classes for the C# API surface and the JSON schema between the metadata reader and `nugetExtractApi`

## Status

Accepted

## Context

ROADMAP Phase 8 defines a pipeline: `nugetRestore` → `nugetExtractApi` → `nugetGenerateBindings`.
The `nugetExtractApi` task (ADR-042) invokes a bundled C# metadata reader that reads `.dll` assemblies via
`System.Reflection.Metadata.MetadataReader` and produces a `reverse-ir.json` file. The
`nugetGenerateBindings` task (next ROADMAP line) consumes that JSON to produce Kotlin stubs and C#
registration shims.

This ADR decides:
1. The shape of the Kotlin data classes (the "RIR" — Reverse Intermediate Representation) into which
   `nugetExtractApi` deserializes `reverse-ir.json`.
2. The JSON schema the C# metadata reader emits and the Gradle plugin parses.
3. Where the RIR data classes live in the module structure.
4. The `NugetExtractApiTask` input/output signature and its wiring into `NugetPlugin`.

The prior-art synthesis document noted "Reverse IR: likely no ADR — mirrors CIR." Three factors
override that judgment:

**Process boundary.** The forward CIR (`nuget-processor/src/main/kotlin/.../cir/CirModel.kt`) is
an in-memory Kotlin model consumed inside the same JVM process by both the KSP processor and the
code generator — no serialization is involved. The RIR crosses a process boundary: a C# executable
subprocess writes JSON to stdout and the Gradle JVM process reads it from a file. The JSON schema is
therefore a genuine design decision with format alternatives, not a mechanical translation of the CIR.

**Module placement is not the same decision.** The CIR must live in `nuget-processor/` because the
KSP processor produces it while processing Kotlin symbols. The RIR has no KSP dependency; its only
producer is a C# tool and its only consumers are Gradle tasks that both live in the `nuget-plugin/` plugin
module. Placing the RIR in `nuget-processor/` would create a spurious coupling.

**Type vocabulary is a structured design choice.** The CIR stores all types as raw Kotlin strings
(`val type: String = "System.String"`) because the KSP processor already understands Kotlin source
types. The RIR must encode a closed v1 type vocabulary (ADR-043: primitives + `string` + `void`)
as a structured sealed hierarchy that both the C# metadata reader emitter and the Kotlin deserializer can
agree on without a shared IDL.

### The CIR as prior art

`CirModel.kt` is a rich AST carrying many code-generation-specific fields:
`val body: String`, `val getter: String`, `val nativeName: String`, rendering helpers
(`CirMarshalHelper`, `CirListHelper`, `CirFuncHelper`, etc.). These are artifacts of the forward
direction — nodes that carry enough information to render C# code without further analysis.

The RIR is structurally simpler because it is extracted from ECMA-335 metadata, not from Kotlin
source. It carries only what the metadata reader discovered in the metadata (namespace, type names,
method/property signatures) and what the subset filter (ADR-043) kept or discarded (diagnostics per
discarded member). No rendering-specific fields appear in the RIR; those belong to
`nugetGenerateBindings`, which transforms RIR → Kotlin stubs and C# shims.

### The v1 type vocabulary (ADR-043)

v1 auto-mappable members have signatures containing only:

- `void` (return type only)
- Primitive types: `bool`, `byte`, `short`, `int`, `long`, `float`, `double`, `char`
- `string` / `System.String`
- Object handles: deferred to post-v1 (ROADMAP: "Map C# objects as opaque handles in Kotlin")

This is a closed set in v1. Future reverse ADRs (for `Task<T>`, collections, object handles) extend
it. The type representation must therefore be structurally extensible without breaking the schema.

### JSON library choice

The `nuget-plugin/` Gradle plugin module currently carries no JSON dependency — its build declares only
`kotlin-gradle-plugin-api`, `kotlin-gradle-plugin`, KSP Gradle plugin, and `kotlin-test`. The
`nugetExtractApi` task action needs JSON parsing for two inputs:

1. `project.assets.json` — a NuGet-generated, partially-known nested JSON file; parsed with
   `ignoreUnknownKeys = true` to extract only the DLL-path derivation fields (ADR-045 pseudocode).
2. `reverse-ir.json` — schema defined by this ADR; parsed into the RIR data classes.

Options: Gson (on Gradle's classpath but version-fragile across Gradle upgrades), Jackson (same
risk), `kotlinx-serialization-json` (explicit, stable, idiomatic Kotlin), manual parser for the
narrow fields needed.

`kotlinx-serialization-json` is recommended: it is the standard Kotlin JSON library, works with
`@Serializable` data classes to give compile-time verification that the schema and the model are in
sync, supports `ignoreUnknownKeys = true` natively, and is safe to ship as an explicit Gradle
plugin dependency rather than relying on Gradle's internal classpath.

## Alternatives Considered

### 1. RIR in `nuget-plugin/` plugin module + `kotlinx.serialization` for JSON (chosen)

The RIR data classes live in the `nuget-plugin/` Gradle plugin module under the package
`io.github.xxfast.kotlin.native.nuget.rir`. The module adds `kotlinx-serialization-json` as an
`implementation` dependency and the Kotlin serialization compiler plugin. A `"kind"` discriminator
field drives the sealed-interface deserialization for both `RirType` and `RirTypeRef`.

**Pros:**
- Co-location with the RIR's sole consumers (`NugetExtractApiTask`, and later
  `NugetGenerateBindingsTask`), both in `nuget-plugin/`.
- No new module; no new inter-module dependency edge.
- `@Serializable` data classes catch schema drift at compile time rather than at runtime.
- The `"kind"` discriminator is forwards-compatible: adding `{ "kind": "handle", "assemblyQualifiedName": "..." }` for object handles in a future reverse ADR does not break existing parsers that use `@JsonClassDiscriminator`.
- `fun parseReverseIr(json: String): RirFile` is a pure function unit-testable with a fixture string,
  following the same pattern as `generateCsproj(...)` in `NugetGenTask.kt`.

**Cons:**
- Adds `kotlinx-serialization-json` (≈ 1.5 MB transitive) to the Gradle plugin's classpath.
- Requires `kotlin("plugin.serialization")` in `nuget-plugin/build.gradle.kts`.

### 2. RIR in a new shared module (`nuget-rir/` or `nuget-model/`) (rejected)

Extract RIR data classes into a new Gradle module depended on by both `nuget-plugin/` and a future
`nuget-codegen/`.

**Rejected.** There is no current consumer outside `nuget-plugin/`. Creating a new module for a model used
exclusively within one other module adds Gradle inter-module build overhead for no immediate benefit.
If `nugetGenerateBindings` is extracted to a separate module later, the RIR can move then.

### 3. RIR in `nuget-processor/` alongside CIR (rejected)

Put the RIR data classes in `nuget-processor/` so both CIR and RIR share a model module.

**Rejected.** `nuget-processor/` is a KSP processor that runs during Kotlin compilation. The RIR
has no KSP dependency. Adding it to `nuget-processor/` would: (a) make `nuget-plugin/` depend on
`nuget-processor/` for deserialization types (the wrong dependency direction — the processor is a
Gradle plugin dependency, not a compile dependency of the plugin module), and (b) conflate two
unrelated abstraction levels. The CIR lives in `nuget-processor/` because it is produced by the KSP
processor from Kotlin symbols; the RIR is produced by a C# tool from assembly metadata and consumed
by a Gradle task — a different domain, a different home.

### 4. Raw `String` type values in JSON (no discriminated union for `RirTypeRef`) (rejected)

Encode types as plain strings: `"int"`, `"string"`, `"void"`. The Kotlin side maps string → sealed
subtype at parse time.

**Rejected.** A plain string encoding leaks C# keyword spellings (`"int"`, `"bool"`, `"string"`)
into the contract as opaque literals without a versioned discriminator. When object handles are added
(e.g., a member returning a `JObject` instance), the parser cannot distinguish a primitive keyword
from an assembly-qualified type name without a tag. The discriminated union keeps the contract
unambiguous and extensible from the first version.

### 5. Separate JSON files per package (rejected)

Instead of one `reverse-ir.json` per `nugetExtractApi` invocation, emit one file per bound package
(e.g., `newtonsoft.json-reverse-ir.json`).

**Rejected.** The metadata reader is invoked once with the full list of DLL paths for all bound packages.
Splitting output across files requires `nugetExtractApi` to declare a dynamic output file set (not
supported cleanly by Gradle's `@OutputFile` / `@OutputDirectory` without a `@OutputFiles` collection
that varies with the input), complicates deserialization (multiple reads), and adds no benefit:
`nugetGenerateBindings` processes all bound packages in a single pass anyway.

## Decision

Use **Alternative 1**: RIR data classes in the `nuget-plugin/` plugin module, `kotlinx.serialization` for
JSON parsing, `"kind"` discriminator for the `RirType` and `RirTypeRef` sealed hierarchies.

### Module and package

All RIR types live in:
```
nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/rir/
```

Additions to `nuget-plugin/build.gradle.kts`:
```kotlin
plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"   // new
}

dependencies {
    // existing …
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")   // new
}
```

### RIR data classes (`RirModel.kt`)

```kotlin
package io.github.xxfast.kotlin.native.nuget.rir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RirFile(
    val assemblies: List<RirAssembly>,
)

@Serializable
data class RirAssembly(
    val packageId: String,
    val assemblyName: String,
    val namespaces: List<RirNamespace>,
    val diagnostics: List<RirDiagnostic> = emptyList(),
)

@Serializable
data class RirNamespace(
    val name: String,
    val types: List<RirType>,
)

@Serializable
sealed interface RirType {
    val name: String
}

@Serializable
@SerialName("class")
data class RirClass(
    override val name: String,
    val isAbstract: Boolean = false,
    val methods: List<RirMethod> = emptyList(),
    val properties: List<RirProperty> = emptyList(),
) : RirType

@Serializable
@SerialName("interface")
data class RirInterface(
    override val name: String,
    val methods: List<RirMethod> = emptyList(),
    val properties: List<RirProperty> = emptyList(),
) : RirType

@Serializable
data class RirMethod(
    val name: String,
    val returnType: RirTypeRef,
    val parameters: List<RirParameter> = emptyList(),
    val isStatic: Boolean = false,
)

@Serializable
data class RirProperty(
    val name: String,
    val type: RirTypeRef,
    val isReadOnly: Boolean = true,
    val isStatic: Boolean = false,
)

@Serializable
data class RirParameter(
    val name: String,
    val type: RirTypeRef,
)

@Serializable
sealed interface RirTypeRef

@Serializable
@SerialName("void")
data object RirVoidType : RirTypeRef

@Serializable
@SerialName("string")
data object RirStringType : RirTypeRef

// name is one of: "bool", "byte", "short", "int", "long", "float", "double", "char"
@Serializable
@SerialName("primitive")
data class RirPrimitiveType(val name: String) : RirTypeRef

// Future (post-v1, unblocked by "Map C# objects as opaque handles in Kotlin"):
// @Serializable
// @SerialName("handle")
// data class RirObjectHandleType(val assemblyQualifiedName: String) : RirTypeRef

@Serializable
data class RirDiagnostic(
    val kind: RirDiagnosticKind,
    val typeName: String,
    val memberName: String,
    val memberSignature: String,
    val reason: String,
    val hint: String,
)

@Serializable
enum class RirDiagnosticKind {
    @SerialName("skipped_overload_set")          SKIPPED_OVERLOAD_SET,
    @SerialName("skipped_ref_struct")            SKIPPED_REF_STRUCT,
    @SerialName("skipped_open_generic")          SKIPPED_OPEN_GENERIC,
    @SerialName("skipped_dynamic")               SKIPPED_DYNAMIC,
    @SerialName("skipped_default_interface_method") SKIPPED_DEFAULT_INTERFACE_METHOD,
    @SerialName("info_async_not_yet_mapped")     INFO_ASYNC_NOT_YET_MAPPED,
}
```

### Deserialization entry point (`RirParsing.kt`)

```kotlin
package io.github.xxfast.kotlin.native.nuget.rir

import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun parseReverseIr(jsonString: String): RirFile = json.decodeFromString(jsonString)
```

### DLL path derivation from `project.assets.json` (`RirParsing.kt`, continued)

A package-private pure function, unit-testable with a fixture assets JSON string. Implements the
ADR-045 pseudocode exactly:

```kotlin
fun deriveDllPaths(
    assetsJson: String,
    packageIds: Set<String>,
): Map<String, List<String>> // packageId → list of absolute DLL paths
```

The implementation parses the assets JSON using a minimal `@Serializable` model covering only the
three JSON paths specified in ADR-045 (`targets["net8.0"]`, `libraries[key]["path"]`,
`project.restore.packagesPath`), with `ignoreUnknownKeys = true` so the thousands of unrelated
fields in a real `project.assets.json` are silently ignored.

### `reverse-ir.json` schema

The metadata reader emits a single JSON object conforming to the `RirFile` class above. Concrete example
for `Newtonsoft.Json 13.0.3`:

```json
{
  "assemblies": [
    {
      "packageId": "Newtonsoft.Json",
      "assemblyName": "Newtonsoft.Json",
      "namespaces": [
        {
          "name": "Newtonsoft.Json",
          "types": [
            {
              "kind": "class",
              "name": "JsonConvert",
              "isAbstract": false,
              "methods": [
                {
                  "name": "SerializeObject",
                  "isStatic": true,
                  "returnType": { "kind": "string" },
                  "parameters": [
                    { "name": "value", "type": { "kind": "primitive", "name": "int" } }
                  ]
                }
              ],
              "properties": []
            },
            {
              "kind": "interface",
              "name": "IJsonConvertible",
              "methods": [],
              "properties": [
                {
                  "name": "IsValid",
                  "type": { "kind": "primitive", "name": "bool" },
                  "isReadOnly": true,
                  "isStatic": false
                }
              ]
            }
          ]
        }
      ],
      "diagnostics": [
        {
          "kind": "skipped_overload_set",
          "typeName": "JsonConvert",
          "memberName": "SerializeObject",
          "memberSignature": "SerializeObject(object) [+3 overloads]",
          "reason": "overload set — 4 overloads of `SerializeObject` cannot be uniquely exported to C",
          "hint": "Add a C# adapter shim to expose each overload under a distinct name."
        }
      ]
    }
  ]
}
```

**Type-reference discriminator table:**

| JSON `"kind"` | Kotlin type | C# type set |
|---|---|---|
| `"void"` | `RirVoidType` | `void` (return positions only) |
| `"string"` | `RirStringType` | `string` / `System.String` |
| `"primitive"` + `"name": "..."` | `RirPrimitiveType` | `bool`, `byte`, `short`, `int`, `long`, `float`, `double`, `char` |
| `"handle"` (post-v1) | `RirObjectHandleType` | any reference type passed as a `GCHandle`-backed opaque pointer |

**Diagnostic kind values:**

| JSON `"kind"` | Kotlin enum | ADR-043 exclusion rule |
|---|---|---|
| `"skipped_overload_set"` | `SKIPPED_OVERLOAD_SET` | Multiple methods with same name |
| `"skipped_ref_struct"` | `SKIPPED_REF_STRUCT` | `IsByRefLike` parameter or return type |
| `"skipped_open_generic"` | `SKIPPED_OPEN_GENERIC` | Uninstantiated generic type parameter in signature |
| `"skipped_dynamic"` | `SKIPPED_DYNAMIC` | `DynamicAttribute` in signature |
| `"skipped_default_interface_method"` | `SKIPPED_DEFAULT_INTERFACE_METHOD` | Non-abstract method with IL body on interface |
| `"info_async_not_yet_mapped"` | `INFO_ASYNC_NOT_YET_MAPPED` | `Task<T>` / `ValueTask<T>` / `IAsyncEnumerable<T>` (deferred, not skipped) |

### `NugetExtractApiTask` signature

```kotlin
abstract class NugetExtractApiTask : DefaultTask() {
    @get:Input abstract val boundPackageIds: ListProperty<String>
    @get:Input abstract val packageNameOverrides: MapProperty<String, String>
    @get:Input abstract val namespaceIncludes: MapProperty<String, List<String>>
    @get:Input abstract val namespaceExcludes: MapProperty<String, List<String>>
    @get:Input abstract val namespaceAliases: MapProperty<String, Map<String, String>>
    @get:InputFile abstract val assetsFile: RegularFileProperty
    @get:OutputFile abstract val reverseIrFile: RegularFileProperty
    @get:Inject abstract val execOps: ExecOperations
}
```

The task carries no `@DisableCachingByDefault` annotation. The metadata reader is deterministic: the same
DLL bytes at the same resolved versions (recorded in `assetsFile`) always produce the same JSON.
Gradle's up-to-date check (`@InputFile` on `assetsFile`, `@Input` on the bind configs,
`@OutputFile` on `reverseIrFile`) is therefore sufficient for incremental build correctness. Build-
cache sharing across machines is deferred (the `assetsFile` embeds absolute paths to the global
NuGet cache, making cross-machine cache hits impractical without further normalisation).

The task's `@TaskAction` (implemented in the implementation sprint, not this ADR):
1. Parse `assetsFile` via `deriveDllPaths(assetsJson, boundPackageIds.get().toSet())` to get a
   `Map<packageId, List<absoluteDllPath>>`.
2. Validate that each derived DLL path exists on disk; if any is missing, throw a `GradleException`
   with guidance to re-run `nugetRestore` (the global cache may have been cleared, per ADR-045
   "global cache problem" note).
3. Invoke the metadata reader as a subprocess via `execOps.exec { }`, passing DLL paths and namespace
   filter arguments, capturing stdout.
4. Write the captured stdout to `reverseIrFile`.

For v1, the metadata reader is invoked via `dotnet run --project <toolDir>` where `<toolDir>` is unpacked
from a JAR resource at task-execution time, avoiding the need to pre-build and separately distribute
the tool binary. Bundling a pre-built tool binary into the plugin JAR is tracked in ROADMAP
Pre-Launch Checklist.

### Metadata reader project layout

A new `nuget-metadata-reader/` C# project at the repository root:

```
nuget-metadata-reader/
  NugetMetadataReader.csproj   (<OutputType>Exe</OutputType>, TargetFramework net8.0)
  Program.cs                   (MetadataReader extraction + subset filter + JSON emission)
```

The tool receives bound package info via CLI arguments:
```
dotnet NugetMetadataReader.dll \
    --package "Newtonsoft.Json" <path/to/Newtonsoft.Json.dll> \
    --package "Acme.Lib" <path/to/Acme.Lib.dll> \
    --include "Newtonsoft.Json" "Acme.Lib.Core" \
    --exclude "Newtonsoft.Json.Bson"
```

Stdout is the `reverse-ir.json` content. Stderr is reserved for tool-level diagnostics (not
per-member subset filter messages — those appear as `diagnostics` objects in the JSON output). Exit
code 0 on success; non-zero on tool error (bad arguments, DLL read failure). The Gradle task throws
`GradleException` on non-zero exit, forwarding stderr.

Internally, `Program.cs` uses `PEReader` + `MetadataReader` as sketched in ADR-042:
- Iterate `TypeDefinitions`, filter `TypeAttributes.Public`.
- For each public class or interface: enumerate methods via `GetMethods()`, filter
  `MethodAttributes.Public`; enumerate properties via `GetProperties()`.
- Apply the ADR-043 subset filter: group methods by name (skip overload sets); check for
  `IsByRefLike`, open generics, `DynamicAttribute`, DIM (non-abstract method with IL body on
  interface type).
- Emit passing members to the `types` array; emit failing members to the `diagnostics` array.
- Apply namespace include/exclude filters from CLI arguments before emitting.
- Serialize to JSON using `System.Text.Json` (ships with .NET SDK; no extra NuGet reference).

### Wiring into `NugetPlugin`

In the `project.afterEvaluate` block where `nugetGen` and `nugetRestore` are already registered,
add after the `nugetRestore` registration:

```kotlin
val bound: List<NugetDependency> = deps.filter { it.bind != null }

if (bound.isNotEmpty()) {
    val nugetExtractApi: TaskProvider<NugetExtractApiTask> =
        project.tasks.register("nugetExtractApi", NugetExtractApiTask::class.java) { task ->
            task.group = "nuget"
            task.description =
                "Extracts the public API surface of bound NuGet packages into reverse-ir.json"
            task.assetsFile.set(nugetRestore.flatMap { it.assetsFile })
            task.boundPackageIds.set(bound.map { it.id })
            task.packageNameOverrides.set(
                bound.filter { it.bind!!.packageName != null }
                    .associate { it.id to it.bind!!.packageName!! }
            )
            task.namespaceIncludes.set(bound.associate { it.id to it.bind!!.include })
            task.namespaceExcludes.set(bound.associate { it.id to it.bind!!.exclude })
            task.namespaceAliases.set(bound.associate { it.id to it.bind!!.aliases })
            task.reverseIrFile.set(interopDir.map { it.file("reverse-ir.json") })
        }

    project.tasks.named("nugetImport") { task ->
        task.dependsOn(nugetExtractApi)
    }
}
```

`nugetExtractApi` is registered only when at least one dependency carries a `bind {}` block. When
no packages are bound, `nugetImport` depends only on `nugetRestore` (existing v1 behavior).

### Updated task graph

```
nugetGen                    generate interop.csproj
    │
    ▼
nugetRestore                dotnet restore → obj/project.assets.json
    │                       @DisableCachingByDefault (external; global NuGet cache)
    │
    ▼
nugetExtractApi             invoke metadata reader → reverse-ir.json       ← this ADR
    │                       registered only when bound packages exist
    │                       inputs: assetsFile (@InputFile), bind configs (@Input)
    │                       output: reverse-ir.json (@OutputFile)
    │
    ▼
nugetGenerateBindings       [next ROADMAP line — reads reverse-ir.json → Kotlin stubs + C# shims]
    ▼
nugetImport                 umbrella: dependsOn(nugetRestore); +dependsOn(nugetExtractApi) when bound
```

### Generated file layout

```
{project.layout.buildDirectory}/
  nuget-interop/
    interop.csproj              ← nugetGen output
    obj/
      project.assets.json       ← nugetRestore output
    reverse-ir.json             ← nugetExtractApi output   ← this ADR
```

## Consequences

### New `nuget-plugin/` module additions

- `kotlin("plugin.serialization")` applied in `nuget-plugin/build.gradle.kts`.
- `kotlinx-serialization-json` as an `implementation` dependency.
- `nuget-plugin/src/main/kotlin/.../rir/RirModel.kt` — sealed-interface RIR data classes.
- `nuget-plugin/src/main/kotlin/.../rir/RirParsing.kt` — `parseReverseIr()` and `deriveDllPaths()` pure
  functions.
- `nuget-plugin/src/main/kotlin/.../NugetExtractApiTask.kt` — task class with annotated inputs/outputs and
  a working `@TaskAction` that derives DLL paths, invokes the metadata reader, and writes
  `reverse-ir.json`.

### New repository artifact

- `nuget-metadata-reader/` — a C# console application project at the repository root. Not a Gradle module.
  Built and tested separately (dotnet build / dotnet test). Included in the plugin JAR resources as
  part of the release packaging step (tracked in ROADMAP Pre-Launch Checklist).

### Forward CIR is not affected

The RIR lives entirely in `nuget-plugin/` and has no dependency on `nuget-processor/`. The CIR is
unchanged. There is no shared "model" module — each IR serves its own pipeline direction.

### Testing

**Unit tests in `nuget-plugin/src/test/kotlin/` — no dotnet required:**

```kotlin
class RirParsingTest {
    @Test
    fun `reverse-ir json with a single class deserializes to one RirClass in one namespace`()

    @Test
    fun `reverse-ir json with an interface type deserializes to RirInterface`()

    @Test
    fun `type ref kind void deserializes to RirVoidType`()

    @Test
    fun `type ref kind string deserializes to RirStringType`()

    @Test
    fun `type ref kind primitive with name int deserializes to RirPrimitiveType with name int`()

    @Test
    fun `diagnostic with kind skipped_overload_set deserializes correctly`()

    @Test
    fun `parseReverseIr ignores unknown top-level fields`()

    @Test
    fun `method with isStatic true deserializes with isStatic true`()

    @Test
    fun `property with isReadOnly false deserializes with isReadOnly false`()
}

class DllPathDerivationTest {
    // Uses a fixture project.assets.json string (no real dotnet restore needed)

    @Test
    fun `deriveDllPaths returns absolute path for a single bound package`()

    @Test
    fun `deriveDllPaths skips packages not in the packageIds set`()

    @Test
    fun `deriveDllPaths handles package with multiple runtime dlls`()

    @Test
    fun `deriveDllPaths returns empty map when packageIds is empty`()

    @Test
    fun `deriveDllPaths prefixes packagesPath to libPath and dllRelPath`()
}

class NugetExtractApiTaskWiringTest {
    // ProjectBuilder tests — no dotnet required

    @Test
    fun `nugetExtractApi is registered when at least one dependency has bind block`()

    @Test
    fun `nugetExtractApi is not registered when no dependencies have bind block`()

    @Test
    fun `nugetExtractApi assetsFile path matches nugetRestore assetsFile path`()

    @Test
    fun `nugetExtractApi reverseIrFile is under nuget-interop directory`()

    @Test
    fun `nugetImport depends on nugetExtractApi when bound packages exist`()

    @Test
    fun `nugetImport does not depend on nugetExtractApi when no bound packages`()

    @Test
    fun `nugetExtractApi boundPackageIds contains only packages with bind block`()

    @Test
    fun `nugetExtractApi boundPackageIds excludes resolve-only dependencies`()
}
```

**Integration test (dotnet-gated, mirrors `NugetRestoreIntegrationTest`):**

```kotlin
class NugetExtractApiIntegrationTest {
    private fun findDotnet(): String? = ...  // same pattern as NugetRestoreIntegrationTest

    @Test
    fun `metadata reader emits reverse-ir json for Newtonsoft Json dll`() {
        val dotnet = findDotnet() ?: return
        // 1. dotnet restore Newtonsoft.Json 13.0.3 to a temp dir
        // 2. find Newtonsoft.Json.dll via deriveDllPaths()
        // 3. invoke metadata reader via dotnet
        // 4. parseReverseIr(stdout)
        // 5. assert: packageId == "Newtonsoft.Json"; at least one namespace "Newtonsoft.Json";
        //            at least one type named "JsonConvert"
    }

    @Test
    fun `metadata reader emits skipped_overload_set diagnostic for overloaded methods`() {
        val dotnet = findDotnet() ?: return
        // Newtonsoft.Json has overloaded methods; verify diagnostic kind in output
    }
}
```

### Slicing recommendation (TDD increments)

**Slice 1 — RIR model + JSON deserialization** (pure Kotlin, no Gradle, no dotnet):
Create `RirModel.kt` and `RirParsing.kt`; all `RirParsingTest` cases. No external dependency.

**Slice 2 — DLL path derivation** (pure Kotlin, fixture JSON):
Add `deriveDllPaths()` to `RirParsing.kt`; all `DllPathDerivationTest` cases. Requires a fixture
`project.assets.json` string in the test (copy a relevant excerpt from a real restore output).

**Slice 3 — `NugetExtractApiTask` class + plugin wiring** (Gradle ProjectBuilder):
Create `NugetExtractApiTask.kt` with `TODO()` action; wire in `NugetPlugin.kt`; all
`NugetExtractApiTaskWiringTest` cases.

**Slice 4 — Metadata reader implementation** (C#, requires dotnet):
Create `nuget-metadata-reader/Program.cs`; implement MetadataReader extraction + subset filter + JSON
emission; integration tests. Parallel to Slices 1–3 if a second contributor is available.

**Slice 5 — Task action** (ties together slices 1–4):
Replace the `TODO()` stub in `NugetExtractApiTask` with the subprocess invocation using
`execOps.exec { }`, `deriveDllPaths()`, and the metadata reader. Verified by the integration tests from
Slice 4.

**The first failing test to write (entry point for Slice 1):**

```kotlin
class RirParsingTest {
    @Test
    fun `reverse-ir json with a single class deserializes to one RirClass in one namespace`() {
        val jsonString = """
            {
              "assemblies": [
                {
                  "packageId": "Newtonsoft.Json",
                  "assemblyName": "Newtonsoft.Json",
                  "namespaces": [
                    {
                      "name": "Newtonsoft.Json",
                      "types": [
                        {
                          "kind": "class",
                          "name": "JsonConvert",
                          "methods": [],
                          "properties": []
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val file: RirFile = parseReverseIr(jsonString)
        assertEquals(1, file.assemblies.size)
        assertEquals("Newtonsoft.Json", file.assemblies[0].packageId)
        assertEquals(1, file.assemblies[0].namespaces.size)
        val type = file.assemblies[0].namespaces[0].types[0]
        assertIs<RirClass>(type)
        assertEquals("JsonConvert", type.name)
    }
}
```

This test is red because `RirFile`, `RirAssembly`, `RirNamespace`, `RirClass`, and `parseReverseIr`
do not yet exist. Making it green requires defining the complete RIR data class hierarchy and the
parsing function — which then makes every subsequent slice's model usage compile.

### Scope

**In v1:**
- `RirFile`, `RirAssembly`, `RirNamespace`, `RirType` (sealed: `RirClass`, `RirInterface`),
  `RirMethod`, `RirProperty`, `RirParameter` data classes.
- `RirTypeRef` (sealed: `RirVoidType`, `RirStringType`, `RirPrimitiveType`) covering the ADR-043
  v1 type vocabulary.
- `RirDiagnostic` and `RirDiagnosticKind` enum covering the five ADR-043 exclusion rules plus
  `INFO_ASYNC_NOT_YET_MAPPED`.
- `parseReverseIr(json: String): RirFile` pure function.
- `deriveDllPaths(assetsJson: String, packageIds: Set<String>): Map<String, List<String>>`
  pure function.
- `NugetExtractApiTask` with annotated inputs/outputs and a working `@TaskAction` that derives DLL
  paths, invokes the metadata reader, and writes `reverse-ir.json`.
- Wiring in `NugetPlugin.afterEvaluate`; `nugetImport` updated to `dependsOn(nugetExtractApi)` when
  bound packages exist.
- `nuget-metadata-reader/` C# console project — `MetadataReader` extraction + ADR-043 subset filter
  + JSON emission.
- Unit tests for `parseReverseIr`, `deriveDllPaths`, and task wiring (no dotnet required); a
  dotnet-gated integration test exercising the metadata reader against a real `Newtonsoft.Json.dll`.

**Deferred:**
- `RirObjectHandleType` (`"kind": "handle"`) for C# reference types as GCHandle-backed opaque Kotlin
  handles — unblocked by the post-v1 ROADMAP item "Map C# objects as opaque handles in Kotlin".
- Bundling the pre-built metadata reader binary inside the Gradle plugin JAR — ROADMAP Pre-Launch
  Checklist. v1 invokes it via `dotnet run --project` against the unpacked project sources.
- Build-cache sharing across machines for `nugetExtractApi` (blocked on normalising the absolute
  packages-path embedded in `assetsFile`).
- The `nugetGenerateBindings` task consuming the RIR — next ROADMAP line.
