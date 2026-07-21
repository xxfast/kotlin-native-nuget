# Architecture

The plugin is one machine run in two directions. Each way, an **intermediate representation** (IR) sits in the middle: a **reader** fills it from one language's metadata, and a **renderer** emits the other language's source. The forward and reverse pipelines are deliberate mirrors of each other.

This page covers how the bridge is built, then traces one real type through every stage in each direction, using the plugin's own generated output from `test-library`.

## Build pipeline

```
Gradle Plugin (Kotlin side)          NuGet Package       C# Consumer
┌─────────────────────────┐     ┌────────────────┐     ┌──────────────┐
│ Compile Kotlin/Native   │     │ native libs    │     │ Add package  │
│ KSP → CIR → Interop.cs  │────>│ Interop.cs     │────>│ Build        │
│ KotlinPoet → exports.kt │     └────────────────┘     │ Run          │
│ Link shared libraries   │                            └──────────────┘
│ Package as .nupkg       │
└─────────────────────────┘
```

- **Gradle plugin** compiles Kotlin/Native, runs KSP to generate C# bindings (via the CIR model) and Kotlin bridge wrappers (via KotlinPoet, `CNameExports.kt`), links shared libraries, and packages everything.
- **NuGet package** ships native libs + pre-generated `Interop.cs`. No consumer-side tooling required.
- **Consumer** just includes the package — bindings are ready at build time.

## Two mirrored IRs

Feature logic lives only in the IR plus its reader and renderer. The task plumbing around them is stable in both directions, so a newly mapped language feature is a change in one place, not across the pipeline.

| Role | Forward (CIR) | Reverse (RIR) |
|---|---|---|
| Middle IR | `CirModel` (in `nuget-processor/`) | `RirModel` (in `nuget-plugin/`) |
| Reader that fills it | KSP, over Kotlin symbols | `NugetMetadataReader`, over ECMA-335 assembly metadata |
| Handoff | in-memory, same JVM process | `reverse-ir.json`, across a process boundary |
| Renderer that emits source | `CirRenderer` → `Interop.cs` / `CNameExports.kt` | Kotlin-stub + C#-shim codegen |
| Runtime direction | C# calls Kotlin (P/Invoke) | Kotlin calls C# (function pointers) |
| Decision record | [ADR-004](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/004-cir-intermediate-representation.md) | [ADR-046](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/046-reverse-ir-model-and-json-contract.md) |

```
   Forward:  Kotlin → C#                        Reverse:  C# → Kotlin

   Kotlin source                                NuGet package (.dll)
        │  KSP reads Kotlin symbols                  │  NugetMetadataReader reads ECMA-335
        ▼                                            ▼
   CIR  (CirModel)                              RIR  (RirModel)  ──►  reverse-ir.json
        │  CirRenderer                               │  stub + shim codegen
        ▼                                            ▼
   Interop.cs  +  CNameExports.kt              Kotlin stubs  +  C# registration shims
```

## Forward slice: a `data class` into C#

Two source lines of Kotlin. Follow the native prefix `toy_` as it threads through every stage: it is minted in the CIR, becomes the `[DllImport]` entry point on the C# side, and the matching `@CName` export on the Kotlin side. Same name, both ends of the ABI.

### 1. Kotlin source

The entire input. Everything downstream is generated.

```kotlin
package io.github.xxfast.kotlin.native.nuget.test.cat

data class Toy(
  val name: String,
  val color: String,
)
```

### 2. KSP fills the CIR (the reader)

KSP resolves the declaration and its types, then builds a `CirClass` in memory. This is where the `toy_` native prefix and the per-member entry points are decided, and where data-class machinery (`Copy`, `Equals`, `ToString`) is materialized. Because the CIR lives in the same JVM process, it is never serialized; this is a faithful rendering of the in-memory model:

