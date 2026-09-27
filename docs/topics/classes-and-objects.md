# Classes and objects

A Kotlin `class` becomes a C# `class` implementing `IDisposable`. Each instance wraps an opaque
native handle: dispose it when you are done, and every constructor call, property access, and
method call crosses the bridge through that handle.

| Kotlin | C# |
|---|---|
| `class` | `class : IDisposable` |
| constructor | `new Foo(...)` |
| `val`/`var` property | property (get / get+set) |
| nested `class`/`object`/`interface`/`enum class`/`value class`/`inner class` | nested C# type, see [Nested types](#nested-classes-and-objects) |

```kotlin
class Cat(
  name: String,
  val lives: Int = 9,
) : Animal(name) {
  var brother: Cat? = null
  var owner: String? = null
  var age: Int? = null
}
```

```C#
using var oreo = new Cat("Oreo", 9);
using var mylo = new Cat("Mylo", 9);
oreo.Brother = mylo;

using Cat? brother1 = oreo.Brother;
using Cat? brother2 = oreo.Brother;
Assert.NotSame(brother1, brother2);       // a fresh wrapper on every access
Assert.Equal(brother1!.Name, brother2!.Name);
```

## A setter narrower than public binds get-only

A `var` whose setter is narrower than the property itself (`private set`, `protected set`,
`internal set`) becomes a get-only C# property: it reads like a `val` from C#, and Kotlin's own
mutators still change the value underneath it. This holds even for `internal set`, since a generated
C# consumer is never inside the Kotlin module the modifier scopes access to.

```kotlin
class Button(val label: String = "ok") {
  var clicks: Int = 0
    private set

  fun click(): String { clicks++; return "$label clicked $clicks" }
}
```

```C#
public int Clicks { get; }   // no set accessor; button.Clicks = 5 is CS0200
```

An override that widens the setter back toward public in Kotlin cannot follow in C#: the base
property is already get-only, and an `override` cannot add a set accessor a base property does not
have (`CS0546`), so the override drops its setter too, with a warning explaining why.

## Object identity and disposal

An object-typed property or method return mints a **new** C# wrapper on every access; there is no
identity caching, so reading `oreo.Brother` twice gives wrappers that are `NotSame` but `Equal`.
Disposal does not cascade: a wrapper's `Dispose()` releases only its own handle. Disposing a
`Brother` wrapper you read off `oreo` does not affect `oreo`, and does not affect any other wrapper
read from the same property. Dispose every wrapper you hold, including ones you only read a
property or method return into.

## Method returns

An instance method returning an object, a nullable type, a collection, an enum, or `Char` marshals
the same way a property of that type would (see [Collections](collections.md) and
[Enums](enums.md)). A method returning a nullable **primitive**, including `Boolean?`, evaluates
only once: unlike a property getter it may have side effects, so it can't be called twice to fetch
a has-value flag and then the value.

```kotlin
fun ageInMonths(): Int? = age?.times(12)
```

```C#
oreo.Age = 3;
Assert.Equal(36, oreo.AgeInMonths());
```

## A nullable class handle parameter {id="nullable-handle-parameter"}

A parameter typed with a nullable exported class (`Foo?`), on a constructor, method, extension, or
top-level function, binds as an ordinary nullable handle: `null` needs no separate has-value flag,
since a handle already has its own null. The handle is borrowed, not consumed: the same instance
can back several calls and stays usable afterwards.

```kotlin
class Hub(val settings: Settings, val logger: Logger?, val note: String? = null) {
  fun describe(): String = "${settings.level}/${logger?.tag ?: "none"}/${note ?: "-"}"
}
```

```C#
using var logger = new Logger("Oreo");
using var hub = new Hub(settings, logger, null);
Assert.Equal("[Oreo] still here", logger.Log("still here")); // logger is still usable
```

