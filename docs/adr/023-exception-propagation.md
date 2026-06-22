# ADR-023: Exception propagation — Kotlin exceptions across the bridge

## Status

Proposed

## Context

ADR-019 established that Kotlin exceptions crossing the bridge become `KotlinException(message)` on the C# side:

> *"Kotlin exceptions become a `KotlinException` on the C# side. The exception message is the `Throwable.message` string (passed as a `StableRef<String>` through the error pointer)."*

The current implementation passes only `e.message ?: "Kotlin error"` across the bridge. This is the minimum viable propagation. The roadmap item "Handle cancellation and exceptions across the bridge" asks whether more information should cross: specifically the Kotlin exception type name and potentially the stack trace.

### What currently crosses the bridge

When a Kotlin exception is thrown in a suspend function:

```kotlin
catch (e: Throwable) {
    val errRef = StableRef.create(e.message ?: "Kotlin error").asCPointer()
    fn.invoke(null, errRef, 0.toByte(), userData)
}
```

C# receives an `IntPtr` to a `StableRef<String>` containing only the exception message. The C# side wraps it in:

```csharp
public class KotlinException : Exception
{
    public KotlinException(string message) : base(message) { }
}
```

Lost: the Kotlin exception class name (e.g., `IllegalArgumentException`, `IOException`), the Kotlin stack trace, and any cause chain.

### How other Kotlin interop targets handle exceptions

#### Java (JVM)

Kotlin exceptions **are** Java exceptions — `kotlin.IllegalArgumentException` is a subclass of `java.lang.IllegalArgumentException`. The full type hierarchy crosses the JVM boundary with no translation needed. Stack traces are native JVM stack traces and are fully preserved.

#### ObjC/Swift Export (built-in)

Kotlin/Native maps exceptions to `NSError` only when the function is annotated with `@Throws`. When `@Throws(IllegalArgumentException::class)` is present:
- The `NSError` domain is `"KotlinException"`.
- The error code is the hash code of the exception.
- The Kotlin exception object is stored in `NSError.userInfo[NSLocalizedDescriptionKey]` as a string (the `toString()` of the exception).
- The exception message is in `NSError.userInfo["KotlinException"]` as a nested `NSError`.

Without `@Throws`, Kotlin exceptions reaching ObjC/Swift terminate the program with an unhandled exception crash.

**Type name**: The `@Throws` annotation specifies which exception types are expected. The Kotlin exception class name is available in `NSError.domain` or via the `toString()`. Swift callers typically use `error.localizedDescription` which contains the Kotlin `toString()` (including class name).

#### Swift Export (Alpha)

Similar to ObjC export. Swift `throws` functions catch Kotlin exceptions into `NSError`. The mechanism is the same as ObjC export.

#### Kotlin/JS

`@JsExport` functions throw Kotlin exceptions as JavaScript `Error` objects with the Kotlin message. The exception class name is lost — JavaScript callers see a standard `Error`. No type mapping is performed.

### What C# developers expect from exceptions

Based on .NET best practices:

1. **Exception message** — must be present. Already implemented.
2. **Exception type** — `KotlinException` as a type is reasonable for exceptions from a foreign runtime. C# callers catch `KotlinException` as a group. More specific subtypes are possible but add complexity.
3. **Type name as a string property** — useful for logging/diagnostics without requiring a subtype per Kotlin exception.
4. **Stack trace** — the Kotlin stack trace is a string on the Kotlin side. Passing it across increases payload size. It would appear as the `InnerException.StackTrace` or as extra text in the `Message`. .NET's own stack trace is the C# call stack — not the Kotlin stack trace.
5. **Exception mapping** — mapping Kotlin exceptions to .NET built-in exception types (e.g., `IllegalArgumentException` → `ArgumentException`) requires a maintained mapping table and risks incorrect inference (Kotlin `IllegalArgumentException` does not always correspond to `ArgumentException` in .NET semantics).

### What information is worth crossing the bridge

The fundamental constraint is that the bridge is C-compatible. To cross the C boundary, exceptions must be serialised as primitive types or string `StableRef` handles. Three dimensions:

