# Generics

Generic classes and functions cross the bridge through a type-erased native layer plus a typed C# surface. The Kotlin side exports one bridge entry point per primitive type argument (`create_string`, `create_int`, ...) plus a generic `create_object` for reference types; `NugetMarshal` on the C# side dispatches to the right one at runtime based on `typeof(T)`. Type constraints and variance both carry through to the generated C# generic parameter.

| Kotlin | C# | Notes |
|---|---|---|
| `class<T>` | `class<T>` | type-erased bridge + generic C# wrapper |
| `class<T>(...)` constructor | typed constructors | typed arguments through the bridge |
| `fun <T> f()` | typed variants | runtime dispatch via `NugetMarshal` |
| `<T : Bound>` constraint | `where T : ...` | see [ADR-015](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/015-generic-type-constraint-mapping.md) |
| `out T` / `in T` variance | `out T` / `in T` | see [ADR-016](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/016-generic-variance-mapping.md) |
| `inline fun` | regular method | see [ADR-017](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/017-inline-function-mapping.md) |
| `inline fun <reified T>` | typed variants | reified type parameters |
| `typealias` | C# alias / underlying | generic type aliases, see [ADR-018](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/018-type-alias-mapping.md) |

## Kotlin

An unconstrained generic class, from `test-library/src/nativeMain/kotlin/.../cat/Box.kt`:

```kotlin
class Box<T>(val value: T) {
  init {
    require(value.toString().isNotEmpty()) { "Box cannot hold a blank value" }
  }
}
```

A constrained generic class, from `test-library/src/nativeMain/kotlin/.../cat/PetBox.kt`:

```kotlin
class PetBox<T : Pet>(val value: T) {
  init {
    require(value.name.isNotBlank()) { "PetBox needs a named pet" }
  }
}
```

Generic functions, from `test-library/src/nativeMain/kotlin/.../cat/Helpers.kt`:

```kotlin
fun <T> identity(value: T): T = value

fun <T> wrapInBox(value: T): Box<T> = Box(value)

fun <T : Pet> adoptPet(pet: T): T = pet

inline fun <reified T : Pet> groomPet(pet: T): T = pet
```

Variance, from `test-library/src/nativeMain/kotlin/.../cat/Variance.kt`:

```kotlin
interface Readable<out T> {
    fun read(): T
}

interface Writable<in T> {
    fun write(value: T)
}
```

`inline fun` (non-reified), from `test-library/src/nativeMain/kotlin/.../math/Arithmetic.kt`:

```kotlin
inline fun square(x: Int): Int = x * x
```

Generic type aliases, from `test-library/src/nativeMain/kotlin/.../TypeAliases.kt`:

```kotlin
typealias Score = Int
typealias CatNames = List<String>
typealias CatScores = Map<String, Int>

fun topScore(): Score = 10
fun defaultNames(): CatNames = listOf("Oreo", "Mylo")
fun defaultScores(): CatScores = mapOf("Oreo" to 10, "Mylo" to 8)
```

## Generated C#

`Box<T>` uses `NugetMarshal.CreateBox<T>` to dispatch construction by runtime type:

```C#
public class Box<T> : IDisposable
{
    internal IntPtr _handle;

    public Box(T value)
    {
        _handle = NugetMarshal.CreateBox<T>(value);
    }

    public T Value => NugetMarshal.FromHandle<T>(BoxNative.Get_value(_handle));

    public void Dispose() { /* ... */ }
}
```

`PetBox<T>` carries the constraint through to C#'s `where` clause, and reflects out the `_handle` field of the constrained argument to pass across the bridge:

```C#
public class PetBox<T> : IDisposable where T : IPet
{
    internal IntPtr _handle;

    public PetBox(T value)
    {
        var field = typeof(T).GetField("_handle",
            System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public);
        IntPtr handle = PetBoxNative.Create_object((IntPtr)field!.GetValue(value)!, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        _handle = handle;
    }

    public T Value => NugetMarshal.FromHandle<T>(PetBoxNative.Get_value(_handle));

    public void Dispose() { /* ... */ }
}
```

A generic function dispatches per primitive type at runtime, falling back to the object/handle path otherwise:

```C#
public static T identity<T>(T value)
{
    if (typeof(T) == typeof(string))
        return (T)(object)Marshal.PtrToStringUTF8(identity_string_native((string)(object)value!))!;
    if (typeof(T) == typeof(int))
        return (T)(object)identity_int_native((int)(object)value!);
    // ... long, float, double, bool ...
    var field = typeof(T).GetField("_handle", /* ... */);
    IntPtr handle = (IntPtr)field!.GetValue(value)!;
    IntPtr result = identity_object_native(handle);
    return (T)Activator.CreateInstance(typeof(T), /* ... */, new object[] { result }, null)!;
}
```

