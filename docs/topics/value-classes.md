# Value classes

A Kotlin `value class` (inline class) wrapping a primitive or `String` becomes a C# `readonly record struct` around the underlying type: no handle, no `IDisposable`, no bridge allocation for the wrapper itself. A value class wrapping a *reference* type (another bridged class) also becomes a `record struct`, but its single property is the wrapped object's own handle-backed type.

| Kotlin | C# | Notes |
|---|---|---|
| `value`/`inline class` | underlying type / `record struct` | see [ADR-014](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/014-value-class-mapping.md) |
| value class wrapping a reference type | `record struct` | wraps the object's own handle type |
| `String`-underlying value class as an ordinary parameter | the same `record struct` | the wire carries the underlying `String`; see [ADR-077](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/077-value-classes-at-ordinary-positions.md) |

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

## As an ordinary parameter

A `String`-underlying value class is also accepted at an ordinary *parameter* position. All five
parameter positions go through the same callable plan, so they all bind: class method, regular
constructor, data-class primary constructor (and the generated `copy()`), extension function, and
top-level function.

The wire carries the underlying `String`. C# hands the struct's `Value` to the native import; Kotlin
re-wraps it with `ChartId(raw)` before the call, so the callee receives a real value class and any
`init` validation on it runs.

From `test-library/src/nativeMain/kotlin/.../clinic/ClinicSample.kt`:

```kotlin
value class ChartId(val value: String) {
  fun isValid(): Boolean = value.isNotBlank()
}

class Patient(val name: String) {
  fun retag(id: ChartId): String = if (id.isValid()) "$name@${id.value}" else "$name@untagged"
}

class Admission(chart: ChartId, val ward: String) {
  val label: String = "${chart.value}/$ward"
}

data class ChartEntry(val id: ChartId, val note: String) {
  fun label(): String = "${id.value}: $note"
}

fun Patient.chartLabel(id: ChartId): String = "$name reads ${id.value}"

fun chartSummary(id: ChartId): String =
  if (id.isValid()) "Chart ${id.value} filed" else "Chart missing"
```

### Generated C#

Every position takes the `ChartId` struct publicly and passes `id.Value` to a `string` native
parameter. From `Interop.cs`:

```C#
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "patient_retag")]
private static extern IntPtr Native_Retag(IntPtr handle, string id, out IntPtr error);

public string Retag(ChartId id)
{
    IntPtr nativeResult = Native_Retag(_handle, id.Value, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
}
```

The other four positions have the same shape. Their generated signatures and the argument each one
passes to the native import:

```C#
public Admission(ChartId chart, string ward)                     // Native_Create(chart.Value, ward, ...)

public ChartEntry(ChartId id, string note)                       // Native_Create(id.Value, note, ...)

public ChartEntry Copy(ChartId id, string note)                  // Native_Copy(_handle, id.Value, note, ...)

public static string ChartLabel(this Patient receiver, ChartId id)  // Native_ChartLabel(receiver._handle, id.Value, ...)

public static string chartSummary(ChartId id)                    // Native_chartSummary(id.Value, ...)
```

The Kotlin side of the crossing declares the parameter as the underlying `String` and boxes it back
into the value class at the call. From the generated `CNameExports.kt`:

```kotlin
@CName("patient_retag")
public fun export_patient_retag(
  handle: COpaquePointer,
  id: String,
  errorOut: COpaquePointer?,
): String = try {
  handle.asStableRef<io.github.xxfast.kotlin.native.nuget.test.clinic.Patient>().get().retag(io.github.xxfast.kotlin.native.nuget.test.clinic.ChartId(id))
} catch (e: Throwable) {
  // ...
}
```

### Using it from C#

From `IntegrationTests/ValueClassParameterTests.cs`:

```C#
[Fact]
public void Patient_Retag_ClassMethodParameter_RoundTripsTheUnwrappedChartId()
{
    using var oreo = new Patient("Oreo");

    // Kotlin's `retag` calls `id.isValid()`, so a blank underlying takes the other branch:
    // proof the parameter arrived as a re-wrapped ChartId, not just a raw string.
    Assert.Equal("Oreo@CH-OREO-1", oreo.Retag(new ChartId("CH-OREO-1")));
    Assert.Equal("Oreo@untagged", oreo.Retag(new ChartId("   ")));
}

[Fact]
public void ChartEntry_PrimaryConstructorAndCopy_RoundTripTheUnwrappedChartId()
{
    using var entry = new ChartEntry(new ChartId("CH-OREO-2"), "chipped a claw");

    Assert.Equal("CH-OREO-2: chipped a claw", entry.Label());

    // The generated copy() carries the same value-class parameter as the primary constructor.
    using ChartEntry rechecked = entry.Copy(new ChartId("CH-MYLO-3"), "still purring");

    Assert.Equal("CH-MYLO-3: still purring", rechecked.Label());
    Assert.Equal("CH-OREO-2: chipped a claw", entry.Label());
}

[Fact]
public void ClinicSample_ChartSummary_TopLevelFunctionParameter_RoundTripsTheUnwrappedChartId()
{
    // Top-level functions keep Kotlin camelCase (ADR-007), unlike the extension above.
    Assert.Equal("Chart CH-CLINIC-0 filed", ClinicSample.chartSummary(new ChartId("CH-CLINIC-0")));
    Assert.Equal("Chart missing", ClinicSample.chartSummary(new ChartId("")));
}
```

<note>
    <p>
        A value-class parameter is unwrapped, not a handle, so nothing here is
        <code>IDisposable</code>. The <code>using</code> in these tests is for the
        <code>Patient</code> and <code>ChartEntry</code> instances, never for the
        <code>ChartId</code>.
    </p>
</note>

## Limitations

- A value class at an ordinary position (rather than as the value class's own receiver) is currently
  scoped to a **`String` underlying type only**, the two shapes shown above: an ordinary method
  return and an ordinary parameter. `Primitive`-, `Enum`- and object-underlying value classes at an
  ordinary position are not yet planned, and neither is `Nullable(ValueClass)` (a `ChartId?`
  parameter, return or property). Each keeps a named `VALUE_CLASS` skip. See
  [ADR-077](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/077-value-classes-at-ordinary-positions.md)
  and [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- A **property** typed as a value class (`val id: ChartId` on a class or data class) does not bind.
  `ChartEntry` takes its `ChartId` through the constructor and `copy()`, but no `Id` property is
  generated, so the fixture reads the value back through an ordinary `String` member (`Label()`).
  Unlike a skipped parameter, a skipped value-class property emits **no diagnostic** at all.
- A value class as a collection element (`List<ChartId>`, `Map<String, ChartId>`) has no boxing
  story on the C# write side and is skipped; see [Collections](collections.md).
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
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/077-value-classes-at-ordinary-positions.md">ADR-077: Value classes at ordinary positions</a>
    </category>
</seealso>
