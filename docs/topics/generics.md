# Generics

Generic Kotlin classes and functions become generic C# classes and methods, keeping the same type
parameter, constraints, and variance.

```kotlin
class Box<T>(val value: T) {
  init {
    require(value.toString().isNotEmpty()) { "Box cannot hold a blank value" }
  }
}
```

```C#
using var box = new Box<Cat>(oreo);
Cat cat = box.Value;
```

## Type mappings

| Kotlin | C# |
|---|---|
| `class<T>` | `class<T>` |
| `<T : Bound>` | `where T : Bound` |
| `out T` / `in T` on an interface | `out T` / `in T` |
| `fun <T> f(value: T): T` (top-level) | a generic method |
| `inline fun` | an ordinary method |
| `inline fun <reified T>` | an ordinary generic method |
| `typealias` | the aliased type itself, no separate alias type |

## Nullable properties

A nullable property on a generic class (`val x: T?`) is `T?` in C#. At a reference-type
instantiation a `null` read stays `null`; at a value-type instantiation it collapses to `default(T)`,
same as any other unconstrained C# generic. A property that doesn't mention `T` keeps its own
declared type instead, at every instantiation:

```kotlin
class Slot<T>(val value: T) {
  val previous: T? = null
  val current: T? = value

  val label: String = "window sill"
  val count: Int = 2
  var note: String = "Oreo napped here"
  val keeper: Cat = Cat("Mylo", 7)
}
```

```C#
using var stringSlot = new Slot<string>("hi");
Assert.Null(stringSlot.Previous);

using var intSlot = new Slot<int>(42);
Assert.Equal(0, intSlot.Previous); // default(int), not null

string label = intSlot.Label;         // string, not T, even though T is int here
using Cat keeper = intSlot.Keeper;    // Cat, not T
intSlot.Note = "Mylo stole the spot";
```

## Nullable type arguments

An unconstrained `T` (no bound, so its implicit upper bound is `Any?`) accepts a `null` argument at
a constructor, method parameter, or return, and reads back `null`, the same way any other nullable
handle position does:

```C#
using var bowl = new Box<string?>(null);
Assert.Null(bowl.Value);

using var crate = new Crate<string?>("tuna");
Assert.Null(crate.Pick(null));
Assert.Equal("null:tuna", crate.Describe(null));
```

A bound rules this out. `class Tin<T : Any>(val value: T)` renders `where T : notnull`, so a
nullable type argument is a compile-time warning (`CS8714`) in a nullable-enabled consumer, on top
of the runtime failure a `null` argument would still hit on the Kotlin side. A nullable bound
(`T : Pet?`) keeps its `?` and behaves like the unconstrained case.

```C#
public class Tin<T> : IDisposable, INugetHandle where T : notnull
```

