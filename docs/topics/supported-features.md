# Supported features

A scannable index of what maps to what, and in which direction. Look here to find out whether a
Kotlin or C# shape crosses the bridge at all, and what it becomes on the other side.

This page carries neither the how nor the why. How to use a mapping lives in the topic page linked
in the **Docs** column; why it maps that way lives in the
[architecture decision records](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/README.md).

| Glyph | Meaning |
|---|---|
| `→` | Kotlin → C#: a Kotlin feature you author, surfaced as C# (forward, code generation) |
| `←` | C# → Kotlin: a feature from a consumed C# NuGet package, surfaced as Kotlin (reverse) |
| `⇄` | both directions |
| `⇸` | Kotlin ⇸ C#: a Kotlin feature with no C# projection; the row says why and what the processor does with it |

`⇄` is the target state. Where a row is one way, the **Notes** say whether the gap is fundamental
(the reverse direction reads a compiled assembly you do not own, so some source-only Kotlin
concepts cannot round-trip) or simply not built yet: see
[ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).

<!-- Editors: Notes is one clause of at most 200 characters: what the consumer
gets and the one thing that differs from naive expectation. Asymmetry is written `→ … · ← …`. No
footnotes, no `[n]` markers, no history, no diagnostic-code inventories. `scripts/verify-features.sh`
enforces this and the Docs CI runs it. -->

## Primitives

