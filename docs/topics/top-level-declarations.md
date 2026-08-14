# Top-level declarations

Kotlin top-level functions, properties, and `const val`s don't belong to any class, so the generator groups them by source file: every `.kt` file gets its own static C# class named after the file. This mirrors Kotlin's own `@file:JvmName` behaviour for Java interop, without the `Kt` suffix baggage, and only falls back to a `Kt` suffix when a class of the same name already exists in that file. See [ADR-007](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/007-top-level-function-class-naming.md).

| Kotlin | C# | Notes |
|---|---|---|
| top-level function | `static class` method | one static class per source file |
| top-level property | static property | get/set, including nullable |
| `const val` | `const` | |
| two or more same-named top-level functions | one C# overload set | numbered native export/extern name, unnumbered public name, counter scoped per (package, name); see [Method overloads](#method-overloads) below ([ADR-095](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md)) |

## Kotlin

Top-level properties, from `test-library/src/nativeMain/kotlin/.../Properties.kt`:

```kotlin
val catBreed: String = "Scottish Fold"
var catLives: Int = 9
var catNickname: String? = null
var catWeight: Double? = null
```

`const val`s, from `test-library/src/nativeMain/kotlin/.../Constants.kt`:

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

Top-level properties, from `IntegrationTests/TopLevelPropertyTests.cs`:

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

`const val`s, from `IntegrationTests/ConstValueTests.cs`:

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

Top-level functions follow the same grouping. `test-library/src/nativeMain/kotlin/.../math/Arithmetic.kt` (`add`, `multiply`, `divide`, `square`) becomes `TestLibrary.Math.Arithmetic`; see [Generics](generics.md) for the `inline fun square` case.

Top-level factory functions that return a bridged class (for example `fun admit(name: String): Patient`
in the clinic fixture) go through the same shared callable plan as other ordinary sync functions
([ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md))
and box the result with `StableRef`, matching companion factories such as `Cat.fromName`.

A top-level `expect fun`/`expect val` follows the same grouping rule, but the static class name is
taken from the **expect's** file, not whichever `{target}Main` file supplied the `actual` body. See
[expect/actual declarations](expect-actual.md).

## Method overloads

Two or more same-named top-level functions in one package generate one natural C# overload set, the
same [ADR-090](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/090-ordinary-class-method-overloads.md)
numbering template a class method uses, extended to this route by
[ADR-095](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md).
The counter is scoped per `(package, name)`, not per file: two same-named top-level functions in
different files of the same package still share one numbering sequence.

### Kotlin {id="overloads-kotlin"}

From `test-library/src/nativeMain/kotlin/.../grooming/GroomingSample.kt`:

```kotlin
fun bookGrooming(): String = "the next slot is free"

fun bookGrooming(cat: String): String = "$cat is groomed at noon"

fun waitTime(): Int? = 15

fun waitTime(cat: String): Int? = if (cat.isBlank()) null else cat.length
```

`waitTime` returns a nullable primitive, so it routes through the ADR-002 two-call
`_has_value`/`_value` shape instead of the plain single-call shape `bookGrooming` uses; both carry
the same `_$n` numbering.

### Generated C# {id="overloads-generated-c"}

From `Interop.cs`. Top-level exports carry no prefix, so the bare `toCName(name)` gets the suffix
directly; the public name keeps today's camelCase, unrelated to the numbering:

```C#
public static partial class GroomingSample
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "bookGrooming")]
    private static extern IntPtr Native_bookGrooming(out IntPtr error);

    public static string bookGrooming() { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "bookGrooming_2")]
    private static extern IntPtr Native_bookGrooming_2([MarshalAs(UnmanagedType.LPUTF8Str)] string cat, out IntPtr error);

    public static string bookGrooming(string cat) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "waitTime_has_value")]
    private static extern bool waitTime_has_value(out IntPtr error);

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "waitTime_value")]
    private static extern int waitTime_value(out IntPtr error);

    public static int? waitTime() { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "waitTime_2_has_value")]
    private static extern bool waitTime_2_has_value([MarshalAs(UnmanagedType.LPUTF8Str)] string cat, out IntPtr error);

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "waitTime_2_value")]
    private static extern int waitTime_2_value([MarshalAs(UnmanagedType.LPUTF8Str)] string cat, out IntPtr error);

    public static int? waitTime(string cat) { /* ... */ }
}
```

### Using it from C# {id="overloads-using-it-from-c"}

From `IntegrationTests/StaticRouteOverloadTests.cs`:

```C#
[Fact]
public void WaitTime_WithBlankCat_ReturnsNullFromTheNumberedPresenceCall()
{
    // The presence half of the numbered pair has to belong to *this* overload; a mis-numbered
    // _has_value would answer for waitTime() instead, which is never null.
    Assert.Null(GroomingSample.waitTime("   "));
}
```

<seealso>
    <category ref="related">
        <a href="objects-and-companions.md">Objects and companions</a>
        <a href="extensions.md">Extensions</a>
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="expect-actual.md">expect/actual declarations</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/007-top-level-function-class-naming.md">ADR-007: Top-level function class naming</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/090-ordinary-class-method-overloads.md">ADR-090: Ordinary-class method overloads</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md">ADR-095: Overloads on the four static export routes</a>
    </category>
</seealso>
