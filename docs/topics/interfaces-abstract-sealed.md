# Interfaces, abstract classes, and sealed classes

Kotlin's three flavours of inheritance each get a distinct C# shape: `interface` becomes an `I`-prefixed C# interface with default methods delegating back to Kotlin, `abstract class` becomes a C# `abstract class` whose subclasses share an inherited `_handle`, and `sealed class` becomes an abstract class with its subtypes nested inside it.

| Kotlin | C# | Notes |
|---|---|---|
| `interface` | `interface` (`I`-prefixed) | default methods delegate to Kotlin |
| `abstract class` | `abstract class` | `_handle` inherited by subclasses |
| `sealed class` | `abstract class` | subclasses nested, see [ADR-009](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md) |
| interface-typed return (method result or property) | `IFoo` / `IFoo?` | backed by a generated `sealed class Foo : IFoo`, see [ADR-040](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/040-interface-return-type-mapping.md) |
| interface-typed parameter, a C# class implementing `IFoo` | accepted, no `_handle` needed | dispatched through a per-interface bridge factory, see [ADR-084](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/084-csharp-implemented-interfaces.md) |
| nullable property on a sealed subclass (`String?`, `Int?`) | `string?` / `int?` | `String?` is one export returning `string?`; `Int?` is a `_has_value`/`_value` pair rendered as one `?:` expression |
| property whose own type is a sealed class (bare, nullable, or a read-only collection component) | the sealed base | materialised through `<Base>.FromHandle(...)`, see [Sealed types as property types](#sealed-types-as-property-types), [ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md) |

## Kotlin

From `test-library/src/nativeMain/kotlin/.../cat/Pet.kt` and `Animal.kt`:

```kotlin
interface Pet {
  val name: String
  fun speak(): String
  fun greet(): String = "Hi, I'm $name"
}

abstract class Animal(override val name: String) : Pet {
  override fun greet(): String = "Hi, I'm $name"

  fun introduce(): String = "My name is $name"
}
```

`Cat` (see [Classes and objects](classes-and-objects.md)) extends `Animal`, which implements `Pet`.

A sealed hierarchy, from `test-library/src/nativeMain/kotlin/.../cat/Observation.kt`:

```kotlin
sealed class Observation {
  data object Superposition : Observation()
  data class Alive(val cat: Cat) : Observation()
  data class Dead(val cause: String) : Observation()
}

fun openBox(name: String): Observation {
  if (name == "Oreo") return Observation.Alive(Cat("Oreo"))
  return Observation.Dead("The cat was not $name")
}
```

## Generated C#

From `Interop.cs`. `IPet` gets a default-implemented `Greet()`, and `Animal` is `abstract` with an inherited `_handle`:

```C#
public interface IPet : IDisposable
{
    string Name { get; }

    string Speak();
    string Greet();
}

public abstract class Animal : IPet
{
    internal IntPtr _handle;

    internal Animal(IntPtr handle)
    {
        _handle = handle;
    }

    public string Name
    {
        get { /* ... */ }
    }

    public string Greet()
    {
        /* delegates to the Kotlin-side override */
    }

    public string Introduce()
    {
        /* ... */
    }

    public abstract string Speak();

    public abstract void Dispose();
}
```

`Cat : Animal` inherits `_handle` and only overrides `Speak()` and `Dispose()`. It never redeclares the field.

`Observation` renders as an abstract class with each Kotlin subtype as a nested `sealed class`, plus a `FromHandle` dispatcher that reads a type tag off the native handle:

```C#
public abstract class Observation : IDisposable
{
    internal IntPtr _handle;

    public sealed class Alive : Observation
    {
        internal Alive(IntPtr handle) : base(handle) { }

        public Cat Cat => new Cat(Native_Get_cat(_handle, out _));

        public override bool Equals(object? obj) { /* ... */ }
        public override int GetHashCode() => Native_HashCode(_handle);
        public override string ToString() => Marshal.PtrToStringUTF8(Native_ToString(_handle))!;
        public override void Dispose() { /* ... */ }
    }

    public sealed class Dead : Observation
    {
        public string Cause => Marshal.PtrToStringUTF8(Native_Get_cause(_handle, out _))!;
        // Equals / GetHashCode / ToString / Dispose ...
    }

    public sealed class Superposition : Observation
    {
        public override string ToString() => "Superposition";
        public override void Dispose() { /* ... */ }
    }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "observation_get_type")]
    private static extern int Native_GetType(IntPtr handle);

    internal static Observation FromHandle(IntPtr handle)
    {
        return Native_GetType(handle) switch
        {
            0 => new Alive(handle),
            1 => new Dead(handle),
            2 => new Superposition(handle),
            _ => throw new InvalidOperationException("Unknown sealed class type")
        };
    }

    public abstract void Dispose();
}
```

`Alive` and `Dead` are Kotlin `data class` subtypes, so they also get `Equals`/`GetHashCode`/`ToString` (see [Data classes](data-classes.md)). `Superposition` is a `data object`, so it has a fixed `ToString()` and no `Equals`/`GetHashCode` override (reference equality is enough for a singleton).

## Sealed types as property types

A property whose type is a sealed class binds as the sealed **base**, and the C# getter materialises it through the generated `FromHandle` discriminator above rather than through a constructor (the base is `abstract`, so `new` would not compile). This covers the bare type, the nullable spelling, and a read-only collection whose component is sealed ([#54](https://github.com/xxfast/kotlin-native-nuget/issues/54), [ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md)).

From `test-library/src/nativeMain/kotlin/.../issue54/Issue54Sample.kt`:

```kotlin
data class Issue54Drawing(
  val shape: Issue54Shape,
  val maybe: Issue54Shape?,
  val shapes: List<Issue54Shape>,
  var current: Issue54Shape,
)
```

```C#
public global::TestLibrary.Issue54.Issue54Shape Shape
{
    get
    {            IntPtr nativeResult = Native_Get_shape(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return global::TestLibrary.Issue54.Issue54Shape.FromHandle(nativeResult);
    }
}
```

The nullable getter returns `null` for a null handle and discriminates otherwise; a `List` component is read per element through `NugetMarshal.FromHandle<T>`, which dispatches to the same discriminator; and a `var` of a sealed type gets an ordinary setter that passes `value._handle`. Because the getter hands back a real subclass instance, a C# consumer can pattern match.

From `IntegrationTests/Issue54Tests.cs`:

```C#
using Issue54Drawing drawing = Issue54Sample.curledCats();

using Issue54Shape shape = drawing.Shape;

string description = shape switch
{
    Issue54Shape.Circle c => $"Oreo curled at r={c.Radius}",
    Issue54Shape.Empty => "Mylo sprawled",
    _ => throw new InvalidOperationException(),
};

Assert.Equal("Oreo curled at r=7.5", description);
```

`Issue54Drawing` itself has no public constructor and no generated `Copy`: every one of its
constructor parameters is sealed-typed. A sealed type at a **parameter** position is not on this
feature's route at all, ADR-105 touched only the property planner, so the whole constructor stays
skipped, pre-existing and unaffected either way. Unlike the property positions above, this drops
with **no diagnostic at all**, the same `SEALED_PROTOCOL` legacy-route deferral as the return-side
gap below; see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)
([details](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/backlog/sealed-collection-return-silently-drops.md)).

