# Top-level declarations

Kotlin top-level functions, properties, and `const val`s don't belong to any class, so the generator groups them by source file: every `.kt` file gets its own static C# class named after the file. This mirrors Kotlin's own `@file:JvmName` behaviour for Java interop, without the `Kt` suffix baggage, and only falls back to a `Kt` suffix when a class of the same name already exists in that file. See [ADR-007](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/007-top-level-function-class-naming.md).

| Kotlin | C# | Notes |
|---|---|---|
| top-level function | `static class` method, `PascalCase` | one static class per source file; native `@CName` export keeps the Kotlin spelling ([ADR-110](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/110-top-level-function-pascal-case.md)) |
| top-level property | static property | get/set, including nullable |
| `const val` | `const` | |
| two or more same-named top-level functions | one C# overload set | numbered native export/extern name, unnumbered public name, counter scoped per (package, name); see [Method overloads](#method-overloads) below ([ADR-095](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md)) |
| top-level function with a trailing run of defaulted parameters | omitting overload per suffix length | see [Function default parameters](#function-default-parameters) below ([ADR-096](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/096-function-default-parameters.md)) |
| top-level function whose `PascalCase` name equals its own file's static class | `Kt`-suffixed class (`GreetingKt`) | ADR-007's conflict rename, extended to fire on a name conflict too; `INFO_FILE_CLASS_RENAMED` |
| top-level function whose `PascalCase` name equals a top-level property in the same file | build error | C# cannot declare a property and a method with one name (CS0102); `ERROR_CSHARP_NAME_COLLISION` |

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

Top-level functions follow the same grouping. `test-library/src/nativeMain/kotlin/.../math/Arithmetic.kt` (`add`, `multiply`, `divide`, `square`) becomes `TestLibrary.Math.Arithmetic`, with `PascalCase` members (`Arithmetic.Add(3, 4)`); see [Generics](generics.md) for the `inline fun square` case.

From `IntegrationTests/ArithmeticTests.cs`:

```C#
int result = Arithmetic.Add(3, 4);
int? divided = Arithmetic.Divide(10, 2);
```

Top-level factory functions that return a bridged class (for example `fun admit(name: String): Patient`
in the clinic fixture) go through the same shared callable plan as other ordinary sync functions
([ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md))
and box the result with `StableRef`, matching companion factories such as `Cat.fromName`.

A top-level `expect fun`/`expect val` follows the same grouping rule, but the static class name is
taken from the **expect's** file, not whichever `{target}Main` file supplied the `actual` body. See
[expect/actual declarations](expect-actual.md).

## A file with nothing left

If every top-level declaration in a file is skipped (for example a `List<List<String>?>` parameter,
which drops the function with a named `SKIPPED_UNSUPPORTED_INPUT` diagnostic, see [Collections](collections.md)),
the file's static class is not generated at all, and if that leaves its namespace with no other
declarations, the namespace is dropped too. `test-library/.../test/husk/HuskOnly.kt` is exactly
this shape: its only function is skipped, so no `HuskOnly` class appears anywhere in `Interop.cs`.
A file with at least one surviving declaration still gets its class, with only the survivors on it.

## Name collisions

`PascalCase`-ing every top-level function ([ADR-110](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/110-top-level-function-pascal-case.md))
opens two collisions camelCase used to keep apart.

A function whose `PascalCase` name equals its own file's static class name (`fun greeting()` in
`Greeting.kt`) would be `Greeting.Greeting()`, a member named like its enclosing type, which C#
forbids (CS0542). ADR-007's `Kt` suffix already exists for a type claiming the file class name; it
fires here too, renaming the whole file class, not just the claiming function, and logging an
`INFO_FILE_CLASS_RENAMED` note:

```C#
public static partial class GreetingKt
{
    public static string Greeting() { /* ... */ }
    public static string Other() { /* ... */ } // moves with the rest of the file
}
```

```
[nuget:INFO_FILE_CLASS_RENAMED] Note GreetingKt: the top-level function 'greeting' renders the
C# name 'Greeting', which is also what its file class would be called, and C# cannot declare a
member named like its enclosing type (CS0542). call it as GreetingKt.Greeting(...); the native
export name is unchanged (ADR-007, ADR-110)
```

The native export is unaffected: `EntryPoint = "greeting"` still targets the Kotlin `@CName`.

A `val name` and a `fun name()` in the same file both want the C# name `Name` on that file's static
class. Kotlin keeps properties and functions in separate namespaces; C# does not, and forbids a
property and a method sharing one name (CS0102). There is no rename to fall back on here, since
renaming either member silently changes the API, so this is a fatal
`ERROR_CSHARP_NAME_COLLISION` naming both declarations, with a hint to rename the Kotlin function.

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
directly; the public name is `PascalCase` ([ADR-110](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/110-top-level-function-pascal-case.md)), unrelated to the numbering:

