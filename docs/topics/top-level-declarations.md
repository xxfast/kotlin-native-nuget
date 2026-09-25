# Top-level declarations

Kotlin top-level functions, properties, and `const val`s don't belong to any class, so the
generator groups them by source file: every `.kt` file gets its own `static` C# class named after
the file, with `PascalCase` members.

A file stem that isn't already a legal C# identifier is sanitised into one: `Hub.mingw.kt` (the
platform-suffixed file of a KMP `expect`/`actual` pair) becomes `HubMingw`, not `Hub.mingw`, which
C# can't parse as a class name. An `actual` declaration still lands on its `expect`'s file class
rather than its own; see the `expect`/`actual` note below.

`Properties.kt`:

```kotlin
val catBreed: String = "Scottish Fold"
var catNickname: String? = null
```

`Constants.kt`:

```kotlin
const val MAX_LIVES: Int = 9
```

```C#
string breed = Properties.CatBreed;
Properties.CatNickname = "Whiskers";

int maxLives = Constants.MaxLives; // a real C# const, no bridge call
```

`const val` becomes a genuine C# `const` field with the value baked in at build time; reading it
never crosses the bridge. Ordinary top-level `val`/`var` become a static property with a getter
(and setter for `var`), including nullable types.

Top-level functions follow the same grouping. `Arithmetic.kt` (`add`, `multiply`, ...) becomes a
static class named after its file, in the C# namespace that matches its package
(`TestLibrary.Math.Arithmetic.Add(3, 4)`); see [Generics](generics.md) for an `inline fun`. A
top-level function returning a bridged class, such as a factory function, works like any other
function that returns a class.

If every declaration in a file is unsupported and skipped, the file's static class is not
generated at all, and its namespace disappears too if nothing else lives there. A file that keeps
at least one surviving declaration still gets its class, with only the survivors on it.

An `expect fun`/`expect val` follows the same grouping, but the static class name comes from the
**expect's** file, not whichever `{target}Main` file supplied the `actual` body. See
[expect/actual declarations](expect-actual.md).

## Name collisions

`PascalCase`-ing every top-level function opens two collisions `camelCase` used to keep apart.

A function whose `PascalCase` name equals its own file's class name (`fun greeting()` in
`Greeting.kt`) would produce a member named like its enclosing type, `Greeting.Greeting()`, which
C# forbids. The whole file's class is renamed with a `Kt` suffix instead, the same fallback used
when a class of that name already exists in the file:

```C#
public static partial class GreetingKt
{
    public static string Greeting() { /* ... */ }
    public static string Other() { /* ... */ } // moves with the rest of the file
}
```

Call it as `GreetingKt.Greeting(...)`. The native export name is unaffected: it still targets the
Kotlin function by its own spelling, not the renamed class.

A `val name` and a `fun name()` in the same file both want the C# name `Name` on that file's
class. Kotlin keeps properties and functions in separate namespaces; C# does not, and refuses a
property and a method sharing one name. There is no rename to fall back on, since renaming either
member would silently change the API, so this fails the build. Rename the Kotlin function (or the
property) to resolve it.

## Method overloads

Two or more same-named top-level functions in one package generate one natural C# overload set,
numbered internally but exposed as ordinary overload resolution. The counter is scoped per
`(package, name)`, not per file: two same-named top-level functions in different files of the same
package still resolve to one overload set.

```kotlin
fun bookGrooming(): String = "the next slot is free"
fun bookGrooming(cat: String): String = "$cat is groomed at noon"

fun waitTime(): Int? = 15
fun waitTime(cat: String): Int? = if (cat.isBlank()) null else cat.length
```

```C#
string slot = GroomingSample.BookGrooming();
string turn = GroomingSample.BookGrooming("Oreo");

int? wait = GroomingSample.WaitTime("   "); // null, resolved by the string overload
```

## Function default parameters

A defaulted top-level parameter widens to its nullable C# form (`null` means "use the Kotlin
default"); a required parameter after it stays required-but-nullable instead of optional.

```kotlin
fun hail(name: String, loud: Boolean = false): String =
  if (loud) "HI ${name.uppercase()}" else "hi $name"

fun book(name: String, capacity: Int = 3, city: String): String =
  "$name booked $capacity spots in $city"
```

```C#
string greeting = WhiskersSample.Hail("Oreo"); // loud omitted; Kotlin supplies false

// capacity is followed by the required `city`, so it stays required-but-nullable
string booking = WhiskersSample.Book("Oreo", null, "Bristol"); // null still means "use 3"
```

A defaulted parameter the bridge cannot route to any non-null C# form is dropped from the C#
signature when it is part of the trailing all-defaulted run, and Kotlin always evaluates its
default. See
[Constructor and method default parameters](classes-and-objects.md#constructor-and-method-default-parameters)
for the full rule, the cap, and `Optional<T>`.

```kotlin
fun hubWithEvents(settings: Settings = Settings(), events: Flow<Int>? = null): Hub =
  Hub(settings, null, events?.toString())
```

```C#
using Hub a = HubSample.HubWithEvents();
using var settings = new Settings(3);
using Hub b = HubSample.HubWithEvents(settings);
```

`HubSample.HubWithEvents(settings, events)` does not exist. The build still warns
`SKIPPED_UNSUPPORTED_INPUT` naming `events`.

Synthesized overloads are numbered after every declared overload, using the same counter as
[method overloads](#method-overloads) above.

A top-level `expect fun`'s defaults are read from the `expect` declaration, not the platform
`actual` body that fills it in (Kotlin forbids an `actual` from restating a default); see
[expect/actual declarations](expect-actual.md#function-default-parameters-on-a-top-level-expect-function).

See also [Objects and companions](objects-and-companions.md), [Extensions](extensions.md), and
[Classes and objects](classes-and-objects.md) for the other three top-level export routes and
their own overload numbering.
