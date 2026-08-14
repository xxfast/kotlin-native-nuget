# Extensions

Kotlin extension functions and properties don't have a native C# analog (C# has extension methods but not extension properties), so both map to static methods, grouped by source file the same way as any other top-level declaration (see [Top-level declarations](top-level-declarations.md)). Extension functions render as true C# extension methods (`this` parameter); extension properties render as ordinary static getter methods, since C# can't declare an extension property.

| Kotlin | C# | Notes |
|---|---|---|
| extension function | static method | true C# extension method (`this` parameter) |
| extension property | static accessor | see [ADR-013](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/013-extension-property-mapping.md) |
| extension function return (object, `T?`, `List`/`Map`/`Set`, enum, `Char`, `String?`, `Int?`, …) | matching C# return type | same cascade as a class-method return via the shared plan, see Return marshalling below and [Classes and objects](classes-and-objects.md) |
| two or more same-named extension functions | one C# overload set | numbered native export/extern name, unnumbered public name, counter scoped per (package, name), receiver-agnostic; see [Method overloads](#method-overloads) below ([ADR-095](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md)) |

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

## Value-class receivers

A value class also works as the receiver of an extension function or extension property, over any
of the four underlyings admitted at ordinary positions: `String`, a primitive, an enum, or
`ObjectHandle` (see [Value classes](value-classes.md#as-an-extension-receiver)). `ChartId.abbreviate`
(`String` underlying) and `Temperament.escalate` (`Mood` enum underlying, returning another
`Temperament`), from `test-library/src/nativeMain/kotlin/.../clinic/ClinicSample.kt`:

```kotlin
fun ChartId.abbreviate(length: Int): String = value.take(length)

fun Temperament.escalate(): Temperament = when (mood) {
  Mood.CALM -> Temperament(Mood.ANXIOUS)
  Mood.ANXIOUS -> Temperament(Mood.PLAYFUL)
  Mood.PLAYFUL -> Temperament(Mood.PLAYFUL)
}
```

The receiver crosses as its underlying wire value, re-wrapped with the value class's own
constructor on the Kotlin side so `init` runs, exactly like a value-class parameter
([ADR-077](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/077-value-classes-at-ordinary-positions.md)).
Generated C#, from `Interop.cs`:

```C#
public static partial class TemperamentExtensions
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "temperament_escalate")]
    private static extern int Native_Escalate(int receiver, out IntPtr error);

    public static Temperament Escalate(this Temperament receiver)
    {
        int nativeResult = Native_Escalate((int)receiver.Mood, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return new Temperament((global::TestLibrary.Clinic.Mood)nativeResult);
    }
}
```

## Method overloads

Two or more same-named extension functions generate one natural C# overload set, the same
[ADR-090](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/090-ordinary-class-method-overloads.md)
numbering template a class method uses, extended to this route by
[ADR-095](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md).
The symbol key is `$package.$name`, which does **not** include the receiver, so the counter is
receiver-agnostic: two same-named extensions on *different* receivers in one package number off the
same sequence, even though their C exports would never have collided on their own.

### Kotlin {id="overloads-kotlin"}

From `test-library/src/nativeMain/kotlin/.../grooming/GroomingSample.kt`:

```kotlin
class Mitten(val name: String)

class Tomcat(val name: String)

fun Mitten.pat(): String = "$name purrs"

fun Mitten.pat(style: String): String = "$name enjoys a $style pat"

fun Mitten.brush(strokes: Int): String = "$name is brushed $strokes times"

fun Mitten.brush(coat: Coat): String = "$name is brushed for a ${coat.name.lowercase()} coat"

/** Same package, same name, different receiver from Mitten.pat. */
fun Tomcat.pat(): String = "$name tolerates exactly one pat"
```

### Generated C# {id="overloads-generated-c"}

From `Interop.cs`. `Mitten.pat`'s two declarations number `mitten_pat` / `mitten_pat_2`; `Tomcat.pat`
comes after all four `Mitten` overloads in source order, so it inherits the *next* number in the
shared package-scoped sequence, `tomcat_pat_3`, on its own `TomcatExtensions` class:

```C#
public static partial class MittenExtensions
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "mitten_pat")]
    private static extern IntPtr Native_Pat(IntPtr receiver, out IntPtr error);

    public static string Pat(this Mitten receiver) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "mitten_pat_2")]
    private static extern IntPtr Native_Pat_2(IntPtr receiver, [MarshalAs(UnmanagedType.LPUTF8Str)] string style, out IntPtr error);

    public static string Pat(this Mitten receiver, string style) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "mitten_brush")]
    private static extern IntPtr Native_Brush(IntPtr receiver, int strokes, out IntPtr error);

    public static string Brush(this Mitten receiver, int strokes) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "mitten_brush_2")]
    private static extern IntPtr Native_Brush_2(IntPtr receiver, int coat, out IntPtr error);

    public static string Brush(this Mitten receiver, global::TestLibrary.Grooming.Coat coat) { /* ... */ }
}

public static partial class TomcatExtensions
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "tomcat_pat_3")]
    private static extern IntPtr Native_Pat_3(IntPtr receiver, out IntPtr error);

    public static string Pat(this Tomcat receiver) { /* ... */ }
}
```

### Using it from C# {id="overloads-using-it-from-c"}

From `IntegrationTests/StaticRouteOverloadTests.cs`:

```C#
[Fact]
public void TomcatPat_SameNameDifferentReceiver_ResolvesToItsOwnExport()
{
    // Extension plan symbols are receiver-agnostic ($package.$name), so this declaration
    // shares a symbol with Mitten.pat and crashes generation today. Both must survive, each on
    // its own {Receiver}Extensions class.
    using var oreo = new Mitten("Oreo");
    using var mylo = new Tomcat("Mylo");

    Assert.Equal("Oreo purrs", oreo.Pat());
    Assert.Equal("Mylo tolerates exactly one pat", mylo.Pat());
}
```

<note>
    <p>
        The redundant <code>_3</code> on <code>tomcat_pat_3</code> is a native-export detail only:
        it composes after <code>toCName</code> and never reaches the public C# name. Package-scoped
        numbering was chosen over a receiver-qualified symbol so cross-receiver namesakes share the
        same rule as top-level functions; see the ADR's Alternatives Considered.
    </p>
</note>

## Limitations

An extension property only binds when its *receiver* is `String`, a primitive, a class in the
export set (an `ObjectHandle`), or a value class over any of those four underlyings. A receiver
outside that set (a generic class, an interface, an unexported type) is warned about and the whole
property is dropped, naming the receiver rather than the property's own type:

```
[nuget:SKIPPED_UNSUPPORTED_PROPERTY] Skipping tier1.skipreceiver.Box.label: its extension receiver
    type generic declaration tier1.skipreceiver.Box is not a supported extension-property receiver.
    declare the property on a class, String, primitive, or value class receiver, or expose a
    top-level getter function instead
    at <file>:<line>
```

An extension property typed `Flow`, `StateFlow`, or a lambda is also named as a skip rather than
exported, even on an otherwise-supported receiver: unlike a class property, no legacy adapter
re-emits it, so it is not exempt from the diagnostic the way a class property of the same type is.

See [Publishing Kotlin to C#: Diagnostics](forward-overview.md#diagnostics) for the full diagnostic
model.

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
        <a href="value-classes.md">Value classes</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/013-extension-property-mapping.md">ADR-013: Extension property mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md">ADR-061: Method return marshalling</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/077-value-classes-at-ordinary-positions.md">ADR-077: Value classes at ordinary positions</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/090-ordinary-class-method-overloads.md">ADR-090: Ordinary-class method overloads</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md">ADR-095: Overloads on the four static export routes</a>
    </category>
</seealso>
