# ADR-048: Reverse IR → Kotlin stub generation — `nugetGenerateBindings` task, `object`-per-type stub shape, source set wiring, and C# registration contract

## Status

Accepted

## Context

Phase 8's pipeline ends with `nugetGenerateBindings`, the Gradle task that reads
`reverse-ir.json` (the output of `nugetExtractApi`, ADR-046) and produces:

1. **Kotlin-idiomatic stub source files** that Kotlin code in `sample-library` (or any consumer
   library) calls directly.
2. **A registration contract** — the exact Kotlin-side `@CName` exports that the C# shim step
   (next ROADMAP item, line 138) must satisfy to wire up the function-pointer table at startup.

This ADR answers the following design questions that are not already settled by ADR-041 (which
prescribes the call mechanism and registration protocol at a conceptual level but leaves the
generation mechanics, source set wiring, and precise contract to this feature):

- What Kotlin construct represents a C# static class full of static methods?
- How are the two generated files per type (`{TypeName}Bindings.kt` vs `{TypeName}.kt`) organised?
- Where do generated Kotlin source files live and how does the plugin wire them into compilation?
- How is the C# namespace → Kotlin package name derived (and where does the ADR-044/047 alias
  machinery apply)?
- What is the v1 type-mapping table (RirTypeRef → Kotlin type, CFunction type parameter)?
- How are strings memory-managed across the boundary?
- What is the exact contract (export name pattern, parameter count, parameter order) that the
  C# shim step must satisfy?

### Prior decisions that constrain this ADR

**ADR-041**: `[UnmanagedCallersOnly]` thunks registered at startup via `[ModuleInitializer]`;
Kotlin side stores function pointers as `CPointer<CFunction<...>>?`; `requireNotNull` fail-fast
guard; per-type registration export named `nuget_{namespace_snake}_{type_snake}_register`.
This ADR confirms and formalises those decisions from the generation perspective.

**ADR-043**: v1 scope = static methods only; only `RirVoidType`, `RirStringType`, and
`RirPrimitiveType` parameter/return types are auto-mappable. Instance methods, object handles,
generics, `async` shapes, overload sets are deferred or excluded.

**ADR-044**: Namespace → Kotlin package: `bind { packageName; include; exclude; alias }` drives
the mapping. Default: lower-case NuGet package ID (dots preserved, hyphens → underscores).
`alias(csharpNamespace, kotlinPackage)` overrides per-namespace.

**ADR-046**: The RIR model: `RirFile` → `RirAssembly` → `RirNamespace` → `RirClass/RirInterface`
→ `RirMethod` (with `isStatic: Boolean`) / `RirProperty`. The `reverse-ir.json` is a deterministic
serialisation parsed by `parseReverseIr()`.

**ADR-047**: Per-package namespace filters at the CLI; by the time the RIR reaches
`nugetGenerateBindings`, the namespace inclusion/exclusion filter has already been applied. The
task only needs `packageNameOverrides` and `namespaceAliases` to convert namespaces to Kotlin
package names.

### Ecosystem analogues

**Kotlin CocoaPods plugin (pod → Kotlin)**: `PodBuildTask` compiles the pod, `DefFileTask`
generates a `.def` file pointing at the ObjC module, then `cinterop` produces a `.klib` with
`external` Kotlin declarations. Static ObjC class methods (e.g., `[NSString stringWithFormat:]`)
surface as `NSString.stringWithFormat(...)` — the ObjC class name is preserved as the Kotlin
container. This is the closest analogue: a named C# class with all-static methods maps to a
named Kotlin container, not to a flat package-level function.

**Kotlin consuming Java**: Java utility classes (e.g., `java.util.Collections`) with only static
methods are accessed in Kotlin as `Collections.sort(...)` — the class name is the container, and
`static` becomes a direct call on the class object. There is no `object` wrapper in the generated
bindings; Kotlin transparently uses the Java class. Our generated `object` mimics this call-site
experience.

