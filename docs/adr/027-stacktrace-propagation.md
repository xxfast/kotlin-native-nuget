# ADR-027: Stack trace propagation — `KotlinException.KotlinStackTrace` property

## Status

Proposed

## Context

ADR-023 established the `Pair<String, String>` (type, message) error bridge and explicitly **deferred** stack trace propagation:

> *"A future ADR could add `KotlinStackTrace` as an optional string property, passed only in debug builds via a compile-time flag."*

ADR-024 reiterated the deferral for sync functions. This ADR addresses the deferred item: whether and how to propagate the Kotlin stack trace as `KotlinException.KotlinStackTrace`.

### How Kotlin/Native exposes a stack trace

Kotlin's stdlib provides `Throwable.stackTraceToString()` since Kotlin 1.4. On Kotlin/Native it is fully supported and returns a multi-line string of the form:

```
kotlin.IllegalArgumentException: must be positive
    at io.github.xxfast.nuget.sample.SampleLibraryKt.requirePositive(SampleLibrary.kt:12)
    at io.github.xxfast.nuget.sample.SampleLibraryKt.add(SampleLibrary.kt:8)
    at io.github.xxfast.nuget.sample.SampleLibraryKt.export_add(SampleLibrary.kt:3)
    ...
```

**Caveats:**
- **Debug builds:** frames are fully symbolicated (file, line number) when the binary is compiled with `-g` (the default for `debug` build type). Controlled by the Gradle property `kotlin.native.binary.sourceInfoType=libbacktrace` (required since Kotlin 1.6.0 for Linux; default on Apple targets).
- **Release builds:** frames may show only addresses or mangled symbols, depending on strip settings. `kotlin.native.binary.stripDebugInfoMode` controls stripping on release. If the binary is stripped the trace is still returned but frames are address-only.
- **API reference:** https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/stack-trace-to-string.html

There is no separate `Throwable.getStackTrace()` array on Kotlin/Native (that API is JVM-only). `stackTraceToString()` is the cross-platform single call that works everywhere.

### How other Kotlin interop targets handle stack traces

| Target | Stack trace behavior |
|---|---|
| **JVM** | Kotlin exceptions are Java exceptions. The full JVM stack trace is natively preserved. `.getStackTrace()` returns `StackTraceElement[]`. No serialisation needed. |
| **ObjC/Swift export** | `NSError.userInfo` receives the Kotlin exception `toString()` (class name + message only). The Kotlin stack trace is **not** included in the `NSError` payload. The ObjC export documentation does not surface the trace — only the description string. See [Kotlin docs on interoperability with Swift/ObjC](https://kotlinlang.org/docs/native-objc-interop.html#errors-and-exceptions). |
| **Swift Export (alpha)** | Same as ObjC. The Kotlin trace is not propagated across the Swift bridge. |
| **Kotlin/JS** | Kotlin exceptions become JavaScript `Error` objects. The `cause` property may carry the original Kotlin object if the exception is rethrown. The JS stack trace is the JS engine's trace (not Kotlin's). |
| **Kotlin/Wasm** | Similar to JS. The Wasm runtime stack trace is surfaced, not the Kotlin source trace. |

**Conclusion:** No existing Kotlin interop target propagates the Kotlin stack trace as a structured field. This ADR proposes to go further than ObjC/Swift export by including the trace string — this is justified because C# developers are accustomed to reading stack traces from foreign runtimes (Java via J4N, Python via Python.NET, etc.) and the property is clearly labelled as a Kotlin trace.

### What is idiomatic in C# for foreign stack traces

`System.Exception.StackTrace` is a **get-only** property set internally by the .NET runtime from the managed call stack at the throw site. It cannot be assigned. Setting it requires the `ExceptionDispatchInfo` trick or reflection hacks — neither is idiomatic.

The established .NET pattern for carrying a foreign stack trace is a **named property** on a custom exception class. Examples from the .NET ecosystem:
- `JavaException.JavaStackTrace : string` (J4N library for Java/.NET interop)
- `PythonException.PythonStackTrace : string` (Python.NET)
- `RubyException.Backtrace : string[]` (Ruby interop libraries)

All follow the same pattern: separate named property, string, clearly foreign-scoped name.

**`ToString()` override**: .NET's `Exception.ToString()` returns `TypeName: Message\n   at C# stack trace`. Overriding it to append the Kotlin trace makes the exception self-describing in logs:

```
SampleLibrary.KotlinException: must be positive
 ---> Kotlin stack trace:
kotlin.IllegalArgumentException: must be positive
    at io.github.xxfast.nuget.sample.SampleLibraryKt.requirePositive(SampleLibrary.kt:12)
    at io.github.xxfast.nuget.sample.SampleLibraryKt.add(SampleLibrary.kt:8)
   --- End of Kotlin stack trace ---
   at SampleLibrary.SampleLibraryKt.Add(Int32 a, Int32 b) in ...
```

This format mirrors .NET's own inner exception rendering convention (`---> InnerException`), making it immediately familiar to C# developers. It is the same convention used by J4N and Python.NET.

