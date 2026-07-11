# Publishing Kotlin to C#

The forward direction takes a Kotlin/Native library and generates a C# API for it. You write Kotlin, the plugin ships a `.nupkg` a C# consumer can reference directly, no code generation step on their side.

## Pipeline

At build time:

1. **KSP discovers public declarations.** The KSP processor (`nuget-processor/`) walks every public class, function, and property in the compiled Kotlin/Native source set.
2. **Translation to CIR.** Each declaration is translated into the CIR (C# Intermediate Representation) model (`CirModel`, see [ADR-004](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/004-cir-intermediate-representation.md)). CIR is the single source of truth the renderer works from, independent of KSP's symbol API.
3. **`CirRenderer` emits `Interop.cs`.** The C# source is generated once, at Kotlin build time, and shipped inside the package. There is no consumer-side codegen step, unlike the `ClangSharpPInvokeGenerator`-based approach from earlier phases (see [ADR-001](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/001-csharp-codegen-in-consumer.md)).
4. **KotlinPoet emits `Bridges.kt`.** Kotlin-side `@CName` export wrappers are generated so every bridged declaration has a stable C ABI entry point.
5. **Kotlin/Native compiles and links** shared libraries for each target platform.
6. **`packNuget` packages** the generated C#, the native binaries, and metadata into a `.nupkg`.

```
Gradle Plugin (Kotlin side)          NuGet Package       C# Consumer
┌─────────────────────────┐     ┌────────────────┐     ┌──────────────┐
│ Compile Kotlin/Native   │     │ native libs    │     │ Add package  │
│ KSP → CIR → Interop.cs  │────>│ Interop.cs     │────>│ Build        │
│ KotlinPoet → Bridges.kt │     └────────────────┘     │ Run          │
│ Link shared libraries   │                            └──────────────┘
│ Package as .nupkg       │
└─────────────────────────┘
```

## Memory model

Kotlin/Native's GC and the .NET GC know nothing about each other. Every object that crosses the bridge needs an explicit ownership story:

- **Primitives** are copied by value. No ownership concern.
- **Strings** cross as UTF-8 `const char*`, copied immediately into a managed `string` via `Marshal.PtrToStringUTF8`. The pointer is never cached.
- **Objects** are pinned Kotlin-side with `StableRef.create(...)`, which returns an opaque `COpaquePointer`. The generated C# wrapper stores that pointer as `_handle` and implements `IDisposable`; disposing releases the `StableRef`. See [ADR-003](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md).

Every time an object-typed property or return value crosses the bridge, the generated code creates a **new wrapper** around a **new `StableRef`** rather than caching or reusing an existing one. This mirrors how Kotlin/Native's ObjC and Swift exports behave. Identity is not preserved (`cat.Brother != cat.Brother` even when both point at the same Kotlin object), and disposing one wrapper never cascades to another. See [Classes and objects](classes-and-objects.md) and [ADR-005](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md) for the concrete generated shape.

## What ships in the `.nupkg`

Running `packNuget` for `sample-library` produces this layout:

```
SampleLibrary.1.0.0/
├── contentFiles/cs/any/Interop.cs
└── runtimes/
    ├── osx-arm64/native/
    └── win-x64/native/
```

- **`contentFiles/cs/any/Interop.cs`**: the generated C# source, compiled directly into the consumer's own project (not a separate assembly). This is why the generated code has no external dependency beyond the .NET BCL: it becomes part of the consumer's compilation unit.
- **`runtimes/{rid}/native/`**: the compiled Kotlin/Native shared libraries, one per supported target (`osx-arm64`, `win-x64`, ...). The .NET runtime resolves the correct native asset for the host RID automatically via `[DllImport]`.

No consumer-side build step, SDK, or tool is required beyond referencing the package.

## Package layout and namespace mapping

The Gradle DSL configures the root of the generated namespace tree:

```kotlin
nuget {
  publish {
    packageId = "MyCatLib"
    version = "1.0.0"
    authors = "yourname"
    description = "My Kotlin/Native library"
    rootPackage = "com.example.cats"
  }
}
```

Kotlin sub-packages map relative to `rootPackage`, and the C# namespace root is the package's `packageId`. In `sample-library`, `rootPackage = "io.github.xxfast.kotlin.native.nuget.sample"`, so:

| Kotlin package | C# namespace |
|---|---|
| `io.github.xxfast.kotlin.native.nuget.sample` | `SampleLibrary` |
| `io.github.xxfast.kotlin.native.nuget.sample.cat` | `SampleLibrary.Cat` |
| `io.github.xxfast.kotlin.native.nuget.sample.math` | `SampleLibrary.Math` |
| `io.github.xxfast.kotlin.native.nuget.sample.mime` | `SampleLibrary.Mime` |

Every generated declaration lands under its mapped namespace inside the single `Interop.cs` file.

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/001-csharp-codegen-in-consumer.md">ADR-001: C# codegen in the consumer</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md">ADR-003: Memory management across the bridge</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/004-cir-intermediate-representation.md">ADR-004: CIR intermediate representation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005: Object return semantics</a>
    </category>
</seealso>