**Kotlin cinterop (C headers → Kotlin)**: C functions that belong to no struct (global functions)
map to top-level `external fun` in a package. If cinterop generates from an ObjC class with class
methods, the class name is preserved. For a C# type with only static methods, `object TypeName`
gives the same experience as ObjC class methods.

**Xamarin Android binding generator** (the exact mirror of this project): Java static methods on
`class JsonConverter` map to C# static methods on `class JsonConverter`. The type name is
preserved as the container. Our reverse direction applies the same principle: C# `class
JsonConvert` with static methods → Kotlin `object JsonConvert`.

## Alternatives Considered

### 1. `object` per C# static class (chosen)

A C# type whose bridgeable methods are all `isStatic = true` generates a Kotlin `object` with the
same name as the C# class. The call site preserves the C# type name as the Kotlin container:

```kotlin
// Generated for Newtonsoft.Json.JsonConvert
object JsonConvert {
    fun serializeObject(value: Int): String { ... }
}

// Consumer call site
val json = JsonConvert.serializeObject(42)
```

**Pros:**
- The C# type name is preserved. A Kotlin developer reading the documentation of `Newtonsoft.Json`
  and then looking at IntelliSense sees the same name (`JsonConvert`), reducing context-switching.
- `object` is Kotlin's idiomatic representation of a stateless singleton with associated functions
  — exactly the semantics of a C# static utility class.
- Consistent with how Kotlin-consuming-Java surfaces static utility classes (the Java class name
  is the container for static calls).
- Consistent with the `object MathHelper { ... }` example in ADR-041.
- If a future phase adds instance methods for `JsonConvert`, the `object` can be augmented with
  a companion, or a class stub can coexist, without breaking the static call sites.

**Cons:**
- `object` introduces a singleton instance that is never used by the runtime (all calls go through
  the function-pointer table). This is cosmetic: the object is zero-allocation and zero-overhead in
  native compilation.

### 2. Top-level package functions (rejected)

Each static method becomes a top-level function in the derived Kotlin package:

```kotlin
// package newtonsoft.json
fun serializeObject(value: Int): String { ... }
```

**Rejected.** Top-level functions lose the C# type name as a disambiguation scope. If two
different NuGet types have a method named `Parse`, the Kotlin consumer sees two `parse()`
functions in the same or different packages with no type-level disambiguation. The `object`
container makes the origin explicit and matches IntelliSense expectations set by the package's
own documentation.

### 3. Class with static methods via companion object (rejected)

A regular `class JsonConvert` with a `companion object` holding the static methods.

**Rejected.** A regular class implies instantiation, which is meaningless for a static utility
class. A companion object inside a class complicates the call site (`JsonConvert.Companion.x`
from Java; `JsonConvert.x` from Kotlin only if the companion members are not in a named companion).
The `object` is simpler and more direct.

---

### 4. Two-file split per bound C# type (chosen): `{TypeName}Bindings.kt` + `{TypeName}.kt`

Per the ADR-041 prescribed structure, each bound `RirClass` generates two files in the same
Kotlin package directory:

**`{TypeName}Bindings.kt`** — private registration machinery (not visible to consumers):
- Package-level `private var {methodCamelCase}Fn: CPointer<CFunction<...>>?` variables, one per
  bridgeable method.
- The `@CName("nuget_{namespace_snake}_{type_snake}_register")` exported function that receives
  `COpaquePointer` arguments from `[ModuleInitializer]` and stores them with `.reinterpret()`.

**`{TypeName}.kt`** — public Kotlin API:
- `object {TypeName}` with one `fun` per bridgeable static method.
- Each stub reads the corresponding function pointer variable from the Bindings file, calls
  `requireNotNull` with a diagnostic message, marshals parameters, invokes through the pointer,
  and unmarshals the return value.

**Pros:**
- Consumers reading `JsonConvert.kt` see only the clean public API; the function pointer
  plumbing is in a separate file they never need to open.
- The Bindings file can have `@Suppress("INVISIBLE_MEMBER")` or equivalent to avoid IDE warnings
  about the exported function symbol.
- Mirrors the forward direction's separation between the KSP-generated `@CName` export files and
  the user's source files.

**Cons:**
- Two files per type vs one (small overhead). Outweighed by clarity.

### 5. One file per type (rejected)

Put both the registration variables and the `object` stubs in a single `{TypeName}.kt`.

**Rejected.** Registration plumbing (raw function pointer variables, the `@CName` export) mixed
into the same file as the public API obscures the consumer-facing API surface. The two-file split
is clean and established by ADR-041.

---

### 6. Source directory `build/nuget-interop/kotlin/` + `nativeMain` wiring (chosen)

The `NugetGenerateBindingsTask` outputs to `{buildDir}/nuget-interop/kotlin/` which extends the
existing `nuget-interop/` output directory introduced by ADR-046. The plugin wires subdirectories
into the appropriate Kotlin source sets at plugin apply time.

Generated source directory layout:

```
{buildDir}/nuget-interop/kotlin/
  nativeMain/                         ← wired into kotlin.sourceSets["nativeMain"].kotlin
    {kotlinPackage path}/
      {TypeName}Bindings.kt           ← registration machinery (package-private)
      {TypeName}.kt                   ← public object stub
    io/github/xxfast/kotlin/native/nuget/internal/
      NugetInterop.kt                 ← expect fun freeManagedString(...)
  mingwMain/                          ← wired into mingwX64Main (and any other mingw targets)
    io/github/xxfast/kotlin/native/nuget/internal/
      NugetInterop.kt                 ← actual fun freeManagedString: CoTaskMemFree
  posixMain/                          ← wired into macosArm64Main, macosX64Main, linuxX64Main …
    io/github/xxfast/kotlin/native/nuget/internal/
      NugetInterop.kt                 ← actual fun freeManagedString: platform.posix.free
