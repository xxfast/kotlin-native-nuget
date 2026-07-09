# Supported Features

This is the catalogue of language features the bridge maps across the Kotlin/Native ↔ C# boundary — in **both directions** — and how. For the development roadmap (including what is not yet supported) see [ROADMAP.md](ROADMAP.md). For the reasoning behind each mapping follow the ADR links in the last column.

**Direction** (the middle column of every table):

| Glyph | Meaning                                                                               |
|-------|---------------------------------------------------------------------------------------|
| `→`   | Kotlin → C#: a Kotlin feature you author, surfaced as C# (forward, code generation)   |
| `←`   | C# → Kotlin: a feature from a consumed C# NuGet package, surfaced as Kotlin (reverse) |
| `⇄`   | both directions                                                                       |

`⇄` is the target state. Where a row is one-way, the **Notes** say why — either it is **fundamental** (the reverse direction reads a compiled assembly you don't own, so some source-only Kotlin concepts can't round-trip), or it is simply **not built yet** (see [ROADMAP.md](ROADMAP.md)).

**How the reverse direction works.** The Gradle plugin resolves the NuGet package, extracts its API to `reverse-ir.json`, and generates a Kotlin stub plus the C# registration shim that wires function pointers into the Kotlin/Native library at startup. `packNuget` produces a real `.nupkg` that merges the reverse shims into `contentFiles` and pins the bound package at its exact resolved version, so a consumer needs only a single `<PackageReference>` (demonstrated by the `sample-library` → `MimeMapping` round trip exercised from `SampleApp.Tests`, [ADR-050](docs/adr/050-end-to-end-packaging-integration.md)). Reverse support is currently **v1**: `void`/`string`/primitive params & returns, static methods, and object handles. See [ROADMAP.md](ROADMAP.md) Phase 8 for the not-yet-supported surface.

## Primitives

