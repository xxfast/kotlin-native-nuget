# Top-level declarations

Kotlin top-level functions, properties, and `const val`s don't belong to any class, so the generator groups them by source file: every `.kt` file gets its own static C# class named after the file. This mirrors Kotlin's own `@file:JvmName` behaviour for Java interop, without the `Kt` suffix baggage, and only falls back to a `Kt` suffix when a class of the same name already exists in that file. See [ADR-007](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/007-top-level-function-class-naming.md).

| Kotlin | C# | Notes |
|---|---|---|
| top-level function | `static class` method | one static class per source file |
| top-level property | static property | get/set, including nullable |
| `const val` | `const` | |

## Kotlin

Top-level properties, from `sample-library/src/nativeMain/kotlin/.../Properties.kt`:

```kotlin
val catBreed: String = "Scottish Fold"
var catLives: Int = 9
var catNickname: String? = null
var catWeight: Double? = null
```

`const val`s, from `sample-library/src/nativeMain/kotlin/.../Constants.kt`:

```kotlin
const val MAX_LIVES: Int = 9
const val GREETING: String = "Hello, world!"
const val PI_APPROX: Double = 3.14
const val IS_DEBUG: Boolean = false
```

## Generated C#

`Properties.kt` becomes a `Properties` static class; each `val`/`var` becomes a static property with a getter (and setter for `var`):

```C#
public static partial class Properties
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "get_catBreed")]
    private static extern IntPtr Native_Get_catBreed(out IntPtr error);

    public static string CatBreed
    {
        get
        {
            IntPtr nativeResult = Native_Get_catBreed(out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
            return Marshal.PtrToStringUTF8(nativeResult)!;
        }
    }
    // CatLives, CatNickname, CatWeight follow the same shape, with a setter for var
}
```

`Constants.kt` becomes a `Constants` static class with genuine C# `const` fields, no bridge call at all. The value is baked into the generated source at build time since Kotlin `const val` is itself a compile-time constant.

Note the C# property names are `PascalCase` (`CatBreed`) even though the Kotlin source uses `camelCase` (`catBreed`), matching each language's own naming convention.

## Using it from C#

Top-level properties, from `sample-app/SampleApp.Tests/TopLevelPropertyTests.cs`:

```C#
[Fact]
public void GetStringVal()
{
    Assert.Equal("Scottish Fold", Properties.CatBreed);
}

[Fact]
public void SetAndGetNullableString()
{
    Properties.CatNickname = "Whiskers";
    Assert.Equal("Whiskers", Properties.CatNickname);
}

[Fact]
public void SetNullableStringToNull()
{
    Properties.CatNickname = "Whiskers";
    Properties.CatNickname = null;
    Assert.Null(Properties.CatNickname);
}
```

`const val`s, from `sample-app/SampleApp.Tests/ConstValueTests.cs`:

```C#
[Fact]
public void MaxLivesIsNine()
{
    Assert.Equal(9, Constants.MaxLives);
}

[Fact]
public void GreetingIsHelloWorld()
{
    Assert.Equal("Hello, world!", Constants.Greeting);
}
```

Top-level functions follow the same grouping. `sample-library/src/nativeMain/kotlin/.../math/Arithmetic.kt` (`add`, `multiply`, `divide`, `square`) becomes `SampleLibrary.Math.Arithmetic`; see [Generics](generics.md) for the `inline fun square` case.

## See also

- [Objects and companions](objects-and-companions.md)
- [Extensions](extensions.md)
- [ADR-007: Top-level function class naming](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/007-top-level-function-class-naming.md)
