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
same as any other unconstrained C# generic.

```kotlin
class Slot<T>(val value: T) {
  val previous: T? = null
  val current: T? = value
}
```

```C#
using var stringSlot = new Slot<string>("hi");
Assert.Null(stringSlot.Previous);

using var intSlot = new Slot<int>(42);
Assert.Equal(0, intSlot.Previous); // default(int), not null
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
instantiation borrows the argument's own live handle and mints nothing on the way in.

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

This row only binds for a **top-level** function with a `T`-typed direct parameter
(`fun <T> f(value: T): T`). A generic function declared on a class, `object`, or interface, or a
top-level one with no `T`-typed parameter (e.g. `fun <T> f(): List<T>`), is not generated.

## Type aliases

A `typealias` erases to its underlying type; there is no separate alias type in the generated C#.

```kotlin
typealias Score = Int
typealias CatNames = List<String>

fun topScore(): Score = 10
fun defaultNames(): CatNames = listOf("Oreo", "Mylo")
```

```C#
int score = TypeAliases.TopScore(); // Score is just Int
IReadOnlyList<string> names = TypeAliases.DefaultNames();
```

Erasure applies to an extension's receiver too, including a nested-type alias
(see [Extensions: Nested receivers](extensions.md#nested-receivers)).

## Limitations

A generic class declared in a dependency module and reachable through the
[export closure](nuget-dsl.md) has never been exercised across a module boundary; if you hit this,
declare the generic class in the publishing module itself instead.

<seealso>
    <category ref="related">
        <a href="collections.md">Collections</a>
        <a href="value-classes.md">Value classes</a>
        <a href="nuget-dsl.md">The nuget {} DSL</a>
        <a href="expect-actual.md">expect/actual declarations</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/147-generic-class-methods.md">ADR-147: Generic class methods</a>
    </category>
</seealso>
