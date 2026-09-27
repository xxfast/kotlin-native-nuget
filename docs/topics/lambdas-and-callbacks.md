# Lambdas and callbacks

A lambda crosses the bridge in one of three shapes, depending on who owns it and how long it
lives: a Kotlin lambda exposed to C# as a disposable handle, a C# lambda Kotlin invokes once per
call and does not keep, or a C# lambda Kotlin stores as an observer until you dispose it. A fourth
shape lets a C# class implement a Kotlin interface and register it as such an observer.

## Kotlin → C#: lambda properties and returns {id="kotlin-c-lambda-properties-and-returns"}

```kotlin
val onMeow: () -> String = { "Meow! My name is $name" }
val onPet: (String) -> String = { action -> "$name $action contentedly" }
val onNap: () -> Unit = { naps++ }
```

```C#
using var cat = new Cat("Oreo", 9);
using var onPet = cat.OnPet;
string result = onPet.Invoke("purrs"); // "Oreo purrs contentedly"

using var onNap = cat.OnNap;
onNap.Invoke();
```

`OnPet` and `OnMeow` are `KotlinFunc<...>`, a disposable handle wrapping the Kotlin lambda; `Invoke`
calls into Kotlin and marshals the result back. A lambda returning `Unit` binds as `KotlinAction`
instead of `KotlinFunc<void>`, since `void` cannot be a C# type argument; `Invoke` returns `void`
and disposal works the same way. Each property access mints a new native handle, so read the
property once into a `using var` rather than calling `cat.OnPet.Invoke(...)` inline, which leaks
the handle.

