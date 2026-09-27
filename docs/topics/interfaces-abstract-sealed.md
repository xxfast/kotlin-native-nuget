# Interfaces, abstract classes, and sealed classes

Kotlin's three flavours of inheritance each get a distinct C# shape: `interface` becomes an
`I`-prefixed C# interface with default methods delegating back to Kotlin, `abstract class` becomes
a C# `abstract class` whose subclasses share one inherited `_handle`, and `sealed class` becomes an
`abstract class` with each subtype reconstructed through a generated discriminator.

| Kotlin | C# | Notes |
|---|---|---|
| `interface` | `interface` (`I`-prefixed) | default methods delegate to Kotlin; a super-interface's members are inherited, not redeclared |
| `abstract class` | `abstract class` | `_handle` inherited by every subclass |
| `sealed class` | `abstract class` | each subtype its own class, nested inside the base or declared beside it, reconstructed through a generated `FromHandle` |
| eligible `sealed interface` (no type parameters; every subclass a `class`/`object` or `enum class`, no other superclass, no sub-interface, no second sealed-interface parent) | `abstract class` | same shape as `sealed class`; no C# interface is declared for it; an `enum class` arm binds as a boxed `{Enum}Arm` |
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

### Widening a read-only property to `var` {id="widening-a-read-only-property-to-var"}

Kotlin lets a subclass widen an inherited `val` to `var`. Whether the C# setter survives the
crossing depends on what it widens:

- Widening an **interface**'s `val` always compiles: the implementing class's property renders
  `virtual`, not `override`, and a `virtual` declaration is free to add a setter the interface
  never declared.
