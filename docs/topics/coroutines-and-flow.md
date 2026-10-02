# Coroutines and Flow

Kotlin coroutines map onto .NET's own async model. `suspend fun` becomes `async`/`Task<T>`,
cancellation maps to `CancellationToken`, and disposing the owning object cancels any coroutine it
started. `Flow<T>` becomes `IAsyncEnumerable<T>`, and `StateFlow<T>` adds a synchronously readable
`.Value`.

| Kotlin | C# |
|---|---|
| `suspend fun` | `async Task<T>`, suffixed `Async` |
| `suspend (A) -> R` lambda | `KotlinSuspendFunc<A, R>` with `InvokeAsync` |
| `Flow<T>` | `KotlinFlow<T> : IAsyncEnumerable<T>` |
| `Flow<T?>` | `KotlinFlow<T?>`, a `null` emission is a genuine item |
| `StateFlow<T>` | `KotlinStateFlow<T> : KotlinFlow<T>`, adds a synchronous `.Value` |
| `MutableStateFlow<T>` (declared, not narrowed to `StateFlow<T>`) | `KotlinMutableStateFlow<T> : KotlinStateFlow<T>`, `.Value` is settable |
| coroutine cancellation | `CancellationToken` |
| structured concurrency | `Dispose()` cancels in-flight work; `DisposeAsync()` drains it |

## `suspend fun`

From `AsyncCatService.kt`:

```kotlin
class AsyncCatService(private val prefix: String) {
  suspend fun fetch(): String {
    delay(5.seconds)
    return "$prefix result"
  }

  suspend fun fetchCat(name: String): Cat {
    delay(5.seconds)
    return Cat(name)
  }
}
```

```C#
using var service = new AsyncCatService("toys");
using var cat = await service.FetchCatAsync("Oreo");
```

Every `suspend fun` becomes an `async Task<T>` method suffixed `Async`. Overloads resolve as an
ordinary C# overload set, whether declared on a class or at the top level:

```kotlin
suspend fun fetchTreat(): String = "one treat for Oreo"
suspend fun fetchTreat(count: Int): String = "$count treats for Mylo"
```

```C#
await AsyncFunctions.FetchTreatAsync();  // "one treat for Oreo"
await AsyncFunctions.FetchTreatAsync(3); // "3 treats for Mylo"
```

## `suspend fun` returning a nullable type {id="suspend-fun-returning-a-nullable-type"}

A nullable primitive, `String?`, or object return carries its `?` all the way through:

```kotlin
suspend fun findShelterCat(catName: String): Cat? {
  delay(100.milliseconds)
  return if (catName == "Mylo") Cat(catName) else null
}

suspend fun countTreatsLeft(catName: String): Int? {
  delay(100.milliseconds)
  return if (catName == "Oreo") 7 else null
}
```

```C#
public static Task<Cat?> FindShelterCatAsync(string catName, CancellationToken cancellationToken = default)
public static Task<int?> CountTreatsLeftAsync(string catName, CancellationToken cancellationToken = default)
```

