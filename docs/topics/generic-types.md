# Generic types

A C# generic class reached through at least one closed instantiation (`Box<int>`,
`Pairing<string, int>`) becomes a real Kotlin generic class, `Box<T>`, not a monomorphized family
like `BoxOfInt`. This is
[ADR-072](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/072-closed-constructed-generics-in-kotlin.md).

```C#
public class Box<T>
{
    public Box(T value) { Value = value; }
    public T Value { get; }
    public string Describe() => $"box[{Value}]";
}
```

```kotlin
fun boxOfIntValue(value: Int): Int = Box(value).value

fun boxOfIntDescribe(value: Int): String = Box(value).describe()
```

A generated instance is a handle wrapper like any other bound C# object: `Box<Int>` implements
`AutoCloseable` and frees itself when unreachable, so `use { }` works the same way.
See [Objects and handles](objects-and-handles.md) for the lifetime rules.

```kotlin
fun boxLifetimeUse(value: Int): Int = Box(value).use { it.value }
```

Members of the generic class, including one that never mentions the type parameter (`describe()`
above), keep working per instantiation, and the class stays polymorphic over `T`, which is the
whole point of not monomorphizing:

```kotlin
internal fun <T> describeAll(boxes: List<Box<T>>): List<String> = boxes.map { it.describe() }
```

## Construction

A public C# constructor becomes a fake top-level Kotlin constructor named like the type
(`Box(value)`), one overload per unambiguous instantiation. When two instantiations of the same
definition would erase to the same non-null Kotlin parameter list, such as `Box<String>` and
`Box<String?>` both erasing to `(String)`, **both** lose their fake constructor. This is a Gradle
build warning (`SKIPPED_AMBIGUOUS_GENERIC_CONSTRUCTOR`), not a `reverse-ir.json` diagnostic. An
instantiation with no fake constructor is still reachable through any bound factory or member that
returns it:

```C#
public static class Boxes
{
    public static Box<string> OfText(string value) => new(value);
}
```

```kotlin
fun boxOfTextUppercased(value: String): String = Boxes.ofText(value).value.uppercase()
```

A second type parameter carries its name straight from metadata:

```C#
public class Pairing<TKey, TValue>
{
    public Pairing(TKey key, TValue value) { Key = key; Value = value; }
    public TKey Key { get; }
    public TValue Value { get; }
}
```

```kotlin
fun tallyPairing(label: String, count: Int): String {
  val tally: Pairing<String, Int> = Boxes.tally(label, count)
  return "${tally.key}/${tally.value}"
}
```

## Limitations

- A type argument must be a primitive, `string` (nullable or not), a bound enum, or a bound class
  or interface handle. Anything else, including another generic instantiation, a struct, or a BCL
  generic such as `List<int>` or `Dictionary<string, int>`, excludes the whole instantiation
  (`SKIPPED_GENERIC_TYPE_ARGUMENT`, or `SKIPPED_UNBOUND_GENERIC_INSTANTIATION` for a BCL type whose
  definition lives outside the bound assemblies).
- No Kotlin type-parameter constraints are emitted: `where T : class` does not become `<T : Any>`.
  You can *write* `Box<Double>` in Kotlin even though no such instantiation is bound; there is no
  witness or factory for it, so the failure surfaces the first time you try to obtain one, at
  compile time.
- A bare type parameter annotated nullable (`T? Peek()`) is not representable per instantiation and
  is skipped (`SKIPPED_NULLABLE_TYPE_PARAMETER`); the rest of the class still binds.
- Only generic **classes** bind. Generic interfaces stay excluded (`SKIPPED_GENERIC_INTERFACE`; see
  [The bridgeable subset](bridgeable-subset.md)), and generic **methods** stay `SKIPPED_OPEN_GENERIC`
  permanently, unless a caller can pin the type argument. That includes `void Reset<T>()`, whose type
  parameter appears in no signature; a non-generic `Reset()` beside it still binds.
- A generic definition with zero discovered instantiations emits nothing at all: no Kotlin type, no
  registration, just an `INFO_UNINSTANTIATED_GENERIC_TYPE` note.

## See also

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
        <a href="generics.md">Generics</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/072-closed-constructed-generics-in-kotlin.md">ADR-072: Closed constructed generics from C# in Kotlin</a>
    </category>
</seealso>
