# Roadmap

## Phase 1: Basic bridging
- [x] Gradle plugin structure with `includeBuild`
- [x] Link shared libraries for multiple targets (mingwX64, macosArm64)
- [x] Package native libs into NuGet layout (`runtimes/{rid}/native/`)
- [x] Generate P/Invoke bindings via ClangSharpPInvokeGenerator
- [x] Move ClangSharp invocation to a `.targets` file shipped inside the NuGet package (see [ADR-001](docs/adr/001-csharp-codegen-in-consumer.md))
- [x] Ship ClangSharp native libs as a package dependency (eliminate Gradle-side ProcessBuilder)

## Phase 2: KSP-driven generation
- [x] KSP processor that discovers all public declarations with full type info
- [x] Auto-generate `@CName` wrappers (no manual annotations needed)
- [x] Emit `Interop.cs` directly from KSP (pre-generated, no consumer-side tooling)
- [x] Remove ClangSharp dependency and `.targets` generation step
- [x] Map primitive types (Byte, Short, Int, Long, Float, Double + unsigned variants)
- [x] Map nullable primitives (see [ADR-002](docs/adr/002-nullable-two-call-pattern.md))
- [x] Map nullable strings

## Phase 3: Basic type support
- [x] Map Kotlin packages to C# namespaces (user-configurable root, sub-packages mapped relative to it)
- [x] Research memory management on the bridge (see [ADR-003](docs/adr/003-memory-management-across-bridge.md))
- [x] Map String parameters (C# `string` → Kotlin `String` via P/Invoke marshalling)
- [x] Map String returns as proper `string` (hidden `IntPtr` + `Marshal.PtrToStringUTF8`)
- [x] Map classes → C# classes with `IDisposable`, StableRef + opaque pointer
- [x] Refactor to CIR (C# Intermediate Representation) model (see [ADR-004](docs/adr/004-cir-intermediate-representation.md))
- [x] Map object-typed properties/returns (see [ADR-005](docs/adr/005-object-return-semantics.md))
- [x] Test cyclic reference disposal (verified wrappers are independent, dispose doesn't cascade)
- [x] Map member setters
- [x] Map enums (see [ADR-006](docs/adr/006-enum-mapping.md))
- [x] Map per-file top-level function class naming (see [ADR-007](docs/adr/007-top-level-function-class-naming.md))
- [x] Map data classes (see [ADR-008](docs/adr/008-data-class-mapping.md))
- [x] Map interfaces (C# `interface` with `I` prefix, default methods delegate to Kotlin)
- [x] Map abstract classes (C# `abstract class`, `_handle` inherited by subclasses)
- [x] Map sealed classes (see [ADR-009](docs/adr/009-sealed-class-mapping.md))
- [x] Map object (→ static class) / data object in sealed classes (→ sealed subclass with ToString)

## Phase 4: Rich type support
- [x] Map Generics (see [ADR-010](docs/adr/010-generics-mapping.md))
  - [x] Generic classes with type-erased bridge + generic C# class
  - [x] NugetMarshal helper for type dispatch
  - [x] Primitive type argument variants (Int, String, etc.)
- [x] Generic class constructors (pass typed arguments through the bridge)
- [x] Map Generic functions (typed variants + runtime dispatch)
- [x] Map Collections types (see [ADR-011](docs/adr/011-collection-type-mapping.md))
  - [x] `List<T>` → `IReadOnlyList<T>` (opaque handle + count, get)
  - [x] `MutableList<T>` → `IList<T>` (add, removeAt, set)
  - [x] `Map<K,V>` → `IReadOnlyDictionary<K,V>` (count, get, containsKey, keys)
  - [x] `MutableMap<K,V>` → `IDictionary<K,V>` (put, remove)
  - [x] `Set<T>` → `IReadOnlySet<T>` (count, contains)
  - [x] `MutableSet<T>` → `ISet<T>` (add, remove)
- [x] Map lambda/function types — Kotlin → C# only (see [ADR-012](docs/adr/012-lambda-function-type-mapping.md))
- [x] Map nullable primitive properties (class properties)
- [x] Map nullable object properties (class properties)
- [x] Map top-level properties (get/set on per-file static classes, including nullable support)
- [x] Map const values
- [x] Map companion objects
- [x] Map extension functions
- [x] Map extension properties (see [ADR-013](docs/adr/013-extension-property-mapping.md))
- [x] Map inline classes (value classes) (see [ADR-014](docs/adr/014-value-class-mapping.md))
- [x] Map inline classes wrapping reference types (value class with object underlying type)
- [x] Map generics with type constraints (see [ADR-015](docs/adr/015-generic-type-constraint-mapping.md))
- [x] Map generics with variance (`out T`, `in T`) (see [ADR-016](docs/adr/016-generic-variance-mapping.md))
- [x] Map inline functions (e.g., `inline fun f()`) (see [ADR-017](docs/adr/017-inline-function-mapping.md))
- [x] Map inline functions with reified type parameters (e.g., `inline fun <reified T> f()`)
- [x] Map generic type aliases (see [ADR-018](docs/adr/018-type-alias-mapping.md))

## Phase 5: Exception handling
- [x] Map exception propagation across the bridge (see [ADR-023](docs/adr/023-exception-propagation.md))
- [x] Map synchronous exception propagation (see [ADR-024](docs/adr/024-sync-exception-propagation.md))
- [x] Propagate Kotlin stack trace as `KotlinException.KotlinStackTrace` property (see [ADR-027](docs/adr/027-stacktrace-propagation.md))
- [x] Map exception cause chain (`e.cause` → `InnerException`) (see [ADR-028](docs/adr/028-exception-cause-chain.md))
- [x] Map core Kotlin exceptions to .NET analogs (e.g., `IllegalArgumentException` → `ArgumentException`) with `IKotlinException` interface (see [ADR-029](docs/adr/029-exception-type-mapping.md))
- [x] Map property getter/setter exception propagation (see [ADR-030](docs/adr/030-property-exception-propagation.md))
- [x] Map constructor exception propagation — primary constructors + data class `copy()` (see [ADR-031](docs/adr/031-constructor-exception-propagation.md))
  - [x] Generic class constructor variants (`create_*` typed variants + `create_object`) (see [ADR-032](docs/adr/032-generic-constructor-exception-propagation.md))
  - [x] Value class constructor exceptions — private `CreateChecked` helper keeps `new CatId(...)`, no static-factory redesign needed (see [ADR-033](docs/adr/033-value-class-constructor-exception-propagation.md))
  - [x] Secondary constructor exceptions — exported as `_create_N` overloads, fail-fast on C# signature collision (see [ADR-034](docs/adr/034-secondary-constructor-exceptions.md))
  - [x] Value class **primary**-constructor `init` validation — hand-written record struct routes every constructor through Kotlin; reference-underlying deferred (see [ADR-035](docs/adr/035-value-class-primary-constructor-validation.md))

## Phase 6: Async support
- [x] Map Suspend functions (coroutines → Task/async) (see [ADR-019](docs/adr/019-suspend-function-mapping.md))
- [x] Map Suspend lambdas (`suspend () -> R` → `KotlinSuspendFunc<R>` / `Task<R>`) (see [ADR-020](docs/adr/020-suspend-lambda-mapping.md))
- [x] Support structured concurrency (see [ADR-021](docs/adr/021-structured-concurrency.md))
- [x] Map CancellationToken to coroutine cancellation (see [ADR-022](docs/adr/022-cancellation-token-support.md))
- [x] Support CancellationToken for suspend lambdas (`KotlinSuspendFunc<T>.InvokeAsync(CancellationToken)`)
- [x] Support `IAsyncDisposable` / graceful drain for in-flight async operations (see [ADR-025](docs/adr/025-async-disposable.md))
- [x] Map `Flow<T>` Flow APIs (cold streams → IAsyncEnumerable) (see [ADR-026](docs/adr/026-flow-mapping.md))
- [ ] Map `SharedFlow<T>` (hot stream with subscribers — may map to `IAsyncEnumerable<T>` with replay or `IObservable<T>`)
- [ ] Map `StateFlow<T>` (hot stream with always-current-value — may map to property + change notification)
- [ ] Map `suspend fun` returning `Flow<T>` (outer suspend rarely matters; treat as non-suspend returning Flow)
- [ ] Map nullable `Flow<T>?` (requires two-call nullable pattern from ADR-002 combined with flow export)
- [ ] Map `Flow<T>` as a function parameter (C#→Kotlin direction; requires Phase 7 bidirectional support)
- [ ] Map `Flow<T>` as a generic type argument (e.g., `Box<Flow<String>>`)
- [ ] Flow backpressure support (bounded `Channel<T>` with explicit resume signaling)
- [ ] Add `@ExperimentalNugetCoroutineApi` opt-in annotation and KSP warning for classes with suspend methods (see [ADR-021](docs/adr/021-structured-concurrency.md))

## Phase 7: Bidirectional support (C# → Kotlin)
- [x] Research calling C# from Kotlin/Native (reverse P/Invoke, function pointers) (see [ADR-036](docs/adr/036-reverse-interop-mechanism.md))
- [x] Map lambda/function types — C# → Kotlin: v1 lambda **parameters**, arity 0–1, per-call (delegate + GCHandle + `Marshal.GetFunctionPointerForDelegate`) (see [ADR-036](docs/adr/036-reverse-interop-mechanism.md), [ADR-012](docs/adr/012-lambda-function-type-mapping.md))
  - [x] Arity 2+ lambda parameters (`(T1, T2) -> R`)
  - [x] Stored callbacks (event handlers / observers) — `IDisposable` subscription + Kotlin-side `_unsubscribe` export (see [ADR-037](docs/adr/037-stored-callbacks.md))
  - [ ] Exception propagation from a C# callback into Kotlin (mirror of forward-direction ADR-024/028/029)
  - [ ] `Flow<T>` / suspend lambda (`suspend (T) -> R`) as a function parameter
- [x] Generate Kotlin wrappers for C# interfaces (callbacks, event handlers) — full interface bridging via N flat function pointers (see [ADR-039](docs/adr/039-interface-bridging.md))
- [ ] Support implementing C# interfaces in Kotlin and passing them back to C# consumers (see [ADR-040](docs/adr/040-interface-return-type-mapping.md))
  - [ ] Generate concrete handle-backed `sealed class Foo : IFoo` per Kotlin interface (`CirInterfaceClass`)
  - [ ] Generate Kotlin interface-dispatch exports (`InterfaceExports.kt`: per-property, per-method, `_dispose`)
  - [ ] Map interface-typed return positions to `IFoo` (`new Foo(handle)` construction, incl. nullable `Foo?` → `IFoo?`)
  - [ ] Sample API + tests: `Cat.friend: Pet?` / `Cat.befriend(pet: Pet)` (Kotlin-backed `IPet` via `_handle` extraction only)
- [ ] Support implementing Kotlin interfaces in C# and passing them to Kotlin producers (general, non-subscription interface parameters — needs ADR)
  - [ ] Interface methods with non-Unit returns — marshal the C# result back to Kotlin through the function pointer (deferred from ADR-039; `IPet.Speak(): string` needs this)
  - [ ] Interface properties as getter function pointers (deferred from ADR-039; `IPet.Name` needs this)
  - [ ] Runtime dispatch at interface parameter positions: Kotlin-backed wrapper (`_handle`) vs C#-implemented object (N-function-pointer bridge)
  - [ ] Stored lifetime without an `add*/remove*` pair — GCHandle release strategy (Kotlin-side free export / callback registry, ADR-036 Alt 4)
  - [ ] Round-trip identity: a stored C#-implemented object returned back to C# resolves to the original object instead of double-bridging

## Phase 8: Ecosystem — consuming NuGet packages from Kotlin

The inverse of everything above, modeled on the Kotlin CocoaPods plugin (`pod("...")` → cinterop → Kotlin externals): the Gradle plugin resolves a C# NuGet dependency, extracts its public API from the .NET assembly metadata, and generates Kotlin-idiomatic bindings so Kotlin code can call the C# library — and implement interfaces it defines. Prior-art research: [CocoaPods plugin architecture](docs/research/cocoapods-plugin-architecture.md), [SPM plugin architectures](docs/research/spm-plugins-architecture.md), and the [synthesis with proposed pipeline + candidate ADRs](docs/research/nuget-plugin-architecture-synthesis.md). Because the Kotlin/Native library always runs inside a .NET host process, Kotlin → C# calls need no CLR hosting: the generated C# side registers function-pointer thunks with Kotlin at startup. Builds on the Phase 7 reverse-interop machinery.

- [x] Research/ADR: Kotlin → managed C# call mechanism — init-time function-pointer registration table (`[ModuleInitializer]` + `[UnmanagedCallersOnly]` thunks, host process is always .NET); record standalone Kotlin hosts (CLR hosting via `hostfxr`) as explicitly out of scope for v1 (synthesis D3) (see [ADR-041](docs/adr/041-kotlin-to-csharp-call-mechanism.md))
- [x] Research/ADR: extracting the C# public API surface from .NET assembly metadata (ECMA-335) at Gradle build time — v1 leans on the .NET SDK (precedented by CocoaPods requiring `pod`); pure-JVM reader tracked under Future Improvements (synthesis D1) (see [ADR-042](docs/adr/042-assembly-metadata-extraction.md))
- [x] Research/ADR: the bridgeable-subset boundary (synthesis D2) — define which C# constructs cannot cross the C ABI (overload sets, `ref struct`/`Span<T>`, open generics, `dynamic`, default interface methods) and fail fast with diagnostics naming each skipped member and why (see [ADR-043](docs/adr/043-bridgeable-subset-boundary.md))
- [ ] Gradle DSL: declare NuGet dependencies (e.g., `nuget { dependencies { ... } }`) with opt-in binding selection and namespace/product aliasing — resolve, but do not bind, the transitive closure by default (synthesis D4)
- [ ] Resolution pipeline: synthetic `.csproj` generation + `dotnet restore` (`nugetGen`/`nugetRestore` tasks), reusing `obj/project.assets.json` as the inter-task manifest; pin `<TargetFramework>` so restore fails fast on packages above the supported floor
- [ ] Tooling UX: detect `dotnet` on PATH (or `local.properties` override) with explicit install guidance; self-heal retry on transient feed failures (mirror of CocoaPods `pod install --repo-update`)
- [ ] Reverse IR: model C# classes/interfaces/methods as Kotlin declarations (mirror of CIR)
- [ ] Generate Kotlin-idiomatic stubs for the C# API surface (v1: static methods, primitives, strings, `void`) — model the managed API once, not per Kotlin target; only native payloads vary by the Kotlin-target ↔ RID mapping
- [ ] Generate C#-side registration shims — thunks + startup registration handing function pointers to Kotlin; Kotlin stubs fail fast if the table is not registered
- [ ] Umbrella `nugetImport` IDE-sync task aggregating resolve + binding generation (mirror of `podImport`)
- [ ] Map C# objects as opaque handles in Kotlin (`GCHandle`, mirror of `StableRef`) with lifetime cleanup (Kotlin `Cleaner` → C#-side free export)
- [ ] Implement a C#-defined interface in Kotlin and pass it back to C# (composes with Phase 7 interface bridging); Kotlin subclassing C# **classes** is explicitly deferred (synthesis D5, Swift-export precedent)
- [ ] Map C# exceptions → Kotlin exceptions (mirror of ADR-023/029)
- [ ] Map `Task<T>` → `suspend fun` (mirror of ADR-019)
- [ ] Map C# collections and generics subsets (mirror of ADR-010/011)

## Pre-Launch Checklist 
- [ ] Pin `<LangVersion>` in the generated project so a consumer's newer SDK can't reinterpret generated code under a different language version
- [ ] Add a CI smoke test that compiles the generated `Interop.cs` against the target `LangVersion` (catches escaping / reserved-word / invalid-construct regressions in this repo instead of at consumer build time)
- [ ] Add KDoc comments in generated C# for better IDE support (e.g., from Kotlin source KDoc or signatures)
- [ ] Finalize README with usage instructions and API documentation
- [ ] Add Writerside documentation
- [ ] Add a better sample app that demonstrates more complex usage of the bridge (e.g., using generics, collections, async features)
- [ ] Local-feed dev loop: let a C# consumer iterate against a locally built `.nupkg` (local NuGet feed / `ProjectReference`) before publishing — KMMBridge-style local-vs-published dual flow (synthesis D6)
- [ ] Publish releases to GitHub Packages
- [ ] Publish releases to mavenCentral

## Future Improvements

- Support flat/unnested sealed class hierarchies (subclasses as top-level in same namespace)
- KSP incremental processing if build times become a concern on large libraries
- Map data classes to C# `record class` if a safe `with`-expression pattern can be found (see [ADR-008](docs/adr/008-data-class-mapping.md))
- Verify Kotlin GC actually frees objects after all StableRefs are disposed (requires Kotlin-side weak references + GC trigger — not feasible in standard unit tests)
- Memory leak detection tooling for bridged objects in CI
- Object identity preservation (caching wrappers) if profiling shows allocation overhead is significant
- Custom type mappers for dependency types (e.g., `kotlinx.datetime.Instant` → `DateTimeOffset`)
- Pure-JVM ECMA-335 metadata reader + NuGet v3 client, dropping the .NET SDK prerequisite from the Kotlin-side build (synthesis D1) — no plugin in this space runs prerequisite-free; a genuine ergonomic edge
- Hand-written C# shim escape hatch for API members outside the auto-bridgeable subset (spm4Kmp's bridge-folder model, synthesis D2)
- Map KDoc annotations to C# XML docs for better IDE support
- Expose Kotlin `Job` as a mapped C# type so cancellation can be tied to the job directly (e.g., `job.Cancel()`) instead of requiring a pre-created `CancellationTokenSource`
- NativeAOT compatibility for the generated bindings (see [ADR-038](docs/adr/038-aot-compilation.md)) — blocked on a reflection-free generics dispatch path; `[DllImport]` → `[LibraryImport]`; AOT publish smoke test in CI
- Centralize version-sensitive C# tokens into a `CSharpProfile` so the output dialect (interop attribute, native-int type, generic dispatch strategy) is a generation-time choice rather than hardcoded across the renderers — the enabler for AOT and any future C# dialect (see [ADR-038](docs/adr/038-aot-compilation.md))
