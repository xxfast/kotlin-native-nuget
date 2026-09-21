# Instance members

Instance methods and instance properties on a bound C# class become member functions and
properties on the generated Kotlin wrapper (see [Objects and handles](objects-and-handles.md)).
Every call passes the wrapper's handle as the receiver, the same mechanism a handle-typed
parameter already uses elsewhere.

```C#
public class Template
{
    public Template(string source) => _source = source;

    public string Source => _source;             // read-only instance property
    public string Name { get; set; } = "world";   // settable instance property

    public string Apply(string name) => _source.Replace("{name}", name);
    public Template Clone() => new(_source) { Name = Name };
}
```

```kotlin
val template: Template = Template("Hello, {name}")
template.name = name

val copy: Template = template.clone()
copy.name        // "world", carried over from template
copy.apply(name) // substitutes {name} in copy's source
```

A read-only property (`{ get; }`, or a getter-only expression body) becomes a Kotlin `val`. A
settable property (`{ get; set; }`) becomes a `var`. Neither is a stored field: every access calls
through the bridge, just like a method call.

An `init`-only property (`{ get; init; }`) also becomes a `val`, not a `var`: Kotlin has no object
initializer to assign it through, so there is no setter to bind and no way to write it after
construction from Kotlin.

```C#
// TestDependency/Kennel.cs
public string Motto { get; init; } = "sit, stay";
public int Capacity { get; init; } = 2;
```

```kotlin
val kennel = Kennel()
"${kennel.motto}|${kennel.capacity}"  // "sit, stay|2", both read-only
```

## Overloads

Same-name C# instance methods become ordinary Kotlin overloads, resolved by parameter type:

```C#
public string Apply(string value) => $"{_origin}:text:{value}";
public string Apply(int value) => $"{_origin}:int:{value}";
```

```kotlin
lab.apply("hi") // resolves to the string overload
lab.apply(3)    // resolves to the int overload
```

An overload set the mapping can't tell apart is skipped with its own build diagnostic; the type's
other bridgeable members still generate.

## Name collisions with the wrapper itself

The generated wrapper already owns three Kotlin member names: `handle`, `close`, and `cleaner`
(see [Objects and handles](objects-and-handles.md)). A C# instance method or property whose
camelCased Kotlin name would collide with one of those is skipped, with a Gradle build warning
naming the member and asking you to rename it on the C# side or expose it through a
differently-named adapter member. Static members are unaffected: they land in the Kotlin
`companion object`, a separate name scope from the wrapper's own instance members (see
[Static classes and methods](static-classes-and-methods.md)).

## Handle-typed properties

A settable property whose type is itself a bound class renders as a Kotlin `var`, and its
nullability follows the property's `NullableAttribute`, exactly as for a handle-typed method
parameter or return (see [Objects and handles](objects-and-handles.md)):

```C#
public Nickname? Favourite { get; set; }  // nullable getter and setter agree: Nickname?
public Nickname Primary { get; set; }     // non-null getter and setter agree: Nickname
```

```kotlin
var favourite: Nickname?
var primary: Nickname
```

Because a property carries exactly one nullability annotation, its Kotlin getter and setter
always agree on the same type, so a settable handle-typed property is never forced to a read-only
`val` the way a mismatched getter/setter pair would be.

## Async methods {id="async-methods"}

A `Task`- or `Task<T>`-returning method, instance or static, becomes a Kotlin `suspend fun`
returning `Unit` or `T`:

```C#
// TestDependency/Kennel.cs
public class Kennel
{
    public async Task NapAsync() => await Task.Delay(10);

    public async Task<int> CountAsync()
    {
        await Task.Delay(10);
        return 2;
    }
}
```

```kotlin
val kennel = Kennel()
kennel.nap()         // suspend fun nap()
kennel.count()       // suspend fun count(): Int
```

A trailing `Async` is dropped, unless the declaring type also has a method whose C# name equals the
stripped name, in which case the `Async`-suffixed name is kept so the two don't collide (Kotlin
can't overload on `suspend` alone):

