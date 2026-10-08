# Interfaces, abstract classes, and sealed classes

Kotlin's three flavours of inheritance each get a distinct C# shape: `interface` becomes an
`I`-prefixed C# interface with default methods delegating back to Kotlin, `abstract class` becomes
a C# `abstract class` whose subclasses share one inherited `_handle`, and `sealed class` becomes an
`abstract class` with each subtype reconstructed through a generated discriminator.

| Kotlin | C# | Notes |
|---|---|---|
| `interface` | `interface` (`I`-prefixed) | default methods delegate to Kotlin; a super-interface's members are inherited, not redeclared; `suspend`/`Flow`/`StateFlow` members are declared too, and the interface becomes `IAsyncDisposable` when it has one |
| `abstract class` | `abstract class` | `_handle` inherited by every subclass; a value typed as the class comes back as an internal subclass |
| `sealed class` | `abstract class` | each subtype its own class, nested inside the base or declared beside it, reconstructed through a generated `FromHandle`; a generic `sealed class` binds as `Outcome<T>` with arms on a non-generic `Outcome` holder (`Outcome.Ok<T>`) |
| eligible `sealed interface` (every subclass a `class`/`object` or `enum class`, no other superclass, no sub-interface, no second sealed-interface parent) | `abstract class` | same shape as `sealed class`; no C# interface is declared for it; an `enum class` arm binds as a boxed `{Enum}Arm` |
| `sealed interface` whose arms extend a class or implement a second sealed interface, every arm an exported class | `interface` (`I`-prefixed) | binds at every sealed position; a returned value is the concrete arm; see [A sealed interface whose arms extend a class](#sealed-interface-over-arms) |
| any other `sealed interface` | `interface` (`I`-prefixed) | stays on the ordinary interface route; every member typed with it is skipped, named |

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

A `List`, `Set` or `Map` of an interface (`List<Pet>`) binds too, as `IReadOnlyList<IPet>` and its
kin; see [Collections: Interfaces as collection components](collections.md#interfaces-as-collection-components).

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

If the implementing class is itself `open` (or `abstract`), its inherited default renders `public
virtual` instead, so a further Kotlin subclass can override it:

```kotlin
interface Scratcher {
  fun scratch(): String = "a quick scratch"
}

open class ScratchingPost : Scratcher

class CarpetPost : ScratchingPost() {
  override fun scratch(): String = "shreds the carpet post"
}
```

```C#
public class ScratchingPost : IScratcher, IDisposable, INugetHandle
{
    public virtual string Scratch() { /* ... */ } // ScratchingPost never declared this itself
}

public class CarpetPost : ScratchingPost
{
    public override string Scratch() { /* ... */ }
}
```

If the interface is not exported, a generic default (or any default no route binds) is not carried
and is named once on each exported class that inherits it. Its `Flow`-return and lambda-parameter
defaults still bind on each implementing class.

The same applies to an `open` [sealed arm](#open-arms-and-further-nesting) inheriting a default from
one of its own interfaces: the arm renders it `virtual` too, so a further Kotlin subclass of the arm
compiles. A `final` owner's inherited default stays non-virtual either way, since Kotlin agrees it
cannot be overridden.

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
  var lastSlip: IllegalStateException?
}
```

```C#
public interface ITally : IDisposable
{
    int Count { get; set; }
    Exception? LastSlip { get; } // no setter: narrower than RuntimeException
}
```

A setter that cannot plan for a type-level reason stays `{ get; }` on the interface too, named in
the build log and on the member itself, whether or not any class ever implements the interface.
`IllegalStateException?` above is such a type: C# exceptions reach Kotlin as a `RuntimeException`,
so a narrower declared type cannot hold one (see
[Passing an exception to Kotlin](exceptions.md#passing-an-exception-to-kotlin)).

One shape needs a second render, because it would otherwise fail to compile: a class that overrides
both an exported base class's `open val` and an exported interface's `var` with a single Kotlin
`override var`. The public property can't gain a setter (the [widening rule above](#widening-a-read-only-property-to-var)
still applies), so C# can't satisfy `ITally.Count { get; set; }` through it; the setter is instead
implemented **explicitly**, reachable only by declaring the reference as the interface:

```kotlin
open class Scoreboard { open val count: Int = 0 }

class TrainingClicker : Scoreboard(), Tally {
  override var count: Int = 0
  override var lastSlip: IllegalStateException? = null
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

    public override global::System.Exception? LastSlip { get { /* ... */ } } // no explicit member: the setter is refused entirely
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
sealed arm gets the identical explicit-member treatment whenever its own read-only sealed base is in
the way; see [A sealed arm's own interfaces](#sealed-arm-own-interfaces) below. Consequence for your
own code: a C# class or sealed arm implementing a `var`-bearing interface must declare every setter
the interface now asks for; one that only declared a getter before this render shipped no longer
compiles.

### Async members on an interface {id="async-members-on-an-interface"}

A `suspend`, `Flow<T>`, or `StateFlow<T>` member on an interface, abstract or defaulted, is declared
on the generated `I<Name>` itself, with the same signature the class route uses, so a caller holding
only the interface-typed reference can still reach it. The interface becomes `IAsyncDisposable`,
draining whichever object is behind it:

```kotlin
interface Feed {
  suspend fun fetch(id: Int): String
  fun ticks(): Flow<Int>
  val level: StateFlow<Int>
  fun doubled(): Flow<Int> = ticks().map { it * 2 } // default body, declared on IFeed too
  fun name(): String
}

class RssFeed : Feed { /* overrides fetch/ticks/level/name */ }
fun makeFeed(): Feed = RssFeed()
```

```C#
public interface IFeed : IDisposable, IAsyncDisposable
{
    KotlinStateFlow<int> Level { get; }
    string Name();
    Task<string> FetchAsync(int id, CancellationToken cancellationToken = default);
    KotlinFlow<int> Ticks();
    KotlinFlow<int> Doubled();
}
```

```C#
await using IFeed feed = Feeds.MakeFeed();
await feed.FetchAsync(3);
await foreach (int tick in feed.Ticks()) { /* ... */ }
int level = feed.Level.Value;
await foreach (int doubled in feed.Doubled()) { /* ... */ } // the default, reached through IFeed
```

A generic implementer, which [ADR-147](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/147-generic-class-methods.md)
refuses these members on directly, gets them as **explicit** interface implementations instead: they
dispatch correctly, but only through an `IFeed`-typed reference, not through the concrete generic
type:

```C#
await using IFeed crate = Feeds.MakeCrate(); // wraps Crate<int>
await crate.FetchAsync(3);                   // compiles: reached through IFeed

var direct = new Crate<int>(1);
// direct.FetchAsync(3) does not compile: FetchAsync is IFeed's explicit member, not Crate<T>'s own
```

An interface nested in a class or object gets the same treatment: its async members are declared on
the nested `Outer.I<Name>`, and a generic implementer still reaches them through that interface
reference.

```kotlin
class Pantry {
  interface Bowl {
    suspend fun fill(scoops: Int): String
    fun kibbles(): Flow<Int>
    val level: StateFlow<Int>
  }
  fun open(): Bowl = OreoBowl()
}
```

```C#
public class Pantry : IDisposable, INugetHandle
{
    public interface IBowl : IDisposable, IAsyncDisposable
    {
        KotlinStateFlow<int> Level { get; }
        Task<string> FillAsync(int scoops, CancellationToken cancellationToken = default);
        KotlinFlow<int> Kibbles();
    }
}
```

```C#
using var pantry = new Pantry();
await using Pantry.IBowl bowl = pantry.Open();
string filled = await bowl.FillAsync(3);
```

An async member inherited from a super-interface is callable through the derived interface, even when
nothing in your Kotlin API returns the super-interface:

```kotlin
interface Trough {
  suspend fun fetch(id: Int): String
  fun ticks(): Flow<Int>
  val level: StateFlow<Int>
}
interface Manger : Trough { fun own(): Int }

fun makeManger(): Manger = Stall()
```

```C#
public interface ITrough : IDisposable, IAsyncDisposable { /* FetchAsync, Ticks, Level */ }
public interface IManger : ITrough, IDisposable, IAsyncDisposable { int Own(); }
```

```C#
await using IManger manger = Mangers.MakeManger();
string item = await manger.FetchAsync(7); // declared on ITrough, inherited by IManger
```

`ITrough` is generated even though only `Manger` is reachable. A super-interface that cannot be
generated this way, a generic one (`Satchel<T>`) or one in a package you did not export, has its async
members declared on the derived interface instead, so `IHaversack : Satchel<Int>` still declares
`FetchAsync`.

`interface Feed<T>` (a generic interface) keeps its async members off `IFeed<T>` entirely, named
`SKIPPED_GENERIC_INTERFACE_ASYNC_MEMBER`. An [eligible sealed interface](#sealed-interfaces) is
excluded from this, since it never gets an `I<Name>` declaration; its async members are declared on
the abstract class instead, see
[Async members on a sealed base](#sealed-method-suspend-generated-c).

**Breaking:** a hand-written C# class implementing an interface that gains async members this way
stops compiling (`CS0535` on the new members and on `DisposeAsync`), the same way adding a lambda
parameter to `I<Name>` already could (see [below](#implementing-a-kotlin-interface-in-c)). Combined
with that section's existing limits — `KotlinFlow<T>`'s constructor is `internal` — such an
interface was never practically implementable in C# anyway.

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
returning `Unit`, a primitive, `Boolean`, an enum, or `String`/`String?`. A `Throwable`,
`Exception` or `RuntimeException` parameter or result crosses too, as a `System.Exception` (see
[Throwable values](exceptions.md#throwable-values)); a result declared narrower than
`RuntimeException` is named on a build warning. An interface with a `var`
property, an object- or collection-typed member, a `suspend` or `Flow` member, a generic member, or
a member with more than two parameters has **no bridge at all**. The build warns once with
`SKIPPED_UNIMPLEMENTABLE_INTERFACE`, naming every such member and how to fix it, and passing an
implementation of it throws `NotSupportedException` the first time it crosses:

```
[nuget:SKIPPED_UNIMPLEMENTABLE_INTERFACE] Skipping tier1.refusevar.Pet: a C# class implementing
    `IPet` cannot be passed to Kotlin, because `var mood: String` is a `var`, and the bridge carries
    a property through a getter slot only. For `var mood: String`, declare it `val`, or hand the
    value over through a member function. Kotlin-backed `IPet` values are unaffected
```

Kotlin-backed implementations of the interface still cross as usual, and `IPet` still declares every
member. A marker interface with no members bridges too:
Kotlin receives an empty object that implements it, and handing it back to C# returns your
original instance. (Before, passing one threw.)

A member whose C# name clashes with a generated one (`token`, `state`, `error`, `release`, or a C#
keyword such as `lock`) is handled for you; implement the member under its usual name.

#### Lifetime and identity {id="lifetime-and-identity"}

The bridge object's release is **GC-timed, not deterministic**: Kotlin frees it on a later
garbage-collection round, not the moment the C# reference goes out of scope. There is no
`IDisposable`-style prompt release for a C#-implemented interface.

Reading a stored C#-implemented object back from Kotlin, whether through a property, a `suspend
fun` completion, a `Flow<T>` element, or any of the three erased generic routes (a
`KotlinFunc<IPet, ...>` result, a legacy `fun <T>` return, or an [ADR-147 generic class's `T`
member](generics.md#an-interface-at-an-erased-position)), resolves to the **original C# instance**,
not a fresh wrapper:

```C#
oreo.Befriend(dog);
oreo.Friend; // the same `dog` instance, not a new Pet wrapper

using KotlinFunc<IPet, IPet> relay = PetRelayKt.PetRelay();
relay.Invoke(dog); // the same `dog` instance too
```

An erased type argument spells an exported interface as the interface itself, never the
ADR-040 backing wrapper: `PetRelayKt.PetRelay()` returns `KotlinFunc<IPet, IPet>`, not
`KotlinFunc<Pet, Pet>`. A consumer that named the old wrapper type explicitly stops compiling;
`var` and passing the result straight on keep working.

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

### Choosing the C# name {id="interface-csharp-name"}

A property and a method with one Kotlin name on an interface collide in C#. Put `@CSharpName` on the
interface member; implementing classes inherit the name without repeating it (see
[Choosing the C# name](instance-members.md#choosing-the-csharp-name) for the rules):

```kotlin
interface Advertisement {
  val collarTag: CollarTag?

  @CSharpName("CollarTagBytes")
  fun collarTag(code: Int): ByteArray?
}
```

```C#
byte[]? CollarTagBytes(int code);
```

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

An interface nested inside a non-generic `class` or `object` is declared as a real
nested C# interface, `Outer.IListener`, with its return-position wrapper nested beside it
(`Outer.Listener`); see [Classes and objects: Nested types](classes-and-objects.md#nested-classes-and-objects).
A nested interface under a generic class or an `enum class` is skipped, and any member typed with
it is skipped too, each named on a build warning rather than silently dropped.

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

A lambda-typed property a subclass overrides is declared once, on the base that carries it:
reading `napper.OnWake` through the subclass runs the subclass's lambda through Kotlin dispatch,
and the subclass declares no second `OnWake`. A subclass of a generic base declares it itself.

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

When the unexported base only **redeclares** a property that an exported base further up already
declares abstract, the re-homed member renders `abstract override`, so a concrete subclass binds to the
single inherited slot. A property that is new on the unexported base stays plain `abstract`:

```C#
public abstract class RedefinedDinghy : RedefinedVessel // RedefinedSkiff in between is unexported
{
    public abstract override string Sail { get; }   // RedefinedVessel declares it too
    public abstract string Rigging { get; }         // new on RedefinedSkiff
}
```

A member the planner declines to plan, for example a generic interface default the type mapper
cannot spell abstractly, or one whose own type has no C# declaration (a nested class never exported),
is dropped from the generated class instead of rendered `abstract`, named on a build warning; an
uncompilable abstract member would break every further subclass.

An `abstract fun` returning `Flow<T>` binds like any other `Flow` member: the abstract class
declares it, so `await foreach` works on a value typed as the class, and the Kotlin subclass's
override supplies the items. Dispose the value with `await using` to drain it, as for any
[Flow member](coroutines-and-flow.md).

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

### An abstract class as a return type {id="an-abstract-class-as-a-return-type"}

A function, property or list typed as an abstract class hands you that abstract type. C# cannot
construct it, so the value is an internal subclass that forwards each abstract member to the
Kotlin object. `is` against the concrete Kotlin subclass is false, but every member answers as that
subclass, and you dispose it like any returned class:

```kotlin
abstract class Hibernator {
  abstract val name: String
  abstract fun snores(): Int
  open fun describe(): String = "$name snores ${snores()} times"
}

class OreoHibernator : Hibernator() { /* name = "Oreo", snores() = 3 */ }

class Den {
  fun hibernator(): Hibernator = OreoHibernator()
  val hibernators: List<Hibernator> get() = listOf(OreoHibernator(), OreoHibernator())
}
```

```C#
using Hibernator hibernator = den.Hibernator();
hibernator.Describe();                         // "Oreo snores 3 times"
bool concrete = hibernator is OreoHibernator;  // false
```

An abstract class that extends another abstract class, or an abstract sealed arm, comes back the
same way at any depth. A `Napper : Hibernator` returned as `Napper` answers `Name` and `Weigh()`,
which `Hibernator` leaves open, as well as its own members.

A *generic* abstract class returned at a closed type comes back the same way, and so does an
abstract class below one. Only a top-level function return binds a closed generic class today (see
[Generics](generics.md#returning-an-instantiated-generic-class)); a property, parameter, list
element or nullable of that type is a named skip (a [generic sealed hierarchy](#generic-sealed-hierarchy)
is the exception):

```kotlin
abstract class Trove<T>(val first: T) {
  abstract fun pick(): T
  abstract val keeper: String
}

abstract class Alcove : Trove<String>("catnip") { abstract fun depth(): Int }

class OreoTrove(first: String) : Trove<String>(first) { /* pick() = "$first flake" */ }

fun stock(): Trove<String> = OreoTrove("tuna")
class Hutch { fun alcove(): Alcove = OreoAlcove() }
```

```C#
using Trove<string> trove = CubbySample.Stock();
trove.Pick();                       // "tuna flake"
bool concrete = trove is OreoTrove; // false

using var hutch = new Hutch();
using Alcove alcove = hutch.Alcove();
((Trove<string>)alcove).Pick();    // "catnip mouse"
```

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

### A sealed base's own supertype {id="sealed-base-supertype"}

A sealed class's own supertype crosses the same way an ordinary class's does. An **exported**
supertype is named in the sealed base's C# base list, so `is`/`as` against it work and its own
members stay on it:

```kotlin
open class Pouffe {
  val stuffing: String = "beans"
  open fun sink(): Int = 1
}

sealed class Ottoman : Pouffe() {
  data class Tall(val shelf: Int) : Ottoman() {
    override fun sink(): Int = shelf
  }

  data class Squat(val step: Int) : Ottoman() // inherits Pouffe's Sink() unchanged
}
```

```C#
public abstract class Ottoman : Pouffe // Stuffing stays on Pouffe, not re-declared here
{
    public sealed class Tall : Ottoman
    {
        public override int Sink() { /* ... */ } // overrides Pouffe's own member
    }
}
```

An **unexported** supertype (a dependency-module or unbound-package type) is dropped from the base
list instead, named `SKIPPED_UNEXPORTED_SUPERTYPE`, and its public members are re-homed onto the
sealed base, reachable there and, through ordinary C# inheritance, on every arm:

```kotlin
// UnexportedBlanket lives outside the export scope
open class UnexportedBlanket {
  val fabric: String = "fleece"
  open fun shake(): Int = 1
}

sealed class Swaddle : UnexportedBlanket() {
  data class Wriggling(val turns: Int) : Swaddle() {
    override fun shake(): Int = turns
  }

  data class Still(val limbs: Int) : Swaddle() // inherits the re-homed Shake() unchanged
}
```

```C#
public abstract class Swaddle : IDisposable, INugetHandle
{
    public virtual string Fabric { get { /* ... */ } } // re-homed from UnexportedBlanket
    public virtual int Shake() { /* ... */ }            // re-homed from UnexportedBlanket
}
```

`Swaddle.Wriggling`'s override of `Shake()` renders `override`, since the base now declares it
`virtual`; `Swaddle.Still` declares nothing of its own and inherits the base's re-homed `Shake()`
through ordinary C# dispatch. This applies to an unexported *interface* supertype too, including an
abstract member with no default the sealed base itself never implements: it still re-homes onto the
base and every arm implements it as it would any other inherited abstract member.

### A sealed arm's own interfaces {id="sealed-arm-own-interfaces"}

A sealed arm that implements an interface lists it in its own C# base list, beside the sealed base,
so `arm is IFoo` holds and every interface member is callable through the arm — including one it only
inherits as a default, or reaches through a super-interface:

```kotlin
interface Basker {
  val warmth: Int
  fun bask(): String = "basking at $warmth degrees"
}

interface Sunseeker : Basker {
  val spot: String
  var naps: Int
  fun stretch(): String = "stretches on the $spot after $naps naps"
}

sealed class Sunroom {
  open val naps: Int = 0

  class Beam(override val spot: String) : Sunroom(), Sunseeker {
    override var naps: Int = 0
    override val warmth: Int = 30
  }
}
```

```C#
public sealed class Beam : Sunroom, global::Interop.ISunseeker
{
    public override int Naps { get { /* ... */ } } // get-only: widens Sunroom's read-only naps (CS0546)

    int global::Interop.ISunseeker.Naps // ADR-168's explicit setter, now given to a sealed arm too
    {
        get => Naps;
        set { /* ... */ }
    }

    public string Stretch() { /* ... */ } // Sunseeker's own default, inherited, never overridden
    public string Bask() { /* ... */ }    // Basker's default, reached through the super-interface
}
```

A sealed arm is a nested type, so its base list is qualified even for a same-namespace
interface (`global::Interop.ISunseeker`, not bare `ISunseeker`): the qualification avoids binding
to a differently-named nested type declared beside it. A top-level class's same-namespace base
list stays bare (`TrainingClicker : Scoreboard, ITally` above).

```C#
ISunseeker seeker = beam;
seeker.Naps = 5;    // reaches Kotlin through the explicit member
beam.Naps;           // 5 - the public getter agrees
```

An interface an arm cannot list, because it is unexported or otherwise unspellable, is dropped from
the arm's base list the same way it would be for an ordinary class (`SKIPPED_UNEXPORTED_SUPERTYPE`),
with its members re-homed onto the arm directly:

```kotlin
class Draught(val gap: Int) : Sunroom(), UnexportedRadiator { // never exported
  override val fins: Int get() = gap * 2
}
```

```C#
public sealed class Draught : Sunroom // no interface here: UnexportedRadiator has no C# type
{
    public int Fins { get { /* ... */ } }  // re-homed, not reached through an interface
    public int Ticks { get { /* ... */ } } // UnexportedRadiator's own default, re-homed too
    public string Hum() { /* ... */ }
}
```

### Subclass placement and pattern matching

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

A `sealed interface` maps the same way exactly when it is **eligible**: every subclass is a `class`/`object` or `enum class`, nested in the interface or
declared beside it, with no other superclass, no sub-interface, and no second sealed-interface parent. An eligible
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
interface, or is a sub-interface arm, keeps its plain `IFoo` declaration instead of becoming an
abstract class. When every arm is a class C# can construct, it still binds, through a discriminator
on the interface itself: see [A sealed interface whose arms extend a
class](#sealed-interface-over-arms). When any arm is not, every function, property, or parameter
typed with it is skipped, named on a build warning. There is no partial binding: fix the arm the
warning names, or use a `sealed class` instead.

### A sealed interface whose arms extend a class {id="sealed-interface-over-arms"}

A `sealed interface` whose arms already extend a class, typically the arms of a `sealed class`,
binds as the C# interface `I<Name>` rather than an abstract class. Any arm passes where the
interface is expected, and a returned value is the concrete arm, so it pattern matches and is also an
instance of its class base:

```kotlin
sealed class EvidenceDevice

sealed interface ConnectableDevice { val address: String }
sealed interface Chargeable { val battery: Int }

data class NearbyDevice(val name: String, override val address: String) :
  EvidenceDevice(), ConnectableDevice

data class RemoteDevice(val host: String, override val battery: Int) :
  EvidenceDevice(), ConnectableDevice, Chargeable {
  override val address: String get() = "remote://$host"
}

data object SavedDevice : EvidenceDevice(), ConnectableDevice {
  override val address: String get() = "saved"
}

fun connect(device: ConnectableDevice): EvidenceDevice = when (device) {
  is NearbyDevice -> device
  is RemoteDevice -> device
  SavedDevice -> SavedDevice
}

fun preferred(): ConnectableDevice = NearbyDevice(name = "desk", address = "aa:bb")
```

The interface keeps its `I` declaration, and each arm keeps its class base and lists the interface:

```C#
public interface IConnectableDevice : IDisposable
{
    string Address { get; }
    internal NugetKotlinHandle _handle { get; }
    // internal static IConnectableDevice FromHandle(...) picks the arm class
}

public sealed class NearbyDevice : EvidenceDevice, IConnectableDevice { /* ... */ }
public sealed class RemoteDevice : EvidenceDevice, IConnectableDevice, IChargeable { /* ... */ }
```

```C#
using var nearby = new NearbyDevice("desk", "aa:bb");
using EvidenceDevice connected = Devices.Connect(nearby);   // any arm where the interface is expected

using IConnectableDevice picked = Devices.Preferred();      // the concrete arm comes back
string label = picked switch
{
    NearbyDevice n => $"nearby {n.Address}",
    RemoteDevice r => $"remote {r.Address}",
    SavedDevice s => $"saved {s.Address}",
    _ => throw new InvalidOperationException(),
};
bool alsoEvidence = picked is EvidenceDevice;               // true
```

It binds wherever a sealed class does: a parameter, return, nullable return, `val`/`var` property,
`List`/`Set`/`Map` component, constructor parameter, `suspend` parameter and result, `Flow` and
`StateFlow` item on a class member, and a member of an arm. Every returned value is a fresh,
owned arm: dispose it. The `switch` needs a discard arm, since C# cannot check a hierarchy for
exhaustiveness.

**Which arms bind.** Every arm must be a class C# can construct, and one refused arm refuses the
whole interface, with a `SKIPPED_INELIGIBLE_SEALED_INTERFACE` warning that names it:

| An arm that is | Binds |
|---|---|
| a class or `data object` of a non-generic `sealed class` | yes |
| an exported, top-level, non-abstract, non-generic class, with or without a superclass | yes |
| a class that also implements a second sealed interface of this kind | yes |
| a bare `object`, an `enum class`, a sub-interface | no |
| generic, or an arm of a generic sealed class, or the interface is generic | no |
| abstract, or an intermediate `sealed class` | no |
| nested in the interface with no sealed class base | no: declare it beside the interface |
| a subclass of another arm of the same interface | no |
| not exported | no |

**What to know before relying on it:**

- The C# shape follows the arms. Giving one arm of an eligible sealed interface a superclass flips
  it from `abstract class Pulse` to `interface IPulse`, which breaks C# consumers at compile time.
- A class that implements two such interfaces, like `RemoteDevice` above or `data class Both :
  Left, Right`, binds as both `ILeft` and `IRight` over the one class. It was refused before.
- Do not implement `I<Name>` in C#. An ordinary implementation fails to compile (CS0535 on the
  internal `_handle` member); an implementer that works around it is unsupported, and nothing
  checks for it at run time.
- A callback payload of the interface type is not bound, the same as for a sealed class. A nullable
  parameter of it binds, as for a sealed class.
- A sealed type with no discriminator at a `Flow` method or `StateFlow` property item, such as a
  refused sealed interface, skips with a named warning. It used to emit a C# name nothing declares
  and break the whole package build.

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
arm; `new PatchArm(Patch.Socks)` without `using` holds a handle to a permanent Kotlin singleton
until you dispose it or the GC finalizes it, the same as an unboxed arm constructor. There is no implicit conversion from the C# enum to the
sealed base (`Marking m = Patch.Bib` does not compile): it would mint a handle the caller never sees
and cannot dispose. A declared type already named `{Enum}Arm` in the same namespace refuses the
interface, naming the collision, rather than colliding silently. An `enum class` arm cannot
join a sealed interface whose other arms extend a class: [that route](#sealed-interface-over-arms)
refuses enum arms, so the whole interface is skipped, named.

### Sealed types as property types {id="sealed-types-as-property-types"}

A property whose type is a sealed class or an eligible sealed interface binds as the sealed
**base**, materialized through `FromHandle` (see `Monitor.Current` above); a [sealed interface whose
arms extend a class](#sealed-interface-over-arms) binds the same way, as `I<Name>`. This covers the bare
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
too, exported under the arm's own name. A member the arm merely **inherits from one of its own
interfaces** — a default it never overrides itself, or one reached through a super-interface — binds
the same way, under the arm's own name (see [A sealed arm's own interfaces](#sealed-arm-own-interfaces)
above). Only a member inherited from the **sealed base itself** stays off the arm: it binds once, on
the base, described next, rather than being re-exported per arm.

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

#### Async members on a sealed base and its arms {id="sealed-method-suspend-generated-c"}

A `suspend fun`, `Flow<T>` or `StateFlow<T>` member the sealed base declares, abstract or with a
default body, binds on the C# base itself, so a caller holding the base can use it without
pattern-matching to an arm. Calls dispatch to the arm's override, or to the base's default when no
arm overrides it, including for an `enum class` arm:

```kotlin
sealed interface Shape {
  suspend fun area(): Int
  fun ticks(): Flow<Int>
  val level: StateFlow<Int>
  suspend fun fallback(): Int = 9 // default body, no arm overrides it

  data class Loaf(val width: Int) : Shape { /* overrides area, ticks, level */ }
  data object Donut : Shape { /* overrides area, ticks, level */ }
}
```

```C#
public abstract class Shape : IDisposable, IAsyncDisposable
{
    public Task<int> AreaAsync(CancellationToken cancellationToken = default);
    public KotlinFlow<int> Ticks();
    public KotlinStateFlow<int> Level { get; }
    public Task<int> FallbackAsync(CancellationToken cancellationToken = default);
    public abstract void Dispose();
    public abstract ValueTask DisposeAsync();
}
```

```C#
Shape shape = ShapeSamples.MakeShape();
await using (shape)
{
    int area = await shape.AreaAsync();
    await foreach (int tick in shape.Ticks()) { /* ... */ }
    int level = shape.Level.Value;
}
```

The base owns the one coroutine scope. Every arm overrides `DisposeAsync()` to drain it, and keeps
`IDisposable` too, so `using` still compiles. A `sealed class` base works the same way
(`Job.RestAsync` for `open suspend fun rest()`). A base member that cannot bind is a named skip with
the reason the ordinary class route gives.

A member only an arm declares (`Shape.Loaf.knead(times)`) binds as `Task<T> KneadAsync(...)` on that
arm and uses the base's scope, so `await using` on the base reference drains it too.

**Breaking:** the arm no longer declares its own copy of a base-declared member, and its own scope
moves to the base. C# source keeps compiling, since `loaf.AreaAsync()` now resolves to the inherited
`Shape.AreaAsync`. A consumer compiled against an earlier package must be rebuilt.

A generic sealed base or arm skips its `suspend` members, like any generic class; see [A generic sealed hierarchy](#generic-sealed-hierarchy).

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
arm's own name. A flow member the sealed base itself declares binds on the base instead, see
[above](#sealed-method-suspend-generated-c). Suspend and flow members share the base's one scope
and `DisposeAsync`, not two.

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

### Open and abstract arms, and further nesting {id="open-arms-and-further-nesting"}

A sealed arm declared `open` renders `public class` instead of `public sealed class`, with its own
`open` members `virtual`, so a further Kotlin subclass of it compiles. That further subclass takes
the ordinary class route, spelled through the arm's nested name (`Roost.HighPerch` extends
`Roost.Perch`). The `FromHandle` discriminator only distinguishes direct arms: a handle for a
further subclass still reconstructs as the arm itself (`Roost.Perch`), never the deeper subclass.
The underlying Kotlin object is correct and dispatch through the wrapper still reaches the
subclass's own overrides, but a consumer cannot pattern-match past the arm.

An `abstract` arm renders `public abstract class`, with its abstract members `abstract` and its
`open` members `virtual`, so an exported Kotlin subclass of it compiles in C#. Like an open arm's
subclass, that subclass comes back typed as the arm, through an internal subclass of the arm that
forwards the abstract members to Kotlin, so `is Torpor.Dormant` holds, `is DeepTorpor` does not, and
every member answers as the Kotlin `DeepTorpor`:

```kotlin
sealed class Torpor {
  abstract class Dormant : Torpor() {
    abstract fun depth(): Int
    open fun label(): String = "deep"
  }
}

class DeepTorpor(private val level: Int) : Torpor.Dormant() {
  override fun depth(): Int = level
  override fun label(): String = "deeper"
}

class Den { fun deepest(level: Int): Torpor = DeepTorpor(level) }
```

```C#
using Torpor torpor = den.Deepest(3);
var deep = (Torpor.Dormant)torpor;  // `torpor is DeepTorpor` is false
deep.Depth();                       // 3
deep.Label();                       // "deeper"
```

A sealed base, a sealed arm, and any `interface` owner can nest their own plain
`class`/`object`/`interface`/`enum class`/`value class`, declared beside the owner's other members
(`Purr.Detail`, `Purr.On.Trace`, `Beam.Lens`); see
[Classes and objects: Nested types](classes-and-objects.md#nested-classes-and-objects). A generic
class hosts them on a non-generic holder beside it. Only an `enum class` or a generic `interface` still cannot host a nested declaration.

### A generic sealed hierarchy {id="generic-sealed-hierarchy"}

A `sealed class` (or an eligible `sealed interface`) with a type parameter binds as a generic
`abstract class`. Its arms are declared on a non-generic static class of the same name, so Kotlin's
`Outcome.Ok<Int>` is `Outcome.Ok<int>` in C#:

```kotlin
sealed class Outcome<out T> {
  data class Ok<T>(val value: T) : Outcome<T>()
  data class Err(val message: String) : Outcome<Nothing>()
  data object Loading : Outcome<Nothing>()
  open fun label(): String = "outcome"
}

object OutcomeDesk {
  fun fetch(id: Int): Outcome<Int> = if (id > 0) Outcome.Ok(id) else Outcome.Err("no $id")
  fun describe(outcome: Outcome<Int>): String { /* ... */ }
}
```

```C#
using Outcome<int> outcome = OutcomeDesk.Fetch(3);

string text = outcome switch
{
    Outcome.Ok<int> ok => $"ok {ok.Value}",
    Outcome.Err<int> err => err.Message,
    Outcome.Loading<int> => "loading",
    _ => outcome.Label(),
};

using var mine = new Outcome.Err<int>("Mylo ate it");
string described = OutcomeDesk.Describe(mine);  // "err Mylo ate it"
```

`Err` fixes the base's argument to `Nothing`, which C# cannot express, so it is generic in C# too.
Its type parameter is a placeholder: choose it to match the `Outcome<T>` you hold or need
(`Err<int>` is an `Outcome<int>`, `Err<string>` an `Outcome<string>`). They are two C# types over
one Kotlin class. A `data object` arm such as `Loading<T>` is only ever received; it has no public
constructor. Arms you build in C# can be passed anywhere Kotlin wants the base.

The same rule decides every arm:

- An arm that forwards the base's parameter (`Ok<T> : Outcome<T>`) is generic over it. An arm that
  permutes or renames parameters lists them in the base's order: `Flip<X, Y> : Duel<Y, X>` is
  `Duel.Flip<Y, X>`.
- An arm that fixes a **covariant** (`out`) parameter gets a placeholder parameter, as `Err` does.
- Under an **invariant** parameter, an arm that closes the argument keeps Kotlin's exact shape:
  `class IntCell : Cell<Int>()` is `Cell.IntCell : Cell<int>`, with no placeholder.
- An intermediate `sealed` arm is abstract and has its own arms, and it follows the same rule:
  `sealed class Lapse : Outcome<Nothing>()` is `Outcome.Lapse<T>` with `Outcome.Lapse.Stall<T>`,
  while `sealed class Spare : Cell<Int>()` stays `Cell.Spare` with `Cell.Spare.Last`.
- An arm restates the base's constraints, so a base with `T : Dozer` gives `Claimed<T> where T : IDozer`.
- A type declared beside the arms (`Outcome.Detail`) lives on the `Outcome` holder, and an arm
  declared beside the base in the same file is declared at namespace level, as for any sealed class.
- An arm cannot be named like its base (`Outcome.Outcome`): the build fails with
  `ERROR_CSHARP_NAME_COLLISION`, so rename the arm.

A position typed as an arm that fixes `Nothing` (`fun fail(): Outcome.Err`) is
`Outcome.Err<KotlinNothing>`. `KotlinNothing` comes from `Kotlin.Native.Interop`; it can never be
instantiated.

A closed instantiation (`Outcome<Int>`, `Outcome<String>?`) binds as a parameter, return, property,
constructor parameter or `List` element, as a `Flow` element returned from a class method, and in a
returned lambda (`KotlinFunc<Outcome<int>, Outcome<int>>`). The argument can be a primitive,
`String`, an exported class, object, interface or enum, or a type parameter of the declaring class
(`Hamper<T>.Wrap(): Outcome<T>`). A `Hamper<Outcome<long>>` you pick yourself works too, once you
hold an `Outcome<long>` to put in it.

A consumer cannot convert between instantiations: an `Outcome.Err<KotlinNothing>` is not an
`Outcome<int>`. This is C# invariance, the same rule that stops a `List<string>` becoming a
`List<object>`. Build the `Err<int>` instead.

These are skipped with a named `SKIPPED_SEALED_POSITION` warning. The member is absent and the rest
of its owner still binds:

- A use-site projection (`Outcome<*>`): C# has no projection of a generic class.
- An argument the bridge cannot read through an erased slot (`Outcome<List<Int>>`, a lambda or a
  `Flow` as the argument).
- `Nothing` under a constraint the marker fails: `Blanket.Folded` is `Blanket<Nothing>` where C#
  restates `T : IDozer`, and `KotlinNothing` does not implement `IDozer`.

An arm that declares a parameter its base never sees (`class Both<T, U>(...) : Outcome<T>()`) is
declared without it (`Outcome.Both<T>`), since C# cannot recover `U` from an `Outcome<T>`. Its
constructor and every member that names `U` are skipped, so you receive a `Both` from Kotlin but
cannot construct one.

A `suspend`, `Flow`, or stored-callback member declared on a generic sealed base or arm is skipped,
like the same member on any [generic class](generics.md#limitations).

## Limitations {id="limitations"}

- A C#-implemented interface's bridge only supports `val` getters and arity 0-2 methods returning
  `Unit`, a primitive, `Boolean`, an enum, `String`/`String?`, or a `Throwable`/`Exception`/
  `RuntimeException` as a `System.Exception`. Anything else (a `var` property,
  an object- or collection-typed member, `suspend`, generics) throws `NotSupportedException` the
  first time an implementation is passed. The build names each disqualifying member on one
  `SKIPPED_UNIMPLEMENTABLE_INTERFACE` warning per interface.
- A C#-implemented object's bridge is released on Kotlin's next garbage-collection round, not
  deterministically; there is no `IDisposable`-style prompt release for it.
- An interface member whose own return type is another interface or a class handle (chained
  resolution) is not supported, and neither is a generic interface type parameter at a return
  position. A generic interface's own `suspend`/`Flow`/`StateFlow` members are a named skip too;
  see [Async members on an interface](#async-members-on-an-interface).
- An interface's `companion object` members (functions, `val`, `const val`) are not bound: the C#
  interface has no statics. Each is skipped with a named warning; move it to a top-level
  declaration or an `object`.
- Object identity is not preserved across two reads of a **Kotlin-backed** interface property: each
  read is a distinct C# wrapper over the same Kotlin object. A stored **C#-implemented** object is
  the exception: it always resolves back to the original instance.
- A sealed interface with an arm that no route can construct (a bare `object`, an `enum class`
  beside arms with a superclass, a sub-interface, an abstract or generic arm, and the other rows of
  [the arm table](#sealed-interface-over-arms)) has no binding at all: every function, property, or
  parameter typed with it is skipped, named.
- An `enum class` arm's boxed constructor and `Value` getter cost a handle and a P/Invoke each; see
  [An `enum class` arm](#an-enum-class-arm) for the disposal obligation and the missing implicit
  conversion.
- An `object` arm, or a `class`-kind arm whose every constructor is refused, has only the
  `internal` handle constructor, so `new Base.Arm(9)` fails to compile rather than binding it;
  obtain the arm from a factory or from the base's `FromHandle` discriminator instead. A
  `class`-kind arm with bridgeable constructor parameters exports a real public constructor
  instead; see [Sealed classes and interfaces](#sealed-classes-and-interfaces).
- A generic sealed hierarchy skips a few uses, named: a use-site projection (`Outcome<*>`), an
  argument the bridge cannot read, `Nothing` under a constraint, and an arm parameter the base never
  sees. See [A generic sealed hierarchy](#generic-sealed-hierarchy).
- A `suspend` lambda parameter (`suspend (T) -> R`) on a sealed arm has no binding, the same as on
  an ordinary class. A generic method on an arm or a sealed base binds as a C# generic method; see
  [Generic methods on a class](generics.md#generic-methods).
- A member whose C# name equals a type nested in the sealed base, or another arm's name
  (`backing()` beside a nested `class Backing`), is a named `ERROR_CSHARP_NAME_COLLISION` at the
  arm. So is a member named like its own type (`OnTap.OnTap`, `Cat.cat`) on a class, a sealed base
  or an arm; rename one of them. A Kotlin `object` or interface member named like its own type is
  not checked, so it surfaces as a C# error in your build instead.

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="data-classes.md">Data classes</a>
        <a href="lambdas-and-callbacks.md">Lambdas and callbacks</a>
        <a href="coroutines-and-flow.md">Coroutines and Flow</a>
    </category>
</seealso>
