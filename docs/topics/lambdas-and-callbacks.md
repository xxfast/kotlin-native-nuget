# Lambdas and callbacks

A lambda crosses the bridge in one of three shapes, depending on who owns it and how long it
lives: a Kotlin lambda exposed to C# as a plain .NET delegate, a C# lambda Kotlin invokes once per
call and does not keep, or a C# lambda Kotlin stores as an observer until you dispose it. A fourth
shape lets a C# class implement a Kotlin interface and register it as such an observer.

## Kotlin → C#: lambda properties and returns {id="kotlin-c-lambda-properties-and-returns"}

A lambda-typed property, and a function that returns a lambda, bind as a plain .NET delegate, on
every owner. A property is a `val` or a `var`:

| Kotlin | C# |
|---|---|
| `(A) -> R` | `Func<A, R>` |
| `(A) -> Unit` | `Action<A>` |
| `suspend (A) -> R` | `Func<A, CancellationToken, Task<R>>` |
| `suspend (A) -> Unit` | `Func<A, CancellationToken, Task>` |

A nullable lambda is the same type with `?`. There is nothing to dispose: the delegate you read or
receive calls the Kotlin lambda, and the bridge frees its native handle when the delegate is
collected.

```kotlin
val onMeow: () -> String = { "Meow! My name is $name" }
val onPet: (String) -> String = { action -> "$name $action contentedly" }
val onNap: () -> Unit = { naps++ }
```

```C#
using var cat = new Cat("Oreo", 9);
string result = cat.OnPet.Invoke("purrs"); // "Oreo purrs contentedly"

cat.OnNap.Invoke();
```

A `var` also takes a delegate from C#, with no cast, and Kotlin calls it from then on. A nullable
`var` reads and writes `null`:

```kotlin
object Landing {
  var format: (Double) -> String = { metres -> "Oreo ran $metres m" }
  var onBump: ((String) -> String)? = null
  var onSettle: (suspend (String) -> String)? = null
  fun describe(metres: Double): String = format(metres)
}
```

```C#
Landing.Format = metres => $"Mylo timed {metres} m";
Landing.Describe(4.0); // "Mylo timed 4 m"

Landing.OnBump = who => $"Oreo bumped into {who}";
Landing.OnBump = null; // Kotlin sees null again

Landing.OnSettle = async (who, token) =>
{
    await Task.Delay(1, token);
    return $"{who} curled up on the stairs";
};
```

A delegate you read from a Kotlin property and set back goes back as the same Kotlin lambda. A delegate
you wrote stays alive for as long as the property holds it, and Kotlin releases it when you replace
or clear the property. A `suspend` property is awaited with the token you pass:

```C#
Func<int, CancellationToken, Task<string>> onSprint = Landing.OnSprint;
string s = await onSprint(4, CancellationToken.None); // "Oreo sprinted 4 laps"
```

### Which owners bind {id="which-lambda-properties-bind"}

