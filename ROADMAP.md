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
- [ ] Map nullable **parameters** (`fun f(name: String?)`) to a correctly-annotated C# signature. Today the generated `[DllImport]`/wrapper declares a non-nullable `string name` (or `int` for a nullable primitive), silently dropping the `?`; it works at runtime (a null pointer still marshals), but the generated API lies about its contract, so a consumer passing `null` gets `CS8625` under the warnings-as-errors `GeneratedBindingsCheck`. Discovered alongside [ADR-053](docs/adr/053-nullable-reference-types-in-kotlin.md); it is the exact mirror of that reverse-direction fix, applied forward

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
- [x] Gradle DSL: declare NuGet dependencies (`nuget { publish { ... } dependencies { ... } }`) with opt-in binding selection (`bind {}`) and namespace aliasing — resolve, but do not bind, the transitive closure by default (synthesis D4); breaking restructure moved publish config into `publish {}` (see [ADR-044](docs/adr/044-nuget-dependency-dsl.md))
  - [ ] Local path / `.nupkg` file source for a dependency (dev-loop flow, synthesis D6)
  - [ ] Extension-level shared feed list (`sources { url("...") }`) — v1 supports per-dependency `source` only
  - [ ] Multiple `bind {}` blocks per dependency (distinct namespace groups with different `packageName` values)
- [x] Resolution pipeline: synthetic `.csproj` generation + `dotnet restore` (`nugetGen`/`nugetRestore` tasks), reusing `obj/project.assets.json` as the inter-task manifest; pin `<TargetFramework>` (net8.0) so restore fails fast (NU1202) on packages above the supported floor (see [ADR-045](docs/adr/045-nuget-resolution-pipeline.md))
- [x] Reverse IR: extract the bound packages' API surface (`nugetExtractApi`, reads `project.assets.json`, ADR-042 extraction) and model C# classes/interfaces/methods as Kotlin declarations (mirror of CIR) (see [ADR-046](docs/adr/046-reverse-ir-model-and-json-contract.md))
  - [x] RIR model + `parseReverseIr()` JSON deserialization (`RirFile`/`RirAssembly`/`RirNamespace`/`RirType`/`RirTypeRef`, `kotlinx.serialization`, `"kind"` discriminator)
  - [x] `deriveDllPaths()` — resolve absolute managed `.dll` paths from `project.assets.json` (ADR-045 contract)
  - [x] `NugetExtractApiTask` + `NugetPlugin` wiring (registered only when a dependency has a `bind {}` block; `nugetImport` `dependsOn`)
  - [x] `nuget-metadata-reader/` C# console app — `MetadataReader` extraction + ADR-043 subset filter + `reverse-ir.json` emission
  - [x] Wire the task action to the metadata reader subprocess (end-to-end extraction; dotnet-gated integration test against `Newtonsoft.Json`)
  - [x] Per-package namespace include/exclude at the reader CLI — filters emitted inline per `--package` triple (stateful CLI parsing), fixing the cross-package flattening bug (see [ADR-047](docs/adr/047-per-package-namespace-filters-at-reader-cli.md))
- [x] Generate Kotlin-idiomatic stubs for the C# API surface (v1: static methods, primitives, strings, `void`) — `object`-per-type stubs, two-file split, `nativeMain` wiring; model the managed API once, not per Kotlin target; only native payloads vary by the Kotlin-target ↔ RID mapping (see [ADR-048](docs/adr/048-kotlin-stub-generation-from-reverse-ir.md))
- [x] Generate C#-side registration shims — `[UnmanagedCallersOnly]` thunks + `[ModuleInitializer]` startup registration handing function pointers to Kotlin (`nugetGenerateShims`); Kotlin stubs fail fast if the table is not registered (contract fixed by [ADR-048](docs/adr/048-kotlin-stub-generation-from-reverse-ir.md): export name `nuget_{ns}_{type}_register`, one `COpaquePointer` per method in `reverse-ir.json` order, `Marshal.StringToCoTaskMemUTF8` for string returns; C# generation design + let-it-crash v1 exception behaviour in [ADR-049](docs/adr/049-csharp-registration-shim-generation.md))
- [x] **Phase goal:** consume a bound NuGet package (`MimeMapping` — `Newtonsoft.Json` can't cross the v1 subset, every static method is an overload set) from Kotlin in `sample-library` and exercise it end-to-end from `sample-app` (`SampleApp.Tests` round-trip via a single local-feed `PackageReference`: real `.nupkg` + `contentFiles` shim merge + exact-resolved-version `<dependencies>` + single-`afterEvaluate` bridging) (see [ADR-050](docs/adr/050-end-to-end-packaging-integration.md))
  - [x] Umbrella `nugetImport` IDE-sync task aggregating resolve + binding generation (mirror of `podImport`)
  - [ ] Tooling UX: detect `dotnet` on PATH (or `local.properties` override) with explicit install guidance; self-heal retry on transient feed failures (mirror of CocoaPods `pod install --repo-update`) — deferred to a follow-up
  - [ ] Surface `RirDiagnostic` to the build. [ADR-043](docs/adr/043-bridgeable-subset-boundary.md)'s premise is "fail fast with diagnostics naming each skipped member and why". Partly done as of ADR-053: `NugetGenerateBindingsTask` now has a single `diagnosticWarnings(rir)` function that warns over every `RirAssembly.diagnostics` entry (all reader-emitted kinds: overload sets, `ref struct`, open generics, `dynamic`, unbound type references, `info_async_not_yet_mapped`, and ADR-053's `info_oblivious_nullability`) plus the Gradle-plugin-derived member-name-collision diagnostics, through one shared formatter, generalizing what used to be a one-off collision-only warn path. Remaining: this is a Gradle build-log warning only, not a structured/queryable report, and there is still no dedicated test asserting every `RirDiagnosticKind` reaches the log