What still skips at a property position, each named by a build warning rather than silently dropped:

| Shape | Status |
|---|---|
| `var shapes: MutableList<Shape>` | Binds **get-only**; the setter is skipped with the read-only property warning, because writing a sealed base into a Kotlin collection from C# is not implemented |
| a sealed **interface** (`sealed interface Filter`), bare or as a collection component | Skipped: only a sealed *class* is given a `FromHandle` discriminator |
| a value class whose underlying type is sealed | Skipped |

A sealed collection component at a **return or parameter** position (`fun x(): List<Shape>`,
`fun f(shapes: List<Shape>)`) is a separate, still-open slot, deferred as
[ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md)
scope (d): neither binds. A **top-level** bare sealed return (`fun x(): Shape`) does bind; the same
signature as a **class method** is silently dropped instead, a pre-existing, separate gap (see
[ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)). Established by
source reading, not run: `fun x(): List<Shape>` is believed to drop the same way, silently, rather
than skip named.

## Collection properties on sealed subclasses

A `List<T>` property on a sealed subclass renders as a `get { ... }` block, the same shape a `List<T>` property gets on an ordinary class (see [Collections](collections.md)), and carries the same `out IntPtr error` convention:

From `test-library/src/nativeMain/kotlin/.../cat/Issue39Sample.kt`:

```kotlin
data class Issue39Item(val name: String, val count: Int)

sealed class Issue39State {
  data class Loaded(val items: List<Issue39Item>, val refreshing: Boolean) : Issue39State()

  data object Loading : Issue39State()
}
```

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue39state_loaded_get_items")]
private static extern IntPtr Native_Get_items(IntPtr handle, out IntPtr error);

