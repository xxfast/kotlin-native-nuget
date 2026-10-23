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
| `Any` / `Any?` | `object` / `object?`, see [Any](#any) |

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

A property getter returning a nullable primitive, `Instant?`, or `Duration?` is evaluated twice
when it returns a value. Keep those getters free of side effects and ensure their result stays
stable between evaluations. A top-level function with the same return evaluates once, like an
ordinary instance method.

## Passing null for a non-null String {id="null-for-a-non-null-string"}

C# lets `null!` through where a `string` is declared. For a Kotlin `String` (not `String?`) the
generated code refuses it before anything crosses, with an `ArgumentNullException` whose
`ParamName` is the parameter you wrote:

```C#
using var desk = new StampDesk("Mylo");
desk.Stamp(null!); // throws ArgumentNullException, ParamName == "text"
```

This covers constructor, method, setter (`ParamName` is `value`), `suspend`, top-level, companion,
`object`, extension receiver and sealed arm parameters alike. A `null` inside a `List`, `Set` or
`Map` of strings is reported against the collection you passed, so `ParamName` is `texts` for
`desk.StampAll(new List<string> { "a", null! })`; it replaces the `KotlinNullReferenceException`
an earlier build threw there. On a member returning a `Flow`, the exception is thrown when the
flow is collected, because that is where Kotlin reads the argument.

Two shapes accept `null` on purpose: a `string?` parameter (including `string? x = null`), and a
generic `T` position (`new Box<string>(null!)` holds the null, since `T` is unbounded). A
[value class](value-classes.md) over a `String` has its own guard and throws `ArgumentException`
for `default`.

A lambda or C#-implemented interface member that returns `null` where Kotlin
expects a non-null `String` is refused the same way. See
[Exceptions from a callback](lambdas-and-callbacks.md#exceptions-from-a-callback).

## Char

`Char` maps to C# `char`, including non-ASCII characters. It works in properties, parameters,
returns, and [collections](collections.md#narrow-primitives-and-char-as-collection-components).

`Char?` becomes C# `char?` everywhere a bare `Char?` appears: a property, a constructor or method
parameter, a method return, and a top-level function return. `null` stays distinct from every
character, including non-ASCII ones.

```kotlin
class Tag(var initial: Char?) {
  fun echo(c: Char?): Char? = c
}
```

```C#
using var tag = new Tag('O');

char? echoed = tag.Echo(null); // null
tag.Initial = '한';
```

Use `String` for characters that need a surrogate pair; lone surrogate values do not reliably
survive the conversion.

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
result, and as a `List` element or a `Map` value (`List<ByteArray>` is `IReadOnlyList<byte[]>`, see
[Collections](collections.md)). It is not supported as a `Set` element or a `Map` key, since arrays
compare by identity and every crossing copies, so a lookup could never succeed, nor as another array
type (`IntArray`, `Array<T>`), nor as an extension receiver.

## Any {id="any"}

`Any` and `Any?` are `object?` wherever they appear: a parameter, result, property, `List` or `Map`
element, lambda payload or result, or `Flow` element. A Kotlin value reaches C# as its runtime C#
value, so test it with `is` or a pattern. A C# object reaches Kotlin as the matching Kotlin value.

```kotlin
class Mouse(val name: String)
enum class Fur { TUXEDO, CARAMEL }
value class Paw(val size: Int)

fun stash(value: Any): Any = value
fun stashOrNull(value: Any?): Any? = value
fun fetch(kind: String): Any? = when (kind) {
  "int" -> 5
  "string" -> "Oreo"
  "mouse" -> Mouse("Mylo")
  "fur" -> Fur.CARAMEL
  "paw" -> Paw(4)
  "list" -> listOf(1, "Mylo", null)
  // ...
  else -> null
}
```

```C#
// stash(value: Any): Any is object Stash(object value); stashOrNull keeps the ?
Assert.Equal(5, Assert.IsType<int>(Hoarding.Stash(5)));
Assert.Equal(3, Assert.IsType<Paw>(Hoarding.Stash(new Paw(3))).Size);
Assert.Null(Hoarding.StashOrNull(null));

using Mouse mouse = Assert.IsType<Mouse>(Hoarding.Fetch("mouse"));
Assert.Equal(Fur.Caramel, Assert.IsType<Fur>(Hoarding.Fetch("fur")));
var list = Assert.IsAssignableFrom<IReadOnlyList<object?>>(Hoarding.Fetch("list")); // 1, "Mylo", null
```

| Kotlin value | C# value |
|---|---|
| `Int`, `Long`, `Double`, `Boolean` and the other primitives | the boxed primitive |
| `String` | `string` |
| an exported class | its wrapper, which you dispose (`Mouse` above) |
| a value class, an enum | the struct, the C# enum |
| a `List`, `Set` or `Map` | `IReadOnlyList<object?>`, `IReadOnlySet<object?>`, `IReadOnlyDictionary<object, object?>` |
| an object a C# class implemented | the same C# object you handed over |
| a generic value class (`Bow<T>`) | its struct closed over `object?`, see [Value classes](value-classes.md#at-an-any-position) |
| any other Kotlin object | a `KotlinObject` that you dispose; passing it back hands Kotlin the same object |

Going the other way, a list or dictionary you pass becomes a Kotlin `List` or `Map` with each
element converted the same way, and a C# object Kotlin has no type for stays opaque inside Kotlin
and comes back as the same instance.

A wrapper you receive as `object?` is yours to dispose, as at any other result position, and a
wrapper you pass in is only borrowed for the call. A generic value class comes back closed over
`object?` (`Bow<object?>`) whatever it was constructed as, so match on that.

A star projection reads at the parameter's bound, or `object?` when it has none: `Box<*>` is
`Box<object?>`, `Flow<*>` is `KotlinFlow<object?>`, and a generic sealed `Outcome<*>` is
`Outcome<object>`. `Any` also stands in for a member's own type parameter on an interface a C# class
implements, see [Implementing a Kotlin interface in C#](interfaces-abstract-sealed.md#implementing-a-kotlin-interface-in-c).

## C# names {id="c-names"}

Function and property names use PascalCase: `string()` becomes `String()`.
For named arguments, use the generated parameter name. C# keywords are escaped, such as
`@abstract`; reserved names such as `error` and `handle` gain a trailing underscore
(`error_`, `handle_`). `value` keeps its name on a parameter. Positional calls are unaffected.

The same applies to `errorOut` and `valueOut`, on plain and generic functions alike:

```kotlin
fun <T> mark(errorOut: T): T = errorOut
```

```C#
public static T Mark<T>(T errorOut_)
```

Any other parameter name on a generic function is kept as written, including `owned`, `result`,
`width`, and `present`.

Your own parameter names are kept even when they match a name the generator would otherwise use
internally for a sibling parameter, such as a boolean flag next to a nullable value or a body local
of a defaulted parameter's dispatch:

```kotlin
fun fill(limit: Int?, limitHasValue: Boolean): String = "$limit|$limitHasValue"
```

```C#
public string Fill(int? limit, bool limitHasValue) // both arguments keep their own values
```

The one visible exception is a `suspend` parameter literally named `cancellationToken`: the user's
name wins, and the generated cancellation token becomes `cancellationToken_`.

```kotlin
suspend fun pour(cancellationToken: Int): String = "poured $cancellationToken"
```

```C#
public Task<string> PourAsync(int cancellationToken, CancellationToken cancellationToken_ = default)
```
