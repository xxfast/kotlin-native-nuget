# C# structs

A bridgeable C# `struct` becomes an immutable Kotlin `data class`. No struct ever crosses the C ABI
itself: a struct-typed parameter expands into one ABI argument per component, and a struct-typed
return expands into `void` plus one out-pointer per component. There is no handle, no `close()`, no
`Cleaner`, and equality is structural.

There are two ways a struct qualifies, tried in order:

- **Shape A**: exactly one public instance constructor covers all stored state. Components are its
  parameters, in constructor-parameter order. C# reconstructs with `new T(a, b)`.
- **Shape B**: no such constructor, but every field is either a public settable field or a settable
  auto-property (`set` or `init`). Components are the fields/properties, in C# declaration order. C#
  reconstructs with an object initializer, `new T { A = a, B = b }`.

A struct that is neither is skipped (see [Which structs bind](#which-structs-bind)). Both shapes share
everything else: the same wire format, the same immutable `data class` surface, the same registration
machinery. Its unique state constructor (Shape A) or its object-initializer reconstruction (Shape B)
both claim zero registration slots; each alternate public constructor on a Shape A struct claims one
slot and reconstructs the returned components through out-pointers (Shape B structs bind no alternate
constructors, see [Which structs bind](#which-structs-bind)). Methods and get-only computed properties
on the struct itself also bind, for either shape: each call reconstructs the value from its components
on the wire (the reverse of
[ADR-014](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/014-value-class-mapping.md)'s
"reconstruct on each invocation"), and members alone force a `Bindings` / registration export even
when the struct has no alternate constructors.

| C# | Kotlin |
|---|---|
| a bridgeable struct (see [Which structs bind](#which-structs-bind) below) | immutable `data class`, one `val` per component |
| struct-typed parameter | one ABI argument per component |
| struct-typed return | thunk return becomes `void`; one out-pointer argument per component |
| struct-typed property getter | as a return: out-pointers |
| struct-typed property setter | as a parameter: decomposed arguments |
| alternate public constructor (Shape A only) | Kotlin secondary constructor; one registration slot and one out-pointer per state component |
| public instance method (non-void) | member function on the `data class`; receiver components lead the wire args |
| get-only computed property (not a component) | `val` with bridge-backed getter on the `data class` |
| public static method on the struct | function in the Kotlin `companion object` |

## The `Point` fixture

`sample-dependency/Geometry.cs` declares a struct whose constructor parameters are lower camelCase
while the properties they back are PascalCase, deliberately exercising the case-insensitive
component-match rule:

```C#
// sample-dependency/Geometry.cs (real source)
public readonly struct Point
{
    public Point(int x, int y)
    {
        X = x;
        Y = y;
    }

    public Point(int value) : this(value, value) { }

    public Point(bool unit) : this(unit ? 1 : 0, unit ? 1 : 0) { }

    public Point(Size size) : this(size.Width, size.Height) { }

    public int X { get; }
    public int Y { get; }

    public int Magnitude => Math.Abs(X) + Math.Abs(Y);

    public Point Offset(int dx, int dy) => new Point(X + dx, Y + dy);

    public string Format() => $"({X},{Y})";

    public static Point Origin() => new Point(0, 0);
}

public static class Geometry
{
    public static Point Translate(Point p, int dx, int dy) => new Point(p.X + dx, p.Y + dy);
    public static string Describe(Point p) => $"({p.X}, {p.Y})";
    public static int Manhattan(Point a, Point b) => Math.Abs(a.X - b.X) + Math.Abs(a.Y - b.Y);
}
```

Generated Kotlin surface (`build/nuget-interop/kotlin/nativeMain/sample/structs/Point.kt`, real
output). Components stay primary constructor `val`s; methods and computed properties sit on the same
`data class`, and statics land in a `companion object`:

```kotlin
internal data class Point(
  val x: Int,
  val y: Int,
) {
  private constructor(components: PointConstructorComponents) : this(components.x, components.y)
  constructor(size: Size) : this(construct__4df77a50827105506f4258372b08561b(size))
  constructor(unit: Boolean) : this(construct__e802c1ad168a16a38ec169d93b7f55fc(unit))
  constructor(value: Int) : this(construct__e71e420dcea5e3c083737f3c34b97ec7(value))
  fun format(): String {
    val fn = requireNotNull(PointBindings.format__0fb3c58859d9da606915abab731b3187Fn) {
      NugetRegistry.notRegistered("Sample.Structs.Point", "SampleDependency")
    }
    val resultPtr = fn.invoke(x, y)
      ?: error("Point.Format returned null, expected a non-null string pointer")
    val result = resultPtr.reinterpret<ByteVar>().toKString()
    freeManagedString(resultPtr)
    return result
  }

  fun offset(dx: Int, dy: Int): Point = memScoped {
    val fn = requireNotNull(PointBindings.offset__8a9f2cbc4c6860d43d71b26e499229c9Fn) {
      NugetRegistry.notRegistered("Sample.Structs.Point", "SampleDependency")
    }
    val outX = alloc<IntVar>()
    val outY = alloc<IntVar>()
    fn.invoke(x, y, dx, dy, outX.ptr, outY.ptr)
    Point(outX.value, outY.value)
  }
  val magnitude: Int
    get() {
      val fn = requireNotNull(PointBindings.magnitudeGetterFn) {
        NugetRegistry.notRegistered("Sample.Structs.Point", "SampleDependency")
      }
      return fn.invoke(x, y)
    }
  companion object {
    fun origin(): Point = memScoped {
      val fn = requireNotNull(PointBindings.origin__8692fa20d808eeacc66f94518d080914Fn) {
        NugetRegistry.notRegistered("Sample.Structs.Point", "SampleDependency")
      }
      val outX = alloc<IntVar>()
      val outY = alloc<IntVar>()
      fn.invoke(outX.ptr, outY.ptr)
      Point(outX.value, outY.value)
    }
  }
}
```

The type is generated `internal`, not `public`: it must stay invisible to the forward-direction (KSP)
exporter's own public-API scan, so a reverse-bound struct never gets re-exported forward into the
package's own `Interop.cs`.

`Translate` shows the full shape for a *host* type taking a struct: a struct parameter decomposed
into leading arguments, and a struct return assembled from out-pointers:

```kotlin
// build/nuget-interop/kotlin/nativeMain/sample/structs/GeometryBindings.kt (real generated output)
internal var translateFn: CPointer<CFunction<(Int, Int, Int, Int, CPointer<IntVar>, CPointer<IntVar>) -> Unit>>? = null
```

```C#
// build/nuget-interop/csharp/GeometryRegistration.cs (real generated output)
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void Translate_Thunk(int p_X, int p_Y, int dx, int dy, int* outX, int* outY)
{
    Point result = Geometry.Translate(new Point(p_X, p_Y), dx, dy);
    *outX = result.X;
    *outY = result.Y;
}
```

`Manhattan` shows two struct parameters in one signature (`int a_X, int a_Y, int b_X, int b_Y`, a
single non-struct `int` return, no out-pointers), and `Describe` shows a struct parameter with a
non-struct (`string`) return.

## The full v1 component vocabulary

`sample-dependency/Profile.cs` exercises every v1 component kind together: `string`, `bool`, `char`,
and a bound enum:

```C#
// sample-dependency/Profile.cs (real source)
public readonly struct Profile
{
    public Profile(string tag, bool active, char grade, CatMood mood)
    {
        Tag = tag;
        Active = active;
        Grade = grade;
        Mood = mood;
    }

    public string Tag { get; }
    public bool Active { get; }
    public char Grade { get; }
    public CatMood Mood { get; }

    public string Label => $"{Tag}:{Mood}";

    public bool IsPlayful => Mood == CatMood.Playful;

    public Profile WithMood(CatMood mood) => new Profile(Tag, Active, Grade, mood);

    public static Profile Resting(string tag) => new Profile(tag, false, 'Z', CatMood.Sleepy);
}
```

```kotlin
// build/nuget-interop/kotlin/nativeMain/sample/structs/Profile.kt (real generated output)
internal data class Profile(
  val tag: String,
  val active: Boolean,
  val grade: Char,
  val mood: CatMood,
)
```

Members on `Profile` (same file) follow the same reconstruct-on-call shape as `Point`, with the full
string / bool / char / enum conversion vocabulary on every receiver component. See
[Struct methods and computed properties](#struct-methods-and-computed-properties).

Per-component ABI types reuse the existing wire tables (no new scalar is introduced by structs):

| Component type | Kotlin `CFunction` (in) | Kotlin `CFunction` (out-ptr) | C# thunk (in) | C# thunk (out-ptr) |
|---|---|---|---|---|
| `int` / enum | `Int` | `CPointer<IntVar>` | `int` | `int*` |
| `long` | `Long` | `CPointer<LongVar>` | `long` | `long*` |
| `bool` | `UByte` | `CPointer<UByteVar>` | `byte` | `byte*` |
| `char` | `UShort` | `CPointer<UShortVar>` | `ushort` | `ushort*` |
| `float` / `double` | `Float` / `Double` | `CPointer<FloatVar>` / `CPointer<DoubleVar>` | `float` / `double` | `float*` / `double*` |
| `string` | `COpaquePointer?` | `CPointer<COpaquePointerVar>` | `IntPtr` | `IntPtr*` |

`sample-dependency/Metrics.cs` covers the "pass-through" numeric components (`long`/`float`/`double`),
each crossing as its own scalar with no ABI-side conversion, unlike `bool`/`char`/`string`/enum.

## Shape B: structs with no public constructor

A struct with no state-covering constructor, but whose stored state is entirely public settable
fields and/or settable auto-properties (`set` or `init`), still binds. Components are recovered from
the FieldDef table in C# declaration order, a public field is its own row and an auto-property is its
backing field's row, so a field and a property interleave correctly. The Kotlin surface is identical
to Shape A: an immutable `data class`. Only the C# reconstruction expression changes, from
`new T(a, b)` to an object initializer, `new T { A = a, B = b }`; `init`-only setters bind with no
special handling, since an object initializer is exactly the context in which `init` is callable.

`sample-dependency/Collar.cs` has both sub-shapes: `Extent` is pure public fields, `Collar` mixes a
public field with `set` and `init` auto-properties across the full component vocabulary:

```C#
// sample-dependency/Collar.cs (real source)
public struct Extent
{
    public int Width;
    public int Height;

    public int Area => Width * Height;
    public Extent Grow(int by) => new Extent { Width = Width + by, Height = Height + by };
    public static Extent Unit() => new Extent { Width = 1, Height = 1 };
}

public struct Collar
{
    public int Girth;                            // public field           -> int, direct
    public string Colour { get; set; }           // settable auto-prop     -> IntPtr / UTF-8
    public bool Belled { get; init; }             // INIT-only auto-prop    -> byte
    public char Initial { get; init; }            // INIT-only auto-prop    -> ushort
    public CatMood Mood { get; set; }             // settable auto-prop     -> int ordinal

    public string Label => $"{Colour}:{Girth}{(Belled ? "*" : "")}";
    public bool IsLoud => Belled && Mood == CatMood.Playful;

    public Collar Resize(int by) =>
        new Collar { Girth = Girth + by, Colour = Colour, Belled = Belled, Initial = Initial, Mood = Mood };

    public static Collar Plain(string colour) =>
        new Collar { Girth = 1, Colour = colour, Belled = false, Initial = 'P', Mood = CatMood.Calm };
}
```

Generated Kotlin: components stay primary constructor `val`s, in declaration order. The class shape is
otherwise unchanged from Shape A, but the KDoc is not: a `data class` generated for an `INITIALIZER`
struct carries an extra paragraph warning about the declaration-order hazard (see [Component order is
a hazard Shape A does not have](#component-order-is-a-hazard-shape-a-does-not-have) below); a Shape A
`data class` like `Point` does not, since its order is already C# API
(`build/nuget-interop/kotlin/nativeMain/sample/structs/Extent.kt` and `Collar.kt`, real output):

```kotlin
internal data class Extent(
  val width: Int,
  val height: Int,
) {
  fun grow(by: Int): Extent = memScoped {
    val fn = requireNotNull(ExtentBindings.grow__9121219becc53aca492b7a3eeec39b31Fn) {
      NugetRegistry.notRegistered("Sample.Structs.Extent", "SampleDependency")
    }
    val outWidth = alloc<IntVar>()
    val outHeight = alloc<IntVar>()
    fn.invoke(width, height, by, outWidth.ptr, outHeight.ptr)
    Extent(outWidth.value, outHeight.value)
  }
  // ...
}

/**
 * Kotlin value type for the C# struct `SampleDependency.Collar`.
 *
 * Copied by value across the bridge: equality is structural, and there is nothing to close.
 * The C# struct's fields/properties are settable, but a Kotlin-side change can never be
 * observable in C# (a copy crossed the boundary), so every component is a `val`. Use [copy]
 * and pass the result back.
 *
 * Component order follows the C# declaration order. Prefer named arguments.
 */
internal data class Collar(
  val girth: Int,
  val colour: String,
  val belled: Boolean,
  val initial: Char,
  val mood: CatMood,
)
```

The reconstruction delta is on the C# side, in the reconstruction expression
(`build/nuget-interop/csharp/ExtentRegistration.cs` and `CollarRegistration.cs`, real output):

```C#
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void Grow__9121219becc53aca492b7a3eeec39b31_Thunk(int Width, int Height, int by, int* outWidth, int* outHeight)
{
    Extent result = new Extent { Width = Width, Height = Height }.Grow(by);
    *outWidth = result.Width;
    *outHeight = result.Height;
}

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void Resize__50abd5f4f4e3ddfc0ecdc552fbbca9ab_Thunk(int Girth, IntPtr ColourPtr, byte Belled, ushort Initial, int Mood, int by, int* outGirth, IntPtr* outColour, byte* outBelled, ushort* outInitial, int* outMood)
{
    Collar result = new Collar { Girth = Girth, Colour = Marshal.PtrToStringUTF8(ColourPtr)!, Belled = Belled != 0, Initial = (char)Initial, Mood = (CatMood)Mood }.Resize(by);
    *outGirth = result.Girth;
    *outColour = Marshal.StringToCoTaskMemUTF8(result.Colour);
    *outBelled = result.Belled ? (byte)1 : (byte)0;
    *outInitial = (ushort)result.Initial;
    *outMood = (int)result.Mood;
}
```

`sample-dependency/Collars.cs`'s `Pair(Collar a, Extent b)` takes two Shape B structs of different
sub-shapes in one signature, decomposing both onto the wire and reconstructing each with its own
object initializer (`build/nuget-interop/csharp/CollarsRegistration.cs`, real output):

```C#
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr Pair__19127a5e1495d2eeb2514ce30eb72bd2_Thunk(int a_Girth, IntPtr a_ColourPtr, byte a_Belled, ushort a_Initial, int a_Mood, int b_Width, int b_Height)
{
    string result = Collars.Pair(new Collar { Girth = a_Girth, Colour = Marshal.PtrToStringUTF8(a_ColourPtr)!, Belled = a_Belled != 0, Initial = (char)a_Initial, Mood = (CatMood)a_Mood }, new Extent { Width = b_Width, Height = b_Height });
    return Marshal.StringToCoTaskMemUTF8(result);
}
```

Hand-written Kotlin in `sample-library` looks exactly like the Shape A samples; nothing about calling
a Shape B struct differs from Kotlin's point of view:

```kotlin
// sample-library/.../sample/structs/CollarSample.kt (real source)
fun describeCollar(
  girth: Int,
  colour: String,
  belled: Boolean,
  initialCode: Int,
  mood: CatMood,
): String = Collars.describe(Collar(girth, colour, belled, initialCode.toChar(), mood))

fun collarMembers(girth: Int, colour: String, mood: CatMood): String {
  val c = Collar(girth, colour, true, 'B', mood)
  return "${c.label}|${c.isLoud}|${c.resize(1).girth}|${Collar.plain(colour).label}"
}

// The documented defence against the declaration-order hazard: named arguments.
fun collarNamedArgs(): String {
  val c: Collar =
    Collar(girth = 1, colour = "black", belled = true, initial = 'O', mood = CatMood.CALM)
  return "${c.girth},${c.colour},${c.belled},${c.initial},${c.mood}"
}
```

```C#
// sample-app/SampleApp.Tests/CollarRoundTripTests.cs (real source)
[Fact]
public void DescribeCollar_RendersEveryComponent()
{
    string result = CollarSample.describeCollar(5, "Oreo", true, 'O', CatMood.Playful);
    Assert.Equal("Oreo 5 O Playful True", result);
}

[Fact]
public void PairCollar_TwoDifferentShapeBStructsInOneSignature()
{
    string result = CollarSample.pairCollar(2, "Oreo", 3, 4);
    Assert.Equal("Oreo:2*/3x4", result);
}

[Fact]
public void CollarNamedArgs_ComponentNamesMatchDeclarationOrder()
{
    string result = CollarSample.collarNamedArgs();
    Assert.Equal("1,black,true,O,CALM", result);
}
```

`initialCode: Int` (rather than a Kotlin `Char`) is the same forward-direction workaround
`StructsSample.kt`'s `gradeCode` uses: a raw Kotlin `Char` parameter cannot cross the *forward*
boundary yet (a pre-existing, unrelated gap, tracked in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)
Phase 3). It affects only how this fixture calls into `sample-library`, not the reverse struct
binding itself, whose own `char` components round-trip correctly.

### Component order is a hazard Shape A does not have

For a Shape A struct, component order is the constructor's parameter order, which is already C# API:
reordering it breaks every positional C# caller too. For a Shape B struct, component order is the
*field declaration* order, which C# callers never see (they always use named object-initializer
syntax). Reordering two same-typed fields in the C# source is source-compatible for C# callers and
silently reorders the generated Kotlin `data class` primary constructor.

Two independent defences exist, at two different layers:

- **Documentation, at the Kotlin source layer.** Every `data class` generated for a Shape B
  (`RirStructShape.INITIALIZER`) struct carries the extra KDoc paragraph quoted above, verbatim:
  "Component order follows the C# declaration order. Prefer named arguments." A Shape A `data class`
  like `Point` does not carry it, since its order already tracks C# API. A generator test pins the
  exact wording. Named arguments are the correct defence (`collarNamedArgs` above), not a workaround.
- **The contract hash, at the wire layer.** The ADR-054 contract hash now includes component
  **names**, not just types, so a reorder changes `contractHash` and a stale shim paired with a fresh
  library is caught rather than silently corrupting memory (see [Registration
  diagnostics](registration-diagnostics.md)).

Neither defence stops a Kotlin consumer who ignores the KDoc and calls `Extent(3, 4)` positionally
from getting a value with the components swapped after a reorder and successful rebuild: the contract
hash only catches a stale-shim mismatch, and the KDoc is only read, not enforced. The named-arguments
recommendation is real advice, not a guarantee.

## Struct-typed properties and instance methods

A struct works as a parameter or return on both static and instance members. `sample-dependency`'s
`Cattery` (a bound handle class) has an instance method taking and returning `Metrics`, and a
**settable** struct-typed property, `CurrentProfile`:

```kotlin
// build/nuget-interop/kotlin/nativeMain/sample/structs/Cattery.kt (real generated output)
fun weigh(m: Metrics, factor: Float): Metrics = memScoped {
  val fn = requireNotNull(CatteryBindings.weighFn) { /* ... */ }
  val outHeartRateBpm = alloc<LongVar>()
  val outWeightKg = alloc<FloatVar>()
  val outTemperatureC = alloc<DoubleVar>()
  fn.invoke(handle.require("Cattery"), m.heartRateBpm, m.weightKg, m.temperatureC, factor, outHeartRateBpm.ptr, outWeightKg.ptr, outTemperatureC.ptr)
  Metrics(outHeartRateBpm.value, outWeightKg.value, outTemperatureC.value)
}

var currentProfile: Profile
  get() {
    val fn = requireNotNull(CatteryBindings.currentProfileGetterFn) { /* ... */ }
    return memScoped {
      val outTag = alloc<COpaquePointerVar>()
      val outActive = alloc<UByteVar>()
      val outGrade = alloc<UShortVar>()
      val outMood = alloc<IntVar>()
      fn.invoke(handle.require("Cattery"), outTag.ptr, outActive.ptr, outGrade.ptr, outMood.ptr)
      val outTagPtr = requireNotNull(outTag.value) { "a struct string component returned null unexpectedly" }
      val outTagResult = outTagPtr.reinterpret<ByteVar>().toKString()
      freeManagedString(outTagPtr)
      Profile(outTagResult, outActive.value.toInt() != 0, outGrade.value.toInt().toChar(), nugetEnumEntry(CatMood.entries, outMood.value, "CatMood"))
    }
  }
  set(value) {
    val fn = requireNotNull(CatteryBindings.currentProfileSetterFn) { /* ... */ }
    memScoped { fn.invoke(handle.require("Cattery"), value.tag.cstr.ptr, value.active, value.grade.code.toUShort(), value.mood.ordinal) }
  }
```

The generated C# thunks show the same shape from the other side, an instance thunk's leading
`selfHandle` parameter followed by the decomposed struct components:

```C#
// build/nuget-interop/csharp/CatteryRegistration.cs (real generated output)
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void CurrentProfile_Get_Thunk(IntPtr selfHandle, IntPtr* outTag, byte* outActive, ushort* outGrade, int* outMood)
{
    Cattery receiver = (Cattery)GCHandle.FromIntPtr(selfHandle).Target!;
    Profile result = receiver.CurrentProfile;
    *outTag = Marshal.StringToCoTaskMemUTF8(result.Tag);
    *outActive = result.Active ? (byte)1 : (byte)0;
    *outGrade = (ushort)result.Grade;
    *outMood = (int)result.Mood;
}

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static void CurrentProfile_Set_Thunk(IntPtr selfHandle, IntPtr value_TagPtr, byte value_Active, ushort value_Grade, int value_Mood)
{
    Cattery receiver = (Cattery)GCHandle.FromIntPtr(selfHandle).Target!;
    receiver.CurrentProfile = new Profile(Marshal.PtrToStringUTF8(value_TagPtr)!, value_Active != 0, (char)value_Grade, (CatMood)value_Mood);
}
```

`Cattery` itself is a real C# class (an ADR-051 handle wrapper), so it keeps `close()`. `Point`,
`Metrics`, and `Profile` are values and have none of that.

## Struct methods and computed properties

Members declared *on* the struct itself bind too. This is the reverse mirror of
[ADR-014](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/014-value-class-mapping.md):
the C# thunk rebuilds `new Point(X, Y)` (or the equivalent for other component vocabularies) from
leading wire args, then invokes the real member. There is no handle and no `selfHandle`.

What binds:

| C# on the struct | Kotlin | Wire |
|---|---|---|
| public instance method with a non-void return | member function | N component args, then ordinary args; struct returns use out-pointers |
| get-only non-component computed property | `val` with bridge-backed getter | N component args only |
| public static method | `companion object` function | ordinary args only (no receiver components) |

What is skipped (so the `data class` keeps Kotlin's own equality / stringification, and components
stay primary constructor vals rather than bridge getters):

- `Equals` / `GetHashCode` / `ToString` / `Deconstruct`
- operators
- setters
- void-returning instance methods
- component auto-properties (`X`, `Y`, `Tag`, …)

Registration slots on a struct follow the ADR-057 category order for that type:
**alternate constructors → static methods → instance methods → computed getters**. Members alone force
`PointBindings.kt` / `PointRegistration.cs` even when the struct has no alternate constructors
(`Profile` is that case: four member slots, zero alternate ctors).

Generated C# thunks for `Point` (`build/nuget-interop/csharp/PointRegistration.cs`, real output):

```C#
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr Format__0fb3c58859d9da606915abab731b3187_Thunk(int X, int Y)
{
    string result = new Point(X, Y).Format();
    return Marshal.StringToCoTaskMemUTF8(result);
}

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void Offset__8a9f2cbc4c6860d43d71b26e499229c9_Thunk(int X, int Y, int dx, int dy, int* outX, int* outY)
{
    Point result = new Point(X, Y).Offset(dx, dy);
    *outX = result.X;
    *outY = result.Y;
}

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static int Magnitude_Get_Thunk(int X, int Y)
{
    int result = new Point(X, Y).Magnitude;
    return result;
}

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void Origin__8692fa20d808eeacc66f94518d080914_Thunk(int* outX, int* outY)
{
    Point result = Point.Origin();
    *outX = result.X;
    *outY = result.Y;
}
```

`Profile` shows the same reconstruct path with the non-primitive component vocabulary. Real thunks
from `build/nuget-interop/csharp/ProfileRegistration.cs`:

```C#
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void WithMood__af153c00af41f44bcc8d2d1964698940_Thunk(IntPtr TagPtr, byte Active, ushort Grade, int Mood, int mood, IntPtr* outTag, byte* outActive, ushort* outGrade, int* outMood)
{
    Profile result = new Profile(Marshal.PtrToStringUTF8(TagPtr)!, Active != 0, (char)Grade, (CatMood)Mood).WithMood((CatMood)mood);
    *outTag = Marshal.StringToCoTaskMemUTF8(result.Tag);
    *outActive = result.Active ? (byte)1 : (byte)0;
    *outGrade = (ushort)result.Grade;
    *outMood = (int)result.Mood;
}

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr Label_Get_Thunk(IntPtr TagPtr, byte Active, ushort Grade, int Mood)
{
    string result = new Profile(Marshal.PtrToStringUTF8(TagPtr)!, Active != 0, (char)Grade, (CatMood)Mood).Label;
    return Marshal.StringToCoTaskMemUTF8(result);
}
```

Hand-written Kotlin in `sample-library` calls the members like any other Kotlin API:

```kotlin
// sample-library/.../sample/structs/StructsSample.kt (real source)
fun pointMagnitude(x: Int, y: Int): Int = Point(x, y).magnitude

fun offsetPoint(x: Int, y: Int, dx: Int, dy: Int): String = Point(x, y).offset(dx, dy).format()

fun pointOriginFormat(): String = Point.origin().format()

fun profileLabel(tag: String, active: Boolean, gradeCode: Int, mood: CatMood): String =
  Profile(tag, active, gradeCode.toChar(), mood).label

fun profileWithMood(
  tag: String,
  active: Boolean,
  gradeCode: Int,
  mood: CatMood,
  newMood: CatMood,
): String {
  val updated: Profile = Profile(tag, active, gradeCode.toChar(), mood).withMood(newMood)
  return "${updated.label}|${updated.isPlayful}"
}

fun profileRestingLabel(tag: String): String = Profile.resting(tag).label
```

## Value semantics

The generated `data class` gives value equality for free, and it holds across independent bridge
calls, not just against itself:

```kotlin
// sample-library/src/nativeMain/kotlin/.../sample/structs/StructsSample.kt (real source)
fun pointValueEqualityRoundTrip(): Boolean {
  val a = Point(3, 4)
  val b = a.copy()
  val c = a.copy(x = 99)
  val translatedOnce: Point = Geometry.translate(a, 1, 1)
  val translatedAgain: Point = Geometry.translate(a, 1, 1)
  return a == b &&
      a.hashCode() == b.hashCode() &&
      a != c &&
      translatedOnce == translatedAgain &&
      translatedOnce.hashCode() == translatedAgain.hashCode()
}
```

## Using it from C#

Structs only appear on the Kotlin side; the forward-exported functions that exercise them stay in the
existing `String`/`Int`/`Long`/`Float`/`Double`/`Boolean`/enum vocabulary rather than exporting a
struct forward (mapping a plain multi-field data class forward is [ADR-014](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/014-value-class-mapping.md)'s
concern, single-component value classes only, and out of scope here):

```C#
// sample-app/SampleApp.Tests/StructRoundTripTests.cs (real source)
[Fact]
public void TranslatePointDescription_MovesBothComponents()
{
    string result = StructsSample.translatePointDescription(1, 2, 10, 20);
    Assert.Equal("11,22", result);
}

[Theory]
[InlineData(0, 0, 3, 4, 7)]
[InlineData(1, 2, 1, 2, 0)]
[InlineData(-2, -3, 2, 3, 10)]
public void ManhattanDistance_TwoStructParameters(int x1, int y1, int x2, int y2, int expected)
{
    int result = StructsSample.manhattanDistance(x1, y1, x2, y2);
    Assert.Equal(expected, result);
}

[Fact]
public void CatteryCurrentProfile_GetSetRoundTrip()
{
    string result = StructsSample.catteryCurrentProfileRoundTrip("Household", "Mylo", true, 66, CatMood.Hungry);
    Assert.Equal("unset,false,63,SLEEPY|Mylo,true,66,HUNGRY", result);
}

[Theory]
[InlineData(3, -4, 7)]
[InlineData(0, 0, 0)]
[InlineData(-2, -3, 5)]
public void PointMagnitude_ComputedProperty(int x, int y, int expected)
{
    int result = StructsSample.pointMagnitude(x, y);
    Assert.Equal(expected, result);
}

[Fact]
public void PointOffset_InstanceMethodStructReturn_ThenFormat()
{
    string result = StructsSample.offsetPoint(1, 2, 10, 20);
    Assert.Equal("(11,22)", result);
}

[Fact]
public void PointOrigin_StaticFactory_ThenFormat()
{
    string result = StructsSample.pointOriginFormat();
    Assert.Equal("(0,0)", result);
}

[Fact]
public void ProfileWithMood_InstanceMethodStructReturn_ThenLabelAndIsPlayful()
{
    string result = StructsSample.profileWithMood(
        "Mylo", false, 66, CatMood.Hungry, CatMood.Playful);
    Assert.Equal("Mylo:Playful|true", result);
}

[Fact]
public void ProfileResting_StaticFactory_ThenLabel()
{
    string result = StructsSample.profileRestingLabel("Oreo");
    Assert.Equal("Oreo:Sleepy", result);
}
```

## Which structs bind

A struct is a classification candidate at all only if it is public, top-level, non-generic,
non-`ref struct`, and not an enum. It is then classified in this order:

**Shape A** iff:

1. Exactly one public instance constructor, the state constructor, has at least one parameter and
   covers all stored state. Other public constructors may bind as alternate constructors.
2. Every state-constructor parameter matches, case-insensitively by name, a public readable instance
   **property or public instance field** of the same type (`x` matches `X`). (Widened by ADR-058 to
   include fields, so an ordinary `struct { public int A; public int B; public S(int a, int b) {...} }`
   binds as Shape A instead of colliding with a generated `data class` primary constructor.)
3. Every component type is in the v1 vocabulary: primitives (including `bool` and `char`), `string`,
   and bound enums.
4. Its state constructor covers all stored state: the number of non-static instance fields equals
   the number of state-constructor parameters.

Components, and their order, are the state constructor's parameter list, not metadata field order.
Rule 4 exists because without it a struct with more stored fields than constructor parameters would
silently drop a field's value on every crossing.

**Shape B** iff no constructor satisfies Shape A, and every non-static field is covered: it is either
a public settable field, or the `[CompilerGenerated]` backing field of a public, non-static
auto-property with a public getter and a public setter (`set` or `init`). A field that fails
coverage (private, `readonly`, or a manually-written property whose setter cannot be proven to write
it) skips the whole struct rather than silently dropping that field. Components, and their order, are
the FieldDef declaration order (see [Shape B](#shape-b-structs-with-no-public-constructor) above). A
Shape B struct binds no constructors at all, alternate or otherwise: every public constructor on it is
skipped with its own diagnostic, since a Shape B struct's primary constructor already reaches every
component and an "alternate" would collide with it.

Otherwise the struct is **skipped**, with a `skipped_unsupported_struct` diagnostic naming the failed
rule. `sample-dependency/UnsupportedStructs.cs` has one adversarial case per rule:

```C#
// sample-dependency/UnsupportedStructs.cs (real source)
public readonly struct Overstuffed                 // fails Shape A rule 4, then Shape B (uncovered _hidden)
{
    private readonly int _hidden;
    public Overstuffed(int visible) { Visible = visible; _hidden = visible; }
    public int Visible { get; }
}

public struct PartlyHidden                          // Shape B: settable Visible, but _hidden is uncovered
{
    private int _hidden;
    public int Visible { get; set; }
}

public struct Frozen                                 // Shape B: a public readonly field is not settable
{
    public readonly int A;
    public int B;
}

public struct Manual                                  // Shape B: settable A, but not an auto-property
{
    private int _a;
    public int A { get => _a; set => _a = value; }
}

public struct Nothing { }                              // Shape B: zero stored state, zero components
```

Every one of these is skipped with a `skipped_unsupported_struct` diagnostic in `reverse-ir.json` and
generates no Kotlin type. Real diagnostics, trimmed, from
`sample-library/build/nuget-interop/reverse-ir.json`:

```json
{
  "kind": "skipped_unsupported_struct",
  "typeName": "Overstuffed",
  "reason": "field `_hidden` is private and no public component covers it: state would be silently dropped"
}
{
  "kind": "skipped_unsupported_struct",
  "typeName": "Frozen",
  "reason": "public field `A` is readonly and cannot be set by an object initializer"
}
{
  "kind": "skipped_unsupported_struct",
  "typeName": "Manual",
  "reason": "property `A` is settable but is not an auto-property (no compiler-generated backing field), so the struct's stored state cannot be proven covered"
}
{
  "kind": "skipped_unsupported_struct",
  "typeName": "Nothing",
  "reason": "struct has no settable public state: zero components"
}
```

## Limitations

- **Alternate constructors on a Shape B struct** are diagnosed, not bound: since a Shape B struct's
  primary reconstruction already reaches every component, an "alternate" would collide with it.
- **Manual (non-auto) settable properties as components** (`Manual` above) are not bound: metadata
  alone cannot prove that a hand-written setter writes the field it appears to.
- **Shape A constructor-parameter nullability** is not decoded (a pre-existing gap; Shape B's own
  components *are* nullability-decoded, since a Shape B struct's `default(T)` reachability makes a
  reference-typed component's nullness observable).
- **Void instance methods**, **setters**, **operators**, and the synthesized
  `Equals`/`GetHashCode`/`ToString`/`Deconstruct` surface on a struct are not bound (see
  [Struct methods and computed properties](#struct-methods-and-computed-properties)).
- **Nested struct components** (a struct field inside a struct) are not flattened.
- **Class-typed (handle) components** inside a struct are not supported: a `GCHandle` does not compose
  with an immutable value copy.
- **Generic structs** and **structs as collection elements** are not supported.

Tracked in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 9
and Phase 10.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="bridgeable-subset.md">The bridgeable subset</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="instance-members.md">Instance members</a>
        <a href="value-classes.md">Value classes</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/056-csharp-structs-in-kotlin.md">ADR-056: C# structs (value types) in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/057-csharp-overload-sets-in-kotlin.md">ADR-057: C# overload sets in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/058-csharp-shape-b-structs-in-kotlin.md">ADR-058: C# Shape B structs in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/014-value-class-mapping.md">ADR-014: Value class mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/054-reverse-bridge-registration-observability.md">ADR-054: Reverse-bridge registration observability</a>
    </category>
</seealso>