```C#
public string Read() => "the kennel ledger";
public async Task<string> ReadAsync() => "the kennel ledger, read slowly";
```

```kotlin
kennel.read()        // fun read(): String
kennel.readAsync()   // suspend fun readAsync(): String
```

A faulted task surfaces as a catchable `NugetManagedException` carrying the *original* .NET
exception type, never `AggregateException`:

```kotlin
try {
  kennel.escape("Oreo")
} catch (e: NugetManagedException) {
  e.managedType   // "System.InvalidOperationException"
  e.message       // "Oreo slipped the latch"
}
```

The completion runs on a .NET thread-pool thread, never inline on the thread that started the
call. A task that ends *cancelled* is different from an ordinary fault: see
[Cancellation](#async-cancellation) below.

## Cancellation {id="async-cancellation"}

An async method with exactly one `CancellationToken` parameter, at any position, binds with the
token elided; the bridge supplies it, so cancelling the Kotlin side that's awaiting the call
actually tells the C# work to stop:

```C#
// TestDependency/Kennel.cs
public async Task<int> StayAsync(string name, CancellationToken ct)
{
    try { await Task.Delay(Timeout.Infinite, ct); return name.Length; }
    catch (OperationCanceledException) { StayCancelled = true; throw; }
}
```

```kotlin
val kennel = Kennel()
withTimeoutOrNull(50.milliseconds) { kennel.stay("Oreo") }  // suspend fun stay(name: String): Int
delay(200.milliseconds)         // give the queued C# cancel a beat to land
kennel.stayCancelled            // true: StayAsync's token really was cancelled
```

This covers `withTimeout`/`withTimeoutOrNull`, `job.cancel()`, and disposing an enclosing
`coroutineScope`, the same as any other coroutine cancellation. A `= default` value on the
parameter makes no difference; the bridge always supplies its own token, never the default.
A method that ignores its token behaves as before: the Kotlin wait still ends promptly, but the
C# work runs to completion regardless.

A C# task that ends cancelled (`OperationCanceledException` or any subtype, including
`TaskCanceledException`, thrown from a sync **or** async call) surfaces as stdlib
`kotlin.coroutines.cancellation.CancellationException` instead of `NugetManagedException`, with
the original `NugetManagedException` riding as `cause`:

```C#
public async Task BoltAsync()
{
    using CancellationTokenSource own = new();
    Task running = Task.Delay(Timeout.Infinite, own.Token);
    own.Cancel();          // C# cancels itself; Kotlin never touched this token
    await running;
}
```

```kotlin
try {
  kennel.bolt()
} catch (e: CancellationException) {
  val cause = e.cause as NugetManagedException
  cause.managedType   // "System.Threading.Tasks.TaskCanceledException"
}
```

This is the one case worth being careful with: a `CancellationException` thrown into a coroutine
that Kotlin itself never cancelled ends that coroutine silently, without failing its parent, the
same footgun a manually-thrown `CancellationException` is anywhere in kotlinx. Catch it explicitly,
as above, around any call whose C# side might cancel itself.

Two C# overloads that differ only by a trailing `CancellationToken` (`FooAsync()` and
`FooAsync(CancellationToken)`) both elide to the same `suspend fun foo()`; the reader keeps the
token-taking overload and drops the other, so a consumer only ever sees one `foo()`.

A **sync** method taking a token, a method taking two or more tokens, a nullable
`CancellationToken?`, or a token on a constructor or property, is not bound; it's a named
diagnostic (`info_cancellation_token_not_yet_mapped`) rather than the generic
`skipped_unbound_type_reference` hint.

## Async streams {id="async-streams"}

An `IAsyncEnumerable<T>`-returning method, instance or static, becomes a Kotlin `Flow<T>`:

