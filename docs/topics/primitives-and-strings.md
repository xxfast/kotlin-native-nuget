# Primitives and strings

Primitive types follow the standard [Kotlin/Native C interop mappings](https://kotlinlang.org/docs/mapping-primitive-data-types-from-c.html#inspect-generated-kotlin-apis-for-a-c-library). Strings marshal as UTF-8. Nullable primitives and nullable strings use a two-call pattern since a C ABI value type can't itself carry "no value".

| Kotlin | C# | Notes |
|---|---|---|
| `Byte` / `Short` / `Int` / `Long` | `sbyte` / `short` / `int` / `long` | |
| `UByte` / `UShort` / `UInt` / `ULong` | `byte` / `ushort` / `uint` / `ulong` | |
| `Float` / `Double` | `float` / `double` | |
| `Boolean` | `bool` | |
| `Char` | `char` | 2-byte scalar (`ushort` at the C ABI); property, parameter, and method return, see [ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md) |
| `String` | `string` | UTF-8 marshalling |
| `T?` (nullable primitive) | `T?` | two-call pattern on property and top-level returns (forward only); method/extension nullable returns use single-call `valueOut`, see [Classes and objects](classes-and-objects.md) and [ADR-002](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md) / [ADR-061](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md); `Boolean?` needs an explicit `[MarshalAs(UnmanagedType.I1)]` at both seams, see Nullable Boolean below and [ADR-069](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/069-nullable-boolean-marshalling.md) |
| `String?` | `string?` | forward: two-call pattern on top-level/property returns (this page); reverse: `NullableAttribute`-driven, see [Objects and handles](objects-and-handles.md) and [ADR-053](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md) |
| `kotlin.time.Instant` | `System.DateTimeOffset` | one `INT64` of .NET ticks; property, constructor parameter, method parameter, method return, top-level return, see Instant below and [ADR-076](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/076-instant-mapping.md) |
| `Instant?` | `DateTimeOffset?` | same wire form as `Instant`, rides the nullable-primitive `INT64` machinery above at all four positions |

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

`kotlin.time.Instant` binds as `System.DateTimeOffset`, a known stdlib type in the same category as
`String`, not a user-configured mapping. It crosses the wire as a single `INT64` of **.NET ticks**
(100ns since `0001-01-01T00:00:00 UTC`), the same tick unit `DateTimeOffset` itself uses, so nothing
is carried across that the destination type would discard one line later. `Instant?` rides the
nullable-primitive `INT64` machinery above (two-call on property/top-level returns, single-call
`valueOut` on method/extension returns) unchanged in shape.

<note>
    <p>The value returned to C# always has <code>Offset == TimeSpan.Zero</code>, since an
    <code>Instant</code> carries no zone. <code>DateTimeOffset</code> equality is instant-based, so
    a non-UTC value passed in still round-trips <code>==</code>, but the value read back is always
    UTC-normalized. A consumer who wants a <code>DateTime</code> should use
    <code>.UtcDateTime</code>; <code>.DateTime</code> has <code>Kind == Unspecified</code>.</p>
</note>

The two directions are not symmetric:

| direction | precision | range |
|---|---|---|
| C# → Kotlin | exact: every `DateTimeOffset` tick is representable in `Instant` | total |
| Kotlin → C# | truncated to 100ns, floored toward the epoch-0001 origin (the ticks are non-negative) | years 0001-9999 only |

<warning>
    <p>An <code>Instant</code> outside years 0001-9999, which includes
    <code>Instant.DISTANT_PAST</code> and <code>Instant.DISTANT_FUTURE</code>, throws rather than
    clamping or wrapping. The Kotlin-side <code>require</code> failure surfaces through the existing
    <code>errorOut</code> slot as a <code>KotlinArgumentException</code>.</p>
</warning>

From `test-library/src/nativeMain/kotlin/.../test/cat/SightingLog.kt`, crossing every position the
mapping supports:

```kotlin
class SightingLog(val firstSeen: Instant, var lastSeen: Instant?) {
  fun elapsedSeconds(until: Instant): Long = until.epochSeconds - firstSeen.epochSeconds

  fun nextExpected(): Instant =
    Instant.fromEpochSeconds(epochSeconds = (lastSeen ?: firstSeen).epochSeconds + 86_400L)

  fun earliestUnconfirmed(): Instant? = if (lastSeen == null) null else firstSeen

  fun escapedForEternity(): Instant = Instant.DISTANT_FUTURE
}

fun sightingEpoch(): Instant =
  Instant.fromEpochSeconds(epochSeconds = 1_700_000_000L, nanosecondAdjustment = 123_456_789)

fun parseSighting(text: String): Instant? =
  if (text.contains("Oreo")) sightingEpoch() else null
```

Generated C#, from `Interop.cs`:

```C#
public class SightingLog : IDisposable
{
    public SightingLog(global::System.DateTimeOffset firstSeen, global::System.DateTimeOffset? lastSeen)
    {
        IntPtr handle = Native_Create(firstSeen.UtcTicks, lastSeen.HasValue, lastSeen.GetValueOrDefault().UtcTicks, out IntPtr error);
        // ...
    }

    public global::System.DateTimeOffset FirstSeen { get; }

    public global::System.DateTimeOffset? LastSeen { get; set; }

    public long ElapsedSeconds(global::System.DateTimeOffset until) { /* ... */ }

    public global::System.DateTimeOffset NextExpected() { /* ... */ }

    public global::System.DateTimeOffset? EarliestUnconfirmed() { /* ... */ }

    public global::System.DateTimeOffset EscapedForEternity() { /* ... */ }
}

public static partial class SightingLogKt
{
    public static global::System.DateTimeOffset sightingEpoch() { /* ... */ }

    public static global::System.DateTimeOffset? parseSighting(string text) { /* ... */ }
}
```

Return positions lift the wire ticks with `new DateTimeOffset(nativeResult, TimeSpan.Zero)`; input
positions take `.UtcTicks`, load-bearing so a consumer holding a `+05:30` `DateTimeOffset` doesn't
send its wall-clock ticks.

From `IntegrationTests/InstantMappingTests.cs`:

```C#
[Fact]
public void UnixEpoch_RoundTrips_ThroughConstructorAndValProperty()
{
    using var log = new SightingLog(DateTimeOffset.UnixEpoch, null);

    Assert.Equal(621355968000000000L, log.FirstSeen.UtcTicks);
    Assert.Equal(TimeSpan.Zero, log.FirstSeen.Offset);
}

[Fact]
public void SubHundredNanosecondKotlinValue_TruncatesTowardEpochOrigin_DoesNotRound()
{
    // Kotlin's sightingEpoch() carries a 123_456_789 ns adjustment; 89 ns of that is below
    // the wire form's 100ns tick resolution. Truncation floors to ...4567, not ...4568.
    var result = SightingLogKt.sightingEpoch();

    Assert.Equal(638355968001234567L, result.UtcTicks);
}

[Fact]
public void NonUtcDateTimeOffset_RoundTripsAsEqual_AndComesBackUtcNormalized()
{
    var at = new DateTimeOffset(2024, 3, 8, 12, 34, 56, 123, new TimeSpan(5, 30, 0));
    using var log = new SightingLog(at, null);

    // DateTimeOffset equality is instant-based, so this holds even though log.FirstSeen's
    // Offset differs from `at`'s.
    Assert.Equal(at, log.FirstSeen);
    Assert.Equal(TimeSpan.Zero, log.FirstSeen.Offset);
}

[Fact]
public void EscapedForEternity_OutOfRangeInstant_IsExactType_KotlinArgumentException()
{
    using var log = new SightingLog(DateTimeOffset.UnixEpoch, null);

    var ex = Assert.ThrowsAny<ArgumentException>(() => log.EscapedForEternity());

    Assert.IsType<KotlinArgumentException>(ex);
}
```

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
- `Instant` mapping is forward-only (`→`); there is no reverse `DateTimeOffset` → Kotlin mapping yet.
  `kotlin.time.Duration` → `TimeSpan` and legacy `kotlinx.datetime.Instant` are deliberately deferred,
  not required to ship `Instant` (see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)
  Phase 4).

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="objects-and-handles.md">Objects and handles</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md">ADR-002: Nullable two-call pattern</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/024-sync-exception-propagation.md">ADR-024: Synchronous exception propagation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md">ADR-053: Nullable reference types in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/069-nullable-boolean-marshalling.md">ADR-069: Nullable Boolean marshalling</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/076-instant-mapping.md">ADR-076: kotlin.time.Instant mapping</a>
    </category>
</seealso>
