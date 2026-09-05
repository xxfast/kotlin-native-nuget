# Exceptions

Every generated bridge call has an `out IntPtr error` parameter (or the object-handle equivalent for constructors). When a Kotlin function throws, the native call packs the exception into that out-parameter instead of aborting the process; the generated C# checks it after every call and throws the corresponding .NET exception. The mechanism is uniform across functions, property getters/setters, and constructors. Anything that can throw in Kotlin gets an error slot. A Kotlin parameter literally named `error` collides with this slot's name and is renamed on the C# side; see [Primitives and strings](primitives-and-strings.md).

| Kotlin | C# | Notes |
|---|---|---|
| thrown exception | `KotlinException` | synchronous propagation, [ADR-023](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/023-exception-propagation.md)/[ADR-024](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/024-sync-exception-propagation.md) |
| stack trace | `KotlinStackTrace` property | [ADR-027](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/027-stacktrace-propagation.md) |
| `e.cause` | `InnerException` | cause chain, [ADR-028](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/028-exception-cause-chain.md) |
| `IllegalArgumentException` etc. | `ArgumentException` etc. | core exceptions mapped via `IKotlinException`, [ADR-029](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/029-exception-type-mapping.md) |
| property getter/setter throws | propagated | [ADR-030](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/030-property-exception-propagation.md) |
| constructor / `init` throws | propagated | primary, secondary, data class `copy()`, generic + value class constructors, [ADR-031](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/031-constructor-exception-propagation.md)–[ADR-035](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/035-value-class-primary-constructor-validation.md) |
| `Throwable`/`Throwable?` property | `Exception`/`Exception?` | constructed, unthrown, riding the same error envelope; see [Throwable properties](#throwable-properties) below and [ADR-107](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/107-throwable-property-mapping.md) |
| `Result<T>` return | `T`, failure thrown | see [Result return values](#result-return-values) below and [ADR-108](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/108-result-return-mapping.md) |

## `KotlinException` and `IKotlinException`

Every generated exception type implements `IKotlinException`, carrying the original fully-qualified Kotlin class name and the Kotlin-side stack trace:

```C#
public interface IKotlinException
{
    string KotlinType { get; }
    string KotlinStackTrace { get; }
}

public class KotlinException : Exception, IKotlinException
{
    public string KotlinType { get; }
    public string KotlinStackTrace { get; }

    public KotlinException(string kotlinType, string message, string kotlinStackTrace,
        Exception? innerException = null) : base(message, innerException)
    {
        KotlinType = kotlinType;
        KotlinStackTrace = kotlinStackTrace;
    }

    public override string ToString()
    {
        return base.ToString()
            + Environment.NewLine + " ---> Kotlin stack trace:"
            + Environment.NewLine + KotlinStackTrace
            + Environment.NewLine + " --- End of Kotlin stack trace ---";
    }
}
```

## Core exception type mapping

A fixed set of Kotlin stdlib exceptions map to the closest .NET analog, as a `sealed class` implementing `IKotlinException` and inheriting the matching .NET base type. Anything not in this table falls back to the base `KotlinException`.

| Kotlin | C# |
|---|---|
| `IllegalArgumentException` | `KotlinArgumentException : ArgumentException` |
| `IllegalStateException`, `NoSuchElementException`, `ConcurrentModificationException` | `KotlinInvalidOperationException : InvalidOperationException` |
| `UnsupportedOperationException` | `KotlinNotSupportedException : NotSupportedException` |
| `ClassCastException` | `KotlinInvalidCastException : InvalidCastException` |
| `ArithmeticException` | `KotlinArithmeticException : ArithmeticException` |
| `NumberFormatException` | `KotlinFormatException : FormatException` |
| `NullPointerException`, `IndexOutOfBoundsException`, user-defined | `KotlinException` (fallback) |

## Kotlin

Sample throw sites, from `test-library/src/nativeMain/kotlin/.../cat/MappedExceptions.kt`:

```kotlin
fun checkOreoWeight(grams: Int): String {
  if (grams > 0) throw IllegalArgumentException("Oreo is on a diet, $grams g treat is too much")
  return "Mylo accepted ${-grams} g of kibble gracefully"
}

fun activateLaserPointer(catName: String): String {
  if (catName == "Oreo") error("Cannot play: Oreo is asleep")
  return "$catName chased the red dot enthusiastically"
}
```

A cause chain, from `CauseExceptions.kt`:

```kotlin
fun groomCat(catName: String): String {
  if (catName == "Oreo") {
    val root = RuntimeException("clippers jammed")
    val mid = IllegalStateException("grooming aborted", root)
    throw IllegalArgumentException("Oreo's grooming failed", mid)
  }
  return "$catName is fluffy"
}
```

A throwing property setter/getter, from `PropertyExceptions.kt`:

```kotlin
class TreatJar(initial: Int) {
  var treatCount: Int = initial
    set(value) {
      require(value >= 0) { "Treat count cannot be negative" }
      field = value
    }
}

class SnackBowl {
  private val snacks: MutableList<String> = mutableListOf()
  val nextSnack: String
    get() {
      check(!isEmpty) { "Snack bowl is empty — Mylo ate everything" }
      return snacks.first()
    }
}
```

A throwing constructor, from `ConstructorExceptions.kt`:

```kotlin
class Kitten(val name: String, val age: Int) {
  init {
    require(age >= 0) { "Kitten age cannot be negative" }
  }
}

class CatLitter(val brand: String, val weightKg: Int) {
  init {
    require(weightKg > 0) { "Litter weight must be positive" }
  }

  constructor(brand: String, bags: Int, perBag: Int) : this(brand, bags * perBag)
}
```

## Generated C#

Every bridge call checks the `error` out-parameter and converts it via `NugetErrorNative.BuildException`:

```C#
public static string checkOreoWeight(int grams)
{
    IntPtr nativeResult = checkOreoWeight_native(grams, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
}
```

The same pattern appears on property setters (`TreatJar.TreatCount`), getters (`SnackBowl.NextSnack`), and constructors (`Kitten(string, int)`), all shown throughout this project's `Interop.cs`. `CatId`'s value-class constructor routes through a private `CreateChecked` helper that does the same check (see [Value classes](value-classes.md)).

A method with a `List`/`Map`/`Set` parameter wraps the error check in a `try`/`finally` instead of
the flat shape above, so a temporary collection handle is released whether the call returns or
throws; see [Collections](collections.md#exception-safety-on-collection-parameters).

## Using it from C#

Type mapping, from `IntegrationTests/ExceptionTypeMappingTests.cs`:

```C#
[Fact]
public void Oreo_OnDiet_IsExactType_KotlinArgumentException()
{
    var ex = Assert.ThrowsAny<ArgumentException>(
        () => MappedExceptions.checkOreoWeight(10));
    Assert.IsType<KotlinArgumentException>(ex);
}

[Fact]
public void Oreo_ToyBehindSofa_NullPointer_IsBaseKotlinException()
{
    // NullPointerException is NOT mapped — .NET reserves NullReferenceException for the CLR
    var ex = Assert.Throws<KotlinException>(
        () => MappedExceptions.retrieveCatToy("Oreo"));
    Assert.IsType<KotlinException>(ex);
}

[Fact]
public void CatchAll_ViaIKotlinException_Guard_WorksForMappedType()
{
    // The idiom that works for ANY Kotlin exception, mapped or unmapped
    Exception? caught = null;
    try
    {
        MappedExceptions.checkOreoWeight(10);
    }
    catch (Exception ex) when (ex is IKotlinException)
    {
        caught = ex;
    }

    Assert.NotNull(caught);
    var ke = (IKotlinException)caught;
    Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    Assert.NotEmpty(ke.KotlinStackTrace);
}
```

Cause chain, from `IntegrationTests/ExceptionCauseTests.cs`:

```C#
[Fact]
public void Oreo_GroomingFailed_DeepChain_RootCause_IsBaseKotlinException()
{
    // RuntimeException is NOT mapped — stays base KotlinException
    var ex = Assert.ThrowsAny<ArgumentException>(
        () => CauseExceptions.groomCat("Oreo"));
    var mid = (InvalidOperationException)ex.InnerException!;
    Assert.IsType<KotlinException>(mid.InnerException);
}
```

Property propagation, from `IntegrationTests/PropertyExceptionPropagationTests.cs`:

```C#
[Fact]
public void TreatJar_NegativeTreatCount_SetterThrowsArgumentException()
{
    using var jar = new TreatJar(5);
    Assert.ThrowsAny<ArgumentException>(
        () => jar.TreatCount = -1);
}

[Fact]
public void SnackBowl_EmptyBowl_GetterThrowsInvalidOperationException()
{
    using var bowl = new SnackBowl();
    Assert.ThrowsAny<InvalidOperationException>(
        () => bowl.NextSnack);
}
```

Constructor propagation, from `IntegrationTests/ConstructorExceptionPropagationTests.cs`, including a `data class`'s generated `Copy()`, which re-runs the same `init` validation:

```C#
[Fact]
public void Kitten_NegativeAge_ConstructorThrowsArgumentException()
{
    Assert.ThrowsAny<ArgumentException>(
        () => new Kitten("Oreo", -1));
}

[Fact]
public void CatProfile_Copy_NegativeTreatBudget_ThrowsArgumentException()
{
    using var profile = new CatProfile("Oreo", 100);
    Assert.ThrowsAny<ArgumentException>(
        () => profile.Copy("Oreo", -1));
}
```

Secondary constructors, from `IntegrationTests/SecondaryConstructorExceptionTests.cs`, exported as separate overloads (`catlitter_create`/`catlitter_create_2`) that each propagate independently:

```C#
[Fact]
public void CatLitter_SecondaryConstructor_ZeroBags_ThrowsArgumentException()
{
    Assert.ThrowsAny<ArgumentException>(() => new CatLitter("Tidy", 0, 5));
}
```

Async exception propagation, from `IntegrationTests/ExceptionPropagationTests.cs`:

```C#
[Fact]
public async Task OreoOnDiet_ThrowsArgumentException_WithTypeName()
{
    var ex = await Assert.ThrowsAnyAsync<ArgumentException>(
        () => AsyncExceptions.FetchCatTreatAsync("Oreo"));
    var ke = (IKotlinException)ex;
    Assert.Equal("kotlin.IllegalArgumentException", ke.KotlinType);
    Assert.Equal("Oreo is on a diet!", ex.Message);
}
```

## Throwable properties

A property declared `Throwable`, `Throwable?`, or a stdlib subtype (`Exception?`,
`IllegalStateException?`, ...) reads as a **constructed, unthrown** `System.Exception`, riding the
same error envelope a thrown exception already crosses on. The value is always, in fact, an
`IKotlinException`: the ADR-029 type mapping and the ADR-028 cause chain both apply, exactly as for
a thrown exception. It binds get-only, since C# cannot mint a typed Kotlin `Throwable` to satisfy a
setter, and it binds on both an ordinary exported class and a **sealed subclass**.

<note>
    <p>Each read allocates a fresh <code>Exception</code>: this is a snapshot, not an identity.
    <code>ReferenceEquals</code> on two reads of the same property is <code>false</code>, even
    though the messages and types are equal.</p>
</note>

From `test-library/src/nativeMain/kotlin/.../test/issue56/Issue56Sample.kt`:

```kotlin
data class Issue56Failure(
  val reason: String,
  val error: Throwable?,
  val fatal: Throwable,
) {
  var lastError: Throwable? = error
}

sealed class Issue56LoadState {
  data object Loading : Issue56LoadState()
  data class Failure(val error: Throwable?) : Issue56LoadState()
}
```

Generated C#, from `Interop.cs`. The getter reconstructs the exception without throwing:

```C#
public global::System.Exception? Error
{
    get
    {
        IntPtr nativeResult = Native_Get_error(_handle, out IntPtr error);
        if (error != IntPtr.Zero)
        {
            throw NugetErrorNative.BuildException(error);
        }
        return nativeResult == IntPtr.Zero ? null : NugetErrorNative.BuildException(nativeResult);
    }
}

public global::System.Exception Fatal
{
    get { /* same shape, no null check */ }
}
```

The sealed-subclass arm goes through a separate, legacy renderer (`CirClassTranslator.kt`/
`SealedClassExports.kt`, not the planner route above) but produces the identical getter shape:

```C#
public sealed class Failure : Issue56LoadState
{
    public global::System.Exception? Error
    {
        get
        {
            IntPtr nativeResult = Native_Get_error(_handle, out IntPtr error);
            if (error != IntPtr.Zero)
            {
                throw NugetErrorNative.BuildException(error);
            }
            return nativeResult == IntPtr.Zero ? null : NugetErrorNative.BuildException(nativeResult);
        }
    }
}
```

From `IntegrationTests/Issue56Tests.cs`:

```C#
[Fact]
public void DietViolation_Error_IsTheAdr029MappedSubtype()
{
    using var failure = Issue56Sample.dietViolation();

    Assert.IsType<KotlinArgumentException>(failure.Error);
}

[Fact]
public void DietViolation_Error_UnmappedCause_FallsBackToBaseKotlinException()
{
    // RuntimeException has no ADR-029 mapping, so the cause must be the base type.
    using var failure = Issue56Sample.dietViolation();

    Assert.IsType<KotlinException>(failure.Error!.InnerException);
}

[Fact]
public void LastError_VarThrowableProperty_HasNoSetter()
{
    var property = typeof(Issue56Failure).GetProperty("LastError");

    Assert.NotNull(property);
    Assert.True(property!.CanRead);
    Assert.False(property.CanWrite);
}

[Fact]
public void FailedLoad_SealedSubclassThrowableProperty_IsTheMappedException()
{
    using Issue56LoadState state = Issue56Sample.failedLoad();

    var failure = (Issue56LoadState.Failure)state;
    Exception? error = failure.Error;

    Assert.IsType<KotlinArgumentException>(error);
    Assert.Equal("Oreo is on a diet!", error!.Message);
}
```

## Result return values

`kotlin.Result<T>` at an ordinary (non-suspend) return position lowers to `T`: the generated export
calls `.getOrThrow()` on the invocation inside the same `try` every other export already wraps its
call in, so a `Result.failure(e)` is indistinguishable in C# from `throw e` and arrives mapped
exactly as ADR-029 describes. `Result<Unit>` binds as `void`, not `Unit`.

<warning>
    <p>This is a deliberate semantic loss: a C# caller cannot tell a modelled
    <code>Result.failure</code> from an unexpected exception. Both surface as the same mapped
    <code>KotlinException</code> subtype. There is no <code>TryRun</code>-style non-throwing
    overload.</p>
</warning>

From `test-library/src/nativeMain/kotlin/.../test/cat/ResultSample.kt`:

```kotlin
class Service {
  fun run(): Result<Unit> = Result.success(Unit)

  fun feed(catName: String): Result<String> =
    if (catName == "Oreo") Result.failure(IllegalArgumentException("Oreo is on a diet!"))
    else Result.success("$catName got a treat")
}
```

Generated C#, from `Interop.cs`: `Run()` is `void`, not `Unit`-returning, and `Feed` returns the
payload type directly:

```C#
public void Run()
{
    Native_Run(_handle, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
}

public string Feed(string catName)
{
    IntPtr nativeResult = Native_Feed(_handle, catName, out IntPtr error);
    if (error != IntPtr.Zero)
    {
        throw NugetErrorNative.BuildException(error);
    }
    return Marshal.PtrToStringUTF8(nativeResult)!;
}
```

From `IntegrationTests/ResultReturnTests.cs`:

```C#
[Fact]
public void Run_ResultOfUnit_BindsAsVoidAndSucceeds()
{
    using var service = ResultSample.service();

    // Compile-time contract: Run() is void, not Unit-returning and not a bool Try shape.
    Assert.Null(Record.Exception(() => service.Run()));
}

[Fact]
public void Feed_Mylo_ResultSuccess_ReturnsThePayloadAsAPlainString()
{
    using var service = ResultSample.service();

    string treat = service.Feed("Mylo");

    Assert.Equal("Mylo got a treat", treat);
}
```

## Limitations

- `Throwable` binds at a **property getter only**: a method return, a parameter, and
  `List<Throwable>` all keep their existing named skip. A module-local, non-exported
  `class MyError : Exception()` does not classify as `Throwable` either and keeps skipping named:
  only a klib/stdlib-origin throwable that is not itself in the export set qualifies. Both deferred
  by [ADR-107](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/107-throwable-property-mapping.md),
  tracked in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).
- `Result<T>` binds at an ordinary **return position only**: property, parameter,
  value-class-own-member, and `suspend fun` positions all keep their existing named skip (a
  `Result<T>` whose payload has no return shape of its own, such as a sealed base or `Flow`, also
  keeps the named skip rather than adopting the payload's). There is no non-throwing `Try`-style
  overload. Deferred by [ADR-108](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/108-result-return-mapping.md),
  tracked in [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md).

<seealso>
    <category ref="related">
        <a href="value-classes.md">Value classes</a>
        <a href="data-classes.md">Data classes</a>
        <a href="coroutines-and-flow.md">Coroutines and Flow</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/023-exception-propagation.md">ADR-023: Exception propagation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/027-stacktrace-propagation.md">ADR-027: Stack trace propagation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/028-exception-cause-chain.md">ADR-028: Exception cause chain</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/029-exception-type-mapping.md">ADR-029: Exception type mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/030-property-exception-propagation.md">ADR-030: Property exception propagation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/031-constructor-exception-propagation.md">ADR-031: Constructor exception propagation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/034-secondary-constructor-exceptions.md">ADR-034: Secondary constructor exceptions</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/107-throwable-property-mapping.md">ADR-107: Throwable property mapping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/108-result-return-mapping.md">ADR-108: Result return mapping</a>
    </category>
</seealso>
