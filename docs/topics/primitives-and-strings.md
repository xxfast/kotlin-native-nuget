# Primitives and strings

Primitive types follow the standard [Kotlin/Native C interop mappings](https://kotlinlang.org/docs/mapping-primitive-data-types-from-c.html#inspect-generated-kotlin-apis-for-a-c-library). Strings marshal as UTF-8. `kotlin.time.Instant` is a first-class built-in (same category as `String`), not a StableRef handle. Nullable primitives and nullable strings use a two-call pattern since a C ABI value type can't itself carry "no value".

| Kotlin | C# | Notes |
|---|---|---|
| `Byte` / `Short` / `Int` / `Long` | `sbyte` / `short` / `int` / `long` | |
| `UByte` / `UShort` / `UInt` / `ULong` | `byte` / `ushort` / `uint` / `ulong` | |
| `Float` / `Double` | `float` / `double` | |
| `Boolean` | `bool` | |
| `Char` | `char` | 2-byte scalar (`ushort` at the C ABI); property, parameter, and method return, see [ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md) |
| `String` | `string` | UTF-8 marshalling |
| `kotlin.time.Instant` | `System.DateTimeOffset` | UTC (`Offset == TimeSpan.Zero`); wire is epoch seconds + nanoseconds; property, constructor/method parameter, return; see Instant below and [ADR-076](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/076-kotlin-time-instant-mapping.md) |
| `Instant?` | `DateTimeOffset?` | two-call on property getters; method returns use single-call has-value + two OUT components; parameters fan out to has-value + components |
| `T?` (nullable primitive) | `T?` | two-call pattern on property and top-level returns (forward only); method/extension nullable returns use single-call `valueOut`, see [Classes and objects](classes-and-objects.md) and [ADR-002](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md) / [ADR-061](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md); `Boolean?` needs an explicit `[MarshalAs(UnmanagedType.I1)]` at both seams, see Nullable Boolean below and [ADR-069](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/069-nullable-boolean-marshalling.md) |
| `String?` | `string?` | forward: two-call pattern on top-level/property returns (this page); reverse: `NullableAttribute`-driven, see [Objects and handles](objects-and-handles.md) and [ADR-053](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md) |

## Kotlin

From `test-library/src/nativeMain/kotlin/.../Mappings.kt`:

```kotlin
fun string(): String = "Kotlin/Native!"

fun byte(): Byte = 42

fun ubyte(): UByte = 255u

fun short(): Short = 1024

fun ushort(): UShort = 65535u

fun int(): Int = 2_147_483_647

fun uint(): UInt = 4_294_967_295u

fun long(): Long = 9_223_372_036_854_775_807L

fun ulong(): ULong = 18_446_744_073_709_551_615u

fun float(): Float = 3.14f

fun double(): Double = 2.718281828459045

fun nullableInt(hasValue: Boolean): Int? = if (hasValue) 42 else null

fun nullableString(hasValue: Boolean): String? = if (hasValue) "hello" else null
```

## Generated C#

From `Interop.cs`, the `Mappings` static class. A plain primitive return is a single `[DllImport]` plus an error out-parameter:

```C#
public static partial class Mappings
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "string")]
    private static extern IntPtr @string_native(out IntPtr error);

    public static string @string()
    {
        IntPtr nativeResult = @string_native(out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return Marshal.PtrToStringUTF8(nativeResult)!;
    }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "byte")]
    private static extern sbyte @byte_native(out IntPtr error);

    public static sbyte @byte()
    {
        sbyte result = @byte_native(out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return result;
    }
    // ... ubyte, short_, ushort, int_, uint, long_, ulong, float_, double_ follow the same shape
}
```

The nullable two-call pattern: a `has_value` probe, then a `value` fetch only if present. Both calls
carry the same `out IntPtr error` out-parameter as every other sync export ([ADR-024](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/024-sync-exception-propagation.md)),
and the wrapper checks it after *each* crossing, since either call can independently throw:

```C#
[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nullableInt_has_value")]
private static extern bool nullableInt_has_value(bool hasValue, out IntPtr error);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nullableInt_value")]
private static extern int nullableInt_value(bool hasValue, out IntPtr error);

public static int? nullableInt(bool hasValue)
{
    bool __nuget_hasValue = nullableInt_has_value(hasValue, out IntPtr __nuget_error);
    if (__nuget_error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(__nuget_error);
    }
    if (!__nuget_hasValue) return null;
    int __nuget_value = nullableInt_value(hasValue, out IntPtr __nuget_error2);
    if (__nuget_error2 != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(__nuget_error2);
    }
    return __nuget_value;
}

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nullableString_has_value")]
private static extern bool nullableString_has_value(bool hasValue, out IntPtr error);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nullableString_value")]
private static extern IntPtr nullableString_value(bool hasValue, out IntPtr error);

public static string? nullableString(bool hasValue)
{
    bool __nuget_hasValue = nullableString_has_value(hasValue, out IntPtr __nuget_error);
    if (__nuget_error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(__nuget_error);
    }
    if (!__nuget_hasValue) return null;
    IntPtr __nuget_nativeResult = nullableString_value(hasValue, out IntPtr __nuget_error2);
    if (__nuget_error2 != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(__nuget_error2);
    }
    return Marshal.PtrToStringUTF8(__nuget_nativeResult);
}
```

Both `error` out-parameters were missing until a recent fix: the two-call pattern's generated
`[DllImport]`s never declared them, even though the native export side always writes through one, so
a nullable-returning function that threw corrupted memory (`SIGBUS`) instead of throwing
`KotlinException`. ADR-024's synchronous exception propagation never actually worked for
nullable-returning exports until this was corrected; see `NullableFunctionExceptionPropagationTests.cs`
for the regression coverage.

Kotlin identifiers that collide with C# keywords (`string`, `byte`, `short`, `int`, `long`) are escaped with `@` on the C# side; `short`/`int`/`long`/`double` also get a trailing underscore on the native entry point to dodge C reserved words.

## Using it from C#

From `IntegrationTests/MappingTests.cs`:

```C#
[Fact]
public void String_ReturnsExpectedValue()
{
    string result = Mappings.@string();
    Assert.Equal("Kotlin/Native!", result);
}

[Fact]
public void UInt_ReturnsExpectedValue()
{
    uint result = Mappings.@uint();
    Assert.Equal(4_294_967_295u, result);
}

[Fact]
public void NullableInt_WithValue_ReturnsValue()
{
    int? result = Mappings.nullableInt(true);
    Assert.Equal(42, result);
}

[Fact]
public void NullableInt_WithoutValue_ReturnsNull()
{
    int? result = Mappings.nullableInt(false);
    Assert.Null(result);
}

[Fact]
public void NullableString_WithValue_ReturnsValue()
{
    string? result = Mappings.nullableString(true);
    Assert.Equal("hello", result);
}
```

Nullable primitive and object *properties* on classes follow the same pattern; see `Cat.Owner` and `Cat.Age` in [Classes and objects](classes-and-objects.md).

## Char

`Char` is a 2-byte scalar on both sides (`char` in C#, `KChar`/`unsigned short` at the C ABI). It is
planned like any other ordinary primitive ([ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)):
as a property, a method parameter, and a method return.

From `test-library/src/nativeMain/kotlin/.../clinic/ClinicSample.kt`:

```kotlin
class Patient(val name: String) {
  val grade: Char = 'A'
  fun tag(initial: Char): String = "$initial-$name"
  fun initial(): Char = name.first()
}
```

Generated C#, from `Interop.cs`:

```C#
public char Grade
{
    get
    {
        char nativeResult = Native_Get_grade(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return nativeResult;
    }
}

public string Tag(char initial)
{
    IntPtr nativeResult = Native_Tag(_handle, initial, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
}

public char Initial()
{
    char result = Native_Initial(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return result;
}
```

From `IntegrationTests/ReturnAndPropertyMarshallingTests.cs` and `MethodParameterMarshallingTests.cs`:

```C#
[Fact]
public void Patient_Grade_IsCharProperty()
{
    using var patient = new Patient("Oreo");
    Assert.Equal('A', patient.Grade);
}

[Fact]
public void Patient_Initial_ReturnsFirstCharacter()
{
    using var patient = new Patient("Oreo");
    Assert.Equal('O', patient.Initial());
}

[Fact]
public void Patient_Tag_MarshalsCharParameter()
{
    using var patient = new Patient("Oreo");
    Assert.Equal("O-Oreo", patient.Tag('O'));
}
```

## Nullable Boolean

`Boolean?` maps to `bool?` at every forward position: constructor parameter, ordinary parameter,
property getter/setter, method/extension/`object`/companion method return, and top-level function
return. It needed its own fix beyond the general nullable-primitive two-call pattern above: Kotlin/Native
writes a `Boolean` as **one byte** (`kotlinx.cinterop.BooleanVar` is `Type(1)`, a 1-byte `putByte`
write), while C#'s default P/Invoke marshalling reads **four**. Every seam that crosses a `Boolean`
value now carries `[MarshalAs(UnmanagedType.I1)]`.

<warning>
    <p>Without <code>[MarshalAs(UnmanagedType.I1)]</code>, a native <code>false</code> can surface in
    C# as <code>true</code> whenever the marshaller's stack temp holds nonzero garbage in its upper
    three bytes. This was confirmed by a spike before the fix landed
    (<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/069-nullable-boolean-marshalling.md">ADR-069</a>).</p>
</warning>

The top-level position uses the same two-call pattern as `nullableInt`/`nullableString` above, with the
attribute added to both calls. From `test-library/src/nativeMain/kotlin/.../cat/NullableBooleanSample.kt`:

```kotlin
fun chipImplanted(state: Int): Boolean? = tribool(state)
```

Generated C#, from `Interop.cs`:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "chipImplanted_has_value")]
[return: MarshalAs(UnmanagedType.I1)]
private static extern bool chipImplanted_has_value(int state, out IntPtr error);

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "chipImplanted_value")]
[return: MarshalAs(UnmanagedType.I1)]
private static extern bool chipImplanted_value(int state, out IntPtr error);

public static bool? chipImplanted(int state)
{
    bool __nuget_hasValue = chipImplanted_has_value(state, out IntPtr __nuget_hasValueError);
    if (__nuget_hasValueError != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(__nuget_hasValueError);
    }
    if (!__nuget_hasValue) return null;
    bool __nuget_value = chipImplanted_value(state, out IntPtr __nuget_valueError);
    if (__nuget_valueError != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(__nuget_valueError);
    }
    return __nuget_value;
}
```

From `IntegrationTests/NullableBooleanTests.cs`, asserting `false` explicitly on a `bool?` (not
`Assert.NotNull`, which the width bug above would still pass):

```C#
[Fact]
public void NullableBooleanSample_ChipImplanted_False()
{
    Assert.False(NullableBooleanSample.chipImplanted(1));
}

[Fact]
public void NullableBooleanSample_ChipImplanted_Null()
{
    Assert.Null(NullableBooleanSample.chipImplanted(2));
}
```

The method/extension/`object`/companion single-call `valueOut` shape gets the same attribute on the
out-parameter; see [Classes and objects](classes-and-objects.md#method-returns).

<note>
    <p><code>Char?</code> is a separate, still-deferred problem: Kotlin <code>Char</code> is 2-byte
    UTF-16 against C#'s default 1-byte ANSI <code>char</code> marshalling, so it needs a
    <code>ushort</code>-narrowing wire type rather than <code>MarshalAs(I1)</code>. Non-null
    <code>Char</code> (above) is unaffected.</p>
</note>

## Instant

`kotlin.time.Instant` (stdlib 2.3+) is a first-class forward built-in, same category as `String`.
It maps to idiomatic `System.DateTimeOffset` with UTC offset zero, not to an opaque handle and not
via `include("kotlin.time")`.

| Kotlin | C# |
|---|---|
| `kotlin.time.Instant` | `System.DateTimeOffset` (`Offset == TimeSpan.Zero`) |
| `Instant?` | `DateTimeOffset?` |

Wire form is Instant's own storage: `epochSeconds: Long` + `nanosecondsOfSecond: Int`. The public
C# type truncates sub-100 ns (`nanos / 100` ticks) and throws `ArgumentOutOfRangeException` (via
checked tick math) for values outside year 0001–9999. Positions in v1: data-class / constructor
parameter, property get/set, method / object parameter and return, including nullables. Ordinary
ADR-062 path throughout.

From `test-library/src/nativeMain/kotlin/.../time/InstantSample.kt`:

```kotlin
data class CatPassport(
  val catName: String,
  val microchippedAt: Instant,
)

class VetAppointment(var arrivedAt: Instant? = null) {
  fun nextSlot(): Instant = OreoMicrochippedAt

  fun maybeCheckout(checkedOut: Boolean): Instant? =
    if (checkedOut) OreoMicrochippedAt else null

  fun secondsSinceEpoch(at: Instant): Long = at.epochSeconds

  fun echo(at: Instant): Instant = at

  fun describeDeparture(at: Instant?): String =
    if (at == null) "still in clinic" else "left at ${at.epochSeconds}"

  fun maybeEcho(at: Instant?): Instant? = at
}

object PassportOffice {
  fun defaultMicrochipDate(): Instant = OreoMicrochippedAt

  fun isAfterOreo(at: Instant): Boolean = at > OreoMicrochippedAt
}
```

Generated C#, from `Interop.cs` (`TestLibrary.Time`). A non-null Instant parameter fans out to two
ABI scalars; a non-null return uses void + two OUT component pointers; helpers live on
`NugetMarshal`:

```C#
internal static void ToInstantComponents(DateTimeOffset value, out long epochSeconds, out int nanosecondsOfSecond)
{
    DateTimeOffset utc = value.ToUniversalTime();
    long unixTicks = utc.UtcTicks - DateTimeOffset.UnixEpoch.UtcTicks;
    epochSeconds = System.Math.DivRem(unixTicks, TimeSpan.TicksPerSecond, out long remTicks);
    nanosecondsOfSecond = (int)(remTicks * 100);
}

internal static DateTimeOffset FromInstantComponents(long epochSeconds, int nanosecondsOfSecond)
{
    return DateTimeOffset.UnixEpoch.AddTicks(
        checked(epochSeconds * TimeSpan.TicksPerSecond + nanosecondsOfSecond / 100));
}
```

DTO constructor + property getter (NYTimes `published_date` shape):

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catpassport_create")]
private static extern IntPtr Native_Create(string catName, long microchippedAt_epochSeconds, int microchippedAt_nanosecondsOfSecond, out IntPtr error);

public CatPassport(string catName, DateTimeOffset microchippedAt)
{
    long microchippedAt_epochSeconds;
    int microchippedAt_nanosecondsOfSecond;
    NugetMarshal.ToInstantComponents(microchippedAt, out microchippedAt_epochSeconds, out microchippedAt_nanosecondsOfSecond);
    IntPtr handle = Native_Create(catName, microchippedAt_epochSeconds, microchippedAt_nanosecondsOfSecond, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    _handle = handle;
}

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catpassport_get_microchippedAt")]
private static extern void Native_Get_microchippedAt(IntPtr handle, out long epochSecondsOut, out int nanosecondsOfSecondOut, out IntPtr error);

public DateTimeOffset MicrochippedAt
{
    get
    {
        Native_Get_microchippedAt(_handle, out long epochSecondsOut, out int nanosecondsOfSecondOut, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return NugetMarshal.FromInstantComponents(epochSecondsOut, nanosecondsOfSecondOut);
    }
}
```

`Instant?` property (ADR-002 two-call getter + three-slot setter):

```C#
public DateTimeOffset? ArrivedAt
{
    get
    {
        bool hasValue = Native_Get_arrivedAt(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        if (!hasValue) return null;
        Native_Get_arrivedAt_value(_handle, out long epochSecondsOut, out int nanosecondsOfSecondOut, out IntPtr error2);
        if (error2 != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error2);
        }
        return NugetMarshal.FromInstantComponents(epochSecondsOut, nanosecondsOfSecondOut);
    }
    set
    {
        bool valueHasValue = value.HasValue;
        long value_epochSeconds = 0;
        int value_nanosecondsOfSecond = 0;
        if (valueHasValue)
        {
            NugetMarshal.ToInstantComponents(value.GetValueOrDefault(), out value_epochSeconds, out value_nanosecondsOfSecond);
        }
        Native_Set_arrivedAt(_handle, valueHasValue, value_epochSeconds, value_nanosecondsOfSecond, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
    }
}
```

Method return (void + two OUT) and `Instant?` return (has-value + two OUT):

```C#
public DateTimeOffset NextSlot()
{
    Native_NextSlot(_handle, out long epochSecondsOut, out int nanosecondsOfSecondOut, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return NugetMarshal.FromInstantComponents(epochSecondsOut, nanosecondsOfSecondOut);
}

public DateTimeOffset? MaybeCheckout(bool checkedOut)
{
    bool hasValue = Native_MaybeCheckout(_handle, checkedOut, out long epochSecondsOut, out int nanosecondsOfSecondOut, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return hasValue ? NugetMarshal.FromInstantComponents(epochSecondsOut, nanosecondsOfSecondOut) : null;
}
```

From `IntegrationTests/InstantMappingTests.cs` (samples use nanos divisible by 100 so equality
survives tick truncation):

```C#
[Fact]
public void CatPassport_MicrochippedAt_ConstructorAndGetter()
{
    using var passport = new CatPassport("Oreo", OreoMicrochippedAt);

    Assert.Equal("Oreo", passport.CatName);
    Assert.Equal(OreoMicrochippedAt, passport.MicrochippedAt);
    Assert.Equal(TimeSpan.Zero, passport.MicrochippedAt.Offset);
}

[Fact]
public void VetAppointment_ArrivedAt_SetAndClear()
{
    using var appt = new VetAppointment(null);

    appt.ArrivedAt = OreoMicrochippedAt;
    Assert.Equal(OreoMicrochippedAt, appt.ArrivedAt);
    Assert.Equal(TimeSpan.Zero, appt.ArrivedAt!.Value.Offset);

    appt.ArrivedAt = null;
    Assert.Null(appt.ArrivedAt);
}

[Fact]
public void VetAppointment_Echo_RoundTripsDateTimeOffset()
{
    using var appt = new VetAppointment(null);
    DateTimeOffset echoed = appt.Echo(OreoMicrochippedAt);

    Assert.Equal(OreoMicrochippedAt, echoed);
    Assert.Equal(TimeSpan.Zero, echoed.Offset);
}

[Fact]
public void PassportOffice_DefaultMicrochipDate_ReturnsOreoTime()
{
    DateTimeOffset date = PassportOffice.DefaultMicrochipDate();

    Assert.Equal(OreoMicrochippedAt, date);
    Assert.Equal(TimeSpan.Zero, date.Offset);
}
```

<warning>
    <p>Stay inside year 0001–9999 when materializing to <code>DateTimeOffset</code>. Instant
    sentinels such as <code>DISTANT_PAST</code> / <code>DISTANT_FUTURE</code> throw on the C# side
    rather than clamp. Sub-100-nanosecond Instant bits are dropped at the public C# boundary
    (<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/076-kotlin-time-instant-mapping.md">ADR-076</a>).</p>
</warning>

## Limitations

- Nullable *primitive* mapping (`Int?`, and friends) is forward-only (`→`): the reverse direction has
  no `Nullable<T>` wire format yet (deferred by [ADR-053](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md)
  Decision 3, not a two-call-pattern gap).
- Nullable *string* mapping is now `⇄`: forward uses this page's two-call pattern on property and
  top-level returns, reverse reads the bound assembly's `NullableAttribute` instead (see
  [Objects and handles](objects-and-handles.md)). The two mechanisms are unrelated; a reverse-bound
  `string?` never goes through a `has_value`/`value` pair.
- `Char?` stays deferred: it needs a `ushort`-narrowing wire type, a different fix from `Boolean?`'s
  `MarshalAs(I1)` above. Tracked in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)
  Phase 4.
- `Instant` is forward-only (`→`). Reverse `DateTimeOffset` → Kotlin Instant, `kotlin.time.Duration`
  → `TimeSpan`, Instant as a collection / Flow element, and the pre-0.7 `kotlinx.datetime.Instant`
  class form are deferred ([ADR-076](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/076-kotlin-time-instant-mapping.md)).
  kotlinx-datetime 0.7+ `typealias Instant = kotlin.time.Instant` expands via [ADR-018](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/018-type-alias-mapping.md).

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="data-classes.md">Data classes</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md">ADR-002: Nullable two-call pattern</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/024-sync-exception-propagation.md">ADR-024: Synchronous exception propagation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md">ADR-053: Nullable reference types in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/069-nullable-boolean-marshalling.md">ADR-069: Nullable Boolean marshalling</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/076-kotlin-time-instant-mapping.md">ADR-076: Kotlin time Instant mapping</a>
    </category>
</seealso>
