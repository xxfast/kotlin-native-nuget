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
| `SharedFlow<T>` | `KotlinSharedFlow<T> : KotlinFlow<T>`, replays the Kotlin replay cache first, never completes, adds `ReplayCache` |
| `MutableSharedFlow<T>` (declared, not narrowed to `SharedFlow<T>`) | `KotlinMutableSharedFlow<T> : KotlinSharedFlow<T>`, adds `SubscriptionCount`, `EmitAsync` and `TryEmit` |
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

A `suspend fun` binds at the top level, on a class or sealed type, and on an interface that some
function returns. On an `object`, a companion object, a value class, an enum, an interface nothing
returns, or as an extension, it is not bound: the member is skipped with a
`SKIPPED_UNSUPPORTED_COMBINATION` warning naming it.

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
of that type is. A closed instantiation of your own generic class (`Box<String>`) is a handle and
binds ([Generics](generics.md#returning-an-instantiated-generic-class)). Any other generic return
(`Pair<A, B>`, `Result<T>`, `Flow<T>`) has no C# binding and is skipped with a diagnostic naming the
member; expose the values through separate `suspend` functions instead.

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

A class `suspend fun` whose declared return is `MutableStateFlow<T>` awaits to a settable
`KotlinMutableStateFlow<T>`, written the same way as the [property form](#settable-value):

```C#
using KotlinMutableStateFlow<int> jar = await tracker.AwaitTreatJarAsync();
jar.Value = 4;
```

A top-level `suspend fun` returning `MutableStateFlow<T>` still awaits to a read-only holder.

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

### Nullable element or nullable holder {id="suspend-stateflow-nullable"}

A nullable element carries its `?` onto the holder, and a `null` is a genuine value for `.Value` and
`await foreach`. A nullable return awaits to `null` when Kotlin hands back no flow, so check it
before use:

```kotlin
suspend fun watchNapStreak(): StateFlow<Int?>   // Task<KotlinStateFlow<int?>>
suspend fun watchDen(): StateFlow<Cat>?         // Task<KotlinStateFlow<Cat>?>
```

```C#
using KotlinStateFlow<int?> streak = await CatWatch.WatchNapStreakAsync();
int? days = streak.Value;

using KotlinStateFlow<Cat>? den = await CatWatch.WatchDenAsync();
if (den is not null)
{
    using Cat cat = den.Value;
}
```

Both work on class methods and top-level functions, and combine as `StateFlow<T?>?`. A
`suspend fun` returning `MutableStateFlow<T?>` or `MutableStateFlow<T>?` binds as a read-only holder;
its `.Value` setter is not available.

### Collection element {id="suspend-stateflow-collection-element"}

A `List`, `Set` or `Map` element awaits to the same read-only collection a `StateFlow` property
gives, so `.Value` and `await foreach` return the collection, value-class elements included:

```kotlin
suspend fun awaitLitterSizes(): StateFlow<List<Int>>
```

```C#
using KotlinStateFlow<IReadOnlyList<int>> litters = await tracker.AwaitLitterSizesAsync();
IReadOnlyList<int> sizes = litters.Value;
```

`suspend fun (): MutableStateFlow<List<T>>` is not bound, and an awaited nullable
`StateFlow<List<T>>?` is not covered by an end-to-end test.

## `suspend fun` returning `Flow<T>` {id="suspend-fun-returning-flow-t"}

A suspend function that returns `Flow<T>` stays asynchronous: await it once to acquire a
`KotlinFlow<T>`. Acquisition does not start collection; later enumerations use the same Flow object
with its usual Kotlin Flow semantics.

```kotlin
class SuspendFlowCafe {
  suspend fun portions(): Flow<Int> = flow {
    emit(17)
    emit(29)
    emit(43)
  }
}
```

```C#
await using var cafe = new SuspendFlowCafe();
using KotlinFlow<int> portions = await cafe.PortionsAsync();
await foreach (int portion in portions)
    Console.WriteLine(portion);
```

Dispose the acquired `KotlinFlow<T>` when finished. `Dispose()` releases its holder and prevents new
collections; a collection already in progress continues. On the owner, `Dispose()` cancels active
work and `DisposeAsync()` drains it. The acquisition `CancellationToken` controls only acquisition;
use `WithCancellation` or the enumerator token to cancel a collection. A top-level function has no
owner, so dispose the holder and cancel collection through its enumerator or token.

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

The exception is a running `Flow` collection: `DisposeAsync()` cancels it rather than waiting, since
a reader that stopped reading without disposing its enumerator would otherwise block the drain
forever. Dispose your enumerators (`await foreach` does) so the Kotlin side sees a clean cancel.

```C#
var service = new CatNapService();
Task<string> quickNap = service.QuickNapAsync();
await service.DisposeAsync(); // waits for quickNap, then releases
string result = await quickNap;
```

A flow wrapper you read from a property, or from a method that returns a read-only flow, throws
`ObjectDisposedException` naming the owner if you use it after the owner is disposed, as a method
call would. A collection already running when you dispose still ends cleanly. A held or awaited
`MutableStateFlow` or `MutableSharedFlow` keeps reading and writing after the owner is disposed, but
a new collection or `EmitAsync` on it throws.

A wrapper you drop without disposing is not cancelled mid-flight: a pending `suspend` call, a running
`await foreach` and a held or awaited `StateFlow` keep the owner alive until they finish, and the
GC releases it afterwards (see [Classes and objects](classes-and-objects.md#object-identity-and-disposal)).

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
An `abstract suspend fun` with no body works the same way: the abstract class carries the one
`...Async` method and the scope, and calling it reaches the concrete subclass's body.

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

### Backpressure {id="flow-backpressure"}

A slow `await foreach` body slows the Kotlin producer, as a slow `collect` would. At most one item
sits unread on the C# side, and the flow body parks inside the next `emit`, so a side effect placed
before that `emit` runs one item ahead of what your loop has seen. A cancel or `break` hands out at
most that one buffered item. There is no C# prefetch setting; call `buffer(n)` on the Kotlin flow
if you want a larger lead.

```C#
await foreach (var treat in conveyor.Belt)
    await Task.Delay(1000); // the Kotlin producer waits here instead of racing ahead
```

The same applies to `StateFlow<T>`, `SharedFlow<T>` and a `Flow` returned from `suspend`.

An element type that is an interface, or a `List<T>`/`Set<T>`/`Map<K, V>`, is spelled and read
exactly like the same type at a property or `suspend` return, described above. An interface that appears
nowhere else in your exported API, only as a `Flow`/`StateFlow` element or a `suspend` result, still gets
its backing class like any other interface return.

If an emitted element (or a `suspend` result) cannot be materialized on the C# side, `await
foreach` throws instead of aborting the process: the failure faults the `IAsyncEnumerable<T>` (or
the `Task`) and cancels the Kotlin side of the collection. The failed item's handle is released,
not leaked; see [ADR-161](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/161-csharp-callback-exception-into-kotlin.md).

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
(`Flow<T?>`); the whole stream being absent is the next section.

### Nullable member `Flow<T>?` {id="flow-nullable-member"}

A property or method whose whole `Flow` can be absent binds as `KotlinFlow<T>?`, `null` when the
Kotlin member is `null`. Check it before enumerating:

```kotlin
val specialDiet: Flow<String>?
  get() = if (catName == "Mylo") null else flow { emit("$catName: salmon") }

fun visitorsAfter(meals: Int): Flow<Cat>? =
  if (meals == 0) null else flow { emit(Cat("Mylo")) }
```

```C#
KotlinFlow<string>? diet = oreo.SpecialDiet;         // null for Mylo
KotlinFlow<Cat>? visitors = feeder.VisitorsAfter(0); // null
```

A `suspend fun` returning `Flow<T>?` awaits to `Task<KotlinFlow<T>?>`, on a class or at top level;
dispose the acquired holder when it is not `null`. `SharedFlow<T>?` members behave the same way. A
top-level non-`suspend` `fun f(): Flow<T>?` still has no binding.

A method is called once to decide presence and again when you enumerate, so it must return the same
answer both times. Keep a nullable-returning member free of side effects.

## `SharedFlow<T>` {id="shared-flow-t"}

```kotlin
class CatBulletin(private val name: String) {
  val editions: SharedFlow<Int> = ...
  val headlines: MutableSharedFlow<String> = MutableSharedFlow(replay = 2)
  val moods: MutableSharedFlow<Mood> = MutableSharedFlow(replay = 1)
  fun editionReport(): SharedFlow<Int> = editions
  fun headlineDesk(): MutableSharedFlow<String> = headlines
  suspend fun awaitHeadlineDesk(): MutableSharedFlow<String> = headlines
  suspend fun latestSightings(): SharedFlow<Cat> = ...
  fun publish(headline: String, edition: Int) { ... }
}
```

```C#
using var bulletin = new CatBulletin("Oreo");
bulletin.Publish("found the treat jar", 1);

await foreach (string headline in bulletin.Headlines)
{
    Console.WriteLine(headline); // "Oreo: found the treat jar", replayed from the cache
    break;
}

IReadOnlyList<int> editions = bulletin.Editions.ReplayCache; // [1], no subscription
using KotlinSharedFlow<Cat> sightings = await bulletin.LatestSightingsAsync(); // dispose it
```

`SharedFlow<T>` becomes `KotlinSharedFlow<T> : KotlinFlow<T>`, and a `suspend` return becomes
`Task<KotlinSharedFlow<T>>`. It binds at a property, a method return and a `suspend` return on
classes, interfaces, sealed arms and top-level functions. `ReplayCache` is a snapshot of Kotlin's
`replayCache`, oldest first. A method that returns a `SharedFlow` is called again on every
read. For an object element, each read returns new wrappers that you own and dispose.

Each `await foreach` starts by receiving the Kotlin replay cache in order, then waits for new
emissions. A `SharedFlow` never completes on its own, so bound the loop with `break` or a
`CancellationToken`, as for [`StateFlow<T>`](#stateflow-t). With `replay = 0`, an item published
before you start enumerating is lost, and the bridge gives no signal that the collector has
subscribed. Element types follow `Flow<T>`, including a nullable element (`SharedFlow<String?>`
binds `KotlinSharedFlow<string?>`).

### Emitting from C# {id="shared-flow-emit"}

A member whose **declared** type is `MutableSharedFlow<T>`, not narrowed to `SharedFlow<T>` (the
common `_x.asSharedFlow()` idiom stays read-only), surfaces as `KotlinMutableSharedFlow<T>`:

```C#
KotlinMutableSharedFlow<string> desk = bulletin.Headlines;
await desk.EmitAsync("found the treat jar");
bool accepted = desk.TryEmit("napped on the keyboard"); // false if it would have to suspend

bulletin.Moods.TryEmit(Mood.Grumpy);                    // an enum crosses by ordinal

using KotlinStateFlow<int> subscribers = desk.SubscriptionCount;
int live = subscribers.Value;                           // collectors subscribed right now
```

- `EmitAsync` completes once every subscriber has taken the value, or it is buffered or replayed.
  It suspends only while a subscriber is busy, and an optional `CancellationToken` cancels it. An
  already-cancelled token returns a cancelled `Task` without emitting. Disposing the owner cancels
  a parked `EmitAsync` on a property, so its `Task` ends as cancelled instead of hanging.
- `TryEmit` never suspends, so it is the call to use from synchronous code.
- Writing an object element passes the wrapper for the call only; you keep ownership. A `null` for
  a non-null object element throws `ArgumentNullException`.
- `SubscriptionCount` returns a new `KotlinStateFlow<int>` on every read. Dispose it; a dropped
  one is released by the GC.
- A method returning `MutableSharedFlow<T>`, and a `suspend` function returning one, give you the
  flow itself. Keep it and dispose it, and every write lands in the same Kotlin flow:

```C#
using KotlinMutableSharedFlow<string> desk = bulletin.HeadlineDesk();
desk.TryEmit("held the desk"); // bulletin.Headlines.ReplayCache sees it
using KotlinMutableSharedFlow<string> awaited = await bulletin.AwaitHeadlineDeskAsync();
```

The elements you can write are those a `MutableStateFlow<T>` can set, described in
[Settable `.Value`](#settable-value). That includes a value class, which emits by value:
`TryEmit(new CatId("oreo-1"))` crosses as the underlying and Kotlin wraps it again, with the
same underlyings and the same `ArgumentException` for `default(CatId)` as a
[`MutableStateFlow` of a value class](#enum-elements). A `MutableSharedFlow` of any other element
(a `List`, `Set` or `Map`, a `ByteArray`, an interface, a value class over any other underlying,
or a nullable `Boolean`, `Char` or enum) binds
a read-only `KotlinSharedFlow<T>` and the build reports `SKIPPED_UNSUPPORTED_INPUT`. On a property
the remark names the read-only C# property; expose a function that takes the element to emit it.
`resetReplayCache()` is not exposed.

## `StateFlow<T>` {id="stateflow-t"}

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

### Data binding {id="data-binding"}

To bind a `StateFlow` to XAML (WPF, WinUI, MAUI, Avalonia), call `AsNotifying()`. It returns a
`KotlinStateFlowObservable<T>` that implements `INotifyPropertyChanged` and `IDisposable`; bind to
its `Value`. It works on `KotlinMutableStateFlow<T>` too, where a C# write to `Value` on the flow
reaches the binding.

```C#
using var tracker = new CatMoodTracker("Mylo");
// No SynchronizationContext here, so the event is raised inline
KotlinStateFlowObservable<string> mood = tracker.Mood.AsNotifying();
mood.PropertyChanged += (_, e) => Console.WriteLine($"{e.PropertyName}: {mood.Value}");

tracker.SetMood("zoomies"); // raises PropertyChanged("Value") on the thread that received it

// when the binding goes away
mood.Dispose();
```

- `Value` starts as the flow's current value and holds the last delivered element, so reading it
  costs nothing.
- `PropertyChanged` is posted to `SynchronizationContext.Current` at the `AsNotifying()` call, or to
  the context you pass (`AsNotifying(context)`). With no context it is raised on the thread that
  received the element. Call it on the UI thread, or pass the dispatcher context.
- Call `Dispose()` when the binding goes away. An adapter you drop keeps collecting and keeps the
  flow's owner alive for the life of the process.
- `Completion` finishes after `Dispose()` and faults if the Kotlin collection fails.
- A wrapper-typed element it replaces is not disposed, because you may still hold it.

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
[Generic classes](generics.md) instantiated at an enum read and write it too (`Box<Mood>.Value`,
`new Box<Mood>(Mood.Calm)`).

A non-null `MutableStateFlow<E>` of an enum is settable, on a class property, a function return, a
sealed-arm member and a class `suspend` return. The write crosses as the entry's ordinal, so an
ordinal that no longer names an entry (a C# cast such as `(Mood)99`) throws `KotlinException` and
the flow keeps its value:

```kotlin
class CatMoodTracker(private val catName: String) {
  val outlook: MutableStateFlow<Mood> = MutableStateFlow(Mood.SLEEPY)
}
```

```C#
tracker.Outlook.Value = Mood.Grumpy;
```

A nullable element (`MutableStateFlow<Mood?>`) stays a get-only `KotlinStateFlow`.

A `MutableStateFlow` of a value class is settable on the same routes, and `CompareAndSet` and the
`Update` family work on it. The write crosses as the underlying value and Kotlin wraps it again, so
the value class's `init` runs on every write:

```kotlin
class CatMoodTracker(private val catName: String) {
  val chipId: MutableStateFlow<CatId> = MutableStateFlow(CatId("oreo-chip"))   // value class CatId(val id: String)
  val spareChipId: MutableStateFlow<CatId?> = MutableStateFlow(null)
}
```

```C#
tracker.ChipId.Value = new CatId("oreo-9");
bool swapped = tracker.ChipId.CompareAndSet(new CatId("oreo-9"), new CatId("mylo-2"));  // true

tracker.SpareChipId.Value = new CatId("spare-1");
tracker.SpareChipId.Value = null;
```

- `CompareAndSet`, `Update`, `UpdateAndGet` and `GetAndUpdate` compare with Kotlin `equals` on the
  underlying value, never C# record-struct equality, so a freshly built `CatId` matches the stored
  one and a stale one does not.
- A nullable element (`MutableStateFlow<CatId?>`) is settable too and `null` clears it, except on a
  `suspend` return, which stays get-only for a nullable element of any kind.
- `default(CatId)` has no `Id`, so writing it, or passing it to `CompareAndSet`, throws
  `ArgumentException` in C# before anything crosses. This applies to a value class over a `String`
  or an object.
- The underlying must be a non-null `String`, a primitive other than `Char`, an enum, or an
  exported class or object. A value class over anything else (a `Char`, a nullable, another value
  class, a `kotlin.*` class such as `Instant` or `Uuid`) and a generic value class bind a get-only
  `KotlinStateFlow<T>`, and the build reports `SKIPPED_UNSUPPORTED_INPUT` naming the underlying.
  On a property the remark names the read-only C# property; expose a function that takes the value
  class to write it.

## Settable `.Value` on `MutableStateFlow<T>` {id="settable-value"}

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

A nullable element is settable too, and `null` is a valid write (a nullable `Int` crosses as
presence plus value, so `null` never lands as `0`):

```kotlin
val napMinutes: MutableStateFlow<Int?> = MutableStateFlow(null)
private var _diary: MutableStateFlow<String>? = null
val diary: MutableStateFlow<String>? get() = _diary
```

```C#
tracker.NapMinutes.Value = 15;
tracker.NapMinutes.Value = null;

KotlinMutableStateFlow<string>? diary = tracker.Diary;  // null until Kotlin creates it
if (diary is not null) diary.Value = "day two";
```

A `MutableStateFlow<T>?` property is settable once it is present. If Kotlin has dropped the flow
since you read it, the write throws `KotlinInvalidOperationException` instead of being ignored. A
function returning `MutableStateFlow<T>?` stays read-only. `Boolean?`, `Char?` and nullable enum
elements stay read-only.

Reassigning the whole `MutableStateFlow<T>` member itself (a `var` holding a different flow
instance) is not supported; only writes through `.Value` are.

### Atomic updates

`CompareAndSet(expect, update)` swaps only if the current value equals `expect` (Kotlin `equals`)
and returns whether it did. `Update`, `UpdateAndGet` and `GetAndUpdate` retry it until the
transform lands, like Kotlin's `update { }`, so concurrent callers lose nothing:

```C#
tracker.TreatCount.Value = 3;
bool moved = tracker.TreatCount.CompareAndSet(expect: 3, update: 4);

Parallel.For(0, 200, _ => tracker.TreatCount.Update(n => n + 1));
int now = tracker.TreatCount.UpdateAndGet(n => n * 2);   // the new value
int before = tracker.TreatCount.GetAndUpdate(n => n + 1); // the previous value
```

They work on every settable form above, nullable elements and members included. Things that differ
from a plain C# CAS:

- An object element is compared with Kotlin `equals`, which is identity for a plain class. A
  wrapper read back from `.Value` is the same Kotlin instance, so it matches; a second `Cat` with
  the same fields does not.
- A throwing Kotlin `equals` surfaces as `KotlinInvalidOperationException`, never as `false`.
- An absent `MutableStateFlow<T>?` throws on `CompareAndSet`, as the setter does.
- The transform may run more than once under contention, so keep it free of side effects. An
  exception it throws propagates unchanged and leaves the value alone.
- `Update` never disposes the value passed to the transform. Dispose any object wrapper you
  create or read yourself.

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

A `null` element crossing `await foreach` is a genuine emission, not the end of the stream. Writes
to a nullable `MutableStateFlow` are covered in [Settable `.Value`](#settable-value). A `suspend fun`
returning a nullable `StateFlow` binds as described [above](#suspend-fun-returning-stateflow-t).

## A flow inside a generic class {id="flow-type-argument"}

A `Flow<E>` or `StateFlow<E>` can be the type argument of an exported generic class, at every
position a generic class instantiation binds, including a top-level function return. `box.Value`
is a `KotlinFlow<E>` or `KotlinStateFlow<E>` that you collect and dispose like any other flow
holder:

```kotlin
class Box<T>(val value: T)

class BoxRadio {
  private val temper = MutableStateFlow(Mood.SLEEPY)

  fun ticks(): Box<Flow<Int>> = Box(flowOf(1, 2, 3))
  fun mood(): Box<StateFlow<Mood>> = Box(temper)
}
```

```C#
using var radio = new BoxRadio();
using Box<KotlinFlow<int>> box = radio.Ticks();
using KotlinFlow<int> ticks = box.Value;
await foreach (int tick in ticks)
    Console.WriteLine(tick);

using Box<KotlinStateFlow<Mood>> moodBox = radio.Mood();
using KotlinStateFlow<Mood> mood = moodBox.Value;
Mood current = mood.Value;
```

Elements follow the same rules as a `Flow` member, so an enum, a value class, a collection, an
interface, a nullable element and `ByteArray` all work. Three things differ from a `Flow` member:

- **Each `.Value` read returns a new holder** over the same Kotlin flow. Dispose every one; each
  `await foreach` still re-runs a cold flow from the start.
- **The holder owns the scope its collections run on.** Disposing the holder cancels them.
  Disposing the object that produced the box neither cancels them nor waits for them, so a
  collection that never completes does not hang the owner's `DisposeAsync`. A holder you never
  dispose keeps its collection running until it is finalized.
- **It is read-only.** `Box<MutableStateFlow<T>>` binds as a read-only `KotlinStateFlow<T>` and the
  build names the dropped setter. Handing a holder back into Kotlin
  (`new Box<KotlinFlow<int>>(ticks)`) throws `NotSupportedException`.

`Box<SharedFlow<E>>` binds as `KotlinFlow<E>`. A flow of a flow (`Box<Flow<Flow<Int>>>`), a lambda
element, a star projection, an open `Box<Flow<T>>`, and a flow argument of a generic sealed type
(`Outcome<Flow<Int>>`) are skipped with a diagnostic naming the member.

## Parameters on `Flow`, `StateFlow`, and `suspend` members {id="parameters-on-flow-stateflow-and-suspend-members"}

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

A nullable `List`, `Set` or `Map` parameter binds as the nullable C# collection, and `null` reaches
Kotlin as `null`, never an empty collection:

```kotlin
fun servingsFor(kinds: List<String>?, cats: List<Cat>?): Flow<String>
suspend fun tallyFor(kinds: List<String>?, cats: List<Cat>?): String
```

```C#
public KotlinFlow<string> ServingsFor(IReadOnlyList<string>? kinds, IReadOnlyList<Cat>? cats)
public Task<string> TallyForAsync(IReadOnlyList<string>? kinds, IReadOnlyList<Cat>? cats,
                                  CancellationToken cancellationToken = default)
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

A defaulted handle parameter (a class, `object` or sealed type) widens the same way: a non-null
declared type becomes `Placemat? mat = null` (`null` means unset), and an already-nullable one
becomes `KotlinOptional<Placemat?> mat = default`, where an explicit `null` stays a value. Kotlin
builds the default instance; you never construct it. A defaulted non-null `List`, `Set` or `Map`
parameter stays required; it does not widen, but a defaulted nullable one does (`tags: List<String>? = null`
becomes `KotlinOptional<IReadOnlyList<string>?> tags = default`: omitted runs the Kotlin default, an
explicit `null` arrives as `null`). A same-name `suspend` overload whose shorter C# signature would
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

A class, `object` or sealed handle parameter may be nullable. It binds as `T?` and `null` reaches
Kotlin as `null`:

```kotlin
class Checkup(private val vet: String) {
  suspend fun examine(room: String, cat: Cat?): String
}
```

```C#
public Task<string> ExamineAsync(string room, Cat? cat, CancellationToken cancellationToken = default);
```

```C#
using var checkup = new Checkup("Dr Purr");
await checkup.ExamineAsync("room 2", null); // Kotlin sees cat == null
```

This holds for `suspend` members, sealed-subclass members, top-level `suspend` functions,
`Flow`/`StateFlow` members and `suspend` functions returning `StateFlow`. A nullable handle with a
default value (`cat: Cat? = null`) is still required in C#, so pass `null` explicitly.

Any other generic parameter (`Pair<A, B>`, `Array<T>`, a lambda; your own `Box<String>` binds as a
handle), `Instant`/`Duration`/`Uuid`, a value class or an interface is not supported at these
positions and is skipped with a diagnostic naming the member. Pass an enum, or a
class/object/sealed handle, `List`/`Set`/`Map` or primitive/`String` (each of these nullable or
not) instead, or split the parameter across separate
members. A `Throwable`, `Exception` or `RuntimeException` parameter does bind, as a
`System.Exception` (see [Throwable values](exceptions.md#throwable-values)).

## Limitations

- `MutableStateFlow<ByteArray>` surfaces as read-only `KotlinStateFlow<byte[]>`, not
  `KotlinMutableStateFlow<byte[]>`: `.Value` is not settable for a `ByteArray` element.
- `Emit`, `TryEmit`, `ReplayCache`, and `SubscriptionCount` on
  `MutableStateFlow<T>` are not exposed (`CompareAndSet` and `Update` are; see [Atomic updates](#atomic-updates)).
- A `suspend fun` returning `MutableStateFlow<T?>` or `MutableStateFlow<T>?`, and a top-level
  `suspend fun` returning `MutableStateFlow<T>`, bind a read-only holder.
- `StateFlow<T>` or `Flow<T>` as a function parameter is not supported. As a generic type argument
  it binds [read-only](#flow-type-argument).
- A top-level non-`suspend` `fun f(): Flow<T>?` and an `object` or companion `Flow<T>?` member are
  skipped with a diagnostic, like their non-null forms.
- A `Pair` or a nullable collection (`List<T>?`) as a `Flow`/`StateFlow` element is not supported.
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
- `suspend fun (): StateFlow<Throwable>` and any `MutableStateFlow<Throwable>` have no binding, for
  the same shared-export reason. A read-only `StateFlow<Throwable>` property or method binds; see
  [Throwable values](exceptions.md#throwable-values).

`Flow`, `StateFlow`, and `suspend` dispatch are AOT- and trim-safe; see
[Publishing Kotlin to C#: AOT and trimming](forward-overview.md#aot-and-trimming).

<seealso>
    <category ref="related">
        <a href="lambdas-and-callbacks.md">Lambdas and callbacks</a>
        <a href="exceptions.md">Exceptions</a>
        <a href="extensions.md">Extensions</a>
    </category>
</seealso>