`Invoke` accepts and returns `string`, any narrow or wide primitive (including `char`), any
exported object, a [value class](value-classes.md#at-an-erased-generic-position), or a nullable
spelling of any of those (`string?`, `int?`, `char?`, and so on): a lambda's type argument carries
`?` when the Kotlin declaration is nullable at that position, and `null` reaches the Kotlin lambda,
or comes back from it, exactly as written. A value class payload or result crosses boxed, not by
its underlying, so its own `init` still validates; a value class with no crossing at this position
(a nullable underlying, a generic value class, or an ineligible sealed interface) is refused by
name instead.

```kotlin
fun signIn(): (String?) -> Unit = { seen = it }
fun describer(): (Int?) -> String = { if (it == null) "none" else "n=$it" }
```

```C#
using KotlinAction<string?> record = Recorder.SignIn();
record.Invoke(null);

using KotlinFunc<int?, string> describe = Recorder.Describer();
string result = describe.Invoke(null); // "none"
```

### A lambda's type arguments across a namespace boundary {id="type-arguments-across-a-namespace-boundary"}

A lambda property's type arguments are qualified with their full namespace automatically, so a
property whose parameter or result type lives outside the declaring class's namespace still
compiles without you spelling it out:

```C#
public KotlinFunc<global::TestLibrary.Catcam.Lens.CamId, global::TestLibrary.Catcam.Lens.Snapshot> OnPick => ...
```

If a type argument has no C# spelling at all, for example a lambda returning `Flow<T>`, the
property is dropped from the generated class instead of emitting code that won't compile. If a
lambda-typed property you expect is missing, check for this rather than a build error naming it.

## C# → Kotlin: per-call lambda parameters

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
`String` value the lambda itself returns. The member's own return can be a scalar, `String`, an
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

A few shapes are not supported and are refused by name (a build-time skip, not broken generated
code): `Char` as either the lambda's payload or its own return; a payload that is a Kotlin builtin
non-scalar (`List`, `Set`, `Map`, `Any`, `Pair`, an array, `Duration`, or anything else under
`kotlin`/`kotlinx` outside a primitive, `String` or `Char`); a lambda's *own* return outside `Unit`,
a primitive or `String` (an object, an enum, `Char`, or one of those same builtins); a suspend
lambda (`suspend (T) -> R`); a lambda type nested inside a `List`/`Set`/`Map`; a callback at a
*result* position (a member returning a lambda rather than taking one; return a
[Kotlin lambda property](#kotlin-c-lambda-properties-and-returns) instead); and a lambda parameter
on a constructor, a data class's `copy()`, an enum-arm box constructor, or a value-class member,
since none of those can keep the callback registered past the single call that creates them.

```kotlin
fun onBatch(cb: (List<Int>) -> Unit) = cb(listOf(1))
fun makeCat(cb: () -> Cat) { cb() }
```

```
[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping Walker.onBatch: a callback parameter can carry a
primitive/String/Char, a class handle or an enum, but not `cb: (List<Int>) -> Unit`, whose payload
`List<Int>` is a Kotlin builtin with no crossing on a callback
    at Walker.kt:<line>

[nuget:SKIPPED_UNSUPPORTED_RETURN] Skipping Walker.makeCat: a callback can return `Unit`, a
primitive or a `String`, but `cb: () -> Cat` returns `Cat`
    at Walker.kt:<line>
```

Neither member is generated on either half, Kotlin included, so nothing calls a delegate that
doesn't exist on the C# side. Pass the collection one element at a time, or wrap it in an exported
class and pass that; hand an object or enum result back through a method on the class instead of a
lambda return.

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

A parameter whose *payload* is nullable (`listener: (Int?) -> Unit`) or whose lambda *returns* a
nullable value has no generated member at all: it is a named skip, because the payload cannot cross
this route today. Change the lambda's own type to carry the "no listener" case instead, as above,
or wrap the payload in a non-nullable type before it crosses. The same skip applies on a sealed arm
and to both halves of a stored-callback `add`/`remove` pair, which go together.

## Ownership of a callback payload {id="ownership-of-a-callback-payload"}

A value handed to your callback from any of the C# → Kotlin routes on this page is owned by C#, not
Kotlin, once it crosses:

- a `string` (or other marshalled value) arrives already fully read; there is nothing to dispose.
- an exported object arrives as a live wrapper you own, the same as any other object you construct
  or receive. Dispose it yourself:

```C#
cat.ForEachToy(toy =>
{
    using var t = toy;
    toyNames.Add(t.Name);
});
```

A generated wrapper has `Dispose()` and no finalizer, so an object payload your callback never
disposes leaks for the life of the process; `NugetMarshal.LiveHandles` can help diagnose that. A
primitive payload never had a handle, so there is nothing to own.

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

## C# → Kotlin: stored callbacks

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
``` `Dispose()` does not wait for an invocation already in flight on another thread: a call
that lands after `Dispose()` is dropped silently for a `void` listener, without running your code
or crashing. If that dropped call carried a handle-passed payload (an exported object), the payload
handle it minted is never released, since the code that would have read and disposed it never
runs; this is a known, unfixed leak, not something to work around on your side. A per-call lambda
kept past the single call that supplied it and invoked later is a different case, see
[below](#exceptions-from-a-callback).

The same pattern also binds a pair declared on a sealed arm; see
[Stored-callback and interface-bridge pairs on a sealed arm](interfaces-abstract-sealed.md#sealed-callback-pair-generated-c).

A stored callback whose payload is nullable (`listener: (Mood?) -> Unit`), or a Kotlin builtin
non-scalar (`List`, `Map`, `Set`, `Any`, `Pair`, an array, `Duration`), has no generated member:
both the `add` and `remove` half are declined together, since a subscription that could not be made
should not have a matching unsubscribe either. A listener must also return `Unit`: Kotlin calls it
back as a bare `Action`, so a listener returning anything else drops both halves the same way,
named `SKIPPED_UNSUPPORTED_RETURN`:

```kotlin
fun addCounter(listener: () -> Int) {}
fun removeCounter(listener: () -> Int) {}
```

```
[nuget:SKIPPED_UNSUPPORTED_RETURN] Skipping Walker.addCounter: the `addCounter` / `removeCounter`
stored-callback pair is not bound: its listener `listener: () -> Int` returns `Int`, and a stored
listener is called back as an `Action`, which returns nothing
    at Walker.kt:<line>
```

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
from a super-interface. A listener interface that declares a property, rather than only methods,
still fails the build with no diagnostic pointing at the cause; keep the listener method-only.

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

An `OperationCanceledException` thrown from a callback cancels the Kotlin coroutine that invoked
it; if that cancellation is never caught, it reaches C# again as `KotlinType ==
"kotlin.coroutines.cancellation.CancellationException"`, not the original .NET cancellation type.

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