A nullable *return-shaped* type at a parameter (`Flow<Event>?`) is a different case; see
[Publishing Kotlin to C#](forward-overview.md#nullable-parameter-names-itself).

## Method overloads

Two or more same-named methods on an exported class collapse into one natural C# overload set;
call whichever one matches your argument types.

```kotlin
class CatNarrator(val name: String) {
  fun describe(): String = "$name is a cat"
  fun describe(prefix: String): String = "$prefix $name"
  fun rate(stars: Int): String = "$name rated $stars"
  fun rate(mood: Mood): String = "$name is ${mood.name.lowercase()}"
}
```

```C#
using var narrator = new CatNarrator("Oreo");
Assert.Equal("Oreo is a cat", narrator.Describe());
Assert.Equal("the biscuit cat Oreo", narrator.Describe("the biscuit cat"));
Assert.Equal("Mylo is grumpy", narrator.Rate(Mood.Grumpy));
```

C# cannot overload on reference nullability alone: a pair like `fun tag(s: String)` and
`fun tag(s: String?)` would render two identical C# signatures, so generation fails outright
(`ERROR_CSHARP_SIGNATURE_COLLISION`) instead of producing invalid C#. Give one of them a distinct
name.

## A class's own interface beside a kept base class

A class with an exported base class keeps its own exported interfaces too: `class X : Base(),
IFoo` renders `public class X : Base, IFoo`, not `Base` alone with `IFoo` dropped. See
[Interfaces, abstract classes, and sealed classes](interfaces-abstract-sealed.md) for when a base
class or interface is dropped instead of kept.

## Constructor and method default parameters

A Kotlin defaulted parameter binds as one C# signature: a non-nullable type widens to its
nullable C# form, and `null` means "use the Kotlin default". A parameter that is already
nullable in Kotlin widens to the generated `Optional<T>` struct instead, since `null` there is
already a real value; `default` means unset. Whichever way it widens, any subset of the
defaulted parameters can be set by name in one call, and Kotlin evaluates the rest.

```kotlin
data class Config(
  val id: Uuid = Uuid.random(),
  val retries: Int = 3,
  val mode: Mode = Mode.Never,
)

object Registry {
  fun describe(name: String, owner: String? = "nobody"): String = "..."
}
```

```C#
new Config(mode: Mode.Always);       // id and retries: Kotlin evaluates Uuid.random() and 3
Registry.Describe("Momo");           // owner unset -> Kotlin's "nobody"
Registry.Describe("Momo", owner: null); // owner set to null, distinct from unset
```

A defaulted parameter followed by a required one (a **middle default**) can't be widened to
optional, since C# can't skip over a required positional argument either: it stays
required-but-nullable instead of optional.

```kotlin
class Book(val title: String, val pages: Int = 100, val city: String)
```

```C#
new Book("Paws", null, "Colombo"); // pages must be passed, but null still means "use 100"
```

`Copy` on a data class gets the same widened shape, so a **partial copy** works: an omitted
parameter keeps the receiver's current value, which is `copy`'s own Kotlin default.

```C#
original.Copy(mode: Mode.Always); // every other field is the receiver's own value
```

The rule is the same for class constructors, class methods, `object`/companion members,
top-level functions, extension functions, and interface methods; see
[Objects and companions](objects-and-companions.md#method-overloads),
[Top-level declarations](top-level-declarations.md#function-default-parameters),
[Extensions](extensions.md#method-overloads), and
[Interfaces, abstract classes, and sealed classes](interfaces-abstract-sealed.md#method-overloads-on-an-interface).
An `override` widens with the same C# signature as
its root overridee, so a base and derived class agree without restating the default:

```kotlin
open class Base { open fun rate(score: Int = 5): Int = score }
class Derived : Base() { override fun rate(score: Int): Int = score * 10 }
```

```C#
new Derived().Rate(); // score defaults to 5, the base's default, then multiplied by 10
```

An exported Kotlin interface's defaulted member widens the same way, so the interface and every
implementer's C# signature agree; there is no separate rule for members reached through an
interface.

A defaulted parameter the bridge cannot route to any non-null C# form at all (a `Flow`, a sealed
type, or a bound interface) is dropped from the C# signature entirely when it is part of the
trailing all-defaulted run, and Kotlin always evaluates its default; call the shorter C#
signature. One that sits before a required parameter leaves the whole callable unroutable, same
as an unsupported required parameter would.

```kotlin
class Desk(val name: String) {
  fun open(settings: Settings = Settings(), events: Flow<Int>? = null): String = "..."
}
```

```C#
using var desk = new Desk("Oreo");
desk.Open();
desk.Open(settings); // settings still widens; `events` never reaches C#
```

A defaulted lambda parameter (a `(T) -> R` used only for the duration of the call, never stored)
widens to its nullable delegate form the same way as any other type:

```kotlin
fun notify(message: String, onDone: (String) -> Unit = {}): String { onDone(message); return "sent $message" }
```

```C#
Issue297Sample.Notify("meow", onDone: message => heard = message);
Issue297Sample.Notify("meow"); // onDone unset: Kotlin's no-op default runs
```

A lambda parameter a **constructor stores** past the call (assigned to a property, called later)
never reaches C# at all, widened or not: a per-call native handle for it cannot outlive the
constructor call, so the class keeps only its non-lambda constructor and Kotlin's default always
runs for the stored one.

```kotlin
class Button(val label: String = "ok", val onClick: () -> Unit = {})
```

```C#
new Button("go"); // the only constructor; onClick is always Kotlin's no-op default
```

At most 8 parameters per callable can widen. Past that cap, only the **last** 8 in declaration
order widen and the earlier ones stay required; a `WARNING_DEFAULT_PARAMETER_CAP_EXCEEDED`
diagnostic names the parameters left required, so split the callable or pass them explicitly.

### Generated C# {id="ctordefaults-generated-c"}

A widened non-nullable parameter renders as `= null`; a widened already-nullable one renders as
`Optional<T> = default`:

```C#
public Config(Guid? id = null, int? retries = null, Mode? mode = null)
public string Describe(string name, Optional<string?> owner = default)
```

A signature that would otherwise collide with another constructor still fails generation with
`ERROR_CSHARP_SIGNATURE_COLLISION`, naming the defaulted parameter as the cause.

## No public constructor

A class whose every public constructor is skipped, for example because a constructor parameter
has an unsupported type, is still exported: it just has no public C# constructor, only the
internal one every handle class carries. C# code obtains an instance from a Kotlin factory
function or property that returns one. The build warns, naming the skipped constructor and the
reason, and the same detail appears as an XML `<remarks>` comment on the generated class, so it
shows up as an IDE tooltip too.

```kotlin
data class Issue56Failure(
  val reason: String,
  val error: Throwable?,
  val fatal: Throwable,
)

fun quietMishap(): Issue56Failure = Issue56Failure(/* ... */)
```

```C#
/// <remarks>
/// Cannot be constructed from C#: every Kotlin constructor of Issue56Failure was skipped by the
/// bridge. Instances come from Kotlin factories that return this type.
/// </remarks>
public class Issue56Failure : IDisposable, INugetHandle
{
    internal Issue56Failure(IntPtr handle, out NugetHandleTag tag) { tag = default; _handle = handle; }
}

var mishap = Issue56Sample.QuietMishap(); // the only way to obtain one
```

## Classes declared in a dependency module

A class does not need to be declared in the publishing Gradle module to reach the generated C#
API. Starting from the module's own exported declarations, the processor also walks return,
parameter, and property types, and admits any type it discovers in a dependency module, under
that type's own Kotlin package rather than the exporting module's. See
[The nuget {} DSL](nuget-dsl.md) for the include/exclude rule this follows.

## Nested types {id="nested-classes-and-objects"}

A public `class`, `object`, `interface`, or `enum class`, at any depth, declared inside a
non-generic, non-`inner` `class` or `object`, an `interface`, or a sealed base/arm, becomes a real
C# nested type, `Outer.Nested`.

| Kotlin nested kind | C# shape |
|---|---|
| `class` | `Outer.Nested : IDisposable`, same members as a top-level class |
| `object` | `public static class Outer.Defaults`, statics only |
| `enum class` | `public enum Outer.Kind`; its extension methods go on a **top-level** `OuterKindExtensions` class, since C# forbids extension methods inside a nested class |
| `interface` | `public interface Outer.IListener`, with its backing wrapper class nested beside it as `Outer.Listener` |
| `value class` | `public readonly record struct Outer.Tag`, under any admitted owner |
| `inner class` | `Outer.Nested : IDisposable`, constructor takes the outer instance first, see [Inner classes](#inner-classes) |

```kotlin
class Aviary(val name: String) {
  class Perch(val height: Int) {
    fun describe(): String = "perch@$height"
  }

  fun perchAt(height: Int): Perch = Perch(height)
  fun heightOf(perch: Perch): Int = perch.height
}
```

```C#
using var aviary = new Aviary("Oreo");
using var perch = aviary.PerchAt(5);
Assert.Equal("perch@5", perch.Describe());
Assert.Equal(5, aviary.HeightOf(perch));
```

An `inner class`'s **own** nested types (inner-of-inner), a generic owner, and an `enum class`
owner have no C# equivalent for a nested slot and stay a named skip
(`SKIPPED_NESTED_DECLARATION`); see [Inner classes](#inner-classes) for what an `inner class`
itself declares. Kotlin allows a nested type named exactly like its owner, or like a PascalCased
member of its owner (a companion's members included, since they fold into the owner's C# type as
statics); C# does not, so that combination fails generation (`ERROR_CSHARP_SIGNATURE_COLLISION`)
instead of emitting invalid C#. Avoid naming an accessor, or a companion function, the same as its
nested return type (`fun perch(): Perch`); name it differently instead (`perchAt`).

### `inner class`: the constructor takes the outer instance first {id="inner-classes"}

A public Kotlin `inner class` declared directly inside an admitted, non-generic, non-`inner` class
becomes a nested C# type too, with one difference from a plain nested class: its constructor's
first parameter is the outer instance, named `outer`.

```kotlin
class Hearth(val room: String) {
  inner class Sunbather(val minutes: Int) {
    val basking: String get() = "${this@Hearth.room} warms Oreo for $minutes min"
  }
}
```

```C#
using var hearth = new Hearth("The bay window");
using var sunbather = new Hearth.Sunbather(hearth, 3);
Assert.Equal("The bay window warms Oreo for 3 min", sunbather.Basking);
```

Disposing `hearth` before `sunbather` is safe: the inner instance's own reference to its outer
keeps the Kotlin object alive, so `sunbather.Basking` keeps reading it after `hearth.Dispose()`
runs. A declared constructor parameter literally named `outer` renders as `outer_` in both the
generated C# signature and the Kotlin export instead (the same shift `value` already gets on a
property setter); a member reading `this@Hearth` needs nothing added at the ABI, since the
reference lives entirely on the Kotlin heap.

An `inner class` as an owner of its own nested types (inner-of-inner), an `inner class` under a
sealed owner, a generic `inner class`, and an inner class of a generic outer stay a named skip
(`SKIPPED_NESTED_DECLARATION`).

A `@Serializable` class exports the same as any other class. kotlinx.serialization's
compiler-generated `$serializer` nested object is never declared in C#, since `$` isn't a legal C#
identifier there; it has no members you'd want to call, so skipping it costs nothing. Nothing about
it appears in the build log either: a member the compiler wrote, a hidden-deprecated member or the
serialization plugin's `serializer()`, is never reported as skipped.

### An `object` at a member position stays CS0722 {id="nested-object-position"}

A Kotlin `object`, nested or top-level, renders as a C# **static** class, and C# forbids a static
type at a parameter or return position (CS0722). A member typed with one is skipped rather than
emitted as uncompilable C# (`SKIPPED_UNSUPPORTED_TYPE`); the object type itself is still declared,
so call its members directly instead of routing through the skipped member.

## Owners ADR-134 admits {id="nested-adr-134-owners"}

An `interface` owner, a sealed base/arm owner, and a nested `value class` candidate under any
admitted owner all declare nested types too:

```kotlin
interface Cage {
  class Bar(val n: Int) { fun describe(): String = "bar#$n" }
  fun barAt(): Bar
}

sealed class Purr {
  data class On(val level: Int) : Purr() {
    class Trace(val at: Int) { fun describe(): String = "trace@$at" }
    fun traceOf(): Trace = Trace(level)
  }
}
```

```C#
ICage cage = new WireCage(7);
using ICage.Bar bar = cage.BarAt();
Assert.Equal(7, bar.N);

using Purr purr = Deferred.PurringPurr(9);
Purr.On on = Assert.IsType<Purr.On>(purr);
using Purr.On.Trace trace = on.TraceOf();
```

The owner-name collision above is checked against the generated C# name, not the Kotlin one: an
`interface` owner gets an `I` prefix in C#, so a nested type sharing its interface owner's Kotlin
name is legal (`interface Cage { class Cage }` declares `ICage.Cage`, two different C# names, not
a collision).

A sealed arm's `internal Arm(IntPtr handle, out NugetHandleTag tag)` constructor still exists
beside any exported public one; the trailing `out` parameter means it is never a candidate for an
ordinary call. `Purr.On`'s own `level: Int` constructor parameter is bridgeable, so `On` also
exports a public constructor, and `new Purr.On(9)` always resolves to it; see
[Interfaces, abstract classes, and sealed classes: Sealed classes and
interfaces](interfaces-abstract-sealed.md#sealed-classes-and-interfaces) for the general rule.

## Limitations

- `Map`/`Set` are not yet supported as method or constructor **parameters**; see
  [Collections](collections.md).
- Defaults on an `expect`/`actual` class member are only synthesized for the top-level-function
  route and the primary constructor; a class method, companion member, extension, or secondary
  constructor on an `expect`/`actual` class gets no synthesized overload. See
  [expect/actual declarations](expect-actual.md).
- A nested type under an owner other than the shapes covered above stays a named
  `SKIPPED_NESTED_DECLARATION` skip. A member typed with a nested `value class` under a
  still-deferred owner (a generic or `enum class` owner) skips named too
  (`SKIPPED_UNSUPPORTED_TYPE`, reason `UNDECLARED_VALUE_CLASS`) instead of emitting an unusable
  struct name; move the value class to the top level of its file, or to an admitted owner, to
  bridge it. An extension function or property on a nested type is not supported; declare the
  extension on a top-level type instead.

<seealso>
    <category ref="related">
        <a href="forward-overview.md">Publishing Kotlin to C#</a>
        <a href="interfaces-abstract-sealed.md">Interfaces, abstract and sealed classes</a>
        <a href="collections.md">Collections</a>
        <a href="extensions.md">Extensions</a>
        <a href="nuget-dsl.md">The nuget {} DSL</a>
        <a href="expect-actual.md">expect/actual declarations</a>
    </category>
</seealso>
