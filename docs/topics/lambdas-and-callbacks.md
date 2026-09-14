# Lambdas and callbacks

A lambda crosses the bridge in one of three shapes, depending on who owns it and how long it
lives: a Kotlin lambda exposed to C# as a disposable handle, a C# lambda Kotlin invokes once per
call and does not keep, or a C# lambda Kotlin stores as an observer until you dispose it. A fourth
shape lets a C# class implement a Kotlin interface and register it as such an observer.

## Kotlin → C#: lambda properties and returns

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

Calling `Invoke` with an argument accepts `string`, `int`, `long`, `float`, `double`, `bool`, or any
exported object; an `sbyte`, `short`, `char`, `uint`, or a Kotlin value class that maps to a C#
`record struct` throws `NotSupportedException` at runtime rather than failing to build.

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
```

```C#
using var cat = new Cat("Oreo", 9);
string described = cat.DescribeWith(name => $"This cat is called {name}");

int minLength = 6;
IReadOnlyList<string> matching = cat.NicknamesMatching(n => n.Length >= minLength);
```

Pass an ordinary `Func<>`/`Action<>`, including a capturing lambda or a method group, arity 0 and
up. Kotlin invokes it once per call and does not retain a reference afterwards; do not rely on a
delegate passed here firing again later (use a stored callback, below, for that). A `kotlin.*`
primitive parameter (`Int`, `Boolean`, `Byte`, `Double`, ...) crosses by value, not through a
handle, so the callback body receives it directly with nothing to dispose. A method with a per-call
lambda parameter must itself return `Unit` or a marshalled type such as `String`, as `DescribeWith`
and `NicknamesMatching` do above; one whose own return is a raw primitive such as `Int` fails
packaging with a forward ABI mismatch instead of generating. `Flow<T>` and a suspend lambda
(`suspend (T) -> R`) are not supported as a parameter type at all.

An exception thrown inside the lambda body is not caught and turned into a Kotlin exception: it
terminates the process (`Environment.FailFast`). Catch inside the lambda if you need to keep the
process alive.

The same route also binds a method declared on a sealed arm; see
[Lambda parameters on a sealed arm](interfaces-abstract-sealed.md#sealed-lambda-generated-c).

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
directly. Disposing the subscription while Kotlin may still be mid-invocation on another thread
is not safe and can terminate the process; only dispose once you know no callback is in flight.

The same pattern also binds a pair declared on a sealed arm; see
[Stored-callback and interface-bridge pairs on a sealed arm](interfaces-abstract-sealed.md#sealed-callback-pair-generated-c).

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
a stored callback does. The generated interface extends `IDisposable`, so every implementation
needs a `Dispose()` even when it does nothing. Every member of such an interface must return `Unit`
and the interface must declare no properties; violating either rule fails the build with no
diagnostic pointing at the cause.

This is one narrow case of a wider capability: a C# class can implement any Kotlin interface,
including at an ordinary parameter, property setter, or extension receiver; see
[Implementing a Kotlin interface in C#](interfaces-abstract-sealed.md#implementing-a-kotlin-interface-in-c).
Exception behavior for an interface method is the same fail-fast rule as a per-call lambda, above.

<seealso>
    <category ref="related">
        <a href="coroutines-and-flow.md">Coroutines and Flow</a>
        <a href="interfaces-abstract-sealed.md">Interfaces, abstract and sealed classes</a>
    </category>
</seealso>
