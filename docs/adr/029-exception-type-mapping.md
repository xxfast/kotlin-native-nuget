# ADR-029: Exception type mapping — Kotlin stdlib exceptions to .NET analogs with `IKotlinException`

## Status

Proposed

## Context

ADR-023 through ADR-028 built the full exception bridge: `KotlinException` carries `KotlinType`, `KotlinStackTrace`, and `InnerException` from the Kotlin side. The roadmap item deferred in ADR-023 (Alternative 3, "Map Kotlin exceptions to .NET built-in types") is now re-evaluated.

The current state: every Kotlin exception becomes a `KotlinException : Exception`. A C# developer who wants to distinguish an `IllegalArgumentException` from a generic Kotlin error must pattern-match on the `ex.KotlinType` string:

```csharp
catch (KotlinException ex) when (ex.KotlinType == "kotlin.IllegalArgumentException")
```

This is not idiomatic C#. The more natural pattern is:

```csharp
catch (ArgumentException ex)    // catches both .NET and Kotlin argument errors
```

This ADR decides whether to introduce mapped subtypes and how.

### How other Kotlin interop targets handle exception type mapping

#### Java (JVM)

`kotlin.IllegalArgumentException` **is** `java.lang.IllegalArgumentException` — it is a typealias for the JVM class. There is no mapping table because the identity is preserved at the bytecode level. Stack traces are native JVM stack traces. This is the only target where type identity is preserved structurally.

#### ObjC/Swift Export

Kotlin/Native's ObjC export maps exceptions to `NSError` only when functions are annotated with `@Throws`. There is **no per-type mapping**: all Kotlin exceptions collapse into `NSError` with `domain = "KotlinException"`. The Kotlin exception class name appears in the `NSError.userInfo` dictionary (as a string in `localizedDescription` / via `toString()`), not as a typed `NSError` subclass. ObjC callers branch on the domain string or the description, not on the error type. Swift callers use `error.localizedDescription`.

