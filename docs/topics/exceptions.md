# Exceptions

A Kotlin function, property accessor, or constructor that throws crosses the bridge as a .NET
exception instead of aborting the process. Nothing needs to be written differently in the exported
Kotlin to get this: it applies uniformly to every generated call shape.

```kotlin
fun checkOreoWeight(grams: Int): String {
  if (grams > 0) throw IllegalArgumentException("Oreo is on a diet, $grams g treat is too much")
  return "Mylo accepted ${-grams} g of kibble gracefully"
}
```

```C#
try
{
    MappedExceptions.CheckOreoWeight(10);
}
catch (KotlinArgumentException ex)
{
    Console.WriteLine(ex.Message);
}
```

## Catching a specific exception type

A fixed set of Kotlin stdlib exceptions map to the closest .NET type, as a subtype of that type
implementing `IKotlinException`:

| Kotlin | C# |
|---|---|
| `IllegalArgumentException` | `KotlinArgumentException : ArgumentException` |
| `IllegalStateException`, `NoSuchElementException`, `ConcurrentModificationException` | `KotlinInvalidOperationException : InvalidOperationException` |
| `UnsupportedOperationException` | `KotlinNotSupportedException : NotSupportedException` |
| `ClassCastException` | `KotlinInvalidCastException : InvalidCastException` |
| `ArithmeticException` | `KotlinArithmeticException : ArithmeticException` |
| `NumberFormatException` | `KotlinFormatException : FormatException` |

Anything not in this table, including `NullPointerException` and `IndexOutOfBoundsException`
(.NET reserves `NullReferenceException` for the CLR itself) and any user-defined exception, arrives
as the base `KotlinException`. `catch (ArgumentException)` still catches `KotlinArgumentException`
since it inherits from the .NET type.

## Catching any Kotlin exception

Every generated exception type, mapped or not, implements `IKotlinException`:

```C#
public interface IKotlinException
{
    string KotlinType { get; }        // fully-qualified Kotlin class name, e.g. "kotlin.IllegalArgumentException"
    string KotlinStackTrace { get; }  // Kotlin-side stack trace
}
```

Use it as a catch filter to handle any Kotlin exception the same way, mapped or not:

```C#
catch (Exception ex) when (ex is IKotlinException ke)
{
    Console.WriteLine(ke.KotlinType);
}
```

`Exception.InnerException` follows Kotlin's `cause` chain, one exception per link, each mapped (or
falling back to `KotlinException`) independently:

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

```C#
var ex = Assert.ThrowsAny<ArgumentException>(() => CauseExceptions.GroomCat("Oreo"));
var mid = (InvalidOperationException)ex.InnerException!;      // KotlinInvalidOperationException
var root = (KotlinException)mid.InnerException!;               // RuntimeException isn't mapped
```

## Where this applies

The same error channel carries a thrown exception out of a property getter or setter, a primary,
secondary, or generic class constructor, a value class's constructor (see
[Value classes](value-classes.md)), and a data class's generated `Copy()`, which re-runs the
constructor's `init` validation. A `suspend` function propagates the exception through the
returned `Task` the ordinary C# async way; see [Coroutines and Flow](coroutines-and-flow.md).

```C#
using var jar = new TreatJar(5);
Assert.ThrowsAny<ArgumentException>(() => jar.TreatCount = -1);       // setter

Assert.ThrowsAny<ArgumentException>(() => new Kitten("Oreo", -1));    // constructor

using var profile = new CatProfile("Oreo", 100);
Assert.ThrowsAny<ArgumentException>(() => profile.Copy("Oreo", -1));  // Copy() re-validates
```

A method with a `List`/`Map`/`Set` parameter releases its temporary collection handle whether the
call throws or returns; see
[Collections](collections.md#exception-safety-on-collection-parameters-and-returns). For an
exception thrown out of a Kotlin implementation of a C#-declared interface, called back from C#,
see [The bridgeable subset](bridgeable-subset.md). The reverse direction, a C# lambda or callback
that throws while Kotlin is calling it, is a separate channel; see
[Exceptions from a callback](lambdas-and-callbacks.md#exceptions-from-a-callback).

## Throwable properties

A property declared `Throwable`, `Throwable?`, or a stdlib subtype reads from C# as a plain,
unthrown `Exception` (or `Exception?`), constructed with the same type mapping and cause chain a
thrown exception gets, not something you catch:

```kotlin
data class Issue56Failure(
  val reason: String,
  val error: Throwable?,
  val fatal: Throwable,
)
```

```C#
using var failure = Issue56Sample.DietViolation();
Assert.IsType<KotlinArgumentException>(failure.Error);
```

It binds get-only, even for a `var` in Kotlin, since C# has no way to construct a typed Kotlin
`Throwable` to satisfy a setter, and each read allocates a new `Exception` instance: compare
`Message` and type, not references. It works the same way on a sealed subclass's property.

A class whose constructor or `copy()` takes a `Throwable` parameter (like `Issue56Failure` above)
gets no generated C# constructor or `Copy` at all; construct it in Kotlin and expose it through a
factory function, as `Issue56Sample.DietViolation()` does here.

Only a property getter is supported today. A method return, a parameter, `List<Throwable>`, and a
module-local `class MyError : Exception()` that is not itself exported all keep their existing skip.

## Result return values

A `kotlin.Result<T>` return from an ordinary (non-suspend) function lowers to `T`: success returns
the payload, and `Result.failure(e)` throws exactly as `throw e` would, mapped the same way as any
other exception. `Result<Unit>` binds as `void`, not `Unit`.

```kotlin
class Service {
  fun run(): Result<Unit> = Result.success(Unit)

  fun feed(catName: String): Result<String> =
    if (catName == "Oreo") Result.failure(IllegalArgumentException("Oreo is on a diet!"))
    else Result.success("$catName got a treat")
}
```

```C#
using var service = ResultSample.Service();
service.Run();                                    // void, always succeeds here
string treat = service.Feed("Mylo");              // "Mylo got a treat"
Assert.ThrowsAny<ArgumentException>(() => service.Feed("Oreo"));
```

A C# caller cannot tell a modelled `Result.failure` apart from an unexpected exception: both surface
as the same mapped `KotlinException` subtype, and there is no non-throwing `TryRun`-style overload.

Only an ordinary return position is supported today. `Result<T>` at a property, parameter,
value-class-own-member, or `suspend fun` position keeps its existing skip, as does a `Result<T>`
whose payload has no return shape of its own (a sealed base, `Flow`).