Primitive types follow the standard [Kotlin/Native C interop mappings](https://kotlinlang.org/docs/mapping-primitive-data-types-from-c.html#inspect-generated-kotlin-apis-for-a-c-library).

| Kotlin | ⇄ | C# | Notes | Docs |
|---|:-:|---|---|---|
| `Byte` / `Short` / `Int` / `Long` | ⇄ | `sbyte` / `short` / `int` / `long` |  | [Primitives and strings](primitives-and-strings.md) |
| `UByte` / `UShort` / `UInt` / `ULong` | ⇄ | `byte` / `ushort` / `uint` / `ulong` |  | [Primitives and strings](primitives-and-strings.md) |
| `Float` / `Double` | ⇄ | `float` / `double` |  | [Primitives and strings](primitives-and-strings.md) |
| `Boolean` | ⇄ | `bool` |  | [Primitives and strings](primitives-and-strings.md) |
| `Char` | → | `char` | A UTF-16 code unit, at a property, parameter, method return and `List`/`Map`/`Set` component. A lone surrogate does not round-trip and is out of scope. | [Primitives and strings](primitives-and-strings.md) |
| `String` | ⇄ | `string` | UTF-8 marshalling. | [Primitives and strings](primitives-and-strings.md) |
| `T?` (nullable primitive) | → | `T?` | Property and top-level getters use a two-call has-value pair; method and extension returns use one call. `Char?` and `Boolean?` bind at every ordinary position. The reverse direction is not built yet. | [Primitives and strings](primitives-and-strings.md) |
| `String?` | ⇄ | `string?` | → single call, `null` rides a null pointer and a nullable parameter carries `?` · ← nullability read from `NullableAttribute`; oblivious metadata binds non-null, with a warning | [Primitives and strings](primitives-and-strings.md) |
| exported class type, nullable (`Foo?`), as a **parameter** | → | `Foo?` | `null` rides `IntPtr.Zero`, so no has-value pair is needed. Bound on the class-method, constructor and top-level-function routes. | [Classes and objects](classes-and-objects.md) |
| `kotlin.time.Instant` / `Instant?` | → | `System.DateTimeOffset` / `DateTimeOffset?` | Property, constructor or method parameter, method return, top-level return. Kotlin to C# truncates below 100ns and throws outside years 0001-9999. | [Primitives and strings](primitives-and-strings.md) |
| `kotlin.uuid.Uuid` / `Uuid?` | → | `System.Guid` / `Guid?` | Crosses as RFC 9562 text on the `String` wire, so `Uuid?` rides a null pointer. Exact both ways, `Guid.Empty` is `Uuid.NIL`. Collection components deferred. | [Primitives and strings](primitives-and-strings.md) |
| `kotlin.time.Duration` / `Duration?` | → | `System.TimeSpan` / `TimeSpan?` | One `INT64` of ticks. Kotlin to C# truncates below 100ns and throws for infinite or out-of-range values; C# to Kotlin is exact within ±146 years. | [Primitives and strings](primitives-and-strings.md) |
| `ByteArray` / `ByteArray?` | → | `byte[]` / `byte[]?` | Copied on every crossing, never a view. Binds at a property, parameter, return, `List` element, `Map` value, `suspend` return and `Flow` element. | [Primitives and strings](primitives-and-strings.md) |

## OOP constructs

| Kotlin | ⇄ | C# | Notes | Docs |
|---|:-:|---|---|---|
| `class` | ⇄ | `class` | → `IDisposable` over an opaque handle; nested types become real C# nested types; a supertype outside the export set is dropped, its members re-homed · ← `AutoCloseable` over a handle | [Classes and objects](classes-and-objects.md) · [Objects and handles](objects-and-handles.md) |
| dependency-module type (cross-module export) | → | same class, generated into this module's package | A type your API exposes but a dependency declares is admitted by `include`/`rootPackage` or by an additive `admit(...)`; whatever is left out skips, named. | [NuGet DSL](nuget-dsl.md) |
| constructor | ⇄ | `new Foo(...)` | → defaults widen to one nullable signature, `null` meaning unset; a class with no bridgeable constructor is factory-only and says so · ← each public `.ctor` becomes a secondary constructor | [Classes and objects](classes-and-objects.md) · [Instance members](instance-members.md) |
| `data class` | → | `class` | `ToString`, `Equals` and `Copy` are generated. | [Data classes](data-classes.md) |
| `interface` | ⇄ | `interface` | → `I`-prefixed; defaults delegate to Kotlin, an interface-typed return gets a backing wrapper, a C# implementation passes back · ← handle-backed Kotlin `interface` a Kotlin class can implement | [Interfaces, abstract and sealed](interfaces-abstract-sealed.md) · [The bridgeable subset](bridgeable-subset.md) |
| `abstract class` | → | `abstract class` | Subclasses inherit the handle. An inherited but unimplemented member renders `public abstract` so a subclass `override` compiles; an unbridgeable member skips, named. | [Interfaces, abstract and sealed](interfaces-abstract-sealed.md) |
| `sealed class` | → | `abstract class` | Arms are nested or sibling C# classes reached through `FromHandle`, with their own members and constructors; a base-declared member renders `virtual`. An eligible sealed interface binds the same way. | [Interfaces, abstract and sealed](interfaces-abstract-sealed.md) |
| `object` | ⇄ | `static class` | → singleton; methods and its own `val`/`var` properties bind as statics, a `const val` becomes a real `const` · ← a consumed static class becomes a Kotlin `object` | [Objects and companions](objects-and-companions.md) · [Static classes and methods](static-classes-and-methods.md) |
| `data object` (in sealed) | → | sealed subclass | Binds `Equals`, `GetHashCode` and `ToString` like a `data class` arm, plus its own properties and declared methods, taking the handle receiver rather than going static. | [Interfaces, abstract and sealed](interfaces-abstract-sealed.md) |
| `enum class` | ⇄ | `enum` | → with extension methods; an entry keeps its internal capitals, `Mood?` binds as `Nullable<Mood>`, a collection component rides its `int` ordinal · ← ordinal-backed Kotlin `enum class` | [Enums](enums.md) · [The bridgeable subset](bridgeable-subset.md) |
| `data class` | ← | `struct` (`readonly record struct`, etc.) | Reverse only: the value decomposes onto the wire, so there is no handle and no `close()`. Components come from a state-covering constructor, else from declaration order. | [Structs](structs.md) |
| companion object | → | static members |  | [Objects and companions](objects-and-companions.md) |
| `annotation class` | ⇸ |  | Fundamental: there is no C# projection of a Kotlin annotation. A public one skips once, named, and using it on an exported declaration changes nothing. | [Publishing Kotlin to C#](forward-overview.md) |
| `kotlin.sequences.Sequence<T>` | ⇸ |  | No eager-copy story exists for a lazy sequence, so a parameter, return or property carrying one skips, named, and the C# declaration that lost it says so in `<remarks>`. | [Collections](collections.md) |
| declaration marked with an author's `@RequiresOptIn` marker | ⇸ |  | A marked type is never declared; a marked member skips while its owner still exports. `publish { exportMarkers(...) }` waives a named marker so its declarations keep exporting. | [Publishing Kotlin to C#](forward-overview.md) |
| `expect`/`actual class`/`interface`/`enum class`/`value class`, `fun`, `val`, `object` | → | ordinary declaration | The `actual` is the export root, indistinguishable from a non-`expect` declaration once exported. An `actual typealias` erases to its target. | [Expect and actual](expect-actual.md) |

## Properties and top-level declarations

| Kotlin | ⇄ | C# | Notes | Docs |
|---|:-:|---|---|---|
| member property (get/set) | ⇄ | property (get/set) | → nullable and handle-typed properties; an inherited unimplemented member renders `abstract`; no public setter, or an override of a base with none, binds get-only · ← `val` read-only, `var` settable | [Classes and objects](classes-and-objects.md) · [Instance members](instance-members.md) |
| instance method return (object, `T?`, `List`/`Map`/`Set`, enum, `Char`, `String?`, `Int?`, …) | → | matching C# return type | Same plan cascade as a property getter, except a nullable numeric return uses a single call: a method may have side effects and cannot be invoked twice. | [Classes and objects](classes-and-objects.md) |
| top-level function | → | `static class` method, `PascalCase` | One static class per source file. A file whose every declaration skips emits no class at all, and a name colliding with a same-file top-level property fails generation. | [Top-level declarations](top-level-declarations.md) |
| top-level property | → | static property | get and set, including nullable. | [Top-level declarations](top-level-declarations.md) |
| `const val` | → | `const` |  | [Top-level declarations](top-level-declarations.md) |
| extension function | → | static method | A real C# extension method. The receiver may be a class, `String`, a primitive, enum, value class, interface, sealed base or a nullable handle; a has-value receiver skips, named. | [Extensions](extensions.md) |
| extension property | → | static accessor | The same receiver set as an extension function, plus a collection and a bound C# interface. A nullable collection, a nullable bound interface and a has-value receiver skip, named. | [Extensions](extensions.md) |

## Generics

| Kotlin | ⇄ | C# | Notes | Docs |
|---|:-:|---|---|---|
| `class<T>` | ⇄ | `class<T>` | → type-erased bridge behind a generic C# wrapper, where a `null` read collapses to `default(T)` at a value-type instantiation · ← bound per closed instantiation; BCL ones are diagnosed | [Generics](generics.md) · [Generic types](generic-types.md) |
| `class<T>(...)` constructor | ⇄ | typed constructors | → typed arguments, and one entry point serves every instantiation of `T` · ← one Kotlin constructor per unambiguous instantiation; two that erase alike are both dropped | [Generics](generics.md) · [Generic types](generic-types.md) |
| a method declared on a `class<T>` | → | instance method on the generic carrier | Every `T` position crosses as a boxed handle; a position not naming `T` binds as on a plain class. `T` inside a collection, lambda or `Flow`, and a generic subclass, are refused. | [Generics](generics.md) |
| `fun <T> f()` | → | typed variants | Dispatched on the runtime type. | [Generics](generics.md) |
| `<T : Bound>` constraint | → | `where T : ...` |  | [Generics](generics.md) |
| `out T` / `in T` variance | → | `out T` / `in T` |  | [Generics](generics.md) |
| `typealias` | → | C# alias / underlying | Generic type aliases included. | [Generics](generics.md) |

## Collections

| Kotlin | ⇄ | C# | Notes | Docs |
|---|:-:|---|---|---|
| `List<T>` | → | `IReadOnlyList<T>` | Eager copy through an opaque handle at a property (settable when every component is wrappable), a method return and a parameter; `T` may itself be a collection. | [Collections](collections.md) |
| `MutableList<T>` | → | `IList<T>` | Eager copy; a parameter never writes back to Kotlin. Settable as a property when every component is wrappable; `T` may itself be a collection. | [Collections](collections.md) |
| `Map<K,V>` | → | `IReadOnlyDictionary<K,V>` | Eager copy at a property, return and parameter; a key or value must be a boxable scalar, enum, handle or collection. A nullable key (`Map<K?, V>`) has no route anywhere. | [Collections](collections.md) |
| `MutableMap<K,V>` | → | `IDictionary<K,V>` | Eager copy; a parameter never writes back. Settable as a property when every component is wrappable; `V` may itself be a collection. | [Collections](collections.md) |
| `Set<T>` | → | `IReadOnlySet<T>` | Eager copy at a property, return and parameter, over the same boxable component set as `Map`; `T` may itself be a `List`. | [Collections](collections.md) |
| `MutableSet<T>` | → | `ISet<T>` | Eager copy; a parameter never writes back. Settable as a property when every component is wrappable; `T` may itself be a collection. | [Collections](collections.md) |
| `T?` (nullable collection reference) | → | `T?` | `null` crosses as `IntPtr.Zero`, independent of component nullability. Property accessors and class-method or extension returns carry it; the `suspend`/`Flow` routes do not. | [Collections](collections.md) |
| `List<T>` / `Set<T>` / `Map<K,V>` (always read-only, never `Mutable*`) | ← | `IEnumerable<T>`, `IReadOnlyCollection<T>`, `IReadOnlyList<T>`, `ICollection<T>`, `IList<T>`, `List<T>` (→ `List<T>`); `IReadOnlySet<T>`, `ISet<T>`, `HashSet<T>` (→ `Set<T>`); `IReadOnlyDictionary<K,V>`, `IDictionary<K,V>`, `Dictionary<K,V>` (→ `Map<K,V>`) | Reverse only: an eager copy, so every C# shape binds read-only at every position. A mutable Kotlin type would let an `add` compile and change nothing. Map keys are never nullable. | [Reverse collections](reverse-collections.md) |

## Functions and lambdas

| Kotlin | ⇄ | C# | Notes | Docs |
|---|:-:|---|---|---|
| `(T) -> R` (Kotlin → C#) | → | `Func<>` / `Action<>` | Invoked from C#; a `Unit` return binds as `KotlinAction`. Nullable type arguments carry `?` and cross `null` both ways; an unnameable one skips the whole member, named. | [Lambdas and callbacks](lambdas-and-callbacks.md) |
| `(T) -> R` parameter (C# → Kotlin) | → | `Func<>` / `Action<>` | Per-call, arity 0 to 3; the member's own return may be a scalar, `String`, object or enum. A throwing C# lambda is catchable in Kotlin. A nullable payload has no route yet. | [Lambdas and callbacks](lambdas-and-callbacks.md) |
| stored callback parameter | → | `IDisposable` subscription | Subscribe and dispose from C#. A throwing listener is catchable in Kotlin, and a call arriving after `Dispose()` is dropped silently. | [Lambdas and callbacks](lambdas-and-callbacks.md) |
| Kotlin lambda at a bound delegate parameter | ← | `Func<>` / `Action<>` / `Predicate<T>` / `Comparison<T>` / `Converter<,>` / package-declared `delegate` parameter | A bound method or constructor's delegate parameter takes a plain Kotlin lambda, `Invoke` arity up to 4. The C# delegate owns its lifetime. Async delegates are not bound yet. | [Reverse delegates](reverse-delegates.md) |
| interface parameter (C# → Kotlin) | → | C# implements `I`-prefixed type | `add`/`remove` paired and `IDisposable`. A throwing member is catchable in Kotlin; uncaught, it reaches the C# caller as the original exception. | [Lambdas and callbacks](lambdas-and-callbacks.md) |
| `inline fun` | → | regular method |  | [Top-level declarations](top-level-declarations.md) |
| `inline fun <reified T>` | → | typed variants | Reified type parameters. | [Generics](generics.md) |
| value class (`value`/`inline class`) | → | underlying type / record struct | Renders as a `readonly record struct`; methods and secondary constructors bind on both underlying kinds, and a nested value class binds too. A reference-underlying `init` is deferred. | [Value classes](value-classes.md) |
| value class at an ordinary position | → | the same record struct | Returns, parameters at all five positions and class properties, over a `String`, primitive, enum or object-handle underlying, nullable included. Inherited members never bind. | [Value classes](value-classes.md) |
| value class as a `List`/`Map`/`Set` component | → | the same record struct, in `IReadOnlyList<T>`/`IReadOnlyDictionary<K,V>`/`IReadOnlySet<T>` etc. | Element, map key or map value, for the same four underlyings. The wire carries the underlying per element, never the value class itself. | [Value classes](value-classes.md) |
| same-name function overloads | ⇄ | method overload set | → one natural C# overload set per container, the numbering hidden on the export symbol; a pair differing only in reference nullability fails generation · ← each member keeps its Kotlin name | [Classes and objects](classes-and-objects.md) · [The bridgeable subset](bridgeable-subset.md) |
| function/method default parameters | → | one widened signature | Any subset settable by name; already-nullable params use `Optional<T>`. Cap of 8; a stored lambda default stays Kotlin-only. | [Classes and objects](classes-and-objects.md) |

## Exception handling

| Kotlin | ⇄ | C# | Notes | Docs |
|---|:-:|---|---|---|
| thrown exception | ⇄ | `KotlinException` | → propagates synchronously · ← a throw from package code surfaces as a catchable `NugetManagedException`; its .NET type map, stack trace and cause chain are not built yet | [Exceptions](exceptions.md) · [The bridgeable subset](bridgeable-subset.md) |
| stack trace | → | `KotlinStackTrace` property |  | [Exceptions](exceptions.md) |
| `e.cause` | → | `InnerException` | The cause chain is preserved. | [Exceptions](exceptions.md) |
| `IllegalArgumentException` etc. | → | `ArgumentException` etc. | Core exceptions are mapped through `IKotlinException`. | [Exceptions](exceptions.md) |
| `Throwable` / `Throwable?` property | → | `System.Exception?` | Reads as a constructed, unthrown exception rebuilt from the error envelope, so the type map and cause chain apply. Get-only, and a snapshot: each read allocates. | [Exceptions](exceptions.md) |
| `Result<T>` return | → | `T`, failure thrown | An ordinary return unwraps with `getOrThrow()`, so a modelled failure is indistinguishable in C# from a thrown one. `Result<Unit>` is `void`; other positions skip, named. | [Exceptions](exceptions.md) |
| property getter / setter throws | → | propagated |  | [Exceptions](exceptions.md) |
| constructor / `init` throws | → | propagated | Primary, secondary, a data class `copy()`, and generic and value class constructors. | [Exceptions](exceptions.md) |

## Async and coroutines

| Kotlin | ⇄ | C# | Notes | Docs |
|---|:-:|---|---|---|
| `suspend fun` | → | `async` / `Task<T>` | Two `suspend` overloads, on a class, a sealed arm, or at the top level, collapse into one C# overload set. | [Coroutines and Flow](coroutines-and-flow.md) |
| `suspend fun` returning `T?` | → | `Task<T?>` | A nullable string, object or primitive return carries its `?` on both sides of the bridge. | [Coroutines and Flow](coroutines-and-flow.md) |
| `suspend fun` returning an interface type, top-level or nested | ⇄ | `Task<IFoo>` | → completes with the interface itself, nullable included · ← a value stored through a C# implementation comes back as the caller's own instance, not a fresh wrapper | [Coroutines and Flow](coroutines-and-flow.md) · [Interfaces, abstract and sealed](interfaces-abstract-sealed.md) |
| `suspend () -> R` lambda | → | `KotlinSuspendFunc<R>` / `Task<R>` | The same mechanism as a `suspend fun`. | [Coroutines and Flow](coroutines-and-flow.md) |
| structured concurrency | → | honoured |  | [Coroutines and Flow](coroutines-and-flow.md) |
| coroutine cancellation | → | `CancellationToken` | Suspend lambdas included. | [Coroutines and Flow](coroutines-and-flow.md) |
| in-flight async drain | → | `IAsyncDisposable` | Graceful drain, owned by the first class in the chain that declares a `suspend` or `Flow` member. A class whose only async member was refused gets neither scope nor interface. | [Coroutines and Flow](coroutines-and-flow.md) |
| `Flow<T>` | → | `IAsyncEnumerable<T>` | Cold streams, at a class-method return or property only; every other owner skips, named. A failure materialising an element faults the enumerable and cancels the collector. | [Coroutines and Flow](coroutines-and-flow.md) |
| `Flow<T?>` (nullable element) | → | `KotlinFlow<T?>` | A null emission is a genuine item, not the end of the stream, for an interface, `String?` or `Int?`. A nullable member (`Flow<T>?`) is still unbound. | [Coroutines and Flow](coroutines-and-flow.md) |
| `Flow<T>` of an interface element type (top-level or nested interface, class-method `Flow` positions only) | ⇄ | `KotlinFlow<IFoo>` | → the element is spelled with the interface itself, nullable included · ← a value stored through a C# implementation comes back as the caller's own instance | [Coroutines and Flow](coroutines-and-flow.md) · [Interfaces, abstract and sealed](interfaces-abstract-sealed.md) |
| `StateFlow<T>` / `MutableStateFlow<T>` | → | `KotlinStateFlow<T>` / `KotlinMutableStateFlow<T>` (`.Value` + `IAsyncEnumerable<T>`) | Hot, always current. The declared type wins: `StateFlow<T>` is get-only `.Value`, a publicly declared `MutableStateFlow<T>` gets a settable one. | [Coroutines and Flow](coroutines-and-flow.md) |
| `StateFlow<T?>` / `StateFlow<T>?` | → | `KotlinStateFlow<T?>` / `KotlinStateFlow<T>?` | A nullable element and a nullable member, independently or combined. | [Coroutines and Flow](coroutines-and-flow.md) |
| `suspend fun` returning `StateFlow<T>` | → | `Task<KotlinStateFlow<T>>` | The outer suspend stays a `Task`, not collapsed to a synchronous return. Class methods only. | [Coroutines and Flow](coroutines-and-flow.md) |
| `List`/`Set`/`Map` parameter on a `Flow`/`StateFlow`-returning or `suspend` member | → | the collection type, never `IntPtr` | Converted eagerly, before the coroutine starts, so the handle is call-scoped; `.Value` re-marshals per read. Any other generic parameter skips, named. | [Coroutines and Flow](coroutines-and-flow.md) |
| `List`/`Set`/`Map` return on a `suspend` member | → | `Task<IReadOnlyList<T>>` / `Task<IReadOnlySet<T>>` / `Task<IReadOnlyDictionary<K, V>>` | Spelled and read exactly as the property route spells the same Kotlin type. Every other generic `suspend` return skips, named. | [Coroutines and Flow](coroutines-and-flow.md) |
| A class, `object`, sealed base, or sealed subclass parameter on a `Flow`/`StateFlow`-returning or `suspend` member | → | the mapped C# type, spelled exactly as the return position on the same member, passed as `x._handle` | Every other non-scalar, non-generic parameter on these routes, an enum, value class, interface or nullable object among them, skips, named. | [Coroutines and Flow](coroutines-and-flow.md) |
| A nullable primitive/`Char`/`String` parameter on a `Flow`/`StateFlow`-returning or `suspend` member | → | `int?`/`char?`/`bool?`/`string?` | A default argument (`= null`) is not honoured here: the C# parameter is always required. | [Coroutines and Flow](coroutines-and-flow.md) |
| `List`/`Set`/`Map` element on a `Flow`/`StateFlow` property or method return | → | `KotlinFlow<IReadOnlyList<T>>` / `KotlinStateFlow<IReadOnlyList<T>>` / `IReadOnlySet<T>` / `IReadOnlyDictionary<K, V>` | Spelled and read as every other route spells the same Kotlin type. A nullable element, a collection of a sealed base and every other generic element skip, named. | [Coroutines and Flow](coroutines-and-flow.md) |
| `suspend fun` returning `Unit`/`T` | ← | `Task`/`Task<T>`, instance or static | A faulted task is a catchable `NugetManagedException` carrying the original .NET type. A trailing `Async` is stripped unless a sibling owns that name; a single `CancellationToken` parameter elides. | [Instance members](instance-members.md) |
| `IAsyncEnumerable<T>` method return, instance or static | ← | `Flow<T>` | Pull-based and cold: the C# method runs at `collect`, not at the call that returned the `Flow`. A mid-stream throw arrives as `NugetManagedException`. | [Instance members](instance-members.md) |

<note>
<p>Hot streams (<code>SharedFlow</code>), <code>Flow</code> parameters, and <code>Flow</code> as a generic argument are not yet supported. See <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md">ROADMAP.md</a> Phase 6.</p>
</note>

## Documentation comments

| Kotlin | ⇄ | C# | Notes | Docs |
|---|:-:|---|---|---|
| KDoc (`/** ... */`, `@param`, `@return`, `@throws`, `@property`, `@constructor`, `@see`, `@suppress`) | → | `///` XML doc comment (`<summary>`, `<remarks>`, `<param>`, `<returns>`, `<exception cref>`, `<seealso cref>`) | Every generated declaration mirroring a documented one carries it. The first paragraph is the summary, the rest the remarks; `@param` is all or none. The reverse direction is not built. | [Documentation comments](documentation-comments.md) |

## Runtime and packaging

| Kotlin | ⇄ | C# | Notes | Docs |
|---|:-:|---|---|---|
| the fixed `nuget_*` ABI (`NugetHandles`, `NugetError`, the scalar wrap/unwrap, collection, callback and coroutine exports, the .NET ticks conversions, `NugetCSharpBridge`, `nuget_runtime_version`) | → | the same `DllImport`s in `Interop.cs`, plus one more | Ships once as a versioned `nuget-runtime` library the plugin adds and exports, so the generator emits only per-declaration code. The C# side is still source-shipped. | [Architecture](architecture.md) |
| every forward `@CName` export symbol | → | the matching `DllImport` `EntryPoint` | Always the library, then the package relative to `rootPackage`, then the declaration's own name, so same-named declarations in different packages never collide. | [Architecture](architecture.md) |
