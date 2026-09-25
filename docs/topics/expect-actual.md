# expect/actual declarations

Kotlin Multiplatform's `expect`/`actual` pair is invisible in the generated C#: only the `actual`
body is exported, and it looks exactly like an ordinary, non-`expect` declaration of the same
shape. The resulting class, function, or object otherwise follows its usual mapping page
([Classes and objects](classes-and-objects.md), [Objects and companions](objects-and-companions.md),
[Top-level declarations](top-level-declarations.md)); this page covers only what differs because
of `expect`/`actual` itself.

| Kotlin | C# | Note |
|---|---|---|
| `expect`/`actual class` | ordinary `class` | gets a public constructor even when the `expect` declares none, because the `actual` always has one |
| `expect`/`actual fun` / `val` (top-level) | static member, `PascalCase` | the containing static class is named after the **expect's** file, never the actual's |
| `expect`/`actual object` | ordinary `static class` | |
| `actual typealias Foo = Target` | `Target`'s own C# type | `Foo` never appears in C#; every position typed `Foo` becomes `Target` instead |

## Example

`PlatformApi.kt` (shared):

```kotlin
expect class Device(name: String) {
  fun describe(): String
}

expect fun platformName(): String

expect object PlatformRegistry {
  fun count(): Int
}

expect class Clock { fun label(): String }

class SystemClock { fun label(): String = "system-clock" }

expect fun defaultClock(): Clock
```

`PlatformApiMacos.kt` (macOS `actual`; a `mingwX64` actual supplies the same surface with different
values):

```kotlin
actual class Device actual constructor(private val name: String) {
  actual fun describe(): String = "$name on macos"
}

actual fun platformName(): String = "macos"

actual object PlatformRegistry {
  actual fun count(): Int = 1
}

actual typealias Clock = SystemClock
actual fun defaultClock(): Clock = SystemClock()
```

Each packaged binary runs the one target's `actual` bodies it was built from. In C#, the top-level
function and object land on a static class named `PlatformApi`, after the expect's file
(`PlatformApiMacos`/`PlatformApiMingw` never exist as C# types), and every position typed `Clock`
resolves to `SystemClock`:

```C#
using var device = new Device("Oreo");
string name = PlatformApi.PlatformName();       // "macos" or "mingw", decided by the running binary
int count = PlatformRegistry.Count();
SystemClock clock = PlatformApi.DefaultClock();  // typed SystemClock, not Clock
```

## Constructor default parameters

Kotlin forbids an `actual` from restating a default value, so the default lives only on the
`expect` declaration's primary constructor. The generator still widens a class's primary
constructor the usual way:

```kotlin
// shared
expect class Beacon(name: String, interval: Int = 5) { fun describe(): String }
// actual: no default restated
actual class Beacon actual constructor(private val name: String, private val interval: Int) {
  actual fun describe(): String = "$name every ${interval}s"
}
```

```C#
using var beacon = new Beacon("Oreo's collar"); // interval defaults to 5
```

A secondary constructor on an `expect`/`actual class` widens none of its defaulted parameters;
pass every parameter explicitly. See
[Constructor and method default parameters](classes-and-objects.md#constructor-and-method-default-parameters)
for the general mechanism.

## Function default parameters on a top-level `expect` function

The same restatement rule applies to an ordinary function, and the generator resolves the default
from the matching `expect fun`, but **only for a top-level function**. A class method, `object`
member, companion member, or extension declared on an `expect` class widens none of its defaulted
parameters, even though the equivalent constructor case above does; call those with every
parameter explicit.

```kotlin
expect fun beaconLabel(prefix: String, level: Int = 7): String
```

```C#
string label = PlatformApi.BeaconLabel("Oreo's collar"); // level defaults to 7
```

When a top-level `expect fun` is overloaded, each overload's default is resolved from its own
`expect` (matched by parameter count, name, and type), never from a namesake's. See
[Function default parameters](top-level-declarations.md#function-default-parameters) for the
general mechanism.

## Sealed, interface, enum, and value class actuals

`expect sealed class`, `expect interface`, `expect enum class`, and `expect value class` all
resolve to their `actual` declaration and then follow the same mapping an ordinary, non-`expect`
declaration of that shape would: a sealed class's subclasses are read off the `actual` (they can
live in the actual's source set or, for a common-side declaration, anywhere on the dependency path
to it), and a per-target implementing class for an `expect interface` that stays `internal` never
reaches C#. Nothing about discrimination, enum ordinals, or value-class unwrapping changes; see
[Classes and objects](classes-and-objects.md) for those mechanics.

## Limitations

- `actual typealias` to a generic target (`actual typealias Bag<T> = Crate<T>`) or to a stdlib type
  the forward direction can't export (`actual typealias Failure = kotlin.IllegalStateException`) is
  not supported. A member that mentions it is skipped from the generated C# rather than emitting a
  type that was never generated; redirect to a type you declare and export instead.
- Cross-module (klib) `expect`/`actual` isn't supported: the `expect` and its `actual` must share a
  module.
- Annotations declared on the `expect` side don't reach the generated C#. KDoc does: see
  [Documentation comments](documentation-comments.md#expectactual).
- `expect annotation class` never binds; there is no C# projection of a Kotlin annotation class
  regardless of `expect`/`actual`.
- Two packaged targets can legitimately produce different C# if their actuals diverge beyond the
  expect's contract, but a package ships only one target's generated API surface; nothing diffs the
  two.
