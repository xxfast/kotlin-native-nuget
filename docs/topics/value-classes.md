# Value classes

A Kotlin `value class` (inline class) wrapping a primitive or `String` becomes a C# `readonly record struct` around the underlying type: no handle, no `IDisposable`, no bridge allocation for the wrapper itself. A value class wrapping a *reference* type (another bridged class) also becomes a `record struct`, but its single property is the wrapped object's own handle-backed type.

| Kotlin | C# | Notes |
|---|---|---|
| `value`/`inline class` | underlying type / `record struct` | see [ADR-014](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/014-value-class-mapping.md) |
| value class wrapping a reference type | `record struct` | wraps the object's own handle type |

## Kotlin

Wrapping a primitive-backed type (`String`), with a validating `init` and a secondary constructor, from `sample-library/src/nativeMain/kotlin/.../cat/CatId.kt`:

```kotlin
value class CatId(val id: String) {
  init {
    require(id.length <= 20) { "Cat ID too long: $id" }
  }

  constructor(name: String, number: Int) : this("$name-$number")
  val length: Int get() = id.length
  fun isValid(): Boolean = id.isNotBlank()
}
```

Wrapping a reference type (`Cat`), from `sample-library/src/nativeMain/kotlin/.../cat/CatResult.kt`:

```kotlin
value class CatResult(val cat: Cat) {
  val name: String get() = cat.name
  fun isAlive(): Boolean = cat.lives > 0
}
```

## Generated C#

`CatId` becomes a `readonly record struct` whose primary constructor routes through Kotlin (so the `init` validation still runs), via a private `CreateChecked` helper:

```C#
public readonly record struct CatId
{
    public string Id { get; }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catid_create")]
    private static extern IntPtr Native_Create(string id, out IntPtr error);

    private static IntPtr CreateChecked(string id)
    {
        IntPtr underlying = Native_Create(id, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return underlying;
    }

    public CatId(string id)
    {
        Id = Marshal.PtrToStringUTF8(CreateChecked(id))!;
    }

    // Secondary constructor (name, number) follows the same CreateChecked_2 pattern

    public int Length => Native_GetLength(Id);

    public bool IsValid() => Native_IsValid(Id);
}
```

`CatResult`, wrapping the reference type `Cat`, is a `record struct` over `Cat` rather than a primitive:

```C#
public readonly record struct CatResult(Cat Cat)
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catresult_get_name")]
    private static extern IntPtr Native_GetName(IntPtr value);

    public string Name => Marshal.PtrToStringUTF8(Native_GetName(Cat._handle))!;

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "catresult_isAlive")]
    [return: MarshalAs(UnmanagedType.I1)]
    private static extern bool Native_IsAlive(IntPtr value);

    public bool IsAlive() => Native_IsAlive(Cat._handle);
}
```

Because `record struct` gives structural equality for free, `CatId`/`CatResult` don't need generated `Equals`/`GetHashCode` overrides the way [data classes](data-classes.md) do. C# derives them from the wrapped property automatically.

## Using it from C#

Primitive-backed value class, from `sample-app/SampleApp.Tests/ValueClassTests.cs`:

```C#
[Fact]
public void CatId_Constructor_WithMultipleValues_WrapsString()
{
    var id = new CatId("oreo", 123);
    Assert.Equal("oreo-123", id.Id);
}

[Fact]
public void CatId_Equality_SameValue_AreEqual()
{
    var oreoId1 = new CatId("oreo-123");
    var oreoId2 = new CatId("oreo-123");
    Assert.Equal(oreoId1, oreoId2);
}

[Fact]
public void CatId_Length_ReturnsUnderlyingStringLength()
{
    var id = new CatId("oreo-123");
    Assert.Equal(8, id.Length);
}
```

Reference-backed value class, from `sample-app/SampleApp.Tests/ReferenceValueClassTests.cs`:

```C#
[Fact]
public void CatResult_Constructor_WrapsClass()
{
    using var oreo = new Cat("Oreo", 9);
    var result = new CatResult(oreo);
    Assert.Equal("Oreo", result.Cat.Name);
}

[Fact]
public void CatResult_Equality_SameUnderlying_AreEqual()
{
    using var oreo = new Cat("Oreo", 9);
    var result1 = new CatResult(oreo);
    var result2 = new CatResult(oreo);
    Assert.Equal(result1, result2);
}
```

Constructor validation propagating an exception through `CreateChecked`, from `sample-app/SampleApp.Tests/ValueClassConstructorExceptionTests.cs` (see [Exceptions](exceptions.md) for the full picture):

```C#
[Fact]
public void CatId_PrimaryConstructor_TooLong_ThrowsArgumentException()
{
    // 21 characters — exceeds the init block's `id.length <= 20` requirement.
    Assert.ThrowsAny<ArgumentException>(
        () => new CatId("supercalifragilisticx"));
}
```

<seealso>
    <category ref="related">
        <a href="data-classes.md">Data classes</a>
        <a href="exceptions.md">Exceptions</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/014-value-class-mapping.md">ADR-014: Value class mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/033-value-class-constructor-exception-propagation.md">ADR-033: Value class constructor exception propagation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/035-value-class-primary-constructor-validation.md">ADR-035: Value class primary constructor validation</a>
    </category>
</seealso>
