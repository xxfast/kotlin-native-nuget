# Extensions

An extension function renders as a genuine C# extension method (`this` parameter). An extension
property renders as a C# 14 extension property, so it reads (and, for a `var`, writes) like a
property. Both are grouped by receiver into a `{Receiver}Extensions` static class, alongside any
other top-level declarations (see [Top-level declarations](top-level-declarations.md)).

From `test-library/src/nativeMain/kotlin/.../cat/CatExtensions.kt` and `StringExtensions.kt`:

```kotlin
fun Cat.sayName(): String = "My name is ${this.name}"
val Cat.isKitten: Boolean get() = lives > 7

fun String.meowify(): String = "$this meow!"
```

```C#
using var cat = new Cat("Oreo", 9);
cat.SayName();       // "My name is Oreo"
var kitten = cat.IsKitten; // extension property, read like a property
"Oreo".Meowify();
```

An extension property becomes a C# 14 `extension` block, so it reads and writes like a property.
The generated package therefore requires C# 14 (`net10.0`).

### Extension properties as C# 14 extension blocks {id="extension-property-blocks"}

```kotlin
val Cat.isKitten: Boolean get() = lives > 7

var PropertyProbe.extensionLives: Int
  get() = extensionPropertyState().lives
  set(value) { extensionPropertyState().lives = value }
```

```C#
bool kitten = cat.IsKitten;
probe.ExtensionLives = 3; // a var also has a setter
```

Two imported namespaces can each declare a property with the same name on the same receiver, which
makes `cat.IsKitten` ambiguous. Call the lowered accessors on the declaring class instead:
`CatExtensions.get_IsKitten(cat)` and `CatExtensions.set_Label(cat, value)`.

> **Breaking in 0.9.0.** The `GetIsKitten(this Cat)` and `SetLabel(this Cat, string)` static
> methods are gone. Replace `cat.GetIsKitten()` with `cat.IsKitten` and `cat.SetLabel(x)` with
> `cat.Label = x`. The generated source now needs C# 14, and the floor only rises in a major
> version.

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
| Bare interface, bound C# interface (from a NuGet dependency) | yes | yes |
| Nullable class or nullable interface | yes | yes |
| `String`, a primitive, `Uuid`, `Instant`, `Duration` | yes | yes |
| Value class over `String`, a primitive, an enum, or an object handle | yes | yes |
| Nullable value class over `String` or an object handle | yes | yes |
| Bare enum | yes | yes |
| `Collection` (`List`/`Map`/`Set`) | yes | yes |
| Nullable `String`, nullable `Uuid` | yes | yes |
| `Int?`, `Enum?`, `Instant?`, `Duration?`, or a nullable primitive/enum-underlying value class | yes | yes |
| `Char`, `Char?` | yes | yes |
| Nullable collection, nullable bound C# interface | yes | no |
| Generic type, unexported (non-stdlib, non-dependency) type, `ByteArray` | no | no |

A closed instantiation of an exported generic class (`Box<Int>`) is the exception to the generic
row for a property: `val Box<Int>.doubled: Int` binds as an extension on `Box<int>`.