```C#
public static partial class GroomingSample
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "bookGrooming")]
    private static extern IntPtr Native_BookGrooming(out IntPtr error);

    public static string BookGrooming() { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "bookGrooming_2")]
    private static extern IntPtr Native_BookGrooming_2([MarshalAs(UnmanagedType.LPUTF8Str)] string cat, out IntPtr error);

    public static string BookGrooming(string cat) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "waitTime_has_value")]
    [return: MarshalAs(UnmanagedType.I1)]
    private static extern bool WaitTime_has_value(out IntPtr error);

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "waitTime_value")]
    private static extern int WaitTime_value(out IntPtr error);

    public static int? WaitTime() { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "waitTime_2_has_value")]
    [return: MarshalAs(UnmanagedType.I1)]
    private static extern bool WaitTime_2_has_value([MarshalAs(UnmanagedType.LPUTF8Str)] string cat, out IntPtr error);

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "waitTime_2_value")]
    private static extern int WaitTime_2_value([MarshalAs(UnmanagedType.LPUTF8Str)] string cat, out IntPtr error);

    public static int? WaitTime(string cat) { /* ... */ }
}
```

### Using it from C# {id="overloads-using-it-from-c"}

From `IntegrationTests/StaticRouteOverloadTests.cs`:

```C#
[Fact]
public void WaitTime_WithBlankCat_ReturnsNullFromTheNumberedPresenceCall()
{
    // The presence half of the numbered pair has to belong to *this* overload; a mis-numbered
    // _has_value would answer for WaitTime() instead, which is never null.
    Assert.Null(GroomingSample.WaitTime("   "));
}
```

## Function default parameters

A trailing run of defaulted parameters on a top-level function synthesizes an omitting overload per
suffix length, the same [ADR-091](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/091-constructor-default-parameters.md)
rule extended to this route by [ADR-096](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/096-function-default-parameters.md).
The top-level route is one of two (with extensions) where the synthesized entry shares its Kotlin
declaration node with the declared one, which needed the planner's `planFor(declaration)` accessor
to become plural (`plansFor`) before it could exist at all. Numbering continues the same
per-`(package, name)` counter [Method overloads](#method-overloads) above already uses, so a
synthesized entry is always numbered after every declared overload.

### Kotlin {id="defaults-kotlin"}

From `test-library/src/nativeMain/kotlin/.../whiskers/WhiskersSample.kt`:

```kotlin
fun hail(name: String, loud: Boolean = false): String =
  if (loud) "HI ${name.uppercase()}" else "hi $name"

fun book(name: String, capacity: Int = 3, city: String): String =
  "$name booked $capacity spots in $city"
```

`book`'s default sits before a required `city` (a middle default), so nothing synthesizes for it:
exactly one public overload, unchanged.

### Generated C# {id="defaults-generated-c"}

From `Interop.cs`:

```C#
public static partial class WhiskersSample
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "hail")]
    private static extern IntPtr Native_Hail([MarshalAs(UnmanagedType.LPUTF8Str)] string name, bool loud, out IntPtr error);

    public static string Hail(string name, bool loud) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "hail_2")]
    private static extern IntPtr Native_Hail_2([MarshalAs(UnmanagedType.LPUTF8Str)] string name, out IntPtr error);

    public static string Hail(string name) { /* ... */ } // loud omitted; Kotlin supplies false

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "book")]
    private static extern IntPtr Native_Book([MarshalAs(UnmanagedType.LPUTF8Str)] string name, int capacity, [MarshalAs(UnmanagedType.LPUTF8Str)] string city, out IntPtr error);

    public static string Book(string name, int capacity, string city) { /* ... */ } // only overload
}
```

### Using it from C# {id="defaults-using-it-from-c"}

From `IntegrationTests/FunctionDefaultParameterTests.cs`:

```C#
[Fact]
public void Hail_OmittingLoud_UsesKotlinDefaultOfFalse()
{
    Assert.Equal("hi Oreo", WhiskersSample.Hail("Oreo"));
}

[Fact]
public void Book_HasNoOmittingOverload()
{
    // `capacity` has a required parameter after it, so a positional Kotlin call can never skip
    // it. Stated by signature so a future "helpful" combinatorial expansion trips here.
    Assert.Null(typeof(WhiskersSample).GetMethod("Book", [typeof(string), typeof(int)]));
    Assert.Null(typeof(WhiskersSample).GetMethod("Book", [typeof(string)]));
    Assert.Equal(1, typeof(WhiskersSample).GetMethods().Count(m => m.Name == "Book"));
}
```

A top-level `expect fun`'s defaults are also surfaced, the one route that consults the `expect`
side for the `hasDefault` bit; see [expect/actual declarations](expect-actual.md#function-default-parameters-on-a-top-level-expect-function).

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
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/091-constructor-default-parameters.md">ADR-091: Constructor default parameters</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/096-function-default-parameters.md">ADR-096: Function default parameters</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/110-top-level-function-pascal-case.md">ADR-110: Forward, top-level functions PascalCase in C#</a>
    </category>
</seealso>