```

The synthesis document (nuget-plugin-architecture-synthesis.md) is explicit: "model the managed
API once, not per Kotlin target; only native payloads vary by the Kotlin-target ↔ RID mapping."
The `nativeMain` type stubs satisfy "model once". The `mingwMain`/`posixMain` `actual` helpers
are the only per-platform variation.

`NugetPlugin` wiring added after `nugetGenerateBindings` is registered:

```kotlin
// Wire nativeMain stubs (same content for all native targets)
kotlin.sourceSets.named("nativeMain") { sourceSet ->
    sourceSet.kotlin.srcDir(
        nugetGenerateBindings.flatMap { it.kotlinOutputDir.map { dir -> dir.dir("nativeMain") } }
    )
}

// Wire platform-specific actual helpers based on configured targets
for (target in kotlin.targets.filterIsInstance<KotlinNativeTarget>()) {
    val rid: String = KONAN_TO_RID[target.konanTarget.name] ?: continue
    val subdir: String = if (rid.startsWith("win-")) "mingwMain" else "posixMain"
    val sourceSetName = "${target.name}Main"
    kotlin.sourceSets.findByName(sourceSetName)?.kotlin
        ?.srcDir(nugetGenerateBindings.flatMap { task ->
            task.kotlinOutputDir.map { dir -> dir.dir(subdir) }
        })
}
```

Gradle's incremental build machinery tracks `@OutputDirectory` → compilation source set input,
so `compileKotlin{Target}` automatically depends on `nugetGenerateBindings` once the source
directory is wired. No explicit `dependsOn` is needed at the task level.

**Pros:**
- Consistent with the existing `build/nuget-interop/` directory (interop.csproj, reverse-ir.json
  are already there).
- Single `nativeMain` wiring for all generated stubs (model once).
- Per-platform helpers wired only to the targets the project actually uses.
- Task up-to-date checking is automatic via `@OutputDirectory`.

### 7. Per-target source directories (rejected)

Generate a separate copy of the stubs for each Kotlin/Native target (mirrors KSP's
`build/generated/ksp/{target}/{target}Main/kotlin/`).

**Rejected.** KSP generates per-target because the KSP processor itself runs per-target
(processing Kotlin source per compilation unit). `nugetGenerateBindings` processes a
target-independent JSON model. Generating identical files for each target wastes build time and
disk, and contradicts the synthesis document's "model once" principle.

---

### 8. Per-type registration granularity (chosen)

One `@CName` registration export per `RirClass`, following ADR-041. The registration export for
a type registers all of that type's bridgeable methods at once.

**Pros:** Fine-grained; adding a new method to a type requires only re-registering that type's
export, not re-running a single large mega-registration. Each C# `[ModuleInitializer]` file maps
1:1 to one Kotlin registration export — easy to audit and test. Consistent with ADR-041.

### 9. Per-namespace or per-package registration (rejected)

One registration export per C# namespace or per NuGet package (all types together).

**Rejected.** A namespace-level registration would have a parameter count equal to the sum of all
methods across all types in the namespace, making it unwieldy and fragile (parameter list grows
unbounded as new types are added). The per-type granularity is the stable unit.

---

### 10. String memory management: `expect`/`actual` `freeManagedString` helper (chosen)

Strings returned by C# thunks cross the boundary as `COpaquePointer?` (UTF-8 byte pointer). The
C# thunk allocates with `Marshal.StringToCoTaskMemUTF8()`. The Kotlin stub must free the memory
after reading the string with `.reinterpret<ByteVar>().toKString()`.

The allocator used by `Marshal.StringToCoTaskMemUTF8()` is platform-specific:
- **Windows**: `CoTaskMemAlloc` (COM task allocator, a distinct Win32 heap)
- **macOS/Linux**: `malloc` (system allocator)

These allocators have matching free functions: `CoTaskMemFree` on Windows, `free()` on
POSIX. Using the wrong free is undefined behaviour.

**Decision**: Generate a single `expect fun freeManagedString(ptr: COpaquePointer?)` in
`nativeMain` with two `actual` implementations:

```kotlin
// nativeMain/…/NugetInterop.kt
internal expect fun freeManagedString(ptr: COpaquePointer?)

