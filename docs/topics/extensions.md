# Extensions

Kotlin extension functions and properties don't have a native C# analog (C# has extension methods but not extension properties), so both map to static methods, grouped by source file the same way as any other top-level declaration (see [Top-level declarations](top-level-declarations.md)). Extension functions render as true C# extension methods (`this` parameter); extension properties render as ordinary static getter methods, since C# can't declare an extension property.

| Kotlin | C# | Notes |
|---|---|---|
| extension function | static method | true C# extension method (`this` parameter); receiver may also be an eligible sealed base, see [Sealed receivers](#sealed-receivers) below, or nullable (`Cat?`), rendered `this Cat? receiver` with a null receiver crossing as `IntPtr.Zero`, see [Nullable receivers](#nullable-receivers) below |
| extension property | static accessor | receiver may also be an eligible sealed base, see [Sealed receivers](#sealed-receivers) below; see [ADR-013](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/013-extension-property-mapping.md) |
| extension function return (object, `T?`, `List`/`Map`/`Set`, enum, `Char`, `String?`, `Int?`, …) | matching C# return type | same cascade as a class-method return via the shared plan, see Return marshalling below and [Classes and objects](classes-and-objects.md) |
| two or more same-named extension functions | one C# overload set | numbered native export/extern name, unnumbered public name, counter scoped per (package, name), receiver-agnostic; see [Method overloads](#method-overloads) below ([ADR-095](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md)) |
| extension function with a trailing run of defaulted parameters | omitting overload per suffix length | receiver is not a plan parameter and always survives truncation; see Method default parameters below ([ADR-096](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/096-function-default-parameters.md)) |
| unexported-receiver `{Receiver}Extensions` class (`String`, a primitive, any stdlib type) | one class per declaring package's namespace | functions and properties on the same receiver in the same package share one class; a different package never merges into it; an exported receiver keeps its own namespace instead, see Namespace placement below ([ADR-126](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/126-extension-class-per-declaring-package.md)) |

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

## Namespace placement

Where a merged `{Receiver}Extensions` class lands depends on whether the receiver is exported by
this library:

- an **exported** receiver (a class this library publishes, e.g. `Cat`) homes the class on the
  receiver's own package, `TestLibrary.Cat.CatExtensions` above. Deterministic: the receiver has
  exactly one package.
- an **unexported** receiver (`String`, a primitive, any stdlib type) has no such home, so the
  class lands in the namespace of the package that *declares* the extension instead. Extension
  functions and extension properties on the same receiver declared in the same package always
  merge into one class; a different package's extensions on the same receiver never merge into it,
  they render their own class in their own namespace.

### Kotlin {id="namespace-kotlin"}

The root package's `String` extensions (`meowify`, `isPurring`, `wordCount`, shown above) sit
beside a second package's own `String` extension, from
`test-library/src/nativeMain/kotlin/.../reserved/ReservedExtensions.kt`:

```kotlin
fun String.tag(receiver: String): String = "$this:$receiver"
```

### Generated C# {id="namespace-generated-c"}

From `Interop.cs`. The root package's `String` extensions land in `TestLibrary`; `reserved`'s own
`String` extension lands in `TestLibrary.Reserved`, a distinct class that never merges with the
first:

```C#
namespace TestLibrary
{
    public static partial class StringExtensions
    {
        public static string Meowify(this string receiver) { /* ... */ }
        public static bool IsPurring(this string receiver) { /* ... */ }
        public static int GetWordCount(this string receiver) { /* ... */ }
    }
}

namespace TestLibrary.Reserved
{
    public static partial class StringExtensions
    {
        public static string Tag(this string receiver, string receiver_) { /* ... */ }
    }
}
```

### Using it from C# {id="namespace-using-it-from-c"}

A fully-qualified static call, from `IntegrationTests/ExtensionNamespaceTests.cs`, is the only way
to pin a namespace at compile time: extension-method call syntax resolves through `using`
directives and keeps compiling wherever the class lands.

```C#
[Fact]
public void RootPackage_Meowify_RendersInTheRootNamespace()
{
    Assert.Equal("Oreo meow!", TestLibrary.StringExtensions.Meowify("Oreo"));
}

[Fact]
public void ReservedPackage_Tag_RendersInItsOwnNamespace()
{
    Assert.Equal("Oreo:Mylo", TestLibrary.Reserved.StringExtensions.Tag("Oreo", "Mylo"));
}
```

<note>
    <p>
        Extension-method call syntax (<code>"Oreo".Meowify()</code>) keeps compiling regardless of
        which package a receiver's extensions land in, as long as the calling file has that
        package's namespace in scope via <code>using</code>. Only a fully-qualified static call
        pins the namespace at compile time, which is why the test above uses one.
    </p>
</note>

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

## Nullable receivers

An extension function's receiver may also be nullable (`Cat?`). It still renders as a genuine C#
extension method, on the nullable wrapper type, so calling it on a null reference is legal C#:
extension methods dispatch statically, there is no `NullReferenceException`. The null crosses the
ABI as `IntPtr.Zero`, and nothing on the native side dereferences it unless the Kotlin body does
([ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md)).

### Kotlin {id="nullable-receiver-kotlin"}

From `test-library/src/nativeMain/kotlin/.../cat/CatExtensions.kt`:

```kotlin
fun Cat?.nameOrStray(): String = this?.name ?: "stray"
```

### Generated C# {id="nullable-receiver-generated-c"}

From `Interop.cs`:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_nameOrStray")]
private static extern IntPtr Native_NameOrStray(IntPtr receiver, out IntPtr error);

public static string NameOrStray(this global::TestLibrary.Cat.Cat? receiver)
{
    IntPtr nativeResult = Native_NameOrStray(receiver?._handle ?? IntPtr.Zero, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
}
```

### Using it from C# {id="nullable-receiver-using-it-from-c"}

From `IntegrationTests/ExtensionFunctionTests.cs`, a live receiver and a null one:

```C#
[Fact]
public void NullableReceiver_LiveCat_ReturnsName()
{
    using var mylo = new Cat("Mylo", 9);
    Assert.Equal("Mylo", mylo.NameOrStray());
}

[Fact]
public void NullableReceiver_NullCat_ReturnsStray()
{
    Cat? none = null;
    Assert.Equal("stray", none.NameOrStray());
}
```

## Sealed receivers

An extension function's or extension property's receiver may also be an eligible sealed base (a
`sealed class`, or an eligible `sealed interface`, see
[Sealed interfaces](interfaces-abstract-sealed.md#sealed-interfaces)). The same `sealedAsHandle()`
rewrite the parameter-position route uses applies to the receiver too, so the extension binds on the
abstract base and every arm inherits it. The Kotlin export takes the base handle and dereferences it
with `asStableRef<Base>().get()`, the same idiom the sealed discriminator's own `_get_type` export
uses. An ineligible or out-of-scope sealed receiver still skips: an extension **function** is named
`SKIPPED_SEALED_POSITION`, an extension **property** is named `SKIPPED_UNSUPPORTED_PROPERTY` (the
property planner records it as an unsupported receiver rather than a dedicated sealed-position reason)
([ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md)).
A nullable sealed receiver (`Shape?`) binds the same way as [a nullable handle receiver](#nullable-receivers)
above, through the same `sealedAsHandle()` recursion into `Nullable`.

### Kotlin {id="sealed-receiver-kotlin"}

From `test-library/src/nativeMain/kotlin/.../issue54/Issue54Sample.kt`:

```kotlin
fun Issue54Shape.footprint(): String = when (this) {
  Issue54Shape.Empty -> "empty"
  is Issue54Shape.Circle -> "circle r=$radius"
}

fun Issue54Shape.covers(other: Issue54Shape): Boolean = when (this) {
  Issue54Shape.Empty -> other == Issue54Shape.Empty
  is Issue54Shape.Circle -> other !is Issue54Shape.Circle || other.radius <= radius
}

val Issue54Shape.area: Double
  get() = when (this) {
    Issue54Shape.Empty -> 0.0
    is Issue54Shape.Circle -> kotlin.math.PI * radius * radius
  }
```

### Generated C# {id="sealed-receiver-generated-c"}

From `Interop.cs`. `Issue54ShapeExtensions` lands in the receiver's own namespace, `TestLibrary.Issue54`, since `Issue54Shape` is exported:

```C#
public static partial class Issue54ShapeExtensions
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue54shape_footprint")]
    private static extern IntPtr Native_Footprint(IntPtr receiver, out IntPtr error);

    public static string Footprint(this global::TestLibrary.Issue54.Issue54Shape receiver)
    {
        IntPtr nativeResult = Native_Footprint(receiver._handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return Marshal.PtrToStringUTF8(nativeResult)!;
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue54shape_covers")]
    [return: MarshalAs(UnmanagedType.I1)]
    private static extern bool Native_Covers(IntPtr receiver, IntPtr other, out IntPtr error);

    public static bool Covers(this global::TestLibrary.Issue54.Issue54Shape receiver, global::TestLibrary.Issue54.Issue54Shape other)
    {
        bool nativeResult = Native_Covers(receiver._handle, other._handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return nativeResult;
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue54shape_get_area")]
    private static extern double Native_Issue54shapeGetArea(IntPtr receiver, out IntPtr error);

    public static double GetArea(this global::TestLibrary.Issue54.Issue54Shape receiver)
    {
        double nativeResult = Native_Issue54shapeGetArea(receiver._handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return nativeResult;
    }
}
```

### Using it from C# {id="sealed-receiver-using-it-from-c"}

From `IntegrationTests/Issue54Tests.cs`:

```C#
[Fact]
public void Footprint_SealedReceiverExtension_BindsOnThePayloadArm()
{
    using Issue54Drawing drawing = Issue54Sample.CurledCats();

    using Issue54Shape shape = drawing.Shape;

    Assert.Equal("circle r=7.5", shape.Footprint());
    Assert.Equal("circle r=7.5", Assert.IsType<Issue54Shape.Circle>(shape).Footprint());
}

[Fact]
public void Area_SealedReceiverExtensionProperty_BindsOnThePayloadArm()
{
    using Issue54Drawing drawing = Issue54Sample.CurledCats();

    using Issue54Shape shape = drawing.Shape;

    Assert.Equal(Math.PI * 7.5 * 7.5, shape.GetArea(), 9);
    Assert.Equal(Math.PI * 7.5 * 7.5, Assert.IsType<Issue54Shape.Circle>(shape).GetArea(), 9);
}

[Fact]
public void Area_SealedReceiverExtensionProperty_BindsOnThePayloadFreeArm()
{
    using Issue54Drawing drawing = Issue54Sample.CurledCats();

    using Issue54Shape shape = drawing.Current;

    Assert.Equal(0.0, shape.GetArea());
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

## Method default parameters

A trailing run of defaulted parameters on an extension function synthesizes an omitting overload
per suffix length, the same [ADR-091](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/091-constructor-default-parameters.md)
rule extended to this route by [ADR-096](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/096-function-default-parameters.md).
Extensions are the other route (with top-level functions) where the synthesized entry shares its
Kotlin declaration node with the declared one, needing the plural `plansFor(declaration)` accessor.
The receiver is a `ForwardReceiver.Value`, not a plan parameter, so it is never truncated: even when
every parameter is defaulted, the omitting overload with zero remaining parameters still carries the
receiver.

### Kotlin {id="defaults-kotlin"}

From `test-library/src/nativeMain/kotlin/.../whiskers/WhiskersSample.kt`:

```kotlin
class Paw(val name: String)

fun Paw.knead(times: Int = 2, surface: String = "blanket"): String =
  "$name kneads the $surface $times times"
```

### Generated C# {id="defaults-generated-c"}

From `Interop.cs`:

```C#
public static partial class PawExtensions
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "paw_knead")]
    private static extern IntPtr Native_Knead(IntPtr receiver, int times, string surface, out IntPtr error);

    public static string Knead(this Paw receiver, int times, string surface) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "paw_knead_2")]
    private static extern IntPtr Native_Knead_2(IntPtr receiver, int times, out IntPtr error);

    public static string Knead(this Paw receiver, int times) { /* ... */ } // surface omitted

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "paw_knead_3")]
    private static extern IntPtr Native_Knead_3(IntPtr receiver, out IntPtr error);

    public static string Knead(this Paw receiver) { /* ... */ } // times and surface both omitted
}
```

### Using it from C# {id="defaults-using-it-from-c"}

From `IntegrationTests/FunctionDefaultParameterTests.cs`:

```C#
[Fact]
public void PawKnead_OmittingEveryParameter_KeepsTheReceiverAndUsesBothDefaults()
{
    // All parameters are defaulted, so at k = 2 the plan carries ZERO parameters. The receiver
    // still has to know it is Oreo's paw.
    using var paw = new Paw("Oreo");

    Assert.Equal("Oreo kneads the blanket 2 times", paw.Knead());
}
```

## Limitations

An extension property only binds when its *receiver* is `String`, a primitive, a class in the
export set (an `ObjectHandle`), an eligible sealed base (see [Sealed receivers](#sealed-receivers)
above), or a value class over any of those underlyings. A receiver outside that set (a generic
class, an interface, an unexported type) is warned about and the whole property is dropped, naming
the receiver rather than the property's own type:

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
        <a href="interfaces-abstract-sealed.md">Interfaces, abstract classes, and sealed classes</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/013-extension-property-mapping.md">ADR-013: Extension property mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md">ADR-061: Method return marshalling</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/077-value-classes-at-ordinary-positions.md">ADR-077: Value classes at ordinary positions</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/090-ordinary-class-method-overloads.md">ADR-090: Ordinary-class method overloads</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/095-static-route-overloads.md">ADR-095: Overloads on the four static export routes</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/091-constructor-default-parameters.md">ADR-091: Constructor default parameters</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/096-function-default-parameters.md">ADR-096: Function default parameters</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md">ADR-105: Sealed types at property positions</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/126-extension-class-per-declaring-package.md">ADR-126: One Extensions class per declaring package</a>
    </category>
</seealso>