public IReadOnlyList<Issue39Item> Items
{
    get
    {
        IntPtr listHandle = Native_Get_items(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        int count = NugetListNative.Count(listHandle);
        var result = new List<Issue39Item>(count);
        for (int i = 0; i < count; i++)
        {
            result.Add(NugetMarshal.FromHandle<Issue39Item>(NugetListNative.Get(listHandle, i)));
        }
        NugetListNative.Dispose(listHandle);
        return result.AsReadOnly();
    }
}
```

The scalar `Refreshing` getter on the same subclass stays expression-bodied. Since [#38](https://github.com/xxfast/kotlin-native-nuget/issues/38) every sealed-subclass getter declares the error slot, so it passes `out _` without reading it back:

```C#
public bool Refreshing => Native_Get_refreshing(_handle, out _);
```

From `IntegrationTests/Issue39Tests.cs`:

```C#
using Issue39State state = Issue39Sample.loadedCats();
var loaded = Assert.IsType<Issue39State.Loaded>(state);

IReadOnlyList<Issue39Item> items = loaded.Items;
Assert.IsAssignableFrom<IReadOnlyList<Issue39Item>>(items);
Assert.Equal(2, items.Count);
```

<note>
    <p>A <code>Map</code>/<code>Set</code> property on a sealed subclass now parses too (the same
    block-vs-expression branch fixed this). Its <code>DllImport</code> carries the
    <code>out IntPtr error</code> slot like every other sealed getter, but the C# getter passes
    <code>out _</code>: only <code>List</code>/<code>MutableList</code> read the slot back and
    throw.</p>
</note>

## Payload types from another namespace

A sealed subclass whose property types come from a different exported package spells them fully qualified, `global::Namespace.Name`, in the property type, the `new List<T>(count)` allocation, the `FromHandle<T>` element read and the `new T(handle)` constructor call ([#50](https://github.com/xxfast/kotlin-native-nuget/issues/50)). This is the same rule [#41](https://github.com/xxfast/kotlin-native-nuget/issues/41) applied to top-level classes; the sealed-subclass renderer had been left on bare simple names, which only resolve when both types happen to share a namespace.

### Kotlin {id="cross-namespace-sealed-kotlin"}

From `test-library/src/nativeMain/kotlin/.../Issue50Feed.kt` (root package) and `.../issue50/Issue50Remote.kt` (sub-package):

```kotlin
sealed class Issue50State {
  data object Loading : Issue50State()

  data class Success(
    val crew: List<Issue50Assignment>,
    val position: Issue50Position,
  ) : Issue50State()
}
```

### Generated C# {id="cross-namespace-sealed-csharp"}

`Issue50State` lands in `TestLibrary`; its payloads land in `TestLibrary.Issue50`:

```C#
public sealed class Success : Issue50State
{
    public IReadOnlyList<global::TestLibrary.Issue50.Issue50Assignment> Crew
    {
        get
        {
            // ...
            var result = new List<global::TestLibrary.Issue50.Issue50Assignment>(count);
            for (int i = 0; i < count; i++)
            {
                result.Add(NugetMarshal.FromHandle<global::TestLibrary.Issue50.Issue50Assignment>(NugetListNative.Get(listHandle, i)));
            }
            // ...
        }
    }

    public global::TestLibrary.Issue50.Issue50Position Position => new global::TestLibrary.Issue50.Issue50Position(Native_Get_position(_handle, out _));
}
```

`Map` and `Set` components and enum-typed properties on a sealed subclass take the same spelling.

## Nullable properties on sealed subclasses

A nullable property on a sealed subclass, a class nested inside its sealed parent, exports nullable and carries the same `errorOut` convention the top-level path uses ([#38](https://github.com/xxfast/kotlin-native-nuget/issues/38)). `String?` renders as one export returning `string?`; `Int?` follows the two-call `_has_value`/`_value` convention (see [Primitives and strings](primitives-and-strings.md)), collapsed into a single C# expression.

### Kotlin {id="nullable-sealed-kotlin"}

From `test-library/src/nativeMain/kotlin/.../cat/Issue38Sample.kt`:

```kotlin
sealed class Issue38State {
  data class Loaded(val error: String?, val retries: Int?, val code: Int) : Issue38State()
  data object Idle : Issue38State()
}

fun issue38State(state: Int): Issue38State = when (state) {
  0 -> Issue38State.Loaded("Oreo knocked the water bowl over", 3, 7)
  1 -> Issue38State.Loaded(null, null, 7)
  else -> Issue38State.Idle
}
```

### Generated C# {id="nullable-sealed-generated-c"}

From `Interop.cs`:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue38state_loaded_get_error")]
private static extern IntPtr Native_Get_error(IntPtr handle, out IntPtr error);

public string? Error => Marshal.PtrToStringUTF8(Native_Get_error(_handle, out _));

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue38state_loaded_get_retries_has_value")]
[return: MarshalAs(UnmanagedType.I1)]
private static extern bool Native_Get_retries_has_value(IntPtr handle, out IntPtr error);

[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "issue38state_loaded_get_retries_value")]
private static extern int Native_Get_retries_value(IntPtr handle, out IntPtr error);

public int? Retries => Native_Get_retries_has_value(_handle, out _) ? Native_Get_retries_value(_handle, out _) : (int?)null;
```

### Using it from C# {id="nullable-sealed-using-it-from-c"}

From `IntegrationTests/Issue38Tests.cs`:

```C#
[Fact]
public void State_Loaded_WithNulls_ErrorIsNull()
{
    using Issue38State state = Issue38Sample.issue38State(1);
    var loaded = Assert.IsType<Issue38State.Loaded>(state);
    string? error = loaded.Error;
    Assert.Null(error);
}
```

This is delivered for sealed subclasses only. A plain nested (non-sealed) class, `class Outer { data class Inner(val x: String?) }`, is never collected at all and is silently dropped with no `SKIPPED_*` diagnostic; see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).

