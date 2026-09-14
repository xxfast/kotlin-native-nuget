# Objects and handles

A bound C# class (not `static`) becomes a Kotlin class that wraps an opaque handle to the real C#
object. Every bridgeable public instance constructor becomes a Kotlin secondary constructor, and
every crossing of the bridge (a return, a parameter, or a fresh construction) allocates a new handle
and a new Kotlin wrapper, even for the same underlying C# object.

For this C# class:

```C#
public class Template
{
    private readonly string _source;
    public Template(string source) => _source = source;
    public string Apply(string name) => _source.Replace("{name}", name);
    public static Template Parse(string source) => new(source);
}
```

Construct and use it from Kotlin:

```kotlin
val template = Template("Hello, {name}")
val rendered: String = template.use { it.apply("Oreo") }
```

Instance methods and properties are covered in [Instance members](instance-members.md); this page
covers the wrapper itself: disposal, nullability, and identity.

## Disposal

The wrapper implements `kotlin.AutoCloseable` and releases its handle automatically once it becomes
unreachable, the way disposal already works for a Java or Objective-C object crossing into Kotlin.
`close()` (directly, or through `use { }` as above) is an opt-in for releasing the C# object
deterministically instead of whenever Kotlin's GC gets to it; it is idempotent, so calling it twice,
or letting automatic cleanup run after an explicit `close()`, is a safe no-op. Calling any member on
an already-closed wrapper throws `IllegalStateException`.

## Nullability

An object's nullability at a return, parameter, or property position follows the bound C# API's own
nullable-reference annotation, not a fixed rule per position:

```C#
public Nickname? Lookup(string name) => /* ... */;      // -> Kotlin `fun lookup(name: String): Nickname?`
public Nickname DefaultNickname() => /* ... */;         // -> Kotlin `fun defaultNickname(): Nickname`
public string Describe(Nickname? nickname) => /* ... */; // -> Kotlin `fun describe(nickname: Nickname?): String`
```

```kotlin
val nickname: String? = NicknameBook().lookup("Mylo")?.value
val fallback: String = NicknameBook().defaultNickname().value
val described: String = NicknameBook().describe(nickname?.let(::Nickname))
```

`null` at an annotated-nullable position means no wrapper was allocated for that call; there is
nothing to `close()`. At a non-null position, a `null` slipping through anyway throws
`IllegalStateException` naming the member, rather than propagating a bad handle.

An assembly with no nullable-reference annotations at all (pre-C#-8, or built with
`#nullable disable`) binds every one of its reference types as non-null, string or object alike, and
the build emits one `info_oblivious_nullability` warning per assembly (or per member, for an
otherwise-annotated assembly with an oblivious island). A legitimately-null return from such an
assembly still throws `IllegalStateException` at the Kotlin call site.

## No identity caching

Two wrappers that came from the same underlying C# object are unrelated: each holds its own handle,
each is closed independently, and neither one's `close()` invalidates the other. `equals`,
`hashCode`, and `toString` are never delegated to the C# object's own implementations; they stay
Kotlin's defaults, reference identity on the wrapper itself:

```kotlin
val a = Template.parse("x")
val b = Template.parse("x")
a == b   // false - different wrappers, even though both wrap equivalent C# state
```

## Limitations

- `Nullable<T>` value types (`int?`, `CatMood?`) are not supported at any position.
- A throwing C# constructor or factory method is catchable as `NugetManagedException`, not fatal;
  see [The bridgeable subset](bridgeable-subset.md#exceptions).

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="instance-members.md">Instance members</a>
        <a href="structs.md">C# structs</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/051-csharp-objects-as-opaque-handles.md">ADR-051: C# objects as opaque handles</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/052-csharp-instance-constructors-in-kotlin.md">ADR-052: C# instance constructors in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md">ADR-053: Nullable reference types in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/104-reverse-thunk-error-channel.md">ADR-104: Reverse thunk error channel</a>
    </category>
</seealso>
