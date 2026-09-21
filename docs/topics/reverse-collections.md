# Collections from C#

A bound C# member that returns or takes a BCL collection binds as a Kotlin collection instead of
being skipped. The value is eagerly copied across the bridge in both directions: there is no lazy
bridging and no lasting connection back to the C# collection you passed in or got out.

```C#
// TestDependency
public class Roster
{
    public IReadOnlyList<string> Names() => new List<string> { "Oreo", "Mylo" };
    public IReadOnlyDictionary<string, double> Scores() => new() { ["Oreo"] = 9.5 };
    public IReadOnlySet<CatMood> Moods() => new HashSet<CatMood> { CatMood.Playful };
    public IList<Tag> Tags() => new List<Tag> { new("chip") };
    public int Enroll(IEnumerable<string> names) => names.Count();
}
```

```kotlin
val roster = Roster()
val names: List<String> = roster.names()
val scores: Map<String, Double> = roster.scores()
val moods: Set<CatMood> = roster.moods()
val tags: List<Tag> = roster.tags()          // IList<Tag> in C#, a read-only copy here
roster.enroll(listOf("Oreo", "Mylo"))
```

## Type mapping

Every C# declared collection type maps to the **read-only** Kotlin collection, at every position:
return, property, parameter, and constructor parameter. There is no `MutableList`/`MutableSet`/
`MutableMap` anywhere on this side of the bridge, even for a C# type that is itself mutable
(`IList<T>`, `IDictionary<K,V>`, `ISet<T>`). The value you hold in Kotlin is a copy either way, so a
mutable Kotlin type would let `roster.tags().toMutableList().add(x)` look like it changes something
in C# when it never does.

| C# declared type | Kotlin |
|---|---|
| `IEnumerable<T>`, `IReadOnlyCollection<T>`, `IReadOnlyList<T>`, `ICollection<T>`, `IList<T>`, `List<T>` | `List<T>` |
| `IReadOnlySet<T>`, `ISet<T>`, `HashSet<T>` | `Set<T>` |
| `IReadOnlyDictionary<K,V>`, `IDictionary<K,V>`, `Dictionary<K,V>` | `Map<K,V>` |

`IEnumerable<T>` is bound as an eager `List<T>`: the C# side calls `ToArray()` before the collection
crosses. An infinite `IEnumerable<T>` never returns from the call that reads it, and a very large one
is fully materialized in memory on both sides; the bridge can't tell either case from an ordinary
finite one.

A parameter also accepts any Kotlin collection literal (`listOf(...)`, `setOf(...)`, `mapOf(...)`)
regardless of which of the six list-like C# definitions the member declares, since the parameter
type is the same read-only Kotlin type either way. A `Set`/`Map`/`List` parameter never writes back:
Kotlin sends its own copy, and the C# member sees only what it does with that copy.

## Elements

A collection element, map key, or map value supports: a primitive (`Boolean`, `Byte`, `Short`, `Int`,
`Long`, `Float`, `Double`, `Char`), `String`, a bound enum, a bound class, or a bound interface (see
[The bridgeable subset](bridgeable-subset.md)). `String`, class, and interface elements or map
values may themselves be nullable (`List<String?>`); the collection itself may be nullable too, and
a `null` collection is distinct from an empty one:

```kotlin
val nicknames: List<String?> = roster.nicknames()   // ["O", null]
val maybe: List<String>? = roster.maybeNames()       // null, not an empty list
```

Not yet supported, each skipped with its own build diagnostic rather than silently dropped: a struct
element, a nested collection (`List<List<T>>`), a `List<Int?>`-shaped nullable *value-type* element,
a bound-generic-instance element (`List<Box<T>>`), and a type-parameter or `object` element. A map
key is never nullable.

## What doesn't bind yet

- **Arrays** (`T[]`). Expose an `IReadOnlyList<T>` (or another mapped collection type) instead.
- **`Task<TCollection>`**. An async method returning a collection doesn't bind yet; see
  [Instance members](instance-members.md#async-methods).
- Any other BCL collection definition outside the table above (`Queue<T>`, `ImmutableArray<T>`,
  `KeyValuePair<K,V>`, `ObservableCollection<T>`, non-generic `IList`/`IEnumerable`, ...).

Each of these is named on its own build diagnostic, alongside every other unbindable member; see
[Unsupported members show up as build warnings](bridgeable-subset.md#unsupported-members-show-up-as-build-warnings).

## Overloads that collapse

Collapsing six different C# list-like declared types onto one Kotlin `List<T>` means two C#
overloads that used to be distinguishable can now project to the identical Kotlin signature, most
commonly a pair like `AddRange(IEnumerable<T>)` beside `AddRange(List<T>)`. When that happens, the
**whole overload set** is dropped, never "all but one": the build diagnostic names every dropped
member, and the type's other, non-colliding members still bind. There's no way to keep one overload
of such a pair; expose them under different names on the C# side if you need both reachable from
Kotlin.

An overload pair that does *not* collapse (different collection **shapes**, or a distinguishing
extra parameter) binds normally, and Kotlin dispatches to the C# overload matching the declared
parameter type, not always the most general one:

```C#
public string Pick(IList<int> xs, Tag tag) => "IList";
public string Pick(List<int> xs, ILabelled labelled) => "List";
```

```kotlin
roster.pick(listOf(1), tag)                 // "IList"
roster.pick(listOf(1), tag as ILabelled)     // "List"
```

## Exceptions mid-enumeration

Reading a C# member's collection return only runs C# user code once, inside the crossing itself
(`ToArray()` on the C# side). If a lazily evaluated `IEnumerable<T>` throws partway through, nothing
was allocated yet, and Kotlin sees an ordinary catchable `NugetManagedException`
(see [Exceptions](bridgeable-subset.md#exceptions)):

```kotlin
try {
  roster.broken().joinToString(",")
} catch (e: NugetManagedException) {
  e.managedType   // "System.InvalidOperationException"
  e.message       // "boom"
}
```

## Handle elements and disposal

A class- or interface-typed element (`IList<Tag>`, `IReadOnlyList<ILabelled>`) crosses as one handle
per element, exactly like an ordinary handle-typed return: each element wrapper needs disposing
(`close()`/`use`) the same as any other bound object, independently of the list itself.

```kotlin
roster.tags().forEach { tag -> tag.use { println(it.label) } }
```

A tag wrapper you never close is never collected until the .NET GC finalizes the underlying C#
object, the same collectability shape as any other undisposed handle (see
[Objects and handles](objects-and-handles.md)).

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="instance-members.md">Instance members</a>
        <a href="collections.md">Collections (forward)</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/155-csharp-collections-in-kotlin.md">ADR-155: C# collections in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/011-collection-type-mapping.md">ADR-011: Collection type mapping (forward)</a>
    </category>
</seealso>
