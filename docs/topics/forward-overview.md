# Publishing Kotlin to C#

The forward direction takes a Kotlin/Native library and generates a C# API for it. You write Kotlin, the plugin ships a `.nupkg` a C# consumer can reference directly, no code generation step on their side.

## Pipeline

At build time:

1. **KSP discovers public declarations.** The KSP processor (`nuget-processor/`) walks every public class, function, and property in the compiled Kotlin/Native source set.
2. **Ordinary sync callables are planned once.** Each ordinary synchronous function, method, constructor, property, companion, object method, extension, and value-class member is classified into a `BridgeType` and validated as a `ForwardCallablePlan` or `ForwardPropertyPlan` ([ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)). Specialized protocols (suspend, `Flow`, lambda/callback, sealed helpers, generic declaration families) stay on named legacy routes.
3. **Dual projection from the plan.** The same plan projects to CIR for C# ([ADR-004](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/004-cir-intermediate-representation.md)) and to KotlinPoet `@CName` exports. A generation-time ABI contract check ([ADR-055](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/055-forward-abi-contract-check.md)) compares both halves to the plan.
4. **`CirRenderer` emits `Interop.cs`.** The C# source is generated once, at Kotlin build time, and shipped inside the package. There is no consumer-side codegen step, unlike the `ClangSharpPInvokeGenerator`-based approach from earlier phases (see [ADR-001](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/001-csharp-codegen-in-consumer.md)).
5. **KotlinPoet emits `Bridges.kt`.** Kotlin-side `@CName` export wrappers are generated so every bridged declaration has a stable C ABI entry point.
6. **Kotlin/Native compiles and links** shared libraries for each target platform.
7. **`packNuget` packages** the generated C#, the native binaries, and metadata into a `.nupkg`.

```
Gradle Plugin (Kotlin side)                NuGet Package       C# Consumer
┌───────────────────────────────────┐     ┌────────────────┐     ┌──────────────┐
│ Compile Kotlin/Native             │     │ native libs    │     │ Add package  │
│ KSP → plan → CIR → Interop.cs     │────>│ Interop.cs     │────>│ Build        │
│        ↘ KotlinPoet → Bridges.kt  │     └────────────────┘     │ Run          │
│ Link shared libraries             │                            └──────────────┘
│ Package as .nupkg                 │
└───────────────────────────────────┘
```

## Memory model

Kotlin/Native's GC and the .NET GC know nothing about each other. Every object that crosses the bridge needs an explicit ownership story:

- **Primitives** are copied by value. No ownership concern.
- **Strings** cross as UTF-8 `const char*`, copied immediately into a managed `string` via `Marshal.PtrToStringUTF8`. The pointer is never cached.
- **Objects** are pinned Kotlin-side with `StableRef.create(...)`, which returns an opaque `COpaquePointer`. The generated C# wrapper stores that pointer as `_handle` and implements `IDisposable`; disposing releases the `StableRef`. See [ADR-003](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md).

Every time an object-typed property or return value crosses the bridge, the generated code creates a **new wrapper** around a **new `StableRef`** rather than caching or reusing an existing one. This mirrors how Kotlin/Native's ObjC and Swift exports behave. Identity is not preserved (`cat.Brother != cat.Brother` even when both point at the same Kotlin object), and disposing one wrapper never cascades to another. See [Classes and objects](classes-and-objects.md) and [ADR-005](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md) for the concrete generated shape.

## What ships in the `.nupkg`

Running `packNuget` for `test-library` produces this layout:

```
TestLibrary.1.0.0/
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

Kotlin sub-packages map relative to `rootPackage`, and the C# namespace root is the package's `packageId`. In `test-library`, `rootPackage = "io.github.xxfast.kotlin.native.nuget.test"`, so:

| Kotlin package | C# namespace |
|---|---|
| `io.github.xxfast.kotlin.native.nuget.test` | `TestLibrary` |
| `io.github.xxfast.kotlin.native.nuget.test.cat` | `TestLibrary.Cat` |
| `io.github.xxfast.kotlin.native.nuget.test.math` | `TestLibrary.Math` |
| `io.github.xxfast.kotlin.native.nuget.test.mime` | `TestLibrary.Mime` |

Every generated declaration lands under its mapped namespace inside the single `Interop.cs` file.

By default every public declaration in the module is bridged, not only those under `rootPackage`.
`publish { include(...); exclude(...) }` narrows that to an explicit package-prefix allowlist, and
when `include` is left empty, `rootPackage` itself becomes the default scope.

The export set is not limited to the module's own files, either: it is a reachability closure that
also walks into types declared in a dependency Gradle module (return types, parameter types,
property types, type arguments of `Flow<T>`/collections, sealed subclasses, primary-constructor
parameters), admitting each discovered type through the same `include`/`exclude`/`rootPackage`
predicate. See [The nuget {} DSL](nuget-dsl.md) for the full predicate and the cross-module closure
rules.

## Diagnostics

Not every Kotlin construct can be expressed as C#. When the generator meets one it cannot bridge,
it names the member and the reason, at the author's own Kotlin source, rather than emitting invalid
Kotlin or a C# API whose signature lies about its contract. Every diagnostic carries a
`ForwardDiagnosticKind` whose name encodes its severity:

- **`SKIPPED_*`**: the member is warned about and omitted entirely from the generated C# API.
  Generation continues. This is the default for a construct the forward direction cannot express
  (an unsupported type, a `Map`/`Set` parameter, a nullable `Boolean` return, an unsupported
  generic/suspend combination, a value-class member inherited through interface delegation).
- **`INFO_*`**: the member still binds, under a documented assumption (for example, `out`/`in`
  variance on a class type parameter is dropped, but the member still generates).
- **`ERROR_*`**: generation fails before any C# is written. The only v1 case is two constructors
  that render an identical C# signature ([ADR-034](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/034-secondary-constructor-exceptions.md)).

A `Map` parameter (see [Collections](collections.md)) is skipped like this:

```
[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping Patient.setTags: its COLLECTION type combination is not
    supported. expose a wrapper taking a List/MutableList (or individual key/value parameters)
    instead of a Map/Set at this position
    at Fixture.kt:4
```

The message names the declaration, the reason, an actionable hint, and, when KSP can resolve it,
the file and line of the Kotlin declaration that was skipped, something the reverse direction's
`RirDiagnostic` cannot carry, since it works from compiled metadata rather than source. See each
forward page's own **Limitations** section for which named diagnostic fires where.

Two more kinds cover the cross-module export closure ([ADR-066](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md);
see [The nuget {} DSL](nuget-dsl.md) for the closure's own rules). A reachable dependency-module type
outside the effective `include`/`rootPackage` scope is skipped, naming the exact fix, from
`test-library`'s `Newsroom.sponsor(): Advertisement` (`dev.other.core.Advertisement` sits outside
`rootPackage`):

```
[nuget:SKIPPED_UNEXPORTED_DEPENDENCY_TYPE] Skipping Newsroom.sponsor: its UNEXPORTED_DEPENDENCY_TYPE
    type combination is not supported. add include("dev.other.core") to nuget { publish { } }, or
    expose a type from an in-scope package instead
    at Newsroom.kt:63
```

When at least one dependency-module type *is* admitted, the closure also emits one aggregate
`INFO_EXPORTED_FROM_DEPENDENCY` line per KSP run rather than one line per type, naming the whole
admitted set.

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/001-csharp-codegen-in-consumer.md">ADR-001: C# codegen in the consumer</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md">ADR-003: Memory management across the bridge</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/004-cir-intermediate-representation.md">ADR-004: CIR intermediate representation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005: Object return semantics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/055-forward-abi-contract-check.md">ADR-055: Forward ABI contract check</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/063-forward-declaration-level-export-scoping.md">ADR-063: Forward declaration-level export scoping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md">ADR-066: Forward export reachability closure</a>
    </category>
</seealso>
