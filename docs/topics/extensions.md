# Extensions

An extension function renders as a genuine C# extension method (`this` parameter). An extension
property renders as an ordinary static method with a `Get` prefix, since C# has no extension-property
syntax: call it like a method, not like a property. Both are grouped by receiver into a
`{Receiver}Extensions` static class, alongside any other top-level declarations
(see [Top-level declarations](top-level-declarations.md)).

From `test-library/src/nativeMain/kotlin/.../cat/CatExtensions.kt` and `StringExtensions.kt`:

```kotlin
fun Cat.sayName(): String = "My name is ${this.name}"
val Cat.isKitten: Boolean get() = lives > 7

fun String.meowify(): String = "$this meow!"
```

```C#
using var cat = new Cat("Oreo", 9);
cat.SayName();       // "My name is Oreo"
cat.GetIsKitten();    // extension property, called as a method
"Oreo".Meowify();
```

## Where the generated class lands

An extension on an **exported** receiver (a class this library publishes, e.g. `Cat`) lands in that
receiver's own namespace (`TestLibrary.Cat.CatExtensions`). An extension on an **unexported**
receiver (`String`, a primitive, any stdlib type) has no such home, so it lands in the namespace of
the package that *declares* the extension instead. Extensions on the same unexported receiver
declared in the same package share one class; a different package gets its own class in its own
namespace, and never merges with the first.

Extension-method call syntax (`"Oreo".Meowify()`) compiles wherever the receiver's extensions land,
as long as the calling file has that namespace in scope via `using`. Only a fully-qualified static
call pins the namespace at compile time:

```C#
TestLibrary.StringExtensions.Meowify("Oreo");
TestLibrary.Reserved.StringExtensions.Tag("Oreo", "Mylo"); // a different package's own String extension
```

## Supported receivers

| Kotlin receiver | Extension function | Extension property |
|---|---|---|
| Exported class, nested class, or eligible sealed base | yes | yes |
| Bare interface | yes | yes |
| Nullable class or nullable interface | yes | yes |
| `String`, a primitive | yes | yes |
| Value class over `String`, a primitive, an enum, or an object handle | yes | yes |
| Nullable value class over `String` or an object handle | yes | no |
| Bare enum, `Uuid`, `Instant`, `Duration` | yes | no |
| `Int?`, `Enum?`, `Instant?`, `Duration?`, or a nullable primitive/enum-underlying value class | no | no |
| Collection, generic type, unexported (non-stdlib) type | no | no |