```
CirClass(
  name = "Toy", libraryName = "test", nativePrefix = "toy",
  isDataClass = true, disposable = true,
  constructor = CirConstructor(parameters = [name, color]),   // entry: toy_create
  properties = [
    CirProperty("Name",  "string", nativeName = "toy_get_name"),
    CirProperty("Color", "string", nativeName = "toy_get_color"),
  ],
  copyMethod = CirMethod("Copy", returnType = "Toy"),          // entry: toy_copy
  methods = [ Equals, GetHashCode, ToString ],
)
```

### 3. `CirRenderer` emits the C# surface

The consumer-facing API. The Kotlin object lives behind an opaque `IntPtr _handle`; every member is a `[DllImport]` into the native library, wrapped in idiomatic C# with UTF-8 string marshalling and error propagation. The entry points match the CIR exactly.

```C#
public class Toy : IDisposable
{
    internal IntPtr _handle;

    [DllImport("test", EntryPoint = "toy_create")]
    private static extern IntPtr Native_Create(string name, string color, out IntPtr error);

    public Toy(string name, string color)
    {
        IntPtr handle = Native_Create(name, color, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        _handle = handle;
    }

    [DllImport("test", EntryPoint = "toy_get_name")]
    private static extern IntPtr Native_Get_name(IntPtr handle, out IntPtr error);
    public string Name => Marshal.PtrToStringUTF8(Native_Get_name(_handle, out _))!;

    // ... Color via toy_get_color, Copy via toy_copy, ToString, Dispose ...
}
```

### 4. The Kotlin export side

The same renderer emits `@CName` top-level functions that Kotlin/Native compiles into C exports. Each catches `Throwable` and writes it back through `errorOut`, which is the `out IntPtr error` the C# side reads. Handles cross as `StableRef`.

```kotlin
@CName("toy_create")
public fun export_toy_create(name: String, color: String, errorOut: COpaquePointer?): COpaquePointer? = try {
  StableRef.create(Toy(name, color)).asCPointer()
} catch (e: Throwable) { /* write buildError(e) into errorOut */ null }

@CName("toy_get_name")
public fun export_toy_get_name(handle: COpaquePointer, errorOut: COpaquePointer?): String = try {
  handle.asStableRef<Toy>().get().name
} catch (e: Throwable) { /* ... */ "" }
```

At runtime, C# `new Toy(...)` calls P/Invoke `toy_create`, which reaches Kotlin `export_toy_create` and returns a `StableRef` handle. See [Publishing Kotlin to C#](forward-overview.md) for the full forward pipeline.

## Reverse slice: a C# class into Kotlin

The mirror image. The plugin resolves a NuGet dependency, reads its compiled metadata, and emits Kotlin you can call. There is no source to author on the Kotlin side. Follow one member, `Apply`, from the assembly's `managedSignature` to a signature-derived bridge slot that both generated sides agree on.

### 1. C# source, in the package

Compiled into `TestDependency.dll` and shipped to a feed. The plugin only ever sees the assembly, never this source.

```C#
namespace Test.Text;
public class Template
{
    public Template(string source) { }         // constructor
    public string Source => _source;            // read-only  -> val
    public string Name { get; set; }            // settable   -> var
    public string Apply(string name) { }        // instance method
    public Template Clone() { }                 // returns a bound handle
    public static Template Parse(string source) { }
    public static string Render(Template t, string name) { }
    public static string DefaultName { get; set; }
    public static int RenderCount { get; }
}
```

### 2. `NugetMetadataReader` fills the RIR (the reader)

A .NET console tool reads ECMA-335 metadata and emits `reverse-ir.json`, the one artifact that crosses the JVM ↔ .NET process boundary. Each member carries its `managedSignature`, which is later hashed into a stable bridge identity so the two generated sides cannot drift apart.

