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

## Phase 5: Async support
- [x] Map Suspend functions (coroutines → Task/async) (see [ADR-019](docs/adr/019-suspend-function-mapping.md))
- [x] Map Suspend lambdas (`suspend () -> R` → `KotlinSuspendFunc<R>` / `Task<R>`) (see [ADR-020](docs/adr/020-suspend-lambda-mapping.md))
- [x] Support structured concurrency (see [ADR-021](docs/adr/021-structured-concurrency.md))
- [ ] Handle cancellation and exceptions across the bridge
- [ ] Map Flow APIs (cold streams → IAsyncEnumerable or RxObservables)

## Phase 6: Bidirectional support (C# → Kotlin)
- [ ] Research calling C# from Kotlin/Native (reverse P/Invoke, function pointers)
- [ ] Map lambda/function types — C# → Kotlin (see [ADR-012](docs/adr/012-lambda-function-type-mapping.md))
- [ ] Generate Kotlin wrappers for C# interfaces (callbacks, event handlers)
- [ ] Support implementing C# interfaces in Kotlin and passing them back to C# consumers
- [ ] Support implementing Kotlin interfaces in C# and passing them to Kotlin producers

## Future Improvements

- Support flat/unnested sealed class hierarchies (subclasses as top-level in same namespace)
- KSP incremental processing if build times become a concern on large libraries
- Map data classes to C# `record class` if a safe `with`-expression pattern can be found (see [ADR-008](docs/adr/008-data-class-mapping.md))
- Verify Kotlin GC actually frees objects after all StableRefs are disposed (requires Kotlin-side weak references + GC trigger — not feasible in standard unit tests)
- Memory leak detection tooling for bridged objects in CI
- Object identity preservation (caching wrappers) if profiling shows allocation overhead is significant
- Custom type mappers for dependency types (e.g., `kotlinx.datetime.Instant` → `DateTimeOffset`)
- Map KDoc annotations to C# XML docs for better IDE support