Post-goal expansion of the bridgeable subset is broken out into Phases 9–13 below, mirroring the forward-direction arc (Phase 3 → 7): basic types, rich types, exceptions, async, then bidirectional contracts.

## Phase 9: Reverse basic type support — C# objects in Kotlin

Mirror of Phase 3. Moves the reverse bridge beyond v1 static methods: C# objects become Kotlin classes backed by opaque handles, over the same ADR-041 registration table. All constructs here are already present in `reverse-ir.json` (ADR-046) or are small extraction additions — this phase is primarily stub-gen + shim-gen coverage, relaxing the ADR-043 v1 ceiling construct by construct.

- [x] Map C# objects as opaque handles in Kotlin (`GCHandle.Normal` → `COpaquePointer`, mirror of `StableRef` / ADR-003; new wrapper per crossing, no identity caching, mirror of ADR-005) (see [ADR-051](docs/adr/051-csharp-objects-as-opaque-handles.md))
  - [x] Lifetime cleanup: `Cleaner`-primary + explicit idempotent `close()` (`kotlin.AutoCloseable`) → shared `nuget_runtime_register` free export — deliberate inversion of the forward `IDisposable`-first choice (see [ADR-051](docs/adr/051-csharp-objects-as-opaque-handles.md))
- [x] Map instance constructors (`new Foo(...)` → Kotlin secondary `constructor` delegating through a bridge helper; v1 supports a single public instance `.ctor`, multiple are an overload set deferred to line 156) (see [ADR-052](docs/adr/052-csharp-instance-constructors-in-kotlin.md))
- [x] Map instance methods and instance properties (v1-mappable parameter/return types first). No new ADR; a confirmed mirror of [ADR-051](docs/adr/051-csharp-objects-as-opaque-handles.md)'s stated principle that an instance thunk is a static thunk whose first parameter is the receiver handle. Read-only property → `val`, settable → `var` with bridge-backed `get()`/`set()`. Registration slot order is ctor → static methods → instance methods → per-property getter then setter
  - [x] Instance members whose Kotlin name would collide with the [ADR-051](docs/adr/051-csharp-objects-as-opaque-handles.md) wrapper's own `handle` / `close` / `cleaner` are skipped with a warning; statics are unaffected (they live in the `companion object`)
- [x] Map static C# properties (getter/setter, v1-mappable types) — straightforward extension of the same function-pointer pattern (see [ADR-048](docs/adr/048-kotlin-stub-generation-from-reverse-ir.md)); static classes expose them on a Kotlin `object`, non-static classes expose them on the `companion object`
- [x] Map C# enums → Kotlin `enum class` (v1: public top-level, default-`int`, non-`[Flags]`, unique contiguous `0..N-1` values; mirror of [ADR-006](docs/adr/006-enum-mapping.md))
- [x] Map nullable reference type annotations → Kotlin `T?` (`NullableAttribute`/`NullableContextAttribute` in metadata; oblivious/un-annotated legacy assemblies bind non-null with a fail-fast bridge guard, plus an `info_oblivious_nullability` diagnostic) (see [ADR-053](docs/adr/053-nullable-reference-types-in-kotlin.md))
  - [x] Setter for handle-typed properties: a C# `Foo Bar { get; set; }` now renders as a Kotlin `var`, because a property carries exactly one `NullableAttribute` so getter and setter agree on one type. Unblocked by ADR-053
  - [ ] `Nullable<T>` value types (`int?`) are a different, deferred feature (ADR-053 Decision 3): no `NullableAttribute` machinery applies, and it needs its own wire-format decision (out-parameter vs. two-call vs. packed struct)
