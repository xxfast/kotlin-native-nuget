# Interfaces, abstract classes, and sealed classes

Kotlin's three flavours of inheritance each get a distinct C# shape: `interface` becomes an `I`-prefixed C# interface with default methods delegating back to Kotlin, `abstract class` becomes a C# `abstract class` whose subclasses share an inherited `_handle`, and `sealed class` becomes an abstract class with its subtypes nested inside it.

| Kotlin | C# | Notes |
|---|---|---|
| `interface` | `interface` (`I`-prefixed) | default methods delegate to Kotlin |
| `abstract class` | `abstract class` | `_handle` inherited by subclasses |
| `sealed class` | `abstract class` | subclasses nested, see [ADR-009](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md) |

## Kotlin

From `sample-library/src/nativeMain/kotlin/.../cat/Pet.kt` and `Animal.kt`:

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

A sealed hierarchy, from `sample-library/src/nativeMain/kotlin/.../cat/Observation.kt`:

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

## Using it from C#

Polymorphism through `IPet`, from `sample-app/SampleApp.Tests/InterfaceTests.cs`:

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

Abstract-class inheritance, from `sample-app/SampleApp.Tests/AbstractClassTests.cs`:

```C#
[Fact]
public void Animal_CannotBeInstantiated()
{
    // Animal is abstract — this verifies the C# class is also abstract
    Assert.True(typeof(Animal).IsAbstract);
}
```

Pattern matching over a sealed hierarchy, from `sample-app/SampleApp.Tests/SealedClassTests.cs`:

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

## See also

- [Classes and objects](classes-and-objects.md)
- [Data classes](data-classes.md)
- [ADR-009: Sealed class mapping](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md)
