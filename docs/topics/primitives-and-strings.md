# Primitives and strings

Primitive types follow the standard [Kotlin/Native C interop mappings](https://kotlinlang.org/docs/mapping-primitive-data-types-from-c.html#inspect-generated-kotlin-apis-for-a-c-library). Strings marshal as UTF-8. Nullable primitives and nullable strings use a two-call pattern since a C ABI value type can't itself carry "no value".

| Kotlin | C# | Notes |
|---|---|---|
| `Byte` / `Short` / `Int` / `Long` | `sbyte` / `short` / `int` / `long` | |
| `UByte` / `UShort` / `UInt` / `ULong` | `byte` / `ushort` / `uint` / `ulong` | |
| `Float` / `Double` | `float` / `double` | |
| `Boolean` | `bool` | |
| `String` | `string` | UTF-8 marshalling |
| `T?` (nullable primitive / string) | `T?` | two-call pattern, see [ADR-002](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md) |

## Kotlin

From `sample-library/src/nativeMain/kotlin/.../Mappings.kt`:

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

The nullable two-call pattern: a `has_value` probe, then a `value` fetch only if present.

```C#
[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nullableInt_has_value")]
private static extern bool nullableInt_has_value(bool hasValue);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nullableInt_value")]
private static extern int nullableInt_value(bool hasValue);

public static int? nullableInt(bool hasValue)
    => !nullableInt_has_value(hasValue) ? null : nullableInt_value(hasValue);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nullableString_has_value")]
private static extern bool nullableString_has_value(bool hasValue);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nullableString_value")]
private static extern IntPtr nullableString_value(bool hasValue);

public static string? nullableString(bool hasValue)
    => !nullableString_has_value(hasValue) ? null : Marshal.PtrToStringUTF8(nullableString_value(hasValue));
```

Kotlin identifiers that collide with C# keywords (`string`, `byte`, `short`, `int`, `long`) are escaped with `@` on the C# side; `short`/`int`/`long`/`double` also get a trailing underscore on the native entry point to dodge C reserved words.

## Using it from C#

From `sample-app/SampleApp.Tests/MappingTests.cs`:

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

## Limitations

- Nullable primitive/string mapping is forward-only (`→`); the reverse direction (C# → Kotlin) does not yet support it.

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md">ADR-002: Nullable two-call pattern</a>
    </category>
</seealso>
