# Exceptions

A Kotlin function, property accessor, or constructor that throws crosses the bridge as a .NET
exception instead of aborting the process. Nothing needs to be written differently in the exported
Kotlin to get this: it applies uniformly to every generated call shape.

The exception types come from the shared `Kotlin.Native.Interop` package. If your .NET project references multiple Kotlin-built packages, an unmapped failure from either can be caught as `KotlinException`; mapped failures keep their usual .NET base type and implement `IKotlinException`.

```kotlin
fun checkOreoWeight(grams: Int): String {
  if (grams > 0) throw IllegalArgumentException("Oreo is on a diet, $grams g treat is too much")
  return "Mylo accepted ${-grams} g of kibble gracefully"
}
```

```C#
using Kotlin.Native.Interop;

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

A Kotlin exception maps to the closest .NET type when it is one of the classes below **or a subclass
of one**, so `class KennelFullException : IllegalStateException()` is caught as
`InvalidOperationException`. The generated type derives from the .NET type and implements
`IKotlinException`; `KotlinType` still names your concrete Kotlin class.

| Kotlin | C# |
|---|---|
| `kotlinx.io.IOException` and subclasses, such as `EOFException` | `KotlinIOException : IOException` |
| `IllegalArgumentException` | `KotlinArgumentException : ArgumentException` |
| `NumberFormatException` | `KotlinFormatException : FormatException` |
| `IllegalStateException`, `NoSuchElementException`, `ConcurrentModificationException`, `NoWhenBranchMatchedException` | `KotlinInvalidOperationException : InvalidOperationException` |
| `CancellationException` | `KotlinOperationCanceledException : OperationCanceledException` |
| `UnsupportedOperationException` | `KotlinNotSupportedException : NotSupportedException` |
| `ClassCastException` | `KotlinInvalidCastException : InvalidCastException` |
| `ArithmeticException` | `KotlinArithmeticException : ArithmeticException` |
| `NullPointerException` | `KotlinNullReferenceException : NullReferenceException` |

The first matching row wins, so `NumberFormatException` is a `FormatException` and not an
`ArgumentException`, and a `CancellationException` is an `OperationCanceledException` and not an
`InvalidOperationException`. Anything else, including `IndexOutOfBoundsException` and a user-defined
exception that extends none of these, arrives as the shared `KotlinException`.

```kotlin
internal class LitterBoxJammedException(message: String) : kotlinx.io.IOException(message)

fun rake(catName: String): String {
  if (catName == "Oreo") throw LitterBoxJammedException("Oreo buried the rake")
  return "$catName's litter is raked into neat rows"
}
```

```C#
try
{
    LitterBoxErrors.Rake("Oreo");
}
catch (System.IO.IOException ex) when (ex is IKotlinException ke)
{
    Console.WriteLine(ke.KotlinType);   // the subclass's own name, not "kotlinx.io.IOException"
}
```

<warning>
<p><b>Breaking change.</b> Code that catches <code>KotlinException</code> for a subclass of a mapped
type, for a <code>NullPointerException</code>, a <code>CancellationException</code> or a
<code>kotlinx.io.IOException</code> now receives the mapped type above instead, and that is not a
<code>KotlinException</code>. To handle any Kotlin exception, catch
<code>Exception</code> and filter on the interface:
<code>catch (Exception e) when (e is IKotlinException)</code>.</p>
</warning>

The `IOException` row exists only in a library that has `kotlinx-io` on its compile classpath, for
example through Ktor. A `suspend` function's `IOException` maps like any other, but two routes see
the standard-library rows only, so an `IOException` there stays a `KotlinException`: a Kotlin
`suspend` lambda invoked from C#, and a `StateFlow` collect.

## Exception messages

`Message` is the Kotlin message verbatim. When the Kotlin exception has no message, `Message` is the
Kotlin class's full name instead, so `throw NullPointerException()` reads as
`kotlin.NullPointerException` rather than a blank or generic text. `ToString()` adds a
`Kotlin type: <name>` line above the Kotlin stack trace.

## Catching any Kotlin exception

Every generated exception type, mapped or not, implements `IKotlinException`:

```C#
public interface IKotlinException
{
    string KotlinType { get; }        // fully-qualified Kotlin class name, e.g. "kotlin.IllegalArgumentException"
    string KotlinStackTrace { get; }  // Kotlin-side stack trace
}
```

`KotlinStackTrace` holds Kotlin frames only: the Kotlin exception's `toString()` line, then one
`at` line per frame, from where it was thrown down to the bridge's `kn_<hex>_...` export function.
The .NET and OS frames of your own process below that are not included; the C# exception's
`StackTrace` has the .NET half. The raw native stack has dozens of those frames, printed as
`0x0 + <address>` or as a nearby symbol with an offset in the millions, which reads like a C++
crash. The bridge cuts them before the trace crosses:

```
kotlin.IllegalArgumentException: Oreo is on a diet!
    at 0   ???   7ff8cd7b1bd4   kfun:io.github.xxfast.kotlin.native.nuget.test.cat#feedCatTreat(kotlin.String){}kotlin.String + 196
    at 1   ???   7ff8cd8b5c99   _konan_function_535 + 169
    at 2   ???   7ff8cdb2617c   kn_746573746c696272617279_cat__feedCatTreat + 156
    at 3   ???   7ff8cf4901be   _ZSt25__throw_bad_function_callv + 24839742   <- cut
    at 4   ???   7ff8cf29f5de   0x0 + 140706604250590                         <- cut
    at 5   ???   7ff994d6caec   _ZSt25__throw_bad_function_callv + 3339243372 <- cut
