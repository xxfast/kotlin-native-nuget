# Data classes

A Kotlin `data class` becomes a regular C# `class` with `ToString`, `Equals`, `GetHashCode`, and `Copy` generated to match Kotlin's own `data class` semantics, all delegating to the Kotlin implementation across the bridge rather than being reimplemented in C#.

| Kotlin | C# | Notes |
|---|---|---|
| `data class` | `class` | `ToString`, `Equals`, `Copy` |

## Kotlin

From `test-library/src/nativeMain/kotlin/.../cat/Toy.kt`:

```kotlin
data class Toy(
  val name: String,
  val color: String,
)
```

## Generated C#

From `Interop.cs`:

```C#
public class Toy : IDisposable
{
    internal IntPtr _handle;

    public Toy(string name, string color)
    {
        IntPtr handle = Native_Create(name, color, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        _handle = handle;
    }

    internal Toy(IntPtr handle)
    {
        _handle = handle;
    }

    public string Name { get { /* ... */ } }
    public string Color { get { /* ... */ } }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "toy_copy")]
    private static extern IntPtr Native_Copy(IntPtr handle, string name, string color, out IntPtr error);

    public Toy Copy(string name, string color)
    {
        IntPtr handle = Native_Copy(_handle, name, color, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return new Toy(handle);
    }

    public override bool Equals(object? obj)
    {
        if (obj is Toy other) return Native_Equals(_handle, other._handle);
        return false;
    }

    public override int GetHashCode() => Native_HashCode(_handle);

    public override string ToString() => Marshal.PtrToStringUTF8(Native_ToString(_handle))!;

    public void Dispose() { /* ... */ }
}
```

`Equals`, `GetHashCode`, and `ToString` are C# overrides that each call across the bridge into Kotlin's generated `equals()`/`hashCode()`/`toString()` rather than reimplementing structural equality on the C# side. `Copy` calls the Kotlin `copy()` and wraps the returned handle in a new `Toy`, following the same new-wrapper-per-crossing rule as any other object return.

## Using it from C#

From `IntegrationTests/DataClassTests.cs`:

```C#
[Fact]
public void Toy_Equals_SameValues_ReturnsTrue()
{
    using var toy1 = new Toy("Mouse", "Gray");
    using var toy2 = new Toy("Mouse", "Gray");
    Assert.True(toy1.Equals(toy2));
}

[Fact]
public void Toy_ToString_ReturnsKotlinFormat()
{
    using var toy = new Toy("Mouse", "Gray");
    Assert.Equal("Toy(name=Mouse, color=Gray)", toy.ToString());
}

[Fact]
public void Toy_Copy_ReturnsNewInstance()
{
    using var toy = new Toy("Mouse", "Gray");
    using var copy = toy.Copy("Ball", "Red");

    Assert.Equal("Ball", copy.Name);
    Assert.Equal("Red", copy.Color);
    Assert.Equal("Mouse", toy.Name);
}

[Fact]
public void Toy_Copy_IsEqualWhenSameValues()
{
    using var toy = new Toy("Mouse", "Gray");
    using var copy = toy.Copy("Mouse", "Gray");

    Assert.True(toy.Equals(copy));
    Assert.NotSame(toy, copy);
}
```

`ToString()` reproduces Kotlin's own `data class` format (`Toy(name=Mouse, color=Gray)`) verbatim, since the string is built Kotlin-side and just marshalled across.

Data classes inside a sealed hierarchy (`Observation.Alive`, `Observation.Dead`) get the same treatment; see [Interfaces, abstract and sealed classes](interfaces-abstract-sealed.md).

## Limitations

- Data classes map to a plain C# `class`, not `record class`. A safe `with`-expression pattern for a handle-backed type hasn't been found yet (tracked under Future Improvements in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)).

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="value-classes.md">Value classes</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/008-data-class-mapping.md">ADR-008: Data class mapping</a>
    </category>
</seealso>