// mingwMain/…/NugetInterop.kt
import platform.windows.CoTaskMemFree
internal actual fun freeManagedString(ptr: COpaquePointer?) {
    ptr?.let { CoTaskMemFree(it) }
}

// posixMain/…/NugetInterop.kt
import platform.posix.free
internal actual fun freeManagedString(ptr: COpaquePointer?) {
    ptr?.let { free(it) }
}
```

Every generated string-returning stub calls `freeManagedString(resultPtr)` unconditionally after
`toKString()`. The helper is `internal` (invisible to consumers) and lives in a generated package
`io.github.xxfast.kotlin.native.nuget.internal`.

**Pros:** Single, uniform call in every stub (`freeManagedString(ptr)`); no platform conditionals
in the stub body; the platform specifics are localised to two small helper files.

### 11. `nativeHeap.free()` or `CoTaskMemFree`-in-stubs (rejected)

Call `nativeHeap.free(ptr)` directly in each stub. `nativeHeap.free()` is backed by
`malloc`/`free` and is not compatible with `CoTaskMemAlloc` on Windows, causing heap corruption
for the `mingwX64` target. Calling `platform.windows.CoTaskMemFree` directly in a `nativeMain`
file fails to compile on POSIX targets because `platform.windows` is not available there.

**Rejected** for both variants. The `expect`/`actual` split is the correct Kotlin/Native
cross-platform mechanism.

## Decision

Use **Alternatives 1, 4, 6, 8, 10**: `object`-per-type stubs, two-file split per type,
`build/nuget-interop/kotlin/` directory wired into `nativeMain` (plus per-platform actuals),
per-type registration granularity, `expect`/`actual` `freeManagedString` helper.

### Task signature

```kotlin
abstract class NugetGenerateBindingsTask : DefaultTask() {
    @get:InputFile  abstract val reverseIrFile: RegularFileProperty
    // Subset of nugetExtractApi inputs (no includes/excludes — already applied by nugetExtractApi)
    @get:Input abstract val packageNameOverrides: MapProperty<String, String>   // packageId → kotlin package
    @get:Input abstract val namespaceAliases: MapProperty<String, Map<String, String>> // packageId → (csharpNs → kotlinPkg)
    @get:OutputDirectory abstract val kotlinOutputDir: DirectoryProperty        // build/nuget-interop/kotlin/
}
```

### Namespace → Kotlin package derivation

For each `RirNamespace` within a `RirAssembly`:

1. Look up `namespaceAliases[assemblyPackageId][namespace.name]` → if present, that is the exact
   Kotlin package (e.g. `"acme.core"`).
2. Otherwise, use `packageNameOverrides[assemblyPackageId]` → if present, that is the Kotlin
   package (e.g. `"json"` for all namespaces in `Newtonsoft.Json` when no alias applies).
3. Otherwise, derive the default: lower-case the NuGet package ID, replace `-` with `_`, keep `.`.
   `Newtonsoft.Json` → `newtonsoft.json`. `My-Package` → `my_package`.

The derived Kotlin package determines the generated file's directory path under `nativeMain/`.

### Registration export naming

For each `RirClass`:

1. Namespace → snake_case: replace `.` with `_`, lowercase all.  
   `Newtonsoft.Json` → `newtonsoft_json`
2. Type name → snake_case: insert `_` before each uppercase letter (after the first), lowercase.  
   `JsonConvert` → `json_convert`, `MathHelper` → `math_helper`
3. Export name: `nuget_{namespace_snake}_{type_snake}_register`  
   `Newtonsoft.Json.JsonConvert` → `nuget_newtonsoft_json_json_convert_register`

If the namespace is empty (global namespace), omit the namespace segment:
`nuget_{type_snake}_register`.

### Parameter order in the registration export

The registration export receives one `COpaquePointer` per bridgeable method, in the exact order
the methods appear in `reverse-ir.json` for that type. Both the Kotlin stub generator and the C#
shim generator read the same `reverse-ir.json`; they must use the same method order to keep the
pointer assignment correct. v1: only `isStatic = true` methods with v1-mappable parameter and
return types are included.

### v1 type-mapping table

| `RirTypeRef` | C# type | Kotlin stub type | `CFunction` type parameter |
|---|---|---|---|
| `RirVoidType` | `void` | `Unit` | `Unit` |
| `RirStringType` | `string` | `String` | `COpaquePointer?` |
| `RirPrimitiveType("bool")` | `bool` | `Boolean` | `Boolean` |
| `RirPrimitiveType("byte")` | `byte` (unsigned) | `UByte` | `UByte` |
| `RirPrimitiveType("short")` | `short` | `Short` | `Short` |
| `RirPrimitiveType("int")` | `int` | `Int` | `Int` |
| `RirPrimitiveType("long")` | `long` | `Long` | `Long` |
| `RirPrimitiveType("float")` | `float` | `Float` | `Float` |
| `RirPrimitiveType("double")` | `double` | `Double` | `Double` |
| `RirPrimitiveType("char")` | `char` (UTF-16) | `Char` | `UShort` |

`char` (C# UTF-16 `System.Char`) is mapped to Kotlin `Char` at the stub level. At the `CFunction`
type parameter level, `Char` is represented as `UShort` because the C ABI passes `char` as an
unsigned 16-bit value. The stub performs the `UShort.toInt().toChar()` conversion. This is a
minor corner case; `char`-typed method parameters and returns are uncommon in C# APIs.

### Contract with the C# shim step (next ROADMAP item, line 138)

The C# shim generator reads the same `reverse-ir.json` and generates, for each `RirClass`:

1. One `[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })] private static`
   method per bridgeable method, with a signature derived from the same v1 type-mapping table
   (inverse direction: Kotlin `Int` ↔ C# `int`, Kotlin `COpaquePointer?` ↔ C# `byte*`).
2. A `[DllImport]` declaration for the Kotlin registration export.
3. A `[ModuleInitializer] internal static unsafe void Initialize()` that calls the registration
   export passing thunk addresses in the same method order as the Kotlin generator.
4. For string-returning methods: C# thunks allocate with `Marshal.StringToCoTaskMemUTF8()`.

The Kotlin side does not need to know anything about the C# side beyond:
- The registration export will be called before any stub is invoked (guaranteed by `[ModuleInitializer]`).
- String pointers passed from C# thunks were allocated with `Marshal.StringToCoTaskMemUTF8()` and
  must be freed with the platform-appropriate free (handled by `freeManagedString`).

The C# side does not need to know anything about the Kotlin side beyond:
- The `@CName` export name (derived by the same rule from the same RIR).
- The number and order of `COpaquePointer` parameters (same RIR method order).

### Full generated artifact example

Input RIR (from `reverse-ir.json`, simplified `Newtonsoft.Json 13.0.3`):

```json
{
  "assemblies": [{
    "packageId": "Newtonsoft.Json",
    "assemblyName": "Newtonsoft.Json",
    "namespaces": [{
      "name": "Newtonsoft.Json",
      "types": [{
        "kind": "class",
        "name": "JsonConvert",
        "methods": [
          {
            "name": "SerializeObject",
            "isStatic": true,
            "returnType": { "kind": "string" },
            "parameters": [{ "name": "value", "type": { "kind": "primitive", "name": "int" } }]
          }
        ],
        "properties": []
      }]
    }]
  }]
}
```

DSL configuration:

```kotlin
nuget {
    dependencies {
        dependency("Newtonsoft.Json") {
            version = "13.0.3"
            bind {
                packageName = "newtonsoft.json"
                include("Newtonsoft.Json")
            }
        }
    }
}
```

**Generated `build/nuget-interop/kotlin/nativeMain/newtonsoft/json/JsonConvertBindings.kt`**:

```kotlin
package newtonsoft.json

