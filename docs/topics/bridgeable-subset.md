# The bridgeable subset

Not every public C# type or member crosses into Kotlin. This page is the support matrix: what
binds, what shape it takes in Kotlin, and what to do when a build warning says a member was
skipped.

## What binds

| C# construct | Binds as | Notes |
|---|---|---|
| Public class with at least one bridgeable member | Kotlin class, handle-backed | see [Objects and handles](objects-and-handles.md) |
| Public static class | Kotlin `object` | see [Static classes and methods](static-classes-and-methods.md) |
| Public, top-level, non-generic interface with at least one admissible member | Kotlin `interface` plus a wrapper for interface-typed values | see [Interfaces](#interfaces) below |
| Public, top-level, default-`int`-backed, non-`[Flags]` enum with unique contiguous values `0..N-1` | standalone Kotlin `enum class` | see [Enums](#enums) below |
| Bridgeable struct (a state-covering constructor, or public settable fields/auto-properties) | immutable Kotlin `data class`, never a handle | see [C# structs](structs.md) |
| Generic class reached through a closed instantiation (`Box<int>`) | real Kotlin generic class, one per instantiation | see [Generic types](generic-types.md) |
| `record class` | same rules as an ordinary class; not a distinct construct in metadata | |
| `ref struct` (`Span<T>`, `ReadOnlySpan<T>`, a custom one) | not bound | any member referencing one is skipped |
| Nested type, public or not | not bound | only top-level public types are candidates |
| Open generic type parameter | member skipped | |
| Generic interface (`IBox<T>`) | not bound | |

## Enums

A supported enum becomes a standalone Kotlin `enum class`. Member names convert to
`SCREAMING_SNAKE_CASE`. Enum values cross the ABI as their ordinal `Int`, so a constructor
argument, property, or return typed with the enum uses the generated Kotlin type directly:

```C#
// TestDependency/CatMood.cs
public enum CatMood
{
    Playful,
    Sleepy,
    Hungry,
}
```

```kotlin
enum class CatMood {
  PLAYFUL,
  SLEEPY,
  HUNGRY
}
```

- `[Flags]` enums, a non-`int` underlying type, and explicit, sparse, negative, or aliased values
  don't bind.
- A nullable enum, a nested enum, and an enum as a collection element (`List<CatMood>`) aren't
  supported yet. An enum used as the type argument of a bound generic **class** (`Box<CatMood>`)
  is supported.
- An unsupported enum is skipped, named on a build warning (see
  [Build warnings](#unsupported-members-show-up-as-build-warnings) below), not silently dropped.

## Interfaces

A public, top-level, non-generic interface with at least one admissible member becomes a plain
Kotlin `interface`:

```C#
// TestDependency/Menagerie.cs
public interface IFeedable
{
    string Describe();
    int Legs { get; }
    void Feed(string food);
    string? Nickname { get; set; }
}
```

```kotlin
interface IFeedable {
  fun describe(): String
  fun feed(food: String)
  val legs: Int
  var nickname: String?
}
```

The interface itself carries no handle. A value arriving at an interface-typed position (a
parameter, a return, or a property) is wrapped in a generated class that implements the interface
and dispatches every member back to the real C# object, whether or not that object's own runtime
class is itself bound.

Interface inheritance binds too: `interface ITagged : IFeedable` becomes `interface ITagged :
IFeedable` in Kotlin, and dispatches `IFeedable`'s inherited members correctly, as long as
`IFeedable` is itself admissible and bound. If the base isn't, the derived interface binds with
only its own declared members. A bound class only declares an implemented interface as a Kotlin
supertype when every interface member has an identically-signed **public** member on the class; a
C# **explicit** interface implementation is invisible in metadata, so such a class omits the
supertype entirely.

<note>
<p>An interface-typed value always becomes the generated wrapper type, never the concrete bound
class, even when the underlying object is itself bound: <code>star() as? Ferret</code> is always
<code>null</code>. The same C# object reachable both as <code>IFeedable</code> and as a bound
<code>Ferret</code> produces two unrelated Kotlin objects of two unrelated types. The "new wrapper
per crossing, no identity caching" rule in [Objects and handles](objects-and-handles.md) applies to
each independently.</p>
</note>

### Implementing a C#-declared interface in Kotlin {id="implementing-a-c-declared-interface-in-kotlin"}

A plain Kotlin class implementing `IFeedable`, with no generated wrapper machinery of its own, can
be passed back at any `IFeedable`-typed parameter or property:

```kotlin
private class Goat : IFeedable {
  var meals: Int = 0
    private set

  override fun describe(): String = "Nibbles the goat"

  override val legs: Int get() = 4

  override fun feed(food: String) {
    meals++
  }

  override var nickname: String? = null
}
```

Nothing needs to be registered or generated ahead of time: the first time a `Goat` crosses into
C#, Kotlin mints a small C#-side bridge for it automatically. From C#, the result is
indistinguishable from a bound `IFeedable`:

```C#
// IntegrationTests/MenagerieRoundTripTests.cs
string result = MenagerieSample.KotlinGoatIntroduce();
Assert.Equal("introduced Nibbles the goat with 4 legs", result);
```

Only a limited set of member shapes can cross this bridge: `val`/`var` property getters and
setters, and methods of arity 0-2 returning `Unit`, a primitive, `Boolean`, an enum, `String`,
`String?`, a bound-object handle, or a bound interface (nullable included for the last two). A
struct-typed member, a generic-instance-typed member, a collection-typed member
(`List`/`Map`/`Set`), or a `Task`-returning member is out of scope and named on a build warning
instead of bridged; a class with one of those members can't be passed at that interface position.

<note>
<p>While C# holds a live reference to a Kotlin-implemented object, repeated crossings resolve to
the <b>same</b> C#-side bridge instance, so <code>ReferenceEquals</code> holds; storing the object
and reading it back from Kotlin also resolves to the original Kotlin instance. Each interface a
class implements gets its own independent reuse: <code>RingLeader : IFeedable, IPerformer</code>
can hold one live <code>IFeedable</code> bridge and one live <code>IPerformer</code> bridge at a
time. This promise is scoped to a live bridge, not forever: once C# drops every reference and the
.NET GC collects the bridge, the next crossing mints a fresh one.</p>
</note>

<warning>
<p>While C# holds a Kotlin-implemented object, Kotlin keeps it pinned. That pin is released only
once the .NET GC actually collects the C#-side bridge, so a dropped object can linger for a while
after C# lets go of its own reference. This is expected, GC-timed behaviour, not a leak; there is
no way to force a prompt release.</p>
</warning>

A Kotlin implementation of a **derived** interface binds too: `Tabby : ITagged` (where `ITagged :
IFeedable`) can be passed at either an `ITagged`-typed or an `IFeedable`-typed C# parameter. A C#
object handed into a Kotlin interface-slot parameter transfers ownership to Kotlin, so an
implementation can safely store it past the call that delivered it, the same as an ordinary
reverse-bound parameter.

### Exposing a C# interface in your own Kotlin API

A bound C# interface can also appear at an ordinary **forward** parameter or return, on a Kotlin
author's own public API, using the original C# type:

```kotlin
// Farm.kt
class Farm {
  private var resident: IFeedable? = null

  fun adopt(feedable: IFeedable) {
    resident = feedable
  }

  fun resident(): IFeedable =
    resident ?: error("Farm.resident() called before Farm.adopt()")
}
```

The generated C# signature names the real `Test.Menagerie.IFeedable`, not a re-projected
duplicate, so it composes with the rest of that package's own API. A parameter is an ordinary
handle transfer, so a C#-implemented value passed in and returned back out is the same managed
instance, and a Kotlin implementation passed in resolves to the original Kotlin object:

```C#
// IntegrationTests/BidirectionalTests.cs
var farm = new Farm();
var goat = new CSharpGoat();
farm.Adopt(goat);
Assert.Same(goat, farm.Resident());
```

This works even for a consumer that never directly depends on the package `IFeedable` came from:
your library's own package metadata declares it as a transitive dependency.

Supported today: a non-nullable bound interface at an ordinary parameter or a method/top-level
function return. Not supported, each named on a build warning instead of silently skipped:

- A nullable interface-typed position (`IFeedable?`).
- An interface-typed property.
- An interface as a collection component (`List<IFeedable>`).
- A bound **class** at a forward position (`fun sanctuary(): Sanctuary`): not attempted at all.
- A return of a Kotlin implementation of an interface that has no C#-side bridge for it (see
  [Implementing a C#-declared interface in Kotlin](#implementing-a-c-declared-interface-in-kotlin)
  above); the same interface still works as a **parameter**.
- An interface stays unavailable at a forward position at all if it, or a base it extends, has a
  member that references an internal bound class: a public interface referencing an internal type
  wouldn't compile.

### Interface limitations

- Generic interfaces (`IBox<T>`) don't bind.
- A `static`, `static abstract`, or `static virtual` interface member is skipped.
- A default interface method is skipped.
- An interface with zero admissible members is skipped entirely.
- No downcast from an interface-typed value to a concrete bound class (see the note above).

## Methods and properties

Static and instance methods and properties bind on classes the same way; see
[Instance members](instance-members.md) and
[Static classes and methods](static-classes-and-methods.md). Struct members bind under their own
rules; see [C# structs](structs.md).

- `Task`- and `Task<T>`-returning methods bind as a Kotlin `suspend fun`; see
  [Instance members](instance-members.md#async-methods). A single trailing or mid-position
  `CancellationToken` parameter elides, with cancellation of the Kotlin side reaching the C# token;
  see [Cancellation](instance-members.md#async-cancellation).
- `ValueTask`, `ValueTask<T>`, and `IAsyncEnumerable<T>` don't bind yet. An `async` method on a
  struct, on a bound interface, or on a generic class also doesn't bind yet: each is skipped with
  its own diagnostic rather than silently dropped. A sync method taking a token, a method taking
  more than one, a nullable `CancellationToken?`, and a token on a constructor or property don't
  bind either.
- An indexer (`this[int]`) doesn't bind, on a class or an interface.
- An `event` member doesn't bind, on a class or an interface.

## Overload sets

Bridgeable C# method and constructor overloads keep their name and parameter types as ordinary
Kotlin overloads. An unsupported member is diagnosed independently and doesn't hide supported
siblings. If two different C# signatures would collapse to the same Kotlin scope, name, and
ordered parameter types, generation fails with an error naming both signatures instead of
producing a broken build.

## Types that cross the wire

Beyond primitives and `string` (see [Primitives and strings](primitives-and-strings.md); the
reverse direction has no nullable value types and no `Instant`/`Duration`/`Uuid` mapping), a
member's type binds as:

- a bound, non-static, non-value-type, non-`ref struct` class from the current extraction: an
  opaque handle, see [Objects and handles](objects-and-handles.md)
- a bridgeable struct: a decomposed, handle-free `data class`, see [C# structs](structs.md)
- a supported enum: an ordinal `Int` converted to and from a Kotlin `enum class`, see
  [Enums](#enums) above
- a bound, admissible interface: a Kotlin `interface`, see [Interfaces](#interfaces) above

Everything else, arrays, collections, delegates, `dynamic`, `object`, open generics, and a generic
instantiation of a definition outside the bound assemblies (`List<int>`), doesn't bind. A closed
instantiation of a bound generic **class** is the one generic shape that does; see
[Generic types](generic-types.md). `System.String` is the only external (out-of-assembly)
reference type recognized: a type from a namespace you didn't `include()`, from an assembly
outside the extraction run, or from an undeclared NuGet dependency is treated the same as an
unsupported type, even though the reader can see and name it.

## Exceptions

A C# exception thrown while dispatching a bound member is caught at the crossing and reaches
Kotlin as a catchable `NugetManagedException` instead of terminating the host:

```kotlin
try {
    infirmary.temperature("Oreo")
} catch (e: NugetManagedException) {
    e.managedType   // "System.ArgumentException"
    e.message       // "Oreo is not a registered patient"
}
```

`NugetManagedException` carries the .NET type's full name and its `Message`, verbatim, and nothing
else: no stack trace and no `InnerException`/cause chain today.

An `OperationCanceledException` (or any subtype, including `TaskCanceledException`) is the one
exception mapped differently: it surfaces as stdlib `CancellationException` with the
`NugetManagedException` as `cause`, not as a plain `NugetManagedException`; see
[Cancellation](instance-members.md#async-cancellation).

<note>
<p>An exception thrown by a Kotlin implementation of a C# interface (see
<a href="#implementing-a-c-declared-interface-in-kotlin">Implementing a C#-declared interface in
Kotlin</a> above) is catchable from both directions, but not as the same exception type: when C#
calls the bridge member directly, it throws the <code>KotlinException</code> family (see
<a href="exceptions.md">Exceptions</a>); when a call originates in Kotlin and reaches the same
member indirectly through an ordinary reverse-bound method, it surfaces in Kotlin as
<code>NugetManagedException</code> instead. The two channels aren't unified into one exception type
at that crossing yet.</p>
</note>

## Unsupported members show up as build warnings

Every member the reader excludes, and every enum, struct, or interface it can't bind, is recorded
with its type, member, and reason, and logged as a Gradle build warning when
`nugetGenerateBindings` runs. If a method you expected in Kotlin is missing, search the build log
for it by name rather than guessing why. An unsupported member never hides its siblings: the rest
of an overload set, or the rest of a class, still binds. For registration-time failures (a stale
build, a contract mismatch at process startup) rather than an extraction-time skip, see
[Registration diagnostics](registration-diagnostics.md).

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="static-classes-and-methods.md">Static classes and methods</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="instance-members.md">Instance members</a>
        <a href="structs.md">C# structs</a>
        <a href="generic-types.md">Generic types</a>
        <a href="registration-diagnostics.md">Registration diagnostics</a>
        <a href="exceptions.md">Exceptions</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/043-bridgeable-subset-boundary.md">ADR-043: Bridgeable subset boundary</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/070-csharp-interfaces-in-kotlin.md">ADR-070: C# interfaces in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/085-kotlin-implemented-csharp-interfaces.md">ADR-085: Kotlin-implemented C# interfaces</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/086-object-interface-slots-kotlin-bridge.md">ADR-086: Object- and interface-typed slots for a Kotlin-implemented C# interface</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/087-kotlin-slot-exceptions.md">ADR-087: Exceptions from Kotlin-implemented C# interface members</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/088-kotlin-implemented-interfaces-at-forward-positions.md">ADR-088: Kotlin-implemented interfaces at forward positions</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/089-bridge-reuse-per-kotlin-object.md">ADR-089: Bridge reuse per Kotlin object</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/104-reverse-thunk-error-channel.md">ADR-104: Reverse thunk error channel</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/072-closed-constructed-generics-in-kotlin.md">ADR-072: Closed constructed generics from C# in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/152-task-to-suspend-fun.md">ADR-152: Reverse Task/Task&lt;T&gt; to suspend fun</a>
    </category>
</seealso>