Source: [Kotlin/Native ObjC interoperability — Errors and exceptions](https://kotlinlang.org/docs/native-objc-interop.html#errors-and-exceptions)

**Conclusion:** ObjC/Swift does one bridge type plus a type-name string. No existing Kotlin target does per-type mapping.

#### Kotlin/JS

Kotlin exceptions surface as JavaScript `Error` objects. The exception class name is available through the Kotlin-JS runtime's `e.constructor.name` but is not mapped to a host `Error` subtype.

#### Kotlin/Wasm

Wasm component model traps do not carry typed exception information. Kotlin's Wasm export does not do exception type mapping.

### The .NET exception type hierarchy — what is idiomatic

Microsoft's [Framework Design Guidelines — Using Standard Exception Types](https://learn.microsoft.com/en-us/dotnet/standard/design-guidelines/using-standard-exception-types) define the canonical usage:

| .NET type | When to throw |
|---|---|
| `ArgumentException` | A method argument is invalid |
| `ArgumentNullException : ArgumentException` | Null passed where non-null required |
| `ArgumentOutOfRangeException : ArgumentException` | Value outside acceptable range |
| `InvalidOperationException` | Object in an inappropriate state for the operation |
| `NotSupportedException` | Operation not supported by this implementation |
| `InvalidCastException` | Type cast is invalid |
| `ArithmeticException` | Arithmetic error (e.g., division by zero) |
| `FormatException` | Input string has wrong format |
| `KeyNotFoundException` | Key not found in collection |

The guidelines explicitly state:
> `NullReferenceException`, `IndexOutOfRangeException`, and `AccessViolationException` are **reserved for the execution engine** and should NOT be explicitly thrown by user code or third-party libraries.

This is a critical constraint: mapping `kotlin.NullPointerException` → `NullReferenceException` or `kotlin.IndexOutOfBoundsException` → `IndexOutOfRangeException` would violate .NET design guidelines by having a library explicitly throw a type reserved for the runtime.

### The mapping table

Based on .NET semantics and the constraint above:

| Kotlin type | .NET analog | Justification |
|---|---|---|
| `kotlin.IllegalArgumentException` | `ArgumentException` | Exact semantic match — bad argument to a method |
| `kotlin.IllegalStateException` | `InvalidOperationException` | "Object in inappropriate state" matches `check()`/`error()` semantics |
| `kotlin.UnsupportedOperationException` | `NotSupportedException` | Standard .NET type for "not implemented / not applicable" operations |
| `kotlin.ClassCastException` | `InvalidCastException` | Direct semantic match |
| `kotlin.ArithmeticException` | `ArithmeticException` | Same name, same semantics (division by zero etc.) |
| `kotlin.NumberFormatException` | `FormatException` | String→number parsing failure matches `FormatException` exactly |
| `kotlin.NoSuchElementException` | `InvalidOperationException` | LINQ (`Enumerable.First()`, `Single()`) throws `InvalidOperationException` when sequence has no matching element — this is the established .NET pattern |
| `kotlin.ConcurrentModificationException` | `InvalidOperationException` | `List<T>` enumerator throws `InvalidOperationException` when modified during iteration — the canonical .NET pattern |
| `kotlin.NullPointerException` | **no mapping — stays `KotlinException`** | .NET guidelines: `NullReferenceException` is reserved for the CLR; explicitly throwing it from a library is a design violation |
| `kotlin.IndexOutOfBoundsException` | **no mapping — stays `KotlinException`** | .NET guidelines: `IndexOutOfRangeException` is reserved for the CLR. `ArgumentOutOfRangeException` is the appropriate user-code analog, but Kotlin's `IndexOutOfBoundsException` is broader than just an argument range error — the mapping would be misleading |
| `kotlin.StringIndexOutOfBoundsException` | **no mapping — stays `KotlinException`** | Same rationale as `IndexOutOfBoundsException` |
| Custom library exceptions | **no mapping — stays `KotlinException`** | Only stdlib exceptions are mapped in v1 |

**Note on `NoSuchElementException` vs `KeyNotFoundException`:** `KeyNotFoundException` is for key-based collection lookup failures (dictionary access). Kotlin's `NoSuchElementException` is thrown by `first()`, `last()`, `single()`, etc. on sequences — this matches `InvalidOperationException` as thrown by LINQ, not `KeyNotFoundException`.

### The `IKotlinException` interface

Mapped subtypes inherit from .NET base classes (`ArgumentException`, `InvalidOperationException`, etc.) and therefore are **not** `KotlinException`. To preserve the ability to catch all Kotlin-originating exceptions in a single handler, an interface is needed:

```csharp
public interface IKotlinException
{
    string KotlinType { get; }
    string KotlinStackTrace { get; }
}
```

Both `KotlinException` (the fallback for unmapped types) and every mapped subtype implement this interface. This lets C# callers write:

```csharp
catch (Exception ex) when (ex is IKotlinException ke)
{
    logger.LogError("[{KotlinType}] {Message}", ke.KotlinType, ex.Message);
}
```

### Backward compatibility

Existing tests catch `KotlinException`:

```csharp
Assert.ThrowsAsync<KotlinException>(() => AsyncExceptions.FetchCatTreatAsync("Oreo"));
// ex.KotlinType == "kotlin.IllegalArgumentException"
```

Under this ADR, `KotlinArgumentException : ArgumentException` is **not** a `KotlinException`. The above test would fail.

**This is a breaking change for existing exception tests.** The fix is to update those tests to catch via `IKotlinException` or via the specific .NET type:

```csharp
// Before
Assert.ThrowsAsync<KotlinException>(...)

// After (option A — use .NET type)
Assert.ThrowsAsync<ArgumentException>(...)

// After (option B — use interface, via base Exception + is-check)
var ex = await Assert.ThrowsAsync<Exception>(...);
Assert.IsAssignableFrom<IKotlinException>(ex);
```

Since this is a roadmap-planned change and all exception-handling code is generated, the breaking change is acceptable. The existing exception tests in `ExceptionPropagationTests.cs` and `SyncExceptionPropagationTests.cs` must be updated to catch `ArgumentException` (or the concrete `KotlinArgumentException`) instead of `KotlinException`.

### The `BuildException` factory update

`BuildException` in `NugetErrorNative` currently always returns `new KotlinException(...)`. With this ADR it becomes a switch on `kotlinType`:

```csharp
internal static Exception BuildException(IntPtr errorPtr)
{
    // ... build cause chain (same as ADR-028) ...
    string kotlinType = Type(errorPtr);
    string msg = Message(errorPtr);
    string stackTrace = StackTrace(errorPtr);
    NugetMarshal.Dispose(errorPtr);
    return kotlinType switch
    {
        "kotlin.IllegalArgumentException" =>
            new KotlinArgumentException(kotlinType, msg, stackTrace, inner),
        "kotlin.IllegalStateException" =>
            new KotlinInvalidOperationException(kotlinType, msg, stackTrace, inner),
        "kotlin.UnsupportedOperationException" =>
            new KotlinNotSupportedException(kotlinType, msg, stackTrace, inner),
        "kotlin.ClassCastException" =>
            new KotlinInvalidCastException(kotlinType, msg, stackTrace, inner),
        "kotlin.ArithmeticException" =>
            new KotlinArithmeticException(kotlinType, msg, stackTrace, inner),
        "kotlin.NumberFormatException" =>
            new KotlinFormatException(kotlinType, msg, stackTrace, inner),
        "kotlin.NoSuchElementException" =>
            new KotlinInvalidOperationException(kotlinType, msg, stackTrace, inner),
        "kotlin.ConcurrentModificationException" =>
            new KotlinInvalidOperationException(kotlinType, msg, stackTrace, inner),
        _ => new KotlinException(kotlinType, msg, stackTrace, inner)
    };
}
```

`BuildException` return type changes from `KotlinException` to `Exception` (the lowest common ancestor of all mapped types and `KotlinException`). All call sites already assign to a local and rethrow, so this is type-safe.

## Alternatives Considered

### 1. Introduce `IKotlinException` + mapped subtypes (chosen)

Generate `KotlinArgumentException : ArgumentException, IKotlinException`, etc. `BuildException` switches on `kotlinType`. Unmapped types remain `KotlinException : Exception, IKotlinException`. Base `KotlinException` also implements `IKotlinException`.

**Pros:**
- C# devs can write `catch (ArgumentException)` and get Kotlin `IllegalArgumentException` automatically.
- `IKotlinException` provides a common handle for "any Kotlin exception" without requiring inheritance from a single class.
- Aligns with how .NET libraries from other runtimes (e.g., Java via IKVM) expose mapped exceptions.
- The mapping table is stable — the Kotlin stdlib exception hierarchy has not changed in years.

**Cons:**
- Breaking change: existing tests that catch `KotlinException` must be updated to catch the .NET type or `IKotlinException`.
- The `BuildException` switch adds a small amount of code to the generated helper, but it is a switch on string constants with 8 cases — trivially fast.
- Custom Kotlin exception types still fall back to `KotlinException`; library consumers who depend on a custom subtype must still check `KotlinType` string.

### 2. Keep current behaviour — single `KotlinException` for all types

No change to ADR-028. Callers use `when (ex.KotlinType == "...")` for type-specific handling.

**Pros:**
- No breaking change to existing tests.
- Simpler `BuildException` — no switch needed.
- Consistent with ObjC/Swift export behavior (one bridge type).

**Cons:**
- Catching `ArgumentException` from Kotlin code requires a try/catch that filters on `KotlinType` — not idiomatic .NET.
- Code that catches both C# `ArgumentException` and Kotlin `IllegalArgumentException` needs two handlers.
- The feature was explicitly planned in the roadmap as a future improvement.

**Rejected:** The `IKotlinException` interface is what makes option 1 viable — it preserves catch-all ability. Without it, option 1 would be pure breaking change. With it, option 1 is strictly additive from a semantics perspective.

### 3. Map exceptions via inheritance from `KotlinException`

Generate `KotlinIllegalArgumentException : KotlinException` without inheriting from a .NET base class.

**Pros:**
- Backward compatible — existing `catch (KotlinException)` continues to work.
- No `IKotlinException` needed.

**Cons:**
- A C# developer still cannot write `catch (ArgumentException)` and receive Kotlin argument errors. The goal of idiomatic .NET exception handling is not met.
- The generated class name `KotlinIllegalArgumentException` is awkward — it's Kotlin-centric, not .NET-centric.
- No benefit over the current `KotlinType` string pattern except the ability to catch by subtype — but the subtype name is still Kotlin-flavored.

**Rejected:** Does not meet the "native C#" design goal.

### 4. Map ALL Kotlin exceptions including NullPointerException and IndexOutOfBoundsException

Add `NullReferenceException` and `IndexOutOfRangeException` to the mapping table.

**Rejected:** The .NET Framework Design Guidelines explicitly state these types are reserved for the CLR/execution engine and should not be thrown by user code or third-party libraries. Mapping to them would be a design violation that surprises C# developers ("this exception should only come from the runtime").

## Decision

**Introduce `IKotlinException` interface. Generate mapped subtypes for 8 Kotlin stdlib exception types. `KotlinException` becomes the fallback for unmapped types. Both `KotlinException` and all mapped subtypes implement `IKotlinException`. Update `BuildException` to switch on `kotlinType`.**

### `IKotlinException` interface (generated once)

```csharp
public interface IKotlinException
{
    string KotlinType { get; }
    string KotlinStackTrace { get; }
}
```

### Updated `KotlinException` (fallback)

```csharp
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

### Generated mapped subtypes (generated once, 8 classes)

Each mapped subtype inherits from the .NET base class and implements `IKotlinException`. The constructor delegates to the .NET base using the `message` parameter (not `kotlinType`), so `ex.Message` is the Kotlin exception's message and `ex.KotlinType` holds the fully-qualified Kotlin class name.

```csharp
public sealed class KotlinArgumentException : ArgumentException, IKotlinException
{
    public string KotlinType { get; }
    public string KotlinStackTrace { get; }

    public KotlinArgumentException(string kotlinType, string message, string kotlinStackTrace,
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

public sealed class KotlinInvalidOperationException : InvalidOperationException, IKotlinException
{
    // ... same pattern, maps: IllegalStateException, NoSuchElementException, ConcurrentModificationException
}

public sealed class KotlinNotSupportedException : NotSupportedException, IKotlinException
{
    // ... maps: UnsupportedOperationException
}

public sealed class KotlinInvalidCastException : InvalidCastException, IKotlinException
{
    // ... maps: ClassCastException
}

public sealed class KotlinArithmeticException : ArithmeticException, IKotlinException
{
    // ... maps: ArithmeticException (same name, same concept)
}

public sealed class KotlinFormatException : FormatException, IKotlinException
{
    // ... maps: NumberFormatException
}
```

Note: `KotlinInvalidOperationException` handles three Kotlin types (`IllegalStateException`, `NoSuchElementException`, `ConcurrentModificationException`) because all three map to the same .NET base.

### Updated `BuildException` in `NugetErrorNative`

```csharp
internal static Exception BuildException(IntPtr errorPtr)
{
    int causeCount = CauseCount(errorPtr);
    Exception? inner = null;
    for (int i = causeCount - 1; i >= 1; i--)
    {
        string causeType = CauseType(errorPtr, i);
        string causeMsg = CauseMessage(errorPtr, i);
        string causeStack = CauseStackTrace(errorPtr, i);
        inner = BuildMapped(causeType, causeMsg, causeStack, inner);
    }
    string kotlinType = Type(errorPtr);
    string msg = Message(errorPtr);
    string stackTrace = StackTrace(errorPtr);
    NugetMarshal.Dispose(errorPtr);
    return BuildMapped(kotlinType, msg, stackTrace, inner);
}

private static Exception BuildMapped(string kotlinType, string message, string stackTrace, Exception? inner) =>
    kotlinType switch
    {
        "kotlin.IllegalArgumentException" =>
            new KotlinArgumentException(kotlinType, message, stackTrace, inner),
        "kotlin.IllegalStateException" =>
            new KotlinInvalidOperationException(kotlinType, message, stackTrace, inner),
        "kotlin.NoSuchElementException" =>
            new KotlinInvalidOperationException(kotlinType, message, stackTrace, inner),
        "kotlin.ConcurrentModificationException" =>
            new KotlinInvalidOperationException(kotlinType, message, stackTrace, inner),
        "kotlin.UnsupportedOperationException" =>
            new KotlinNotSupportedException(kotlinType, message, stackTrace, inner),
        "kotlin.ClassCastException" =>
            new KotlinInvalidCastException(kotlinType, message, stackTrace, inner),
        "kotlin.ArithmeticException" =>
            new KotlinArithmeticException(kotlinType, message, stackTrace, inner),
        "kotlin.NumberFormatException" =>
            new KotlinFormatException(kotlinType, message, stackTrace, inner),
        _ => new KotlinException(kotlinType, message, stackTrace, inner)
    };
```

The cause chain uses `BuildMapped` for each node in the chain — cause exceptions are also mapped to their .NET analogs when applicable.

### Recommended C# consumer API

```csharp
// Idiomatic: catch by .NET type — works for both C# and Kotlin argument errors
try
{
    await service.FetchAsync(name);
}
catch (ArgumentException ex)
{
    // Catches both C# ArgumentException and KotlinArgumentException (from Kotlin IllegalArgumentException)
    logger.LogError("Bad argument: {Message}", ex.Message);

    // Access Kotlin metadata if needed
    if (ex is IKotlinException ke)
        logger.LogDebug("[KotlinType: {KotlinType}]", ke.KotlinType);
}
catch (InvalidOperationException ex)
{
    // Catches KotlinInvalidOperationException (from IllegalStateException, NoSuchElementException, ConcurrentModificationException)
    logger.LogError("Invalid operation: {Message}", ex.Message);
}

// Catch-all for any Kotlin exception (replaces the old "catch KotlinException" pattern)
catch (Exception ex) when (ex is IKotlinException)
{
    var ke = (IKotlinException)ex;
    logger.LogError("[{KotlinType}] {Message}", ke.KotlinType, ex.Message);
}
```

## Consequences

### Breaking changes

- `KotlinException` now implements `IKotlinException`. This is additive and does not break existing code.
- For the **8 mapped Kotlin exception types**, `BuildException` no longer returns a `KotlinException`. Existing code that catches `KotlinException` and expects to receive `IllegalArgumentException`-originated exceptions will no longer catch those — they become `KotlinArgumentException : ArgumentException`, which is not a `KotlinException`.
- The existing exception tests in `ExceptionPropagationTests.cs` and `SyncExceptionPropagationTests.cs` assert `Assert.ThrowsAsync<KotlinException>` with `ex.KotlinType == "kotlin.IllegalArgumentException"`. These must be updated to either:
  - Assert `ThrowsAsync<ArgumentException>` (or `KotlinArgumentException`) and cast `ex` to `IKotlinException` to access `KotlinType`, OR
  - Assert `ThrowsAsync<Exception>` with an `is IKotlinException` guard.
- `BuildException` return type changes from `KotlinException` to `Exception`. Call sites that stored the result in `KotlinException result = BuildException(...)` must change to `Exception result = BuildException(...)` or use `var`.

### New generated infrastructure (C# side only — Kotlin side unchanged)

- `IKotlinException` interface emitted in `renderErrorHelper`.
- `IKotlinException` added to `KotlinException` implements list.
- 6 new mapped exception classes: `KotlinArgumentException`, `KotlinInvalidOperationException`, `KotlinNotSupportedException`, `KotlinInvalidCastException`, `KotlinArithmeticException`, `KotlinFormatException`.
- `BuildMapped` private helper extracted from `BuildException`; `BuildException` updated to use it for cause chain nodes too.
- `BuildException` return type changed from `KotlinException` to `Exception`.

### No Kotlin-side changes

The error bridge (NugetError, buildError, nuget_error_* exports) is unchanged. The entire implementation is a C# generator change in `CirRenderer.kt`.

### Scope

**v1 (this ADR):**
- `IKotlinException` interface.
- 8 mapped Kotlin stdlib exception types: `IllegalArgumentException`, `IllegalStateException`, `UnsupportedOperationException`, `ClassCastException`, `ArithmeticException`, `NumberFormatException`, `NoSuchElementException`, `ConcurrentModificationException`.
- `KotlinException` as fallback for all unmapped types.
- Cause chain nodes also mapped via `BuildMapped`.

**Explicitly NOT mapped (stay `KotlinException`):**
- `kotlin.NullPointerException` — .NET guidelines prohibit explicitly throwing `NullReferenceException`.
- `kotlin.IndexOutOfBoundsException` / `kotlin.StringIndexOutOfBoundsException` — .NET guidelines prohibit explicitly throwing `IndexOutOfRangeException`.
- Custom exception types from Kotlin libraries (infinite space; falls to `KotlinException` fallback).

**Deferred:**
- `kotlin.IOException` → `System.IO.IOException` — this is a natural addition but `IOException` is in `System.IO`, a different namespace from the core mapping; deferred to a follow-up ADR.
- `kotlin.StackOverflowError` → no mapping (CLR reserved).
- `kotlin.OutOfMemoryError` → no mapping (CLR reserved).
- Custom type mapper extension point (let library authors register extra mappings) — future improvement.
