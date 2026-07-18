# Extensions

Kotlin extension functions and properties don't have a native C# analog (C# has extension methods but not extension properties), so both map to static methods, grouped by source file the same way as any other top-level declaration (see [Top-level declarations](top-level-declarations.md)). Extension functions render as true C# extension methods (`this` parameter); extension properties render as ordinary static getter methods, since C# can't declare an extension property.

| Kotlin | C# | Notes |
|---|---|---|
| extension function | static method | true C# extension method (`this` parameter) |
| extension property | static accessor | see [ADR-013](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/013-extension-property-mapping.md) |
| extension function return (object, `T?`, `List<T>`, `String?`, `Int?`) | matching C# return type | same cascade as a class-method return, see Return marshalling below and [Classes and objects](classes-and-objects.md) |

## Kotlin

Extension functions on `String`, from `test-library/src/nativeMain/kotlin/.../StringExtensions.kt`:

```kotlin
fun String.meowify(): String = "$this meow!"
fun String.isPurring(): Boolean = lowercase().contains("purr")

val String.wordCount: Int get() = trim().split("\\s+".toRegex()).size
```

Extension functions and properties on `Cat`, from `test-library/src/nativeMain/kotlin/.../cat/CatExtensions.kt`:

```kotlin
fun Cat.sayName(): String = "My name is ${this.name}"
fun Cat.greetWith(greeting: String): String = "$greeting, ${this.name}!"

val Cat.isKitten: Boolean get() = lives > 7
val Cat.label: String get() = "${name} (${mood.name.lowercase()})"
```

## Generated C#

From `Interop.cs`, `CatExtensions` static class. `SayName` is a genuine C# extension method (`this Cat cat`); `GetIsKitten` is the extension-property accessor, named with a `Get` prefix since C# has no extension-property syntax:

```C#
public static partial class CatExtensions
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_sayName")]
    private static extern IntPtr Native_SayName(IntPtr handle);

    public static string SayName(this Cat cat)
        => Marshal.PtrToStringUTF8(Native_SayName(cat._handle))!;

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_greetWith")]
    private static extern IntPtr Native_GreetWith(IntPtr handle, string greeting);

    public static string GreetWith(this Cat cat, string greeting)
        => Marshal.PtrToStringUTF8(Native_GreetWith(cat._handle, greeting))!;

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_get_isKitten")]
    private static extern bool Native_GetIsKitten(IntPtr handle, out IntPtr error);

    public static bool GetIsKitten(this Cat cat)
    {
        bool result = Native_GetIsKitten(cat._handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return result;
    }
}
```

## Using it from C#

Extension functions, from `IntegrationTests/ExtensionFunctionTests.cs`:

```C#
[Fact]
public void String_Meowify_AppendsMeow()
{
    Assert.Equal("Oreo meow!", "Oreo".Meowify());
}

[Fact]
public void Cat_SayName()
{
    using var cat = new Cat("Oreo", 9);
    Assert.Equal("My name is Oreo", cat.SayName());
}

[Fact]
public void Cat_GreetWith()
{
    using var cat = new Cat("Oreo", 9);
    Assert.Equal("Hello, Oreo!", cat.GreetWith("Hello"));
}
```

Extension properties, from `IntegrationTests/ExtensionPropertyTests.cs`, called as a method, not a property, on the C# side:

```C#
[Fact]
public void Cat_GetIsKitten_ReturnsTrueForNewCatWithNineLives()
{
    using var cat = new Cat("Oreo", 9);
    Assert.True(cat.GetIsKitten());
}

[Fact]
public void Cat_GetLabel_ReturnsNameWithMood()
{
    using var cat = new Cat("Oreo", 9);
    Assert.Equal("Oreo (sleepy)", cat.GetLabel());
}

[Fact]
public void String_GetWordCount_ReturnsTwoForTwoWords()
{
    Assert.Equal(2, "hello world".GetWordCount());
}
```

## Return marshalling

An extension function's return goes through the same marshalling cascade a class-method return
does (see [Method returns](classes-and-objects.md) in Classes and objects): object, nullable
object, collection, nullable `String`, and nullable primitive (single call, `bool` has-value +
`valueOut` out-parameter, not the property getter's two-call pattern).

From `test-library/src/nativeMain/kotlin/.../cat/CatExtensions.kt`. The receiver is `Toy`, not
`Cat`: an extension can't be distinguished from a member of the same name on the same receiver, so
exercising the extension-function export path needs a receiver with no colliding member:

```kotlin
/** Object return (converting), extension-function position. Always non-null. */
fun Toy.findOwner(): Cat = Cat(name, color.length)

/** Nullable object return, extension-function position. Non-null only for the "Gray" toys. */
fun Toy.maybeOwner(): Cat? = if (color == "Gray") Cat(name, name.length) else null

/** Collection return, converting element, extension-function position. */
fun Toy.tags(): List<String> = listOf("$name-tag", "$color-tag")

/** Collection return, non-converting element, extension-function position. */
fun Toy.scores(): List<Int> = listOf(name.length, color.length)

/** Nullable String return, extension-function position. Non-null only for the "Gray" toys. */
fun Toy.alias(): String? = if (color == "Gray") "$name (aka Grey Ghost)" else null

/** Nullable primitive return, extension-function position. Non-null only for the "Gray" toys. */
fun Toy.ageInMonths(): Int? = if (color == "Gray") name.length * 12 else null
```

Generated C#, from `Interop.cs`, `ToyExtensions`:

```C#
public static Cat FindOwner(this Toy toy)
{
    IntPtr nativeResult = Native_FindOwner(toy._handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return new Cat(nativeResult);
}

public static Cat? MaybeOwner(this Toy toy)
{
    IntPtr nativeResult = Native_MaybeOwner(toy._handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return nativeResult == IntPtr.Zero ? null : new Cat(nativeResult);
}

public static int? AgeInMonths(this Toy toy)
{
    bool hasValue = Native_AgeInMonths(toy._handle, out int value, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return hasValue ? value : null;
}
```

From `IntegrationTests/MethodReturnMarshallingTests.cs`:

```C#
[Fact]
public void Toy_MaybeOwner_NonNullForGrayToy()
{
    var mouse = new Toy("Mouse", "Gray");
    using Cat? owner = mouse.MaybeOwner();
    Assert.NotNull(owner);
    Assert.Equal("Mouse", owner!.Name);
}

[Fact]
public void Toy_Tags_ReturnsMarshalledStringElements()
{
    var mouse = new Toy("Mouse", "Gray");
    IReadOnlyList<string> tags = mouse.Tags();
    Assert.Equal(new List<string> { "Mouse-tag", "Gray-tag" }, tags);
}
```

<seealso>
    <category ref="related">
        <a href="top-level-declarations.md">Top-level declarations</a>
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="collections.md">Collections</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/013-extension-property-mapping.md">ADR-013: Extension property mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md">ADR-061: Method return marshalling</a>
    </category>
</seealso>
