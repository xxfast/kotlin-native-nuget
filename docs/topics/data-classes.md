# Data classes

A Kotlin `data class` that only holds plain data crosses to C# as a value: a
`sealed class X : IEquatable<X>` with get-only properties, no handle and nothing to dispose. Every
other data class is an ordinary [disposable class](classes-and-objects.md). Either way, `Equals`,
`GetHashCode`, `ToString` and `Copy` run Kotlin's code, and Kotlin's `componentN()` functions are not
exported.

## Data classes as values {id="data-classes-as-values"}

```kotlin
enum class Mood { Happy, Sleepy }

data class Plushie(
  val name: String,
  val colors: List<String>,
  val mood: Mood,
  val squeaks: Int,
) {
  init {
    require(name.isNotBlank()) { "a plushie needs a name" }
  }

  val label: String get() = "$name (${colors.joinToString("/")})"

  fun describe(loud: Boolean): String = if (loud) "${name.uppercase()}!" else name
}

fun favouritePlushie(): Plushie = Plushie("Bun", listOf("pink", "white"), Mood.Happy, 3)
```

```C#
Plushie bun = PlushieSample.FavouritePlushie();   // no using, no Dispose

string name = bun.Name;                           // "Bun"
string label = bun.Label;                         // "Bun (pink/white)", Kotlin's getter
string loud = bun.Describe(loud: true);           // "BUN!"
string text = bun.ToString();                     // "Plushie(name=Bun, colors=[pink, white], mood=Happy, squeaks=3)"

bool same = bun == new Plushie("Bun", ["pink", "white"], Mood.Happy, 3);   // true, Kotlin's equals

Plushie louder = bun.Copy(squeaks: 4);            // every other component is bun's
new Plushie(" ", ["grey"], Mood.Happy, 0);        // throws KotlinArgumentException from init
```

- The components are a snapshot held in C#, so reading one does not cross the bridge. The
  constructor copies a collection you pass, so changing your array afterwards does not change the
  value.
- Every Kotlin-defined member (a computed property, a method, `Equals`, `GetHashCode`, `ToString`,
  `Copy`) builds a temporary Kotlin instance from the components, calls it, and releases it. Each
  call crosses the bridge and sends every component again, a large list included. Read a computed
  member once into a local when you need it repeatedly, and expect a value used as a dictionary or
  set key to cross on every lookup.
- Kotlin's `init` runs again for each of those calls and each time you pass the value to Kotlin.
  A `require` just passes again, but a side effect in `init` (a counter, a log line) repeats.
- `==` and `!=` call `Equals`. Two reads of the same Kotlin value are two C# objects, so do not
  compare them with `ReferenceEquals`.
- A constructor or `Copy` with [default parameters](classes-and-objects.md#constructor-and-method-default-parameters)
  holds what Kotlin evaluated. `new Config()` reads back the `Uuid.random()` Kotlin generated for
  its `Id`, and that `Id` stays the same on every read.
- A value works at every position: parameter, return, nullable, `List` element, `Map` value,
  `suspend` result, `Flow` element, lambda payload and result, a property of an ordinary class, and
  an erased `T`. A returned `List` of values is a plain `IReadOnlyList<T>` with nothing to release.
- Its own `suspend` and `Flow` members have no owner `Dispose` to cancel them: cancel a `suspend`
  call through its `CancellationToken` and a collection through its enumerator; see
  [Coroutines and Flow](coroutines-and-flow.md#suspend-static-owners).

### When a data class is a value {id="when-a-data-class-is-a-value"}

A data class crosses as a value when all of these hold:

- It is declared in your module. A data class from a dependency module stays a handle.
- It is not an `expect` class, and neither its constructor nor a component carries an opt-in marker.
- Its public primary constructor takes only public `val` parameters, each a primitive, `Char`, `String`,
  an exported enum, `Instant`, `Duration`, `Uuid`, a non-generic value class over one of these,
  another data class that is a value, a nullable of any of these, or a read-only `List`, `Set` or
  `Map` of them.
- Its body stores nothing: only getter-only computed properties and methods. A body `val` with an
  initializer, a `by lazy` or other delegated property, a `var` and a `lateinit var` each keep it a
  handle.
- It is not generic, not a sealed arm, not a `Throwable`, has no superclass and implements no
  exported interface.
- It does not refer to itself through its components (`data class Chain(val next: Chain?)`).

A `MutableList` or other mutable collection, a `ByteArray`, an ordinary class, an interface, a lambda
or a `Flow` component keeps the class a handle. There is no switch to opt a qualifying class out.

### Migrating from a disposable data class {id="migrating-to-data-class-values"}

A data class that now qualifies used to be an `IDisposable` handle class. Code written against it
changes in these ways:

- `using var toy = new Toy(...)` no longer compiles (CS1674), and `toy.Dispose()` is gone. Drop both.
- `==` and `!=` compare with Kotlin's `equals` instead of by reference.
- The class is `sealed` and no longer implements `IDisposable`, so code that disposes whatever
  `IDisposable` it is handed no longer sees it.
- A consumer built against the previous package must be rebuilt.

## Handle-backed data classes {id="handle-backed-data-classes"}

Any other data class is an `IDisposable` class like the [classes](classes-and-objects.md) it sits
beside: every property read crosses to the Kotlin object, and you dispose it when you are done.
`Equals`, `GetHashCode` and `ToString` delegate to Kotlin, `Copy` takes the same optional named
arguments as on a value, and `==` compares references, so use `Equals` for Kotlin's structural
equality.

```kotlin
data class StampedPlushie(val name: String) {
  val stamp: String = "stamped-$name" // a body val with an initializer keeps this a handle
}
```

```C#
public class StampedPlushie : IDisposable, INugetHandle
```

There is no `with` expression on either kind; call `Copy(...)` with the components you want to
change. A data class arm of a sealed hierarchy is handle-backed; see
[Interfaces, abstract and sealed classes](interfaces-abstract-sealed.md). A data class that extends
`Throwable` keeps `Equals` and `GetHashCode`, but its `ToString()` is the Kotlin trace form, see
[Your own exception classes](exceptions.md#exported-exception-members).

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="value-classes.md">Value classes</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/242-scalar-data-classes-as-values.md">ADR-242: Data classes as values</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/008-data-class-mapping.md">ADR-008: Data class mapping</a>
    </category>
</seealso>