| Information | Cost to cross bridge | C# value |
|---|---|---|
| Message | Already crossing (1 `StableRef<String>`) | High — essential for debugging |
| Type name | +1 `StableRef<String>` | Medium — helps categorise exceptions in logs |
| Stack trace | +1 `StableRef<String>` (potentially large) | Low — Kotlin trace not useful in C# context; large payload |
| Mapped exception type | Complex: requires mapping table in generator | Very low — semantics don't reliably map across runtimes |

### What `KotlinException` should carry

The current `KotlinException : Exception` carries only `message`. Adding the Kotlin type name as a named property makes the exception more useful for diagnostics without changing the `message` or requiring exception type mapping:

```csharp
public class KotlinException : Exception
{
    public string KotlinType { get; }

    public KotlinException(string kotlinType, string message)
        : base(message)
    {
        KotlinType = kotlinType;
    }
}
```

Consumer usage:
```csharp
try
{
    await service.FetchAsync();
}
catch (KotlinException ex)
{
    logger.LogError("Kotlin error [{KotlinType}]: {Message}", ex.KotlinType, ex.Message);
}
```

This is additive: the message is still `ex.Message`, unchanged from the current API. The `KotlinType` property is a diagnostic aid, not a mechanism for control flow (C# callers should not branch on `KotlinType` string values — they should instead ask the Kotlin library author to expose a typed result).

### The error pointer ABI

Currently the error `StableRef` points to a `String` (the message). To pass both type name and message, two options exist:

**Option A — Two error pointers in the callback**

Add a second `errorTypePtr` alongside `errorPtr`:

```
(resultPtr, errorPtr, errorTypePtr, isCancelled, userData)
```

**Cons:** Breaks the `NugetAsyncCallback` ABI again (just changed in ADR-021). Doubles the error path parameter count. The callback signature becomes unwieldy.

**Option B — Single StableRef<KotlinErrorInfo> with two fields**

Pass a `StableRef` pointing to a data structure containing both fields. But Kotlin/Native's C interop does not support struct values crossing the boundary — only primitives and pointers.

**Option C — Single StableRef pointing to a pair<type, message>** (chosen)

Pass a `StableRef<Pair<String, String>>` where `first` is the type name and `second` is the message. Two new shared exports unwrap the pair:

```kotlin
@CName("nuget_error_type")
fun export_nuget_error_type(handle: COpaquePointer): String =
    handle.asStableRef<Pair<String, String>>().get().first

@CName("nuget_error_message")
fun export_nuget_error_message(handle: COpaquePointer): String =
    handle.asStableRef<Pair<String, String>>().get().second
```

This keeps the callback ABI unchanged (one `errorPtr` that now points to a richer structure). C# calls two additional exports to extract the fields.

**Option D — Separate string for type, reuse message pointer** (chosen alternative)

Change the Kotlin `catch` to create a `StableRef<Pair<String, String>>` always. But the existing `nuget_unwrap_string` / `NugetMarshal.FromHandle<string>` pattern that C# uses to read the error would need to be replaced. This is a breaking change to all existing error handling.

**Option E — Encode type in the message string** (rejected)

Prefix the message with the type name: `"IllegalArgumentException: must be positive"`. C# parses the prefix.

**Rejected:** Fragile; breaks if the message itself contains a colon; makes `ex.Message` ugly; not idiomatic for either C# or Kotlin.

### Decision on ABI

Given that this is pre-1.0 and all existing error handling C# code must be updated (because the bridge is generated), Option C is the cleanest: the `errorPtr` now points to a `StableRef<Pair<String, String>>`. Two new shared exports expose the fields. The existing `NugetAsyncCallback` delegate signature is unchanged.

## Alternatives Considered

### 1. Type name as a property on KotlinException, via Pair bridge (chosen)

Add `KotlinType` property to `KotlinException`. Pass a `StableRef<Pair<String, String>>` as the error pointer. Two shared exports extract type and message. Existing callback ABI unchanged.

**Pros:**
- `KotlinException` remains the single catch type for all Kotlin errors.
- `KotlinType` provides diagnostic information without requiring per-type subclasses.
- Callback ABI unchanged — only the error extraction on the C# side changes.
- Aligns with what ObjC export does: the Kotlin exception class name is available in the error info, but not mapped to a host type.

**Cons:**
- Two cross-boundary calls per error (type + message) instead of one.
- `StableRef<Pair<String, String>>` is slightly heavier than `StableRef<String>`.

### 2. Subtype KotlinException per Kotlin exception type

Generate C# subclasses like `KotlinIllegalArgumentException : KotlinException`, `KotlinIOException : KotlinException`, etc., mapping known Kotlin types.

**Pros:**
- Callers can `catch (KotlinIllegalArgumentException)` for type-specific handling.

**Cons:**
- The mapping must be maintained — Kotlin has hundreds of exception types.
- Kotlin library authors can throw custom exception types not in the standard library, which would fall back to `KotlinException` anyway.
- C# callers should not rely on exception types from a foreign runtime for control flow — they should use typed return values (Result types, etc.) instead.
- No other interop target (ObjC/Swift, JS) maps exception types — they all carry a name string and message.
- The generated mapping table would require updating with every new Kotlin stdlib version.

**Rejected**: Over-engineering. Not done by any other Kotlin interop target.

### 3. Map Kotlin exceptions to .NET built-in types

Map `IllegalArgumentException` → `ArgumentException`, `IllegalStateException` → `InvalidOperationException`, `IOException` → `IOException`, etc.

**Pros:**
- C# callers can catch familiar .NET exceptions.

**Cons:**
- Semantic mismatch: a Kotlin `IllegalArgumentException` in Kotlin `suspend fun` context is not necessarily the same as a .NET `ArgumentException` (which .NET convention says should be thrown synchronously, before entering the async path).
- Stack trace is Kotlin's — does not integrate with .NET exception mechanics.
- Kotlin exception types that don't have .NET equivalents still fall through.
- The mapping is lossy and potentially misleading.

**Rejected**: Not done by any other interop target. The ObjC export puts the exception type in the error info without trying to map it.

### 4. Keep current behaviour (message only)

Do not change `KotlinException`. The type name is not propagated.

**Pros:**
- No ABI change.
- Simpler.

**Cons:**
- Log messages say `KotlinException: some message` with no indication of what Kotlin exception type was thrown.
- Debugging Kotlin exceptions from C# side is harder.
- ObjC export already surfaces the type name in `NSError` — not doing so here would be a regression from ObjC's level of detail.

**Acceptable as a deferral** but the type name costs very little to add and has clear diagnostic value.

## Decision

Use **option 1: `KotlinType` property on `KotlinException` via a `Pair<String, String>` bridge**.

### Error encoding on Kotlin side

All `catch (e: Throwable)` blocks in async exports change from:

```kotlin
val errRef = StableRef.create(e.message ?: "Kotlin error").asCPointer()
```

to:

```kotlin
val errRef = StableRef.create(
    Pair(e::class.qualifiedName ?: e::class.simpleName ?: "UnknownException",
         e.message ?: "Kotlin error")
).asCPointer()
```

`e::class.qualifiedName` returns the fully-qualified Kotlin type name, e.g. `"kotlin.IllegalArgumentException"`. If the class is anonymous or not available (edge case with Kotlin reflection), `simpleName` is used; if both fail, `"UnknownException"` is the fallback.

### New shared exports for error extraction

```kotlin
@CName("nuget_error_type")
fun export_nuget_error_type(handle: COpaquePointer): String =
    handle.asStableRef<Pair<String, String>>().get().first

@CName("nuget_error_message")
fun export_nuget_error_message(handle: COpaquePointer): String =
    handle.asStableRef<Pair<String, String>>().get().second
```

These replace the current pattern of calling `NugetMarshal.FromHandle<string>(errorPtr)` (which unwrapped the `StableRef` as a plain `String`).

### NugetErrorNative helper class (generated once)

```csharp
internal static class NugetErrorNative
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_error_type")]
    [return: MarshalAs(UnmanagedType.LPUTF8Str)]
    internal static extern string Type(IntPtr handle);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_error_message")]
    [return: MarshalAs(UnmanagedType.LPUTF8Str)]
    internal static extern string Message(IntPtr handle);
}
```

### Updated KotlinException class

```csharp
public class KotlinException : Exception
{
    public string KotlinType { get; }

    public KotlinException(string kotlinType, string message)
        : base(message)
    {
        KotlinType = kotlinType;
    }
}
```

### Updated error handling in async callbacks

Error handling in all generated async callbacks changes from:

```csharp
else if (errorPtr != IntPtr.Zero)
{
    string msg = NugetMarshal.FromHandle<string>(errorPtr);
    t.SetException(new KotlinException(msg));
}
```

to:

```csharp
else if (errorPtr != IntPtr.Zero)
{
    string kotlinType = NugetErrorNative.Type(errorPtr);
    string msg = NugetErrorNative.Message(errorPtr);
    NugetMarshal.Dispose(errorPtr);
    t.SetException(new KotlinException(kotlinType, msg));
}
```

The `errorPtr` `StableRef` is disposed after extraction (same as result `StableRef`s). `NugetMarshal.Dispose` is a new alias for `nuget_dispose` to make the disposal explicit.

### Consumer experience

```csharp
try
{
    string result = await service.FetchAsync(cts.Token);
}
catch (KotlinException ex) when (ex.KotlinType == "kotlin.IllegalArgumentException")
{
    // Type-specific handling (use sparingly — prefer typed Kotlin results)
    logger.LogWarning("Bad argument: {Message}", ex.Message);
}
catch (KotlinException ex)
{
    logger.LogError("[{KotlinType}] {Message}", ex.KotlinType, ex.Message);
}
catch (TaskCanceledException)
{
    // Cancellation — either from CancellationToken or Dispose()
}
```

### What is NOT included in v1

**Stack trace**: The Kotlin stack trace string is not passed across the bridge in v1. Reasons:
1. It can be large (many frames), increasing bridge payload significantly.
2. Kotlin stack traces include internal coroutine frames that are not meaningful to C# callers.
3. The C# `Exception.StackTrace` property reflects the C# call stack, not the Kotlin one — there is no standard place to attach a foreign stack trace.
4. If debugging requires the Kotlin stack trace, the library author should use Kotlin-side logging.

**Deferred**: A future ADR could add `KotlinStackTrace` as an optional string property, passed only in debug builds via a compile-time flag.

## Consequences

### Breaking changes

- `KotlinException` constructor changes from `(string message)` to `(string kotlinType, string message)`. All error-path code is generated; no manually-written C# changes needed.
- `nuget_error_type` and `nuget_error_message` replace direct `StableRef<String>` unwrapping for error pointers. Generated code updated accordingly.
- Kotlin `catch` blocks create `Pair<String, String>` instead of `String` — a minor allocation increase on the error path (errors are exceptional, so this is acceptable).

### New infrastructure

- `nuget_error_type`, `nuget_error_message` shared exports in `GenericClassExports.kt`.
- `CirErrorNativeHelper` CIR declaration → `NugetErrorNative` class in `CirRenderer`.
- `KotlinException` CIR class updated with `KotlinType` property and two-arg constructor.
- `buildSuspendFunctionBody` and `buildSuspendMethodBody` updated: `Pair(typeName, message)` instead of `message`.
- `renderAsyncMethod` and `renderAsyncFunction` updated: `NugetErrorNative.Type/Message` instead of `NugetMarshal.FromHandle<string>`.

### Scope

**v1 (this ADR):**
- `KotlinType` property (fully-qualified Kotlin class name) on `KotlinException`.
- `KotlinException(string kotlinType, string message)` constructor.
- `nuget_error_type` and `nuget_error_message` shared exports.
- Applies to all async error paths (class methods, top-level functions, suspend lambdas).

**Deferred:**
- Kotlin stack trace as `KotlinStackTrace` string property.
- Synchronous exception propagation (non-suspend Kotlin functions don't throw across the bridge today — they crash; this is a separate concern).
- Exception cause chain (`ex.getCause()` in Kotlin → `InnerException` in C#).