A receiver in a "no" cell is dropped with a named diagnostic: `SKIPPED_UNSUPPORTED_INPUT` for a
function, `SKIPPED_UNSUPPORTED_PROPERTY` for a property
(see [Publishing Kotlin to C#: Diagnostics](forward-overview.md#diagnostics)).
An extension property typed `Flow`, `StateFlow`, or a lambda is skipped the same way, even on an
otherwise-supported receiver.

An extension property is also skipped, named `SHADOWED_BY_MEMBER`, when the receiver already has a
visible member property of the same name (declared or inherited):

```kotlin
class Foo { val x: Int = 1 }
val Foo.x: Int get() = 2 // skipped: Foo already has a member `x`
```

Kotlin resolves `receiver.x` to the member in both the generated export body and in your own call
sites, so the extension is unreachable by plain call syntax, not merely un-exported. Rename the
extension property or expose a top-level function instead. A private or protected member does not
shadow, and neither does a nullable receiver (`val Foo?.x`).

An extension **function** shadowed by a member is kept, unlike a property. `FooExtensions.Y(foo)`
runs the extension and `foo.Y()` runs the member, as Kotlin and C# each resolve the call. Declare the
extension in the member's own package or in another one; both bind the same way. The exception is
an enum receiver: an enum member and a same-package extension of the same name fail with
`ERROR_CSHARP_SIGNATURE_COLLISION`, because both land in the enum's `Extensions` class. Rename one.

An extension property is skipped, named `SHADOWED_BY_EXTENSION_FUNCTION` under
`SKIPPED_UNSUPPORTED_PROPERTY`, when an extension function on the same receiver has the same C# name,
because `cat.NameOrStray` would be ambiguous. The function keeps the name. Put `@CSharpName` on the
property to keep both:

```kotlin
fun Cat.homeLabel(): String = "${name}'s basket"

@CSharpName("HomeTag")
val Cat.homeLabel: String get() = "${name}'s tag"
```

```C#
cat.HomeLabel(); // "Oreo's basket"
cat.HomeTag;     // "Oreo's tag"
```

The same skip applies when the receiver's own enum, class or value class declares a member function
with that C# name, at any arity (`fun grooming(times: Int)` beside `val Cat.grooming`), because a
member method makes `cat.Grooming` unreachable as a property. The property is skipped with the
warning and the member function keeps the name; `@CSharpName` on the property keeps both. Before
this rule an enum member function without parameters was a build error and the other cases shipped C#
that failed to compile.

The clash is per generated C# extension class. On an exported receiver such as `Cat`, extensions
from every package merge into one `CatExtensions`, so the skip applies across packages. On an
unexported receiver such as `String`, each package gets its own `StringExtensions`, so a property
and a function of the same name in different packages both bind. A file that imports both
namespaces gets CS9339 on the property syntax; call `A.StringExtensions.get_Tag(s)` instead.

Two extension properties with the same C# name on the same receiver are a build error
(`ERROR_CSHARP_SIGNATURE_COLLISION`); rename one with `@CSharpName`.

`val Cat.x` beside `val Cat?.x` compiles in Kotlin but is also a build error with
`ERROR_CSHARP_SIGNATURE_COLLISION`, because C# reads `Cat` and `Cat?` as one receiver type and
cannot declare both. `@CSharpName` does not separate them; rename one of them in Kotlin. A
value-type receiver is different: `int` and `int?` are two receivers to C#, so `val Int.x` beside
`val Int?.x` binds both. The same goes for `Char`, an enum, `Instant`, `Duration`, `Uuid` and a
value class. A property and a function of one name also bind when their value-type receivers
differ in nullability (`fun Int.label()` beside `val Int?.label`); the same nullability is still
`SHADOWED_BY_EXTENSION_FUNCTION`.

`Instant`, `Duration`, and `Uuid` map to `DateTimeOffset`, `TimeSpan`, and `Guid` at a receiver the
same way they do everywhere else (see
[Primitives and strings](primitives-and-strings.md#instant)). A nullable collection or a nullable
bound C# interface (see
[The bridgeable subset](bridgeable-subset.md#exposing-a-c-interface-in-your-own-kotlin-api)) still
works as a function *parameter*, just not as a receiver at either position. A has-value shape such
as `Int?` binds as a receiver at both (see [Nullable receivers](#nullable-receivers)).

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

An extension property on a nullable receiver (`val Cat?.x`) behaves the same way.

A nullable value-type receiver (`Int?`, `Char?`, an enum, `Instant?`, `Duration?`, or a value class
over a primitive or enum) binds as an extension method on the matching `Nullable<T>`, and a C#
`null` reaches Kotlin as a real `null`. An `Int` and an `Int?` receiver of the same name become two
overloads and each call picks its own:

```kotlin
fun Int?.orNoLives(): Int = this ?: 0
fun Int.describeLives(): String = "lives:$this"
fun Int?.describeLives(): String = "lives?:$this"
```

```C#
public static int OrNoLives(this int? receiver)
public static string MoodOrShrug(this Mood? receiver)
public static DateTimeOffset LastSeenOrEpoch(this DateTimeOffset? receiver)
```

As with a nullable value class, the receiver is nullable only: calling `OrNoLives` on a bare `int`
fails to compile (`CS1929`), so call it on an `int?` variable.

Extension properties on these receivers bind the same way, as C# 14 `extension(int? receiver)`
blocks, and a `var` gets its setter:

```kotlin
val Int?.livesOrNone: String get() = this?.toString() ?: "none"

val Int.livesLabel: String get() = "lives:$this"
val Int?.livesLabel: String get() = "lives?:$this"
```

```C#
int? none = null;
none.LivesOrNone;  // "none"
7.LivesLabel;      // "lives:7"
none.LivesLabel;   // "lives?:null"
```

A property declared only on `Int?` is read on an `int?` variable; a bare `int` is `CS1929`. A setter
needs a variable on the left (`none.LivesNote = "x"`), since C# rejects an assignment through an
rvalue.

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
var summary = rex.Summary; // "Rex/4/Woof!"
```

### Converting and collection receivers {id="converting-and-collection-receivers"}

An extension property's receiver may also be a converting scalar (`Enum`, `Uuid`, `Instant`,
`Duration`), a `Collection`, or a bound C# interface from a NuGet dependency, the same shapes an
extension *function* receiver already takes. A `var` works too:

```kotlin
val Uuid.shortForm: String get() = toString().substringBefore('-')

var Uuid.nickname: String
  get() = chipNicknames[this] ?: "unnamed chip"
  set(value) { chipNicknames[this] = value }
```

```C#
Guid chip = Guid.Parse("7f9c2ba4-0000-4e10-8c1a-11111111abcd");
string before = chip.Nickname; // "unnamed chip"
chip.Nickname = "Oreo's chip";
string after = chip.Nickname;  // "Oreo's chip"
```

A collection receiver (`val List<String>.longestName`) disposes a handle the C# side builds for the
crossing, same as any other collection argument; a bound-interface receiver (`val
IFeedable.feedingNote`) dispatches back into the C# object the same way an interface receiver does.

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

`Guid?` has the same asymmetry for the same reason: a nullable `Uuid` receiver (`val
Uuid?.isMissing`) only binds `this Guid? receiver`, so `missing.IsMissing` needs a `Guid?`
local, not a bare `Guid`.

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

A defaulted extension parameter widens the same way a class method or top-level function's does
(`null` means "use the Kotlin default"). The receiver is never a parameter the widening rule
considers, so it is always required:

```kotlin
fun Paw.knead(times: Int = 2, surface: String = "blanket"): String =
  "$name kneads the $surface $times times"
```

```C#
paw.Knead();                 // "Oreo kneads the blanket 2 times"
paw.Knead(3);
paw.Knead(3, "sofa");
```

A defaulted parameter the bridge cannot route to any non-null C# form is dropped from the C#
signature when it is trailing, and Kotlin always evaluates its default.

```kotlin
fun Logger.call(level: Int = 0, events: Flow<Int>? = null): String =
  "$tag calls $level/${events?.toString() ?: "-"}"
```

```C#
using var logger = new Logger("Mylo");
logger.Call();
logger.Call(3);
```

The `events` parameter does not exist in C# at all. See
[Constructor and method default parameters](classes-and-objects.md#constructor-and-method-default-parameters).

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