```json
{
  "kind": "class", "name": "Template", "isStatic": false,
  "methods": [
    { "name": "Apply", "isStatic": false,
      "returnType": { "kind": "string", "nullable": false },
      "parameters": [ { "name": "name", "type": { "kind": "string" } } ],
      "managedSignature": "method|instance|Test.Text.Template|Apply|(System.String)|System.String" },
    { "name": "Parse",  "isStatic": true },
    { "name": "Render", "isStatic": true }
  ],
  "properties": [
    { "name": "Name",        "isStatic": false, "isReadOnly": false, "type": {"kind":"string"} },
    { "name": "RenderCount", "isStatic": true,  "isReadOnly": true,  "type": {"kind":"primitive","name":"int"} }
  ]
}
```

### 3. The renderer emits the Kotlin wrapper

Idiomatic Kotlin: instance members on the class, statics in a `companion object`, `get`/`set` for settable properties, and `AutoCloseable` plus a `Cleaner` over the underlying `GCHandle`. Every call reads a function pointer from `TemplateBindings`, the slot keyed by that hashed signature.

```kotlin
internal class Template internal constructor(handle: COpaquePointer) : AutoCloseable {
  internal val handle = NugetObjectHandle(handle)
  private val cleaner = createCleaner(this.handle) { it.free() }   // GC-time release
  override fun close() = handle.free()                             // or use { } for determinism

  constructor(source: String) : this(construct__2b78a67(source))

  fun apply(name: String): String {
    val fn = requireNotNull(TemplateBindings.apply__cc33a635Fn) {
      NugetRegistry.notRegistered("Test.Text.Template", "TestDependency")
    }
    val resultPtr = memScoped { fn.invoke(handle.require("Template"), name.cstr.ptr) } ?: error("...")
    return resultPtr.reinterpret<ByteVar>().toKString().also { freeManagedString(resultPtr) }
  }

  companion object {
    fun parse(source: String): Template { /* parse__c03e15Fn */ }
    fun render(template: Template, name: String): String { /* render__2c5dbdFn */ }
    var defaultName: String   // defaultNameGetter/SetterFn
    val renderCount: Int      // renderCountGetterFn
  }
}
```

### 4. The native binding table and register export

The wrapper's function pointers start null. A `@CName` export lets C# hand them over at startup. Before storing anything, it checks the contract (`slotCount` plus `contractHash`): a stale shim with the wrong shape is rejected up front instead of corrupting the call. See [ADR-054](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/054-reverse-bridge-registration-observability.md) and [Registration diagnostics](registration-diagnostics.md).

```kotlin
internal object TemplateBindings {
  internal var apply__cc33a635Fn: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>? = null
  // ... 11 slots total, one per bridged member ...
}

@CName("nuget_test_text_template_register")
fun nuget_test_text_template_register(slotCount: Int, contractHash: Long, /* ...11 ptrs... */) {
  NugetRegistry.checkContract(
    qualifiedType = "Test.Text.Template", packageId = "TestDependency",
    slotCount = slotCount, contractHash = contractHash,
    expectedSlots = 11, expectedHash = -2136106240238705992L,   // reject a stale shim
  )
  TemplateBindings.apply__cc33a635Fn = requireNotNull(apply__cc33a635Ptr) { "..." }.reinterpret()
  // ... store the remaining 10 pointers ...
}
```

### 5. The C# registration shim

The mirror of the binding table. A `[ModuleInitializer]` fires at process start and calls the Kotlin register export, passing `[UnmanagedCallersOnly]` thunks as raw function pointers. Each thunk unwraps the `GCHandle`, calls the real C# member, and marshals the result back.

```C#
namespace Test.Text
{
    internal static class TemplateRegistration
    {
        [DllImport("test", EntryPoint = "nuget_test_text_template_register")]
        private static extern void nuget_test_text_template_register(
            int slotCount, long contractHash, IntPtr ctorPtr, /* ... */ IntPtr renderCountGetterPtr);

        [ModuleInitializer]                          // fires once at process start
        internal static unsafe void Initialize() => nuget_test_text_template_register(
            11, -2136106240238705992L,
            (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr>)(&Apply_Thunk),
            /* ... the other 10 thunks, in the same canonical slot order ... */);

        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
        private static IntPtr Apply_Thunk(IntPtr selfHandle, IntPtr namePtr)
        {
            Template receiver = (Template)GCHandle.FromIntPtr(selfHandle).Target!;
            string result = receiver.Apply(Marshal.PtrToStringUTF8(namePtr)!);
            return Marshal.StringToCoTaskMemUTF8(result);
        }
    }
}
```

