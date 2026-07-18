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
| `T?` (nullable primitive) | `T?` | two-call pattern on property and top-level returns (forward only); method/extension nullable numerics use single-call `valueOut`, see [Classes and objects](classes-and-objects.md) and [ADR-002](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md) / [ADR-061](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/061-method-return-marshalling.md) |
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

## Limitations

- Nullable *primitive* mapping (`Int?`, and friends) is forward-only (`→`): the reverse direction has
  no `Nullable<T>` wire format yet (deferred by [ADR-053](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md)
  Decision 3, not a two-call-pattern gap).
- Nullable *string* mapping is now `⇄`: forward uses this page's two-call pattern on property and
  top-level returns, reverse reads the bound assembly's `NullableAttribute` instead (see
  [Objects and handles](objects-and-handles.md)). The two mechanisms are unrelated; a reverse-bound
  `string?` never goes through a `has_value`/`value` pair.
- Nullable `Boolean` method returns remain unplanned under the shared callable plan and are skipped
  rather than fallthrough-emitted. Tracked in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)
  Phase 4 ([ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)).

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
    </category>
</seealso>