## Defaulted interface members on implementing classes

A class implementing an interface without overriding one of its defaulted members still has to carry that member in C#: the generated class declares the interface, so omitting the member is `CS0535`. The defaulted body is reached by ordinary dynamic dispatch on the Kotlin instance behind the handle, so no separate delegation is generated for it.

From `test-library/src/nativeMain/kotlin/.../cat/Greeter.kt`:

```kotlin
interface Greeter {
  val greeting: String get() = "hello"

  fun greet(): String = "$greeting from a $species"

  val species: String
}

class Parrot(override val species: String) : Greeter
```

`Parrot` declares only `species`; `greeting` and `greet()` are inherited defaults, and both still bind:

```C#
public class Parrot : IGreeter
{
    internal IntPtr _handle;

    public Parrot(string species)
    {
        /* ... */
    }

    public virtual string Species
    {
        get { /* ... */ }
    }

    public string Greeting
    {
        get { /* ... */ }
    }

    public string Greet()
    {
        /* ... */
    }
}
```

From `IntegrationTests/DefaultedInterfaceMemberTests.cs`:

```C#
[Fact]
public void Parrot_Greeting_UsesDefaultProperty()
{
    using var parrot = new Parrot("macaw");
    Assert.Equal("hello", parrot.Greeting);
}

[Fact]
public void Parrot_Greet_UsesDefaultMethod()
{
    using var parrot = new Parrot("macaw");
    Assert.Equal("hello from a macaw", parrot.Greet());
}

[Fact]
public void IGreeter_Greet_ReachesTheDefaultThroughTheInterface()
{
    using IGreeter greeter = new Parrot("cockatoo");
    Assert.Equal("hello", greeter.Greeting);
    Assert.Equal("hello from a cockatoo", greeter.Greet());
}
```

<note>
    <p>
        This used to be a gap: a class with no <code>ClassKind.CLASS</code> supertype (interface-only)
        skipped its defaulted interface members entirely, with no export, no C# member, and no
        diagnostic, while the generated class still declared the interface. Two planners and one
        translator each answered "does this class have a superclass" differently; all four
        membership checks now share one predicate.
    </p>
</note>

## Interface-typed return values

A Kotlin function or property whose declared return type is an interface (`fun closestFriend(): Pet`, `var friend: Pet?`) surfaces in C# as `IFoo` / `IFoo?`, constructed from the generated `sealed class Foo : IFoo` backing wrapper. The concrete Kotlin object behind the interface can be any implementation, including an anonymous `object`, which is why the wrapper dispatches through generated Kotlin interface-dispatch exports (`pet_get_name`, `pet_speak`, ...) rather than resolving a concrete C# type.

From `Pet.kt` and `Cat.kt`:

