# Extensions

Kotlin extension functions and properties don't have a native C# analog (C# has extension methods but not extension properties), so both map to static methods, grouped by source file the same way as any other top-level declaration (see [Top-level declarations](top-level-declarations.md)). Extension functions render as true C# extension methods (`this` parameter); extension properties render as ordinary static getter methods, since C# can't declare an extension property.

| Kotlin | C# | Notes |
|---|---|---|
| extension function | static method | true C# extension method (`this` parameter) |
| extension property | static accessor | see [ADR-013](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/013-extension-property-mapping.md) |

## Kotlin

Extension functions on `String`, from `sample-library/src/nativeMain/kotlin/.../StringExtensions.kt`:

```kotlin
fun String.meowify(): String = "$this meow!"
fun String.isPurring(): Boolean = lowercase().contains("purr")

val String.wordCount: Int get() = trim().split("\\s+".toRegex()).size
```

Extension functions and properties on `Cat`, from `sample-library/src/nativeMain/kotlin/.../cat/CatExtensions.kt`:

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

Extension functions, from `sample-app/SampleApp.Tests/ExtensionFunctionTests.cs`:

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

Extension properties, from `sample-app/SampleApp.Tests/ExtensionPropertyTests.cs`, called as a method, not a property, on the C# side:

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

## See also

- [Top-level declarations](top-level-declarations.md)
- [ADR-013: Extension property mapping](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/013-extension-property-mapping.md)
