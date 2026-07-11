# Classes and objects

A Kotlin `class` becomes a C# `class` backed by an opaque `StableRef` handle, implementing `IDisposable`. Constructors, member properties (including setters), and object-typed properties/returns all cross the bridge through that handle.

| Kotlin | C# | Notes |
|---|---|---|
| `class` | `class : IDisposable` | `StableRef` + opaque pointer |
| constructor | `new Foo(...)` | Kotlin constructor surfaces as a C# `new` |
| member property (get) | property (get) | |
| member property (get/set) | property (get/set) | |
| object-typed property/return | property/return | new wrapper per access, identity not preserved |

## Kotlin

From `sample-library/src/nativeMain/kotlin/.../cat/Cat.kt`:

```kotlin
class Cat(
  name: String,
  val lives: Int = 9,
) : Animal(name) {
  var brother: Cat? = null
  var owner: String? = null
  var age: Int? = null
  var mood: Mood = Mood.SLEEPY

  override fun speak(): String = "Meow! My name is $name"

  fun meow(): String = "Meow! My name is $name"

  fun pet(): String = "$name purrs contentedly"
}
```

## Generated C#

From `Interop.cs`. The constructor allocates a `StableRef` and stores the handle; `Brother` shows the object-typed property pattern, `Owner` shows a nullable string property with a setter:

```C#
public class Cat : Animal
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_create")]
    private static extern IntPtr Native_Create(string name, int lives, out IntPtr error);

    public Cat(string name, int lives) : base(IntPtr.Zero)
    {
        IntPtr handle = Native_Create(name, lives, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        _handle = handle;
    }

    internal Cat(IntPtr handle) : base(handle)
    {
    }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_get_brother")]
    private static extern IntPtr Native_Get_brother(IntPtr handle, out IntPtr error);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "cat_set_brother")]
    private static extern void Native_Set_brother(IntPtr handle, IntPtr value, out IntPtr error);

    public Cat? Brother
    {
        get
        {
            IntPtr nativeResult = Native_Get_brother(_handle, out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
            return nativeResult == IntPtr.Zero ? null : new Cat(nativeResult);
        }
        set
        {
            Native_Set_brother(_handle, value?._handle ?? IntPtr.Zero, out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
        }
    }
}
```

Every access to `Brother` calls `Native_Get_brother` again and wraps the resulting handle in a **new** `Cat` instance. There is no caching. Setting `Brother` unwraps the C# wrapper's `_handle` field (or passes `IntPtr.Zero` for `null`).

## Using it from C#

From `sample-app/SampleApp.Tests/ObjectTests.cs`:

```C#
[Fact]
public void Cat_Brother_EachAccessReturnsNewWrapper()
{
    using var oreo = new Cat("Oreo", 9);
    using var mylo = new Cat("Mylo", 9);

    oreo.Brother = mylo;

    using Cat? brother1 = oreo.Brother;
    using Cat? brother2 = oreo.Brother;

    // Per ADR-005: identity is NOT preserved (new wrapper each access)
    Assert.NotSame(brother1, brother2);
    Assert.Equal(brother1!.Name, brother2!.Name);
}

[Fact]
public void Cat_Brother_DisposingBrotherDoesNotAffectOriginal()
{
    using var oreo = new Cat("Oreo", 9);
    using var mylo = new Cat("Mylo", 9);

    oreo.Brother = mylo;

    Cat? brother = oreo.Brother;
    brother!.Dispose();

    // Oreo is still alive — disposing the brother wrapper only releases that one StableRef
    Assert.Equal("Oreo", oreo.Name);
}

[Fact]
public void Cat_Brother_CyclicReference_BothCanBeDisposed()
{
    using var oreo = new Cat("Oreo", 9);
    using var mylo = new Cat("Mylo", 9);

    oreo.Brother = mylo;
    mylo.Brother = oreo;

    using Cat? oreosBrother = oreo.Brother;
    using Cat? mylosBrother = mylo.Brother;
    // All wrappers can be independently disposed without crashes
}
```

From `sample-app/SampleApp.Tests/NullablePropertyTests.cs`, a nullable primitive property:

```C#
[Fact]
public void Cat_Age_SetToNull()
{
    using var cat = new Cat("Oreo", 9);
    cat.Age = 3;
    Assert.Equal(3, cat.Age);
    cat.Age = null;
    Assert.Null(cat.Age);
}
```

## Disposal semantics

Disposal does not cascade. A parent wrapper's `Dispose()` only releases *its own* `StableRef`; any wrapper obtained from a property or method call on that parent holds an independent `StableRef` and must be disposed separately (or leaks). This is deliberate: since every access allocates a new wrapper, there's no tree of ownership for a parent to walk. See [ADR-005](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md) for the alternative designs considered (cached wrapper with cascading dispose was rejected).

## See also

- [Publishing Kotlin to C#](forward-overview.md)
- [Interfaces, abstract and sealed classes](interfaces-abstract-sealed.md)
- [ADR-003: Memory management across the bridge](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md)
- [ADR-005: Object return semantics](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md)
