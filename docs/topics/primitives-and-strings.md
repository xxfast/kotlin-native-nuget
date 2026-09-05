# Primitives and strings

Primitive types follow the standard [Kotlin/Native C interop mappings](https://kotlinlang.org/docs/mapping-primitive-data-types-from-c.html#inspect-generated-kotlin-apis-for-a-c-library). Strings marshal as UTF-8. Nullable primitives and nullable strings use a two-call pattern since a C ABI value type can't itself carry "no value".

| Kotlin | C# | Notes |
|---|---|---|
| `Byte` / `Short` / `Int` / `Long` | `sbyte` / `short` / `int` / `long` | `Byte`/`Short` also bind as a `List`/`Map`/`Set` component, see [Collections](collections.md#narrow-primitives-and-char-as-collection-components) and [ADR-098](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md) |
| `UByte` / `UShort` / `UInt` / `ULong` | `byte` / `ushort` / `uint` / `ulong` | all four also bind as a `List`/`Map`/`Set` component, see [Collections](collections.md#narrow-primitives-and-char-as-collection-components) and [ADR-098](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md) |
| `Float` / `Double` | `float` / `double` | |
| `Boolean` | `bool` | |
| `Char` | `char` | 2-byte scalar (`ushort` at the C ABI); property, parameter, method return, and `List`/`Map`/`Set` component, see [Char](#char) below, [ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md) and [ADR-098](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md) |
| `String` | `string` | UTF-8 marshalling |
| `T?` (nullable primitive) | `T?` | two-call pattern on property and top-level returns (forward only); method/extension nullable returns use single-call `valueOut`, see [Classes and objects](classes-and-objects.md) and [ADR-002](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md) / [ADR-061](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md); `Boolean?` needs an explicit `[MarshalAs(UnmanagedType.I1)]` at both seams, see Nullable Boolean below and [ADR-069](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/069-nullable-boolean-marshalling.md) |
| `String?` | `string?` | forward: two-call pattern on top-level/property returns (this page); reverse: `NullableAttribute`-driven, see [Objects and handles](objects-and-handles.md) and [ADR-053](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md) |
| `kotlin.time.Instant` | `System.DateTimeOffset` | one `INT64` of .NET ticks; property, constructor parameter, method parameter, method return, top-level return, see Instant below and [ADR-076](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/076-instant-mapping.md) |
| `Instant?` | `DateTimeOffset?` | same wire form as `Instant`, rides the nullable-primitive `INT64` machinery above at all four positions |
| `kotlin.time.Duration` | `System.TimeSpan` | one `INT64` of `TimeSpan` ticks; property, constructor parameter, method parameter, method return, top-level return, see Duration below and [ADR-103](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/103-duration-mapping.md) |
| `Duration?` | `TimeSpan?` | same wire form as `Duration`, rides the nullable-primitive `INT64` machinery above at all four positions |
| `kotlin.uuid.Uuid` | `System.Guid` | third known stdlib type; crosses as the RFC 9562 hex-dash `String`, not a binary wire; property (get and set), constructor parameter, method parameter, method return, top-level return, static (`object`) route, see Uuid below and [ADR-106](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/106-uuid-mapping.md) |
| `Uuid?` | `Guid?` | rides the null-pointer sentinel the `String?` wire already uses, not a has-value pair |

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

Kotlin identifiers that collide with C# keywords (`string`, `byte`, `short`, `int`, `long`) are escaped with `@` on the C# side; `short`/`int`/`long`/`double` also get a trailing underscore on the native entry point to dodge C reserved words. This applies to a *parameter* name too, not just a declaration name: a Kotlin parameter literally named `abstract`, `default`, `params`, `ref`, or any other C# reserved word renders as a verbatim identifier (`@abstract`) at every C# position, the public wrapper declaration, the `[DllImport]` extern, and every use site (a call argument, a member access like `@ref._handle`, or a marshalling local like `@paramsHandle`).

From `test-library/src/nativeMain/kotlin/.../issue65/Issue65Sample.kt`:

```kotlin
data class Issue65Article(val abstract: String, val title: String) {
  fun describe(default: Int): String = "$title x$default"
}
```

Generated C#, from `Interop.cs`:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue65article_create")]
private static extern IntPtr Native_Create([MarshalAs(UnmanagedType.LPUTF8Str)] string @abstract, [MarshalAs(UnmanagedType.LPUTF8Str)] string title, out IntPtr error);

public Issue65Article(string @abstract, string title)
{
    IntPtr handle = Native_Create(@abstract, title, out IntPtr error);
    // ...
}

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue65article_describe")]
private static extern IntPtr Native_Describe(IntPtr handle, int @default, out IntPtr error);

public string Describe(int @default)
{
    IntPtr nativeResult = Native_Describe(_handle, @default, out IntPtr error);
    // ...
}
```

A consumer passes a named argument the same way, verbatim: `new Issue65Article(@abstract: "...", title: "...")`. From `IntegrationTests/Issue65Tests.cs`:

```C#
using var article = new Issue65Article(
    @abstract: "Mylo slept through the entire press conference.",
    title: "Cat Naps Through Budget Address");
```

<note>
    <p>Only the ordinary synchronous forward callable plan is covered: constructors (including a
    data class's <code>Copy</code>), class methods, top-level and extension functions, and
    value-class members. A keyword-named parameter on a legacy route (suspend, <code>Flow</code>,
    lambda, sealed, generic, interface-bridge) is not yet escaped; see
    <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md">ROADMAP.md</a>.</p>
</note>

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
as a property, a method parameter, a method return, and, since
[ADR-098](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md),
a `List`/`Map`/`Set` component (see [Collections](collections.md#narrow-primitives-and-char-as-collection-components)).

<warning>
    <p>Every non-ASCII <code>Char</code> at an ordinary position (property, parameter, return) was
    silently corrupted before ADR-098: the generated <code>[DllImport]</code> declared a bare
    <code>char</code> with no <code>CharSet</code>/<code>MarshalAs</code>, which defaults to
    <code>CharSet.Ansi</code> and truncates to one byte. <code>Tag('é')</code> arrived Kotlin-side as
    <code>'Ã'</code>; a <code>Char</code> return or property getter for a non-ASCII value came back
    as <code>U+FFFD</code>. No exception, no diagnostic. Every <code>char</code> slot the generator
    emits now carries <code>[MarshalAs(UnmanagedType.U2)]</code>, both new and already-shipped: ASCII
    call sites are unaffected, non-ASCII call sites go from corrupt to correct. This is a behaviour
    change on already-shipped members, so anything relying on the truncated byte was already
    broken.</p>
</warning>

From `test-library/src/nativeMain/kotlin/.../clinic/ClinicSample.kt`:

```kotlin
class Patient(val name: String) {
  val grade: Char = 'A'
  fun tag(initial: Char): String = "$initial-$name"
  fun initial(): Char = name.first()
}
```

Generated C#, from `Interop.cs`. Every `char` slot carries `[MarshalAs(UnmanagedType.U2)]`, on the
parameter, the return, and the property getter:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "patient_get_grade")]
[return: MarshalAs(UnmanagedType.U2)]
private static extern char Native_Get_grade(IntPtr handle, out IntPtr error);

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

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "patient_tag")]
private static extern IntPtr Native_Tag(IntPtr handle, [MarshalAs(UnmanagedType.U2)] char initial, out IntPtr error);

public string Tag(char initial)
{
    IntPtr nativeResult = Native_Tag(_handle, initial, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
}

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "patient_initial")]
[return: MarshalAs(UnmanagedType.U2)]
private static extern char Native_Initial(IntPtr handle, out IntPtr error);

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

The ASCII cells above pass byte-identically through either wire shape, which is exactly why the bug
survived undetected: only a non-ASCII cell can tell a correct `char` from an ANSI-truncated one. From
`IntegrationTests/CharPositionMarshallingTests.cs`:

```C#
[Fact]
public void Patient_Tag_LatinCharParameter_ArrivesUncorrupted()
{
    using var patient = new Patient("Oreo");

    // U+00E9. Two UTF-8 bytes (C3 A9), truncated to 0xC3, so the shipped wire delivers 'Ã'.
    Assert.Equal("é-Oreo", patient.Tag('é'));
}

[Fact]
public void Patient_Initial_LatinCharReturn_ComesBackUncorrupted()
{
    // The return leg breaks differently from the parameter leg: not truncated to a plausible
    // wrong character, but lost entirely to U+FFFD, because the low byte decodes as an invalid
    // lone ANSI byte. Émile is Oreo's least favourite housemate.
    using var patient = new Patient("Émile");

    Assert.Equal('É', patient.Initial());
}
```

<note>
    <p>A bare <code>Char?</code> (not inside a collection) still has no wire: it needs a has-value
    fan-out (<code>hasValueFanOutInner()</code> has no <code>Char</code> case yet), not a width fix,
    since the width question is answered by this ADR. See Limitations below. A lone surrogate
    <code>Char</code> (an unpaired <code>\uD83D</code>-style code unit) does not round-trip under any
    wire shape and is out of scope: neither a Kotlin <code>Char</code> nor a C# <code>char</code> can
    legitimately hold one.</p>
</note>

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
    <p><code>Char?</code> at a bare (non-collection) position is still deferred, but not for the
    reason once assumed: the width question is already answered above,
    <code>[MarshalAs(UnmanagedType.U2)]</code>, on <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/069-nullable-boolean-marshalling.md">ADR-069</a>'s
    exact precedent, and the Kotlin side needs no change since it already emits <code>KChar</code> as
    <code>unsigned short</code>. What remains is only the has-value fan-out
    (<code>hasValueFanOutInner()</code> has no <code>Char</code> case), the same shape this section's
    <code>Boolean?</code> fix used. Non-null <code>Char</code> (above) is unaffected, and
    <code>List&lt;Char?&gt;</code> already works, since a collection element rides a null pointer
    instead of a has-value pair.</p>
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

## Duration

`kotlin.time.Duration` binds as `System.TimeSpan`, the second known-scalar branch alongside
`Instant` (same mechanism, cloned at every seam). It crosses the wire as a single `INT64` of
**`TimeSpan` ticks** (100ns, signed, the full `Int64` domain), `TimeSpan`'s own tick unit, so
nothing is carried across that the destination type would discard one line later. `Duration?`
rides the nullable-primitive `INT64` machinery above unchanged in shape.

Unlike `Instant`, the wire is signed and total in the C# → Kotlin direction: `new TimeSpan(long)`
never throws, for any tick value. The throwing direction is Kotlin → C#, and it throws twice over:

<warning>
    <p><code>Duration.INFINITE</code> and <code>Duration.NEG_INFINITE</code> have no
    <code>System.TimeSpan</code> counterpart and throw rather than saturating to
    <code>TimeSpan.MaxValue</code>/<code>MinValue</code> or aliasing
    <code>Timeout.InfiniteTimeSpan</code> (itself a magic -1ms value, not a real infinity). A
    <em>finite</em> Duration can also be out of range: <code>Duration</code>'s millisecond band
    spans about ±146 million years, far wider than <code>TimeSpan</code>'s ±10,675,199 days (about
    ±29,228 years), so a finite Duration outside that range throws too. Both cases surface the
    Kotlin-side <code>require</code> failure through the existing <code>errorOut</code> slot as a
    <code>KotlinArgumentException</code>.</p>
</warning>

The two directions are not symmetric:

| direction | precision | range |
|---|---|---|
| C# → Kotlin | exact within about ±146 years (`Duration`'s nanosecond band); silent 1ms granularity beyond, matching `Duration`'s own construction semantics (`Long.nanoseconds`) | total: every `TimeSpan` a consumer can hold is representable |
| Kotlin → C# | truncated toward zero to 100ns | about ±10,675,199 days; `INFINITE`/`NEG_INFINITE` or an out-of-range finite `Duration` throws |

From `test-library/src/nativeMain/kotlin/.../test/cat/NapTracker.kt`, crossing every position the
mapping supports:

```kotlin
class NapTracker(val longestNap: Duration, var lastNap: Duration?) {
  fun extend(extra: Duration): Duration = longestNap + extra

  fun shortestNap(): Duration? {
    val last = lastNap ?: return null
    return if (last < longestNap) last else longestNap
  }

  fun describe(nap: Duration?): String =
    if (nap == null) "no nap recorded" else "napped for ${nap.inWholeMilliseconds}ms"

  fun maybeEcho(nap: Duration?): Duration? = nap

  fun echo(nap: Duration): Duration = nap
}

object NapClock {
  fun defaultNap(): Duration = 90.minutes

  fun isLong(nap: Duration): Boolean = nap > 1.hours

  fun infiniteNap(): Duration = Duration.INFINITE

  fun aeonNap(): Duration = (200_000L * 365).days
}

fun napEpsilon(): Duration = 150.nanoseconds

fun parseNap(text: String): Duration? = if (text.contains("Oreo")) 20.minutes else null
```

Generated C#, from `Interop.cs`:

```C#
public class NapTracker : IDisposable
{
    public NapTracker(global::System.TimeSpan longestNap, global::System.TimeSpan? lastNap)
    {
        IntPtr handle = Native_Create(longestNap.Ticks, lastNap.HasValue, lastNap.GetValueOrDefault().Ticks, out IntPtr error);
        // ...
    }

    public global::System.TimeSpan LongestNap { get; }

    public global::System.TimeSpan? LastNap { get; set; }

    public global::System.TimeSpan Extend(global::System.TimeSpan extra) { /* ... */ }

    public global::System.TimeSpan? ShortestNap() { /* ... */ }

    public string Describe(global::System.TimeSpan? nap) { /* ... */ }

    public global::System.TimeSpan? MaybeEcho(global::System.TimeSpan? nap) { /* ... */ }

    public global::System.TimeSpan Echo(global::System.TimeSpan nap) { /* ... */ }
}

public static class NapClock
{
    public static global::System.TimeSpan DefaultNap() { /* ... */ }

    public static bool IsLong(global::System.TimeSpan nap) { /* ... */ }

    public static global::System.TimeSpan InfiniteNap() { /* ... */ }

    public static global::System.TimeSpan AeonNap() { /* ... */ }
}

public static partial class NapTrackerKt
{
    public static global::System.TimeSpan napEpsilon() { /* ... */ }

    public static global::System.TimeSpan? parseNap(string text) { /* ... */ }
}
```

Return positions lift the wire ticks with `new TimeSpan(nativeResult)`; input positions take
`.Ticks`.

From `IntegrationTests/DurationMappingTests.cs`:

```C#
[Fact]
public void TimeSpanMaxValue_EchoesBack_AtMostOneMillisecondShort()
{
    // C# -> Kotlin is total, but MaxValue lands in Duration's millisecond band, so the way
    // back loses 5807 ticks (well under 1ms). Documented contract, not a defect.
    using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);

    var result = tracker.Echo(TimeSpan.MaxValue);

    Assert.Equal(9223372036854770000L, result.Ticks);
    Assert.True(TimeSpan.MaxValue - result < TimeSpan.FromMilliseconds(1));
}

[Fact]
public void SubHundredNanosecondKotlinValue_TruncatesTowardZero_DoesNotRound()
{
    // Kotlin's napEpsilon() is 150 ns; 50 ns of that is below the wire form's 100ns tick
    // resolution. Truncation gives 1 tick, rounding would give 2.
    var result = NapTrackerKt.napEpsilon();

    Assert.Equal(1L, result.Ticks);
}

[Fact]
public void NapClock_InfiniteNap_IsExactType_KotlinArgumentException()
{
    var ex = Assert.ThrowsAny<ArgumentException>(() => NapClock.InfiniteNap());

    Assert.IsType<KotlinArgumentException>(ex);
}

[Fact]
public void NapClock_AeonNap_OutOfTimeSpanRangeButFinite_IsExactType_KotlinArgumentException()
{
    var ex = Assert.ThrowsAny<ArgumentException>(() => NapClock.AeonNap());

    Assert.IsType<KotlinArgumentException>(ex);
}
```

## Uuid

`kotlin.uuid.Uuid` binds as `System.Guid`, a third known-stdlib type in the same category as
`String`, `Instant` and `Duration`. Unlike `Instant`/`Duration`, it does not ride the `INT64`
machinery above: `Uuid` is 128 bits, so it crosses as the RFC 9562 lowercase hex-dash **string**
(`Uuid.toString()`/`Uuid.parse()` on the Kotlin side, `Guid.ToString()`/`Guid.Parse()` on the C#
side), reusing the existing `String` wire row rather than a new binary shape. `Uuid?` therefore
rides the null-pointer sentinel the `String?` wire already carries, not a has-value pair.

<note>
    <p>Both directions are exact and total: every byte of a <code>Uuid</code> survives the round
    trip through the hex-dash string, and <code>Guid.Empty</code> ⇄ <code>Uuid.NIL</code> is a
    real value on the wire, not a null sentinel. A per-crossing string allocation and parse is the
    cost of this choice; a binary two-<code>INT64</code> wire was considered and rejected for
    narrowness (see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/106-uuid-mapping.md">ADR-106</a>'s
    Alternatives), and is tracked as a possible follow-up in
    <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md">ROADMAP.md</a> if
    the allocation cost ever matters.</p>
</note>

From `test-library/src/nativeMain/kotlin/.../test/cat/Microchip.kt`, crossing every position the
mapping supports:

```kotlin
class Microchip(val chipId: Uuid, var previousChipId: Uuid?) {
  fun matches(candidate: Uuid): Boolean = candidate == chipId

  fun reissue(): Uuid = Uuid.random()

  fun lastRetired(): Uuid? = previousChipId

  fun describe(tag: Uuid?): String = if (tag == null) "no tag scanned" else "scanned $tag"

  fun maybeEcho(tag: Uuid?): Uuid? = tag

  fun echo(tag: Uuid): Uuid = tag
}

object MicrochipRegistry {
  fun nil(): Uuid = Uuid.NIL

  fun isNil(tag: Uuid): Boolean = tag == Uuid.NIL
}

fun wellKnownChip(): Uuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")

fun parseChip(text: String): Uuid? = try {
  Uuid.parse(text)
} catch (e: IllegalArgumentException) {
  null
}

data class ChipRecord(val id: Uuid)
```

Generated C#, from `Interop.cs`:

```C#
public Microchip(global::System.Guid chipId, global::System.Guid? previousChipId)
{
    IntPtr handle = Native_Create(chipId.ToString(), previousChipId?.ToString(), out IntPtr error);
    // ...
}

public global::System.Guid ChipId
{
    get
    {
        IntPtr nativeResult = Native_Get_chipId(_handle, out IntPtr error);
        // ...
        return global::System.Guid.Parse(Marshal.PtrToStringUTF8(nativeResult)!);
    }
}

public global::System.Guid? PreviousChipId
{
    get { /* nativeResult == IntPtr.Zero ? null : global::System.Guid.Parse(...) */ }
    set { /* Native_Set_previousChipId(_handle, value?.ToString(), out IntPtr error); */ }
}

public bool Matches(global::System.Guid candidate) { /* candidate.ToString() */ }

public global::System.Guid Reissue() { /* ... */ }

public global::System.Guid? LastRetired() { /* ... */ }
```

The static (`object`) route and the top-level route use the same `.ToString()`/`Guid.Parse()`
pair:

```C#
public static class MicrochipRegistry
{
    public static global::System.Guid Nil() { /* ... */ }

    public static bool IsNil(global::System.Guid tag) { /* tag.ToString() */ }
}

public static partial class MicrochipKt
{
    public static global::System.Guid wellKnownChip() { /* ... */ }
}
```

From `IntegrationTests/UuidMappingTests.cs`:

```C#
[Fact]
public void WellKnownChip_TopLevelReturn_RendersTheSameStringKotlinParsed()
{
    Guid chip = MicrochipKt.wellKnownChip();

    Assert.Equal(WellKnown, chip.ToString());
}

[Fact]
public void Registry_Nil_StaticReturn_IsGuidEmpty()
{
    Assert.Equal(Guid.Empty, MicrochipRegistry.Nil());
}

[Fact]
public void RandomGuid_RoundTripsThroughConstructorAndValProperty()
{
    var minted = Guid.NewGuid();

    using var chip = new Microchip(minted, null);

    Assert.Equal(minted, chip.ChipId);
}

[Fact]
public void PreviousChipId_NullableVarProperty_HoldsGuidEmptyDistinctlyFromNull()
{
    using var chip = new Microchip(Guid.Parse(WellKnown), null);

    chip.PreviousChipId = Guid.Empty;

    Assert.NotNull(chip.PreviousChipId);
    Assert.Equal(Guid.Empty, chip.PreviousChipId!.Value);
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
- A bare `Char?` (not inside a collection) still has no route and aborts `packNuget`: the wire is
  answered (`[MarshalAs(UnmanagedType.U2)]`, see [Char](#char) above), what's missing is the
  has-value fan-out. `List<Char?>`/`Set<Char?>`/`Map<K, Char?>` already work, since a collection
  component rides a null pointer instead. Tracked in
  [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 4.
- A lone surrogate `Char` (an unpaired UTF-16 code unit) does not round-trip under any wire shape and
  is deliberately not fixture-covered; deferred as degenerate input. Tracked in
  [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- `Instant` and `Duration` mapping are forward-only (`→`); there is no reverse `DateTimeOffset`/`TimeSpan`
  → Kotlin mapping yet. Legacy `kotlinx.datetime.Instant` is deliberately deferred, a distinct
  qualified name on a distinct dependency (see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)
  Phase 4).
- `Uuid` mapping is forward-only (`→`) and deliberately excludes `List<Uuid>`/`Map`/`Set` components,
  `Flow<Uuid>`, and `Uuid` as an extension receiver; there is no reverse `Guid` → Kotlin mapping yet.
  The binary two-`INT64` wire considered and rejected in [ADR-106](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/106-uuid-mapping.md)
  is a possible follow-up if the per-crossing string allocation ever matters, tracked in
  [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 4.
- A keyword-named parameter is only escaped on the ordinary synchronous forward callable plan
  (constructors, class methods, top-level/extension functions, value-class members). The same
  parameter on a suspend method, a `Flow<T>`-returning member, a lambda/callback parameter, a sealed
  member, a generic member, or an interface-bridge member still generates invalid C#, tracked in
  [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 3.

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="collections.md">Collections</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md">ADR-002: Nullable two-call pattern</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/024-sync-exception-propagation.md">ADR-024: Synchronous exception propagation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md">ADR-053: Nullable reference types in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/069-nullable-boolean-marshalling.md">ADR-069: Nullable Boolean marshalling</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/076-instant-mapping.md">ADR-076: kotlin.time.Instant mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/098-narrow-primitive-and-char-collection-components.md">ADR-098: Narrow-primitive and Char collection components</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/103-duration-mapping.md">ADR-103: kotlin.time.Duration mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/106-uuid-mapping.md">ADR-106: kotlin.uuid.Uuid mapping</a>
    </category>
</seealso>
