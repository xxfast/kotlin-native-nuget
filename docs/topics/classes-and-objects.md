# Classes and objects

A Kotlin `class` becomes a C# `class` backed by an opaque `StableRef` handle, implementing `IDisposable`. Constructors, member properties (including setters), and object-typed properties/returns all cross the bridge through that handle.

| Kotlin | C# | Notes |
|---|---|---|
| `class` | `class : IDisposable` | `StableRef` + opaque pointer |
| constructor | `new Foo(...)` | Kotlin constructor surfaces as a C# `new`; a trailing run of defaulted parameters adds an omitting overload per suffix length, see Constructor default parameters below ([ADR-091](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/091-constructor-default-parameters.md)) |
| member property (get) | property (get) | |
| member property (get/set) | property (get/set) | |
| object-typed property/return | property/return | new wrapper per access, identity not preserved |
| instance method return (object, `T?`, `List`/`Map`/`Set`, enum, `Char`, `String?`, `Int?`, `Boolean?`, …) | matching C# return type | same cascade as the property getter via the shared plan ([ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)); nullable primitive (including `Boolean?`) is single-call `valueOut`, see Method returns below |
| two or more same-named methods | one C# overload set | numbered native export/extern name, unnumbered public name; see Method overloads below ([ADR-090](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/090-ordinary-class-method-overloads.md)) |

## Kotlin

From `test-library/src/nativeMain/kotlin/.../cat/Cat.kt`:

```kotlin
class Cat(
  name: String,
  val lives: Int = 9,
) : Animal(name) {
  var brother: Cat? = null
  var owner: String? = null
  var age: Int? = null
  var mood: Mood = Mood.SLEEPY

  override fun speak(): String = "Meow! My name is $name"

  fun meow(): String = "Meow! My name is $name"

  fun pet(): String = "$name purrs contentedly"
}
```

## Generated C#

From `Interop.cs`. The constructor allocates a `StableRef` and stores the handle; `Brother` shows the object-typed property pattern, `Owner` shows a nullable string property with a setter:

```C#
public class Cat : Animal
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_create")]
    private static extern IntPtr Native_Create(string name, int lives, out IntPtr error);

    public Cat(string name, int lives) : base(IntPtr.Zero)
    {
        IntPtr handle = Native_Create(name, lives, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        _handle = handle;
    }

    internal Cat(IntPtr handle) : base(handle)
    {
    }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_get_brother")]
    private static extern IntPtr Native_Get_brother(IntPtr handle, out IntPtr error);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_set_brother")]
    private static extern void Native_Set_brother(IntPtr handle, IntPtr value, out IntPtr error);

    public Cat? Brother
    {
        get
        {
            IntPtr nativeResult = Native_Get_brother(_handle, out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
            return nativeResult == IntPtr.Zero ? null : new Cat(nativeResult);
        }
        set
        {
            Native_Set_brother(_handle, value?._handle ?? IntPtr.Zero, out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
        }
    }
}
```

Every access to `Brother` calls `Native_Get_brother` again and wraps the resulting handle in a **new** `Cat` instance. There is no caching. Setting `Brother` unwraps the C# wrapper's `_handle` field (or passes `IntPtr.Zero` for `null`).

## Using it from C#

From `IntegrationTests/ObjectTests.cs`:

```C#
[Fact]
public void Cat_Brother_EachAccessReturnsNewWrapper()
{
    using var oreo = new Cat("Oreo", 9);
    using var mylo = new Cat("Mylo", 9);

    oreo.Brother = mylo;

    using Cat? brother1 = oreo.Brother;
    using Cat? brother2 = oreo.Brother;

    // Per ADR-005: identity is NOT preserved (new wrapper each access)
    Assert.NotSame(brother1, brother2);
    Assert.Equal(brother1!.Name, brother2!.Name);
}

[Fact]
public void Cat_Brother_DisposingBrotherDoesNotAffectOriginal()
{
    using var oreo = new Cat("Oreo", 9);
    using var mylo = new Cat("Mylo", 9);

    oreo.Brother = mylo;

    Cat? brother = oreo.Brother;
    brother!.Dispose();

    // Oreo is still alive — disposing the brother wrapper only releases that one StableRef
    Assert.Equal("Oreo", oreo.Name);
}

[Fact]
public void Cat_Brother_CyclicReference_BothCanBeDisposed()
{
    using var oreo = new Cat("Oreo", 9);
    using var mylo = new Cat("Mylo", 9);

    oreo.Brother = mylo;
    mylo.Brother = oreo;

    using Cat? oreosBrother = oreo.Brother;
    using Cat? mylosBrother = mylo.Brother;
    // All wrappers can be independently disposed without crashes
}
```