- [ ] Map C# structs (value types) — blittable pass-by-value vs boxed handle (needs ADR, mirror of ADR-014)
- [ ] Overload sets: revisit the ADR-043 exclusion with a deterministic disambiguation scheme — Kotlin *has* overloading, so the ceiling is only export-symbol uniqueness (mirror of ADR-034's collision rule, inverted)

## Phase 10: Reverse rich type support

Mirror of Phase 4: generics, collections, delegates, and the C#-specific surface sugar that has a direct Kotlin idiom.

- [ ] Map closed constructed generics (`List<int>`, `Dictionary<string, T>` at concrete use sites) — the reverse of type-erased-bridge + typed variants (mirror of ADR-010; needs ADR — open generic *types* stay excluded per ADR-043)
- [ ] Map C# collections → Kotlin collections (`IReadOnlyList<T>` → `List<T>`, `IDictionary<K,V>` → `MutableMap<K,V>`, eager copy; mirror of ADR-011)
- [ ] Map delegate parameters (`Func<>` / `Action<>` / custom delegates) → Kotlin function types (builds directly on the ADR-036 reverse machinery, direction inverted)
- [ ] Map default parameter values → Kotlin default arguments (constants are in metadata)
- [ ] Map C# extension methods → Kotlin extension functions
- [ ] Map indexers → Kotlin `operator fun get`/`set`
- [ ] Map operator overloads → Kotlin operator functions (where a Kotlin operator exists; skip + warn otherwise per ADR-043 diagnostics)
- [ ] Map `params` arrays → `vararg`
- [ ] `ref` / `out` parameters — decide mapping (multi-value return data class) or document as permanently excluded (needs ADR)

## Phase 11: Reverse exception handling

Mirror of Phase 5. Any managed call can throw; until this phase, an escaping C# exception at an `[UnmanagedCallersOnly]` boundary is fatal — so this lands before async widens the call surface.

- [ ] Catch managed exceptions in registration thunks and propagate across the ABI as Kotlin exceptions (mirror of ADR-023/024)
- [ ] Map core .NET exceptions to Kotlin analogs (`ArgumentException` → `IllegalArgumentException` etc. — ADR-029's table, reversed)
- [ ] Propagate the .NET stack trace on the Kotlin exception (mirror of ADR-027)
- [ ] Map `InnerException` → Kotlin `cause` chain (mirror of ADR-028)
- [ ] Propagate property accessor and constructor exceptions (mirror of ADR-030/031)

## Phase 12: Reverse async support

Mirror of Phase 6.

- [ ] Map `Task` / `Task<T>` → `suspend fun` (mirror of ADR-019; completion callback over the ABI, no CLR hosting needed per ADR-041)
- [ ] Wire coroutine cancellation → `CancellationToken` (mirror of ADR-022, direction inverted)
- [ ] Map `IAsyncEnumerable<T>` → `Flow<T>` (mirror of ADR-026)
- [ ] Map C# events → Kotlin (`Flow<T>` or listener + `Cleaner`-scoped subscription; no forward-direction mirror exists — needs ADR, builds on ADR-037 stored-callback machinery)

## Phase 13: Reverse bidirectional — implementing C# contracts in Kotlin

Mirror of Phase 7, composed with its machinery.

- [ ] Implement a C#-defined interface in Kotlin and pass it back to C# (composes with ADR-039 interface bridging + ADR-040; synthesis D5)
- [ ] Pass Kotlin lambdas where a C# API stores the delegate (lifetime beyond the call — mirror of ADR-037)
- [ ] Kotlin subclassing C# **classes** — explicitly deferred, revisit only with a concrete use case (synthesis D5, Swift-export precedent)

## Launch

The v0.1.0 launch checklist lives in [MVP.md](MVP.md): forward direction stable, reverse direction as a labelled preview, published to the Gradle Plugin Portal and mavenCentral.

Items deferred out of the MVP:

- [ ] Add KDoc comments in generated C# for better IDE support (e.g., from Kotlin source KDoc or signatures)
- [ ] Add Writerside documentation
- [ ] Add a better sample app that demonstrates more complex usage of the bridge (e.g., using generics, collections, async features)
- [ ] Local-feed dev loop: let a C# consumer iterate against a locally built `.nupkg` (local NuGet feed / `ProjectReference`) before publishing (KMMBridge-style local-vs-published dual flow, synthesis D6)
- [ ] Publish to GitHub Packages as a secondary registry, mirroring KStore. Mechanically straightforward: a Gradle plugin is just the plugin jar plus its marker, and `maven-publish` targets `maven.pkg.github.com` like any other Maven repo. The catch is that [GitHub Packages requires an access token even to read public packages](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry), and plugin resolution happens in `pluginManagement` before anything else. So a consumer would need credentials in `settings.gradle.kts` just to see the plugin, which is precisely the friction the Plugin Portal exists to remove. Worth it only as a mirror alongside the primary registries, never as the sole channel

## Tooling & Test Integrity

Fallout from [ADR-053](docs/adr/053-nullable-reference-types-in-kotlin.md) (reverse nullability), the first fixture to exercise the reverse bridge realistically. It flushed out four latent defects, **two of which were phantoms of stale build state**, and the debugging cost was dominated by having no way to observe the bridge and no way to trust the build. These items exist so the next feature does not pay that cost again.

- [x] **Reverse-bridge registration observability: always-on self-check first, trace second.** The [ADR-041](docs/adr/041-kotlin-to-csharp-call-mechanism.md) function-pointer table was unobservable: nothing could report which `nuget_{ns}_{type}_register` exports actually fired, so "did registration work?" was only answerable by bisecting. The research found that an opt-in trace, the original framing of this item, is the weaker half of the fix: nobody enables a trace for a bug they don't already suspect, and in the ADR-053 hunt nobody suspected registration. What would actually have caught it is **always-on**, no env var required: every register export now carries a registration-time contract self-check (`slotCount: Int` and `contractHash: Long`, amending [ADR-048](docs/adr/048-kotlin-stub-generation-from-reverse-ir.md)'s and [ADR-049](docs/adr/049-csharp-registration-shim-generation.md)'s registration contract), which refuses to store any pointer and fails loud, naming both sides, the instant a stale C# shim and a freshly built native library disagree; and a generated `NugetRegistry` that turns the old constant "bindings are not registered" message into a computed "N of M registrations fired", naming exactly which types are missing so a whole-assembly problem (nothing fired) is never confused with a single-type problem (everything but one fired). The opt-in `NUGET_INTEROP_TRACE=1` / `NUGET_INTEROP_TRACEFILE=<path>` trace (stderr by default, JNI/`LD_DEBUG`/`DYLD_PRINT_BINDINGS` precedent) ships alongside as the cheap complement for the case a consumer already suspects registration and needs ordering detail the two always-on checks can't express (see [ADR-054](docs/adr/054-reverse-bridge-registration-observability.md))
- [x] **Put the ranked remediation guidance into `NugetRegistry.notRegistered()`.** [ADR-054](docs/adr/054-reverse-bridge-registration-observability.md) now makes a zero-registration failure rank the likely causes: stale consumer build state, a missing package reference, then an unloaded shim assembly. It names the stale `obj/project.assets.json` failure mode and cleanup, while a partial result instead limits diagnosis to the missing `{Type}Registration.cs` shim or its `[ModuleInitializer]`.
- [ ] **Stop pinning the fixture packages at `1.0.0`. This is the root fix, do it first.** Every staleness bug in the ADR-053 hunt traces back to one decision: `SampleLibrary` and `SampleDependency` keep version `1.0.0` across every build, so NuGet's cache key never changes and a rebuilt package with different contents silently resolves to the old one. Everything else we have done about this (purge `~/.nuget/packages/`, wipe the consumers' `obj`/`bin`, ban hand-editing the cache) is **mitigation**: it treats the symptom and depends on every agent and every human remembering to do it, which is exactly the bet that lost today. Stamp a content-hash or build-timestamp version in `packNuget` (`1.0.0-{hash}`) and have the consumers resolve that version, and a stale cache becomes **structurally impossible**: NuGet cannot hand back a package that was never built. Both phantom bugs we chased could not have existed. Prefer this over hardening the purge
- [ ] **Forward-direction ABI contract check (mirror of [ADR-054](docs/adr/054-reverse-bridge-registration-observability.md)).** Nothing verifies that a generated C# `[DllImport]` and the Kotlin `@CName` export it targets agree on their signature. That is not hypothetical: `translateNullableFunction` declared **one fewer parameter** than the export expected, so the Kotlin side wrote through an unpopulated `errorOut` register and produced a `SIGBUS`, meaning [ADR-024](docs/adr/024-sync-exception-propagation.md)'s exception propagation had silently never worked for any nullable-returning export. ADR-054 just built exactly this guard for the reverse direction and proved it catches the mismatch cleanly (an old shim calling a new export fails with a named `IllegalStateException`, not a memory fault). The forward direction has the identical silent-mismatch class and no guard at all. Either mirror the registration-time self-check, or add a generation-time assertion that every `CirDllImport`'s parameter list matches the `FunctionExports` signature it binds to, which is cheaper and catches it at build time
- [ ] **Compile the generated Kotlin in the plugin unit tests.** `NugetGenerateBindingsTaskTest` and `NugetGenerateShimsTaskTest` assert on generated text with substring matching, so they cannot see whether the text they approve is *valid source*. The ADR-054 walking skeleton emitted literal backslashes into `NugetRegistry.kt` (backslash-escaped quotes inside a Kotlin raw string) and every unit test passed; only actually compiling the output caught it. The forward direction already has `GeneratedBindingsCheck`, which compiles the generated C# with warnings as errors. The reverse direction has no equivalent for the generated Kotlin. Add one, so a generator that emits syntactically broken source fails in seconds rather than at the end of a full round trip
- [ ] **An adversarial reverse fixture.** Every reverse fixture before `Sample.Nullability` had exactly **one bound class per namespace**, and no exported nullable-returning function ever threw. That is why three latent bugs (the metadata reader's positional parameter lookup, the colliding top-level registration vars, the missing `errorOut` on the forward two-call P/Invoke) sat green in CI. Add a fixture namespace that deliberately combines the hard cases: 3+ bound classes in one namespace, cross-referencing handle types, two types sharing a method name, a nullable return that throws, an oblivious (`#nullable disable`) island, and a settable handle-typed property. The suite was greener than the pipeline; this is the fix
- [ ] **`scripts/verify.sh --fast`.** The full verify is slow enough that agents route around it, hand-patching generated output or the NuGet cache, which manufactures phantom bugs. A ban only holds if the sanctioned path is not the slow path. Add a mode that skips untouched targets while keeping the cache purge and the `obj`/`bin` wipe correct, so the safe path is also the fast one
- [ ] **Harness guardrails in `.claude/settings.json`.** Two rules in [AGENTS.md](AGENTS.md) are honour-system today, and both were violated in the very session that wrote them. A `PreToolUse` hook should refuse `Edit`/`Write` into `~/.nuget/packages/**` and generated `**/build/**` output; a `Stop` hook should kill a stray `gradlew` / `GradleWorkerMain` when an agent finishes (an orphaned build held the project lock for 25 minutes and silently starved the next agent)
- [ ] **Let the `research` agent verify what it asserts.** [ADR-053](docs/adr/053-nullable-reference-types-in-kotlin.md) stated that `NullableAttribute`'s constructor is always a `MethodDefinitionHandle`. On net8.0 it is a `MemberReferenceHandle`, so an implementing agent followed the ADR literally and silently decoded every annotation as oblivious. The agent cannot execute anything, so it cannot check a claim about a real assembly, yet it writes authoritative design docs. Either give it a way to run a spike, or make validating every claim it labelled "inferred" the walking skeleton's first job

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
- Reverse-bridge integration tests against real published NuGet packages (not just the controlled `sample-dependency` fixture) — chosen precisely because they *don't* fit the bridgeable subset cleanly, to prove the ADR-043 skip diagnostics and partial-binding behaviour on messy real-world surfaces (mixed bridgeable/unbridgeable members, legacy nullability, multi-TFM)
- Map KDoc annotations to C# XML docs for better IDE support
- Expose Kotlin `Job` as a mapped C# type so cancellation can be tied to the job directly (e.g., `job.Cancel()`) instead of requiring a pre-created `CancellationTokenSource`
- NativeAOT compatibility for the generated bindings (see [ADR-038](docs/adr/038-aot-compilation.md)) — blocked on a reflection-free generics dispatch path; `[DllImport]` → `[LibraryImport]`; AOT publish smoke test in CI
- Centralize version-sensitive C# tokens into a `CSharpProfile` so the output dialect (interop attribute, native-int type, generic dispatch strategy) is a generation-time choice rather than hardcoded across the renderers — the enabler for AOT and any future C# dialect (see [ADR-038](docs/adr/038-aot-compilation.md))
