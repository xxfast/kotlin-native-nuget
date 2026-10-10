# Value classes

A Kotlin `value class` wrapping a primitive or `String` becomes a C# `readonly record struct`
around the underlying value: no handle, no `IDisposable`, structural equality for free. A value
class wrapping another bridged class also becomes a `record struct`, but its one property is that
class's own handle-backed type, so disposal still applies to the wrapped object.

```kotlin
value class CatId(val id: String) {
  init { require(id.length <= 20) { "Cat ID too long: $id" } }
  val length: Int get() = id.length
}

value class CatResult(val cat: Cat)
```

```C#
var id = new CatId("oreo-123");
int n = id.Length;                          // ordinary struct member
bool same = id == new CatId("oreo-123");    // true -- structural equality, no generated Equals needed

using var oreo = new Cat("Oreo", 9);
var result = new CatResult(oreo);
string name = result.Cat.Name;              // "Oreo" -- dispose result.Cat like any other Cat
```

`init` validation on a primitive- or `String`-underlying value class runs on construction; a failed
`require`/`check` surfaces as the usual [exception](exceptions.md). On a value class wrapping
another class, only a secondary constructor's validation crosses the bridge -- the primary
constructor's `init` does not run when called from C#.

Only a value class over `String`, a primitive other than `Char`, an enum, a nullable `String` or
primitive, an object handle, or a sealed base/eligible sealed interface is declared at all. A value
class over `Char`, a plain interface, a nullable non-scalar, `Instant`, `Duration`, `Uuid`,
`ByteArray`, a collection, another value class, or a type outside the export set is refused by
name; every member typed with it, at any position, is a separate named skip
(`SKIPPED_UNSUPPORTED_TYPE`: "its value class type `X` has no C# record struct, because it wraps
`kotlin.Char`, which no value-class wire carries"). That covers a generic value class, and a
`Box<X>`, `List<X>`, lambda or `Flow` of one.

## As an ordinary parameter, property, or return type

A value class isn't limited to being the receiver of its own methods: it also binds as a plain
parameter, property, or return type anywhere else in the API, including constructors, `copy()`,
extension functions, and top-level functions. The wire carries only the underlying value; Kotlin
re-wraps it into the value class before the callee runs, so `init` validation applies on every
parameter and every property write, not only at construction.

```kotlin
value class ChartId(val value: String) {
  fun isValid(): Boolean = value.isNotBlank()
}

class Patient(val name: String) {
  var currentChart: ChartId = ChartId("CH-0")
  fun retag(id: ChartId): String = if (id.isValid()) "$name@${id.value}" else "$name@untagged"
}
```

```C#
using var oreo = new Patient("Oreo");

oreo.Retag(new ChartId("CH-OREO-1"));    // "Oreo@CH-OREO-1"
oreo.Retag(new ChartId("   "));          // "Oreo@untagged" -- isValid() ran Kotlin-side on the re-wrapped value

oreo.CurrentChart = new ChartId("CH-9"); // setter re-validates through ChartId's init
ChartId id = oreo.CurrentChart;          // ordinary struct, not IDisposable
```

A value class wrapping an enum or another bridged class crosses the same way, riding that
underlying's own wire (an `int` ordinal for an enum, the wrapped object's handle for a class), and
is re-wrapped on the way back.

A `default(ChartId)` of a `String`- or class-wrapping value class carries no underlying value, which
Kotlin could never have built. Passing one into a non-null parameter, property, or collection
element throws `ArgumentException` in C#, naming the struct (`default(ChartId) carries no Value;
construct a ChartId instead`). A `ChartId?` still accepts `null`.

```C#
oreo.Retag(default);   // throws ArgumentException before the call
oreo.Retag(new ChartId("CH-9"));
```

## Nullable

`ChartId?` binds as a genuine `Nullable<ChartId>`, not a reference nullable, everywhere a value
class binds: property, parameter, return. `null` stays distinct from a value class wrapping an
empty string, zero, or `false`.

```kotlin
var backupChart: ChartId? = null
fun transferTo(to: ChartId?): String =
  if (to != null) "transferred to ${to.value}" else "no transfer"
```

```C#
patient.BackupChart = new ChartId("CH-3");
patient.BackupChart = null;
patient.TransferTo(null); // "no transfer" -- Kotlin sees a genuine null, not ChartId("")
```

A method or getter declared on the value class itself can return a nullable too: `String?`,
`Int?`, an enum, an object, a `List` and `Throwable?` all bind, with `null` coming through as
`null`. Object results are owned by you like any other returned object, so dispose them.

```kotlin
value class CollarTag(val name: String) {
  val nickname: String? get() = name.takeIf { it.isNotEmpty() }?.let { "$it the brave" }
  fun letters(): Int? = name.length.takeIf { it > 0 }
  fun bell(): Bell? = name.takeIf { it.isNotEmpty() }?.let { Bell("$it-ding") }
}
```

```C#
var mylo = new CollarTag("Mylo");
var blank = new CollarTag("");

