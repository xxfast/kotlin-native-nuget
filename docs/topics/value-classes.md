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

A value class over `String`, a primitive other than `Char`, an enum, a nullable `String` or
primitive, an object handle, a sealed base/eligible sealed interface, `Instant`, `Duration`, `Uuid`,
`ByteArray`, a collection, another value class, or a plain interface is declared at all (the last
group is [below](#over-instant-bytearray-collection-interface)). A value class over `Char`, a
nullable non-scalar, or a type outside the export set is refused by
name; every member typed with it, at any position, is a separate named skip
(`SKIPPED_UNSUPPORTED_TYPE`: "its value class type `X` has no C# record struct, because it wraps
`kotlin.Char`, which no value-class wire carries"). That covers a `Box<X>`, `List<X>`, lambda or
`Flow` of one.

## Over `Instant`, `Duration`, `Uuid`, `ByteArray`, a collection, another value class or an interface {id="over-instant-bytearray-collection-interface"}

A value class over one of these becomes a `readonly record struct` whose one property is the
underlying's usual C# type, and it crosses on that type's own wire. Kotlin re-wraps it, so `init`
runs when the struct reaches Kotlin, not when you construct it in C#.

```kotlin
value class SunbeamStart(val at: Instant) {
  init { require(at.epochSeconds >= 0) { "a sunbeam cannot start before 1970" } }
  fun minutesUntil(other: SunbeamStart): Long = (other.at - at).inWholeMinutes
}

value class PawPrint(val pads: ByteArray) {
  constructor(pad: Byte, count: Int) : this(ByteArray(count) { pad })
  init { require(pads.isNotEmpty()) { "a paw print needs at least one pad" } }
  val padCount: Int get() = pads.size
}

value class PurrVoice(val rumbler: Rumbler) // Rumbler is a plain Kotlin interface
```

```C#
var start = new SunbeamStart(DateTimeOffset.FromUnixTimeSeconds(1_700_000_000));
FurcoatSample.EchoStart(start);                  // fine
FurcoatSample.EchoStart(new SunbeamStart(DateTimeOffset.FromUnixTimeSeconds(-60)));
                                                 // ArgumentException: init ran in Kotlin

var paw = new PawPrint(new byte[] { 1, 2 });     // byte[]
int pads = paw.PadCount;                         // 2
var same = new PawPrint(7, 3);                   // secondary constructor, runs in Kotlin
```

| Underlying | Struct property |
|---|---|
| `Instant` | `DateTimeOffset` |
| `Duration` | `TimeSpan` |
| `Uuid` | `Guid` |
| `ByteArray` | `byte[]` |
| `List<String>` and the other collections | `IReadOnlyList<string>`, `IReadOnlySet<T>`, `IReadOnlyDictionary<K,V>` |
| another value class | that value class's struct (`ShelterTag(ChipSerial Serial)`) |
| a plain interface | the generated `I<Name>` (`PurrVoice(IRumbler Rumbler)`) |

These structs bind at parameters, results, properties and constructors, nullable included,
exactly like the structs above, and as a `List`, `Set` or `Map` element, key or value. Unlike a
[bare `ByteArray`](collections.md#bytearray-as-a-collection-component), a struct over a `ByteArray`
or a collection is accepted as a `Set` element and a `Map` key, but it compares by reference (see
below), so `Contains` or a lookup with a struct you just built never matches; read the elements
or iterate the entries instead. Their methods, properties and
secondary constructors bind too. A failing `init` on the receiver throws the usual
[exception](exceptions.md) from the member call.

- A `ByteArray` or collection is copied at each crossing, so changing the array you passed in
  does not reach Kotlin. Those two structs compare their payload by reference, so assert on
  `Pads` or `Toys`, not on the struct.
- A value class over an interface holds an `IDisposable` interface value. Dispose a Kotlin-backed
  one you read out (`using IRumbler rumbler = voice.Rumbler`). A C#-implemented one you pass in
  comes back as the same instance.
- `default(PawPrint)`, and `default` of a struct over a collection or an interface, carries a
  `null` payload that Kotlin could never have built. Crossing one throws `ArgumentException`
  naming the struct, as a parameter, nullable value, collection element or member receiver. A struct
  over `Instant`, `Duration` or `Uuid` has no null payload, so its `default` crosses and `init`
  decides whether Kotlin accepts it.

## A generic value class

A generic value class over its own type parameter, or a phantom one that never uses it, becomes a
positional generic `readonly record struct`. Each closed instantiation crosses as its argument
would on its own, so `Sticker<Int>` rides the primitive route and `Sticker<Cat>` the object route.

```kotlin
value class Sticker<T>(val value: T)
value class Decal<T>(val serial: Long)

fun count(): Sticker<Int> = Sticker(3)
fun adopt(name: String?): Sticker<Cat>? = name?.let { Sticker(Cat(it)) }
fun <T> stick(value: T): Sticker<T> = Sticker(value)
fun <T> peel(sticker: Sticker<T>): T = sticker.value
fun oreoDecal(): Decal<Cat> = Decal(7L)
```

```C#
Sticker<int> treats = Stickers.Count();                 // new Sticker<int>(3)
Sticker<Cat>? adopted = Stickers.Adopt("Oreo");         // null when the name is null
using Cat oreo = adopted!.Value.Value;                  // dispose the wrapped Cat
string label = Stickers.Peel(new Sticker<string>("x"));
Decal<Cat> decal = Stickers.OreoDecal();                // Serial stays a long
```

- The struct is `Sticker<T>(T Value)`, with no `where` clause. A function's own `T` inside it
  (`Stick<T>`, `Peel<T>`) binds like a bare `T`, and its bound becomes the method's `where`.
  Any bound works: `T : Comparable<T>`, `T : Enum<T>` and a multi-bound `T` all bind, on top-level
  functions and class members alike.

  ```kotlin
  fun <T : Comparable<T>> higher(a: Sticker<T>, b: Sticker<T>): Sticker<T> = if (a.value >= b.value) a else b
  fun <T : Enum<T>> napOf(sticker: Sticker<T>): Sticker<T> = sticker
  ```

  ```C#
  Sticker<string> higher = Stickers.Higher(new Sticker<string>("Mylo"), new Sticker<string>("Oreo"));
  Sticker<Mood> napped = Stickers.NapOf(new Sticker<Mood>(Mood.Sleepy));   // where T : struct, Enum
  ```

  A `Comparable<T>` bound is not spelled as a C# constraint, so Kotlin checks it.
- `init` does not run at the primary constructor `new Sticker<int>(-1)` in C#. It runs when the value first crosses into
  Kotlin, so the call that receives it throws. A non-generic value class validates at construction.
- `default(Sticker<string>)` passed to a non-null `Sticker<String>` parameter throws
  `ArgumentException` in C#. For an unconstrained `T`, `default` carries `null`.
- A KDoc `[Sticker]` link renders as plain `<c>Sticker</c>`, as it does for a generic class.
- A closed instantiation works as a `List`, `Set` or `Map` component and under `?`, and at an
  [erased position](#a-generic-value-class-at-an-erased-position). A function's own `T` does too:
  see [Under `?` and inside collections](#a-generic-value-class-under-and-inside-collections).
- A generic value class over `List<T>` or any other shape that is not `T` or a phantom stays
  refused by name.

### Under `?` and inside collections {id="a-generic-value-class-under-and-inside-collections"}

A function's own `T` inside a generic value class binds under `?` and as an element, key or value
of a `List`, `Set` or `Map`, including `MutableList` and nested lists. An unconstrained `T` can be
`null`, so `Sticker<T>?` keeps "no sticker" apart from `Sticker(null)`.

```kotlin
fun <T> maybeStick(value: T, present: Boolean): Sticker<T>? = if (present) Sticker(value) else null
fun <T> isAbsent(sticker: Sticker<T>?): Boolean = sticker == null
fun <T> stickAll(values: List<T>): List<Sticker<T>> = values.map { Sticker(it) }
fun <T> gaps(stickers: List<Sticker<T>?>): List<Sticker<T>?> = stickers
fun starred(sticker: Sticker<*>): String = "star ${sticker.value}"
```

```C#
Sticker<uint?>? blank = StickerGaps.MaybeStick<uint?>(null, present: true);   // Sticker(null)
Sticker<uint?>? none = StickerGaps.MaybeStick<uint?>(null, present: false);   // null

bool absent = StickerGaps.IsAbsent<byte?>(new Sticker<byte?>(null));          // false
IReadOnlyList<Sticker<ushort>> all = StickerGaps.StickAll(new ushort[] { 1, 2 });
IReadOnlyList<Sticker<ushort?>?> back = StickerGaps.Gaps(
    new Sticker<ushort?>?[] { null, new Sticker<ushort?>(null) });            // null, then Sticker(null)

string text = StickerGaps.Starred(new Sticker<int>(3));                       // "star 3"
```

- C# cannot infer `T` through `Nullable<>`, so a `Sticker<T>?` parameter called with a
  `Sticker<byte?>` needs the explicit `IsAbsent<byte?>(...)`. Without it the call fails with
  `CS0411`.
- `init` runs in Kotlin when the value crosses, so `IsAbsent<int>(new Sticker<int>(-1))` throws
  `ArgumentException` before the call body runs. In a collection it runs per element.
- A `T : Any` bound has no null of its own, so `Sticker<T>?` there is a plain `null`.
- A star-projected `Sticker<*>` parameter becomes a method type parameter you pick at the call
  (`Starred<TSticker>`), on a class member too.

### Members, properties and constructors

The struct carries the class's own members, properties, secondary constructors and `suspend`
members, on every instantiation including a phantom one.

```kotlin
value class Sticker<T>(val value: T) {
  constructor(label: String, times: Int) : this(label.repeat(times) as T)
  val shiny: Boolean get() = value != null
  fun describe(): String = "sticker of $value"
  fun swap(other: T): Sticker<T> = Sticker(other)
  fun <R> pairWith(other: R): String = "$value+$other"
  suspend fun peelLater(): T = value
}

value class Decal<T>(val serial: Long) {
  constructor(label: String) : this(label.toLong())
  fun describe(): String = "decal #$serial"
}
```

```C#
string text = new Sticker<int>(3).Describe();              // "sticker of 3"
bool shiny = new Sticker<string?>(null).Shiny;             // false
Sticker<string> doubled = new Sticker<string>("ab", 2);    // Value is "abab"
string pair = new Sticker<string>("Oreo").PairWith(7);     // "Oreo+7"
int later = await new Sticker<int>(3).PeelLaterAsync();
Decal<Cat> decal = new Decal<Cat>("42");                   // Serial is 42
```

- A secondary constructor runs in Kotlin at `new Sticker<string>("ab", 2)`, so `init` runs there. The
  primary constructor still does not.
- A member that throws, or whose receiver fails `init`, throws in C# (`KotlinArgumentException` for
  `new Sticker<int>(-1).Describe()`). A `suspend` member faults its `Task` instead.
- A member on `default(Badge<string>)` over a non-null `T` throws `ArgumentException` ("carries no
  Value; construct a Badge instead") before reaching Kotlin.
- A member's own `<R>` binds on any value class, generic or not, and is renamed if it would clash with
  the struct's `T`.
- A `T` you pass or return is a bare `T`, so a `Cat` you read back from `Swap(...)` is yours to dispose.

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

### A value class whose underlying can be null {id="nullable-underlying-value-classes"}

`value class Nick(val name: String?)` and `value class TreatCount(val count: Int?)` bind at every
ordinary position. `Nick?` is a `Nullable<Nick>` where `null` and `Nick(null)` stay different values,
at a parameter, result, constructor, property, extension receiver and as a collection element.

```kotlin
value class Nick(val name: String?)
value class TreatCount(val count: Int?)

fun maybeNick(present: Boolean): Nick? = if (present) Nick(null) else null
fun nickState(nick: Nick?): String = if (nick == null) "no nick" else nick.name ?: "nameless"
fun nicknames(): List<Nick?> = listOf(null, Nick(null), Nick("Oreo"))
fun Nick?.callName(): String = if (this == null) "no nick" else name ?: "nameless"
fun treatsOr(treats: TreatCount): Int = treats.count ?: -1
```

```C#
StickerGaps.MaybeNick(true);                // new Nick(null)
StickerGaps.MaybeNick(false);               // null
StickerGaps.NickState(new Nick(null));      // "nameless"
StickerGaps.Nicknames();                    // null, Nick(null), Nick("Oreo")
((Nick?)new Nick(null)).CallName();         // "nameless"
NickExtensions.CallName(null);              // "no nick"
StickerGaps.TreatsOr(new TreatCount(null)); // -1
```

- A `this Nick?` extension needs a `Nick?` receiver, so call it on a variable typed `Nick?`.
- The closed `Sticker<String?>` and `Sticker<String?>?` behave the same way.

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

Not every declared value class crosses here: a value class over an ineligible sealed interface has
no crossing at this position. A lambda payload, a bare `Flow`/`StateFlow`/`SharedFlow` element or an
awaited result over one is a named skip (inside a `List` it still binds), and so is a `Box<V>`
member over one. But `Box<T>` is an open C# generic with no build-time gate of its own, so
`new Box<V>(v)` written in your C# code for such a `V` still compiles and throws
`NotSupportedException` at the call.

A value class over a nullable underlying (`value class Nick(val name: String?)`) crosses here too.
`null` and `Nick(null)` stay distinct: a `Box<Nick?>` or a `Flow<Nick?>` element holds either no
`Nick` or a `Nick` with a null name.

```C#
using Box<Nick?> present = StickerSwaps.NickBoxOrNone(true);   // Nick(null)
using Box<Nick?> absent = StickerSwaps.NickBoxOrNone(false);   // null
Assert.Equal(new Nick(null), present.Value);
Assert.Null(absent.Value);
```

### A generic value class at an erased position {id="a-generic-value-class-at-an-erased-position"}

A generic value class crosses at every erased position for any instantiation: inside a generic
class, as a lambda payload or result (nullable too), as a `Flow`, `StateFlow`, `MutableStateFlow`
or `SharedFlow` element, as a `Flow` parameter, and as an awaited `suspend` result or parameter. A
function's own `T` inside the struct works as well. Reads and writes both bind, so you can set a
`MutableStateFlow<Sticker<Int>>` and pass your own `Sticker<T>` elements in a `Flow` parameter.

```kotlin
class Box<T>(val value: T)

fun <T> boxedOf(value: T): Box<Sticker<T>> = Box(Sticker(value))
fun boxedBlank(): Box<Sticker<String?>?> = Box(Sticker(null))
fun boxedNone(): Box<Sticker<String?>?> = Box(null)

class StickerCourier(val desk: String) {
  val onSticker: (Sticker<String>) -> Sticker<Int> = { Sticker(it.value.length) }
  val board: MutableStateFlow<Sticker<Int>> = MutableStateFlow(Sticker(2))
  fun stream(): Flow<Sticker<Cat>> = flowOf(Sticker(Cat("Oreo")), Sticker(Cat("Mylo")))
  suspend fun bumpLater(sticker: Sticker<Int>): Sticker<Int> = Sticker(sticker.value + 1)
}
```

```C#
using Box<Sticker<string>> names = StickerSwaps.BoxedOf("Oreo");
Assert.Equal("Oreo", names.Value.Value);

using Box<Sticker<string?>?> blank = StickerSwaps.BoxedBlank();
using Box<Sticker<string?>?> none = StickerSwaps.BoxedNone();
Assert.Equal(new Sticker<string?>(null), blank.Value);   // present, with a null value
Assert.Null(none.Value);                                 // no sticker at all

using var courier = new StickerCourier("Oreo's desk");
using KotlinFunc<Sticker<string>, Sticker<int>> onSticker = courier.OnSticker;
Assert.Equal(new Sticker<int>(4), onSticker.Invoke(new Sticker<string>("Oreo")));

courier.Board.Value = new Sticker<int>(8);
Assert.Equal(new Sticker<int>(9), await courier.BumpLaterAsync(new Sticker<int>(8)));

await foreach (Sticker<Cat> sticker in courier.Stream())
{
  using Cat cat = sticker.Value;   // an object-handle T is yours to dispose
}
```

- `init` runs in Kotlin when a value crosses in, so `new Box<Sticker<int>>(new Sticker<int>(-1))`
  and `courier.Board.Value = new Sticker<int>(-1)` throw `ArgumentException`, and a C# lambda that
  returns such a `Sticker` throws inside the Kotlin call.
- A phantom `Decal<Cat>` crosses the same way and keeps its `Serial` as a `long`.
- A Kotlin `Sticker<ulong>` that your code has never constructed reads back fine: the generated
  member registers the instantiation before it returns.
- A generic value class's own `Flow`, `StateFlow` and `SharedFlow` members and properties bind as
  on any [value class](coroutines-and-flow.md#flow-static-owners), including an enum-bound `T`:
  `new Sticker<string>("Mylo").Glow.Value`, `await foreach (string v in new Sticker<string>("Oreo").Trail())`.

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