From `IntegrationTests/NullablePropertyTests.cs`, a nullable primitive property:

```C#
[Fact]
public void Cat_Age_SetToNull()
{
    using var cat = new Cat("Oreo", 9);
    cat.Age = 3;
    Assert.Equal(3, cat.Age);
    cat.Age = null;
    Assert.Null(cat.Age);
}
```

## Disposal semantics

Disposal does not cascade. A parent wrapper's `Dispose()` only releases *its own* `StableRef`; any wrapper obtained from a property or method call on that parent holds an independent `StableRef` and must be disposed separately (or leaks). This is deliberate: since every access allocates a new wrapper, there's no tree of ownership for a parent to walk. See [ADR-005](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md) for the alternative designs considered (cached wrapper with cascading dispose was rejected).

## Method returns

An instance method returning an object, a collection (`List`/`Map`/`Set` and mutable variants), an
enum, a `Char`, or a nullable type crosses the bridge through the same planned marshalling path the
property getter uses ([ADR-061](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md),
[ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)).
The one exception is a nullable **primitive** return (including `Boolean?`): a method might have side
effects, so it can't reuse the property getter's two-call `hasValue`/`value` pattern (that would
invoke the method twice). It gets a single-call shape instead: the export returns `bool` (has-value)
and writes the value through a `valueOut` out-parameter.

From `test-library/src/nativeMain/kotlin/.../cat/Cat.kt`:

```kotlin
/** Object return (converting: handle -> `new Cat`). No brother set -> a cat looks after itself. */
fun findOwner(): Cat = brother ?: this

/** Nullable object return. Null until `brother` is assigned. */
fun maybeOwner(): Cat? = brother

/** Collection return, converting element (String needs marshalling per element). */
fun tags(): List<String> = listOf("$name-tag", "$name-chip")

/** Collection return, non-converting element (Int is blittable). */
fun scores(): List<Int> = listOf(lives, lives * 2)

/** Nullable String return, single-call. Null until `owner` is assigned. */
fun alias(): String? = owner?.let { "$name (owned by $it)" }

/** Nullable primitive return, single-call out-param per ADR-061. Null until `age` is assigned. */
fun ageInMonths(): Int? = age?.times(12)
```

`Boolean?` returns take the same single-call shape, from `test-library/src/nativeMain/kotlin/.../cat/NullableBooleanSample.kt`:

```kotlin
class CatChecklist(var vaccinated: Boolean? = null) {
  fun isGroomed(state: Int): Boolean? = tribool(state)
}
```

Generated C#, from `Interop.cs` (the collection return, `Tags()`/`Scores()`, walks the handle the
same way as a list *property*, see [Collections](collections.md)):

```C#
public Cat FindOwner()
{
        IntPtr nativeResult = Native_FindOwner(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return new Cat(nativeResult);
}

public Cat? MaybeOwner()
{
        IntPtr nativeResult = Native_MaybeOwner(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return nativeResult == IntPtr.Zero ? null : new Cat(nativeResult);
}

public string? Alias()
{
        IntPtr nativeResult = Native_Alias(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return Marshal.PtrToStringUTF8(nativeResult);
}

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_ageInMonths")]
private static extern bool Native_AgeInMonths(IntPtr handle, out int value, out IntPtr error);

public int? AgeInMonths()
{
        bool hasValue = Native_AgeInMonths(_handle, out int value, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return hasValue ? value : null;
}
```

`Boolean?`'s single-call `valueOut` needs an explicit `[MarshalAs(UnmanagedType.I1)]`: Kotlin/Native
writes a `Boolean` as one byte and C#'s default marshalling reads four, see
[ADR-069](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/069-nullable-boolean-marshalling.md).
Also from `Interop.cs`:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catchecklist_isGroomed")]
private static extern bool Native_IsGroomed(IntPtr handle, int state, [MarshalAs(UnmanagedType.I1)] out bool valueOut, out IntPtr error);

