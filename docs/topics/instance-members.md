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
call. Cancelling the Kotlin coroutine (e.g. its enclosing scope) stops the Kotlin side from
waiting, but doesn't cancel the C# task; its result, once it arrives, is discarded.

## Limitations

- `Nullable<T>` value-typed instance properties and parameters (`int?`, `CatMood?`) are not yet
  supported.
- Struct-typed instance properties and methods are supported; see [C# structs](structs.md).
- `ValueTask`/`ValueTask<T>` methods, and an async method on a bound interface, a struct, or a
  generic class, don't bind yet; see [The bridgeable subset](bridgeable-subset.md).

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
    </category>
</seealso>