A constrained generic function carries the `where` clause the same way as the class:

```C#
public static T adoptPet<T>(T pet) where T : IPet
{
    var field = typeof(T).GetField("_handle", /* ... */);
    IntPtr handle = (IntPtr)field!.GetValue(pet)!;
    IntPtr result = adoptPet_object_native(handle);
    return (T)Activator.CreateInstance(typeof(T), /* ... */, new object[] { result }, null)!;
}
```

`inline fun <reified T : Pet> groomPet` generates identically to a regular constrained generic function (`groomPet<T>(T pet) where T : IPet`). Reification only matters Kotlin-side, where it lets the function inspect `T` at the call site; the bridge doesn't need to know the difference.

Variance carries straight through to the C# interface declaration:

```C#
public interface IReadable<out T> : IDisposable
{
    T Read();
}

public interface IWritable<in T> : IDisposable
{
    void Write(T value);
}
```

Generic type aliases erase to their underlying type; `Score` (an alias for `Int`) generates identically to `Int`, and `CatNames`/`CatScores` (aliases for `List<String>`/`Map<String, Int>`) generate as `IReadOnlyList<string>`/`IReadOnlyDictionary<string, int>`. There's no separate alias type in the generated C#.

## Using it from C#

Unconstrained generics, from `IntegrationTests/GenericTests.cs`:

```C#
[Fact]
public void Box_Cat_ConstructorAndGetter()
{
    using var oreo = new Cat("Oreo", 9);
    using var box = new Box<Cat>(oreo);
    using Cat cat = box.Value;
    Assert.Equal("Oreo", cat.Name);
}
```

Constrained generics, from `IntegrationTests/GenericConstraintTests.cs`:

```C#
[Fact]
public void PetBox_TypeParameter_HasIPetConstraint()
{
    Type[] constraints = typeof(PetBox<>).GetGenericArguments()[0].GetGenericParameterConstraints();
    Assert.Contains(typeof(IPet), constraints);
}

[Fact]
public void AdoptPet_Oreo_ReturnsSameCat()
{
    using var oreo = new Cat("Oreo", 9);
    using Cat adopted = Helpers.adoptPet<Cat>(oreo);
    Assert.Equal("Oreo", adopted.Name);
}
```

Generic functions, from `IntegrationTests/GenericFunctionTests.cs`:

```C#
[Fact]
public void WrapInBox_Int()
{
    using Box<int> box = Helpers.wrapInBox<int>(99);
    Assert.Equal(99, box.Value);
}
```

Variance, from `IntegrationTests/VarianceTests.cs`:

```C#
[Fact]
public void IReadable_Covariance_AllowsNarrowingAssignment()
{
    // IReadable<Cat> can be assigned to IReadable<IPet> because T is covariant (out T)
    Assert.True(typeof(IReadable<IPet>).IsAssignableFrom(typeof(IReadable<Cat>)));
}

[Fact]
public void IWritable_Contravariance_AllowsWideningAssignment()
{
    Assert.True(typeof(IWritable<Cat>).IsAssignableFrom(typeof(IWritable<IPet>)));
}
```

`inline fun square`, from `IntegrationTests/ArithmeticTests.cs`:

```C#
[Fact]
public void Square_ReturnsSquaredValue()
{
    int result = Arithmetic.square(5);
    Assert.Equal(25, result);
}
```

Type aliases, from `IntegrationTests/TypeAliasTests.cs`:

```C#
[Fact]
public void TopScore_ReturnsInt()
{
    int result = TypeAliases.topScore();
    Assert.Equal(10, result);
}

[Fact]
public void DefaultScores_ReturnsReadOnlyDictionaryOfStringInt()
{
    IReadOnlyDictionary<string, int> scores = TypeAliases.defaultScores();
    Assert.Equal(2, scores.Count);
}
```

<seealso>
    <category ref="related">
        <a href="collections.md">Collections</a>
        <a href="value-classes.md">Value classes</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/010-generics-mapping.md">ADR-010: Generics mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/015-generic-type-constraint-mapping.md">ADR-015: Generic type constraint mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/016-generic-variance-mapping.md">ADR-016: Generic variance mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/017-inline-function-mapping.md">ADR-017: Inline function mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/018-type-alias-mapping.md">ADR-018: Type alias mapping</a>
    </category>
</seealso>
