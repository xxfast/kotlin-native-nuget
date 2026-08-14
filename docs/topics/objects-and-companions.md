# Objects and companions

A Kotlin `object` singleton becomes a static C# class: no instance, no constructor, just static members reached directly through the type. A `data object` nested inside a `sealed class` hierarchy becomes a sealed subclass instead (see [Interfaces, abstract and sealed classes](interfaces-abstract-sealed.md)). A `companion object`'s members land as static members on the enclosing C# class.

| Kotlin | C# | Notes |
|---|---|---|
| `object` | `static class` | singleton; methods are PascalCased and their returns marshalled, exactly like a class method |
| `data object` (in `sealed class`) | sealed subclass | with `ToString` |
| companion object | static members | |
| two or more same-named `object`/companion members | one C# overload set | numbered native export/extern name, unnumbered public name; see [Method overloads](#method-overloads) below ([ADR-095](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md)) |

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

## Method overloads

Two or more same-named members on an `object` or a `companion object` generate one natural C#
overload set, the same [ADR-090](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/090-ordinary-class-method-overloads.md)
template a class method uses (see [Method overloads](classes-and-objects.md#method-overloads) in
Classes and objects), extended to these routes by [ADR-095](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md).
Numbering is per object and per companion, independently of any other container.

### Kotlin {id="overloads-kotlin"}

From `test-library/src/nativeMain/kotlin/.../grooming/GroomingSample.kt`:

```kotlin
object Parlour {
  fun describe(): String = "the parlour is open"

  fun describe(cat: String): String = "$cat is booked in"

  fun rate(stars: Int): String = "the parlour is rated $stars"

  fun rate(coat: Coat): String = "the parlour grooms ${coat.name.lowercase()} coats"
}

class Groomer(val name: String) {
  companion object {
    fun of(name: String): Groomer = Groomer(name)

    fun of(chairs: Int): Groomer = Groomer("groomer of $chairs chairs")

    fun of(coat: Coat): Groomer = Groomer("${coat.name.lowercase()} groomer")
  }
}
```

`rate(Int)`/`rate(Coat)` and `of(Int)`/`of(Coat)` each pair a plain `Int` with an enum: both cross
the C ABI as `int`, the shape that forces the private extern *name* itself to carry the number, not
just the `DllImport` `EntryPoint`.

### Generated C# {id="overloads-generated-c"}

From `Interop.cs`. The object route numbers on `${prefix}_${name}_$n`; the companion route inserts
`_companion_` before the name:

```C#
public static class Parlour
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "parlour_describe")]
    private static extern IntPtr Native_Describe(out IntPtr error);

    public static string Describe() { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "parlour_describe_2")]
    private static extern IntPtr Native_Describe_2([MarshalAs(UnmanagedType.LPUTF8Str)] string cat, out IntPtr error);

    public static string Describe(string cat) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "parlour_rate")]
    private static extern IntPtr Native_Rate(int stars, out IntPtr error);

    public static string Rate(int stars) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "parlour_rate_2")]
    private static extern IntPtr Native_Rate_2(int coat, out IntPtr error);

    public static string Rate(global::TestLibrary.Grooming.Coat coat) { /* ... */ }
}
```

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "groomer_companion_of")]
private static extern IntPtr Native_Companion_Of([MarshalAs(UnmanagedType.LPUTF8Str)] string name, out IntPtr error);

public static Groomer Of(string name) { /* ... */ }

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "groomer_companion_of_2")]
private static extern IntPtr Native_Companion_Of_2(int chairs, out IntPtr error);

public static Groomer Of(int chairs) { /* ... */ }

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "groomer_companion_of_3")]
private static extern IntPtr Native_Companion_Of_3(int coat, out IntPtr error);

public static Groomer Of(global::TestLibrary.Grooming.Coat coat) { /* ... */ }
```

### Using it from C# {id="overloads-using-it-from-c"}

From `IntegrationTests/StaticRouteOverloadTests.cs`:

```C#
[Fact]
public void ParlourRate_IntAndCoat_ShareOneWireShapeAndStayDistinct()
{
    Assert.Equal("the parlour is rated 10", Parlour.Rate(10));
    Assert.Equal("the parlour grooms tuxedo coats", Parlour.Rate(Coat.Tuxedo));
}

[Fact]
public void GroomerOf_WithCoat_DispatchesToEnumOverload()
{
    using var groomer = Groomer.Of(Coat.Tabby);
    Assert.Equal("tabby groomer", groomer.Name);
}
```

<note>
    <p>
        A companion static and an instance method on the same class share one generated C# class,
        and C# does not distinguish an overload by <code>static</code>-ness, so the
        <code>ERROR_CSHARP_SIGNATURE_COLLISION</code> check (see
        <a href="classes-and-objects.md#method-overloads">Method overloads</a> in Classes and
        objects) compares a companion static against the owning class's planned instance methods
        too, not just against its own companion siblings.
    </p>
</note>

<seealso>
    <category ref="related">
        <a href="interfaces-abstract-sealed.md">Interfaces, abstract and sealed classes</a>
        <a href="top-level-declarations.md">Top-level declarations</a>
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="expect-actual.md">expect/actual declarations</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/090-ordinary-class-method-overloads.md">ADR-090: Ordinary-class method overloads</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md">ADR-095: Overloads on the four static export routes</a>
    </category>
</seealso>