import io.github.xxfast.kotlin.native.nuget.internal.freeManagedString
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlin.experimental.ExperimentalNativeApi

// Generated: registration machinery for Newtonsoft.Json.JsonConvert
// Do not call these functions from Kotlin code directly.

@Suppress("NOTHING_TO_INLINE")
internal var serializeObjectFn: CPointer<CFunction<(Int) -> COpaquePointer?>>? = null

@OptIn(ExperimentalNativeApi::class)
@CName("nuget_newtonsoft_json_json_convert_register")
fun nuget_newtonsoft_json_json_convert_register(
    serializeObjectPtr: COpaquePointer,
) {
    serializeObjectFn = serializeObjectPtr.reinterpret()
}
```

**Generated `build/nuget-interop/kotlin/nativeMain/newtonsoft/json/JsonConvert.kt`**:

```kotlin
package newtonsoft.json

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import io.github.xxfast.kotlin.native.nuget.internal.freeManagedString

// Generated: Kotlin-idiomatic stubs for Newtonsoft.Json.JsonConvert

object JsonConvert {

    fun serializeObject(value: Int): String {
        val fn = requireNotNull(serializeObjectFn) {
            "JsonConvert bindings are not registered. " +
            "Ensure the generated C# shims for Newtonsoft.Json are referenced " +
            "in the consuming application before making Kotlin → C# bridge calls."
        }
        val resultPtr = fn.invoke(value)
            ?: error("JsonConvert.SerializeObject returned null — expected a non-null string pointer")
        val result = resultPtr.reinterpret<ByteVar>().toKString()
        freeManagedString(resultPtr)
        return result
    }
}
```

**Generated `build/nuget-interop/kotlin/nativeMain/io/github/xxfast/kotlin/native/nuget/internal/NugetInterop.kt`**
(emitted once, shared across all bound packages in the same `nuget {}` block):

```kotlin
package io.github.xxfast.kotlin.native.nuget.internal

