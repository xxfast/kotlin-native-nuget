# Objects and companions

A Kotlin `object` singleton becomes a static C# class: no instance, no constructor, just static members reached directly through the type. A `data object` nested inside a `sealed class` hierarchy becomes a sealed subclass instead (see [Interfaces, abstract and sealed classes](interfaces-abstract-sealed.md)). A `companion object`'s members land as static members on the enclosing C# class.

| Kotlin | C# | Notes |
|---|---|---|
| `object` | `static class` | singleton |
| `data object` (in `sealed class`) | sealed subclass | with `ToString` |
| companion object | static members | |

## Kotlin

A top-level singleton, from `test-library/src/nativeMain/kotlin/.../cat/CatRegistry.kt`:

```kotlin
object CatRegistry {
  private val cats: MutableList<String> = mutableListOf()

  fun register(name: String) {
    cats.add(name)
  }

  fun count(): Int = cats.size

  fun clear() {
    cats.clear()
  }
}
```

A companion object, from `test-library/src/nativeMain/kotlin/.../cat/Cat.kt`:

```kotlin
class Cat(
  name: String,
  val lives: Int = 9,
) : Animal(name) {
  // ...
  companion object {
    const val SPECIES: String = "Felis catus"
    val defaultBreed: String = "Domestic Shorthair"
    fun fromName(name: String): Cat = Cat(name)
  }
}
```

## Generated C#

`CatRegistry` renders as a `static class` with no handle at all. Every member is a direct `[DllImport]`:

```C#
public static class CatRegistry
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catregistry_register_")]
    public static extern void register(string name);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catregistry_count")]
    public static extern int count();

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catregistry_clear")]
    public static extern void clear();
}
```

The `Cat` companion's members are generated as static members directly on the `Cat` class itself (`Cat.Species`, `Cat.DefaultBreed`, `Cat.FromName(...)`) rather than a nested type. There's no separate `Cat.Companion` class in the generated output.

## Using it from C#

Singleton object, from `IntegrationTests/ObjectTests_Singleton.cs`:

```C#
[Fact]
public void CatRegistry_IsStaticClass()
{
    Assert.True(typeof(CatRegistry).IsAbstract && typeof(CatRegistry).IsSealed);
}

[Fact]
public void CatRegistry_RegisterAndCount()
{
    CatRegistry.clear();
    CatRegistry.register("Oreo");
    CatRegistry.register("Mylo");
    Assert.Equal(2, CatRegistry.count());
    CatRegistry.clear();
}
```

Companion object, from `IntegrationTests/CompanionObjectTests.cs`:

```C#
[Fact]
public void CompanionConstVal()
{
    Assert.Equal("Felis catus", Cat.Species);
}

[Fact]
public void CompanionProperty()
{
    Assert.Equal("Domestic Shorthair", Cat.DefaultBreed);
}

[Fact]
public void CompanionFactoryMethod()
{
    using var cat = Cat.FromName("Whiskers");
    Assert.Equal("Whiskers", cat.Name);
}
```

<seealso>
    <category ref="related">
        <a href="interfaces-abstract-sealed.md">Interfaces, abstract and sealed classes</a>
        <a href="top-level-declarations.md">Top-level declarations</a>
    </category>
</seealso>