string? nickname = mylo.Nickname;   // "Mylo the brave"
int? none = blank.Letters();        // null
using Bell? bell = mylo.Bell();     // "Mylo-ding" tone; blank.Bell() is null
```

A value class's own members have no error slot, so an exception thrown inside one of them is not
caught and rethrown in C# the way it is on other members. Do not let these members throw.

### Primitive- and enum-underlying nullables {id="nullable-over-primitive-and-enum-underlyings"}

`Dosage?` and `Temperament?` (value classes wrapping a `Double` and an enum) behave the same as
`ChartId?` above: a real `Nullable<T>`, with `0.0`, a zero-ordinal enum case, and `false` all
staying distinct from `null`. No extra code is needed on either side.

A bare `Mood?`, not wrapped in a value class, follows the same rule; see
[Enums: Nullable](enums.md#nullable).

## Over a sealed type

A value class can also wrap a sealed base, such as `value class ObservationResult(val observation:
Observation)` over `sealed class Observation`. It binds at a property, a callable parameter or
return, and as a `List<T>` element. C# reconstructs through the sealed base's own subtype, so it
pattern-matches like any other [sealed type](interfaces-abstract-sealed.md).

```kotlin
class ObservationDesk {
  val result: ObservationResult = ObservationResult(openBox("Oreo"))
}
```

```C#
var alive = Assert.IsType<Observation.Alive>(desk.Result.Observation);
using Cat oreo = alive.Cat;
```

## As a collection component

A value class also binds as a `List`/`Map`/`Set` element, key, or value. The collection's wire
never carries the value class itself, only its underlying value; C# and Kotlin re-wrap each element
on the way in and out.

```kotlin
class ChartBook(charts: List<ChartId>) {
  fun issuedCharts(): List<ChartId> = listOf(ChartId("CH-OREO-9"), ChartId("CH-MYLO-4"))
}
```

```C#
var counts = new Dictionary<ChartId, int> { [new ChartId("CH-1")] = 3 };

IReadOnlyList<ChartId> issued = book.IssuedCharts();
string first = issued[0].Value; // "CH-OREO-9"
```

A nested collection of value classes (`List<List<ChartId>>`) is not supported; see
[Collections](collections.md).

## At an erased generic position {id="at-an-erased-generic-position"}

A value class also crosses at a [generic class](generics.md)'s `T`, and as a
[lambda](lambdas-and-callbacks.md) payload or result, both positions where C# only sees `T`/`object`
and Kotlin only sees `Any?`. C# hands across the boxed value class itself, not its underlying, so
the round trip still validates through `init` and still compares structurally:

```kotlin
class Box<T>(val value: T)

class ChartCourier(val desk: String) {
  val onChart: (ChartId) -> String = { id -> "$desk filed ${id.value}" }
}
```

```C#
var id = new ChartId("CH-OREO-1");
using var box = new Box<ChartId>(id);
Assert.Equal(id, box.Value);

using var courier = new ChartCourier("Ward 9");
using KotlinFunc<ChartId, string> onChart = courier.OnChart;
Assert.Equal("Ward 9 filed CH-OREO-1", onChart.Invoke(new ChartId("CH-OREO-1")));
```

`init` runs at this boundary even for a value class wrapping another class, which otherwise never
runs `init` from C# (see above): the box export is the first place it runs.

```kotlin
value class WardBand(val patient: Patient) {
  init { require(patient.name.isNotEmpty()) { "A ward band needs the patient's name" } }
}
```

```C#
using var nameless = new Patient("");
Assert.ThrowsAny<ArgumentException>(() => new Box<WardBand>(new WardBand(nameless)));
```

Not every declared value class crosses here: a value class whose underlying is itself nullable or
an ineligible sealed interface has no crossing at this position. A lambda payload, a bare
`Flow`/`StateFlow`/`SharedFlow` element or an awaited result over one is a named skip (inside a
`List` it still binds), and so is a `Box<V>` member over one. But `Box<T>` is an
open C# generic with no build-time gate of its own, so `new Box<V>(v)` written in your C# code for
such a `V` still compiles and throws `NotSupportedException` at the call, as it does for an
unsupported `T` of any other kind.

## As an extension receiver

A value class also works as the receiver of an extension function or extension property. The
receiver crosses as its underlying value and is re-wrapped with the value class's own constructor
before use, so `init` still runs. See [Extensions](extensions.md) for the general receiver mapping.

```kotlin
fun Temperament.escalate(): Temperament = when (mood) {
  Mood.CALM -> Temperament(Mood.ANXIOUS)
  Mood.ANXIOUS -> Temperament(Mood.PLAYFUL)
  Mood.PLAYFUL -> Temperament(Mood.PLAYFUL)
}
```

```C#
Temperament next = temperament.Escalate();
```

## Inherited members

A member whose signature comes from a supertype -- inherited, forwarded through interface
delegation (`value class ArticleUri(val value: String) : CharSequence by value`), or explicitly
overridden -- is never exported to C#. Only members with a signature no supertype declares are
bridged. Reach the supertype's own API through the underlying property instead.

```kotlin
value class ArticleUri(val value: String) : CharSequence by value {
  fun shout(): String = value.uppercase()
}
```

```C#
var uri = new ArticleUri("nyt://article/1234");
string shouted = uri.Shout();      // declared directly on ArticleUri, so it's bridged
int n = uri.Value.Length;          // CharSequence's own API, reached through the underlying string
```

A member that merely shares a name with a supertype member, but not its parameter types, still
exports as its own overload. Give a member a distinct name or signature if you need it bridged
despite colliding with an inherited one.
