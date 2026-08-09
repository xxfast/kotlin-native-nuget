# The bridgeable subset

Not every C# construct visible in assembly metadata can cross the C ABI. This page describes exactly
what binds and what doesn't, read directly from `NugetMetadataReader/Program.cs`, the tool that
does the actual filtering. Where the ADR prose and the reader's real behaviour could be read two
ways, this page follows the code.

## Types

| Construct | Binds? |
|---|---|
| Public top-level class | Yes, if it has at least one bridgeable member |
| Public top-level static class | Yes, as a Kotlin `object` (see [Static classes and methods](static-classes-and-methods.md)) |
| Interface | Yes, as a Kotlin `interface` plus a handle-backed implementation, when it is public, top-level, non-generic, and has at least one admissible member. See [Interfaces](#interfaces) below |
| Enum | Yes, as a standalone Kotlin `enum class`, when it is public, top-level, default-`int` backed, non-`[Flags]`, and has unique contiguous values from `0` through `N-1` |
| Struct / value type | Yes, but never as a **handle**. A struct is its own RIR node (never emitted as a class), and `CollectBoundHandleTypeNames` explicitly excludes any type whose base type is `System.ValueType` or `System.Enum`, so it can never become an object-handle parameter or return. A bridgeable struct decomposes into an immutable Kotlin `data class` instead, whether it has a state constructor ("Shape A") or only public settable fields/auto-properties ("Shape B"); see [C# structs](structs.md) |
| `ref struct` (`Span<T>`, `ReadOnlySpan<T>`, custom) | No. Detected via the `IsByRefLikeAttribute` custom attribute; any member referencing one is skipped and diagnosed (`skipped_ref_struct`) |
| Nested type (public or not) | No. The reader filters on `TypeAttributes.VisibilityMask == Public`, which excludes `NestedPublic` as well as every non-public visibility. Only top-level public types are candidates at all |
| Generic type | An open generic type parameter still skips per-member (`skipped_open_generic`). A generic **class** reached through at least one closed instantiation (`Box<int>`) binds fully as a real Kotlin generic class, one per instantiation registration export; see [Generic types](generic-types.md) and [ADR-072](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/072-closed-constructed-generics-in-kotlin.md). Generic **interfaces** still don't bind at all (`skipped_generic_interface`) |
| Record | Not recognized as a distinct construct at all: a C# `record class` compiles to an ordinary class in IL with no marker, and each constructor/member is evaluated by the same rules as any other class |

Classes (static or not), supported enums, and admissible interfaces produce Kotlin output.

## Enums

A supported C# enum becomes a standalone Kotlin `enum class`. Its member names are converted to
Kotlin `SCREAMING_SNAKE_CASE`. Enum values cross the C ABI as their ordinal `Int`, so enum arguments,
returns, constructors, and properties use the generated Kotlin enum type without allocating an
object handle or registration table for the enum itself.

```c#
// TestDependency/CatMood.cs
public enum CatMood
{
    Playful,
    Sleepy,
    Hungry,
}
```

```kotlin
// build/nuget-interop/kotlin/nativeMain/sample/enums/CatMood.kt
enum class CatMood {
  PLAYFUL,
  SLEEPY,
  HUNGRY
}
```

An enum can appear in the supported members of a bound class. This Kotlin sample uses an enum
constructor argument, an instance property, a static property, and an enum return value:

```kotlin
// test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/sample/enums/CatMoodSample.kt
fun catMoodRoundTrip(): CatMood {
  CatMoodService.defaultMood = CatMood.SLEEPY

  val service = CatMoodService(CatMood.PLAYFUL)
  service.currentMood = CatMood.HUNGRY

  check(service.readDefault() == CatMood.SLEEPY) {
    "CatMoodService.defaultMood did not round trip through its instance method"
  }

  return service.advance(service.currentMood)
}
```

### Enum limitations

- `[Flags]` enums do not bind.
- The underlying type must be the default C# `int`.
- Values must be unique and contiguous from `0` through `N-1`. Explicit, sparse, negative, and
  aliased values do not bind.
- Nullable enums, nested enums, and enums as a collection element (`List<CatMood>`) are not yet
  supported. An enum used as the type argument of a bound generic **class** (`Box<CatMood>`) is
  supported, since it's in the [Generic types](generic-types.md) v1 vocabulary (ADR-072).

Unsupported enums are excluded with a `skipped_unsupported_enum` diagnostic in `reverse-ir.json`.

## Interfaces

A public, top-level, non-generic C# interface with at least one admissible member becomes a pure
Kotlin `interface`, plus a handle-backed implementation class (the same shape Xamarin calls an
"Invoker" for a bound Java interface):

```c#
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
// build/nuget-interop/kotlin/nativeMain/test/menagerie/IFeedable.kt
internal interface IFeedable {
  fun describe(): String
  fun feed(food: String)
  val legs: Int
  var nickname: String?
}
```

The interface itself carries no handle. A value arriving at an interface-typed position (a return,
a parameter, or a property) is wrapped in a generated `IFeedableHandle`, which implements `IFeedable`
and dispatches every member through its own registration slot table, one `[UnmanagedCallersOnly]`
thunk per member, keyed on the interface rather than any concrete class:

```kotlin
// build/nuget-interop/kotlin/nativeMain/test/menagerie/IFeedableHandle.kt (excerpt)
internal class IFeedableHandle internal constructor(handle: COpaquePointer) : IFeedable, NugetHandleOwner, AutoCloseable {
  override val handle: NugetObjectHandle = NugetObjectHandle(handle)
  ...
  override fun describe(): String {
    val fn = requireNotNull(IFeedableBindings.describe__fa0681f6f7a68dd9b326d010404efcfbFn) { ... }
    ...
  }
}
```

Because the per-interface slot table is keyed on the interface, not the runtime class, dispatch
works for a runtime type the generator never even named, bound or not, public or not: a `Sanctuary`
method returning `IFeedable` can hand back an `internal` C# class and Kotlin still calls through it
correctly.

Interface inheritance binds too: `interface ITagged : IFeedable` becomes `interface ITagged :
IFeedable` in Kotlin, and `ITaggedHandle` dispatches `IFeedable`'s inherited members through
`IFeedable`'s own slots, with no re-registration needed, as long as `IFeedable` is itself admissible
and bound. If the base interface is not admissible, the derived interface binds with only its own
declared members and an `info_inherited_interface_members_absent` diagnostic.

A bound class declares an implemented interface as a Kotlin supertype only when every interface
member has an identically-signed **public** bridged member on the class:

```kotlin
// build/nuget-interop/kotlin/nativeMain/test/menagerie/TaggedFerret.kt (excerpt)
internal class TaggedFerret internal constructor(handle: COpaquePointer) : ITagged, NugetHandleOwner, AutoCloseable {
  ...
}
```

A C# **explicit** interface implementation (`string IFeedable.Describe() => ...;`) is non-public in
metadata, so the reader can't see it as a class member at all; such a class omits the supertype
entirely and gets a `skipped_interface_supertype` diagnostic instead of invalid Kotlin.

<note>
<p>An interface-typed value always becomes the generated handle type (<code>IFeedableHandle</code>),
never the concrete bound class, even when the runtime object is bound. There is no downcast:
<code>star() as? Ferret</code> is always <code>null</code>. This extends the "new wrapper per
crossing, no identity caching" rule from identity to type: one C# object reachable both as
<code>IFeedable</code> and as a bound <code>Ferret</code> yields two unrelated Kotlin objects of two
unrelated Kotlin types.</p>
</note>

Passing a bound class at an interface-typed parameter works through the same `NugetHandleOwner`
marker every generated wrapper implements.

### Implementing a C#-declared interface in Kotlin

A plain Kotlin class implementing `IFeedable`, with **no** `NugetHandleOwner` (so not one of the
generated wrappers above), can be passed back at the same interface-typed parameter or property:

```kotlin
// test-library/.../menagerie/MenagerieSample.kt
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

`nugetHandle()`, the same lowering a `NugetHandleOwner` already goes through, falls back to minting
a C#-side bridge instead of erroring:

```kotlin
// build/nuget-interop/kotlin/nativeMain/io/github/xxfast/kotlin/native/nuget/internal/NugetRuntime.kt
internal fun Any.nugetHandle(interfaceName: String): NugetObjectHandle =
  (this as? NugetHandleOwner)?.handle
    ?: nugetMintBridge(this, interfaceName)?.let { NugetObjectHandle(it) }
    ?: error(
      "[nuget] ${this::class.simpleName} is a Kotlin implementation of " +
          "${interfaceName.substringAfterLast('.')}; passing a Kotlin-implemented C# " +
          "interface back to C# is not supported yet."
    )
```

`interfaceName` is the crossing position's fully qualified C# interface name
(`Test.Menagerie.IPerformer`), not just the value's runtime type. That matters when a single Kotlin
class implements more than one bound interface: `nugetMintBridge` keys its dispatch on that name,
so `RingLeader : IFeedable, IPerformer` mints an `IFeedable` bridge at an `IFeedable`-typed parameter
and an `IPerformer` bridge at an `IPerformer`-typed one, regardless of which interface `Menagerie.cs`
declares first:

```kotlin
// build/nuget-interop/kotlin/nativeMain/io/github/xxfast/kotlin/native/nuget/internal/NugetKotlinBridges.kt
internal fun nugetMintBridge(value: Any, interfaceName: String): COpaquePointer? = when {
  interfaceName == "Test.Menagerie.IFeedable" && value is test.menagerie.IFeedable -> test.menagerie.mintIFeedableBridge(value)
  interfaceName == "Test.Menagerie.IPerformer" && value is test.menagerie.IPerformer -> test.menagerie.mintIPerformerBridge(value)
  else -> null
}
```

The generated `mintIFeedableBridge` mints a `StableRef` for the Kotlin object and hands one
`staticCFunction` per admissible member, plus the ctx pointer, to a C#-registered factory:

```kotlin
// build/nuget-interop/kotlin/nativeMain/test/menagerie/IFeedableBindings.kt (excerpt)
internal fun mintIFeedableBridge(impl: IFeedable): COpaquePointer {
  val fn = requireNotNull(IFeedableBindings.createBridgeFn) { ... }
  val ctx: COpaquePointer = StableRef.create(impl).asCPointer()
  return requireNotNull(
    fn.invoke(
    staticCFunction(::iFeedableDescribe__fa0681f6f7a68dd9b326d010404efcfbSlot),
    staticCFunction(::iFeedableFeed__f22c2c6775e88e24afa4a7ce7d1612c5Slot),
    staticCFunction(::iFeedableLegsGetterSlot),
    staticCFunction(::iFeedableNicknameGetterSlot),
    staticCFunction(::iFeedableNicknameSetterSlot),
    ctx,
    ),
  ) { "[nuget] CreateIFeedableBridge returned a null bridge handle." }
}
```

C# receives a real `IFeedable`: a generated `IFeedableBridge` dispatching every member through
those Kotlin function pointers, exactly like a hand-written implementation would:

```C#
internal sealed unsafe class IFeedableBridge : IFeedable, INugetKotlinBridge
{
    private readonly KotlinRefHandle _ctx;
    // one delegate* unmanaged[Cdecl] field per member ...

    public string Describe()
    {
        IntPtr resultPtr = _describe__fa0681f6f7a68dd9b326d010404efcfb(_ctx.DangerousGetHandle());
        try
        {
            return Marshal.PtrToStringUTF8(resultPtr)!;
        }
        finally
        {
            if (resultPtr != IntPtr.Zero) NugetKotlinNative.nuget_kotlin_string_free(resultPtr);
        }
    }
    // Feed(string), Legs, Nickname follow the same pattern
}
```

From C#, a Kotlin-implemented `IFeedable` is indistinguishable from a bound one: `Sanctuary.Introduce`,
`Sanctuary.FeedAnimal`, and `Sanctuary.Featured` all dispatch straight into the Kotlin object, and
the Kotlin side can observe the call landed:

```C#
// IntegrationTests/MenagerieRoundTripTests.cs
string result = MenagerieSample.kotlinGoatIntroduce();
Assert.Equal("introduced Nibbles the goat with 4 legs", result);
```

<note>
<p>Storing a Kotlin-implemented object in C# and reading it back resolves to the original Kotlin
instance on the Kotlin side (<code>sanctuary.featured === goat</code>), through an
identity-token probe registered alongside the interface's own factory thunk. The reverse now holds
too, while the bridge is alive: repeated crossings of the same Kotlin object resolve to the
<b>same</b> C#-side bridge instance, so <code>ReferenceEquals</code> holds
(<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/089-bridge-reuse-per-kotlin-object.md">ADR-089</a>).
Each interface a Kotlin object implements keeps its own reuse table, keyed on the object's
identity: <code>RingLeader : IFeedable, IPerformer</code> holds up to one live <code>IFeedable</code>
bridge and one live <code>IPerformer</code> bridge at a time, each reused independently. If C# drops
every reference and the .NET GC collects the bridge, the next crossing mints a fresh one; the
promise is scoped to a live bridge, not forever.</p>
</note>

<warning>
<p>While C# holds a Kotlin-implemented object, Kotlin keeps it pinned (a <code>StableRef</code>). That
pin is released only when the .NET GC actually collects the bridge C# was holding, which then runs a
<code>SafeHandle</code> release back into Kotlin; a dropped object can therefore linger for a while
after C# lets go of its own reference, until both garbage collectors get around to it. This is
expected, GC-timed behaviour, not a leak.</p>
</warning>

A Kotlin implementation of a **derived** interface binds too: `Tabby : ITagged` (where `ITagged :
IFeedable`) mints one flattened factory covering `ITagged`'s own slots plus every slot it inherits
from `IFeedable`, base before own, so C# calling either `Sanctuary.Showcase(ITagged)` or
`Sanctuary.Introduce(IFeedable)` with the same `Tabby` dispatches correctly at both positions.
Bound-object-handle and bound-interface parameters, returns, and properties (nullable included) bind
too, ownership following one rule: the side that receives the handle owns it. A C# object handed
into a Kotlin slot parameter transfers ownership to Kotlin, so a Kotlin implementation can safely
store it past the call that delivered it, the same posture an ordinary reverse-bound parameter
already has. A value a slot returns is always a fresh transfer handle the C# bridge member resolves
and frees immediately, so returning the implementation's own stored wrapper never hands back a
handle whose Kotlin owner could be collected mid-read
([ADR-086](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/086-object-interface-slots-kotlin-bridge.md)).

### Bridge reuse

Each `{Iface}Bindings.kt` (per plannable interface) carries a small identity-keyed table. `mint{Iface}Bridge`
consults it before minting a new bridge, and stores the freshly minted one weakly, so the table never
roots the bridge and cannot recreate the strong cross-runtime cycle that killed the reuse design in
[ADR-084](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/084-csharp-implemented-interfaces.md):

```kotlin
// build/nuget-interop/kotlin/nativeMain/test/menagerie/IFeedableBindings.kt (excerpt)
private val iFeedableBridgeTable: NugetBridgeTable = NugetBridgeTable()

internal fun mintIFeedableBridge(impl: IFeedable): COpaquePointer {
  val reused: COpaquePointer? = iFeedableBridgeTable.resolve(impl)
  if (reused != null) return reused
  val fn = requireNotNull(IFeedableBindings.createBridgeFn) { ... }
  val ctx: COpaquePointer = StableRef.create(impl).asCPointer()
  val bridge: COpaquePointer = requireNotNull(fn.invoke(/* slot pointers */, ctx)) { ... }
  iFeedableBridgeTable.store(impl, bridge, ctx)
  return bridge
}
```

The table's key is `kotlin.native.identityHashCode`-based (identity, never `equals`), and it holds
the bridge through two new interface-agnostic C# thunks riding the shared `<runtime>` registration:

```C#
// generated NugetRuntimeRegistration.cs (excerpt)
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr WeakenGcHandle_Thunk(IntPtr strong) =>
    GCHandle.ToIntPtr(GCHandle.Alloc(GCHandle.FromIntPtr(strong).Target, GCHandleType.Weak));

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr ResolveGcHandle_Thunk(IntPtr weak) =>
    GCHandle.FromIntPtr(weak).Target is object target
        ? GCHandle.ToIntPtr(GCHandle.Alloc(target))
        : IntPtr.Zero;
```

`resolve` promotes the weak handle to a fresh strong transfer handle while the bridge is alive, or
returns `null` once the .NET GC has collected it, the mint-fresh signal. Eviction happens in
`nuget_kotlin_release` (the same export a dropped bridge's `SafeHandle` already called), guarded by
the releasing bridge's own ctx pointer, so a late finalizer for a since-replaced bridge can never
evict a newer, still-live entry for the same object
([ADR-089](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/089-bridge-reuse-per-kotlin-object.md)).

<note>
<p>A dead weak handle found by <code>resolve()</code> is freed immediately, on the calling thread.
A weak handle evicted by <code>nuget_kotlin_release</code> is freed <b>lazily</b> instead, queued and
drained on the next mint for any interface, rather than freed from inside the release call itself:
that call already runs on the .NET finalizer thread, inside a C#-to-Kotlin P/Invoke, and calling
back into a Kotlin-to-C# thunk from there is a re-entry shape the ADR left unspiked. The design does
not depend on which variant runs; both keep the table's weak handles bounded.</p>
</note>

<warning>
<p>A Kotlin-implemented member that throws is now catchable from C#, but only when C# calls the
bridge member directly. Every generated slot body catches the exception, writes a Kotlin-owned
error envelope through a trailing out-parameter, and returns a dummy value; the C# bridge member
checks that out-parameter and throws the same public <code>KotlinException</code> family a forward
call throws, naming the member (<code>IFeedableBridge.Describe</code>) in its own stack trace
(<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/087-kotlin-slot-exceptions.md">ADR-087</a>
stage 2).</p>
</warning>

<warning>
<p>That propagation does not reach a call that <i>originates</i> in Kotlin. If Kotlin calls a
reverse-bound C# method (<code>Sanctuary.Introduce</code>, say) and that method calls the
Kotlin-implemented member internally, the thrown exception still terminates the process: the
Kotlin→C# call crosses its own <code>[UnmanagedCallersOnly]</code> thunk, which has no error
out-parameter of its own (<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md">ROADMAP.md</a>
Phase 11, unshipped), so the C# exception has nowhere to go once it escapes the bridge member. Only
a call C# makes directly into the bridge, holding a stored reference and calling a member on it, is
catchable today.</p>
</warning>

v1 slot vocabulary: `val`/`var` property getter-and-setter slots, and methods of arity 0-2
returning `Unit`, a primitive, `Boolean`, an enum, `String`, `String?`, a bound-object handle, or a
bound interface (nullable included for the last two). Still out of v1 and named-skipped
(`skipped_kotlin_bridge`) rather than silently dropped: a struct-typed slot, a generic-instance
slot, a collection-typed slot (`List`/`Map`/`Set`, blocked on the reverse direction having no BCL
collection mapping at any position yet, see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)
Phase 10), and a `Task`-returning member. See [ADR-085](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/085-kotlin-implemented-csharp-interfaces.md),
[ADR-086](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/086-object-interface-slots-kotlin-bridge.md),
[ADR-087](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/087-kotlin-slot-exceptions.md),
and [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 13 for
what's still open.

### Bound C# interfaces at forward positions

Everything above wires the reverse pipeline's own crossing points (`Sanctuary.Introduce` and
friends). A bound C# interface can also appear at an ordinary **forward** parameter or return, on a
Kotlin author's own public API, with the original C# type name, not a re-projected duplicate:

```kotlin
// test-library/.../menagerie/Farm.kt
class Farm {
  private var resident: IFeedable? = null

  fun adopt(feedable: IFeedable) {
    resident = feedable
  }

  fun resident(): IFeedable =
    resident ?: error("Farm.resident() called before Farm.adopt()")
}
```

The generated C# names the real `Test.Menagerie.IFeedable` from the consumer's own `TestDependency`
dependency, not a duplicate:

```C#
// generated Interop.cs (excerpt)
public global::Test.Menagerie.IFeedable Resident()
{
    IntPtr nativeResult = Native_Resident(_handle, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    GCHandle resultGcHandle = GCHandle.FromIntPtr(nativeResult);
    global::Test.Menagerie.IFeedable resultValue = (global::Test.Menagerie.IFeedable)resultGcHandle.Target!;
    resultGcHandle.Free();
    return resultValue;
}

public void Adopt(global::Test.Menagerie.IFeedable feedable)
{
    IntPtr feedableHandle = GCHandle.ToIntPtr(GCHandle.Alloc(feedable));
    Native_Adopt(_handle, feedableHandle, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
}
```

A parameter is an ordinary GCHandle transfer resolved by the same `nuget{Iface}Value` token probe
the reverse pipeline already generates, so a C#-implemented value passed in and returned back out is
the same managed instance, and a Kotlin implementation passed in resolves to the original Kotlin
object:

```C#
// IntegrationTests/BidirectionalTests.cs
var farm = new Farm();
var goat = new CSharpGoat();
farm.Adopt(goat);
Assert.Same(goat, farm.Resident());
```

`Test.Menagerie` resolves in a consumer that never directly references `TestDependency`: `TestLibrary`'s
own nuspec declares it as a package dependency, so NuGet's transitive restore surfaces it
([ADR-088](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/088-kotlin-implemented-interfaces-at-forward-positions.md)).

<note>
<p>The ADR-070 pure interface stub is public precisely so a Kotlin author can put it in their own
public signature; every other generated file for a bound interface (the handle class, class stubs,
bindings objects) stays <code>internal</code>. Whether the stub is emitted public at all is a
per-interface predicate, not a blanket flip: a stub goes public only when neither it nor any of its
bases has an internal class stub anywhere in its declared surface. <code>IKeeper</code> (whose
members reference the bound class <code>Ferret</code>, itself internal) stays internal and off the
forward export scan for exactly that reason: a public interface referencing an internal class would
not compile.</p>
</note>

v1 scope: non-nullable bound interfaces at ordinary parameters and method/top-level-function
returns, gated on the plugin's cross-pipeline manifest (`bound-types.json`). A return additionally
needs the interface to be Kotlin-implementable (the manifest's `implementable` flag, ADR-085
admissibility): a plain Kotlin implementation returned from a forward position has nothing to become
on the C# side otherwise. Every position outside that scope is a named skip, never silence:

```
[nuget:SKIPPED_BOUND_TYPE_POSITION] Skipping ...: ... . ADR-088 v1 marshals a bound C# interface at
ordinary, non-nullable function/method/constructor parameters and method/function returns only;
expose one of those instead of a nullable, property or collection-component position
```

- Nullable positions (`IFeedable?`): `SKIPPED_BOUND_TYPE_POSITION`.
- Property positions: `SKIPPED_BOUND_TYPE_POSITION` (property drops carry their own
  `boundInterface` flag rather than the parameter/return skip-reason enum, so the message can name
  the position without a second detail slot).
- Collection components (`List<IFeedable>` and friends): `SKIPPED_BOUND_TYPE_POSITION`.
- Bound **classes** at forward positions (`fun sanctuary(): Sanctuary`): not attempted at all, a
  bigger visibility change than this feature took on.
- A return of a plain Kotlin implementation of an interface the manifest does not flag
  Kotlin-implementable (no `mint{Iface}Bridge` for it): `SKIPPED_UNIMPLEMENTABLE_BOUND_INTERFACE`.
  The same interface stays admissible as a **parameter**, since that direction only needs
  `nuget{Iface}Value`. Every bound interface in this project's own fixtures is Kotlin-implementable
  today, so this skip fires only against a synthetic manifest entry in the test suite, not against
  `IFeedable`/`ITagged`/`IPerformer`.

### Interface limitations

- Generic interfaces (`IBox<T>`) do not bind: `skipped_generic_interface`.
- Any `static`, `static abstract`, or `static virtual` interface member is skipped:
  `skipped_interface_static_member`.
- A default interface method is skipped: `skipped_default_interface_method` (unchanged, pre-existing).
- An indexer (`this[int]`) is skipped: `skipped_indexer`. This also now applies to classes, a
  pre-existing silent gap turned into a diagnostic.
- An `event` member is skipped: `skipped_event`. This also now applies to classes, a pre-existing
  silent gap turned into a diagnostic.
- An interface with zero admissible members is skipped entirely: `skipped_empty_interface`.
- No downcast from an interface-typed value to a concrete bound class (see the note above).
- A Kotlin implementation with a struct-typed, generic-instance-typed, or collection-typed
  (`List`/`Map`/`Set`) member, or a `Task`-returning member, is out of the v1 slot vocabulary and
  named-skipped (`skipped_kotlin_bridge`), not bridged. An *unbound* object or interface type at a
  slot position keeps the same named skip; a bound one now binds (see the ADR-086 note above).

## Constructors

- Every independently bridgeable public instance constructor on a class binds as a Kotlin secondary
  constructor (see [Objects and handles](objects-and-handles.md)).
- A Shape A struct has exactly one state-covering constructor that defines its data-class
  components. Other bridgeable public constructors bind as secondary constructors backed by
  registration slots (see [C# structs](structs.md)).
- A Shape B struct (no state-covering constructor; public settable fields/auto-properties instead)
  binds **no** constructors at all. Its `data class` primary constructor already reaches every
  component, so every public constructor on it, alternate or otherwise, is skipped with a diagnostic
  rather than bound (see [C# structs](structs.md)).
- A non-public constructor is excluded before the constructor-admission check even runs: the reader
  tests `MethodAttributes.MemberAccessMask == Public` first, so `private`/`internal`/`protected`
  constructors are silently invisible, never diagnosed.
- Static classes and interfaces never carry a constructor at all (a static class is `abstract
  sealed` in metadata and has no instance `.ctor`; interfaces have none by definition).

## Methods and properties

- **Static methods** and **instance methods** both bind on classes. Each overload is checked
  independently against the parameter/return type rules.
- **Instance properties** and **static properties** bind: read-only → `val`, settable → `var` (see
  [Instance members](instance-members.md) and [Static classes and methods](static-classes-and-methods.md)).
- **Struct members** also bind: public non-void instance methods, get-only computed properties that
  are not component `readName`s, and static methods (Kotlin `companion object`). Skipped on structs:
  `Equals`/`GetHashCode`/`ToString`/`Deconstruct`, operators, setters, void instance methods, and
  component auto-properties. Wire form reconstructs the receiver from leading component args (see
  [C# structs](structs.md)).
- **`async`/`Task`-returning methods do not bind.** The reader recognizes `Task`, `Task<T>`,
  `ValueTask`, `ValueTask<T>`, and `IAsyncEnumerable<T>` by name and emits an *informational*
  diagnostic (`info_async_not_yet_mapped`), not a skip-with-reason like the others; the method is
  still excluded from the generated output either way.

### The visibility mask bug this project already hit

Methods and property accessors are filtered with `(attrs & MemberAccessMask) == Public`, an exact
equality check against the masked value, not a non-zero `AND` test. This distinction matters: `AND`
against `Public` (`0x6`) also matches `Assembly`/`internal` (`0x3`) and `Family`/`protected` (`0x4`)
because both share bits with `0x6`. An earlier version of the reader used the non-zero-AND form and
leaked internal members through as if they were public; it's called out here because it's exactly
the kind of subtle metadata-reading detail that's easy to reintroduce.

## Overload sets

Bridgeable C# method and constructor overloads keep their name and parameter types as ordinary
Kotlin overloads. An unsupported member is diagnosed independently and does not hide supported
siblings. Generated thunk and function-pointer names use a stable identifier derived from the full
managed signature, so they remain unique without changing the public Kotlin API.

If two different managed signatures map to the same Kotlin scope, name, and ordered parameter
types, `nugetGenerateBindings` fails with `error_kotlin_signature_collision` and names both managed
signatures. This is a defensive invariant, not a reason to collapse useful type distinctions.
Phase 10 mappings must retain their respective interfaces, for example
`IReadOnlyList<T>` as `List<T>` and `IEnumerable<T>` as `Iterable<T>`, so overloads using them remain
distinct.

## The bridgeable type vocabulary

| Kind | Bridges as |
|---|---|
| `void` | return position only |
| `string` | UTF-8 marshalled `IntPtr` |
| `bool`, `byte`, `short`, `int`, `long`, `float`, `double`, `char` | primitives, direct or narrowed for ABI blittability |
| A supported enum | ordinal `Int`, converted to and from a Kotlin `enum class` |
| A bound, non-static, non-value-type, non-`ref struct` class from the current extraction | an opaque `GCHandle`-backed pointer (a "handle" type) |
| A bridgeable struct (Shape A or Shape B) | decomposed onto the wire, one ABI argument per component (parameter) or one out-pointer per component (return); surfaces as an immutable Kotlin `data class`, no handle. Methods and get-only computed properties on the struct bind via reconstruct-on-call (leading component args). See [C# structs](structs.md) |

`string` and a bound handle type both carry real nullability now: a `NullableAttribute`/
`NullableContextAttribute`-derived `nullable` flag on the `RirTypeRef` decides `String` vs. `String?`
and `Foo` vs. `Foo?` for every return, parameter, and property position (see
[Objects and handles](objects-and-handles.md) and [Instance members](instance-members.md); ADR-053).
`Nullable<T>` value types (`int?`, `CatMood?`) are the one nullable shape that still does not bridge:
they carry no `NullableAttribute` at all (a nullable value type is `System.Nullable<T>`, a distinct
closed generic struct). The wire format for it is no longer an open question: [ADR-056](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/056-csharp-structs-in-kotlin.md)'s
struct out-pointer convention is exactly the format `Nullable<T>` needs, so this is unblocked and
needs no further ADR, only the reader work to stop dropping `System.Nullable<T>` at
`GetGenericInstantiation` (tracked in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 9).

Everything else, arrays, collections, delegates, `dynamic`, `object`, open generics, and generic
instantiations of a definition outside the bound assemblies (`List<int>`), does not bridge. A closed
instantiation of a bound generic **class** is the one generic shape that does; see
[Generic types](generic-types.md).
`System.String` is the only external (out-of-assembly) reference type recognized; every other
external type reference is unbound and diagnosed (`skipped_unbound_type_reference`).

### Why a type reference might be "unbound" rather than a hard type mismatch

A parameter or return whose type is a class from a namespace you didn't `include()`, or from an
assembly outside this extraction run entirely, isn't a type-vocabulary failure, it's an *unbound*
reference: the type exists, the reader can name it, but it wasn't part of the bound set, so it can't
become a handle. This produces the same `skipped_unbound_type_reference` diagnostic whether the type
is external (e.g. a type from a NuGet dependency you didn't declare) or merely excluded by your own
`include()`/`exclude()` filters.

## Exceptions are not propagated

A C# exception thrown inside a thunk is never caught. `[UnmanagedCallersOnly]` thunks contain no
`try`/`catch` at all, by design: a managed exception cannot cross the managed/native boundary
gracefully, and the .NET runtime tears the whole host process down (`FailFast`) if one tries. This is
the accepted v1 behaviour, not a bug, chosen specifically over catch-and-return-a-sentinel, because a
sentinel value (`0`, `false`, `null`) is indistinguishable from a legitimate result for a
primitive-or-`void`-returning method. A crash is loud; a wrong answer is not.

```C#
// v1: no try/catch - a thrown C# exception escapes and fast-fails the host process
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static IntPtr SerializeObject_Thunk(int value)
{
    string result = JsonConvert.SerializeObject(value);
    return Marshal.StringToCoTaskMemUTF8(result);
}
```

Graceful propagation into a catchable Kotlin exception is tracked as
[ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 11.

<note>
<p>A Kotlin-implemented interface member is the one place propagation already works, and only
partially: see the two warnings under "Implementing a C#-declared interface in Kotlin" earlier on
this page. It is catchable when C# calls the bridge member directly, and still fatal when the call
originates in Kotlin and calls back into the member through a reverse-bound method, exactly the gap
this section describes, since that outer reverse thunk has no error path of its own.</p>
</note>

## Diagnostics: recorded in the RIR and surfaced to the build

Every skip the metadata reader makes is recorded in `reverse-ir.json`, under each assembly's
`diagnostics` array, as a `RirDiagnostic`:

```kotlin
// nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/rir/RirModel.kt
data class RirDiagnostic(
  val kind: RirDiagnosticKind,
  val typeName: String,
  val memberName: String,
  val memberSignature: String,
  val reason: String,
  val hint: String,
)

enum class RirDiagnosticKind {
  SKIPPED_OVERLOAD_SET,
  SKIPPED_REF_STRUCT,
  SKIPPED_OPEN_GENERIC,
  SKIPPED_DYNAMIC,
  SKIPPED_DEFAULT_INTERFACE_METHOD,
  SKIPPED_UNBOUND_TYPE_REFERENCE,
  SKIPPED_MEMBER_NAME_COLLISION,   // Gradle-plugin-side only, see below
  SKIPPED_UNSUPPORTED_ENUM,
  SKIPPED_UNSUPPORTED_STRUCT,
  ERROR_KOTLIN_SIGNATURE_COLLISION,
  INFO_ASYNC_NOT_YET_MAPPED,
  INFO_OBLIVIOUS_NULLABILITY,      // ADR-053: an un-annotated (oblivious) reference type bound non-null
  SKIPPED_GENERIC_INTERFACE,               // ADR-070: open generic interface, arity-mangled CLR name
  SKIPPED_INTERFACE_STATIC_MEMBER,         // ADR-070: static/static abstract/static virtual interface member
  SKIPPED_INDEXER,                         // ADR-070: this[int] (also fires for classes)
  SKIPPED_EVENT,                           // ADR-070: event member (also fires for classes)
  SKIPPED_EMPTY_INTERFACE,                 // ADR-070: interface with zero admissible members
  SKIPPED_INTERFACE_SUPERTYPE,             // ADR-070: a class's interface member is non-public (explicit impl)
  INFO_INHERITED_INTERFACE_MEMBERS_ABSENT, // ADR-070: base interface not admissible/bound
  SKIPPED_UNBOUND_GENERIC_INSTANTIATION,   // ADR-072: instantiation of a definition outside the bound assemblies
  SKIPPED_GENERIC_TYPE_ARGUMENT,           // ADR-072: type argument outside the v1 vocabulary
  SKIPPED_NULLABLE_TYPE_PARAMETER,         // ADR-072: a bare type parameter annotated nullable
  SKIPPED_AMBIGUOUS_GENERIC_CONSTRUCTOR,   // ADR-072: Gradle-plugin-side only, see below
  INFO_UNINSTANTIATED_GENERIC_TYPE,        // ADR-072: bound generic definition, zero discovered instantiations
  ERROR_GENERIC_ARITY_NAME_COLLISION,      // ADR-072: Gradle-plugin-side only, see below
  SKIPPED_KOTLIN_BRIDGE,                   // ADR-085: a Kotlin implementer can't get a bridge factory for this interface/member
}
```

`INFO_OBLIVIOUS_NULLABILITY` is emitted once per assembly when the *whole* assembly carries no
`NullableAttribute`/`NullableContextAttribute` anywhere (a legacy, pre-C#-8, or
`<Nullable>disable</Nullable>` package), and once per member for an oblivious island (a
`#nullable disable` region) inside an otherwise-annotated assembly. Either way the member still binds,
non-null, per [ADR-053](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md);
the diagnostic just records that the binding is an assumption, not a read fact.

`skipped_overload_set` remains readable for older `reverse-ir.json` files, but the metadata reader
does not emit it for otherwise supported overloads. `error_kotlin_signature_collision` is fatal;
`skipped_*` diagnostics remain warnings and `info_*` diagnostics remain informational warnings.

`NugetGenerateBindingsTask` calls a single diagnostic path that first rejects every `ERROR_*` entry,
then formats the remaining diagnostics as Gradle build warnings. It combines every reader-emitted diagnostic (from each
assembly's `diagnostics` array: `ref struct` parameters, open generics, `dynamic`,
default interface methods, unbound type references, async-not-yet-mapped, and ADR-053's
oblivious-nullability notes) with the Gradle-plugin-derived `SKIPPED_MEMBER_NAME_COLLISION`
diagnostics (the instance-member-name-vs-wrapper collision described in
[Instance members](instance-members.md), which depends on the Kotlin wrapper's own member names and
so can only be computed plugin-side) through one shared formatter, and logs each one with
`logger.warn(...)`. An `ERROR_*` kind fails generation before sources are written. A `SKIPPED_*`
kind is logged as "Skipping ..." (the member is absent from the
generated output); an `INFO_*` kind is logged as "Note ..." (the member still binds, under an assumed
policy).

<note>
<p>Not every diagnostic kind can appear in <code>reverse-ir.json</code>. <code>skipped_ambiguous_generic_constructor</code>
and <code>error_generic_arity_name_collision</code> (ADR-072) are computed entirely Gradle-plugin-side,
the same way <code>skipped_member_name_collision</code> already was: the ambiguity rule is stated in
terms of parameter lists erased to non-null <b>Kotlin</b> types, which only <code>NugetGenerateBindingsTask</code>
can compute, not the metadata reader. A build log line is the only place either one shows up; a
generic-heavy package's <code>reverse-ir.json</code> alone will not explain why a fake constructor
or a whole type is missing.</p>
</note>

If a method you expected to see in Kotlin is missing, the build log now names it and says why; the
underlying `reverse-ir.json` `diagnostics` array is still there for programmatic inspection, but
reading it by hand is no longer the only way to find out. What is not yet built: a structured,
queryable diagnostics report (only a Gradle log line exists today), tracked in
[ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md) Phase 8.

<seealso>
    <category ref="related">
        <a href="reverse-overview.md">Consuming C# in Kotlin</a>
        <a href="static-classes-and-methods.md">Static classes and methods</a>
        <a href="objects-and-handles.md">Objects and handles</a>
        <a href="instance-members.md">Instance members</a>
        <a href="structs.md">C# structs</a>
        <a href="generic-types.md">Generic types</a>
        <a href="registration-diagnostics.md">Registration diagnostics</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/043-bridgeable-subset-boundary.md">ADR-043: Bridgeable subset boundary</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/072-closed-constructed-generics-in-kotlin.md">ADR-072: Closed constructed generics from C# in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/046-reverse-ir-model-and-json-contract.md">ADR-046: Reverse IR model and JSON contract</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/049-csharp-registration-shim-generation.md">ADR-049: C# registration shim generation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/053-nullable-reference-types-in-kotlin.md">ADR-053: Nullable reference types in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/056-csharp-structs-in-kotlin.md">ADR-056: C# structs (value types) in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/058-csharp-shape-b-structs-in-kotlin.md">ADR-058: C# Shape B structs in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/070-csharp-interfaces-in-kotlin.md">ADR-070: C# interfaces in Kotlin</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/085-kotlin-implemented-csharp-interfaces.md">ADR-085: Kotlin-implemented C# interfaces</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/086-object-interface-slots-kotlin-bridge.md">ADR-086: Object- and interface-typed slots for a Kotlin-implemented C# interface</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/087-kotlin-slot-exceptions.md">ADR-087: Exceptions from Kotlin-implemented C# interface members</a>
    </category>
</seealso>
