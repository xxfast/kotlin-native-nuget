# C# structs

A bridgeable C# `struct` becomes an immutable Kotlin `data class`. No struct ever crosses the C ABI
itself: a struct-typed parameter expands into one ABI argument per component, and a struct-typed
return expands into `void` plus one out-pointer per component. There is no handle, no `close()`, no
`Cleaner`, and equality is structural. Its unique state constructor claims zero registration slots;
each alternate public constructor claims one slot and reconstructs the returned components through
out-pointers.

| C# | Kotlin |
|---|---|
| a bridgeable struct (see [Which structs bind](#which-structs-bind) below) | immutable `data class`, one `val` per component |
| struct-typed parameter | one ABI argument per component |
| struct-typed return | thunk return becomes `void`; one out-pointer argument per component |
| struct-typed property getter | as a return: out-pointers |
| struct-typed property setter | as a parameter: decomposed arguments |
| alternate public constructor | Kotlin secondary constructor; one registration slot and one out-pointer per state component |

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
}

public static class Geometry
{
    public static Point Translate(Point p, int dx, int dy) => new Point(p.X + dx, p.Y + dy);
    public static string Describe(Point p) => $"({p.X}, {p.Y})";
    public static int Manhattan(Point a, Point b) => Math.Abs(a.X - b.X) + Math.Abs(a.Y - b.Y);
}
```

Generated Kotlin surface (`build/nuget-interop/kotlin/nativeMain/sample/structs/Point.kt`, real
output):

```kotlin
/**
 * Kotlin value type for the C# struct `SampleDependency.Point`.
 *
 * Copied by value across the bridge: equality is structural, and there is nothing to close.
 * Mutating this value never affects the C# side (a copy crossed the boundary); use [copy].
 */
internal data class Point(
  val x: Int,
  val y: Int,
) {
  private constructor(components: PointConstructorComponents) : this(components.x, components.y)
  constructor(size: Size) : this(construct__4df77a50827105506f4258372b08561b(size))
  constructor(unit: Boolean) : this(construct__e802c1ad168a16a38ec169d93b7f55fc(unit))
  constructor(value: Int) : this(construct__e71e420dcea5e3c083737f3c34b97ec7(value))
}
```

The type is generated `internal`, not `public`: it must stay invisible to the forward-direction (KSP)
exporter's own public-API scan, so a reverse-bound struct never gets re-exported forward into the
package's own `Interop.cs`.

`Translate` shows the full shape: a struct parameter decomposed into leading arguments, and a struct
return assembled from out-pointers:

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
```

## Which structs bind

A struct is bridgeable (Shape A) only if **all** of:

1. It is public, top-level, non-generic, non-`ref struct`, and not an enum.
2. Exactly one public instance constructor, the state constructor, has at least one parameter and
   covers all stored state. Other public constructors may bind as alternate constructors.
3. Every state-constructor parameter matches, case-insensitively by name, a public readable instance
   property of the same type (`x` matches `X`).
4. Every component type is in the v1 vocabulary: primitives (including `bool` and `char`), `string`,
   and bound enums.
5. Its state constructor covers all stored state: the number of non-static instance fields equals
   the number of state-constructor parameters.

Components, and their order, are the state constructor's parameter list, not metadata field order.
Rule 5 exists because without it a struct with more stored fields than constructor parameters would silently
drop a field's value on every crossing; `sample-dependency/UnsupportedStructs.cs` has both adversarial
cases:

```C#
// sample-dependency/UnsupportedStructs.cs (real source)
public struct Unsupported { public int A; public int B; }          // no public instance constructor

public readonly struct Overstuffed
{
    private readonly int _hidden;                                   // ctor covers only 1 of 2 fields
    public Overstuffed(int visible) { Visible = visible; _hidden = visible; }
    public int Visible { get; }
}
```

Both are skipped with a `skipped_unsupported_struct` diagnostic in `reverse-ir.json` and generate no
Kotlin type and no handle wrapper:

```json
{
  "kind": "skipped_unsupported_struct",
  "typeName": "Overstuffed",
  "memberName": "Overstuffed",
  "memberSignature": "Sample.Structs.Overstuffed",
  "reason": "struct has 2 stored instance field(s) but its constructor takes 1 parameter(s) — state would be silently dropped",
  "hint": "See ADR-056 Decision 3a: a bridgeable struct has exactly one public constructor covering all stored state, with primitive/string/bound-enum components matching the constructor parameters case-insensitively."
}
```

## Limitations

- **Shape B structs** (no public constructor; public fields or settable auto-properties) are not
  bound. Metadata extraction for public fields does not exist yet.
- **Struct methods and computed properties** are not bound. A struct gets a registration export only
  when it has at least one bridgeable alternate constructor.
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
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/014-value-class-mapping.md">ADR-014: Value class mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/054-reverse-bridge-registration-observability.md">ADR-054: Reverse-bridge registration observability</a>
    </category>
</seealso>