A receiver in a "no" cell is dropped with a named diagnostic
(`SKIPPED_UNSUPPORTED_PROPERTY` for a property, `RECEIVER_FAN_OUT` for the fan-out function case).
Declare a top-level function taking the value as a parameter instead
(see [Publishing Kotlin to C#: Diagnostics](forward-overview.md#diagnostics)).
An extension property typed `Flow`, `StateFlow`, or a lambda is skipped the same way, even on an
otherwise-supported receiver.

`Instant`, `Duration`, and `Uuid` (mapped to `DateTimeOffset`, `TimeSpan`, and `Guid`, see
[Primitives and strings](primitives-and-strings.md#instant)) work as extension-function receivers,
not extension-property receivers. A nullable `Instant`, `Duration`, or primitive/enum-underlying
value class only works as a function *parameter*, never as a receiver (see the table above).

### Nullable receivers

An extension function on a nullable receiver (`Cat?`) is still a genuine C# extension method, so
calling it on a null reference is legal and reaches Kotlin: extension methods dispatch statically,
there is no `NullReferenceException`.

```kotlin
fun Cat?.nameOrStray(): String = this?.name ?: "stray"
```

```C#
Cat? none = null;
none.NameOrStray(); // "stray"
```

An extension property behaves the same way (`none.GetNameOrStray()`).

### Interface receivers {id="interface-receivers"}

An interface receiver works whether the concrete instance is Kotlin-backed or implemented in C#, the
same as an interface *parameter*: the plugin dispatches back to whichever side actually implements
the members, so a C# `Dog : IPet` behaves correctly without anything extra on the caller's part.

```kotlin
fun Pet.describe(): String = "$name has $legs legs and says ${speak()}"
```

```C#
using var oreo = new Cat("Oreo", 9);
oreo.Describe(); // "Oreo has 4 legs and says Meow! My name is Oreo"

using IPet rex = new Dog("Rex");
rex.Describe(); // "Rex has 4 legs and says Woof!", dispatched back into C#
```

#### On an extension property {id="interface-receiver-property"}

An extension property's receiver may be a bare interface too, with the same dispatch behavior:

```kotlin
val Pet.summary: String get() = "$name/$legs/${speak()}"
```

```C#
rex.GetSummary(); // "Rex/4/Woof!"
```

### Sealed receivers {id="sealed-receivers"}

An extension function's or property's receiver may be an eligible sealed base (see
[Sealed interfaces](interfaces-abstract-sealed.md#sealed-interfaces)). The extension binds on the
abstract base and every arm inherits it, so you don't need to `when`-match the arm just to call it:

```kotlin
fun Issue54Shape.footprint(): String = when (this) {
  Issue54Shape.Empty -> "empty"
  is Issue54Shape.Circle -> "circle r=$radius"
}
```

```C#
using Issue54Shape shape = drawing.Shape;
shape.Footprint(); // works on the base type, no cast needed
```

### Value-class receivers

A value class works as a receiver over any of its four supported underlyings (`String`, a
primitive, an enum, or an object handle; see
[Value classes](value-classes.md#as-an-extension-receiver)):

```kotlin
fun Temperament.escalate(): Temperament = when (mood) {
  Mood.CALM -> Temperament(Mood.ANXIOUS)
  Mood.ANXIOUS -> Temperament(Mood.PLAYFUL)
  Mood.PLAYFUL -> Temperament(Mood.PLAYFUL)
}
```

A `String`- or object-handle-underlying value class receiver may also be nullable:

```kotlin
fun CatId?.orAnonymous(): String = this?.id ?: "anonymous"
```

`CatId` generates as a `readonly record struct`, so the receiver is `CatId?`
(`Nullable<CatId>`). C# only binds an extension receiver through an identity, implicit-reference, or
boxing conversion, and `CatId -> CatId?` is none of those, so calling the extension on a bare
`CatId` fails to compile (`CS1929`):

```C#
CatId? id = new CatId("Oreo-1");
id.OrAnonymous();          // compiles: "Oreo-1"

new CatId("Oreo-1").OrAnonymous(); // CS1929: call it on a CatId? variable instead
```

### Nested receivers

A nested class works as a receiver too, binding under its own owner chain the same way a member of
that class does (see [Nested types](classes-and-objects.md#nested-classes-and-objects)). The
generated class stays at namespace level rather than nesting (`AviaryPerchExtensions`, not
`Aviary.PerchExtensions`), since C# forbids nesting an extension class; extension-call syntax is
unaffected:

```kotlin
fun Aviary.Perch.summarize(): String = "perch@$height (ext)"
```

```C#
using var perch = aviary.PerchAt(9);
perch.Summarize(); // "perch@9 (ext)"
```

The same expansion applies when the receiver is spelled through a `typealias` of a nested type
(`typealias Bird = Aviary.Bird; fun Bird.sing()`): the generated class is `AviaryBirdExtensions`,
not `BirdExtensions`, so it never collides with an unrelated top-level `Bird`'s own extensions. This
only changes the internal entry point the generated `Sing` method calls into, not the C# surface;
since the C# shim and native library always ship together in one package, upgrading the plugin
changes nothing a consumer needs to do.

## Overloads and default parameters {id="method-overloads"}

Two or more same-named extensions on the same receiver render as one ordinary C# overload set,
resolved the normal C# way by parameter types:

```C#
public static string Pat(this Mitten receiver);
public static string Pat(this Mitten receiver, string style);
```

A trailing run of defaulted parameters synthesizes the same omitting overloads a class method or
top-level function gets. The receiver itself is never part of that truncation, so it is always
present, even when every declared parameter is defaulted:

```kotlin
fun Paw.knead(times: Int = 2, surface: String = "blanket"): String =
  "$name kneads the $surface $times times"
```

```C#
paw.Knead();               // "Oreo kneads the blanket 2 times"
paw.Knead(3);
paw.Knead(3, "sofa");
```

A trailing default the bridge cannot carry costs only that arity.

```kotlin
fun Logger.call(level: Int = 0, events: Flow<Int>? = null): String =
  "$tag calls $level/${events?.toString() ?: "-"}"
```

```C#
using var logger = new Logger("Mylo");
logger.Call();
logger.Call(3);
```

The events arity does not exist. See
[Method default parameters](classes-and-objects.md#method-default-parameters).

## Return values

An extension function's return goes through the same marshalling as a class method's (see
[Classes and objects](classes-and-objects.md)): converted objects, nullable objects, collections,
and nullable primitives all follow the same rules. A returned object still needs disposal the same
way any other returned handle does.

<seealso>
    <category ref="related">
        <a href="top-level-declarations.md">Top-level declarations</a>
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="value-classes.md">Value classes</a>
        <a href="interfaces-abstract-sealed.md">Interfaces, abstract classes, and sealed classes</a>
        <a href="primitives-and-strings.md">Primitives and strings</a>
    </category>
</seealso>