import kotlinx.cinterop.COpaquePointer

internal expect fun freeManagedString(ptr: COpaquePointer?)
```

**Generated `build/nuget-interop/kotlin/mingwMain/io/github/xxfast/kotlin/native/nuget/internal/NugetInterop.kt`**:

```kotlin
package io.github.xxfast.kotlin.native.nuget.internal

import kotlinx.cinterop.COpaquePointer
import platform.windows.CoTaskMemFree

internal actual fun freeManagedString(ptr: COpaquePointer?) {
    ptr?.let { CoTaskMemFree(it) }
}
```

**Generated `build/nuget-interop/kotlin/posixMain/io/github/xxfast/kotlin/native/nuget/internal/NugetInterop.kt`**:

```kotlin
package io.github.xxfast.kotlin.native.nuget.internal

import kotlinx.cinterop.COpaquePointer
import platform.posix.free

internal actual fun freeManagedString(ptr: COpaquePointer?) {
    ptr?.let { free(it) }
}
```

**Consumer call site in `sample-library`:**

```kotlin
import newtonsoft.json.JsonConvert

// Calls C# JsonConvert.SerializeObject(42) via registered function pointer
val json: String = JsonConvert.serializeObject(42)
```

### `NugetPlugin` wiring for `nugetGenerateBindings`

```kotlin
// Inside afterEvaluate, after nugetExtractApi is registered (bound.isNotEmpty() guard):
val nugetGenerateBindings: TaskProvider<NugetGenerateBindingsTask> =
    project.tasks.register("nugetGenerateBindings", NugetGenerateBindingsTask::class.java) { task ->
        task.group = "nuget"
        task.description = "Generates Kotlin stubs and the C# registration contract from reverse-ir.json"
        task.reverseIrFile.set(nugetExtractApi.flatMap { it.reverseIrFile })
        task.packageNameOverrides.set(
            bound.filter { it.bind!!.packageName != null }
                .associate { it.id to it.bind!!.packageName!! }
        )
        task.namespaceAliases.set(bound.associate { it.id to it.bind!!.aliases })
        task.kotlinOutputDir.set(interopDir.map { it.dir("kotlin") })
    }

