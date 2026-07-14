# Consuming C# in Kotlin

The reverse direction lets Kotlin/Native code call into a C# NuGet package as if it were a native
Kotlin library. The Gradle plugin resolves the package, reads its public API straight out of the
compiled assembly, and generates both sides of the bridge: Kotlin stubs your code calls, and a C#
shim that registers function pointers with Kotlin at process startup.

## Why this direction needs no CLR hosting

The Kotlin/Native library you're building always runs *inside* a .NET host process: it's loaded via
P/Invoke from a C# application or library. The CLR is already resident in the same process, so there
is nothing to bootstrap. The only problem to solve is the opposite of the forward direction: Kotlin
needs a way to call into managed code, and there's no per-call opportunity for C# to hand Kotlin a
function pointer (unlike the forward direction's callback machinery). The mechanism is described in
full in [ADR-041](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/041-kotlin-to-csharp-call-mechanism.md).

## The pipeline

```
nugetGen              generates a synthetic interop.csproj
    ↓
nugetRestore          dotnet restore → obj/project.assets.json
    ↓
nugetExtractApi       nuget-metadata-reader subprocess → reverse-ir.json
    ↓
nugetGenerateBindings  reverse-ir.json → Kotlin stubs
nugetGenerateShims     reverse-ir.json → C# registration shims
    ↓
packNuget              merges the shims into contentFiles, pins the bound
                        package at its exact resolved version
```

`nugetImport` is the umbrella IDE-sync task that runs the whole chain (mirroring `podImport` in the
Kotlin CocoaPods plugin).

### `nugetGen`: a synthetic `.csproj`

For every declared dependency (see [Declaring dependencies](declaring-dependencies.md)) the plugin
writes a throwaway `interop.csproj` with one `<PackageReference>` per package, pinned to
`net8.0`, and `<RuntimeIdentifiers>` covering every configured Kotlin/Native target so
`runtimes/{rid}/native/` payloads resolve for all of them.

### `nugetRestore`: `dotnet restore`

Running `dotnet restore` against that `.csproj` resolves the full transitive dependency graph,
downloads packages to the local NuGet cache, and writes `obj/project.assets.json`, the manifest the
rest of the pipeline reads. NuGet's own resolver handles version conflicts, floating versions, and
multi-feed sources; the plugin re-implements none of that.

### `nugetExtractApi`: reading ECMA-335 metadata

`nugetExtractApi` invokes `nuget-metadata-reader`, a small bundled `dotnet` console app. It opens
each resolved `.dll` with `System.Reflection.Metadata`'s `PEReader` and `MetadataReader` and reads
the ECMA-335 metadata tables directly, without loading or executing the assembly. It walks public
top-level types, filters members through the bridgeable-subset rules (see
[The bridgeable subset](bridgeable-subset.md)), and emits `build/nuget-interop/reverse-ir.json`, the
Reverse Intermediate Representation (RIR). Every member the filter rejects is recorded as a
diagnostic in the same file rather than silently dropped.

Supported public top-level C# enums are also extracted as standalone Kotlin `enum class` values.
The v1 enum subset is deliberately ordinal-backed: default-`int`, non-`[Flags]` enums whose values
are unique and contiguous from `0` through `N-1`. See [The bridgeable subset](bridgeable-subset.md)
for the supported positions and exclusions.

A bridgeable struct is extracted too, but it never becomes a handle: its components decompose onto the
wire, and it surfaces as an immutable Kotlin `data class`. A struct with a state-covering constructor
("Shape A") draws its components from that constructor's parameters; a struct with no such
constructor but only public settable fields/auto-properties ("Shape B") draws them from its C#
declaration order instead, and C# reconstructs it with an object initializer rather than a
constructor call. Alternate public constructors (Shape A only), struct methods, get-only computed
properties, and static methods on the struct receive bridge-backed registration slots (slot order:
alternate ctors → static methods → instance methods → computed getters); the state constructor (or, for
Shape B, the object-initializer reconstruction) remains slot-free. Members alone force a registration
export even without alternate constructors. See [C# structs](structs.md).

### `nugetGenerateBindings` and `nugetGenerateShims`: two generators, one contract

Both tasks read the identical `reverse-ir.json` and must agree on one shared contract, fixed by
[ADR-041](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/041-kotlin-to-csharp-call-mechanism.md)
and [ADR-048](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/048-kotlin-stub-generation-from-reverse-ir.md):

- Every bound C# type gets one registration export named
  `nuget_{namespace_snake}_{type_snake}_register` (for example
  `Newtonsoft.Json.JsonConvert` → `nuget_newtonsoft_json_json_convert_register`).
- The export takes one function-pointer parameter per bridgeable member, in a single shared
  order: constructors, static methods, instance methods, instance properties, then static
  properties. Each category is sorted by canonical managed identity and each property getter stays
  immediately before its setter. All slots are drawn from a shared helper
  (`bridgeableRegistrables` in `RirBridging.kt`) so the two generators can never drift out of
  parameter-order sync.
- Overloaded methods and constructors retain their ordinary Kotlin names. Their generated thunk,
  helper, and pointer identifiers carry a checked 128-bit SHA-256-derived ID of the canonical
  managed signature, keeping internal identities unique and stable across declaration reorderings.
- On the Kotlin side these arrive as `CPointer<CFunction<...>>?` variables. Every generated stub
  does `requireNotNull(fn) { "... bindings are not registered ..." }` before calling through the
  pointer, so a missing registration fails fast with a clear message instead of a null-pointer
  crash.
- On the C# side, a `[ModuleInitializer]` method runs automatically when the assembly loads and
  calls the Kotlin registration export, passing the address of one `[UnmanagedCallersOnly]` thunk
  per member. Two leading scalars, `slotCount` and `contractHash`, precede the thunk pointers: a
  registration-time contract self-check, described in [Registration diagnostics](registration-diagnostics.md)
  ([ADR-054](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/054-reverse-bridge-registration-observability.md)):

```C#
// build/nuget-interop/csharp/MimeUtilityRegistration.cs (real generated output)
internal static class MimeUtilityRegistration
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_mimemapping_mime_utility_register")]
    private static extern void nuget_mimemapping_mime_utility_register(int slotCount, long contractHash, IntPtr getMimeMapping__cb4c202351abfeec4d85fccb5ca462a0Ptr);

    [ModuleInitializer]
    internal static unsafe void Initialize()
    {
        NugetTrace.Write(
            "register enter MimeMapping.MimeUtility -> nuget_mimemapping_mime_utility_register(1 slot) dll=sample");
        try
        {
            nuget_mimemapping_mime_utility_register(
                1,
                8143158361847426877L,
                (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr>)(&GetMimeMapping__cb4c202351abfeec4d85fccb5ca462a0_Thunk));
        }
        catch (DllNotFoundException e)
        {
            NugetTrace.WriteAlways($"FATAL: native library 'sample' not found: {e.Message}");
            throw;
        }
        catch (EntryPointNotFoundException e)
        {
            NugetTrace.WriteAlways($"FATAL: export 'nuget_mimemapping_mime_utility_register' missing from " +
                $"'sample'. The native library predates this shim (stale build state). {e.Message}");
            throw;
        }
        NugetTrace.Write("register ok    MimeMapping.MimeUtility");
    }

    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    private static IntPtr GetMimeMapping__cb4c202351abfeec4d85fccb5ca462a0_Thunk(IntPtr filePtr)
    {
        string result = MimeUtility.GetMimeMapping(Marshal.PtrToStringUTF8(filePtr)!);
        return Marshal.StringToCoTaskMemUTF8(result);
    }
}
```

```kotlin
// build/nuget-interop/kotlin/nativeMain/mimemapping/MimeUtilityBindings.kt (real generated output)
internal object MimeUtilityBindings {
  @Suppress("NOTHING_TO_INLINE")
  internal var getMimeMapping__cb4c202351abfeec4d85fccb5ca462a0Fn: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
}

@CName("nuget_mimemapping_mime_utility_register")
fun nuget_mimemapping_mime_utility_register(
  slotCount: Int,
  contractHash: Long,
  getMimeMapping__cb4c202351abfeec4d85fccb5ca462a0Ptr: COpaquePointer?,
) {
  NugetRegistry.checkContract(
    qualifiedType = "MimeMapping.MimeUtility",
    packageId = "MimeMapping",
    slotCount = slotCount,
    contractHash = contractHash,
    expectedSlots = 1,
    expectedHash = 8143158361847426877L,
  )
  MimeUtilityBindings.getMimeMapping__cb4c202351abfeec4d85fccb5ca462a0Fn = requireNotNull(getMimeMapping__cb4c202351abfeec4d85fccb5ca462a0Ptr) { "nuget_mimemapping_mime_utility_register passed a null getMimeMapping thunk pointer." }.reinterpret()
  NugetRegistry.record("MimeMapping.MimeUtility", 1)
}
```

Kotlin never calls the registration function itself; C# calls it once, automatically, before any of
your Kotlin code can run. Because `[ModuleInitializer]` only fires once the containing module is
actually loaded, the shim ships as *source* merged into the consumer's own compile, not a separately
compiled DLL. A separately compiled shim assembly might never get loaded at all, since nothing in a
typical consumer's code path references it directly.

`checkContract` and the `requireNotNull` guard it protects are always on: no environment variable
enables them. See [Registration diagnostics](registration-diagnostics.md) for what a mismatch
means and how to read it, and for the opt-in trace that logs each registration as it lands.

### `packNuget`: one `PackageReference` for the consumer

`packNuget` copies the generated `.cs` shim files into the same `contentFiles/cs/any/` folder that
already carries the forward-direction `Interop.cs`, so a consuming `.csproj` compiles them straight
into its own assembly. It also adds a `<dependencies>` entry to the `.nuspec` for each bound package,
pinned to the **exact version NuGet resolved** (not the floating floor declared in the DSL), because
the shim's method signatures are frozen against that one specific assembly. A C# consumer needs
nothing more than:

```xml
<PackageReference Include="SampleLibrary" Version="1.0.0" />
```

NuGet then compiles the shims, imports the `AllowUnsafeBlocks` MSBuild target, copies the native
library, and restores `MimeMapping`/`SampleDependency` transitively, with zero hand-edited items.

## What you need installed

This direction requires the .NET SDK (8 or later) on the machine running the Kotlin build, because
`nugetRestore` and `nugetExtractApi` shell out to `dotnet`. This is a prerequisite for the reverse
direction only: a Kotlin library author who never declares a `bind {}` block needs nothing beyond
the usual Kotlin/Native toolchain. If `dotnet` isn't found on `PATH`, the plugin fails fast with an
install pointer rather than a cryptic subprocess error.

<seealso>
    <category ref="related">
        <a href="declaring-dependencies.md">Declaring dependencies</a>
        <a href="static-classes-and-methods.md">Static classes and methods</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="registration-diagnostics.md">Registration diagnostics</a>
        <a href="instance-members.md">Instance members</a>
        <a href="structs.md">C# structs</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/041-kotlin-to-csharp-call-mechanism.md">ADR-041: Kotlin → managed C# call mechanism</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/042-assembly-metadata-extraction.md">ADR-042: Assembly metadata extraction</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/045-nuget-resolution-pipeline.md">ADR-045: NuGet resolution pipeline</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/046-reverse-ir-model-and-json-contract.md">ADR-046: Reverse IR model and JSON contract</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/048-kotlin-stub-generation-from-reverse-ir.md">ADR-048: Kotlin stub generation from reverse IR</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/049-csharp-registration-shim-generation.md">ADR-049: C# registration shim generation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/050-end-to-end-packaging-integration.md">ADR-050: End-to-end packaging integration</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/054-reverse-bridge-registration-observability.md">ADR-054: Reverse-bridge registration observability</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/056-csharp-structs-in-kotlin.md">ADR-056: C# structs (value types) in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/057-csharp-overload-sets-in-kotlin.md">ADR-057: C# overload sets in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/058-csharp-shape-b-structs-in-kotlin.md">ADR-058: C# Shape B structs in Kotlin</a>
    </category>
</seealso>