Every owner binds, each in the spelling its other members use. A [returned lambda](#a-top-level-function-that-returns-a-lambda) binds on the same owners:

| Kotlin owner | C# |
|---|---|
| class, sealed base and arm | instance property |
| `object`, companion (default or named), top level | static property on the object, owning class or file class |
| enum body | `Name()` getter extension and, for a `var`, `SetName(...)`: `Stride.Trot.OnStride()`, `Stride.Gallop.SetOnTrip(f)` |
| enum companion | static property on `{Enum}Extensions` |
| generic class | instance property typed by the closed `T`: `Relay<string>.OnPass` is `Func<string, string>` |
| Kotlin interface | property on the interface; a C# class implements it and Kotlin reads and writes it |
| [listener interface](#a-listener-val) | the same, read back by Kotlin through the subscription |

```C#
// A C# class implementing a Kotlin interface that declares lambda properties
private sealed class FeatherWand : IPouncer
{
    public Func<int, string> OnPounce { get; } = times => $"Oreo swatted the feather {times} times";
    public Func<string, string>? Cue { get; set; }
    public Func<int, CancellationToken, Task<int>> OnLeap { get; } = async (height, token) =>
    {
        await Task.Delay(1, token);
        return height * 10;
    };
    public void Dispose() { }
}
```

A generic payload binds too: `val unwind: () -> Tote<Tote<Skein>>` is `Func<Tote<Tote<Skein>>>`, and
the `Tote` instances you read are wrappers you dispose as usual (see
[Ownership of a callback payload](#ownership-of-a-callback-payload)).

An exception a Kotlin getter throws surfaces as a `KotlinException` on the read, on a sealed arm too.

### Lambdas a function returns {id="a-top-level-function-that-returns-a-lambda"}

A function or member that returns a lambda binds as the same delegate, on every owner a property
binds on: a class, `object`, companion, top-level function, enum, value class, generic class, sealed
base and arm, extension, and a Kotlin interface. The returned lambda keeps whatever it captured, and
you call it later.

```kotlin
class Tassel(val colour: String) {
  fun cheer(): (String) -> String = { who -> "$colour tassel for $who" }
  fun chant(): suspend (String) -> String = { who -> /* ... */ "$colour chant for $who" }
  fun maybeCheer(loud: Boolean): ((String) -> String)? = if (loud) { who -> "$colour roar for $who" } else null
}

object Bleachers { fun cheer(): (String) -> String = { who -> "the bleachers cheer $who" } }
fun adder(n: Int): (Int) -> Int = { it + n }
```

```C#
using var tassel = new Tassel("red");
Func<string, string> cheer = tassel.Cheer();
Func<string, CancellationToken, Task<string>> chant = tassel.Chant();
Func<string, string>? loud = tassel.MaybeCheer(true); // null when Kotlin returned null

string s = Bleachers.Cheer().Invoke("Oreo");           // static on an object
string t = await chant.Invoke("Mylo", CancellationToken.None);
Func<int, int> twoMore = PetRelayKt.Adder(2);
```

Nothing to dispose: the delegate calls the Kotlin lambda, and the bridge frees its native handle when
the delegate is collected. An extension binds as an extension method (`tassel.Encore()`,
`"ha".Refrain()`), and an enum body member as an extension on the enum
(`Formation.Pyramid.Call()`).

A returned lambda's payloads and results are the [callback vocabulary](#callback-vocabulary). A value
class crosses boxed, not by its underlying, so its own `init` still validates; one with no crossing at
this position is refused by name, and so is one with no C# record struct (see
[Value classes](value-classes.md)). An exported interface type argument (`Func<IPet, IPet>`) is
spelled with the interface itself, never the ADR-040 backing wrapper, and passing your own C#
implementation to the delegate returns that same instance; a Kotlin-backed value still materializes
through the interface, see
[An interface at an erased position](generics.md#an-interface-at-an-erased-position).

Other parameters beside the returned lambda can be an interface, an exported class, an enum, a
nullable, or a collection. The lambda captures them: a C#-implemented interface you pass stays alive
for as long as the lambda does, so it remains valid after the call returns, and a `null` for a
nullable parameter reaches Kotlin as `null`.

```C#
IPet rex = new Dog("Rex"); // your own C# implementation of IPet
Func<IPet> supplier = PetRelayKt.PetSupplier(rex);
IPet same = supplier(); // the same instance as rex
```

A C# class implementing a Kotlin interface can return a delegate from a member, and Kotlin calls it,
`suspend` and nullable ones included:

```kotlin
interface Chanter {
  fun cheer(): (String) -> String
  fun chant(): suspend (String) -> String
  fun maybeCheer(loud: Boolean): ((String) -> String)?
}
fun rally(chanter: Chanter, who: String): String = chanter.cheer()(who)
```

```C#
private sealed class Pompom(string shaker) : IChanter
{
    public Func<string, string> Cheer() => who => $"{shaker} shakes for {who}";
    public Func<string, CancellationToken, Task<string>> Chant() => async (who, token) =>
    {
        await Task.Delay(1, token);
        return $"{shaker} chants for {who}";
    };
    public Func<string, string>? MaybeCheer(bool loud) => loud ? who => $"{shaker} roars for {who}" : null;
    public void Dispose() { }
}

CheersquadSample.Rally(new Pompom("Oreo"), "Mylo"); // "Oreo shakes for Mylo"
```

A Kotlin lambda such a member receives as a parameter is a `Func<>`/`Action<>` too, see
[every member shape](interfaces-abstract-sealed.md#implementing-every-member-shape).

### Migrating from `KotlinFunc` {id="migrating-from-kotlinfunc-properties"}

Lambda properties and returned lambdas used to be `KotlinFunc<..>`, `KotlinAction<..>` and
`KotlinSuspendFunc<..>` handles. A property was read-only and non-null; a return bound only on a
top-level function, and a lambda returned from a member of a class, `object` or companion was
skipped. If you upgrade, expect these compile errors:

| Before | After |
|---|---|
| `using var f = obj.OnPet;` or `using KotlinFunc<int, int> f = Decks.Adder(2);` | `var f = ...;` (a delegate is not `IDisposable`) |
| `KotlinFunc<A, R>` / `KotlinAction<A>` | `Func<A, R>` / `Action<A>` |
| `KotlinSuspendFunc<A, R>` / `KotlinSuspendAction<A>` | `Func<A, CancellationToken, Task<R>>` / `Func<A, CancellationToken, Task>` |
| `await f.InvokeAsync(a)` | `await f.Invoke(a, token)`; pass `CancellationToken.None` if you have none |
| `typeof(KotlinFunc<A, R>)` on the property or return type, by reflection | `typeof(Func<A, R>)` |

A `var` lambda property now has a setter and a nullable one binds, where both were skipped before.
A lambda returned from a member of a class, `object`, companion, enum, interface, sealed type, generic
class or extension now binds too.

Why a delegate rather than a wrapper with an implicit conversion: C# never applies a user-defined
conversion to a lambda literal (CS1660), so `obj.OnLap = i => ...` would not compile against a
wrapper type.

### A lambda's type arguments across a namespace boundary {id="type-arguments-across-a-namespace-boundary"}

A lambda property's type arguments are qualified with their full namespace automatically, so a
property whose parameter or result type lives outside the declaring class's namespace still
compiles without you spelling it out:

```C#
public Func<global::TestLibrary.Catcam.Lens.CamId, global::TestLibrary.Catcam.Lens.Snapshot> OnPick => ...
```

If a type argument has no C# spelling at all, for example a lambda returning `Flow<T>`, the
property is dropped from the generated class instead of emitting code that won't compile. If a
lambda-typed property you expect is missing, check the build's warnings, and the owner and
owner rules above.

## C# → Kotlin: per-call lambda parameters {id="c-kotlin-per-call-lambda-parameters"}

```kotlin
fun describeWith(format: (String) -> String): String = format(name)
fun nicknamesMatching(predicate: (String) -> Boolean): List<String> = nicknames.filter(predicate)
fun forEachToy(action: (Toy) -> Unit) = toys.forEach(action)

// The member's own (outer) return survives a lambda parameter, at any type the plan supports.
fun countTicks(listener: (Int) -> Unit): Int {
  repeat(beats) { listener(it + 1) }
  return beats
}

// The lambda's own (inner) return crosses back by value too.
fun sumWeights(weigh: (Int) -> Int): Int = (1..beats).sumOf { weigh(it) }

// A non-lambda parameter is fine beside the lambda.
fun countAbove(min: Int, listener: (Int) -> Unit): Int { /* ... */ }
```

```C#
using var cat = new Cat("Oreo", 9);
string described = cat.DescribeWith(name => $"This cat is called {name}");

int minLength = 6;
IReadOnlyList<string> matching = cat.NicknamesMatching(n => n.Length >= minLength);

using var metronome = new Metronome(4);
var seen = new List<int>();
int total = metronome.CountTicks(t => seen.Add(t));   // 4, seen == [1, 2, 3, 4]

int sum = metronome.SumWeights(t => t * 2);            // 20
int fired = metronome.CountAbove(2, t => seen.Add(t)); // only ticks above 2 fire
```

Pass an ordinary `Func<>`/`Action<>`, including a capturing lambda or a method group, arity 0 to 3.
Kotlin invokes it once per call and does not retain a reference afterwards; do not rely on a
delegate passed here firing again later (use a stored callback, below, for that). A `kotlin.*`
primitive parameter (`Int`, `Boolean`, `Byte`, `Double`, ...) crosses by value, not through a
handle, so the callback body receives it directly with nothing to dispose; so does a primitive or
`String` value the lambda itself returns. Unsigned primitives (`UInt`, `ULong`, `UByte`, `UShort`)
cross the same way and keep their full range. A Kotlin interface is a supported payload even when
no other member exposes it: the callback receives a live `I...` wrapper that you
[own and dispose](#ownership-of-a-callback-payload). The member's own return can be a scalar, `String`, an
exported object, its nullable twin, or an enum, the same set an ordinary method return supports.

Passing `null` throws `ArgumentNullException` naming the Kotlin parameter, at the call site, before
Kotlin runs anything:

```C#
metronome.CountTicks(null!); // throws ArgumentNullException, ParamName == "listener"
```

This holds regardless of the Kotlin parameter's own nullability (`listener: (Int) -> Unit` and
`listener: ((Int) -> Unit)?` both throw the same way), and on a class member, a top-level function,
and a sealed arm alike. The one exception is a lambda parameter with a Kotlin default value: there,
`null` means "use the default" rather than "no listener", so no guard runs.

The same route binds a method declared on a sealed arm (see
[Lambda parameters on a sealed arm](interfaces-abstract-sealed.md#sealed-lambda-generated-c)), a
top-level function, or an extension function.

### The callback vocabulary {id="callback-vocabulary"}

A payload or result of a callback is the same set on every lambda route: a per-call or `suspend`
lambda parameter, a stored `add`/`remove` pair, a returned lambda and a lambda property. It
crosses in its usual C# spelling:

| Kotlin | C# |
|---|---|
| primitive, unsigned primitive, `String`, `Char` | the same-named C# type |
| enum, exported class or interface, [value class](value-classes.md) | the generated type |
| `List<T>` / `Set<T>` / `Map<K, V>` | `IReadOnlyList<T>` / `IReadOnlySet<T>` / `IReadOnlyDictionary<K, V>` |
| `ByteArray` | `byte[]` |
| `Instant` / `Duration` / `Uuid` | `DateTimeOffset` / `TimeSpan` / `Guid` |
| a bound C# interface such as `IFeedable` | the interface itself, the same object you implemented |
| a member's or class's own `T` | `T`, see [below](#a-type-parameter-in-the-lambda) |
| `Any` | `object`, see [Any](primitives-and-strings.md#any) |
| `Throwable` | `Exception`, see [Throwable values](exceptions.md#throwable-values) |
| any of these, nullable | the same type with `?` |

A nullable payload arrives as `null`, and a callback that returns `null` for a nullable result
reaches Kotlin as `null`, never as a crash. A collection is copied at the crossing, as at any
method boundary. A `Throwable` result of a `suspend` lambda is a `Task<Exception>`, and a `Throwable?` payload arrives as `Exception?`. A result declared narrower than `RuntimeException` (such as `IllegalStateException`) is refused, because C# hands Kotlin a `NugetManagedException`.

`Any` is not among the refused payloads: a per-call `(Any) -> Unit` parameter is `Action<object>`,
a stored `add`/`remove` pair and a `suspend` lambda take it as `object` too, and the payload is read
as its runtime C# value (see [Any](primitives-and-strings.md#any)).

```kotlin
fun lineup(pick: (String) -> List<String>): String = "$judge called ${pick(judge).joinToString()}"
fun scores(describe: (Int?) -> String): String = "${describe(null)} then ${describe(7)}"
fun askWeave(onAsk: (Int) -> String?): String? = onAsk(1)
```

```C#
using var card = new Scorecard("Mylo");
card.Lineup(who => new[] { who, "Oreo" });                // "Mylo called Mylo, Oreo"
card.Scores(n => n?.ToString() ?? "none");                // "none then 7"
using var strip = new ClawStrip("Oreo");
strip.AskWeave(_ => null);                                // null reaches Kotlin as null
```

A bound C# interface payload is a live `IFeedable` of your own, not a wrapper:

```C#
using var pen = new Pen();
pen.Adopt(goat); // your own IFeedable
IFeedable? seen = null;
pen.Inspect(feedable => { seen = feedable; return feedable.Legs; });
// seen is goat, the same object
```

These shapes are refused by name (a build-time skip, not broken generated code):

- A lambda of four or more parameters. `(Int, Int, Int, Int) -> Int` takes 4 parameters, and a lambda
  crosses with at most 3 on every callback route. Group the rest into a class, or split the lambda.
- A `Pair` or `IntArray` payload. Pass a class that holds the values instead.
- A lambda type nested inside a `List`/`Set`/`Map`.

A lambda parameter on a constructor, a data class's `copy()`, an enum-arm box constructor, or a
value-class member is also unbound, since none of those can keep the callback registered past the
single call that creates them. A [`suspend` lambda parameter](coroutines-and-flow.md#suspend-lambda-parameter)
is a different shape with its own rules: it is not per-call, it binds on a constructor and on a method
that stores it, and Kotlin owns its lifetime.

```kotlin
class Crowd {
  fun group(pick: (Int, Int, Int, Int) -> Int): Int = pick(1, 2, 3, 4)
}
```

The build warns, naming `Crowd.group`: `(Int, Int, Int, Int) -> Int` takes 4 parameters, and a lambda
crosses with at most 3 on every callback route. Group the rest into a class, or split the lambda. The
member is generated on neither half, Kotlin included, so nothing calls a delegate that doesn't exist
on the C# side.

### A nullable lambda parameter {id="a-nullable-lambda-parameter"}

A parameter whose own type is nullable (`listener: ((Int) -> Unit)?`) still binds, spelled as an
ordinary non-nullable delegate (`Action<int>`). The nullable Kotlin spelling has no C# effect:
`null` is refused here exactly as it is on the non-nullable spelling above, since the route has no
way to express "no listener" other than not calling the method at all.

```kotlin
fun onMaybeTick(listener: ((Int) -> Unit)?) = repeat(beats) { listener?.invoke(it + 1) }
```

```C#
metronome.OnMaybeTick(tick => ticks.Add(tick)); // works
metronome.OnMaybeTick(null!);                   // throws ArgumentNullException, same as a non-nullable listener
```

A nullable `suspend` lambda parameter is the exception: it binds as a nullable `Func<..>?`, and
`null` reaches Kotlin as `null`. See [A `suspend` lambda as a parameter](coroutines-and-flow.md#suspend-lambda-parameter).
A nullable *payload* (`listener: (Int?) -> Unit`) or *result* binds as in the
[vocabulary](#callback-vocabulary) above, on a sealed arm and on a stored `add`/`remove` pair too.

### A type parameter in the lambda {id="a-type-parameter-in-the-lambda"}

A generic member can name its own `T` (or its class's) as a lambda's payload or result. It binds
as a generic method taking an ordinary delegate, and a top-level `fun <T>` can return a lambda over
`T` as a `Func<T, T>`:

```kotlin
fun <T> each(items: List<T>, action: (T) -> Unit)
fun <T> map(value: T, transform: (T) -> T): T
fun <T> echoer(): (T) -> T   // top level
```

```C#
dealer.Each(new[] { "Oreo", "Mylo" }, name => seen.Add(name));
using Tabby same = dealer.Map(oreo, cat => cat);
Func<int, int> echo = Decks.Echoer<int>();
```

A wrapper `T` follows [the ownership rules below](#ownership-of-a-callback-payload) as a payload.
The rest, including bounds and `T?`, is covered in [Generics](generics.md#a-type-parameter-in-a-lambda).

A `suspend (T) -> T` lambda over a member's or class's `T` binds the same way, as
`Func<T, CancellationToken, Task<T>>`; see [A type parameter in a `Flow` or `suspend` lambda
parameter](coroutines-and-flow.md#flow-and-suspend-lambda-type-parameter). A plain lambda also binds on a `suspend` member and on a member returning a `Flow`; see
[below](#plain-lambda-on-suspend-or-flow-member).

## A plain lambda on a `suspend` or `Flow` member {id="plain-lambda-on-suspend-or-flow-member"}

A plain lambda parameter on a `suspend` member, or on a member that returns a `Flow`, binds as a
delegate on every owner those members bind on:

```kotlin
suspend fun run(steps: Int, onStep: (Int) -> Unit): Int { /* ... */ }
suspend fun recruit(name: String, make: (String) -> Baton): String = make(name).holder
```

```C#
await treadmill.RunAsync(3, step => seen.Add(step));
string holder = await treadmill.RecruitAsync("Oreo", name => new Baton(name));
```

Kotlin may call the delegate repeatedly and from other threads while the coroutine runs, so it must
be thread-safe. It stays valid until the `Task` completes, faults or is cancelled, or until the
collection ends. Lifetime for each
shape, including a `suspend` member that returns a `Flow`, is on
[A plain lambda on a `suspend` or `Flow` member](coroutines-and-flow.md#plain-lambda-on-suspend-member).

A lambda that returns an exported class binds on every per-call lambda route: return a new instance or
an existing one, and Kotlin takes its own reference.

## Ownership of a callback payload {id="ownership-of-a-callback-payload"}

A value handed to your callback from any of the C# → Kotlin routes on this page is owned by C#, not
Kotlin, once it crosses:

- a `string` (or other marshalled value) arrives already fully read; there is nothing to dispose.
- an exported object arrives as a live wrapper you own, the same as any other object you construct
  or receive. Dispose it yourself to release it promptly:

```C#
cat.ForEachToy(toy =>
{
    using var t = toy;
    toyNames.Add(t.Name);
});
```

A payload your callback never disposes is not leaked: the .NET GC releases it when it finalizes the
wrapper, eventually and not at process exit, the same as any other
[dropped wrapper](classes-and-objects.md#object-identity-and-disposal). A primitive payload never
had a handle, so there is nothing to own.

The rule applies just as much when the lambda also returns a value picked from its payload: dispose
the payload before returning from it, not after.

```C#
using Chime chime = metronome.FirstChime(c =>
{
    using (c) { return c.Weight >= 2; }
});
```

The object `FirstChime` itself returns is a separate, freshly retained handle and is yours to
dispose too, the same as any other exported-object return.

## C# → Kotlin: stored callbacks {id="c-kotlin-stored-callbacks"}

```kotlin
fun addMoodListener(listener: (Mood) -> Unit) = moodListeners.add(listener)
fun removeMoodListener(listener: (Mood) -> Unit) = moodListeners.remove(listener)
```

```C#
using var cat = new Cat("Oreo", 9);
var recorded = new List<string>();
using IDisposable sub = cat.AddMoodListener(mood => recorded.Add(mood.ToString()));

cat.TriggerMoodChange(Mood.Happy); // recorded == ["Happy"]
sub.Dispose(); // no further callbacks fire
```

A Kotlin `add{X}`/`remove{X}` (or `subscribe{X}`/`unsubscribe{X}`) pair, both taking the same
lambda type, is generated as a single `AddXxx` that returns `IDisposable` instead of two separate
methods: dispose it to unsubscribe, there is no public `RemoveXxx` method generated for you to call
directly.

Dropping the `IDisposable` without disposing it leaves the listener registered, and it keeps
delivering while the receiver is alive. Disposing the receiver, or letting the GC finalize it,
unregisters every subscription still attached to it. Disposing the subscription after that is a
silent no-op.

`AddXxx(null!)` throws `ArgumentNullException` naming the parameter, before anything subscribes,
whatever the Kotlin listener type's own nullability; a Kotlin subscription never has a way to mean
"subscribe to nothing", so there is no null spelling to honour. Calling `AddXxx` on an already
disposed receiver throws `ObjectDisposedException` instead (the null check runs first, so a null
listener on a disposed receiver still names the argument):

```C#
var ex = Assert.Throws<ArgumentNullException>(() => cat.AddMoodListener(null!));
Assert.Equal("listener", ex.ParamName);

cat.Dispose();
Assert.Throws<ObjectDisposedException>(() => cat.AddMoodListener(mood => { }));
```

`Dispose()` does not wait for an invocation already in flight on another thread: a call
that lands after `Dispose()` is dropped silently for a `void` listener, without running your code
or crashing. A handle-passed payload (an exported object) it carried is released for you; there is
nothing to dispose. A per-call lambda kept past the single call that supplied it and invoked later
is a different case, see
[below](#exceptions-from-a-callback).

The same pattern also binds a pair declared on a sealed arm; see
[Stored-callback and interface-bridge pairs on a sealed arm](interfaces-abstract-sealed.md#sealed-callback-pair-generated-c).

A stored listener takes the same [callback vocabulary](#callback-vocabulary) as a per-call lambda,
and it can return a value: Kotlin reads the result of each call, so `addJudge(pick: (String) ->
Spirit)` binds as `AddJudge(Func<string, Spirit>)`. Payloads include collections, nullables and the
rest of the vocabulary, and `add`/`remove` still go together.

```kotlin
fun addJudge(pick: (String) -> Spirit) { judges.add(pick) }
fun removeJudge(pick: (String) -> Spirit) { judges.remove(pick) }
fun addWatcher(listener: (List<String>) -> Unit) { watchers.add(listener) }
fun removeWatcher(listener: (List<String>) -> Unit) { watchers.remove(listener) }
```

```C#
using IDisposable judge = card.AddJudge(_ => Spirit.Hyped);   // card.Verdicts() == "HYPED"
using IDisposable watcher = card.AddWatcher(names => seen.AddRange(names));
```

The pair binds on a generic owner too, typed by the closed `T`:

```kotlin
class Shaker<T>(val item: T) {
  fun addShake(listener: (T) -> Unit) { /* ... */ }
  fun removeShake(listener: (T) -> Unit) { /* ... */ }
  fun addCounter(listener: () -> Int) { /* ... */ }
  fun removeCounter(listener: () -> Int) { /* ... */ }
}
```

```C#
using var shaker = new Shaker<string>("pompom");
using IDisposable shake = shaker.AddShake(item => shaken.Add(item)); // Action<string>
using IDisposable counter = shaker.AddCounter(() => 4);              // Func<int>
```

A generic sealed arm that carries the arm's `T` binds the same way; see
[Members of a generic arm](interfaces-abstract-sealed.md#generic-sealed-arm-members). A pair whose
listener is a Kotlin interface (an [interface subscription](#c-implementing-a-kotlin-interface-as-a-parameter))
binds on a generic owner as well.

A shape the vocabulary refuses (four or more parameters, or a `Pair` or `IntArray` payload)
declines the pair, both halves together and named, since a subscription that could not be made
should not have a matching unsubscribe either.

A `Boolean` listener binds like any other primitive, over the same handle wire every other scalar
listener uses:

```kotlin
fun addPurrListener(listener: (Boolean) -> Unit) = purrListeners.add(listener)
fun removePurrListener(listener: (Boolean) -> Unit) = purrListeners.remove(listener)
fun purr(loud: Boolean) = purrListeners.forEach { it(loud) }
```

```C#
using var cat = new Cat("Oreo", 9);
var recorded = new List<bool>();
using IDisposable sub = cat.AddPurrListener(loud => recorded.Add(loud));

cat.Purr(true);
cat.Purr(false); // recorded == [true, false]
```

## C# implementing a Kotlin interface as a parameter {id="c-implementing-a-kotlin-interface-as-a-parameter"}

```kotlin
interface CatEventListener {
  fun onMeow(message: String)
  fun onPurr()
}

fun addListener(listener: CatEventListener) { listeners.add(listener) }
fun removeListener(listener: CatEventListener) { listeners.remove(listener) }
```

```C#
private class RecordingCatListener : ICatEventListener
{
    public List<string> Meows { get; } = new();
    public int Purrs { get; private set; }
    public void OnMeow(string message) => Meows.Add(message);
    public void OnPurr() => Purrs++;
    public void Dispose() { }
}

using var source = new CatEventSource("Oreo");
using IDisposable sub = source.AddListener(new RecordingCatListener());
source.Trigger();
```

An interface used only as an `add`/`remove`-paired subscription parameter binds against the
generated `IFoo` interface, one function pointer per method, and returns `IDisposable` the same way
a stored callback does, including the same `null`/disposed-receiver behavior above. The generated
interface extends `IDisposable`, so every implementation needs a `Dispose()` even when it does
nothing.

Every listener member's parameters must be a non-null primitive other than `Char`, a `String`, an
enum, or an exported class/interface, and every member must return `Unit`. A member outside that
set, one inherited from a super-interface, or one declared more than once (an overload) refuses the
*whole* pair, named on both `add` and `remove`, rather than generating code that fails to build:

```kotlin
interface Watcher { fun onBatch(items: List<Int>) }
class Kennel {
  fun addWatcher(w: Watcher) { /* ... */ }
  fun removeWatcher(w: Watcher) { /* ... */ }
}
```

```
[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping Kennel.addWatcher: the `addWatcher` / `removeWatcher`
subscription pair is not bound: its listener member `Watcher.onBatch(items: List<Int>)` takes
`List<Int>`, which an interface callback cannot carry (it carries a non-null primitive, String,
enum, or exported class/interface). give `Watcher` members only those parameter types (a
collection, a nullable, `Char`, `Any` or an array has no crossing on this route), or split the
member that needs one into a separate listener
    at Kennel.kt:<line>
```

Neither `AddWatcher` nor any wire for `onBatch` is generated; nothing else in `Kennel` is affected.
The same warning names a non-`Unit` return, as `SKIPPED_UNSUPPORTED_RETURN`, and a member inherited
from a super-interface.

### A listener property {id="a-listener-val"}

A listener interface can declare `val` properties beside its methods. Your implementation supplies
each one, and Kotlin reads it from your object every time it uses the property, so a change you
make after subscribing is seen on the next read:

```kotlin
interface PurrListener {
  val name: String
  val sleepy: Boolean
  fun onPurr(volume: Int)
}

class PurrBox {
  fun addPurrListener(listener: PurrListener) { /* ... */ }
  fun removePurrListener(listener: PurrListener) { /* ... */ }
  fun purr(volume: Int) = listeners.filter { !it.sleepy }.forEach { it.onPurr(volume) }
}
```

```C#
private sealed class Purrer(string name, bool sleepy) : IPurrListener
{
    public string Name => name;
    public bool Sleepy => sleepy;
    public void OnPurr(int volume) { /* ... */ }
    public void Dispose() { }
}

using var box = new PurrBox();
using IDisposable sub = box.AddPurrListener(new Purrer("Oreo", sleepy: false));
box.Purr(3); // Kotlin reads Sleepy, then calls OnPurr(3)
```

A property binds when it is a `val` or `var` declared on the listener itself, typed `String`,
`String?`, `Boolean`, `Int`, `Long`, `Float`, `Double`, a non-null enum, or a lambda type (see below).
Kotlin reads the current value on every access, and a write to a `var` lands on your C# property:

```kotlin
interface NameTagListener {
  var tag: String
  fun onRetag()
}
```

```C#
private sealed class RecordingNameTagListener(string tag) : INameTagListener
{
    public string Tag { get; set; } = tag;
    public void OnRetag() { }
    public void Dispose() { }
}

using IDisposable sub = board.AddNameTagListener(new RecordingNameTagListener("Oreo"));
board.RetagAll("cat"); // Kotlin writes "cat:Oreo" to Tag, then reads it back
```

A lambda property is a delegate on your class, nullable and `suspend` included. Kotlin reads it back
through the subscription and calls it, and writes a `var`:

```kotlin
interface LapListener {
  val onLap: (Int) -> String
  var onSplit: ((Int) -> Int)?
}
```

```C#
private sealed class LapCounter : ILapListener
{
    public Func<int, string> OnLap { get; } = count => $"Mylo clocked lap {count}";
    public Func<int, int>? OnSplit { get; set; }
    public void Dispose() { }
}
```

Any other property refuses the whole pair, named on both `add` and `remove`: one inherited from a
super-interface as `SKIPPED_UNSUPPORTED_INPUT`, and any other type (a collection, a nullable
primitive, `Char`, a class) as `SKIPPED_UNSUPPORTED_RETURN`. To bind the pair, declare the property
on the listener itself with a supported type, or pass the value through a member function.

A listener with no members at all (a marker interface) binds too: `AddX(new Quiet())` subscribes
and the returned `IDisposable` unsubscribes, with nothing for Kotlin to call. A listener can also
declare `val name` beside `fun nameGet()`. The generated plumbing keeps the two apart, and your C#
class implements `Name` and `NameGet` as usual.

The pair also refuses, named the same way on both halves, when `Watcher` itself has no C#
declaration at all: nested under an owner that never gets its own nested declaration (an `enum
class` or a generic `interface`; see [Classes and objects: Nested
types](classes-and-objects.md#nested-classes-and-objects)), or declared in a dependency module
outside the plugin's export scope. Move the listener interface to the top level of its file, or
into the exported scope, to bind the pair.

This is one narrow case of a wider capability: a C# class can implement any Kotlin interface,
including at an ordinary parameter, property setter, or extension receiver; see
[Implementing a Kotlin interface in C#](interfaces-abstract-sealed.md#implementing-a-kotlin-interface-in-c).
A throwing member follows the same rule as a per-call lambda, below.

## Exceptions from a callback {id="exceptions-from-a-callback"}

A per-call lambda, a stored callback, or a C#-implemented interface member that throws no longer
takes the whole process down with it. Kotlin sees the throw at its own invocation site and can
catch it there:

```kotlin
fun recoverWith(format: (String) -> String): String =
  try {
    "described ${format("Oreo")}"
  } catch (e: Exception) {
    "recovered ${e::class.simpleName}: ${e.message}"
  }
```

```C#
using var cat = new CallbackFaults();
string report = cat.RecoverWith(_ => throw new InvalidOperationException("no vase left"));
// report == "recovered NugetManagedException: <managed type>: no vase left"
```

Catch `Exception`, not a named type: the runtime class this throws,
`io.github.xxfast.kotlin.native.nuget.runtime.NugetManagedException`, is nameable only from a
per-target source set (`mingwMain`, `posixMain`, ...), not from the shared `nativeMain` file where
you write your callback bodies. Its message is `"<C# exception type>: <message>"`, so a broad catch
can still tell you what actually failed on the other side.

If Kotlin does not catch it, the exception reaches the C# caller of the *outer* call: the original
C# exception, unchanged, when nothing on the Kotlin side rethrew a different one:

```C#
var ex = Assert.Throws<InvalidOperationException>(
    () => cat.DescribeWith(_ => throw new InvalidOperationException("Oreo knocked the vase")));
```

If Kotlin caught the exception and threw its own instead, the C# caller sees that Kotlin exception
(a `KotlinException`, or one of its mapped subtypes, see [Exceptions](exceptions.md)) rather than
the original, so a Kotlin author's own wrapper is never silently replaced.
The same holds if Kotlin catches the callback exception, makes another successful call into C#, and
only then rethrows the original: the C# caller gets the `KotlinException`, not the original object.

An `OperationCanceledException` thrown from a callback cancels the Kotlin coroutine that invoked
it; if that cancellation is never caught, it reaches C# again as the original exception: the same `TaskCanceledException`,
`OperationCanceledException` or subclass object when no other successful C# call ran in between. If
one did, a `TaskCanceledException` or `OperationCanceledException` is rebuilt with the original
message, and a user subclass arrives as `KotlinOperationCanceledException`, which
`catch (OperationCanceledException)` still catches.

A callback that returns `null` for a non-null Kotlin `String`, such as `desk.StampWith(() => null!)`
or a C#-implemented interface member returning `null`, is refused inside the bridge and travels this
same path: Kotlin can catch it, and otherwise the C# caller of the outer call gets an
`ArgumentNullException`.

A callback that throws on a Kotlin coroutine or worker with no `try`/`catch` around the call still
terminates the process, the same as any other uncaught Kotlin exception on that thread: catching
at the Kotlin call site, as above, is what keeps the process alive, not merely the fact that the
exception is now catchable in principle.

Invoking a per-call lambda after the call that supplied it already returned (holding onto it past
its documented lifetime) is an authoring bug, not a supported pattern; Kotlin sees an
`ObjectDisposedException` reported through the same channel, since there is no result left to make
up, rather than a use-after-free read.

<seealso>
    <category ref="related">
        <a href="coroutines-and-flow.md">Coroutines and Flow</a>
        <a href="interfaces-abstract-sealed.md">Interfaces, abstract and sealed classes</a>
        <a href="exceptions.md">Exceptions</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/161-csharp-callback-exception-into-kotlin.md">ADR-161: A C# callback exception reaches Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/171-value-classes-at-erased-generic-positions.md">ADR-171: Value classes at erased generic positions</a>
    </category>
</seealso>
