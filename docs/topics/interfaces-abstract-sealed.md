# Interfaces, abstract classes, and sealed classes

Kotlin's three flavours of inheritance each get a distinct C# shape: `interface` becomes an `I`-prefixed C# interface with default methods delegating back to Kotlin, `abstract class` becomes a C# `abstract class` whose subclasses share an inherited `_handle`, and `sealed class` becomes an abstract class with its subtypes nested inside it.

| Kotlin | C# | Notes |
|---|---|---|
| `interface` | `interface` (`I`-prefixed) | default methods delegate to Kotlin |
| `abstract class` | `abstract class` | `_handle` inherited by subclasses |
| `sealed class` | `abstract class` | subclasses nested, see [ADR-009](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md) |
| interface-typed return (method result or property) | `IFoo` / `IFoo?` | backed by a generated `sealed class Foo : IFoo`, see [ADR-040](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/040-interface-return-type-mapping.md) |

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

        public Cat Cat => new Cat(Native_Get_cat(_handle));

        public override bool Equals(object? obj) { /* ... */ }
        public override int GetHashCode() => Native_HashCode(_handle);
        public override string ToString() => Marshal.PtrToStringUTF8(Native_ToString(_handle))!;
        public override void Dispose() { /* ... */ }
    }

    public sealed class Dead : Observation
    {
        public string Cause => Marshal.PtrToStringUTF8(Native_Get_cause(_handle))!;
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
        return nativeResult == IntPtr.Zero ? null : new Pet(nativeResult);
    }
    set
    {
        Native_Set_friend(_handle, value != null ? NugetMarshal.HandleOf(value) : IntPtr.Zero, out IntPtr error);
        if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    }
}

public IPet ClosestFriend()
{
    IntPtr nativeResult = Native_ClosestFriend(_handle, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    return new Pet(nativeResult);
}
```

`befriend(pet: Pet)` is an interface-typed **parameter**. In v1 it accepts only a Kotlin-backed `IPet` (one of the generated wrapper classes, which carry `_handle`), extracted via a shared reflective helper:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_befriend")]
private static extern void Native_Befriend(IntPtr handle, IntPtr pet, out IntPtr error);

public void Befriend(IPet pet)
{
    Native_Befriend(_handle, NugetMarshal.HandleOf(pet), out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
}
```

`NugetMarshal.HandleOf` reads the `_handle` field by reflection and throws `NotSupportedException` when it is absent, i.e. when the object is a C#-implemented `IPet` rather than a Kotlin one:

```C#
internal static IntPtr HandleOf(object value)
{
    var field = value.GetType().GetField("_handle",
        System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public);
    if (field == null)
    {
        throw new NotSupportedException(
            $"{value.GetType().Name} is not a Kotlin-backed object; passing a C#-implemented interface is not supported yet.");
    }
    return (IntPtr)field.GetValue(value)!;
}
```

<note>
    <p>Each interface-typed return mints a <b>fresh</b> <code>StableRef</code>, so a returned <code>IPet</code> wrapper disposes independently of the object it came from. Reading <code>cat.Friend</code> twice produces two distinct C# wrapper instances over the same underlying Kotlin object, consistent with <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005</a>.</p>
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

Passing a C#-implemented `IPet` to `Befriend` compiles (the parameter type is just `IPet`) but throws at runtime, from `IntegrationTests/BidirectionalTests.cs`:

```C#
private class Dog : IPet
{
    public string Name { get; }
    public int Legs => 4;
    public string? Nickname => null;
    public Dog(string name) { Name = name; }
    public string Speak() => "Woof!";
    public string Greet() => $"Hi, I'm {Name} the dog";
    public string Fetch(string item) => $"{Name} enthusiastically fetches the {item}";
    public void Nap() { }
    public void Dispose() { }
}

[Fact]
public void Cat_Befriend_CSharpImplementedPet_ThrowsNotSupportedException()
{
    using var oreo = new Cat("Oreo", 9);
    using IPet dog = new Dog("Rex");
    Assert.Throws<NotSupportedException>(() => oreo.Befriend(dog));
}
```

<note>
    <p>An <code>internal IntPtr NugetHandle</code> member on the generated <code>IFoo</code> was considered instead of the reflective helper, and rejected: <code>Interop.cs</code> compiles into the consumer assembly, so an abstract member would break any consumer-written <code>IFoo</code> implementer with <code>CS0535</code>.</p>
</note>

## Limitations

- Passing a **C#-implemented** interface to an interface-typed parameter is not supported; `NugetMarshal.HandleOf` throws `NotSupportedException`. Only a Kotlin-backed `IFoo` wrapper can be passed. General C#-implemented interface parameters (the mirror direction) is a separate, deferred ROADMAP item.
- An interface member whose own return type is another interface or a class handle (chained resolution) is not supported.
- Interfaces with generic type parameters, suspend interface members, and `Flow`/`StateFlow`-valued interface members are not supported as return positions.
- A backing class and its dispatch exports are only generated for interfaces that actually appear in a planned return position; an interface only ever used as an `add`/`remove` subscription parameter (like `ICatEventListener`, see [Lambdas and callbacks](lambdas-and-callbacks.md)) does not get one.
- Object identity is not preserved across reads: two reads of the same interface-typed property produce two distinct C# wrapper instances over the same Kotlin object (each disposes independently).

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
    </category>
</seealso>