The same rule applies to a [top-level generic function](#generic-functions):
`Helpers.Identity<int?>(null)` and `fun <T : Any> handBack(treat: T): T` behave the same way as
their generic-class counterparts.

If a generic class's property name collides with one of its own type parameter names once both are
PascalCased (`class Duo<A, B>(val a: A, val b: B)`, where property `A` and type parameter `A` would
both render `A`), the type parameter is renamed in C# (`A` becomes `TA`, `B` becomes `TB`); the
property keeps its ordinary name. A diagnostic about the type parameter still names it the way you
wrote it in Kotlin.

```C#
public class Duo<TA, TB> : IDisposable, INugetHandle
{
    public Duo(TA a, TB b) { /* ... */ }
    public TA A { get; }
    public TB B { get; }
}
```

## Constraints

A bound (`<T : Pet>`) becomes a C# `where T : ...` clause. Any type assignable to the bound works
as the type argument:

```kotlin
class PetBox<T : Pet>(val value: T) {
  init {
    require(value.name.isNotBlank()) { "PetBox needs a named pet" }
  }
}
```

```C#
using var oreo = new Cat("Oreo", 9);
using var box = new PetBox<Cat>(oreo);
```

A bound declared in the Kotlin standard library (`Comparable<T>`, `Number`, `CharSequence`) has no
C# equivalent, so it is dropped from the `where` clause and the build reports an
`INFO_DROPPED_BOUND` note. A non-null bound keeps `where T : notnull`. C# can then pass a type
argument Kotlin would reject, which Kotlin refuses when it reads the argument, before the function
body or constructor runs. The call throws `KotlinInvalidCastException`.

```kotlin
class Ranked<T : Comparable<T>>(val value: T) {
  fun outranks(other: T): Boolean = value > other
}
```

```C#
using var oreo = new Ranked<int>(9);
Assert.True(oreo.Outranks(4));   // public class Ranked<T> ... where T : notnull
```

A parameter with several bounds lists every exportable one in the `where` clause, on a class and on
a function alike. The stdlib bounds are dropped as above, so `where T : Comparable<T>, T : Pet`
renders `where T : IPet`.

```kotlin
class Arena<T>(val champion: T) where T : Pet, T : Trainable {
  fun challenge(challenger: T): T =
    if (challenger.tricks > champion.tricks) challenger else champion
}
```

```C#
using var oreo = new Performer("Oreo", 3);        // Performer is both a Pet and a Trainable
using var arena = new Arena<Performer>(oreo);     // where T : IPet, ITrainable
```

### A generic bound from another package {id="a-generic-bound-from-another-package"}

When the bound is declared in a different Kotlin package than the generic class itself, the
generated `where` clause spells it fully qualified (`where T : global::TestLibrary.Cat.IPet`)
instead of a bare name. This only matters if you inspect the constraint through reflection; calling
the class works the same either way.

## Variance

`out T` / `in T` on an interface carries straight through to the C# interface:

```kotlin
interface Readable<out T> {
    fun read(): T
}

interface Writable<in T> {
    fun write(value: T)
}
```

```C#
// IReadable<Cat> can be assigned to IReadable<IPet> because T is covariant
Assert.True(typeof(IReadable<IPet>).IsAssignableFrom(typeof(IReadable<Cat>)));
```

Variance declared on a **class's** own type parameter, as opposed to an interface's, is dropped: C#
does not support variance on classes. The class still generates and works, just without `out`/`in`
on its type parameter.

## Methods on a generic class

A public method declared on a generic class binds as an instance method on the C# generic carrier,
`T` positions included, the same [ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)
plan an ordinary class's methods bind on:

```kotlin
open class Crate<T>(val item: T) {
  fun describe(tag: T): String = "$tag:$item"
  fun pick(other: T): T = other
  fun label(prefix: String, count: Int): String = "$prefix-$count:$item"
}
```

```C#
using var crate = new Crate<int>(3);
Assert.Equal("7:3", crate.Describe(7));
Assert.Equal(7, crate.Pick(7));
Assert.Equal("lot-2:3", crate.Label("lot", 2));
```

A position that never mentions `T` (`Label` above) binds exactly as it would on a non-generic
class. A position that does (`Describe`, `Pick`) crosses as the same boxed handle a `T`-typed
property already uses: a value type pays one box mint and dispose per call, an exported class
instantiation borrows the argument's own live handle and mints nothing on the way in, and a
[value class](value-classes.md#at-an-erased-generic-position) mints and disposes a box through its
own box/unbox pair, running `init` at the boundary. A value class with no such pair (a nullable
underlying, a generic value class, or an ineligible sealed interface) still compiles at `T` but
throws `NotSupportedException` at the call, since an open C# generic has no build-time way to
refuse it.

### An interface at an erased position {id="an-interface-at-an-erased-position"}

`T` bound to an exported interface (`PetBox<T : Pet>`, or the unconstrained `T` above) accepts your
own C# implementation and hands back the SAME instance, not a fresh Kotlin-backed wrapper. A
Kotlin-backed value read at `T = IPet` still materializes as `IPet`, through a factory the
interface itself now carries:

```C#
sealed class Dog(string name) : IPet { /* ... */ }

using IPet rex = new Dog("Rex");
using var box = new PetBox<IPet>(rex);
Assert.Same(rex, box.Value);

using var oreo = new Cat("Oreo", 9);
using var oreoBox = new PetBox<IPet>(oreo);
IPet boxed = oreoBox.Value; // a fresh IPet wrapper over the Kotlin cat, not the same kind of "same instance"
```

Each crossing mints a bridge handle for your C# object (freed after the call) or reads one back by
token (freed by the read); see [Implementing a Kotlin interface in
C#](interfaces-abstract-sealed.md#implementing-a-kotlin-interface-in-c). `T` bound to something
that isn't your own C# type and also isn't a Kotlin-backed instance Kotlin can reconstruct (a
different implementation entirely) throws `InvalidCastException`; a `T = Dog` read of a
Kotlin-backed value that isn't a `Dog` throws `NotSupportedException` naming the missing factory,
inherent to erasure. The same identity rule applies to a lambda argument
(see [Lambdas and callbacks](lambdas-and-callbacks.md)) and to the legacy [generic
functions](#generic-functions) below.

`T` is admitted only at a top-level position: a parameter, a return, a constructor parameter, or a
property getter, optionally nullable. It is refused, named, everywhere else: nested in a
collection or lambda (`List<T>`, `(T) -> Unit`, `Flow<T>`), a `var` property's setter
(`var item: T` renders a get-only `T Item`), a `suspend fun`, a `Flow`/`StateFlow` member, a
stored-callback member, and the method's own type parameter (`fun <R> map(f: (T) -> R): R`). A
type parameter declared on an `interface` rather than a `class` is unaffected by this and keeps
its own, unrelated named refusal.

## Subclassing a generic base

A class extending an exported generic base spells the closed type argument, and inherits members,
including methods, from that closed base:

```kotlin
open class Parcel<T>(val value: T)

class NamedParcel(name: String) : Parcel<String>(name)

class LabelledCrate(item: String) : Crate<String>(item) {
  fun describe(tag: Int): String = "#$tag:$item"
}
```

```C#
using var parcel = new NamedParcel("Oreo");
Assert.Equal("Oreo", parcel.Value); // inherited from Parcel<string>
Assert.IsAssignableFrom<Parcel<string>>(parcel);

var labelled = new LabelledCrate("apple");
Assert.Equal("#7:apple", labelled.Describe(7));        // its own declared overload
Assert.Equal("ripe:apple", labelled.Describe("ripe"));  // inherited from Crate<string>
```

Because `Parcel` and `Crate` are declared `open`, their generated `Dispose()` is `virtual` so a
subclass can override it. A subclass declaring its own overload of a name it also inherits from
the generic base keeps exactly its own declared member; the base's substituted overload is not
re-declared alongside it, and C# overload resolution then picks between the two exactly as Kotlin
does. Generic **subclasses** (`class Sub<T> : Base<T>(...)`) are not supported yet; declare the
member directly on the closed subclass instead.

## Returning an instantiated generic class

A top-level function returning a generic class instantiated with a primitive, `String`, or an
exported class, object, or enum binds normally, qualified the same way any other cross-namespace
return is:

```kotlin
class Crate<T>(val item: T)

fun crateOfInt(): Crate<Int> = Crate(1)
fun crateOfSnapshot(): Crate<Snapshot> = Crate(Snapshot("Oreo"))
```

```C#
Crate<int> crate = Crates.CrateOfInt();
Crate<Snapshot> snapshotCrate = Crates.CrateOfSnapshot(); // qualified: Crate<global::...Snapshot>
```

If the type argument is something this route can't spell — a collection, another generic class,
`Flow`, a lambda, `Any`, or `ByteArray` — or the outer type is itself not an exported generic class
(`Pair<Int, Int>`), the function is skipped instead of generating C# that fails to compile:

```kotlin
fun crateOfList(): Crate<List<Int>> = Crate(listOf(1)) // skipped, named
fun pairOf(): Pair<Int, Int> = 1 to 2                  // skipped: Pair isn't declared in C#
```

```
[nuget:SKIPPED_UNSUPPORTED_RETURN] Skipping crateOfList(): its type argument
   `kotlin.collections.List` has no C# spelling on a generic return: an argument must be a
   primitive, String, or an exported class, object, enum or interface, and a type carrying its own
   type arguments (a collection, a generic class, Flow, a lambda) has none
```

A **nullable** generic-class return (`fun f(): Crate<Int>?`) is refused the same way, named, even
when the non-null form (`Crate<Int>`) would bind fine; return the non-null form, or wrap it in your
own non-generic class if `null` needs to be expressible.

## Generic functions

```kotlin
fun <T> identity(value: T): T = value

fun <T> wrapInBox(value: T): Box<T> = Box(value)

fun <T : Pet> adoptPet(pet: T): T = pet

inline fun <reified T : Pet> groomPet(pet: T): T = pet
```

```C#
using Box<int> box = Helpers.WrapInBox<int>(99);
Assert.Equal(99, box.Value);
```

A constrained generic function carries the same `where` clause as a constrained class. `reified`
and non-reified `inline fun` (like a plain `square(x: Int)`) both generate as an ordinary method or
generic method; inlining and reification only matter inside Kotlin and don't change the C# side.

An unconstrained `T` bound to an exported interface gets the same identity treatment as the
[generic-class case above](#an-interface-at-an-erased-position): the object arm now writes through
the same shared marshalling helper every other erased route uses, so it also picks up boxed value
classes for free.

```C#
using IPet rex = new Dog("Rex");
Assert.Same(rex, Helpers.AdoptPet<IPet>(rex));       // fun <T : Pet> adoptPet(pet: T): T
Assert.Same(rex, PetRelayKt.RelayPet<IPet>(rex));    // fun <T> relayPet(value: T): T, unconstrained
```

This row only binds for a **top-level** function with a `T`-typed direct parameter
(`fun <T> f(value: T): T`). A generic function declared on a class, `object`, or interface, or a
top-level one with no `T`-typed parameter (e.g. `fun <T> f(): List<T>`), is not generated.

A C# builtin (`int`, `double`, `string`, `short`, ...) works as `T`, with a stdlib bound or none.
A bound C# cannot name is dropped, so a type argument outside it compiles and throws
`KotlinInvalidCastException` at the call:

```kotlin
fun <T : Number> weigh(value: T): T = value
```

```C#
Assert.Equal(4, Treats.Weigh(4));
Assert.Equal(2.5, Treats.Weigh(2.5));
Assert.Throws<KotlinInvalidCastException>(() => Treats.Weigh(3u));   // uint is not a Kotlin Number
```

## Type aliases

A `typealias` erases to its underlying type; there is no separate alias type in the generated C#.
This holds on every route, including a use-site `?` on the alias itself and a suspend or `Flow`
member, so an aliased type behaves exactly like the written-out type would, refusals included:

```kotlin
typealias Score = Int
typealias PetName = String
typealias Box<T> = List<T>

fun topScore(): Score = 10
suspend fun greetLater(name: PetName?): PetName? { /* ... */ }
fun boxedTallies(tallies: Box<Int>): Box<Int> = tallies.map { it * 2 }
```

```C#
int score = TypeAliases.TopScore(); // Score is just Int
string? greeting = await TypeAliases.GreetLaterAsync(null); // PetName? is just string?
Assert.Null(greeting);
IReadOnlyList<int> doubled = TypeAliases.BoxedTallies([2, 3]); // Box<Int> is just List<Int>
```

A generic alias substitutes its type parameter only when the parameter is one of the RHS's own
top-level type arguments, as `Box<T>` above does. A parameter nested deeper in the RHS
(`typealias Pages<T> = List<Map<String, T>>`) is not substituted; a member using it is skipped with
a named warning instead of generating a wrong binding. Write the type out at that position if you
need it exported.

Erasure applies to an extension's receiver too, including a nested-type alias
(see [Extensions: Nested receivers](extensions.md#nested-receivers)).

## Limitations

A generic class declared in a dependency module is exported like a module-local one once the
[export closure](nuget-dsl.md) reaches it, with `Int` and `String` type arguments checked. Other
type arguments on a dependency generic class are not checked; if one fails, declare the class in the
publishing module itself.

A `T?` written at a generic function's return (`fun <T> f(item: T): Crate<T?>`) is spelled `T` in
C#, so the type argument you choose decides whether `null` can be held.

A generic class binds its ordinary members, including a member that takes a lambda
(`box.Measure(n => n * 10)`). It does not bind `suspend` members, `Flow`-returning members, stored
callback or listener pairs, or lambda members with a `Char` or `T` payload. Each is skipped with a
named `SKIPPED_UNSUPPORTED_COMBINATION` diagnostic; move it onto a non-generic class that wraps the
generic one.

A type nested inside a generic class is not bound, in a dependency or otherwise. If your API returns
one, the member is skipped with a named diagnostic, but the generic owner is still exported.

These bounds do not generate compilable code yet: `T : Enum<T>`, a self-referencing bound on an
invariant type (`T : Node<T>`), and a bound on a generic interface (`T : Rival<T>`), whose C#
`where` clause drops the type arguments and fails with CS0305.

<seealso>
    <category ref="related">
        <a href="collections.md">Collections</a>
        <a href="value-classes.md">Value classes</a>
        <a href="nuget-dsl.md">The nuget {} DSL</a>
        <a href="expect-actual.md">expect/actual declarations</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/147-generic-class-methods.md">ADR-147: Generic class methods</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/171-value-classes-at-erased-generic-positions.md">ADR-171: Value classes at erased generic positions</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/173-erased-generic-routes-carry-csharp-interface-identity.md">ADR-173: Erased generic routes carry C# interface identity</a>
    </category>
</seealso>