- Widening an exported **base class**'s own `val`, or a `var` whose own setter is narrower than
  public (`private set`, `protected set`, `internal set`, see [Classes and objects: A setter
  narrower than public binds get-only](classes-and-objects.md#a-setter-narrower-than-public-binds-get-only)),
  does not: the base already renders a get-only `virtual` property, so a derived
  `{ get; set; }` `override` of it would fail to compile (`CS0546`). The public property stays
  get-only and the build emits a warning explaining why. If the same property also widens an
  exported **interface**'s `var` (below), the setter is not lost: it is still reachable, just not
  through the public property.

```kotlin
abstract class Animal(override val name: String) : Pet {
  override val vibe: String = "calm" // implements Pet.vibe: renders virtual, get-only
}

class Cat(name: String) : Animal(name) {
  override var vibe: String = "curious" // widens Animal's val to var
}
```

`Cat.Vibe` compiles as a get-only `override string Vibe`; there is no generated `Vibe` setter.

### An interface's own `var` property {id="an-interface-s-own-var-property"}

A `var` on an exported interface renders `{ get; set; }` on the generated interface, and any class
that implements it, Kotlin- or C#-side, must supply a setter:

```kotlin
interface Tally {
  var count: Int
  var lastSlip: Throwable?
}
```

```C#
public interface ITally : IDisposable
{
    int Count { get; set; }
    Exception? LastSlip { get; } // ADR-107: C# cannot construct a Kotlin Throwable, stays read-only
}
```

A setter that cannot plan for a type-level reason (`Throwable?` above) stays `{ get; }` on the
interface too, named in the build log and on the member itself, whether or not any class ever
implements the interface.

One shape needs a second render, because it would otherwise fail to compile: a class that overrides
both an exported base class's `open val` and an exported interface's `var` with a single Kotlin
`override var`. The public property can't gain a setter (the [widening rule above](#widening-a-read-only-property-to-var)
still applies), so C# can't satisfy `ITally.Count { get; set; }` through it; the setter is instead
implemented **explicitly**, reachable only by declaring the reference as the interface:

```kotlin
open class Scoreboard { open val count: Int = 0 }

class TrainingClicker : Scoreboard(), Tally {
  override var count: Int = 0
  override var lastSlip: Throwable? = null
}
```

```C#
public class TrainingClicker : Scoreboard, ITally
{
    public override int Count { get { /* ... */ } } // stays get-only: CS0546 forbids a setter here

    int ITally.Count // reaches the same setter export the public property couldn't carry
    {
        get => Count;
        set { /* ... */ }
    }

    public override global::System.Exception? LastSlip { get { /* ... */ } } // no explicit member: ADR-107 refused this setter entirely
}
```

```C#
ITally tally = clicker;
tally.Count = 5;             // reaches Kotlin through the explicit member
clicker.Count;                // 5 - the public getter agrees
clicker.Count = 5;            // does not compile: TrainingClicker.Count has no public setter
```

An ordinary implementer with no read-only base in the way (`class Abacus : Tally`) needs none of
this: its own public setter satisfies `ITally` directly, the same as any other interface member. A
sealed subclass never gets the explicit form: its C# base list only ever names its sealed base, so
a `var` it widens over a read-only sealed base stays a plain get-only override with the named skip,
the same shape a non-sealed class without an implemented interface gets. Consequence for your own
code: a C# class implementing a `var`-bearing interface must declare every setter the interface now
asks for; one that only declared a getter before this render shipped no longer compiles.

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

### Method overloads on an interface {id="method-overloads-on-an-interface"}

Two or more same-named methods on a Kotlin interface collapse into one natural C# overload set on
the generated interface, exactly like [an ordinary class's overloads](classes-and-objects.md#method-overloads);
the numbering that keeps the export symbols distinct never reaches the C# surface:

```kotlin
interface Brusher {
  fun brush(): String
  fun brush(strokes: Int): String
  fun brush(mood: Mood): String
  fun trim(claws: Int = 4): String
  fun trim(paw: String, claws: Int = 1): String
}
```

```C#
public interface IBrusher : IDisposable
{
    string Brush();
    string Brush(int strokes);
    string Brush(global::TestLibrary.Cat.Mood mood);
    string Trim(int? claws = null);
    string Trim(string paw, int? claws = null);
}
```

This works the same way whether the interface reaches C# through
[a return value](#interface-typed-return-values) or [a parameter](#implementing-a-kotlin-interface-in-c):

```C#
using IBrusher brusher = BrusherKt.HouseBrusher();
brusher.Brush(Mood.Grumpy); // "Oreo is brushed while grumpy" - not Brush(2)'s overload

salon.BrushAll(new MyloBrusher()); // each Kotlin call reaches Mylo's matching C# overload
```

As on the class route, C# cannot overload on reference nullability alone: a `fun tag(s: String)` /
`fun tag(s: String?)` pair on an interface fails generation with `ERROR_CSHARP_SIGNATURE_COLLISION`
instead of producing invalid C#.

### An interface extending another interface {id="interface-super-interfaces"}

`interface Derived : Base` renders real C# interface inheritance: `IDerived` declares only its own
members and inherits the rest, the way C#'s own `IList<T> : ICollection<T>` does:

```kotlin
interface Named {
  val name: String
  fun greet(): String
}

interface Aged {
  val age: Int
}

interface Pet : Named, Aged {
  fun feed(food: String): String
}

interface HouseCat : Pet {
  fun purr(times: Int): String
  override fun greet(): String = "Purr, I'm $name" // identical signature: not redeclared below
}
```

```C#
public interface INamed : IDisposable
{
    string Name { get; }
    string Greet();
}

public interface IAged : IDisposable
{
    int Age { get; }
}

public interface IPet : INamed, IAged, IDisposable
{
    string Feed(string food);
}

public interface IHouseCat : IPet, IDisposable
{
    string Purr(int times); // Greet() stays on INamed; redeclaring it here would be CS0108
}
```

A C# reference typed as any ancestor works, including one returned from Kotlin behind the
[return-value backing wrapper](#interface-typed-return-values) and one a plain C# class
implements:

```C#
using IHouseCat oreo = Lineage.AdoptHouseCat();
INamed named = oreo;               // compiles because IHouseCat is transitively an INamed
named.Greet();                     // "Purr, I'm Oreo" - HouseCat's own override, reached via INamed

private sealed class MyloHouseCat : IHouseCat { /* must implement every inherited member too */ }
```

Implementing `IHouseCat` in C# obliges every member the whole hierarchy declares, `Name`/`Nickname`/
`Greet()`/`Age`/`Feed(string)`/`Purr(int)`: that full set is exactly what the
[C#-implemented-interface bridge](#implementing-a-kotlin-interface-in-c) reads when Kotlin calls
back into it, whichever ancestor's parameter position it crosses at.

A generic super-interface keeps its type argument on the derived interface, the same as a class
implementing one does:

```kotlin
interface Holder<T> {
  val size: Int
  fun peek(): T
}

interface IntHolder : Holder<Int> {
  fun shake(): String
}
```

```C#
public interface IIntHolder : IHolder<int>, IDisposable
{
    string Shake(); // Size/Peek() stay on IHolder<int>, not redeclared here
}
```

A super-interface outside the plugin's export scope (a dependency-module or unbound-package type)
is dropped from the base list, `SKIPPED_UNEXPORTED_SUPERTYPE`, and its members are declared
directly on the derived interface instead, so a C# implementer can still satisfy them:

```kotlin
interface Pedigree { // never exported
  val breed: String
  fun registry(): String
}

interface ShowCat : Named, Pedigree {
  fun pose(): String
}
```

```C#
public interface IShowCat : INamed, IDisposable
{
    string Breed { get; }   // re-homed from the unexported Pedigree
    string Pose();
    string Registry();      // re-homed from the unexported Pedigree
}
```

Two unrelated supers declaring the *same* member (a diamond) redeclare it with `new` instead of
being dropped, since C# would otherwise leave the call ambiguous:

```kotlin
interface Whiskered { val whiskers: Int; fun twitch(): String = "Whiskers twitch" }
interface Tailed { val whiskers: Int; fun twitch(): String = "Tail swishes" }

interface Moggy : Whiskered, Tailed {
  override fun twitch(): String = "twitches and swishes" // Kotlin forces this override
  fun nap(): String
}
```

```C#
public interface IMoggy : IWhiskered, ITailed, IDisposable
{
    new int Whiskers { get; }
    new string Twitch();
    string Nap();
}
```

An override that **narrows** a kept super's return type (`override fun greet(): Cat` over a
super's `fun greet(): Pet`) cannot be redeclared without also generating an explicit interface
implementation on every implementer, so it stays off the derived interface entirely, named
`SKIPPED_UNSUPPORTED_COMBINATION`: call it through the ancestor interface, at the ancestor's type.

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
`_handle`. A hand-written C# subclass compiles too, since the generated `Interop.cs` ships as
`<Compile Include>`d source and lands in the same assembly; its constructor chains
`: base(IntPtr.Zero, out _)` instead of calling the base's public constructor.

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

The same also applies when the abstract member is inherited from an **unexported abstract base
class** instead of an interface. `SKIPPED_UNEXPORTED_SUPERTYPE` already drops that base from the C#
class list and re-homes its bridgeable members onto the exported subclass; an abstract member it
never implements now renders `public abstract` on that subclass too, so a further C# or Kotlin
`override` compiles instead of failing to build:

```kotlin
abstract class Cushion { // never exported
  abstract val weave: String
  abstract var loft: Int
}

abstract class Lounger : Cushion() // exported; implements neither member

class Beanbag : Lounger() {
  override val weave: String = "corduroy"
  override var loft: Int = 4
}
```

```C#
public abstract class Lounger : IDisposable, INugetHandle
{
    public abstract string Weave { get; }
    public abstract int Loft { get; set; }
}
```

A member the planner declines to plan, for example a generic interface default the type mapper
cannot spell abstractly, or one whose own type has no C# declaration (a nested class never exported),
is dropped from the generated class instead of rendered `abstract`, named on a build warning; an
uncompilable abstract member would break every further subclass.

### A class-typed abstract member {id="a-class-typed-abstract-member"}

A parameter or return typed with another declared class renders fully qualified, never a bare name:
the generated file carries only the `System` usings, so an unqualified reference would resolve to
nothing outside the declaring class's own namespace.

```kotlin
abstract class Hauler(val plate: String) {
  abstract fun cargo(): Toy // declared in a different package
}
```

```C#
public abstract class Hauler : IDisposable, INugetHandle
{
    public abstract global::TestLibrary.Cat.Toy Cargo();
}
```

A type with no C# declaration at all, an unexported or nested-under-an-unexported-owner class, is
dropped from the abstract declaration instead of spelled, named on a build warning.

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

A `class`-kind arm whose public constructor parameters are all bridgeable exports that constructor
too, spelled exactly like an ordinary class with the same parameters and chaining into the
inherited handle:

```kotlin
sealed class Nap {
  data class Deep(val minutes: Int) : Nap()
  data object Zoomies : Nap()
}
```

```C#
public Deep(int minutes) : base(IntPtr.Zero, out _)
{
    IntPtr handle = Native_Create(minutes, out IntPtr error);
    // ...
    _handle = handle;
}
```

`new Nap.Deep(minutes: 12)` then works like any other constructed object, at an arm-typed or a
base-typed parameter. An `object`/`data object` arm, like `Zoomies`, is unchanged: Kotlin gives it
no public constructor to export, so it stays reachable only from Kotlin. An arm whose every
constructor is refused, for example one whose sole parameter is an opt-in-marked type, keeps only
its `internal` handle constructor and warns `WARNING_NO_PUBLIC_CONSTRUCTOR`, the same as a
non-subclass class with [no reachable constructor](classes-and-objects.md#no-public-constructor).

A `data class`/`data object` arm gets `Equals`/`GetHashCode`/`ToString` the same as any
[data class](data-classes.md); compare two reads of the same singleton arm with `Equals`, not
`ReferenceEquals`.

### Sealed interfaces {id="sealed-interfaces"}

A `sealed interface` maps the same way exactly when it is **eligible**: no type parameters, and
every subclass is a `class`/`object` or `enum class`, nested in the interface or declared beside it,
with no other superclass, no sub-interface, and no second sealed-interface parent. An eligible
sealed interface renders as an `abstract class`, exactly like `sealed class` above; no `IFoo`
interface is ever declared for it:

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
interface, or is a sub-interface arm, stays on the ordinary interface route instead: it keeps its
plain `IFoo` declaration, and every function, property, or parameter typed with it is skipped,
named on a build warning. There is no partial binding for an ineligible sealed interface: make
every arm a plain `class`/`object`/`enum class` with no other superclass, or use a `sealed class`
instead.

### An `enum class` arm {id="an-enum-class-arm"}

An `enum class` arm, alone or mixed with `class`/`object` arms, binds as a boxed arm: `public
sealed class {Enum}Arm : Base`, holding the enum entry, with a public constructor from the C# enum
and a `Value` getter back to it. The C# `enum` is still declared exactly once, keeping its own
[extension methods](enums.md); a boxed arm adds no second spelling for those members.

```kotlin
sealed interface Marking

enum class Patch : Marking { BIB, SOCKS }
enum class Swirl(val patch: Patch) : Marking { COCOA(Patch.BIB), CREAM(Patch.SOCKS) }
```

```C#
using Marking painted = EnumArmedSealedSample.PaintedMarking();

string text = painted switch
{
    PatchArm { Value: Patch.Bib } => "bib",
    PatchArm patch => $"patch {patch.Value}",
    SwirlArm swirl => $"swirl {swirl.Value} over {swirl.Value.Patch()}",
    _ => throw new InvalidOperationException(),
};

using var socks = new PatchArm(Patch.Socks); // boxes an existing entry
```

`swirl.Value.Patch()` reaches `Swirl`'s own member through the ordinary [enum extension
method](enums.md), never a second spelling on the box. A boxed arm is `IDisposable` like any other
arm; `new PatchArm(Patch.Socks)` without `using` leaks a handle to a permanent Kotlin singleton the
same way an unboxed arm constructor does. There is no implicit conversion from the C# enum to the
sealed base (`Marking m = Patch.Bib` does not compile): it would mint a handle the caller never sees
and cannot dispose. A declared type already named `{Enum}Arm` in the same namespace refuses the
interface, naming the collision, rather than colliding silently. An arm that extends another class
in addition to being an enum is still out of scope.

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
binds the same as it would on an ordinary class: overloads, widened default parameters,
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

A default parameter that lives on an interface the sealed base overrides counts too, even though
Kotlin forbids the base's own `override` from restating it: the base still gets the omitting
overload, and every arm inherits it through C# inheritance.

```kotlin
interface Squishy { fun squish(factor: Double = 1.0): Double }
sealed class Pose : Squishy {
  override fun squish(factor: Double): Double = factor
}
```

```C#
pose.Squish();    // factor defaults to 1.0
pose.Squish(2.0);
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
- An ineligible sealed interface, an arm with another superclass, a sub-interface arm, or an arm
  implementing a second sealed interface, has no binding at all: every function, property, or
  parameter typed with it is skipped.
- An `enum class` arm's boxed constructor and `Value` getter cost a handle and a P/Invoke each; see
  [An `enum class` arm](#an-enum-class-arm) for the disposal obligation and the missing implicit
  conversion.
- A sealed arm's own interfaces are dropped silently from the generated class: its C# base list
  names only the sealed base, whether the arm implements an eligible sealed interface plus an extra
  one (`class Odd : Kind, CharSequence`) or an ordinary exported interface with a `var` (`class Arm :
  Perch(), Tally`). `arm is ITally` is always false in C#, and there is no explicit-member fallback
  for a `var` setter the way a non-sealed class gets (see [above](#an-interface-s-own-var-property)).
- An `object` arm, or a `class`-kind arm whose every constructor is refused, has only the
  `internal` handle constructor, so `new Base.Arm(9)` fails to compile rather than binding it;
  obtain the arm from a factory or from the base's `FromHandle` discriminator instead. A
  `class`-kind arm with bridgeable constructor parameters exports a real public constructor
  instead; see [Sealed classes and interfaces](#sealed-classes-and-interfaces).
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
