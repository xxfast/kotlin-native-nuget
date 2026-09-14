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

## Limitations

- `Nullable<T>` value-typed instance properties and parameters (`int?`, `CatMood?`) are not yet
  supported.
- Struct-typed instance properties and methods are supported; see [C# structs](structs.md).

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
    </category>
</seealso>