```C#
// TestDependency/Kennel.cs
public IAsyncEnumerable<string> BarksAsync(int count) { /* ... */ }
public async IAsyncEnumerable<Kitten> LitterAsync(
    [EnumeratorCancellation] CancellationToken ct = default) { /* ... */ }
public static async IAsyncEnumerable<int> Ticks() { /* ... */ }
```

```kotlin
val kennel = Kennel()
kennel.barks(3).collect { println(it) }                    // fun barks(count: Int): Flow<String>
kennel.litter().collect { kitten -> kitten.use { it.name } } // fun litter(): Flow<Kitten>, elements are handles you own
Kennel.ticks().toList()                                      // companion object { fun ticks(): Flow<Int> }
```

The naming and cancellation-token elision rules are the same as [async methods](#async-methods) and
[cancellation](#async-cancellation) above; a trailing `Async` drops the same way, and a single
`CancellationToken` parameter elides.

The flow is cold, and **the C# method itself runs at `collect`, not at the Kotlin call that returns
the `Flow`**. One `Flow` value collected twice runs the C# method twice:

```kotlin
val barks = kennel.barks(1)
barks.toList()   // BarksAsync runs once
barks.toList()   // runs again, from the start
```

A synchronous argument-validation throw from the C# method therefore surfaces at `collect`, not
where you called `kennel.barks(...)`, and a method with side effects runs once per collect rather
than once per `Flow` value.

Collector cancellation (`withTimeoutOrNull`, `take(1)`, a cancelled parent job) stops the C#
enumeration, but only **promptly** when the source itself marks its token parameter
`[EnumeratorCancellation]` (`LitterAsync` above); without it, the current step finishes and yields
one more element before C# notices. Either way, the collector receives nothing past the point it
cancelled: no element is ever delivered after cancellation. Disposing the C# enumerator is
fire-and-forget: `collect` can return before the C# iterator's own `finally` has run, and an
exception thrown from that `finally` is silently dropped. A mid-stream throw from the C# source
surfaces the same way a faulted `Task` does, as a catchable `NugetManagedException`:

```kotlin
try {
  kennel.howls().collect { }
} catch (e: NugetManagedException) {
  e.managedType   // "System.InvalidOperationException"
}
```

`IAsyncEnumerable<T>` at a parameter, property, constructor, or type-argument position,
`IAsyncEnumerable<T>?`, a nullable *value* element (`IAsyncEnumerable<int?>`), and async on a bound
interface, a struct, or a generic class, are not bound; see Limitations below.

## Limitations

- `Nullable<T>` value-typed instance properties and parameters (`int?`, `CatMood?`) are not yet
  supported.
- Struct-typed instance properties and methods are supported; see [C# structs](structs.md).
- `ValueTask`/`ValueTask<T>` methods, and an async method (including `IAsyncEnumerable<T>`) on a
  bound interface, a struct, or a generic class, don't bind yet; see
  [The bridgeable subset](bridgeable-subset.md).
- `IAsyncEnumerable<T>` at a parameter, property, constructor, or type-argument position,
  `IAsyncEnumerable<T>?`, and a nullable *value* element (`IAsyncEnumerable<int?>`), don't bind yet.
- A sync method taking a `CancellationToken`, a method taking more than one, a nullable
  `CancellationToken?`, and a token on a constructor or property are not yet bound.

<seealso>
    <category ref="related">
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="static-classes-and-methods.md">Static classes and methods</a>
        <a href="structs.md">C# structs</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/051-csharp-objects-as-opaque-handles.md">ADR-051: C# objects as opaque handles</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md">ADR-053: Nullable reference types in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/057-csharp-overload-sets-in-kotlin.md">ADR-057: C# overload sets in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/152-task-to-suspend-fun.md">ADR-152: Reverse Task/Task&lt;T&gt; to suspend fun</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/153-reverse-cancellation-token.md">ADR-153: Reverse cancellation token</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/156-iasyncenumerable-to-flow.md">ADR-156: Reverse IAsyncEnumerable&lt;T&gt; to Flow&lt;T&gt;</a>
    </category>
</seealso>