nugetImport.configure { task -> task.dependsOn(nugetGenerateBindings) }

// Wire generated source directories into Kotlin source sets
kotlin.sourceSets.named("nativeMain") { sourceSet ->
    sourceSet.kotlin.srcDir(
        nugetGenerateBindings.flatMap { task ->
            task.kotlinOutputDir.map { it.dir("nativeMain") }
        }
    )
}

for (target in kotlin.targets.filterIsInstance<KotlinNativeTarget>()) {
    val rid: String = KONAN_TO_RID[target.konanTarget.name] ?: continue
    val actualSubdir: String = if (rid.startsWith("win-")) "mingwMain" else "posixMain"
    val sourceSetName = "${target.name}Main"
    kotlin.sourceSets.findByName(sourceSetName)?.kotlin
        ?.srcDir(nugetGenerateBindings.flatMap { task ->
            task.kotlinOutputDir.map { it.dir(actualSubdir) }
        })
}
```

### Updated task graph

```
nugetGen                    generate interop.csproj
    │
    ▼
nugetRestore                dotnet restore → obj/project.assets.json
    │
    ▼
nugetExtractApi             invoke metadata reader → reverse-ir.json
    │
    ▼
nugetGenerateBindings       reverse-ir.json → Kotlin stubs + registration contract  ← this ADR
    │                       inputs: reverseIrFile, packageNameOverrides, namespaceAliases
    │                       output: build/nuget-interop/kotlin/ (@OutputDirectory)
    │                       wired into: nativeMain, {target}Main (per-platform actuals)
    ▼
compileKotlin{Target}       Kotlin/Native compilation picks up generated sources automatically
    ▼
