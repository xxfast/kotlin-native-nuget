# Interfaces, abstract classes, and sealed classes

Kotlin's three flavours of inheritance each get a distinct C# shape: `interface` becomes an
`I`-prefixed C# interface with default methods delegating back to Kotlin, `abstract class` becomes
a C# `abstract class` whose subclasses share one inherited `_handle`, and `sealed class` becomes an
`abstract class` with each subtype reconstructed through a generated discriminator.

| Kotlin | C# | Notes |
|---|---|---|
| `interface` | `interface` (`I`-prefixed) | default methods delegate to Kotlin |
| `abstract class` | `abstract class` | `_handle` inherited by every subclass |
| `sealed class` | `abstract class` | each subtype its own class, nested inside the base or declared beside it, reconstructed through a generated `FromHandle` |
| eligible `sealed interface` (no type parameters; every subclass a `class`/`object`, no other superclass, no sub-interface, no second sealed-interface parent, no `enum class` arm) | `abstract class` | same shape as `sealed class`; no C# interface is declared for it |
| ineligible `sealed interface` | `interface` (`I`-prefixed) | stays on the ordinary interface route; every member typed with it is skipped |

## Interfaces

A default method or property delegates back to Kotlin, so a class that never overrides it still
gets a working implementation:

```kotlin
interface Pet {
  val name: String
  fun speak(): String
  fun greet(): String = "Hi, I'm $name"
}
```

```C#
public interface IPet : IDisposable
{
    string Name { get; }
    string Speak();
    string Greet();
}
```

`Cat : IPet` only declares `Name` and `Speak()`; `Greet()` still works, reached through the
interface's own default body:

```C#
using IPet pet = new Cat("Oreo", 9);
pet.Greet(); // "Hi, I'm Oreo" - the default, never overridden
```

### Interface-typed return values {id="interface-typed-return-values"}

A function or property whose declared type is an interface (`fun closestFriend(): Pet`) returns
`IFoo`/`IFoo?`, backed by a generated wrapper that dispatches every member back into Kotlin. This
works even when the real Kotlin object is anonymous (`object : Pet { ... }`); the consumer only
ever sees the interface, never a concrete type:

```C#
using IPet? friend = oreo.Friend;
if (friend is not null) friend.Speak();
```

Each read returns a **fresh wrapper**. Reading `cat.Friend` twice returns two different C# objects
over the same Kotlin instance, and each disposes independently, so dispose every interface-typed
value you receive.

### Defaulted interface members on implementing classes {id="defaulted-interface-members-on-implementing-classes"}

A class that implements an interface without overriding one of its defaulted members still carries
that member in C#, reached through the Kotlin default body rather than generated separately:

```kotlin
interface Greeter {
  val greeting: String get() = "hello"
  fun greet(): String = "$greeting from a $species"
  val species: String
}

class Parrot(override val species: String) : Greeter
```

```C#
using var parrot = new Parrot("macaw");
parrot.Greeting; // "hello" - Parrot never declared this
parrot.Greet();  // "hello from a macaw"
```

### Widening a read-only property to `var`

Kotlin lets a subclass widen an inherited `val` to `var`. Whether the C# setter survives the
crossing depends on what it widens:

- Widening an **interface**'s `val` always compiles: the implementing class's property renders
  `virtual`, not `override`, and a `virtual` declaration is free to add a setter the interface
  never declared.
- Widening an exported **base class**'s own `val` does not: the base already renders a get-only
  `virtual` property, so a derived `{ get; set; }` `override` of it would fail to compile
  (`CS0546`). The setter is dropped and the property stays read-only in C#; the build emits a
  warning explaining why.

```kotlin
abstract class Animal(override val name: String) : Pet {
  override val vibe: String = "calm" // implements Pet.vibe: renders virtual, get-only
}

class Cat(name: String) : Animal(name) {
  override var vibe: String = "curious" // widens Animal's val to var
}
```