### Bridge mechanism — Triple vs alternatives

The current error pointer is a `StableRef<Pair<String, String>>` with two shared exports (`nuget_error_type`, `nuget_error_message`).

**Option A — `Triple<String, String, String>` + third export `nuget_error_stacktrace`** (recommended)

Change the Kotlin `catch` block from `Pair(type, message)` to `Triple(type, message, stackTrace)`. Add a third shared export `nuget_error_stacktrace`. The `NugetErrorNative` helper gains a third method. All other infrastructure (callback ABI, error pointer shape, disposal) is unchanged.

```kotlin
// Kotlin catch block — before
StableRef.create(
    Pair(e::class.qualifiedName ?: e::class.simpleName ?: "UnknownException",
         e.message ?: "Kotlin error")
)

// Kotlin catch block — after
StableRef.create(
    Triple(
        e::class.qualifiedName ?: e::class.simpleName ?: "UnknownException",
        e.message ?: "Kotlin error",
        e.stackTraceToString()
    )
)
```

**Pros:**
- Minimal change: one extra field, one extra export, one extra C# method. The pattern is identical to the existing `Pair` extension.
- No ABI change: the error pointer is still one `COpaquePointer` (now pointing to a `Triple` instead of `Pair`).
- Cost is paid **only on the error path** — exceptions are exceptional; `stackTraceToString()` is only called when a Kotlin exception is caught. The payload is larger but only crosses the bridge on failure.
- `Triple` is in kotlin stdlib — no extra dependencies.

**Cons:**
- `stackTraceToString()` allocates a string even on the catch path. This is acceptable: the error path is not performance-sensitive.
- Release builds may produce address-only traces. This is the same limitation as any native library (C++, Rust, etc.) and is expected.

**Option B — Debug-only opt-in flag**

Include the stack trace only in debug builds, controlled by a KGP compile-time property (e.g. `nuget.propagateStackTrace=true`).

**Pros:** Zero overhead in release builds.
**Cons:** Complicates the processor (conditional code generation), complicates the `KotlinException` API (property would be nullable), breaks the principle of always-available diagnostics. If a production crash occurs, the trace is missing. The payload concern is minor: `stackTraceToString()` is a string of ~1–5 KB, only paid on exceptions. The concern raised in ADR-023 was correct for the _happy_ path; on the error path it is irrelevant.
**Rejected.**

**Option C — Always include (chosen)**

Always call `e.stackTraceToString()`. No compile-time flag. `KotlinStackTrace` is always non-null on `KotlinException`. This is simpler and consistent.

The concern ADR-023 raised was "It can be large (many frames), increasing bridge payload significantly." Re-evaluated: the bridge payload only increases **on the error path**. On the success path (the common case), the bridge payload is unchanged. A 2–5 KB string on an exception is entirely acceptable for diagnostic purposes.

**Decision: Option A (Triple) + Option C (always include).**

### What `KotlinException` should carry

```csharp
public class KotlinException : Exception
{
    public string KotlinType { get; }
    public string KotlinStackTrace { get; }

    public KotlinException(string kotlinType, string message, string kotlinStackTrace)
        : base(message)
    {
        KotlinType = kotlinType;
        KotlinStackTrace = kotlinStackTrace;
    }

    public override string ToString()
    {
        return base.ToString()
            + Environment.NewLine + " ---> Kotlin stack trace:"
            + Environment.NewLine + KotlinStackTrace
            + " --- End of Kotlin stack trace ---";
    }
}
```

Consumer usage:
```csharp
catch (KotlinException ex)
{
    // ex.KotlinType   == "kotlin.IllegalArgumentException"
    // ex.Message      == "must be positive"
    // ex.KotlinStackTrace contains the full Kotlin trace string
    logger.LogError(ex, "Kotlin error [{KotlinType}]", ex.KotlinType);
    // ex.ToString() includes the Kotlin trace in structured log output
}
```

## Decision

**Add `KotlinStackTrace : string` property to `KotlinException`. Change the Kotlin error object from `Pair<String, String>` to `Triple<String, String, String>`. Add `nuget_error_stacktrace` shared export. Override `ToString()` on `KotlinException` to include the Kotlin trace.**

### Kotlin side: error encoding change

All `catch (e: Throwable)` blocks in all export files change from:

```kotlin
StableRef.create(
    Pair(e::class.qualifiedName ?: e::class.simpleName ?: "UnknownException",
         e.message ?: "Kotlin error")
).asCPointer()
```

to:

```kotlin
StableRef.create(
    Triple(
        e::class.qualifiedName ?: e::class.simpleName ?: "UnknownException",
        e.message ?: "Kotlin error",
        e.stackTraceToString()
    )
).asCPointer()
```

### New shared export

```kotlin
@CName("nuget_error_stacktrace")
fun export_nuget_error_stacktrace(handle: COpaquePointer): String =
    handle.asStableRef<Triple<String, String, String>>().get().third
```

The existing `nuget_error_type` and `nuget_error_message` exports also change their `Pair` type reference to `Triple`:

```kotlin
@CName("nuget_error_type")
fun export_nuget_error_type(handle: COpaquePointer): String =
    handle.asStableRef<Triple<String, String, String>>().get().first

@CName("nuget_error_message")
fun export_nuget_error_message(handle: COpaquePointer): String =
    handle.asStableRef<Triple<String, String, String>>().get().second
```

### Updated `NugetErrorNative` helper (C#)

```csharp
internal static class NugetErrorNative
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_error_type")]
    private static extern IntPtr Native_type(IntPtr handle);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_error_message")]
    private static extern IntPtr Native_message(IntPtr handle);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_error_stacktrace")]
    private static extern IntPtr Native_stacktrace(IntPtr handle);

    internal static string Type(IntPtr handle) => Marshal.PtrToStringUTF8(Native_type(handle))!;
    internal static string Message(IntPtr handle) => Marshal.PtrToStringUTF8(Native_message(handle))!;
    internal static string StackTrace(IntPtr handle) => Marshal.PtrToStringUTF8(Native_stacktrace(handle))!;
}
```

### Updated error extraction at call sites

All error-handling sites change from:

```csharp
string kotlinType = NugetErrorNative.Type(errorPtr);
string msg = NugetErrorNative.Message(errorPtr);
NugetMarshal.Dispose(errorPtr);
throw new KotlinException(kotlinType, msg);
```

to:

```csharp
string kotlinType = NugetErrorNative.Type(errorPtr);
string msg = NugetErrorNative.Message(errorPtr);
string stackTrace = NugetErrorNative.StackTrace(errorPtr);
NugetMarshal.Dispose(errorPtr);
throw new KotlinException(kotlinType, msg, stackTrace);
```

## Alternatives Considered

### 1. Keep `KotlinStackTrace` nullable (opt-in)

Allow callers to skip the trace by passing `null`. Rejected: adds complexity, breaks the "always non-null" contract of the other `KotlinException` properties, and the overhead is trivial on the error path.

### 2. Encode trace in `Message`

Append the trace to the message string. Rejected: `Message` would be unusable for concise logging; it is already used for the Kotlin exception message. Separating into a dedicated property is unambiguously more idiomatic.

### 3. Surface trace only via `ToString()` without a property

Generate the trace into `ToString()` only, without a `KotlinStackTrace` property. Rejected: callers who want to log type + message separately and omit the verbose trace would have no way to do so. Having the property gives full flexibility.

### 4. Pass trace through `InnerException.StackTrace`

Create a synthetic `Exception` with the Kotlin trace as its message and pass it as `innerException`. The `InnerException.StackTrace` would be null (the inner exception was never thrown on the C# side). Rejected: confusing; `InnerException` implies a wrapped C# exception, not a foreign trace.

## Consequences

### Breaking changes

- `KotlinException` constructor changes from `(string kotlinType, string message)` to `(string kotlinType, string message, string kotlinStackTrace)`. All error-path code is generated; no manually-written C# changes needed.
- Kotlin `catch` blocks now create `Triple<String, String, String>` instead of `Pair<String, String>`.
- `nuget_error_type` and `nuget_error_message` shared exports change their type parameter from `Pair` to `Triple` (same symbols, same ABI, just a Kotlin internal change).
- Three cross-boundary calls per error (type, message, stacktrace) instead of two — still trivially fast on the error path.

### Affected export builders (Kotlin)

All files that produce the `StableRef.create(Pair(...))` pattern must be updated:

- `FunctionExports.kt` — `Pair(...)` → `Triple(...)` in all catch blocks
- `ClassExports.kt` — same
- `SuspendFunctionExports.kt` — same
- `GenericClassExports.kt` — same, plus the two existing exports (`export_nuget_error_type`, `export_nuget_error_message`) update their `Pair` reference to `Triple`, and a new `export_nuget_error_stacktrace` function is added

### Affected CIR / renderer (C#)

- `CirRenderer.kt` — `renderErrorHelper`: add `nuget_error_stacktrace` `[DllImport]`, add `StackTrace()` method, update `KotlinException` constructor to three-arg, add `ToString()` override.
- `CirRenderer.kt` — all error-extraction sites (8 locations per grep output): add `string stackTrace = NugetErrorNative.StackTrace(...)` line and pass it to `KotlinException` constructor.
- `CirFunctionTranslator.kt` — same error-extraction sites (2 locations): same additions.

### Scope

**v1 (this ADR):**
- `KotlinStackTrace : string` property on `KotlinException` (always populated, never null).
- `Triple<String, String, String>` encoding for all Kotlin error objects.
- `nuget_error_stacktrace` shared export.
- `KotlinException.ToString()` override appending the Kotlin trace.
- Applies to all error paths: sync functions, async callbacks, Flow errors, suspend lambda errors.

**Deferred (not in this ADR):**
- Exception cause chain (`e.cause` → `InnerException`) — separate roadmap item, separate ADR.
- Release-build trace quality improvements (debug symbols, `libbacktrace` configuration) — library author's concern, not the bridge generator's.
- `@Throws`-based opt-in to skip stack trace on specific functions — over-engineering; not needed.