nugetImport                 umbrella task
```

### Diagnostics emitted by `nugetGenerateBindings`

The task emits an informational Gradle log entry for types it skips:

```
i: [nuget:Newtonsoft.Json] Skipping RirInterface IJsonConvertible: interface stubs deferred (v1 scope: static methods only)
i: [nuget:Newtonsoft.Json] Skipping instance method StringBuilder.Append(string): instance method stubs deferred (post-v1: requires GCHandle opaque handle)
i: [nuget:Newtonsoft.Json] No v1-bridgeable methods found for class JArray: generating no stub (all methods are either instance, overloaded, or use non-v1 types)
```

These are informational only (not warnings); the user already saw per-member diagnostics at the
`nugetExtractApi` stage (ADR-043 warnings).

## Consequences

### New `nuget-plugin/` Gradle plugin additions

- `NugetGenerateBindingsTask.kt` — task class with `@InputFile reverseIrFile`, `@Input`
  overrides/aliases maps, `@OutputDirectory kotlinOutputDir`; task action renders stub files.
- Wiring in `NugetPlugin.afterEvaluate` after `nugetExtractApi` registration:
  `nugetGenerateBindings` task registered + source set wiring for `nativeMain` and per-target.
- `nugetImport.dependsOn(nugetGenerateBindings)` added alongside the existing
  `nugetImport.dependsOn(nugetExtractApi)`.

### New generated files per bound NuGet package (one `nugetGenerateBindings` run)

- `nativeMain/{kotlinPkgPath}/{TypeName}Bindings.kt` — one per `RirClass` with at least one
  bridgeable static method; contains registration variables + `@CName` export.
- `nativeMain/{kotlinPkgPath}/{TypeName}.kt` — one per `RirClass` with at least one bridgeable
  static method; contains `object {TypeName}` with public stubs.
- `nativeMain/io/.../internal/NugetInterop.kt` — emitted once if any bound type has a
  `RirStringType` parameter or return; contains `expect fun freeManagedString`.
- `mingwMain/io/.../internal/NugetInterop.kt` — Windows `actual` (conditional on `win-*` RIDs).
- `posixMain/io/.../internal/NugetInterop.kt` — POSIX `actual` (conditional on non-Windows RIDs).

### Boundary contract defined for the next ROADMAP item (C# shim generation, line 138)

The C# shim generator reads the same `reverse-ir.json` and must satisfy:
- One `[UnmanagedCallersOnly]` thunk per bridgeable static method, in the same method order.
- One `[DllImport("nuget_…_register")]` + `[ModuleInitializer]` per `RirClass`.
- String-returning thunks allocate with `Marshal.StringToCoTaskMemUTF8()`.
- The generated project must include `<AllowUnsafeBlocks>true</AllowUnsafeBlocks>` (per ADR-041).

The C# shim ADR (next step) will detail the thunk signatures, exception handling (error-out
pointer; v1 may return sentinel on exception with full error propagation deferred per ADR-041),
and `AllowUnsafeBlocks` in the generated `.csproj`.

### Breaking changes

None. `nugetGenerateBindings` is a new additive task. No existing forward-direction code
is modified. The new source directories are wired only when a dependency has a `bind {}` block
(conditional on `bound.isNotEmpty()`, identical to the existing `nugetExtractApi` guard).

### Scope

**In v1 (this ROADMAP item):**
- `NugetGenerateBindingsTask` generating Kotlin stubs for `RirClass` types with `isStatic = true`
  methods and v1-mappable types (`RirVoidType`, `RirStringType`, `RirPrimitiveType`).
- `object {ClassName}` stub shape; two-file split per type.
- `expect`/`actual` `freeManagedString` helper for string return values.
- Source directory `build/nuget-interop/kotlin/` wired into `nativeMain` + per-target actuals.
- Namespace → Kotlin package derivation using `packageNameOverrides` + `namespaceAliases`.
- Registration export naming: `nuget_{ns_snake}_{type_snake}_register`.
- Informational diagnostics for skipped types/methods.

**Deferred (post-v1):**
- `RirInterface` stubs (Kotlin implementing a C# interface; composes with ADR-039 machinery).
- Instance method stubs — requires `RirObjectHandleType` + GCHandle lifetime management
  (mirror of ADR-003 `StableRef`; post-v1 ROADMAP: "Map C# objects as opaque handles in Kotlin").
- Exception propagation from C# thunks back to Kotlin — full error-out ABI (mirror of
  ADR-023/024 in the reverse direction; ADR-041 defers this).
- `Task<T>` / `IAsyncEnumerable<T>` methods (mirrors of ADR-019/026).
- C# properties with v1-mappable types (getter/setter) — straightforward extension of the same
  pattern; deferred for scope, not for technical reasons.