`Cat.Vibe` compiles as a get-only `override string Vibe`; there is no generated `Vibe` setter.

### Implementing a Kotlin interface in C# {id="implementing-a-kotlin-interface-in-c"}

A plain C# class implementing `IFoo`, with no Kotlin `_handle`, can be passed anywhere `IFoo` is
expected: a parameter, a property setter, or an extension receiver (including an extension
**property**'s receiver). Kotlin calls back into it through generated function pointers, so a
Kotlin-side call actually reaches your C# implementation:

```C#
private class Dog : IPet
{
    public string Name { get; }
    public int Legs => 4;
    public string? Nickname { get; }
    public Dog(string name) { Name = name; }
    public string Speak() => "Woof!";
    public string Greet() => $"Hi, I'm {Name} the dog";
    public string Fetch(string item) => $"{Name} fetches the {item}";
    public void Nap() { }
    public void Dispose() { }
}

using IPet dog = new Dog("Rex");
oreo.Befriend(dog);
oreo.ClosestFriend().Speak(); // "Woof!" - Kotlin actually called into `dog`
```

Only a limited set of member shapes can cross this bridge: `val` getters, and methods of arity 0-2
returning `Unit`, a primitive, `Boolean`, an enum, or `String`/`String?`. An interface with a `var`
property, an object- or collection-typed member, a `suspend` member, or a generic member has **no
bridge at all**: passing an implementation of it throws `NotSupportedException`, naming the C#
type, the first time it crosses, not at build time.

#### Lifetime and identity {id="lifetime-and-identity"}

The bridge object's release is **GC-timed, not deterministic**: Kotlin frees it on a later
garbage-collection round, not the moment the C# reference goes out of scope. There is no
`IDisposable`-style prompt release for a C#-implemented interface.

Reading a stored C#-implemented object back from Kotlin, whether through a property, a `suspend
fun` completion, or a `Flow<T>` element, resolves to the **original C# instance**, not a fresh
wrapper:

```C#
oreo.Befriend(dog);
oreo.Friend; // the same `dog` instance, not a new Pet wrapper
```

This identity match is C#-side only. Passing the same C# object into Kotlin twice builds two
separate bridge objects, so Kotlin-side `===` does not treat them as equal, the same way identity
is not preserved across two reads of a **Kotlin-backed** interface property either (see
[Interface-typed return values](#interface-typed-return-values) above).

### An interface reachable only at a parameter position {id="an-interface-reachable-only-at-a-parameter-position"}

An interface that is never returned anywhere, only ever taken as a parameter, a property setter, or
an [extension property receiver](extensions.md#interface-receiver-property), still gets the full
bridge treatment described above:

```kotlin
object Boarding {
  interface Clerk { fun stamp(): String }
  fun fileVia(clerk: Clerk): String = "${clerk.stamp()} filed at boarding"
}
```

```C#
private sealed class DeskClerk : Boarding.IClerk
{
    public string Stamp() => "stamped";
    public void Dispose() { }
}

Boarding.FileVia(new DeskClerk()); // "stamped filed at boarding"
```

### Nested interfaces {id="nested-interfaces-skip-named"}

An interface nested inside a non-generic, non-`inner` `class` or `object` is declared as a real
nested C# interface, `Outer.IListener`, with its return-position wrapper nested beside it
(`Outer.Listener`); see [Classes and objects: Nested types](classes-and-objects.md#nested-classes-and-objects).
A nested interface under a still-deferred owner shape, an `inner class`, a generic class, or an
`enum class`, has no C# spelling: it is skipped, and any member typed with it is skipped too, each
named on a build warning rather than silently dropped.

## Abstract classes

`abstract class` becomes a C# `abstract class`. Every subclass shares one inherited `_handle`
field rather than redeclaring it:

```kotlin
abstract class Animal(override val name: String) : Pet {
  fun introduce(): String = "My name is $name"
}
```

```C#
public abstract class Animal : IPet, IDisposable, INugetHandle
{
    internal IntPtr _handle;
    public string Name { get { /* ... */ } }
    public string Introduce() { /* ... */ }
    public abstract string Speak();
    public abstract void Dispose();
}
```

`Cat : Animal` only declares what it overrides (`Speak()`, `Dispose()`); it never redeclares
`_handle`.

An `open val`/`open var`/`open fun` the base declares itself renders `public virtual`, so a
subclass `override` of it compiles; a member that stays the Kotlin default (final) carries no
modifier and cannot be overridden in C# either. Writing a member through the *base* static type
still reaches the subclass's own Kotlin dispatch, not just the C# facade:

```C#
Bed bed = new Hammock();
bed.Occupant = "Oreo"; // dispatches through Hammock's own override
```

### A base class's own `abstract fun` {id="a-base-class-s-own-abstract-fun"}

An exported base class's own **unimplemented** `abstract val`/`abstract var`/`abstract fun`, one
declared with no body, renders `public abstract` in C# too, so a subclass `override` compiles
instead of failing to build:

```kotlin
abstract class Vehicle(val plate: String) {
  abstract fun honk(): String
  fun describe(): String = "$plate says ${honk()}"
}

class Truck(plate: String) : Vehicle(plate) {
  override fun honk(): String = "HONK"
}
```

```C#
public abstract class Vehicle : IDisposable, INugetHandle
{
    public string Plate { get { /* ... */ } }
    public string Describe() { /* ... */ }
    public abstract string Honk();
}
```

`truck.Describe()` still calls Kotlin's own `honk()` dispatch, so reading it through a
`Vehicle`-typed reference proves the override reaches the real Kotlin object, not just the C#
wrapper.

The same treatment applies when the abstract member is **inherited from an interface** and never
implemented on the base itself, even when that interface is never declared in C# at all (an
unexported interface): the abstract class still declares the inherited member `public abstract`,
so a further C# subclass compiles.

A member the planner declines to plan, for example a generic interface default the type mapper
cannot spell abstractly, is dropped from the generated class instead of rendered `abstract`; an
uncompilable abstract member would break every further subclass.

## Sealed classes and interfaces

`sealed class` becomes a C# `abstract class`, with each subtype its own class and a generated
`FromHandle` reading a type tag off the native handle to reconstruct the right one:

```kotlin
sealed class Observation {
  data object Superposition : Observation()
  data class Alive(val cat: Cat) : Observation()
  data class Dead(val cause: String) : Observation()
}
```

```C#
using Observation result = ObservationKt.OpenBox("Oreo");

string message = result switch
{
    Observation.Superposition => "Unknown - cat is in superposition",
    Observation.Alive a => $"Alive: {a.Cat!.Name}",
    Observation.Dead d => $"Dead: {d.Cause}",
    _ => throw new InvalidOperationException(),
};
```

A subclass declared *inside* the sealed base stays nested (`Observation.Alive`); one declared
*beside* the base in the same file is still discriminated by `FromHandle`, but is declared at
namespace level instead (`public sealed class Label : FlatShape`, not `FlatShape.Label`): match on
the bare name in a `switch`, not the nested spelling.

Every arm has only an `internal` handle constructor: a consumer can never `new` one directly, only
receive one from Kotlin or a pattern match. On .NET 7+, `IntPtr` is `nint`, and an `int` converts
to it implicitly, so `new Purr.On(9)` **compiles** and binds to that internal constructor, treating
`9` as a native address; the first call through it access-violates instead of failing to build.
Never construct a sealed arm directly.

A `data class`/`data object` arm gets `Equals`/`GetHashCode`/`ToString` the same as any
[data class](data-classes.md); compare two reads of the same singleton arm with `Equals`, not
`ReferenceEquals`.

### Sealed interfaces {id="sealed-interfaces"}

A `sealed interface` maps the same way exactly when it is **eligible**: no type parameters, and
every subclass is a `class`/`object`, nested in the interface or declared beside it, with no other
superclass, no sub-interface, no second sealed-interface parent, and no `enum class` arm. An
eligible sealed interface renders as an `abstract class`, exactly like `sealed class` above; no
`IFoo` interface is ever declared for it:

```kotlin
sealed interface Pulse {
  data class Beat(val bpm: Int) : Pulse
  data object Flat : Pulse
}
```

```C#
using Pulse current = monitor.Current;
Assert.IsType<Pulse.Flat>(current);
```

An **ineligible** sealed interface, one whose arm extends another class, implements a second sealed
interface, or is declared as an `enum class`, stays on the ordinary interface route instead: it
keeps its plain `IFoo` declaration, and every function, property, or parameter typed with it is
skipped, named on a build warning. There is no partial binding for an ineligible sealed interface:
make every arm a plain `class`/`object` with no other superclass, or use a `sealed class` instead.

### Sealed types as property types {id="sealed-types-as-property-types"}

A property whose type is a sealed class or an eligible sealed interface binds as the sealed
**base**, materialized through `FromHandle` (see `Monitor.Current` above). This covers the bare
type, a nullable sealed type, and a `List`/`Map`/`Set` component, read-only or `var`; a mutable
collection of a sealed type gets a real setter, not just a getter. Because the getter always hands
back a genuine subclass instance, pattern matching works immediately with no extra cast:

```C#
Issue54Shape shape = drawing.Shape;

string description = shape switch
{
    Issue54Shape.Circle c => $"Oreo curled at r={c.Radius}",
    Issue54Shape.Empty => "Mylo sprawled",
    _ => throw new InvalidOperationException(),
};
```

An extension function's or property's receiver typed as a sealed base binds too; see
[Extensions: Sealed receivers](extensions.md#sealed-receivers). A value class whose underlying type
is sealed binds the same way; see [Value classes: Over a sealed type](value-classes.md#over-a-sealed-type).

### A sealed type at a parameter position {id="a-sealed-type-at-a-parameter-position"}

A sealed type at a parameter position, bare, nullable, or a collection component, including a
constructor parameter, binds as an ordinary handle argument:

```kotlin
data object Silence : Transmission
data class Ping(val ms: Int, val label: String) : Transmission
data class Packet(val signal: Transmission)

class Radio {
  fun heard(signal: Transmission): Int = when (signal) {
    is Ping -> signal.ms
    Silence -> 0
  }
}
```

```C#
using var radio = new Radio();
using var packet = new Packet(radio.History[0]);

using Transmission carried = packet.Signal;
Assert.Equal(60, Assert.IsType<Ping>(carried).Ms);
```

A consumer cannot construct a sealed subclass directly, so every argument here comes from an
existing getter or return value, never `new`.

### A nested sealed subclass at a member position {id="a-nested-sealed-subclass-at-a-member-position"}

A property, method return, or parameter typed with a sealed subclass that is nested inside its base
keeps the base in its C# name, `NestedShape.Circle`, not the bare `Circle`:

```kotlin
sealed class NestedShape {
  data class Circle(val radius: Double) : NestedShape()
}

class NestedShapeFactory {
  fun circle(radius: Double): NestedShape.Circle = NestedShape.Circle(radius)
}
```

```C#
NestedShape.Circle unit = factory.Circle(1.0);
```

A `suspend fun` returning a nested arm spells the same way: `Task<Job.Running>`, not
`Task<Running>`.

### Members of a sealed arm

A public method or property a sealed subclass **itself declares**, including its own `override`,
binds the same as it would on an ordinary class: overloads, default-argument omitting overloads,
nullable types, collections, and `Duration`/`Uuid`/value-class/interface shapes all work on an arm
too, exported under the arm's own name. This is **declared-only**: an arm never re-exports a member
it merely inherits unchanged.

The sealed base's own declared `abstract`/`open` member is a different carrier: it renders `public
virtual` on the C# **base** itself (never `abstract`), so a consumer holding the sealed base can
read it without pattern-matching to an arm first:

```kotlin
sealed class Job {
  open val kind: String = "job" // renders `public virtual string Kind` on Job itself

  data class Running(val progress: Int) : Job() {
    override val kind: String = "running" // renders `override` on Running
  }

  data object Idle : Job() // inherits Job's Kind unchanged; declares nothing of its own
}
```

```C#
using Job job = JobSample.AnyJob(40);
job.Kind; // "running" - dispatches to the concrete arm's own override, or the base's default
```

#### Suspend methods on a sealed arm {id="sealed-method-suspend-generated-c"}

A `suspend fun` an arm declares binds like an ordinary class's suspend method, as `Task<T>
XxxAsync(...)` under the arm's own name. An arm that declares a `suspend` member also implements
`IAsyncDisposable`, draining its own coroutine scope before disposing the handle; an arm with **no**
suspend member stays `IDisposable` only. Because this is per-arm, a consumer holding the sealed base
has to pattern-match to the concrete arm before `await using`/`DisposeAsync()`:

```C#
Job job = JobSample.AnyJob(40);
if (job is Job.Running running) await running.DisposeAsync();
else job.Dispose();
```

The sealed **base**'s own `open suspend fun` has no carrier on the base at all, unlike an ordinary
`open`/`abstract` member: it is absent everywhere unless an arm overrides it and declares its own.

#### A `suspend fun` returning the sealed base {id="sealed-method-suspend-base-generated-c"}

A `suspend fun` returning the sealed base itself, rather than a specific arm, binds as `Task<Job>`
(or `Task<Job?>`) and completes through the same `FromHandle` discriminator a synchronous return
uses:

```kotlin
class JobFactory {
  suspend fun nextLater(progress: Int): Job = when {
    progress < 0 -> Job.Idle
    progress < 100 -> Job.Running(progress)
    else -> Job.Done(progress)
  }
}
```

```C#
using Job result = await factory.NextLaterAsync(7);
Assert.Equal(7, Assert.IsType<Job.Running>(result).Progress);
```

#### Flow and StateFlow members on a sealed arm {id="sealed-flow-generated-c"}

A `Flow<T>`/`StateFlow<T>` an arm declares, at a property or a method return, binds the same shape
an ordinary class's flow member does, see [Coroutines and Flow](coroutines-and-flow.md), under the
arm's own name. As with `suspend`, only an arm that declares a flow member gets
`IAsyncDisposable`; the sealed base's own `open` flow member has no base carrier. An arm that
already has a `suspend` member and gains a flow member too shares the **same** scope and
`DisposeAsync`, not two.

#### Lambda parameters on a sealed arm {id="sealed-lambda-generated-c"}

A method taking a function parameter (`(String) -> String`) an arm declares binds the same shape
an ordinary class's callback method does, see [Lambdas and callbacks](lambdas-and-callbacks.md),
under the arm's own name:

```kotlin
data class Running(val progress: Int) : Job() {
  fun relabel(transform: (String) -> String): String = transform("running-$progress")
}
```

```C#
oreo.Relabel(s => s.ToUpper()); // "RUNNING-9"
```

#### Stored-callback and interface-bridge pairs on a sealed arm {id="sealed-callback-pair-generated-c"}

An `addX`/`removeX` stored-callback or interface-bridge pair an arm declares returns the same
`IDisposable` subscription an ordinary class's pair does, see
[Lambdas and callbacks](lambdas-and-callbacks.md), under the arm's own name:

```C#
using IDisposable sub = oreo.AddTicker(s => ticks.Add(s));
oreo.Tick();
```

A `data object` arm's subscriber list is process-wide, since there is only one instance, so a
subscription on it must be disposed by whoever added it and can outlive any single caller.

#### An override of a base member the base declined to plan {id="sealed-method-declined-base-overload"}

If the base's own member is behind an opt-in marker and its plan skips it structurally, the base
declares nothing for it at all, not even `virtual`. An arm that overrides it anyway still binds,
but its override renders as a plain `public` method rather than `override`, since there is nothing
on the base to relate to.

### Open arms and further nesting

A sealed arm declared `open` renders `public class` instead of `public sealed class`, with its own
`open` members `virtual`, so a further Kotlin subclass of it compiles. That further subclass takes
the ordinary class route, spelled through the arm's nested name (`Roost.HighPerch` extends
`Roost.Perch`). The `FromHandle` discriminator only distinguishes direct arms: a handle for a
further subclass still reconstructs as the arm itself (`Roost.Perch`), never the deeper subclass.
The underlying Kotlin object is correct and dispatch through the wrapper still reaches the
subclass's own overrides, but a consumer cannot pattern-match past the arm.

A sealed base, a sealed arm, and any `interface` owner can nest their own plain
`class`/`object`/`interface`/`enum class`/`value class`, declared beside the owner's other members
(`Purr.Detail`, `Purr.On.Trace`, `Beam.Lens`); see
[Classes and objects: Nested types](classes-and-objects.md#nested-classes-and-objects). Only an
`inner class`, a generic class, or an `enum class` owner still cannot host a nested declaration.

## Limitations {id="limitations"}

- A C#-implemented interface's bridge only supports `val` getters and arity 0-2 methods returning
  `Unit`, a primitive, `Boolean`, an enum, or `String`/`String?`. Anything else (a `var` property,
  an object- or collection-typed member, `suspend`, generics) throws `NotSupportedException` the
  first time an implementation is passed, naming the C# type, with nothing at build time naming
  which member disqualified it.
- A C#-implemented object's bridge is released on Kotlin's next garbage-collection round, not
  deterministically; there is no `IDisposable`-style prompt release for it.
- An interface member whose own return type is another interface or a class handle (chained
  resolution) is not supported. Suspend interface members, `Flow`/`StateFlow`-valued interface
  members, and generic interface type parameters at a return position are not supported either.
- Object identity is not preserved across two reads of a **Kotlin-backed** interface property: each
  read is a distinct C# wrapper over the same Kotlin object. A stored **C#-implemented** object is
  the exception: it always resolves back to the original instance.
- An ineligible sealed interface, an arm with another superclass, an `enum class` arm, or an arm
  implementing a second sealed interface, has no binding at all: every function, property, or
  parameter typed with it is skipped.
- An eligible sealed interface arm's own extra interfaces (`class Odd : Kind, CharSequence`) are
  dropped silently from the generated class.
- A sealed arm's only constructor is `internal`; an `int`-convertible literal implicitly converts
  to `IntPtr` (`nint` on .NET 7+), so `new Base.Arm(9)` compiles and access-violates at runtime
  instead of failing to build. Never construct a sealed arm directly.
- `interface Derived : Base` does not carry `Base`'s members onto `IDerived`; a `var` interface
  property always renders `{ get; }` only on the generated interface, even when an implementing
  class's own property has a setter.
- The sealed base's own `open suspend fun` and any `Flow`/`StateFlow` member it declares have no
  carrier on the C# base at all: only an arm that itself declares one binds, and only that arm
  gains `IAsyncDisposable`. A consumer holding the sealed base must pattern-match to the concrete
  arm before an async call or `await using`.
- A generic method or a `suspend` lambda parameter on a sealed arm has no binding, the same as on
  an ordinary class.

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="data-classes.md">Data classes</a>
        <a href="lambdas-and-callbacks.md">Lambdas and callbacks</a>
        <a href="coroutines-and-flow.md">Coroutines and Flow</a>
    </category>
</seealso>