Primitive types follow the standard [Kotlin/Native C interop mappings](https://kotlinlang.org/docs/mapping-primitive-data-types-from-c.html#inspect-generated-kotlin-apis-for-a-c-library).

| Kotlin                                | ⇄ | C#                                   | Notes                             | ADRs                                                 |
|---------------------------------------|:-:|--------------------------------------|-----------------------------------|------------------------------------------------------|
| `Byte` / `Short` / `Int` / `Long`     | ⇄ | `sbyte` / `short` / `int` / `long`   |                                   |                                                      |
| `UByte` / `UShort` / `UInt` / `ULong` | ⇄ | `byte` / `ushort` / `uint` / `ulong` |                                   |                                                      |
| `Float` / `Double`                    | ⇄ | `float` / `double`                   |                                   |                                                      |
| `Boolean`                             | ⇄ | `bool`                               |                                   |                                                      |
| `String`                              | ⇄ | `string`                             | UTF-8 marshalling                 |                                                      |
| `T?` (nullable primitive / string)    | → | `T?`                                 | two-call pattern; ← not built yet | [ADR-002](docs/adr/002-nullable-two-call-pattern.md) |

## OOP Constructs

| Kotlin                    | ⇄ | C#               | Notes                                                                                                                                                                                                                                                                                                               | ADRs                                                                                                                              |
|---------------------------|:-:|------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `class`                   | ⇄ | `class`          | → `: IDisposable`, StableRef + opaque pointer · ← `AutoCloseable` wrapper, opaque `GCHandle` released via `Cleaner` (deterministically via `close()` / `use {}`), C# statics land in a `companion object` and instance methods on the wrapper itself (the receiver handle is passed as the thunk's first argument); an instance member whose Kotlin name would collide with the wrapper's own `handle`/`close`/`cleaner` is skipped with a warning; object return/param maps `null` ⇄ null pointer, new wrapper per crossing (identity equality, no caching) | [ADR-005](docs/adr/005-object-return-semantics.md), [ADR-051](docs/adr/051-csharp-objects-as-opaque-handles.md)                   |
| constructor               | ⇄ | `new Foo(...)`   | → Kotlin constructor surfaces as C# `new` · ← single public instance `.ctor` → Kotlin secondary `constructor(...)` delegating through a bridge helper, non-null handle return; multiple ctors (overload set) not built yet                                                                                             | [ADR-052](docs/adr/052-csharp-instance-constructors-in-kotlin.md)                                                                 |
| `data class`              | → | `class`          | `ToString`, `Equals`, `Copy`                                                                                                                                                                                                                                                                                        | [ADR-008](docs/adr/008-data-class-mapping.md)                                                                                     |
| `interface`               | → | `interface`      | `I`-prefixed, default methods delegate to Kotlin                                                                                                                                                                                                                                                                    |                                                                                                                                   |
| `abstract class`          | → | `abstract class` | `_handle` inherited by subclasses                                                                                                                                                                                                                                                                                   |                                                                                                                                   |
| `sealed class`            | → | `abstract class` | subclasses nested                                                                                                                                                                                                                                                                                                   | [ADR-009](docs/adr/009-sealed-class-mapping.md)                                                                                   |
| `object`                  | ⇄ | `static class`   | → singleton, `object` in `sealed` · ← consumed static class → Kotlin `object` (v1: static methods only)                                                                                                                                                                                                             | [ADR-048](docs/adr/048-kotlin-stub-generation-from-reverse-ir.md), [ADR-049](docs/adr/049-csharp-registration-shim-generation.md) |
| `data object` (in sealed) | → | sealed subclass  | with `ToString`                                                                                                                                                                                                                                                                                                     |                                                                                                                                   |
| `enum class`              | → | `enum`           | with extension methods                                                                                                                                                                                                                                                                                              | [ADR-006](docs/adr/006-enum-mapping.md)                                                                                           |
| companion object          | → | static members   |                                                                                                                                                                                                                                                                                                                     |                                                                                                                                   |

## Properties & Top-level Declarations

| Kotlin                    | ⇄ | C#                    | Notes                                            | ADRs                                                       |
|---------------------------|:-:|-----------------------|--------------------------------------------------|------------------------------------------------------------|
| member property (get/set) | ⇄ | property (get/set)    | → nullable primitive + object properties supported · ← instance property, read-only → `val`, settable → `var` with bridge-backed `get()`/`set()`; a handle-typed property is always `val Foo?` until reverse nullability lands; static C# properties not built yet | [ADR-051](docs/adr/051-csharp-objects-as-opaque-handles.md) |
| top-level function        | → | `static class` method | one static class per source file                 | [ADR-007](docs/adr/007-top-level-function-class-naming.md) |
| top-level property        | → | static property       | get/set, including nullable                      |                                                            |
| `const val`               | → | `const`               |                                                  |                                                            |
| extension function        | → | static method         |                                                  |                                                            |
| extension property        | → | static accessor       |                                                  | [ADR-013](docs/adr/013-extension-property-mapping.md)      |

## Generics

| Kotlin                      | ⇄ | C#                    | Notes                                   | ADRs                                                       |
|-----------------------------|:-:|-----------------------|-----------------------------------------|------------------------------------------------------------|
| `class<T>`                  | → | `class<T>`            | type-erased bridge + generic C# wrapper | [ADR-010](docs/adr/010-generics-mapping.md)                |
| `class<T>(...)` constructor | → | typed constructors    | typed arguments through the bridge      |                                                            |
| `fun <T> f()`               | → | typed variants        | runtime dispatch via `NugetMarshal`     |                                                            |
| `<T : Bound>` constraint    | → | `where T : ...`       |                                         | [ADR-015](docs/adr/015-generic-type-constraint-mapping.md) |
| `out T` / `in T` variance   | → | `out T` / `in T`      |                                         | [ADR-016](docs/adr/016-generic-variance-mapping.md)        |
| `typealias`                 | → | C# alias / underlying | generic type aliases                    | [ADR-018](docs/adr/018-type-alias-mapping.md)              |

## Collections

| Kotlin            | ⇄  | C#                         | Notes                        | ADRs |
|-------------------|:--:|----------------------------|------------------------------|------|
| `List<T>`         | →  | `IReadOnlyList<T>`         | eager copy via opaque handle |      |
| `MutableList<T>`  | →  | `IList<T>`                 | eager copy                   |      |
| `Map<K,V>`        | →  | `IReadOnlyDictionary<K,V>` | eager copy                   |      |
| `MutableMap<K,V>` | →  | `IDictionary<K,V>`         | eager copy                   |      |
| `Set<T>`          | →  | `IReadOnlySet<T>`          | eager copy                   |      |
| `MutableSet<T>`   | →  | `ISet<T>`                  | eager copy                   |      |

See [ADR-011](docs/adr/011-collection-type-mapping.md).

## Functions & Lambdas

| Kotlin                               | ⇄ | C#                              | Notes                                            | ADRs                                                    |
|--------------------------------------|:-:|---------------------------------|--------------------------------------------------|---------------------------------------------------------|
| `(T) -> R` (Kotlin → C#)             | → | `Func<>` / `Action<>`           | invoked from C#                                  | [ADR-012](docs/adr/012-lambda-function-type-mapping.md) |
| `(T) -> R` parameter (C# → Kotlin)   | → | `Func<>` / `Action<>`           | reverse interop, arity 0+, per-call              | [ADR-036](docs/adr/036-reverse-interop-mechanism.md)    |
| stored callback parameter            | → | `IDisposable` subscription      | Kotlin-side `_unsubscribe` export                | [ADR-037](docs/adr/037-stored-callbacks.md)             |
| interface parameter (C# → Kotlin)    | → | C# implements `I`-prefixed type | `add`/`remove`-paired, `IDisposable`, N pointers | [ADR-039](docs/adr/039-interface-bridging.md)           |
| `inline fun`                         | → | regular method                  |                                                  | [ADR-017](docs/adr/017-inline-function-mapping.md)      |
| `inline fun <reified T>`             | → | typed variants                  | reified type parameters                          |                                                         |
| value class (`value`/`inline class`) | → | underlying type / record struct |                                                  | [ADR-014](docs/adr/014-value-class-mapping.md)          |

## Exception Handling

| Kotlin                          | ⇄ | C#                          | Notes                                                                                                                                                                  | ADRs                                                                                                                                                                    |
|---------------------------------|:-:|-----------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| thrown exception                | ⇄ | `KotlinException`           | → synchronous propagation across the bridge · ← v1 thunk has no `try/catch`, a C# exception escapes and terminates the host; graceful propagation deferred to Phase 11 | [ADR-023](docs/adr/023-exception-propagation.md), [ADR-024](docs/adr/024-sync-exception-propagation.md), [ADR-049](docs/adr/049-csharp-registration-shim-generation.md) |
| stack trace                     | → | `KotlinStackTrace` property |                                                                                                                                                                        | [ADR-027](docs/adr/027-stacktrace-propagation.md)                                                                                                                       |
| `e.cause`                       | → | `InnerException`            | cause chain                                                                                                                                                            | [ADR-028](docs/adr/028-exception-cause-chain.md)                                                                                                                        |
| `IllegalArgumentException` etc. | → | `ArgumentException` etc.    | core exceptions mapped via `IKotlinException`                                                                                                                          | [ADR-029](docs/adr/029-exception-type-mapping.md)                                                                                                                       |
| property getter / setter throws | → | propagated                  |                                                                                                                                                                        | [ADR-030](docs/adr/030-property-exception-propagation.md)                                                                                                               |
| constructor / `init` throws     | → | propagated                  | primary, secondary, data class `copy()`, generic + value class constructors                                                                                            | [ADR-031](docs/adr/031-constructor-exception-propagation.md)–[ADR-035](docs/adr/035-value-class-primary-constructor-validation.md)                                      |

## Async & Coroutines

| Kotlin                   | ⇄ | C#                                 | Notes                     | ADRs                                                  |
|--------------------------|:-:|------------------------------------|---------------------------|-------------------------------------------------------|
| `suspend fun`            | → | `async` / `Task<T>`                |                           | [ADR-019](docs/adr/019-suspend-function-mapping.md)   |
| `suspend () -> R` lambda | → | `KotlinSuspendFunc<R>` / `Task<R>` |                           | [ADR-020](docs/adr/020-suspend-lambda-mapping.md)     |
| structured concurrency   | → | honoured                           |                           | [ADR-021](docs/adr/021-structured-concurrency.md)     |
| coroutine cancellation   | → | `CancellationToken`                | including suspend lambdas | [ADR-022](docs/adr/022-cancellation-token-support.md) |
| in-flight async drain    | → | `IAsyncDisposable`                 | graceful drain            | [ADR-025](docs/adr/025-async-disposable.md)           |
| `Flow<T>`                | → | `IAsyncEnumerable<T>`              | cold streams              | [ADR-026](docs/adr/026-flow-mapping.md)               |

> [!NOTE]
> Hot streams (`SharedFlow`, `StateFlow`), `Flow` parameters, and `Flow` as a generic argument are not yet supported. See [ROADMAP.md](ROADMAP.md) Phase 6.
