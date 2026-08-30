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

<note>
    <p>
        Naming a top-level exported declaration (an <code>object</code>, a class, a static-class
        file) the same as its own package's last segment produces a C# namespace whose simple name
        matches a type inside it, for example a package <code>com.example.parlour</code> alongside
        an object named <code>Parlour</code>. C# resolves an unqualified reference in that shape
        ambiguously (<code>CS0118</code>, "is a namespace but is used like a type") unless the
        consumer fully qualifies it. Pick a name that doesn't repeat the containing package.
    </p>
</note>

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

A `List`/`Map`/`Set` parameter with an unsupported element/key/value type (see
[Collections](collections.md)) is skipped like this, naming the component that failed rather than the
collection kind:

```
[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping Patient.setMoods: its COLLECTION type combination is not
    supported. the value type Collection? cannot be written into a Kotlin collection; use components
    that are primitives, Char, String, enums, exported class handles, value classes over those, or
    non-null nested collections of the same
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

A related but distinct case: the reachability closure never walks a class's supertypes at all, so
an exported class implementing an interface declared outside the export set, the Koin
`KoinComponent` shape, used to render a base-list entry ("`: IKoinComponent`") that nothing ever
generates. `Issue42Api : Issue42Component` is the fixture: `Issue42Component` lives in a separate
Gradle module the export set never admits.

```
[nuget:SKIPPED_UNEXPORTED_SUPERTYPE] Skipping Issue42Api : Issue42Component: supertype
    'dev.other.core.Issue42Component' is not in the export set, so it has no generated C#
    interface; the class is generated without it and its own members still export. an unexported
    supertype carries no members the C# side could call, so nothing is lost; note that
    include("...") does not help here — the export reachability closure never walks supertypes
    at Issue42Api.kt:15
```

Unlike `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, the hint does not point at `include(...)`: the closure
has no edge for supertypes, so admitting the dependency package changes nothing. The interface is
dropped from the generated base list; the class's own members, and any defaulted member the
interface declares, still export:

```C#
public class Issue42Api : IDisposable, INugetHandle
{
    /* ... */
    public string ComponentTag()  // Issue42Component's defaulted method, still bound on the class
    {
        /* ... */
    }
}
```

An unexported **base class** (`class X : UnexportedBase()`) dangles the same way and is not yet
covered by this diagnostic; see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).

### Where these messages appear

A diagnostic computed at generation time is only useful if it reaches the console. The processor
writes every accumulated diagnostic to `NugetDiagnostics.json`, a declared KSP task output
([ADR-100](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/100-forward-diagnostic-delivery.md)).
Being a declared output, not just something printed during the task action, is what makes it survive
an incremental build: it is *restored* on a cache hit and present after an `UP-TO-DATE` run, the two
outcomes a normal, unchanged `packNuget` produces on every run after the first. A
`NugetReportDiagnosticsTask` ahead of `packNuget` reads that file and re-emits every message through
Gradle's own warning logger, so a skip is visible on every `packNuget`, cached or not, not only the
build where KSP happened to run. This is a real `packNuget --console=plain` run against this
repository's own fixture, KSP task `UP-TO-DATE`:

```
> Task :test-library:kspKotlinMacosArm64 UP-TO-DATE

> Task :test-library:nugetReportDiagnostics
[nuget:INFO_EXPORTED_FROM_DEPENDENCY] Note TestLibraryNative: the export closure admitted 6 type(s) from dependency modules: io.github.xxfast.kotlin.native.nuget.test.models.Byline, io.github.xxfast.kotlin.native.nuget.test.models.Purr, io.github.xxfast.kotlin.native.nuget.test.models.StoryCode, io.github.xxfast.kotlin.native.nuget.test.models.StoryUri, io.github.xxfast.kotlin.native.nuget.test.models.TopStory, io.github.xxfast.kotlin.native.nuget.test.models.Whisker. these are generated exactly like module-local types; narrow with exclude(...) if any of them should not be part of the public API
[nuget:SKIPPED_UNEXPORTED_DEPENDENCY_TYPE] Skipping io.github.xxfast.kotlin.native.nuget.test.Newsroom.sponsor: its UNEXPORTED_DEPENDENCY_TYPE type combination is not supported. add include("dev.other.core") to nuget { publish { } }, or expose a type from an in-scope package instead
    at /Users/xxfast/Developer/XXFAST/KMP/kotlin-native-nuget/test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/Newsroom.kt:63
[nuget:SKIPPED_INHERITED_MEMBER] Skipping io.github.xxfast.kotlin.native.nuget.test.models.StoryUri.length: its INHERITED_MEMBER type combination is not supported. declare the member directly on the value class itself instead of relying on interface delegation
[nuget:SKIPPED_INHERITED_MEMBER] Skipping io.github.xxfast.kotlin.native.nuget.test.models.StoryUri.get: its INHERITED_MEMBER type combination is not supported. declare the member directly on the value class itself instead of relying on interface delegation
[nuget:SKIPPED_INHERITED_MEMBER] Skipping io.github.xxfast.kotlin.native.nuget.test.models.StoryUri.subSequence: its INHERITED_MEMBER type combination is not supported. declare the member directly on the value class itself instead of relying on interface delegation
[nuget:SKIPPED_UNSUPPORTED_PROPERTY] Skipping io.github.xxfast.kotlin.native.nuget.test.cat.Cat.unsupported: its type generic declaration kotlin.sequences.Sequence has no property getter or setter shape. expose a bridgeable property (or a getter function) whose type is not generic declaration kotlin.sequences.Sequence, and export that instead
    at /Users/xxfast/Developer/XXFAST/KMP/kotlin-native-nuget/test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/Cat.kt:46
```

