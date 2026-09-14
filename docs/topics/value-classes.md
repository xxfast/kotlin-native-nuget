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
