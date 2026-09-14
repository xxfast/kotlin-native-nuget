# Classes and objects

A Kotlin `class` becomes a C# `class` implementing `IDisposable`. Each instance wraps an opaque
native handle: dispose it when you are done, and every constructor call, property access, and
method call crosses the bridge through that handle.

| Kotlin | C# |
|---|---|
| `class` | `class : IDisposable` |
| constructor | `new Foo(...)` |
| `val`/`var` property | property (get / get+set) |
| nested `class`/`object`/`interface`/`enum class`/`value class` | nested C# type, see [Nested types](#nested-classes-and-objects) |

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

## Constructor default parameters

Each exported constructor gets one additional C# overload per maximal trailing run of defaulted
parameters, omitting that suffix; Kotlin supplies the omitted arguments, evaluated on the Kotlin
side. A default followed by a required parameter (a **middle default**) produces no overload,
since a positional call can't skip over it.

```kotlin
class Carrier(
  val label: String,
  val size: Int = 3,
  val padded: Boolean = true,
)

class Kennel(
  val name: String,
  val capacity: Int = 10,
  val city: String,
)
```

`Carrier` gets three public constructors: the full signature, one omitting `padded`, and one
omitting both `padded` and `size`. `Kennel`'s `capacity` sits before the required `city`, so
nothing is synthesized: exactly one public constructor.

```C#
using var mouse = new Cat("Mouse");               // lives defaults to Kotlin's 9
Assert.Equal(9, mouse.Lives);

using var carrier = new Carrier("Mylo's crate");  // both size and padded default
Assert.Single(typeof(Kennel).GetConstructors());
```

### Generated C# {id="ctordefaults-generated-c"}

Every constructor overload, synthesized or not, carries the same `out IntPtr error` shape as any
other constructor call:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "carrier_create")]
private static extern IntPtr Native_Create(string label, int size, bool padded, out IntPtr error);
```

A constructor overload that collides with another, declared or synthesized, fails generation with
the same `ERROR_CSHARP_SIGNATURE_COLLISION` diagnostic used for method overloads, naming the
defaulted parameter as the cause.

## Method default parameters

The same rule extends to class methods, and to `object`/companion members, top-level functions,
and extension functions, each with its own overload-numbering scope; see
[Objects and companions](objects-and-companions.md#method-overloads),
[Top-level declarations](top-level-declarations.md#method-overloads), and
[Extensions](extensions.md#method-overloads).

```kotlin
class Announcer(val prefix: String) {
  fun announce(message: String, loud: Boolean = false): String =
    if (loud) "$prefix: ${message.uppercase()}!" else "$prefix: $message"
}
```

```C#
using var announcer = new Announcer("Oreo");
Assert.Equal("Oreo: morning", announcer.Announce("morning")); // loud defaults to false
```

An `override` does not synthesize its own omitting overload when its C# base already carries one:
the override just inherits it through ordinary C# inheritance. When there is no C# base to
inherit from, or the member comes through an interface, no omitting overload is generated;
call the override with every argument.

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
    internal Issue56Failure(IntPtr handle) { _handle = handle; }
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

An `inner class` owner, a generic owner, and an `enum class` owner have no C# equivalent for a
nested slot and stay a named skip (`SKIPPED_NESTED_DECLARATION`). Kotlin allows a nested type
named exactly like its owner, or like a PascalCased member of its owner; C# does not, so that
combination fails generation (`ERROR_CSHARP_SIGNATURE_COLLISION`) instead of emitting invalid C#.
Avoid naming an accessor the same as its nested return type (`fun perch(): Perch`); name the
accessor differently instead (`perchAt`).

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

A sealed arm's only constructor is `internal Arm(IntPtr handle)`. In .NET 7+, `IntPtr` is `nint`,
and an `int` converts to it implicitly, so `new Purr.On(9)` **compiles**, binding to that internal
handle constructor with `9` as a fabricated address, and access-violates on first use instead of
failing to build. Always obtain a sealed arm from a factory method or a pattern match, never `new`.

## Limitations

- `Map`/`Set` are not yet supported as method or constructor **parameters**; see
  [Collections](collections.md).
- Value classes don't get default-parameter overloads on their constructor or methods, and
  `Copy(...)` can't omit arguments.
- An `override` with no exported C# base to inherit an omitting overload from, or a member reached
  through an interface, gets no synthesized overload; call it with every argument. An override on
  a sealed class arm never gets one either, even when its sealed base has none to inherit.
- An exported Kotlin interface doesn't number same-named methods at all; avoid declaring an
  overload directly on an interface.
- Defaults on an `expect`/`actual` class member are only synthesized for the top-level-function
  route and the primary constructor; a class method, companion member, extension, or secondary
  constructor on an `expect`/`actual` class gets no synthesized overload. See
  [expect/actual declarations](expect-actual.md).
- A nested type under an owner other than the shapes covered above stays a named
  `SKIPPED_NESTED_DECLARATION` skip. A nested `value class` under one of those still-deferred
  owners has no such diagnostic and can emit an unusable struct name silently; avoid nesting a
  `value class` directly inside an `inner class`, generic, or `enum class` owner. An extension
  function or property on a nested type is not supported; declare the extension on a top-level
  type instead.

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
