# Objects and companions

A Kotlin `object` becomes a C# `static class`: no instance, no constructor, its members reached
directly through the type. A `companion object`'s members land as static members directly on the
enclosing C# class, not on a separate `Companion` type. A `data object` nested inside a
`sealed class` becomes a sealed subclass instead; see
[Interfaces, abstract and sealed classes](interfaces-abstract-sealed.md). An `object` can also own
a nested type the same way a `class` can; see
[Nested types](classes-and-objects.md#nested-classes-and-objects).

For this declaration in `CatRegistry.kt`:

```kotlin
object CatRegistry {
  private val cats: MutableList<String> = mutableListOf()

  fun register(name: String) {
    cats.add(name)
  }

  fun count(): Int = cats.size
}
```

Call it from C# through the type; there is nothing to construct:

```C#
CatRegistry.Register("Oreo");
CatRegistry.Register("Mylo");
int count = CatRegistry.Count(); // 2
```

Object methods are PascalCased and their returns marshalled exactly like class methods; see
[Classes and objects](classes-and-objects.md).

<note>
    <p>Naming an <code>object</code> member <code>All</code>, <code>Any</code>, <code>First</code>,
    or <code>Select</code> is legal Kotlin, but if that member ever fails to bind for an unrelated
    reason, C#'s implicit usings resolve the call site against a <code>System.Linq</code> extension
    method of the same name instead of leaving it unresolved. A consumer then sees a confusing
    <code>CS1501</code> ("no overload takes N arguments") rather than the <code>CS0117</code>
    ("does not contain a definition") that would point at the real cause.</p>
</note>

## Object properties

An `object`'s own `val`/`var` properties bind as static properties on its static class, at the same
type coverage a companion property already has: nullable primitives, enums, collections, and
handle-typed properties.

```kotlin
object TreatPantry : Stockroom("kitchen"), Labelled {
  const val CAPACITY: Int = 12
  override val label: String = "treats"
  var count: Int = 4
  val flavours: List<String> get() = listOf("tuna", "salmon")
  val favourite: Cat = Cat("Oreo", 9)
}

open class Stockroom(val origin: String) {
  fun restock(): Int = origin.length
}
```

```C#
int capacity = TreatPantry.Capacity;             // 12, a real C# const
TreatPantry.Count = 11;                          // process-global: there is one TreatPantry
int count = TreatPantry.Count;                   // 11

IReadOnlyList<string> flavours = TreatPantry.Flavours;

using Cat favourite = TreatPantry.Favourite;      // a fresh owned wrapper on every read

string origin = TreatPantry.Origin;               // inherited from Stockroom, flattened onto the static class
int restocked = TreatPantry.Restock();
```

A `const val` on an object or companion follows the same evaluated-constant rule as a top-level
one (see [Top-level declarations](top-level-declarations.md)): an expression over another const,
even one declared on a different owner, renders its evaluated value, not its source text:

```kotlin
object PantryTally {
  const val TRAILING: Int = 5
  const val REF: Int = TRAILING + 1
  const val DOUBLED: Int = TreatPantry.CAPACITY * 2
}
```

```C#
public const int Trailing = 5;
public const int Ref = 6;
public const int Doubled = 24;
```

A `val` renders get-only, a `var` renders `get`/`set`. A member the object only *inherits* — a base
class property or method it does not redeclare — is flattened directly onto the static class,
methods included: a C# static class cannot extend or implement anything, so an inherited member has
no other place to live. The relation itself is gone: `TreatPantry` cannot be used as a `Stockroom` or
an `ILabelled`, only through its own flattened members, and a `SKIPPED_UNEXPORTED_SUPERTYPE` warning
names each dropped supertype at build time (this fires for every declared supertype, whether or not
it is itself exported, because the C# shape has no base list to put it in).

Declaring a property and a function that render the same C# name on one object — `val count` beside
`fun count()`, or an inherited method colliding with a declared property — fails the build with
`ERROR_CSHARP_NAME_COLLISION` (CS0102), naming both Kotlin declarations; rename one of them. This is
the same shared guard as an ordinary class's (see
[Classes and objects](classes-and-objects.md#property-and-method-name-collisions)), which also
covers a companion member colliding with an instance member on the enclosing class, and, wherever a
real base class exists, a declared member that hides an inherited one of the other kind instead of
colliding with it directly.

An `Int?`-style nullable property round-trips its null branch the same way a class property does. A
`lateinit var` read before it is assigned surfaces in C# as a `KotlinException`, not a crash. A
`Flow`/`StateFlow`/lambda-typed object property has no static-owner adapter, so it generates no C#
member at all and is named with a `SKIPPED_UNSUPPORTED_PROPERTY` warning instead of vanishing
silently.

## Companion objects

A companion's members are static members on the enclosing class itself:

```kotlin
class Cat(
  name: String,
  val lives: Int = 9,
) : Animal(name) {
  companion object {
    const val SPECIES: String = "Felis catus"
    val defaultBreed: String = "Domestic Shorthair"
    fun fromName(name: String): Cat = Cat(name)
  }
}
```

```C#
string species = Cat.Species; // "Felis catus"
string breed = Cat.DefaultBreed; // "Domestic Shorthair"

using var cat = Cat.FromName("Whiskers");
```

There is no separate `Cat.Companion` class in the generated output.

## Method overloads {id="method-overloads"}

Two or more same-named members on an `object` or a `companion object` generate one natural C#
overload set, resolved by parameter type like any other C# overload, including an `Int` overload
beside an enum overload:

```kotlin
object Parlour {
  fun rate(stars: Int): String = "the parlour is rated $stars"

  fun rate(coat: Coat): String = "the parlour grooms ${coat.name.lowercase()} coats"
}
```

```C#
string byStars = Parlour.Rate(10); // "the parlour is rated 10"
string byCoat = Parlour.Rate(Coat.Tuxedo); // "the parlour grooms tuxedo coats"
```

See [Method overloads](classes-and-objects.md#method-overloads) in Classes and objects for the
same rule on class methods, top-level functions, and extension functions.

<note>
    <p>
        A companion static and an instance method on the same class share one generated C# class,
        and C# does not distinguish an overload by <code>static</code>-ness. Giving a companion
        member the same signature as an instance method on the enclosing class fails generation
        with <code>ERROR_CSHARP_SIGNATURE_COLLISION</code>; rename one of them. The same sharing
        applies to a companion <code>val</code>/<code>const val</code> against an instance property
        or method of the same C# name: <code>ERROR_CSHARP_NAME_COLLISION</code> (CS0102) fires
        there too.
    </p>
</note>

## Method default parameters

A defaulted parameter on an `object` or companion member widens to its nullable C# form, using
the Kotlin default when the caller passes `null`:

```kotlin
object Kibble {
  fun scoop(flavour: String, scoops: Int = 2): String = "$scoops scoops of $flavour"
}
```

```C#
string oneFlavour = Kibble.Scoop("tuna"); // "2 scoops of tuna"; scoops defaults to 2
```

A defaulted parameter followed by a non-defaulted one stays required-but-nullable instead of
optional, so pass it explicitly (`null` for it still means "use the Kotlin default"). A defaulted
parameter the bridge cannot route to any non-null C# form is dropped from the trailing signature
entirely.

```kotlin
object Switchboard {
  fun patch(level: Int = 0, events: Flow<Int>? = null): String =
    "patch $level/${events?.toString() ?: "-"}"
}
```

```C#
Switchboard.Patch();
Switchboard.Patch(3);
```

The `events` parameter does not exist in C#. See
[Constructor and method default parameters](classes-and-objects.md#constructor-and-method-default-parameters)
for the full rule.