```kotlin
interface Pet {
  val name: String // String getter: needs UTF8 marshalling
  val legs: Int // primitive getter: no conversion at all - catches an open-coded conversion bug
  val nickname: String? // nullable String getter: IntPtr.Zero -> null
  fun speak(): String // String-returning method
  fun greet(): String = "Hi, I'm $name" // default method: dispatch must reach the override
  fun fetch(item: String): String // String *input* on the dispatch export
  fun nap() // Unit-returning method (void export)
}

// The strongest polymorphism proof: an anonymous object with no generated C# wrapper of its own,
// so the consumer can only reach it through `pet_*` dispatch.
fun strayPet(): Pet = object : Pet {
  override val name: String = "Whiskers the Stray"
  override val legs: Int = 3
  override val nickname: String? = null
  override fun speak(): String = "Mrrp?"
  override fun fetch(item: String): String = "eyes the $item warily but doesn't fetch it"
  override fun nap() = Unit
}
```

`Cat` gains a nullable interface-typed property (both get and set), an interface-typed parameter, and both a nullable and non-null interface-typed method return:

```kotlin
var friend: Pet? = null

fun befriend(pet: Pet) {
  friend = pet
}

fun closestFriend(): Pet = friend ?: this

fun maybeFriend(): Pet? = friend

val self: Pet get() = this
```

## Generated C#: the backing class

Every Kotlin interface still gets its `IFoo` projection, plus a new `sealed class Foo : IFoo` that dispatches each member through a generated `foo_*` Kotlin export:

```C#
public interface IPet : IDisposable
{
    string Name { get; }
    int Legs { get; }
    string? Nickname { get; }

    string Speak();
    string Greet();
    string Fetch(string item);
    void Nap();
}

public sealed class Pet : IPet
{
    internal IntPtr _handle;

    internal Pet(IntPtr handle)
    {
        _handle = handle;
    }

    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "pet_get_name")]
    private static extern IntPtr Native_Get_name(IntPtr handle, out IntPtr error);

    public string Name
    {
        get { /* ... */ }
    }

    // Legs, Nickname, Speak(), Greet(), Fetch(item), Nap() follow the same pet_* dispatch pattern.
}
```

`Cat`'s interface-typed positions construct `Pet` but declare `IPet`:

```C#
public IPet? Friend
{
    get
    {
        IntPtr nativeResult = Native_Get_friend(_handle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return nativeResult == IntPtr.Zero ? null : (NugetMarshal.TryResolveCSharp(nativeResult, out IPet csharpOriginal) ? csharpOriginal : new Pet(nativeResult));
    }
    set
    {
        IntPtr valueHandle = NugetMarshal.HandleOfOrZero(value, out bool valueOwned);
        Native_Set_friend(_handle, valueHandle, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        if (valueOwned) { NugetMarshal.Dispose(valueHandle); }
    }
}

public IPet ClosestFriend()
{
    IntPtr nativeResult = Native_ClosestFriend(_handle, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    return (NugetMarshal.TryResolveCSharp(nativeResult, out IPet csharpOriginal) ? csharpOriginal : new Pet(nativeResult));
}
```

`NugetMarshal.TryResolveCSharp` probes the returned handle for a C#-originated bridge token before falling back to the ordinary `new Pet(nativeResult)` wrapper; see [Implementing a Kotlin interface in C#](#implementing-a-kotlin-interface-in-c) below. `HandleOfOrZero`/`HandleOf` return an `owned` flag so the caller can dispose a handle it minted itself (a bridge transfer handle) without touching one it merely read off an existing wrapper's `_handle`.

`befriend(pet: Pet)` is an interface-typed **parameter**. It accepts a Kotlin-backed `IPet` (one of the generated wrapper classes, which carry `_handle`) or a C#-implemented one (see [Implementing a Kotlin interface in C#](#implementing-a-kotlin-interface-in-c) below), extracted via a shared helper:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_befriend")]
private static extern void Native_Befriend(IntPtr handle, IntPtr pet, out IntPtr error);