At runtime, Kotlin `template.apply(name)` invokes the stored function pointer, which lands in the C# `Apply_Thunk`, which calls `Template.Apply` and marshals the string back. See [Consuming C# in Kotlin](reverse-overview.md) for the full reverse pipeline.

## Runtime call flow

The Kotlin/Native library always runs inside a .NET host process, so neither direction needs a runtime host. Forward calls go out over P/Invoke; reverse calls go out over function pointers registered once, at startup.

- **Forward, C# calls Kotlin.** A `[DllImport]` bound to a generated `@CName` export. Synchronous and direct. Errors ride back through an `out IntPtr error` the export fills from any thrown `Throwable`. A forward call can also carry a delegate the other way: C# pins a `Func<>`/`Action<>` with `GCHandle` and hands it over as a function pointer, which Kotlin `reinterpret`s to `CPointer<CFunction<…>>` and invokes as a lambda (inside `filter`/`map`/…), calling back into C#. See [ADR-036](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md).
- **Reverse, Kotlin calls C#.** No P/Invoke into C#. At startup, a `[ModuleInitializer]` hands Kotlin every thunk pointer (contract-checked), which Kotlin stores in a bindings table. Each later call invokes the stored pointer. A stale shim is caught at registration: a mismatched `slotCount` or `contractHash` refuses to store any pointer.

## The Gradle tasks that build the artifacts

Two task chains, one per direction. See [Gradle tasks](gradle-tasks.md) for the full reference.

**Forward: publish Kotlin → C#**, producing `MyLib.1.0.0.nupkg`:

| Task | Does | Produces |
|---|---|---|
| compile + link | Kotlin/Native shared libs, one per target RID | `.dylib` / `.dll` |
| KSP → CIR | reader + renderer emit the C# and Kotlin bridge source | `Interop.cs`, `CNameExports.kt` |
| `packNuget` | assembles `runtimes/` + `contentFiles` into the package | `.nupkg` |

**Reverse: consume C# → Kotlin**, aggregated behind the `nugetImport` IDE-sync task:

| Task | Does | Produces |
|---|---|---|
| `nugetGen` | writes a synthetic `.csproj` for the bound dependencies | `interop.csproj` |
| `nugetRestore` | `dotnet restore`; pins net8.0, fails fast below the floor | `project.assets.json` |
| `nugetExtractApi` | runs `NugetMetadataReader` over the resolved `.dll` | `reverse-ir.json` |
| `nugetGenerateBindings` | Kotlin stubs + native binding tables from the RIR | `Template.kt`, `TemplateBindings.kt` |
| `nugetGenerateShims` | C# registration shims with thunks + `[ModuleInitializer]` | `TemplateRegistration.cs` |

<seealso>
    <category ref="related">
        <a href="forward-overview.md">Publishing Kotlin to C#</a>
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="gradle-tasks.md">Gradle tasks</a>
        <a href="registration-diagnostics.md">Registration diagnostics</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/004-cir-intermediate-representation.md">ADR-004: CIR intermediate representation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md">ADR-036: Reverse interop mechanism</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/042-assembly-metadata-extraction.md">ADR-042: Assembly metadata extraction</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/046-reverse-ir-model-and-json-contract.md">ADR-046: Reverse IR model and JSON contract</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/048-kotlin-stub-generation-from-reverse-ir.md">ADR-048: Kotlin stub generation from reverse IR</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/049-csharp-registration-shim-generation.md">ADR-049: C# registration shim generation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/054-reverse-bridge-registration-observability.md">ADR-054: Reverse bridge registration observability</a>
    </category>
</seealso>