`null` stays distinct from `0` or an empty string, the same as a
[nullable synchronous return](primitives-and-strings.md#nullable-values).

## `suspend fun` returning a collection {id="suspend-fun-returning-a-collection"}

```kotlin
class Headcount(private val names: List<String>) {
  suspend fun ids(): Set<Int> { /* ... */ }
  suspend fun ages(): Map<String, Int> { /* ... */ }
}
```

```C#
public Task<IReadOnlySet<int>> IdsAsync(CancellationToken cancellationToken = default)
public Task<IReadOnlyDictionary<string, int>> AgesAsync(CancellationToken cancellationToken = default)
```

`List<T>`, `Set<T>`, and `Map<K, V>` returns are spelled the same way a [property](collections.md)
of that type is. Any other generic return (`Pair<A, B>`, `Result<T>`, `Flow<T>`) has no C# binding
and is skipped with a diagnostic naming the member; expose the values through separate `suspend`
functions instead.

A nullable collection completes with `null`, never an empty collection, and an element that is a
sealed base binds as the base type, each element arriving as its concrete arm. Both work on a
class, a sealed base or arm, and at top level:

```kotlin
class Headcount(private val names: List<String>) {
  suspend fun maybeTags(present: Boolean): List<String>? { /* ... */ }
}

class Issue54Studio {
  suspend fun sketch(): List<Issue54Shape> { /* ... */ }
}
```

```C#
public Task<IReadOnlyList<string>?> MaybeTagsAsync(bool present, CancellationToken cancellationToken = default)
public Task<IReadOnlyList<global::TestLibrary.Issue54.Issue54Shape>> SketchAsync(CancellationToken cancellationToken = default)
```

`Flow<List<Shape>>` binds as `KotlinFlow<IReadOnlyList<Shape>>` the same way.

## `suspend fun` returning a type from a dependency module {id="suspend-fun-returning-a-dependency-type"}

A class or value class reached only through a top-level `suspend fun`, never through any ordinary
member, binds the same way it would anywhere else, once its package is in scope (see
[The nuget {} DSL: cross-module export closure](nuget-dsl.md#cross-module-export-closure)):

```kotlin
// dev.other.bysuspend, a dependency module admitted with admit("dev.other.bysuspend")
class Mousetoy(val squeak: String) {
  fun batted(by: String): String = "$by bats the $squeak mouse under the sofa"
}
value class Chipcode(val digits: String)

// io.github.xxfast.kotlin.native.nuget.test.errand
suspend fun fetchMousetoy(catName: String): Mousetoy { /* ... */ }
suspend fun squeakOf(toy: Mousetoy): String { /* ... */ }
suspend fun scanChip(catName: String): Chipcode { /* ... */ }
```

```C#
public static Task<global::TestLibrary.Dev.Other.Bysuspend.Mousetoy> FetchMousetoyAsync(
    string catName, CancellationToken cancellationToken = default);
public static Task<string> SqueakOfAsync(
    global::TestLibrary.Dev.Other.Bysuspend.Mousetoy toy, CancellationToken cancellationToken = default);
public static Task<global::TestLibrary.Dev.Other.Bysuspend.Chipcode> ScanChipAsync(
    string catName, CancellationToken cancellationToken = default);
```

`Chipcode` completes through the same unboxing a value class uses at any other position; an enum
return completes the same way an enum parameter binds, by ordinal. A dependency type outside the
admitted scope at this same position is a named skip pointing at `admit(...)`; it is never spelled
as an undeclared C# type, whether it appears at a top-level or a class-member `suspend` signature.

## `suspend fun` returning an interface {id="suspend-fun-returning-an-interface"}

```kotlin
suspend fun strayPetLater(): Pet = strayPet()
```

```C#
public static Task<global::TestLibrary.Cat.IPet> StrayPetLaterAsync(CancellationToken cancellationToken = default)
```

The result is typed with the interface itself, the same as a synchronous interface return, and
resolves back to the caller's own instance if a C# type implemented that interface. See
[Interfaces, abstract classes and sealed classes](interfaces-abstract-sealed.md).

A nullable interface return (`suspend fun handBackLaterOrNull(): Pet?`) carries its `?` the same way
a nullable primitive or object return does:

```C#
public Task<global::TestLibrary.Cat.IPet?> HandBackLaterOrNullAsync(CancellationToken cancellationToken = default)
```

`null` crosses as `null`, checked before the identity probe runs, and a non-null, C#-implemented
result still resolves back to the caller's own instance.

## `suspend fun` returning `StateFlow<T>` {id="suspend-fun-returning-stateflow-t"}

A `suspend fun` can suspend before handing back a `StateFlow<T>`, for example to build it lazily.
The outer suspend stays a `Task`; once awaited, `.Value` and `await foreach` behave exactly like an
ordinary `StateFlow<T>` (below).

```kotlin
suspend fun awaitMoodReport(): StateFlow<String> {
  delay(1)
  return mood
}
```

```C#
public Task<KotlinStateFlow<string>> AwaitMoodReportAsync(CancellationToken cancellationToken = default)
```

A top-level `suspend fun` gets the same holder, as a static method on its file's class:

```kotlin
suspend fun watchPurrCount(): StateFlow<Int> {
  delay(1)
  return purrCount.asStateFlow()
}
```

```C#
public static Task<KotlinStateFlow<int>> WatchPurrCountAsync(CancellationToken cancellationToken = default)
```

A top-level function has no owning object to cancel its collections, so each `await foreach` runs
until its own enumerator is disposed or its token is cancelled. Dispose the holder when you are done
with it, as with a class method's.

### `StateFlow<T>` element type is an interface {id="suspend-stateflow-interface-element"}

The element can itself be an interface. It is spelled and read through the interface, the same as
any other [interface element](#flow-t):

```kotlin
suspend fun keeperReport(): StateFlow<Keeper> {
  yield()
  return onDuty
}
```

```C#
public Task<KotlinStateFlow<global::TestLibrary.Nested.Aviary.IKeeper>> KeeperReportAsync(CancellationToken cancellationToken = default)
```

A C#-implemented keeper booked earlier and read back through `.Value` is the same instance the
caller passed in.

## `suspend () -> R` lambdas

```kotlin
val onFeedWith: suspend (String) -> String = { food ->
  delay(1.seconds)
  "$catName devoured the $food!"
}
```

```C#
using var feeder = new CatFeeder("Mylo");
using var onFeedWith = feeder.OnFeedWith;
string result = await onFeedWith.InvokeAsync("salmon");
```

`InvokeAsync` optionally accepts a `CancellationToken`; cancelling it throws
`TaskCanceledException` from the awaited call.

## Cancellation and disposal

Disposing the owning object cancels any coroutine it started, including children launched with
`coroutineScope { launch { ... } }`:

```kotlin
class CatNapService {
  suspend fun longNap(): String {
    delay(10.seconds)
    return "refreshed after nap"
  }
}
```

```C#
Task<string> task;
using (var service = new CatNapService())
{
    task = service.LongNapAsync();
    await Task.Delay(50);
} // Dispose() cancels the in-flight nap
// task throws TaskCanceledException
```

Every generated `async` method also accepts an explicit `CancellationToken`, independent of
`Dispose()`; cancelling it fails only that call, not sibling calls on the same object:

```C#
var cts = new CancellationTokenSource();
Task<string> napTask = service.LongNapAsync(cts.Token);
cts.Cancel(); // napTask throws TaskCanceledException
```

If the Kotlin function itself declares a parameter literally named `cancellationToken`, that name
stays on the user's own parameter and the generated token is renamed `cancellationToken_` instead
(see [C# names](primitives-and-strings.md#c-names)).

`DisposeAsync()` drains instead of cancelling: it waits for in-flight coroutines to finish
naturally before releasing the handle.

```C#
var service = new CatNapService();
Task<string> quickNap = service.QuickNapAsync();
await service.DisposeAsync(); // waits for quickNap, then releases
string result = await quickNap;
```

A class that both implements an exported interface and has `suspend`/`Flow` members still gets
`Dispose()`/`DisposeAsync()` on its own base list, alongside the interface:

```C#
public class NapPod : INapper, IDisposable, IAsyncDisposable, INugetHandle
```

Hold it as `IAsyncDisposable` through a field, a cast, or `await using`, and it resolves correctly.

An interface that itself declares a `suspend`/`Flow`/`StateFlow` member is `IAsyncDisposable` too,
and every implementer's version of that member is callable through the interface-typed reference
itself, not just through a concrete class; see
[Interfaces, abstract classes and sealed classes: Async members on an interface](interfaces-abstract-sealed.md#async-members-on-an-interface).

### A class with a Kotlin superclass {id="a-class-with-a-kotlin-superclass"}

One coroutine scope exists per instance, owned by whichever class is **first**, top to bottom, to
declare a `suspend` or `Flow`/`StateFlow`-returning member. It does not have to be the base:

```kotlin
open class SunShelf(val spot: String) {
  fun describeLounge(): String = "$spot is warm"
}

class NapLounge(spot: String) : SunShelf(spot) {
  suspend fun rest(cat: String): String {
    delay(10.milliseconds)
    return "$cat napped on $spot"
  }
}
```

```C#
await using var lounge = new NapLounge("the windowsill");
Assert.Equal("Oreo napped on the windowsill", await lounge.RestAsync("Oreo"));
// SunShelf itself is IDisposable but not IAsyncDisposable: it declares no async member.
```

The other direction works the same way: if the **base** declares the first async member, a subclass
that declares none of its own is not `IAsyncDisposable` a second time, it inherits `Dispose()`/
`DisposeAsync()` from the owner, and calling the member through either a base- or subclass-typed
reference reaches the one scope:

```kotlin
open class Feeder(val bowls: Int) {
  open suspend fun fill(): String { /* ... */ }
}

class TimedFeeder(bowls: Int, val hour: Int) : Feeder(bowls) {
  override suspend fun fill(): String { /* ... */ } // not re-declared in C#; dispatches dynamically
  suspend fun schedule(): Int { /* ... */ }
}
```

```C#
Feeder feeder = new TimedFeeder(2, 7);
await feeder.FillAsync();                              // reaches TimedFeeder.fill
await ((TimedFeeder)feeder).ScheduleAsync();
await feeder.DisposeAsync();                            // drains both, one scope
```

An `override suspend fun` is not declared as a second C# method: `Feeder.FillAsync` is the only one,
and Kotlin's own dynamic dispatch reaches the override. An abstract class that declares the first
async member declares `DisposeAsync` abstractly, and each concrete subclass carries the body as
`override`, so `await using` and a cast to `IAsyncDisposable` both work through the abstract type.

A class whose only `suspend`/`Flow` member was refused (see [Limitations](#limitations)) gets no
scope and no `IAsyncDisposable` anywhere in its chain, since nothing on it uses one.

## `Flow<T>` {id="flow-t"}

```kotlin
val mealAnnouncements: Flow<String> = flow {
  emit("$catName is hungry")
  delay(50.milliseconds)
  emit("$catName is eating")
  delay(50.milliseconds)
  emit("$catName is full")
}
```

```C#
using var feeder = new CatFeeder("Oreo");
await foreach (var item in feeder.MealAnnouncements)
    items.Add(item);
```

`Flow<T>` becomes `KotlinFlow<T> : IAsyncEnumerable<T>`. It is cold: each `await foreach` re-runs
the Kotlin flow from the start. `WithCancellation` stops the enumeration early.

An element type that is an interface, or a `List<T>`/`Set<T>`/`Map<K, V>`, is spelled and read
exactly like the same type at a property or `suspend` return, described above.

If an emitted element (or a `suspend` result) cannot be materialized on the C# side, `await
foreach` throws instead of aborting the process: the failure faults the `IAsyncEnumerable<T>` (or
the `Task`) and cancels the Kotlin side of the collection. One accepted cost: the item whose
materialisation failed had already had its handle handed over by Kotlin, so that one handle leaks;
see [ADR-161](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/161-csharp-callback-exception-into-kotlin.md).

### Nullable element `Flow<T?>` {id="flow-nullable-element"}

A nullable element carries its `?` onto `KotlinFlow<T?>`, and a `null` emission is a genuine item,
not the end of the stream:

```kotlin
fun petsPassingBy(): Flow<Pet?> = flow {
  emit(strayPet())
  emit(null)
  emit(strayPet())
}
fun napsPassingBy(): Flow<Int?> = flowOf(1, null, 3)
```

```C#
public KotlinFlow<global::TestLibrary.Cat.IPet?> PetsPassingBy()
public KotlinFlow<int?> NapsPassingBy()
```

```C#
using var window = new PassersBy();
var seen = new List<int?>();
await foreach (int? naps in window.NapsPassingBy()) seen.Add(naps);
// seen: [1, null, 3]
```

An interface element still resolves a stored C#-implemented instance the same way the non-null form
does; only a genuinely absent element is `null`. This is the element's own nullability
(`Flow<T?>`); the whole stream being absent (`Flow<T>?`) is a different, unsupported shape, see
Limitations.

## `StateFlow<T>`

```kotlin
private val _energyLevel: MutableStateFlow<Int> = MutableStateFlow(100)
val energyLevel: StateFlow<Int> = _energyLevel.asStateFlow()
```

```C#
using var tracker = new CatMoodTracker("Oreo");
int current = tracker.EnergyLevel.Value; // synchronous, no await
```

`StateFlow<T>` becomes `KotlinStateFlow<T> : KotlinFlow<T>`: hot, always has a current `.Value`,
and replays that value as the first element of any new `await foreach`. It never completes on its
own, so bound the enumeration with a `CancellationToken` or a `break`:

```C#
var cts = new CancellationTokenSource();
await foreach (var level in tracker.EnergyLevel.WithCancellation(cts.Token))
{
    seen.Add(level);
    cts.Cancel();
}
```

`KotlinStateFlow<T>` upcasts to `KotlinFlow<T>` and `IAsyncEnumerable<T>`, mirroring Kotlin's own
`StateFlow : Flow`. `T` can be a sealed base class; `.Value` and `await foreach` both materialize
the correct generated subclass, see
[Interfaces, abstract classes and sealed classes](interfaces-abstract-sealed.md).

## Enum and value class elements {id="enum-elements"}

`StateFlow<E>`, `StateFlow<E?>`, `Flow<E>` and `Flow<E?>` over an [enum](enums.md) read back as the
mapped C# enum, with `null` staying `null`. A value class element reads back as its record struct
the same way.

```kotlin
class CatMoodTracker(private val catName: String) {
  val temper: StateFlow<Mood>          // starts SLEEPY
  val maybeTemper: StateFlow<Mood?>    // null until sulk()
  fun sulk()
  fun moodSwings(): Flow<Mood?> = flow { emit(Mood.SLEEPY); emit(null); emit(Mood.GRUMPY) }
}
```

```C#
using var tracker = new CatMoodTracker("Mylo");
Mood now = tracker.Temper.Value;                  // Mood.Sleepy
tracker.Sulk();
Mood? maybe = tracker.MaybeTemper.Value;          // Mood.Grumpy

await foreach (Mood? mood in tracker.MoodSwings())
{
  // Mood.Sleepy, null, Mood.Grumpy
}
```

The same holds for an enum dependency admitted with `admit(...)` and for a nested enum.
[Generic classes](generics.md) instantiated at an enum read it back too (`Box<Mood>.Value`).
Writing an enum into a generic slot (`new Box<Mood>(Mood.Calm)`) is not supported yet.

## Settable `.Value` on `MutableStateFlow<T>`

A member whose **declared** type is `MutableStateFlow<T>`, not narrowed to `StateFlow<T>` (the
common `private val _x = MutableStateFlow(...)` / `val x: StateFlow<T> = _x.asStateFlow()` idiom
stays get-only), surfaces as `KotlinMutableStateFlow<T> : KotlinStateFlow<T>` with a settable
`.Value`:

```kotlin
val treatCount: MutableStateFlow<Int> = MutableStateFlow(0)
```

```C#
using var tracker = new CatMoodTracker("Mylo");
tracker.TreatCount.Value = 7;
```

The write lands in Kotlin synchronously and is visible to any live collector as its next
(conflated) emission. Writing is safe from any thread. Kotlin's `MutableStateFlow.value` setter
calls `equals` on the *previous* value to decide whether to conflate; if that `equals` throws, the
throw propagates out of the C# write as `KotlinInvalidOperationException`, unlike the get-only
`.Value` read, which never throws.

Reassigning the whole `MutableStateFlow<T>` member itself (a `var` holding a different flow
instance) is not supported; only writes through `.Value` are.

## Nullable `StateFlow<T?>` and `StateFlow<T>?`

A `StateFlow` can be nullable in the **element** (`StateFlow<T?>`) or the **member** itself
(`StateFlow<T>?`), independently, and the two compose:

```kotlin
val nickname: StateFlow<String?> = _nickname.asStateFlow()          // nullable element
val maybeMood: StateFlow<String>? get() = _maybeMood?.asStateFlow() // nullable member
```

```C#
public KotlinStateFlow<string?> Nickname { get; }   // .Value and every emission are string?
public KotlinStateFlow<string>? MaybeMood { get; }  // null until the member exists
```

A `null` element crossing `await foreach` is a genuine emission, not the end of the stream. Writing
a nullable element or a nullable member is not supported. A `suspend fun` returning `StateFlow<T?>`
is not supported either: that route reads its element through a shared export with no null arm and
is skipped with a diagnostic naming the member.

## Parameters on `Flow`, `StateFlow`, and `suspend` members

A `List`/`Set`/`Map`, primitive, `String`, or class/object/sealed-type parameter on a `Flow`-,
`StateFlow`-, or `suspend`-returning member crosses the same way it does anywhere else, spelled
exactly as the same member's return position spells it:

```kotlin
fun watch(observation: Observation.Alive): StateFlow<String> { /* ... */ }
suspend fun forget(ids: Set<String>): Int { /* ... */ }
```

```C#
public KotlinStateFlow<string> Watch(global::TestLibrary.Cat.Observation.Alive observation)
public Task<int> ForgetAsync(IReadOnlySet<string> ids, CancellationToken cancellationToken = default)
```

A nullable primitive, `Char`, `Boolean`, or `String` parameter on these same members carries its `?`
through as `int?`/`char?`/`bool?`/`string?`, and `null` reaches Kotlin as `null`:

```kotlin
suspend fun countNaps(limit: Int?): String { /* ... */ }
fun snacks(limit: Int?): Flow<String> { /* ... */ }
```

```C#
public Task<string> CountNapsAsync(int? limit, CancellationToken cancellationToken = default)
await feeder.Snacks(null); // limit reaches Kotlin as null, not 0
```

An enum parameter binds too, by ordinal (`(int)x` in C#, `Q.entries[x]` in Kotlin):

```kotlin
fun meows(asked: Hunger, hunger: Hunger = if (bowl > 30) Hunger.PECKISH else Hunger.STARVING): Flow<String> =
  flow { emit("$asked|$hunger") }
```

```C#
public KotlinFlow<string> Meows(Hunger asked, Hunger? hunger = null)
```

### Default arguments on these parameters {id="flow-suspend-parameter-defaults"}

A default on a scalar, `String`, or enum parameter here widens the same way a
[constructor or method default](classes-and-objects.md#constructor-and-method-default-parameters)
does: a non-nullable type widens to its nullable C# form (`null` means unset), an already-nullable
one widens to `KotlinOptional<T>`, and omitting the argument runs the Kotlin default:

```kotlin
class Dinnerbell(val bowl: Int) {
  suspend fun feed(cat: String, portion: Int = bowl + ++served, treats: Int? = 5): String =
    "$cat|$portion|$treats"
}
```

```C#
public Task<string> FeedAsync(string cat, int? portion = null, Optional<int?> treats = default,
                               CancellationToken cancellationToken = default);
```

```C#
using var bell = new Dinnerbell(10);
await bell.FeedAsync("Oreo"); // portion and treats: Kotlin evaluates bowl + ++served and 5
```

A defaulted handle or collection parameter (a class, `object`, sealed type, or `List`/`Set`/`Map`)
stays required; it does not widen. A same-name `suspend` overload whose shorter C# signature would
otherwise become ambiguous with the widened one (`CountAsync()` beside `CountAsync(int?, ...)`,
CS0121) keeps the widened parameter required-but-nullable instead of gaining `= null`:

```kotlin
class Dinnerbell {
  suspend fun count(): Int = -1
  suspend fun count(limit: Int = 3): Int = limit * 10
}
```

```C#
public Task<int> CountAsync(CancellationToken cancellationToken = default);
public Task<int> CountAsync(int? limit, CancellationToken cancellationToken = default); // no `= null`
```

Any other generic parameter (`Pair<A, B>`, `Array<T>`, a lambda), `Instant`/`Duration`/`Uuid`, a
value class, an interface, or a nullable class/object parameter (`Observation?`) is not supported at
these positions and is skipped with a diagnostic naming the member. Pass a class/object/sealed
handle, a `List`/`Set`/`Map`, an enum, or a primitive/`String` (nullable or not) instead, or split
the parameter across separate members.

## Limitations

- `SharedFlow<T>` (hot, multi-subscriber) is not supported.
- `MutableStateFlow<SomeEnum>` surfaces as read-only `KotlinStateFlow<SomeEnum>`: `.Value` reads the
  current entry but is not settable.
- `MutableStateFlow<ByteArray>` surfaces as read-only `KotlinStateFlow<byte[]>`, not
  `KotlinMutableStateFlow<byte[]>`: `.Value` is not settable for a `ByteArray` element.
- `CompareAndSet`, `Update`, `Emit`, `TryEmit`, `ReplayCache`, and `SubscriptionCount` on
  `MutableStateFlow<T>` are not exposed.
- A nullable-element or nullable-member `MutableStateFlow` write, and a `suspend fun` returning
  `MutableStateFlow<T>`, are not supported.
- A `suspend fun` returning `Flow<T>` has no binding, on a class or at top level.
- `StateFlow<T>` or `Flow<T>` as a function parameter, or as a generic type argument, is not
  supported.
- A nullable `Flow<T>?` (the whole stream absent, as opposed to a nullable *element* `Flow<T?>`,
  which is supported), and a `Pair` or a nullable collection (`List<T>?`) as a `Flow`/`StateFlow`
  element, are not supported.
- `Boolean?` / `Char?` value elements on a nullable `StateFlow` are not supported.
- A `suspend inline fun <reified T> Receiver.f(...): Result<T>` extension has no bridge at all:
  `inline` plus `reified` erase at the native boundary, and `suspend` needs a concrete
  continuation type. It is skipped with a diagnostic naming the extension.
- A top-level `suspend fun` declared as an **extension function** (`suspend fun String.extRet()`)
  has no route at all and is skipped with a diagnostic naming the extension.
- A bare `ByteArray` **parameter** on a `Flow`-, `StateFlow`-, or `suspend`-returning member is not
  supported, even though a `ByteArray` return or `Flow` element is (see
  [Collections](collections.md#bytearray-as-a-collection-component)). Pass it as a `List<ByteArray>`
  of one, or split it onto a separate ordinary member.
- `suspend fun (): StateFlow<ByteArray>` has no binding: the shared `nuget_stateflow_value` export
  has no per-member projection seam for a `ByteArray`. A `StateFlow<ByteArray>` **property** binds.

`Flow`, `StateFlow`, and `suspend` dispatch are AOT- and trim-safe; see
[Publishing Kotlin to C#: AOT and trimming](forward-overview.md#aot-and-trimming).

<seealso>
    <category ref="related">
        <a href="lambdas-and-callbacks.md">Lambdas and callbacks</a>
        <a href="exceptions.md">Exceptions</a>
        <a href="extensions.md">Extensions</a>
    </category>
</seealso>
