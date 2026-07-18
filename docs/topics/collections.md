# Collections

Kotlin's collection types cross the bridge as an opaque handle plus a small accessor surface (`Count`/`Get`/`ContainsKey`/...), then get eagerly copied into a real .NET collection on the C# side. There's no lazy bridging or lasting connection between the returned C# collection and the Kotlin one after the copy.

| Kotlin | C# | Notes |
|---|---|---|
| `List<T>` | `IReadOnlyList<T>` | eager copy via opaque handle |
| `MutableList<T>` | `IList<T>` | eager copy |
| `Map<K,V>` | `IReadOnlyDictionary<K,V>` | eager copy |
| `MutableMap<K,V>` | `IDictionary<K,V>` | eager copy |
| `Set<T>` | `IReadOnlySet<T>` | eager copy |
| `MutableSet<T>` | `ISet<T>` | eager copy |

## Kotlin

From `test-library/src/nativeMain/kotlin/.../cat/Cat.kt`:

```kotlin
val nicknames: List<String> = listOf("${name}y", "Little $name")
val toys: List<Toy> = listOf(Toy("Mouse", "Gray"), Toy("Ball", "Red"))
val favoriteFoods: MutableList<String> = mutableListOf("Tuna", "Salmon")
val accessories: Map<String, Toy> = mapOf(
  "collar" to Toy("Bell Collar", "Gold"),
  "tag" to Toy("Name Tag", "Silver"),
)
val traits: Set<String> = setOf("Playful", "Curious", "Fluffy")
val vaccinations: MutableSet<String> = mutableSetOf("Rabies", "FVRCP")
val schedule: MutableMap<String, String> = mutableMapOf(
  "morning" to "Nap",
  "evening" to "Play",
)
```

## Generated C#

Every collection-typed property follows the same shape: fetch an opaque `listHandle`/`mapHandle`/`setHandle` from Kotlin, walk it with `Count`/`Get`/`KeyAt`/`ValueAt`/`ElementAt`, copy into a real .NET collection, then dispose the Kotlin-side handle.

```C#
public IReadOnlyList<string> Nicknames
{
    get
    {
        IntPtr listHandle = Native_Get_nicknames(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        int count = NugetListNative.Count(listHandle);
        var result = new List<string>(count);
        for (int i = 0; i < count; i++)
        {
            result.Add(NugetMarshal.FromHandle<string>(NugetListNative.Get(listHandle, i)));
        }
        NugetListNative.Dispose(listHandle);
        return result.AsReadOnly();
    }
}

public IList<string> FavoriteFoods
{
    get
    {
        /* same walk as Nicknames, but no .AsReadOnly(), returns the mutable List<string> directly */
    }
}

public IReadOnlyDictionary<string, Toy> Accessories
{
    get
    {
        IntPtr mapHandle = Native_Get_accessories(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        int count = NugetMapNative.Count(mapHandle);
        var result = new Dictionary<string, Toy>(count);
        for (int i = 0; i < count; i++)
        {
            var key = NugetMarshal.FromHandle<string>(NugetMapNative.KeyAt(mapHandle, i));
            var value = NugetMarshal.FromHandle<Toy>(NugetMapNative.ValueAt(mapHandle, i));
            result[key] = value;
        }
        NugetMapNative.Dispose(mapHandle);
        return result;
    }
}

public IReadOnlySet<string> Traits
{
    get
    {
        IntPtr setHandle = Native_Get_traits(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        int count = NugetSetNative.Count(setHandle);
        var result = new HashSet<string>(count);
        for (int i = 0; i < count; i++)
        {
            result.Add(NugetMarshal.FromHandle<string>(NugetSetNative.ElementAt(setHandle, i)));
        }
        NugetSetNative.Dispose(setHandle);
        return result;
    }
}
```

`MutableMap<K,V>` (`Schedule`) and `MutableSet<T>` (`Vaccinations`) follow the same walk, exposed as `IDictionary<K,V>`/`ISet<T>` instead of the read-only interfaces.

## Using it from C#

`List<T>`, from `IntegrationTests/ListTests.cs`:

```C#
[Fact]
public void Cat_Nicknames_ListEquality()
{
    using var cat = new Cat("Oreo", 9);
    IReadOnlyList<string> nicknames = cat.Nicknames;
    Assert.Equal(new List<string> { "Oreoy", "Little Oreo" }, nicknames);
}
```

`MutableList<T>`, from `IntegrationTests/MutableListTests.cs`:

```C#
[Fact]
public void Cat_FavoriteFoods_IsMutable()
{
    using var cat = new Cat("Oreo", 9);
    IList<string> foods = cat.FavoriteFoods;
    foods.Add("Chicken");
    Assert.Equal(3, foods.Count);
    Assert.Equal("Chicken", foods[2]);
}
```

Mutating the returned `IList<string>` only changes the C#-side copy. It does not write back to the Kotlin `Cat` instance, since the collection was eagerly copied at the moment of the property access.

`Map<K,V>`, from `IntegrationTests/MapTests.cs`:

```C#
[Fact]
public void Cat_Accessories_GetByKey()
{
    using var cat = new Cat("Oreo", 9);
    IReadOnlyDictionary<string, Toy> accessories = cat.Accessories;
    using var collar = accessories["collar"];
    Assert.Equal("Bell Collar", collar.Name);
    Assert.Equal("Gold", collar.Color);
}
```

`Set<T>`, from `IntegrationTests/SetTests.cs`:

```C#
[Fact]
public void Cat_Traits_SetEquality()
{
    using var cat = new Cat("Oreo", 9);
    IReadOnlySet<string> traits = cat.Traits;
    var expected = new HashSet<string> { "Fluffy", "Playful", "Curious" };
    Assert.True(traits.SetEquals(expected));
}
```

## Returned from a method or extension function

`List<T>` also marshals as a class-method or extension-function return, not only as a property.
The Kotlin side needs no separate export shape: the list is boxed as the same object carrier an
object return uses, and the shared `NugetListNative` helpers materialize it exactly as they do for
a property (see [Method returns](classes-and-objects.md) and [ADR-061](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md)):

```kotlin
// test-library/.../cat/Cat.kt
fun tags(): List<String> = listOf("$name-tag", "$name-chip")
fun scores(): List<Int> = listOf(lives, lives * 2)
```

```C#
public IReadOnlyList<string> Tags()
{
        IntPtr listHandle = Native_Tags(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        int count = NugetListNative.Count(listHandle);
        var result = new List<string>(count);
        for (int i = 0; i < count; i++)
        {
            result.Add(NugetMarshal.FromHandle<string>(NugetListNative.Get(listHandle, i)));
        }
        NugetListNative.Dispose(listHandle);
        return result.AsReadOnly();
}
```

## Limitations

- `Sequence<T>` is not bridgeable. `Cat.unsupported: Sequence<String>` in the sample library is deliberately left out of the generated `Interop.cs` (no eager-copy story for a lazy sequence).
- A collection only survives at positions that route through the opaque-handle path — the non-null list *property* shown above (`Cat.nicknames`) is one. At any other site the generated Kotlin drops the element type and renders a raw `List` (invalid Kotlin, *one type argument expected*): a `List<T>` in a data-class constructor, in a generated `copy()`, or a **nullable** list property (`List<T>?`) all break today. Tracked in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 4 and pinned as red cells by the adversarial forward fixture ([ADR-060](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/060-adversarial-forward-fixture.md)).

<seealso>
    <category ref="related">
        <a href="generics.md">Generics</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/011-collection-type-mapping.md">ADR-011: Collection type mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md">ADR-061: Method return marshalling</a>
    </category>
</seealso>
