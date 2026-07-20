# Value classes

A Kotlin `value class` (inline class) wrapping a primitive or `String` becomes a C# `readonly record struct` around the underlying type: no handle, no `IDisposable`, no bridge allocation for the wrapper itself. A value class wrapping a *reference* type (another bridged class) also becomes a `record struct`, but its single property is the wrapped object's own handle-backed type.

| Kotlin | C# | Notes |
|---|---|---|
| `value`/`inline class` | underlying type / `record struct` | see [ADR-014](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/014-value-class-mapping.md) |
| value class wrapping a reference type | `record struct` | wraps the object's own handle type |

## Kotlin

Wrapping a primitive-backed type (`String`), with a validating `init` and a secondary constructor, from `test-library/src/nativeMain/kotlin/.../cat/CatId.kt`:

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

Wrapping a reference type (`Cat`), from `test-library/src/nativeMain/kotlin/.../cat/CatResult.kt`:

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

## Methods with parameters

Value-class methods (both primitive-underlying and reference-underlying) go through the shared
callable plan ([ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)),
including parameters. From `test-library/.../clinic/ClinicSample.kt` (ADR-060 cells 15 and 16):

```kotlin
value class ChartId(val value: String) {
  fun matches(other: String): Boolean = value == other
  fun isValid(): Boolean = value.isNotBlank()
}

value class ChartRef(val patient: Patient) {
  fun label(suffix: String): String = "${patient.name}$suffix"
}
```

Generated C#, from `Interop.cs`:

```C#
public readonly record struct ChartId
{
    public string Value { get; }

    public ChartId(string value)
    {
        Value = Marshal.PtrToStringUTF8(CreateChecked(value))!;
    }

    public bool Matches(string other) => Native_Matches(Value, other);

    public bool IsValid() => Native_IsValid(Value);
}

public readonly record struct ChartRef(Patient Patient)
{
    public string Label(string suffix) => Marshal.PtrToStringUTF8(Native_Label(Patient._handle, suffix))!;
}
```

From `IntegrationTests/ValueClassTests.cs`:

```C#
[Fact]
public void ChartId_Matches_SameValue_ReturnsTrue()
{
    var id = new ChartId("abc");
    Assert.True(id.Matches("abc"));
}

[Fact]
public void ChartRef_Label_AppendsSuffix()
{
    using var patient = new Patient("Rex");
    var chart = new ChartRef(patient);
    Assert.Equal("Rex-ward", chart.Label("-ward"));
}
```

## Using it from C#

Primitive-backed value class, from `IntegrationTests/ValueClassTests.cs`:

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

Reference-backed value class, from `IntegrationTests/ReferenceValueClassTests.cs`:

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

Constructor validation propagating an exception through `CreateChecked`, from `IntegrationTests/ValueClassConstructorExceptionTests.cs` (see [Exceptions](exceptions.md) for the full picture):

```C#
[Fact]
public void CatId_PrimaryConstructor_TooLong_ThrowsArgumentException()
{
    // 21 characters — exceeds the init block's `id.length <= 20` requirement.
    Assert.ThrowsAny<ArgumentException>(
        () => new CatId("supercalifragilisticx"));
}
```

## As an ordinary return type

A `String`-underlying value class also binds correctly at an *ordinary* position, not only as the
receiver of its own methods: a plain class-method return typed as the value class. From
`test-library/src/nativeMain/kotlin/.../Newsroom.kt` (a `value class` declared one Gradle module
away, in `:test-models`; see [The nuget {} DSL](nuget-dsl.md) for the cross-module export closure):

```kotlin
value class StoryCode(val value: String)

class Newsroom {
  fun code(): StoryCode = StoryCode("BREAKING-001")
}
```

Generated C#, from `Interop.cs`:

```C#
public readonly record struct StoryCode
{
    public string Value { get; }
    // ...
}

public class Newsroom : IDisposable, IAsyncDisposable
{
    // ...
    public global::TestLibrary.Models.StoryCode Code()
    {
        IntPtr nativeResult = Native_Code(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return new global::TestLibrary.Models.StoryCode(Marshal.PtrToStringUTF8(nativeResult)!);
    }
}
```

From `IntegrationTests/NewsroomReachabilityTests.cs`:

```C#
[Fact]
public void Code_CrossModuleValueClass_BindsAsUnwrappedValue()
{
    using var newsroom = new Newsroom();
    var code = newsroom.Code();

    Assert.Equal("BREAKING-001", code.Value);
}
```

`StoryCode` binds as the unwrapped `record struct`, never as an `IDisposable` handle, which is worth
calling out because it is easy to get wrong here specifically: KSP reports a *cross-module* value
class with `Modifier.INLINE`, never `Modifier.VALUE`, so a classification check written for
`Modifier.VALUE` alone would silently misclassify it as an ordinary class.

## Limitations

- A value class at an ordinary position (a plain method/property return or parameter typed as the
  value class, rather than the value class's own receiver) is currently scoped to a **`String`
  underlying type only**, the shape shown above. `Primitive`-, `Enum`-, and object-underlying value
  classes at an ordinary position, and value classes at ordinary **parameter** positions, are not yet
  planned (see [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)).
- Reference-underlying value-class **primary** constructor `init` validation stays deferred
  ([ADR-035](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/035-value-class-primary-constructor-validation.md));
  primitive-underlying validation (the `CatId` path above) is in place.
- Whether inherited members on a value class (for example `CharSequence` members via `by value`)
  should be exported is still an open product decision; declared methods with parameters are planned.
  In the meantime they are excluded from the generated C# API with a `SKIPPED_INHERITED_MEMBER`
  diagnostic naming each one, rather than binding silently
  ([ADR-064](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md)).

<seealso>
    <category ref="related">
        <a href="data-classes.md">Data classes</a>
        <a href="exceptions.md">Exceptions</a>
        <a href="nuget-dsl.md">The nuget {} DSL</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/014-value-class-mapping.md">ADR-014: Value class mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/033-value-class-constructor-exception-propagation.md">ADR-033: Value class constructor exception propagation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/035-value-class-primary-constructor-validation.md">ADR-035: Value class primary constructor validation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md">ADR-066: Forward export reachability closure</a>
    </category>
</seealso>