public bool? IsGroomed(int state)
{
    bool hasValue = Native_IsGroomed(_handle, state, out bool valueOut, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return hasValue ? valueOut : null;
}
```

From `IntegrationTests/MethodReturnMarshallingTests.cs`:

```C#
[Fact]
public void Cat_FindOwner_ReturnsBrotherWhenSet()
{
    using var oreo = new Cat("Oreo", 9);
    using var mylo = new Cat("Mylo", 8);
    oreo.Brother = mylo;

    using Cat owner = oreo.FindOwner();
    Assert.Equal("Mylo", owner.Name);
}

[Fact]
public void Cat_AgeInMonths_NonNullWhenAgeSet()
{
    using var oreo = new Cat("Oreo", 9);
    oreo.Age = 3;
    Assert.Equal(36, oreo.AgeInMonths());
}
```

From `IntegrationTests/NullableBooleanTests.cs`, asserting `false` explicitly on a `bool?` (not
`Assert.NotNull`, which a missing `MarshalAs` would still pass):

```C#
[Fact]
public void CatChecklist_IsGroomed_False()
{
    using var checklist = new CatChecklist(null);
    Assert.False(checklist.IsGroomed(1));
}
```

The same cascade applies at the extension-function position; see [Extensions](extensions.md).
Enum, `Char`, and `Map`/`Set` method returns are covered under the shared plan; clinic fixtures
`Patient.Mood()`, `Patient.Initial()`, `Patient.Scores()`, and `Patient.Labels()` exercise them
(see [Enums](enums.md), [Primitives and strings](primitives-and-strings.md), [Collections](collections.md)).

## Method overloads

Two or more same-named methods on an exported class generate one natural C# overload set. From
`test-library/src/nativeMain/kotlin/.../cat/CatNarrator.kt`:

```kotlin
class CatNarrator(val name: String) {
  fun describe(): String = "$name is a cat"

  fun describe(prefix: String): String = "$prefix $name"

  fun describe(prefix: String, excited: Boolean): String =
    if (excited) "$prefix $name!!!" else "$prefix $name, quietly"

  fun rate(stars: Int): String = "$name rated $stars"

  fun rate(mood: Mood): String = "$name is ${mood.name.lowercase()}"
}
```

### Generated C# {id="overloads-generated-c"}

From `Interop.cs`. The native export symbol, the `DllImport` `EntryPoint`, and the private extern
name all carry constructor-style numbering (the first declared overload unnumbered, the next `_2`,
and so on, counted in declaration order), but the public C# surface stays one shared name with no
visible numbering:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catnarrator_describe")]
private static extern IntPtr Native_Describe(IntPtr handle, out IntPtr error);

public string Describe()
{
    IntPtr nativeResult = Native_Describe(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
}

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catnarrator_describe_2")]
private static extern IntPtr Native_Describe_2(IntPtr handle, [MarshalAs(UnmanagedType.LPUTF8Str)] string prefix, out IntPtr error);

public string Describe(string prefix)
{
    IntPtr nativeResult = Native_Describe_2(_handle, prefix, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
}

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catnarrator_rate")]
private static extern IntPtr Native_Rate(IntPtr handle, int stars, out IntPtr error);

public string Rate(int stars) { /* ... */ }

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catnarrator_rate_2")]
private static extern IntPtr Native_Rate_2(IntPtr handle, int mood, out IntPtr error);

public string Rate(global::TestLibrary.Cat.Mood mood) { /* ... */ }
```

`rate(Int)` and `rate(Mood)` are the pair that matters here: an `Int` and an enum both cross the C
ABI as `int`, so they share one wire shape. The private extern *name* is numbered too
(`Native_Rate`/`Native_Rate_2`), not just the `EntryPoint`; numbering only the `EntryPoint` would
declare `Native_Rate` twice and fail to compile with CS0111.

<note>
    <p>
        The numbering is declaration-order dependent and lives only in the native export symbol,
        the <code>DllImport</code> <code>EntryPoint</code>, and the private extern name, none of
        which is a public surface. Reordering the Kotlin declarations renumbers the native exports,
        but the C ABI is not public: the shim and the native library always ship from one build,
        guarded by <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/054-reverse-bridge-registration-observability.md">ADR-054</a>'s
        contract-hash check.
    </p>
</note>

## Using it from C# {id="overloads-using-it-from-c"}

From `IntegrationTests/MethodOverloadTests.cs`:

```C#
[Fact]
public void Describe_AllThreeOverloads_ShareOneInstanceAndStayDistinct()
{
    // One receiver, three arities: proves the numbered exports all reach the same Kotlin
    // object rather than each overload standing up its own state.
    using var narrator = new CatNarrator("Oreo");

    Assert.Equal("Oreo is a cat", narrator.Describe());
    Assert.Equal("the biscuit cat Oreo", narrator.Describe("the biscuit cat"));
    Assert.Equal("the biscuit cat Oreo!!!", narrator.Describe("the biscuit cat", true));
}

[Fact]
public void Rate_WithMood_DispatchesToEnumOverload()
{
    using var narrator = new CatNarrator("Mylo");

    Assert.Equal("Mylo is grumpy", narrator.Rate(Mood.Grumpy));
    Assert.Equal("Mylo is sleepy", narrator.Rate(Mood.Sleepy));
}
```

<warning>
    <p>
        C# cannot overload on reference nullability alone. A same-name pair like
        <code>fun tag(s: String)</code> / <code>fun tag(s: String?)</code> would render two
        identical C# signatures, so generation fails with the named
        <code>ERROR_CSHARP_SIGNATURE_COLLISION</code> diagnostic instead of emitting invalid C#.
    </p>
</warning>

## Constructor default parameters

For each exported constructor, every maximal trailing run of defaulted parameters synthesizes one
additional omitting overload per suffix length, the same rule Kotlin itself uses for
`@JvmOverloads` on the JVM. KSP can only read whether a parameter has a default (`hasDefault`),
never the value or expression, so a synthesized overload's generated Kotlin wrapper calls the
constructor positionally with fewer arguments and Kotlin supplies the rest at the call site. A
defaulted parameter followed by a required one, a **middle default**, produces no overload at all,
since a positional Kotlin call can't skip over it.

### Kotlin {id="ctordefaults-kotlin"}

From `test-library/src/nativeMain/kotlin/.../cat/DefaultsSample.kt`:

```kotlin
class Carrier(
  val label: String,
  val size: Int = 3,
  val padded: Boolean = true,
)

class Kennel(
  val name: String,
  val capacity: Int = 10,
  val city: String,
)

class ScratchPost(val label: String) {
  constructor(label: String, height: Int, sturdy: Boolean = true) :
    this("$label/${height}cm/${if (sturdy) "sturdy" else "wobbly"}")
}
```

`Carrier` has two trailing defaults, so both suffix lengths (`k=1`, `k=2`) synthesize alongside the
full signature: three public constructors in total. `Kennel`'s `capacity` default sits before a
required `city`, so nothing synthesizes: exactly one public constructor. `ScratchPost`'s trailing
default lives on its **secondary** constructor; the primary, `(label: String)`, declares no
defaults of its own and gets nothing extra.

### Generated C# {id="ctordefaults-generated-c"}

From `Interop.cs`:

```C#
public class Carrier : IDisposable
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "carrier_create")]
    private static extern IntPtr Native_Create([MarshalAs(UnmanagedType.LPUTF8Str)] string label, int size, bool padded, out IntPtr error);

    public Carrier(string label, int size, bool padded) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "carrier_create_2")]
    private static extern IntPtr Native_Create_2([MarshalAs(UnmanagedType.LPUTF8Str)] string label, int size, out IntPtr error);

    public Carrier(string label, int size) { /* ... */ }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "carrier_create_3")]
    private static extern IntPtr Native_Create_3([MarshalAs(UnmanagedType.LPUTF8Str)] string label, out IntPtr error);

    public Carrier(string label) { /* ... */ }
}
```

`Kennel` renders exactly one public constructor, the full signature, since `capacity`'s default can
never be omitted:

```C#
public class Kennel : IDisposable
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "kennel_create")]
    private static extern IntPtr Native_Create([MarshalAs(UnmanagedType.LPUTF8Str)] string name, int capacity, [MarshalAs(UnmanagedType.LPUTF8Str)] string city, out IntPtr error);

    public Kennel(string name, int capacity, string city) { /* ... */ }
}
```

`ScratchPost`'s synthesized overload continues the same numbering [ADR-034](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/034-secondary-constructor-exceptions.md)
already gives secondary constructors, so it belongs to the **secondary**, not the primary:

```C#
public class ScratchPost : IDisposable
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "scratchpost_create")]
    private static extern IntPtr Native_Create([MarshalAs(UnmanagedType.LPUTF8Str)] string label, out IntPtr error);

    public ScratchPost(string label) { /* ... */ } // primary, unchanged

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "scratchpost_create_2")]
    private static extern IntPtr Native_Create_2([MarshalAs(UnmanagedType.LPUTF8Str)] string label, int height, bool sturdy, out IntPtr error);

    public ScratchPost(string label, int height, bool sturdy) { /* ... */ } // secondary, full

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "scratchpost_create_3")]
    private static extern IntPtr Native_Create_3([MarshalAs(UnmanagedType.LPUTF8Str)] string label, int height, out IntPtr error);

    public ScratchPost(string label, int height) { /* ... */ } // secondary, k=1
}
```

### Using it from C# {id="ctordefaults-using-it-from-c"}

From `IntegrationTests/ConstructorDefaultParameterTests.cs`:

```C#
[Fact]
public void Cat_OmittingLives_UsesKotlinDefaultOfNine()
{
    // The papercut this fixes: `new Cat("Mouse")` used to be CS7036. Nine lives is Kotlin's
    // number, evaluated by Kotlin, never copied into the C# source.
    using var mouse = new Cat("Mouse");

    Assert.Equal(9, mouse.Lives);
}

[Fact]
public void Carrier_OmittingBothTrailingArguments_UsesBothDefaults()
{
    // k = 2: the deepest suffix. Both defaults come from Kotlin.
    using var carrier = new Carrier("Mylo's crate");

    Assert.Equal("Mylo's crate size 3 padded", carrier.Describe());
}

[Fact]
public void Kennel_ExposesExactlyOnePublicConstructor()
{
    // `capacity` has a required parameter after it, so the JvmOverloads rule synthesizes
    // nothing. Asserted structurally so a future "helpful" combinatorial expansion trips here.
    Assert.Single(typeof(Kennel).GetConstructors());
}

[Fact]
public void ScratchPost_SecondaryConstructor_OmittingTrailingDefault_UsesSturdy()
{
    // The synthesized overload belongs to the SECONDARY constructor, so it must route to the
    // secondary's body and pick up `sturdy = true`, not fall back to the primary.
    using var post = new ScratchPost("tower", 60);

    Assert.Equal("scratch post tower/60cm/sturdy", post.Describe());
}
```

<note>
    <p>
        A constructor with an <code>expect</code>/<code>actual</code> pair still gets its
        omitting overload: Kotlin forbids an <code>actual</code> from restating a default, so
        <code>hasDefault</code> is <code>false</code> on the exported (<code>actual</code>)
        declaration and the planner has to consult the <code>expect</code> class's primary
        constructor to see it. See
        <a href="expect-actual.md">expect/actual declarations</a> for the <code>Beacon</code>
        example. This lookup only covers an <code>expect</code> class's <b>primary</b>
        constructor; a secondary constructor on an <code>expect</code>/<code>actual</code> class
        gets no synthesized overloads in v1.
    </p>
</note>

<warning>
    <p>
        A synthesized overload that collides with a real constructor (or with another
        synthesized overload) fails generation with the same
        <code>ERROR_CSHARP_SIGNATURE_COLLISION</code> diagnostic the Method overloads section
        describes above, its hint extended to name the defaulted-parameter cause. For example,
        <code>class Foo(val name: String, val lives: Int = 9)</code> next to
        <code>constructor(name: String) : this(name, 1)</code> both reduce to a
        <code>Foo(string)</code> C# signature.
    </p>
</warning>

## Classes declared in a dependency module

A class doesn't need to be declared in the publishing Gradle module to reach the generated C# API.
The export set is a reachability closure ([ADR-066](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md)):
starting from the module's own exported declarations, the processor walks return types, parameter
types, and property types, and admits a discovered type declared in a dependency module (pulled in
with `implementation(project(":models"))`) through the same `include`/`exclude`/`rootPackage`
predicate used for the module's own files. See [The nuget {} DSL](nuget-dsl.md) for the full rule.

From `test-library/src/nativeMain/kotlin/.../Newsroom.kt`, where `TopStory` and `Byline` are
declared one Gradle module away, in `:test-models`:

```kotlin
// io.github.xxfast.kotlin.native.nuget.test.models, in :test-models
data class TopStory(val title: String, val rank: Int, val byline: Byline?)
class Byline(val name: String)
```

```kotlin
// io.github.xxfast.kotlin.native.nuget.test, in :test-library
class Newsroom {
  fun latest(): TopStory = TopStory("Oreo escapes the cardboard box (again)", 1, Byline("Mylo"))
}
```

`Byline` is never returned directly by anything in `:test-library`; it only enters the export set
because `TopStory.byline` references it, which is what proves the closure keeps walking rather than
stopping after one hop. Both classes generate exactly like a module-local class, under a namespace
derived from their own Kotlin package (`TestLibrary.Models`), not the exporting module's:

```C#
namespace TestLibrary.Models
{
    public class TopStory : IDisposable
    {
        public string Title { get; }
        public int Rank { get; }
        public global::TestLibrary.Models.Byline? Byline { get; }
        // ...
    }
    public class Byline : IDisposable
    {
        public string Name { get; }
        // ...
    }
}
```

From `IntegrationTests/NewsroomReachabilityTests.cs`:

```C#
[Fact]
public void Latest_Byline_ReachableOnlyTransitively_ThroughAnAlreadyAdmittedType()
{
    using var newsroom = new Newsroom();
    using TopStory story = newsroom.Latest();
    using Byline? byline = story.Byline;

    Assert.NotNull(byline);
    Assert.Equal("Mylo", byline!.Name);
}
```

A dependency-module type reached this way is otherwise a completely ordinary handle-backed class:
cyclic references (`Whisker.purr: Purr?` / `Purr.whisker: Whisker?`) resolve correctly and the
closure terminates rather than recursing forever, and a type whose package falls outside the
effective `include`/`rootPackage` scope is skipped with a named diagnostic instead of silently
binding or breaking the build; see [Publishing Kotlin to C#](forward-overview.md#diagnostics).

## Limitations

- `Map`/`Set` **inputs** (parameters) are not planned yet; see [Collections](collections.md).
- Method overloads on this page cover the class-method route. `object` members, companion members,
  top-level functions, and extension functions have their own numbering scopes and are documented
  on [Objects and companions](objects-and-companions.md#method-overloads),
  [Top-level declarations](top-level-declarations.md#method-overloads), and
  [Extensions](extensions.md#method-overloads) respectively.
- Constructor default parameters synthesize overloads; function and method default parameters
  (top-level functions, class methods, `object`/companion members, extension functions) do not
  yet, and every argument must still be passed explicitly at those positions; see
  [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- Value-class constructor defaults and a partial (argument-omitting) `Copy(...)` are out of scope
  for the constructor-default-parameters feature; see
  [ADR-091](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/091-constructor-default-parameters.md).

<seealso>
    <category ref="related">
        <a href="forward-overview.md">Publishing Kotlin to C#</a>
        <a href="interfaces-abstract-sealed.md">Interfaces, abstract and sealed classes</a>
        <a href="collections.md">Collections</a>
        <a href="extensions.md">Extensions</a>
        <a href="nuget-dsl.md">The nuget {} DSL</a>
        <a href="expect-actual.md">expect/actual declarations</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md">ADR-003: Memory management across the bridge</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005: Object return semantics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/034-secondary-constructor-exceptions.md">ADR-034: Secondary constructor exceptions</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md">ADR-061: Method return marshalling</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md">ADR-066: Forward export reachability closure</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/069-nullable-boolean-marshalling.md">ADR-069: Nullable Boolean marshalling</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/082-value-class-inherited-members.md">ADR-082: Value-class inherited members</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/090-ordinary-class-method-overloads.md">ADR-090: Ordinary-class method overloads</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/091-constructor-default-parameters.md">ADR-091: Constructor default parameters</a>
    </category>
</seealso>