<note>
<p>Before ADR-100, this exact set of six diagnostics was computed correctly but reached nobody: the
KSP stdout channel never surfaced in the Gradle console (a Worker API stdout-attribution gap), and
even when it did, a normal, unchanged <code>packNuget</code> reports the KSP task
<code>FROM-CACHE</code> then <code>UP-TO-DATE</code> on consecutive runs, so a transport that only
speaks during a task action was silent on every build after the first. Nothing about which
declarations are skipped, or their severity, changed; only delivery did.</p>
</note>

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

The generated bindings are AOT-safe.

The generics bridge's reflection, the first blocker
([ADR-038](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/038-aot-compilation.md), Deferred),
is fixed: [ADR-094](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/094-reflection-free-generic-dispatch.md)
(Accepted) replaced the type-erased generics bridge's `GetField`/`Activator.CreateInstance`
reflection with a static factory registry. A build against a pre-ADR-094 version of these bindings
(plugin 0.2.0) could still trigger the .NET trimmer's `IL2075` on that `GetField`, mitigated with
`<TrimmerRootAssembly>` on the project compiling `Interop.cs`; on the current generator this should
no longer fire for that reason.

The second blocker, the forward callback surface, is fixed too
([ADR-102](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md),
Accepted). Every place C# calls back into Kotlin, a per-call lambda parameter, a stored callback, a
C#-implemented interface bridge, `Flow`/`StateFlow` collection, and `suspend` continuation
resumption, used to hand Kotlin a pointer obtained from `Marshal.GetFunctionPointerForDelegate`,
which needs the runtime to JIT a native-to-managed thunk the first time it is invoked from native
code. A fully AOT-compiled runtime has none, which is what threw `ExecutionEngineException` on a
Mono full-AOT Mac Catalyst build the first time a generated `Flow` was collected. The generated C#
now dispatches every one of those shapes through a static `[UnmanagedCallersOnly]` thunk (one per
delegate shape, `NugetThunks`), keyed off a `GCHandle` ctx pointer every callback ABI already
threaded through unused. Kotlin needs no change:

```C#
internal static unsafe partial class NugetThunks
{
    [UnmanagedCallersOnly(CallConvs = new[] { typeof(global::System.Runtime.CompilerServices.CallConvCdecl) })]
    internal static IntPtr NugetStringStringCallbackThunk(IntPtr a0, IntPtr a1)
    {
        try
        {
            return ((NugetStringStringCallback)GCHandle.FromIntPtr(a1).Target!)(a0, a1);
        }
        catch (Exception ex)
        {
            Environment.FailFast("nuget: unhandled exception in NugetStringStringCallback", ex);
            return default;
        }
    }

    internal static IntPtr NugetStringStringCallbackPtr =>
        (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr>)&NugetStringStringCallbackThunk;
}
```

<note>
<p>An exception escaping a user callback (a per-call lambda, a bridge slot implementation) inside
one of these thunks is process-fatal by design: every thunk body catches and calls
<code>Environment.FailFast</code> rather than letting the exception tear through the native frame
undefined. This matches the de-facto behaviour before this change. A real error-channel ABI
(out-parameters on callback signatures, Kotlin-side rethrow) is future work, see
<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md">ROADMAP.md</a>.</p>
</note>

<note>
<p>CoreCLR NativeAOT (<code>PublishAot=true</code>) was measured working on <code>win-x64</code>
even before this change: ILC pre-generates reverse-P/Invoke marshalling stubs for delegate types it
can root statically, so NativeAOT consumers were never actually broken, only resting on an
undocumented ILC implementation detail rather than the documented
<code>[UnmanagedCallersOnly]</code> contract. This change moves the generated code onto that
contract. <code>AotSmokeTest/</code> publishes and runs all five callback shapes under
<code>PublishAot=true</code> as a permanent CI regression lane: <code>win-x64</code> is verified
locally through the new thunks, <code>osx-arm64</code> is a CI-only lane executing for the first
time in CI.</p>
<p>The Mono full-AOT failure (Catalyst/iOS Release) is what this change actually fixes.
<code>&lt;MtouchInterpreter&gt;-all&lt;/MtouchInterpreter&gt;</code> is expected to no longer be
required on Catalyst/iOS Release builds, but the original Catalyst repro has not yet been re-run
without the flag to confirm the fix end to end on that runtime; keep the flag set until that manual
re-verification lands, tracked in
<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md">ROADMAP.md</a>.</p>
</note>

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
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/100-forward-diagnostic-delivery.md">ADR-100: Forward diagnostic delivery</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/101-unexported-supertype-skip.md">ADR-101: Forward, unexported supertype skip</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md">ADR-102: AOT-safe forward callbacks</a>
    </category>
</seealso>
