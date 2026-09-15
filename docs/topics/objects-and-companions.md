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
        with <code>ERROR_CSHARP_SIGNATURE_COLLISION</code>; rename one of them.
    </p>
</note>

## Method default parameters

A trailing run of defaulted parameters on an `object` or companion member generates one overload
per omitted trailing default, each using the Kotlin default for the parameters it drops:

```kotlin
object Kibble {
  fun scoop(flavour: String, scoops: Int = 2): String = "$scoops scoops of $flavour"
}
```

```C#
string oneFlavour = Kibble.Scoop("tuna"); // "2 scoops of tuna"; scoops defaults to 2
```

Only a *trailing* run of defaults gets an omitting overload: a defaulted parameter followed by a
non-defaulted one does not, so pass it explicitly. A trailing default the bridge cannot carry
costs only that arity.

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

The events arity does not exist. See
[Method default parameters](classes-and-objects.md#method-default-parameters) for the full rule.
