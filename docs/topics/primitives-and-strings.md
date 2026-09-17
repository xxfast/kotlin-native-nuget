# Primitives and strings

Kotlin primitives and strings appear as ordinary C# values. Nullable types keep their `?`.

For this declaration in `Mappings.kt`:

```kotlin
fun string(): String = "Kotlin/Native!"
```

Call it from C#:

```C#
string result = Mappings.String(); // "Kotlin/Native!"
```

## Type mappings

| Kotlin | C# |
|---|---|
| `Byte` / `Short` / `Int` / `Long` | `sbyte` / `short` / `int` / `long` |
| `UByte` / `UShort` / `UInt` / `ULong` | `byte` / `ushort` / `uint` / `ulong` |
| `Float` / `Double` | `float` / `double` |
| `Boolean` | `bool` |
| `Char` | `char` |
| `String` | `string` |
| `kotlin.time.Instant` | `System.DateTimeOffset` |
| `kotlin.time.Duration` | `System.TimeSpan` |
| `kotlin.uuid.Uuid` | `System.Guid` |
| `ByteArray` | `byte[]` |

These mappings describe Kotlin APIs exported to C#. For consuming a C# library from Kotlin,
see [Types that cross the wire](bridgeable-subset.md#types-that-cross-the-wire); nullable value types and the
`DateTimeOffset`, `TimeSpan`, and `Guid` mappings are not supported in that direction.

## Nullable values

`Int?` becomes `int?`, `Boolean?` becomes `bool?`, and `String?` becomes `string?`.
`Instant?`, `Duration?`, and `Uuid?` become `DateTimeOffset?`, `TimeSpan?`, and `Guid?`.
`null` stays distinct from `0`, `false`, an empty string, or `Guid.Empty`.

```kotlin
fun nullableInt(hasValue: Boolean): Int? = if (hasValue) 42 else null
```

```C#
int? result = Mappings.NullableInt(false); // null
```

A top-level function or property getter returning a nullable primitive, `Instant?`, or `Duration?`
is evaluated twice when it returns a value. Keep those functions and getters free of side effects
and ensure their result stays stable between evaluations. Ordinary instance methods evaluate once.

## Char

`Char` maps to C# `char`, including non-ASCII characters. It works in properties, parameters,
returns, and [collections](collections.md#narrow-primitives-and-char-as-collection-components).

A standalone `Char?` is unsupported and can cause packaging to fail; nullable characters inside
collections, such as `List<Char?>`, are supported. Use `String` for characters that need a surrogate
pair; lone surrogate values do not reliably survive the conversion.

## Instant

Use `DateTimeOffset` for Kotlin's `kotlin.time.Instant`:

```kotlin
class SightingLog(val firstSeen: Instant, var lastSeen: Instant?)
```

```C#
var at = new DateTimeOffset(2024, 3, 8, 12, 34, 56, 123, new TimeSpan(5, 30, 0));
using var log = new SightingLog(at, null);
```

`log.FirstSeen` represents the same instant as `at`, with its offset normalized to UTC.
Use `.UtcDateTime` if you need a `DateTime`.

- C# inputs preserve their full precision. Kotlin results lose fractions smaller than 100 ns,
  rounding down to the preceding tick.
- Results must be within years 0001–9999. Out-of-range values, including `Instant.DISTANT_PAST`
  and `Instant.DISTANT_FUTURE`, throw `KotlinArgumentException`.
- The older `kotlinx.datetime.Instant` type is not mapped.

## Duration

Use `TimeSpan` for Kotlin's `kotlin.time.Duration`:

```kotlin
class NapTracker(val longestNap: Duration, var lastNap: Duration?) {
  fun extend(extra: Duration): Duration = longestNap + extra
}
```

```C#
using var tracker = new NapTracker(TimeSpan.FromMinutes(90), null);
var result = tracker.Extend(TimeSpan.FromMinutes(30)); // two hours
```

- Kotlin results truncate toward zero to 100 ns precision.
- All `TimeSpan` inputs are accepted. Values beyond roughly ±146 years lose sub-millisecond
  precision in Kotlin, so a round trip may return a slightly different value.
- Infinite durations and finite values outside `TimeSpan`'s range (about ±10,675,199 days)
  throw `KotlinArgumentException`.

## Uuid

Use `Guid` for Kotlin's `kotlin.uuid.Uuid`:

```kotlin
data class ChipRecord(val id: Uuid)
```

```C#
var minted = Guid.NewGuid();
using var record = new ChipRecord(minted);
```

`record.Id` equals `minted`. All 128 bits are preserved, and `Uuid.NIL` maps to `Guid.Empty`.

`Instant`, `Duration`, and `Uuid` work as properties, constructor and method parameters, and
function results. They are not supported as collection elements. For extension receiver support,
see [Extensions](extensions.md).

## ByteArray

Use `byte[]` for Kotlin's `ByteArray`. A `byte[]` argument or result is always a fresh copy: nothing
you do to it on either side reaches the other.

```kotlin
data class Payload(val code: Int, var data: ByteArray) {
  val checksum: ByteArray? get() = if (data.isEmpty()) null else byteArrayOf(data.sum().toByte())
}

fun reverse(data: ByteArray): ByteArray = data.reversedArray()
```

```C#
using var payload = new Payload(7, new byte[] { 1, 2, 3 });

byte[] data = payload.Data; // { 1, 2, 3 }
data[0] = 99; // does not change payload.Data; the getter handed out a fresh copy

byte[] reversed = PayloadKt.Reverse(new byte[] { 1, 2, 3 }); // { 3, 2, 1 }
```

An empty `ByteArray` (`byteArrayOf()`) crosses as `Array.Empty<byte>()`, never `null`. `ByteArray?`
becomes `byte[]?`, with `null` distinct from an empty array; `payload.Checksum` above is `null` for
an empty payload. `ByteArray` works as a property, constructor and method parameter, and function
result. It is not yet supported as a collection element (`List<ByteArray>`), as another array type
(`IntArray`, `Array<T>`), or as an extension receiver.

## C# names

Function and property names use PascalCase: `string()` becomes `String()`.
For named arguments, use the generated parameter name. C# keywords are escaped, such as
`@abstract`; reserved names such as `error`, `value`, and `handle` gain a trailing underscore
(`error_`, `value_`, `handle_`). Positional calls are unaffected.
