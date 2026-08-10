# Publishing Kotlin to C#

The forward direction takes a Kotlin/Native library and generates a C# API for it. You write Kotlin, the plugin ships a `.nupkg` a C# consumer can reference directly, no code generation step on their side.

## Pipeline

At build time:

1. **KSP discovers public declarations.** The KSP processor (`nuget-processor/`) walks every public class, function, and property in the compiled Kotlin/Native source set.
2. **Ordinary sync callables are planned once.** Each ordinary synchronous function, method, constructor, property, companion, object method, extension, and value-class member is classified into a `BridgeType` and validated as a `ForwardCallablePlan` or `ForwardPropertyPlan` ([ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)). Specialized protocols (suspend, `Flow`, lambda/callback, sealed helpers, generic declaration families) stay on named legacy routes.
3. **Dual projection from the plan.** The same plan projects to CIR for C# ([ADR-004](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/004-cir-intermediate-representation.md)) and to KotlinPoet `@CName` exports. A generation-time ABI contract check ([ADR-055](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/055-forward-abi-contract-check.md)) compares both halves to the plan. The same check also covers the specialized legacy routes ([ADR-078](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/078-forward-abi-legacy-contract-coverage.md)): since their `DllImport` declarations are raw renderer text rather than plan output, they are collected straight from the rendered `Interop.cs` and normalized the same way before being compared against their Kotlin `@CName` exports.
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
  (an unsupported type, a `Map`/`Set` parameter, an unsupported generic/suspend combination, a
  value-class member inherited through interface delegation).
- **`INFO_*`**: the member still binds, under a documented assumption (for example, `out`/`in`
  variance on a class type parameter is dropped, but the member still generates).
- **`ERROR_*`**: generation fails before any C# is written. The only v1 case is two constructors
  that render an identical C# signature ([ADR-034](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/034-secondary-constructor-exceptions.md)).
  This also catches two constructors that differ only in *reference*-type nullability
  (`constructor(from: Patient)` next to `constructor(from: Patient?)`): C# does not treat a nullable
  reference annotation as part of a method's signature, so both would otherwise render, correctly but
  uncompilably, as `Referral(Patient from)` and `Referral(Patient? from)` (`CS0111`). Nullable
  **value** types are unaffected and keep working: `constructor(n: Int)` next to
  `constructor(n: Int?)` render genuinely distinct signatures and are not treated as a collision.

A `Map`/`Set` parameter with an unsupported key/value/element type (see [Collections](collections.md))
is skipped like this:

```
[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping Patient.setMoods: its COLLECTION type combination is not
    supported. expose a wrapper taking a List/MutableList (or individual key/value parameters)
    instead of a Map/Set at this position
    at Fixture.kt:4
```

A property whose declared type the property planner has no getter/setter shape for is skipped the
same way, naming the property's own type. `Cat.unsupported: Sequence<String>` is the fixture:

```
[nuget:SKIPPED_UNSUPPORTED_PROPERTY] Skipping Cat.unsupported: its type generic declaration
    kotlin.sequences.Sequence has no property getter or setter shape. expose a bridgeable property
    (or a getter function) whose type is not generic declaration kotlin.sequences.Sequence, and
    export that instead
    at Cat.kt:46
```

`SKIPPED_UNSUPPORTED_PROPERTY` never fires for a property whose type is a lambda, suspend lambda,
`Flow`, or `StateFlow` (nullable or not): those are unplannable by design and still bind through a
named legacy route, so warning would tell a consumer a working property had vanished.

The same kind also fires when an extension property's *receiver* type, not its declared type, is
what the planner can't wire. `String`, a primitive, `ObjectHandle` classes, and a value class over
any of the four underlyings admitted at ordinary positions (`String`, a primitive, an enum, or
`ObjectHandle`) are the supported receivers; anything else warns and the property is dropped
entirely, naming the receiver rather than the property's own (usually fine) type:

```
[nuget:SKIPPED_UNSUPPORTED_PROPERTY] Skipping tier1.skipreceiver.Box.label: its extension receiver
    type generic declaration tier1.skipreceiver.Box is not a supported extension-property receiver.
    declare the property on a class, String, primitive, or value class receiver, or expose a
    top-level getter function instead
    at <file>:<line>
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

## Limitations

<note>
<p>Another Kotlin compiler plugin on the same target's <code>kotlinCompilerPluginClasspath</code>
can crash <b>link time</b> for a <code>sharedLib</code>, even though ordinary compilation succeeds.
This is not a bug in this plugin; it is the pre-existing Kotlin/Native backend issue
<a href="https://youtrack.jetbrains.com/issue/KT-62984">KT-62984</a>, and it is triggered by any
plugin that generates IR without a klib origin, not by anything this project's KSP processor
emits.</p>
<p>The crash is a <code>NullPointerException</code> in <code>CAdapterCodegen.buildCAdapter</code>
during C-export codegen, which every forward-direction target hits because every forward target
links a <code>sharedLib</code>. Marking the affected declarations <code>internal</code> does not
help, since the crashing IR belongs to the other plugin, not to user code. See
<a href="publish-kotlin-library-as-nuget.md">Publish a Kotlin/Native library as NuGet</a> for the
symptom and the classpath-exclusion workaround.</p>
</note>

### AOT and trimming {id="aot-and-trimming"}

The generated bindings are not safe under a fully AOT-compiled .NET runtime yet.

The generics bridge's reflection, the first blocker
([ADR-038](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/038-aot-compilation.md), Deferred),
is fixed: [ADR-094](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/094-reflection-free-generic-dispatch.md)
(Accepted) replaced the type-erased generics bridge's `GetField`/`Activator.CreateInstance`
reflection with a static factory registry. A build against a pre-ADR-094 version of these bindings
(plugin 0.2.0) could still trigger the .NET trimmer's `IL2075` on that `GetField`, mitigated with
`<TrimmerRootAssembly>` on the project compiling `Interop.cs`; on the current generator this should
no longer fire for that reason.

<warning>
<p>Every callback that crosses from C# into Kotlin still is not AOT-safe: <code>Flow</code>/<code>StateFlow</code>
collection, <code>suspend</code>/<code>async</code>, a lambda parameter, a stored callback, and
interface bridging. Each pins a C# delegate with <code>GCHandle</code> and hands Kotlin a pointer via
<code>Marshal.GetFunctionPointerForDelegate</code>
(<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md">ADR-036</a>),
which needs the runtime to JIT a native-to-managed thunk the first time it is invoked from native
code. A fully AOT-compiled build has no JIT to do that.</p>
<p>Verified against a Release-configuration Mac Catalyst arm64 build (.NET 10, MAUI, plugin 0.2.0):
on the first <code>Flow</code> collection, Mono throws <code>ExecutionEngineException</code>
(<code>AOT NOT FOUND: (wrapper native-to-managed) KotlinFlowEnumerator...</code>) building the
thunk for the flow enumerator's callback. Debug builds work, since they still JIT. <b>The failure
can be entirely silent</b>: a collection loop that only catches <code>OperationCanceledException</code>,
on an unobserved task, leaves the app running with an empty UI and no crash. Repro: build
<code>-c Release -f net10.0-maccatalyst</code>, run the built <code>.app</code>'s binary directly
with <code>MONO_LOG_LEVEL=debug MONO_LOG_MASK=aot</code>, and grep the output for
<code>AOT NOT FOUND</code>.</p>
<p>iOS and tvOS devices run the same Mono full-AOT regime, so the same failure is expected there too
(currently academic: the package ships no <code>ios-arm64</code> native asset, and Mac Catalyst
borrows the <code>osx-arm64</code> dylib via <code>NativeReference</code>). NativeAOT
(<code>PublishAot=true</code>) on any OS is expected to fail the same way on the callback thunks,
since it also has no JIT. Neither is verified directly.</p>
</warning>

Affected: <a href="coroutines-and-flow.md">Coroutines and Flow</a>'s `Flow`/`StateFlow` collection and
`suspend`/`async`, and <a href="lambdas-and-callbacks.md">Lambdas and callbacks</a>'s lambda
parameters, stored callbacks, and <a href="interfaces-abstract-sealed.md">interface bridging</a>. Not
affected: the synchronous, callback-free surface, methods, properties, constructors, strings,
collections.

Three workarounds, in order of preference:

1. **`MtouchInterpreter` (Catalyst/iOS Release builds).** Verified in the same sample: adding
   `<MtouchInterpreter>-all</MtouchInterpreter>` (or `-p:MtouchInterpreter=-all`) to the consumer
   `.csproj` keeps the rest of the app fully AOT-compiled but ships the Mono interpreter as a
   fallback for the paths AOT could not pre-generate, including these callback thunks. Re-running
   the exact failing scenario with this flag set threw no `ExecutionEngineException`, and the
   generated `Flow` delivered states with live data on screen. The perf cost is limited to the code
   paths the interpreter actually executes. This only applies to `net*-ios`/`net*-maccatalyst`
   TFMs; NativeAOT (`PublishAot=true`) has no interpreter equivalent, so on NativeAOT the limitation
   stands unmitigated.
2. **Debug/JIT configurations** work as-is for local development, no flag needed.
3. **The PeopleInSpace pattern**, exporting scalar accessors from Kotlin and polling them instead of
   collecting a generated `Flow`, for the cases the interpreter is unacceptable: a strict full-AOT
   policy, NativeAOT, or a performance-critical hot path.

The identified permanent fix is porting
[ADR-041](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/041-kotlin-to-csharp-call-mechanism.md)'s
`[UnmanagedCallersOnly]` + `[ModuleInitializer]` registration pattern, already used for the reverse
direction, onto this callback surface, which would remove the need for the interpreter fallback
entirely and also cover NativeAOT; tracked in
[ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/001-csharp-codegen-in-consumer.md">ADR-001: C# codegen in the consumer</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md">ADR-003: Memory management across the bridge</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/004-cir-intermediate-representation.md">ADR-004: CIR intermediate representation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005: Object return semantics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md">ADR-036: Reverse interop mechanism</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/038-aot-compilation.md">ADR-038: NativeAOT compatibility</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/041-kotlin-to-csharp-call-mechanism.md">ADR-041: Kotlin to C# call mechanism</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/055-forward-abi-contract-check.md">ADR-055: Forward ABI contract check</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/063-forward-declaration-level-export-scoping.md">ADR-063: Forward declaration-level export scoping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md">ADR-066: Forward export reachability closure</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/078-forward-abi-legacy-contract-coverage.md">ADR-078: Forward ABI legacy contract coverage</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/094-reflection-free-generic-dispatch.md">ADR-094: Reflection-free generic dispatch</a>
    </category>
</seealso>
