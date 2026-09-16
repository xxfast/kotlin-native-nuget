# Collections

A Kotlin collection is eagerly copied into a real .NET collection when it crosses the bridge.
There is no lazy bridging and no lasting connection to the Kotlin side afterward: mutating the C#
collection you get back never writes back to Kotlin.

| Kotlin | C# |
|---|---|
| `List<T>` | `IReadOnlyList<T>` |
| `MutableList<T>` | `IList<T>` |
| `Map<K,V>` | `IReadOnlyDictionary<K,V>` |
| `MutableMap<K,V>` | `IDictionary<K,V>` |
| `Set<T>` | `IReadOnlySet<T>` |
| `MutableSet<T>` | `ISet<T>` |

```kotlin
val nicknames: List<String> = listOf("${name}y", "Little $name")
val favoriteFoods: MutableList<String> = mutableListOf("Tuna", "Salmon")
```

```C#
using var cat = new Cat("Oreo", 9);

IReadOnlyList<string> nicknames = cat.Nicknames;

IList<string> foods = cat.FavoriteFoods;
foods.Add("Chicken"); // only changes this C# copy; Cat's own list is untouched
```

A `var`-declared collection property can still be reassigned: `cat.FavoriteFoods = new List<string>
{ "Chicken" }` replaces the whole collection on the Kotlin side. See
[Mutable collection properties](#mutable-collection-properties) for what element types support this.

## Method parameters and returns

`List`, `Map`, `Set`, and their mutable variants marshal the same way as class-method, top-level
function, extension-function, and constructor parameters and return values, not only as properties.

```kotlin
class Patient(val name: String) {
  fun recordScores(scores: Map<String, Int>): Int = scores.values.sum()

  fun tallyScores(scores: MutableMap<String, Int>): Int {
    scores["total"] = scores.values.sum()
    return scores.size
  }
}
```

```C#
using var oreo = new Patient("Oreo");

int total = oreo.RecordScores(new Dictionary<string, int> { ["agility"] = 3, ["cuddles"] = 4 }); // 7

var scores = new Dictionary<string, int> { ["agility"] = 3 };
int tally = oreo.TallyScores(scores); // 2, scores.ContainsKey("total") is false
```

A `MutableMap`/`MutableSet` parameter does not write back: Kotlin gets its own copy, and changes it
makes inside the function are not reflected in the `Dictionary`/`HashSet` you passed. This matches
`MutableList`'s existing input behavior.

A plain `Dictionary`/`HashSet` is directly assignable at every parameter kind, but a bare
`IDictionary<K,V>` cannot be passed to a `Map<K,V>` parameter (it needs `IReadOnlyDictionary`), and a
bare `IReadOnlyDictionary<K,V>` cannot be passed to a `MutableMap<K,V>` parameter (it needs
`IDictionary`). The same asymmetry applies to `Set`/`ISet`/`IReadOnlySet`.

## Nullable collection references

A `List`/`Map`/`Set` property or parameter can itself be nullable (`List<String>?`), independent of
whether its elements are. A `null` Kotlin value comes back as C# `null`, never an empty collection.

```kotlin
data class Visit(
  val patient: String,
  val symptoms: List<String>,
  val notes: List<String>? = null,
)
```

```C#
using var visit = new Visit("Mylo", new List<string> { "sneezing" }, null);

Assert.Null(visit.Notes);
```

A class method or extension function can return a nullable collection the same way (`fun ids():
List<ChartId>?`); the C# return type carries the `?` and `null` means the same thing it does above,
never an empty collection:

```C#
public IReadOnlyList<global::TestLibrary.Clinic.ChartId>? Ids()
```

This does not extend to a `suspend fun` or a `Flow`/`StateFlow` returning a nullable collection;
those stay unsupported. See [Coroutines and Flow](coroutines-and-flow.md).

## Mutable collection properties {id="mutable-collection-properties"}

A `var`-declared collection property always gets a getter. It gets a setter only when every
component (the element for `List`/`Set`, the key **and** value for `Map`) is one of the supported
[collection component types](#collection-component-types) below. If a component isn't supported
(today: a nullable nested collection, or an interface), the property still generates, but read-only,
and the compiler emits a `SKIPPED_UNSUPPORTED_INPUT` diagnostic naming the property.

```kotlin
class Chart(val patientName: String) {
  var tags: List<String> = emptyList()
  var seen: MutableList<Nurse> = mutableListOf()
}
```

```C#
using var chart = new Chart("Oreo");

chart.Tags = new List<string> { "black", "white", "biscuit" };
Assert.Equal(new[] { "black", "white", "biscuit" }, chart.Tags);
```

The getter always returns a detached copy, the same as any other collection return: mutating
`chart.Seen` in place does not reach Kotlin. Read, extend on the C# side, and reassign to get the
change across:

```C#
var attending = new List<Nurse>(chart.Seen) { barton };
chart.Seen = attending;
```

The same eligibility rule applies to a collection-typed extension property, including one whose
receiver crosses by value (a `String`- or object-underlying value class) rather than by object
handle.

## Collection component types

A `List`/`Map`/`Set` element, map key, or map value supports: `String`; the wide primitives (`Int`,
`Long`, `Float`, `Double`, `Boolean`); the narrow primitives and `Char`
(`Byte`/`UByte`/`Short`/`UShort`/`UInt`/`ULong`, see below); an object handle (a class instance); a
bare enum via its `int` ordinal (see [Enums](enums.md#as-a-collection-component)); a value class over
any of the above underlyings (see [Value classes](value-classes.md#as-a-collection-component)); a
sealed base, which boxes as an object handle the same way a concrete class does (see
[Interfaces, abstract classes, and sealed classes](interfaces-abstract-sealed.md#a-sealed-type-at-a-parameter-position));
and a `List`/`Map`/`Set` itself, nested at arbitrary depth. This applies uniformly to parameters,
method and property returns, and [collection property setters](#mutable-collection-properties).

A nullable spelling of any of those (`Map<String, Int?>`, `Set<String?>`, `List<Mood?>`,
`List<Short?>`, `List<List<String?>>`) is also supported: a `null` element, set member, or map value
rides a null pointer in that component's slot, both reading and writing.

Two shapes are not supported and fail with a named `SKIPPED_UNSUPPORTED_INPUT` diagnostic rather than
binding incorrectly: a **nullable nested collection** (`List<List<String>?>`), and a plain interface
component. A nullable map **key** (`Map<String?, Int>`) is also unsupported at a parameter position,
since a C# `Dictionary` can't hold a null key.

### Narrow primitives and Char as collection components {id="narrow-primitives-and-char-as-collection-components"}

The six narrow primitives and `Char` bind as a `List`/`Map`/`Set` element, key, or value the same way
the wide primitives do:

```kotlin
class Readings {
  fun chart(samples: List<Short>): String = samples.joinToString(",")
  fun census(byGrade: Map<UByte, Short>): String =
    byGrade.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
  fun marks(): List<Char> = listOf('é', '日')
}
```

```C#
using var readings = new Readings();

string chart = readings.Chart(new short[] { 1, -2, short.MaxValue }); // "1,-2,32767"

IReadOnlyList<char> marks = readings.Marks(); // ['é', '日']
```

See [Primitives and strings: Char](primitives-and-strings.md#char) for `Char`'s behavior outside
collections, including why a standalone `Char?` is unsupported.

### Nested collections

A `List<List<String>>`-shaped parameter, return, or property works at any depth and for any
combination of outer/inner kind (`Set<List<T>>`, `Map<K, List<V>>`, and so on). The inner collection
crosses as its own handle, in the same component slot every other element type uses.

```kotlin
class WardBoard {
  fun logGrid(rows: List<List<String>>): String = rows.joinToString(";") { it.joinToString(",") }
  fun grid(): List<List<String>> = listOf(listOf("oreo", "mylo"), listOf("biscuit"))
}
```

```C#
using var board = new WardBoard();

IReadOnlyList<IReadOnlyList<string>> grid = board.Grid();
Assert.Equal(new[] { "oreo", "mylo" }, grid[0]);

string logged = board.LogGrid(new[] { new[] { "oreo", "mylo" }, new[] { "biscuit" } }); // "oreo,mylo;biscuit"
```

## Exception safety on collection parameters and returns {id="exception-safety-on-collection-parameters-and-returns"}

A temporary handle built for a collection parameter is released on every exit path, including when
the Kotlin callee throws. If enumerating your own `IEnumerable` argument throws partway through (for
example, a custom collection that fails mid-`MoveNext`), the partially built native handle is
disposed and your original exception surfaces unmasked, not wrapped or replaced by a Kotlin-side
error.

The read side is symmetric: if materializing a returned collection's elements throws partway through
(an element factory failure), the collection's own handle is still disposed and every element wrapper
already built before the throw is disposed too. You don't need to add cleanup code for either
direction; both are handled by the generated bridge code.

## Limitations

- `Sequence<T>` does not cross the bridge; there is no eager-copy story for a lazy sequence. It is
  left out of the generated C# entirely.
- `MutableMap`/`MutableSet` parameters (and `MutableList`) don't write back; see
  [Method parameters and returns](#method-parameters-and-returns).
- A collection property setter is narrower than its getter; see
  [Mutable collection properties](#mutable-collection-properties).

<seealso>
    <category ref="related">
        <a href="generics.md">Generics</a>
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="value-classes.md">Value classes</a>
        <a href="enums.md">Enums</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/011-collection-type-mapping.md">ADR-011: Collection type mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md">ADR-061: Method return marshalling</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/073-map-and-set-parameters.md">ADR-073: Map/Set parameters</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/075-collection-property-getter-setter-independence.md">ADR-075: Collection property getter/setter independence</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/081-value-class-collection-components.md">ADR-081: Value-class collection components</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/083-nullable-collection-components.md">ADR-083: Nullable collection components</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/097-enum-collection-components.md">ADR-097: Enum collection components</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md">ADR-098: Narrow-primitive and Char collection components</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/099-nested-collection-components.md">ADR-099: Nested collection components</a>
    </category>
</seealso>
