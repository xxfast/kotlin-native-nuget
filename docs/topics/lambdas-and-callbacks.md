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
property once into a `using var` rather than calling `cat.OnPet.Invoke(...)` inline, which
leaves the handle undisposed until the GC finalizes it.

`Invoke` accepts and returns `string`, any narrow or wide primitive (including `char`), any
exported object, a [value class](value-classes.md#at-an-erased-generic-position), or a nullable
spelling of any of those (`string?`, `int?`, `char?`, and so on): a lambda's type argument carries
`?` when the Kotlin declaration is nullable at that position, and `null` reaches the Kotlin lambda,
or comes back from it, exactly as written. A value class payload or result crosses boxed, not by
its underlying, so its own `init` still validates; a value class with no crossing at this position
(a nullable underlying, a generic value class, or an ineligible sealed interface) is refused by
name instead.

An exported **interface** type argument (`KotlinFunc<IPet, IPet>`) is spelled with the interface
itself, never the ADR-040 backing wrapper, and passing your own C# implementation to `Invoke`
returns that same instance; a Kotlin-backed value still materializes through the interface, see
[An interface at an erased position](generics.md#an-interface-at-an-erased-position). If you
explicitly named the old wrapper type (`KotlinFunc<Pet, Pet>`) before, that no longer compiles;
`var` and passing the result straight on are unaffected.

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

### A top-level function that returns a lambda {id="a-top-level-function-that-returns-a-lambda"}

A top-level function may return a lambda and take ordinary parameters alongside it: an interface,
an exported class, an enum, a nullable, or a collection. The returned lambda captures them, and you
call it later.

```kotlin
fun petSupplier(pet: Pet): () -> Pet = { pet }
fun adder(n: Int): (Int) -> Int = { it + n }
```

```C#
public static KotlinFunc<IPet> PetSupplier(IPet pet)
```

```C#
using IPet rex = new Dog("Rex"); // your own C# implementation of IPet
using KotlinFunc<IPet> supplier = PetRelayKt.PetSupplier(rex);
IPet same = supplier.Invoke(); // the same instance as rex

using KotlinFunc<int, int> twoMore = PetRelayKt.Adder(2);
int five = twoMore.Invoke(3);
```

Dispose the returned `KotlinFunc`, as for a lambda property above. A C#-implemented interface you
pass stays alive for as long as the lambda does, so `rex` above remains valid after the call
returns; a `null` for a nullable parameter reaches Kotlin as `null`.

The lambda's type arguments follow the rules above. One Kotlin cannot have C# spell, such as
`() -> List<Int>`, skips the function with a named `SKIPPED_UNSUPPORTED_RETURN`. A `suspend` lambda
return (`suspend () -> Int`) and a lambda returned from a class, object or companion member are named
skips too; expose a lambda property on the class instead.

### Which lambda properties bind {id="which-lambda-properties-bind"}

A lambda property binds on an ordinary class and on a sealed arm. A lambda declared on a sealed base
binds on every arm, not on the base type. Every other owner names the property in a
`SKIPPED_UNSUPPORTED_PROPERTY` warning and generates no C# member: an interface, an `object`, a
companion, a top-level property and a generic class. A `suspend` lambda property binds on an
ordinary class only; on a sealed arm or base, or an interface, it is named the same way.

The binding is read-only and non-null:

```kotlin
var onPurr: (Int) -> Unit = {}
val onWake: (() -> Unit)? = null
```

`OnPurr` is a get-only `KotlinAction<int>`, and its setter is named once as
`SKIPPED_UNSUPPORTED_INPUT`. `onWake` is not generated at all: the warning says only the non-null
form binds, so declare it non-null with a no-op lambda in place of `null`.

### A lambda's type arguments across a namespace boundary {id="type-arguments-across-a-namespace-boundary"}

A lambda property's type arguments are qualified with their full namespace automatically, so a
property whose parameter or result type lives outside the declaring class's namespace still
compiles without you spelling it out:

```C#
public KotlinFunc<global::TestLibrary.Catcam.Lens.CamId, global::TestLibrary.Catcam.Lens.Snapshot> OnPick => ...
```

If a type argument has no C# spelling at all, for example a lambda returning `Flow<T>`, the
property is dropped from the generated class instead of emitting code that won't compile. If a
lambda-typed property you expect is missing, check the build's warnings, and the owner and
nullability rules above.

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

A few shapes are not supported and are refused by name (a build-time skip, not broken generated
code): `Char` as either the lambda's payload or its own return; a payload that is a Kotlin builtin
non-scalar (`List`, `Set`, `Map`, `Any`, `Pair`, an array, `Duration`, or anything else under
`kotlin`/`kotlinx` outside a primitive, `String`, `Char` or a `Throwable`, which binds as an unthrown
`Exception`, see [Throwable values](exceptions.md#throwable-values)); a lambda's *own* return
outside `Unit`, a primitive, `String` or a `Throwable`, `Exception` or `RuntimeException` (an
object, an enum, `Char`, or one of those same builtins); a suspend
lambda (`suspend (T) -> R`); a lambda type nested inside a `List`/`Set`/`Map`; a class or object member
that returns a lambda rather than taking one (a [top-level function](#a-top-level-function-that-returns-a-lambda) can; expose a
[lambda property](#kotlin-c-lambda-properties-and-returns) on the class instead); and a lambda parameter
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
from a super-interface.

### A listener `val` {id="a-listener-val"}

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

A property binds when it is a `val` declared on the listener itself, typed `String`, `String?`,
`Boolean`, `Int`, `Long`, `Float`, `Double`, or a non-null enum. Any other property refuses the
whole pair, named on both `add` and `remove`: a `var` or a property inherited from a super-interface
as `SKIPPED_UNSUPPORTED_INPUT`, and any other type (a collection, a nullable primitive, `Char`, a
class) as `SKIPPED_UNSUPPORTED_RETURN`. To bind the pair, declare the property `val` on the
listener, or pass the new value through a member function.

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
