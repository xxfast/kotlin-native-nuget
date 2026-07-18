# Objects and companions

A Kotlin `object` singleton becomes a static C# class: no instance, no constructor, just static members reached directly through the type. A `data object` nested inside a `sealed class` hierarchy becomes a sealed subclass instead (see [Interfaces, abstract and sealed classes](interfaces-abstract-sealed.md)). A `companion object`'s members land as static members on the enclosing C# class.

| Kotlin | C# | Notes |
|---|---|---|
| `object` | `static class` | singleton; methods are PascalCased and their returns marshalled, exactly like a class method |
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

`CatRegistry` renders as a `static class` with no handle at all. Each method is PascalCased and routes through the same static-function marshalling as a top-level function: a private `[DllImport]` extern plus a public wrapper that checks the error out-parameter across the bridge.

```C#
public static class CatRegistry
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catregistry_register_")]
    private static extern void Register_native(string name, out IntPtr error);

    public static void Register(string name)
    {
        Register_native(name, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catregistry_count")]
    private static extern int Count_native(out IntPtr error);

    public static int Count()
    {
        int result = Count_native(out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return result;
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catregistry_clear")]
    private static extern void Clear_native(out IntPtr error);

    public static void Clear()
    {
        Clear_native(out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
    }

}
```

A non-primitive return is marshalled to its idiomatic C# type, exactly like a class method — the hidden `IntPtr` and `Marshal.PtrToStringUTF8` live on the generated side, never the consumer's. The `Clinic` object (`test-library/src/nativeMain/kotlin/.../clinic/ClinicSample.kt`) has a `String`-returning method:

```kotlin
object Clinic {
  fun greet(name: String): String = "Welcome to the clinic, $name"
  fun capacity(): Int = 12
  fun reset() {}
}
```

`greet` surfaces as a PascalCased `Greet` returning a real `string`:

```C#
public static class Clinic
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "clinic_greet")]
    private static extern IntPtr Greet_native(string name, out IntPtr error);

    public static string Greet(string name)
    {
        IntPtr nativeResult = Greet_native(name, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return Marshal.PtrToStringUTF8(nativeResult)!;
    }

    // Capacity() and Reset() follow the same wrapper pattern as CatRegistry above.
}
```

Object methods thus match class and companion methods on both facets: PascalCased names and marshalled returns (completing [ADR-060](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/060-adversarial-forward-fixture.md) cells 1 and 25; naming follows [ADR-007](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/007-top-level-function-class-naming.md)).

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
    CatRegistry.Clear();
    CatRegistry.Register("Oreo");
    CatRegistry.Register("Mylo");
    Assert.Equal(2, CatRegistry.Count());
    CatRegistry.Clear();
}
```

An object method with a non-primitive return is called just like any other marshalled member — no `Marshal.PtrToStringUTF8` at the call site. From `IntegrationTests/ObjectMethodMarshallingTests.cs`:

```C#
[Fact]
public void Clinic_Greet_ReturnsMarshalledString()
{
    string greeting = Clinic.Greet("Bob");
    Assert.Equal("Welcome to the clinic, Bob", greeting);
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