public void Befriend(IPet pet)
{
    IntPtr petHandle = NugetMarshal.HandleOf(pet, out bool petOwned);
    Native_Befriend(_handle, petHandle, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    if (petOwned) { NugetMarshal.Dispose(petHandle); }
}
```

`NugetMarshal.HandleOf` checks whether the value implements the internal `INugetHandle` interface
(see [ADR-094](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/094-reflection-free-generic-dispatch.md));
when it does not, i.e. the object is a C#-implemented `IPet` rather than a Kotlin one, it falls back to a bridge instead of throwing:

```C#
internal static IntPtr HandleOf(object value)
{
    if (value is INugetHandle wrapper) return wrapper.Handle;
    return NugetBridge.HandleFor(value);
}
```

The `petOwned` flag distinguishes a handle `HandleOf` merely read off an existing wrapper (not owned, must not be disposed here) from one it minted fresh for a bridge (owned, a one-shot transfer handle disposed right after the call).

<note>
    <p>Each interface-typed return mints a <b>fresh</b> <code>StableRef</code>, so a returned <code>IPet</code> wrapper disposes independently of the object it came from. Reading a Kotlin-backed <code>cat.Friend</code> twice produces two distinct C# wrapper instances over the same underlying Kotlin object, consistent with <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005</a>. A <b>C#-implemented</b> <code>IPet</code> stored and read back does not go through this path at all: it resolves to the original C# instance instead, see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/084-csharp-implemented-interfaces.md">ADR-084</a>.</p>
</note>

## Using interface-typed returns from C#

From `IntegrationTests/InterfaceReturnTests.cs`:

```C#
[Fact]
public void Befriend_ThenFriend_ReturnsBefriendedPetAsIPet()
{
    using var oreo = new Cat("Oreo", 9);
    using var mylo = new Cat("Mylo", 3);

    oreo.Befriend(mylo);

    using IPet? friend = oreo.Friend;
    Assert.NotNull(friend);
    Assert.Equal("Mylo", friend!.Name);
    Assert.Equal(4, friend.Legs);
    Assert.Equal("Meow! My name is Mylo", friend.Speak());
    Assert.Equal("Hi, I'm Mylo", friend.Greet());
}

// The seam that fails if the backing class ever tries to resolve the concrete Kotlin type:
// the runtime object is an anonymous `object : Pet` with no generated C# wrapper at all.
[Fact]
public void StrayPet_AnonymousKotlinObject_DispatchesThroughIPet()
{
    using IPet stray = PetKt.strayPet();
    Assert.Equal("Whiskers the Stray", stray.Name);
    Assert.Equal(3, stray.Legs);
    Assert.Null(stray.Nickname);
    Assert.Equal("Mrrp?", stray.Speak());
    Assert.Equal("Hi, I'm Whiskers the Stray", stray.Greet()); // interface default implementation
    Assert.Equal("eyes the yarn warily but doesn't fetch it", stray.Fetch("yarn"));
    stray.Nap(); // void dispatch export - just must not throw
}
```

## Implementing a Kotlin interface in C#

A C# class implementing `IPet` with no `_handle` field, an ordinary class, not one of the generated wrappers, can now be passed at an interface-typed parameter or property setter. `HandleOf`'s bridge fallback builds a Kotlin-side object with one function pointer per interface member and dispatches through it, so a Kotlin call against the parameter reaches the real C# implementation, not a stub.

From `IntegrationTests/BidirectionalTests.cs`:

```C#
private class Dog : IPet
{
    public string Name { get; }
    public int Legs => 4;
    public string? Nickname { get; }
    public Dog(string name, string? nickname = null) { Name = name; Nickname = nickname; }
    public string Speak() => "Woof!";
    public string Greet() => $"Hi, I'm {Name} the dog";
    public string Fetch(string item) => $"{Name} enthusiastically fetches the {item}";
    public void Nap() { }
    public void Dispose() { }
}

[Fact]
public void CSharpDog_ImplementsIPet()
{
    using IPet dog = new Dog("Rex");
    using var oreo = new Cat("Oreo", 9);

    oreo.Befriend(dog);

    // Both values only come back correct if Kotlin actually dispatched into `dog` through the
    // generated function-pointer slots: `ClosestFriend().Speak()` calls back into the C# object,
    // and `Interview` is a Kotlin extension (`"${pet.name} says: ${pet.speak()}"`) that reads two
    // separate slots and composes the result itself.
    Assert.Equal("Woof!", oreo.ClosestFriend().Speak());
    Assert.Equal("Rex says: Woof!", oreo.Interview(dog));
}

[Fact]
public void StoredCSharpPet_RoundTripsToTheOriginalInstance()
{
    using var oreo = new Cat("Oreo", 9);
    using IPet dog = new Dog("Rex");

    oreo.Befriend(dog);

    Assert.Same(dog, oreo.ClosestFriend());
    Assert.Same(dog, oreo.Friend);
}
```

### Generated Kotlin: the bridge factory

One factory export per interface, `pet_bridge_create`, with a fnPtr/ctx pair per member (`val` getters and non-`Unit`-returning methods included) plus a release fnPtr/ctx pair. It builds an anonymous `object : Pet` that dispatches every member through its own slot and carries a `createCleaner` tied to the release slot:

From `CNameExports.kt`:

```kotlin
@CName("pet_bridge_create")
@OptIn(ExperimentalNativeApi::class)
public fun export_pet_bridge_create(
  nameGetPtr: COpaquePointer, nameGetCtx: COpaquePointer,
  legsGetPtr: COpaquePointer, legsGetCtx: COpaquePointer,
  nicknameGetPtr: COpaquePointer, nicknameGetCtx: COpaquePointer,
  speakPtr: COpaquePointer, speakCtx: COpaquePointer,
  greetPtr: COpaquePointer, greetCtx: COpaquePointer,
  fetchPtr: COpaquePointer, fetchCtx: COpaquePointer,
  napPtr: COpaquePointer, napCtx: COpaquePointer,
  releasePtr: COpaquePointer, releaseCtx: COpaquePointer,
  token: COpaquePointer,
  errorOut: COpaquePointer?,
): COpaquePointer? = try {
  val nameGetFn = nameGetPtr.reinterpret<CFunction<(COpaquePointer) -> COpaquePointer?>>()
  val releaseFn = releasePtr.reinterpret<CFunction<(COpaquePointer) -> Unit>>()
  // ... one reinterpret per remaining slot ...
  val bridge = object : io.github.xxfast.kotlin.native.nuget.test.cat.Pet, NugetCSharpBridge {
    override val nugetToken: COpaquePointer = token
    @Suppress("unused")
    private val cleaner = createCleaner(releaseFn to releaseCtx) { (fn, ctx) ->
      fn.invoke(ctx)
    }
    override val name: String
      get() {
        val ref = nameGetFn.invoke(nameGetCtx)!!
        val value = ref.asStableRef<String>().get()
        ref.asStableRef<Any>().dispose()
        return value
      }
    // ... legs, nickname, speak(), greet(), fetch(item), nap() follow the same pattern ...
  }
  StableRef.create(bridge).asCPointer()
} catch (e: Throwable) { /* ... */ null }
```

### Generated C#: the bridge state

C# pins one delegate per slot, calls the factory once per crossing, and frees every pin from the
release slot. Each slot's function pointer is a shared `[UnmanagedCallersOnly]` static thunk keyed
off that slot's own `GCHandle` ctx (`NugetThunks`, see
[Publishing Kotlin to C#: AOT and trimming](forward-overview.md#aot-and-trimming),
[ADR-102](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md)),
not a per-instance runtime-built thunk:

```C#
internal sealed class PetBridgeState : NugetBridgeState
{
    [DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "pet_bridge_create")]
    private static extern IntPtr Native_Create(IntPtr nameGetPtr, IntPtr nameGetCtx, /* ... */ IntPtr token, out IntPtr error);

    internal static PetBridgeState Create(TestLibrary.Cat.IPet impl)
    {
        var state = new PetBridgeState();
        state.Root();
        IntPtr token = state.TokenFor(impl);
        NugetBridgeObjectCallback nameGet = _ => { string result = impl.Name; return NugetMarshal.WrapString(result); };
        // ... legsGet, nicknameGet, speak, greet, fetch follow the same pattern ...
        NugetBridgeVoidCallback release = _ => state.FreeAll();
        IntPtr nameGetCtx = state.Pin(nameGet);
        // ... one state.Pin(...) call per slot ...
        IntPtr releaseCtx = state.Pin(release);
        state.KotlinHandle = Native_Create(
            NugetThunks.NugetBridgeObjectCallbackPtr, nameGetCtx, /* ... */
            NugetThunks.NugetBridgeVoidCallbackPtr, releaseCtx, token, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
        return state;
    }
}
```

### Lifetime and identity

<note>
    <p>The bridge's Kotlin-side cleaner only fires on a later GC round, never at the moment the C# reference is dropped: release is <b>GC-timed, not deterministic</b>. A <code>nuget_gc_collect</code> export exists so tests, and hosts that genuinely need it, can force a collection round rather than wait for one.</p>
</note>

`NugetMarshal.TryResolveCSharp` (used by every interface-typed return and getter shown above) probes a returned handle's `nuget_csharp_token` before constructing a fresh wrapper, so a stored C#-implemented object handed back to C# resolves to the **original instance**: `Assert.Same(dog, oreo.Friend)` holds. This is C#-side identity only. Passing the same `Dog` to Kotlin twice builds two separate bridge objects, one per crossing, so Kotlin-side `===` on the underlying bridge is not preserved, the same way identity is not preserved across two reads of a Kotlin-backed interface return (see the note above).

<note>
    <p>An <code>internal IntPtr NugetHandle</code> member on the generated <code>IFoo</code> was considered instead of the reflective helper, and rejected: <code>Interop.cs</code> compiles into the consumer assembly, so an abstract member would break any consumer-written <code>IFoo</code> implementer with <code>CS0535</code>.</p>
</note>

## Limitations

- A C#-implemented interface's bridge factory only ever gets `val` getters and `Unit`/primitive/`Boolean`/enum/`String`/`String?`-returning methods of arity 0-2. An interface with a `var` property, an object- or collection-typed member, a `suspend` member, or generics plans **no factory at all**, silently: `NugetMarshal.HandleOf` keeps the old `NotSupportedException` for it, with no diagnostic naming why.
- A C#-implemented object's bridge is released only when Kotlin's GC actually collects it, on a later collection round; there is no deterministic, prompt release comparable to `IDisposable`.
- Kotlin-side `===` on a C#-implemented object's bridge is not preserved across repeated crossings of the same C# instance: each crossing builds a new bridge object. C#-side identity (`Assert.Same` on the object read back from Kotlin) is preserved via a token probe.
- An interface member whose own return type is another interface or a class handle (chained resolution) is not supported.
- Interfaces with generic type parameters, suspend interface members, and `Flow`/`StateFlow`-valued interface members are not supported as return positions.
- A backing class and its dispatch exports are only generated for interfaces that actually appear in a planned return position; an interface only ever used as an `add`/`remove` subscription parameter (like `ICatEventListener`, see [Lambdas and callbacks](lambdas-and-callbacks.md)) does not get one.
- Object identity is not preserved across reads of a **Kotlin-backed** interface-typed property: two reads produce two distinct C# wrapper instances over the same Kotlin object (each disposes independently). A **C#-implemented** object read back is the one exception, see above.
- A nullable *enum* property on a sealed subclass is not handled by the fix above: it still renders `.ordinal` on a possibly-null value, the same bug class as `String?`/`Int?`, unverified by a fixture. A plain nested (non-sealed) class is never bridged at all, silently and with no diagnostic. See [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- A sealed type at a **property** position binds (bare, nullable, and a read-only collection component). A sealed type at a **constructor or method parameter** position does not, and drops **silently, with no diagnostic**; it was never on ADR-105's route (property planner only), pre-existing. A sealed collection component at a **return or parameter** position does not either, deferred as [ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md) scope (d), and it drops the same silent way. A *mutable* collection of sealed (`var shapes: MutableList<Shape>`) binds get-only. A sealed **interface** is never discriminated at any property position, bare or as a collection component, since only a sealed *class* gets a `FromHandle`. See [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).

## Using it from C#

Polymorphism through `IPet`, from `IntegrationTests/InterfaceTests.cs`:

```C#
[Fact]
public void IPet_Greet_UsesDefaultImplementation()
{
    using IPet pet = new Cat("Oreo", 9);
    Assert.Equal("Hi, I'm Oreo", pet.Greet());
}

[Fact]
public void IPet_CanBeUsedPolymorphically()
{
    using IPet pet = new Cat("Mylo", 9);
    string greeting = Greet(pet);
    Assert.Equal("Hi, I'm Mylo", greeting);
}

private static string Greet(IPet pet) => pet.Greet();
```

Abstract-class inheritance, from `IntegrationTests/AbstractClassTests.cs`:

```C#
[Fact]
public void Animal_CannotBeInstantiated()
{
    // Animal is abstract — this verifies the C# class is also abstract
    Assert.True(typeof(Animal).IsAbstract);
}
```

Pattern matching over a sealed hierarchy, from `IntegrationTests/SealedClassTests.cs`:

```C#
[Fact]
public void Observation_WorksWithPatternMatching()
{
    using Observation result = ObservationKt.openBox("Oreo");

    string message = result switch
    {
        Observation.Superposition => "Unknown - cat is in superposition",
        Observation.Alive a => $"Alive: {a.Cat!.Name}",
        Observation.Dead d => $"Dead: {d.Cause}",
        _ => throw new InvalidOperationException(),
    };

    Assert.Equal("Alive: Oreo", message);
}
```

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="data-classes.md">Data classes</a>
        <a href="lambdas-and-callbacks.md">Lambdas and callbacks</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md">ADR-009: Sealed class mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/040-interface-return-type-mapping.md">ADR-040: Interface return type mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/084-csharp-implemented-interfaces.md">ADR-084: C#-implemented Kotlin interfaces</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/094-reflection-free-generic-dispatch.md">ADR-094: Reflection-free generic dispatch</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md">ADR-102: AOT-safe forward callbacks</a>
    </category>
</seealso>