```

A `suspend` or `Flow` body throws on a Kotlin worker thread, so its trace has no `kn_<hex>_...`
frame and ends at the last Kotlin frame instead. If a trace on macOS or Linux still shows `0x0` or
huge-offset frames, report it.

Use it as a catch filter to handle any Kotlin exception the same way, mapped or not:

```C#
catch (Exception ex) when (ex is IKotlinException ke)
{
    Console.WriteLine(ke.KotlinType);
}
```

`Exception.InnerException` follows Kotlin's `cause` chain, one exception per link, each mapped (or
falling back to `KotlinException`) independently. Each exception carries its own trace, and the
outer `KotlinStackTrace` has no `Caused by:` section, so read causes through `InnerException`
(Kotlin `Suppressed` exceptions do not cross at all):

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

### Telling a failure from a thrown exception {id="result-try"}

The throwing member cannot tell a modelled `Result.failure` apart from an exception the Kotlin body
threw: both surface as the same mapped `KotlinException` subtype. For that, every such member also
gets a `TryX` twin that follows the .NET Try pattern. It returns `false` for a `Result.failure`,
hands back the mapped exception through `failure`, and still throws for an exception the body threw.
A `Result<Unit>` twin has only `failure`.

```kotlin
fun weigh(catName: String): Result<Int> = when (catName) {
  "Ghost" -> throw IllegalStateException("No such cat: Ghost")
  "Oreo" -> Result.failure(IllegalArgumentException("Oreo will not get on the scale"))
  else -> Result.success(4)
}
```

```C#
bool weighed = service.TryWeigh("Mylo", out int weight, out Exception? failure);   // true, 4, null
weighed = service.TryWeigh("Oreo", out weight, out failure);   // false, 0, KotlinArgumentException
service.TryWeigh("Ghost", out _, out _);                       // throws KotlinInvalidOperationException
```

The twin is generated on class, object, companion, top-level, extension, sealed, interface and
abstract members, and overrides copy the modifier. On an interface it is a default interface method
that calls the throwing member, so a C# class implementing the interface does not write it. Three
things to know:

- A `Result.failure` that Kotlin built with `runCatching` around a programming error returns `false`
  too: `TryX` reports what the Kotlin function modelled.
- If `TryX` would share a name with a property, constant or nested type of the same type, with the
  type itself, with an extension property on the same receiver, or with a member the type inherits
  from a base declared in your module, the twin is dropped with the warning
  `SKIPPED_RESULT_TRY_COLLISION` and the throwing member stays. Rename one of them, or give one a
  `@CSharpName`, to get `TryX` back. If one twin in an override chain is dropped, every twin in the
  chain is dropped, with one warning each. A base from a dependency module cannot be checked, so
  its twin is kept. An enum's member property is an extension method, so the twin stays beside it.
- There is no twin on a `new` (covariant) sealed arm member, which inherits the base's, on an
  interface member whose payload mentions a variant type parameter (`fun next(): Result<T>` on
  `I<out T>`; implementing classes keep theirs), or on a `suspend fun`. None of these warns. The
  twin carries no default parameter values and no XML doc.

Only an ordinary return position is supported. `Result<T>` at a property, parameter, collection
element, `Flow<Result<T>>`, value-class-own-member, or `suspend fun` position keeps its existing
skip, as does a `Result<T>` whose payload has no return shape of its own (a sealed base, `Flow`).
