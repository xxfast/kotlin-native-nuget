# Data classes

A Kotlin `data class` becomes a regular C# `class`, not a `record class`. Its `Equals`,
`GetHashCode`, `ToString`, and `Copy` behave exactly as Kotlin's `data class` does. Kotlin's own
`componentN()` destructuring functions are never exported, including on a `Flow`/`StateFlow`-typed
constructor parameter.

For this declaration in `Toy.kt`:

```kotlin
data class Toy(
  val name: String,
  val color: String,
)
```

```C#
using var toy1 = new Toy("Mouse", "Gray");
using var toy2 = new Toy("Mouse", "Gray");

bool equal = toy1.Equals(toy2);   // true
string text = toy1.ToString();    // "Toy(name=Mouse, color=Gray)"

using var copy = toy1.Copy("Ball", "Red");
string name = copy.Name;          // "Ball"; toy1.Name is still "Mouse"
```

`==` compares references, not values; use `Equals` for Kotlin's structural equality. `Copy`
returns a new, independently disposable `Toy` and takes every constructor parameter, since C#
cannot replicate Kotlin's default-to-current-value parameters on `copy()`. There is no
`with`-expression; call `Copy(...)` with the full argument list instead.

Data classes inside a sealed hierarchy get the same treatment; see
[Interfaces, abstract and sealed classes](interfaces-abstract-sealed.md).

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="value-classes.md">Value classes</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/008-data-class-mapping.md">ADR-008: Data class mapping</a>
    </category>
</seealso>
