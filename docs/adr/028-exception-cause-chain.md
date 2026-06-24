# ADR-028: Exception cause chain — `e.cause` → `KotlinException.InnerException`

## Status

Proposed

## Context

ADR-023 introduced `KotlinException` with `KotlinType` and `Message`. ADR-027 added `KotlinStackTrace` and changed the bridge payload from `Pair<String,String>` to `Triple<String,String,String>`. Both ADRs explicitly deferred the exception cause chain:

> *"Exception cause chain (`ex.getCause()` in Kotlin → `InnerException` in C#) — separate roadmap item, separate ADR."* — ADR-023 Consequences
> *"Exception cause chain (`e.cause` → `InnerException`) — separate roadmap item, separate ADR."* — ADR-027 Consequences

This ADR addresses that deferred item.

### Kotlin/Native `Throwable.cause` — how it works

`Throwable.cause` is declared as `val cause: Throwable?` in `kotlin.Throwable`
([Kotlin stdlib source](https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/src/kotlin/exceptions/Exceptions.kt)).
On Kotlin/Native the property is fully accessible at runtime — it is not JVM-only.
The chain is recursive: a cause can itself have a cause, forming an arbitrary-depth linked list.

Cycles (`A.cause = B`, `B.cause = A`) are not constructible via the standard `Throwable(cause)` constructor, but they are constructible by direct assignment in principle. In practice Kotlin/Native does not implement cycle detection in the runtime, so the bridge must guard against infinite loops when walking the chain.

### How other Kotlin interop targets handle cause chains

| Target | Cause chain behavior |
|---|---|
| **JVM** | Kotlin exceptions are Java exceptions. `Throwable.cause` IS `java.lang.Throwable.getCause()`. The full cause chain is natively preserved in the JVM exception object — no bridge encoding needed. |
| **ObjC/Swift export** | `NSError.userInfo[NSUnderlyingErrorKey]` is the idiomatic place for a "cause" error. Kotlin/Native's built-in ObjC export does **not** populate `NSUnderlyingErrorKey` with the Kotlin cause — only the top-level exception's `toString()` is placed in `NSError.localizedDescription`. The cause chain is **lost**. See [Kotlin Native / ObjC interoperability docs](https://kotlinlang.org/docs/native-objc-interop.html#errors-and-exceptions). |
| **Swift Export (alpha)** | Same as ObjC export. The cause chain is not propagated. |
| **Kotlin/JS** | `@JsExport` functions surface exceptions as JavaScript `Error` objects. `Error.cause` was added in ES2022 and Kotlin/JS does not populate it from `Throwable.cause`. The chain is lost. See [MDN Error.cause](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Error/cause). |
| **Kotlin/Wasm** | Similar to JS. The cause is not propagated through the Wasm boundary. |

**Conclusion:** No existing Kotlin interop target propagates the cause chain. This ADR proposes to go further than any existing target — justified because C# developers are accustomed to `InnerException` and `Exception.ToString()` already renders it idiomatically.

### What is idiomatic in C# for cause chains

`Exception.InnerException` is the direct C# equivalent of `Throwable.cause`. The constructor pattern
`Exception(string message, Exception innerException)` is the standard way to chain exceptions
([Microsoft docs — Exception.InnerException](https://learn.microsoft.com/en-us/dotnet/api/system.exception.innerexception)).

`Exception.ToString()` renders the chain automatically in the format:

```
OuterException: outer message
 ---> InnerException: inner message
    at inner stack frames
 --- End of inner exception stack trace ---
    at outer stack frames
```

The existing `KotlinException.ToString()` override (from ADR-027) appends the Kotlin stack trace after `base.ToString()`. When `InnerException` is set, `base.ToString()` will already render the inner exception block using .NET's convention. The ADR-027 `ToString()` override therefore remains correct without modification — the Kotlin trace section is appended after the inner exception block, which is exactly the right read order for a C# developer.

### The hard design question — bridge encoding for a recursive structure

The current bridge payload is a `StableRef<Triple<String,String,String>>` (type, message, stackTrace) with three scalar exports. A cause chain is recursive. Three options were considered:

---

#### Option A — Flatten the chain on the Kotlin side into an indexed list (recommended)

On the Kotlin side, before creating the `StableRef`, walk `e.cause` (with a cycle guard) and build a recursive `NugetError` structure that mirrors the chain. The `StableRef` holds the top-level `NugetError`.

A dedicated data class models the error and its recursive cause directly — clearer than a positional `Triple` (where callers must remember `first`=type, `second`=message, `third`=stacktrace). A `StableRef` can pin any Kotlin object, so the data class is a drop-in with no C ABI impact:

```kotlin
data class NugetError(
    val type: String,
    val message: String,
    val stackTrace: String,
    val cause: NugetError?,
)
```

New shared exports. The indexed accessors walk `.cause`; depth is typically 1–3 and this is the error path, so the O(index) walk is irrelevant:

```kotlin
@CName("nuget_error_cause_count")
fun export_nuget_error_cause_count(handle: COpaquePointer): Int {
    var e: NugetError? = handle.asStableRef<NugetError>().get()
    var n = 0
    while (e != null) { n++; e = e.cause }
    return n
}

@CName("nuget_error_cause_type")
fun export_nuget_error_cause_type(handle: COpaquePointer, index: Int): String =
    handle.asStableRef<NugetError>().get().at(index).type

@CName("nuget_error_cause_message")
fun export_nuget_error_cause_message(handle: COpaquePointer, index: Int): String =
    handle.asStableRef<NugetError>().get().at(index).message

@CName("nuget_error_cause_stacktrace")
fun export_nuget_error_cause_stacktrace(handle: COpaquePointer, index: Int): String =
    handle.asStableRef<NugetError>().get().at(index).stackTrace

private tailrec fun NugetError.at(index: Int): NugetError =
    if (index == 0) this else cause!!.at(index - 1)
```

The existing `nuget_error_type`, `nuget_error_message`, `nuget_error_stacktrace` exports now read the top-level `NugetError` (index 0). Their `StableRef` type parameter changes from `Triple<String,String,String>` to `NugetError`; their C symbols and signatures are unchanged:

```kotlin
// Before (ADR-027):
StableRef.create(Triple(type, message, stackTrace)).asCPointer()

// After (this ADR):
StableRef.create(buildError(e)).asCPointer()

// where:
private fun buildError(e: Throwable): NugetError {
    val seen = mutableSetOf<Throwable>()
    fun build(t: Throwable): NugetError? {
        if (!seen.add(t)) return null // cycle guard — stop if already visited
        return NugetError(
            type = t::class.qualifiedName ?: t::class.simpleName ?: "UnknownException",
            message = t.message ?: "Kotlin error",
            stackTrace = t.stackTraceToString(),
            cause = t.cause?.let(::build),
        )
    }
    return build(e)!! // e is added to seen first, so this is never null
}
```

Cycle protection: `seen.add(current)` returns `false` when a `Throwable` object has already been visited. The loop terminates.

On the C# side, after extracting the top-level type/message/stackTrace (unchanged), a second pass calls `nuget_error_cause_count` and reconstructs nested `KotlinException` objects from the tail of the list, folding from the deepest cause inward:

```csharp
// Build the chain from deepest cause to the top, then the top is created normally
int causeCount = NugetErrorNative.CauseCount(errorPtr);
KotlinException? inner = null;
for (int i = causeCount - 1; i >= 1; i--)
{
    string causeType = NugetErrorNative.CauseType(errorPtr, i);
    string causeMsg = NugetErrorNative.CauseMessage(errorPtr, i);
    string causeStack = NugetErrorNative.CauseStackTrace(errorPtr, i);
    inner = new KotlinException(causeType, causeMsg, causeStack, inner);
}
string kotlinType = NugetErrorNative.Type(errorPtr);
string msg = NugetErrorNative.Message(errorPtr);
string stackTrace = NugetErrorNative.StackTrace(errorPtr);
NugetMarshal.Dispose(errorPtr);
throw new KotlinException(kotlinType, msg, stackTrace, inner);
```

**ABI impact:** The callback signature (`NugetAsyncCallback`) and `errorOut` parameter are **unchanged**. The `errorPtr` is still one `COpaquePointer` passed through the existing channel. C# makes `2 + 4×N` cross-boundary calls per error where `N` is the cause depth (typically 1–3). On the success path (no exception), zero additional calls.

**Disposal:** The entire chain lives in one `StableRef` pointing to the top-level `NugetError` (causes are reachable via `.cause`). `NugetMarshal.Dispose(errorPtr)` disposes this single ref, freeing everything. No per-cause handles to track.

**Cycle protection:** Handled on the Kotlin side via `mutableSetOf<Throwable>()`. The C# side sees a finite list and iterates over it with a bounded index loop — no C#-side cycle risk.

**Compatibility with ADR-027:** The existing `nuget_error_type`, `nuget_error_message`, `nuget_error_stacktrace` exports change their `StableRef` type parameter from `Triple` to `NugetError`, but their C symbol names and signatures are unchanged. The C# `NugetErrorNative.Type/Message/StackTrace` P/Invoke declarations do not change.

---

#### Option B — Lazy chain via linked `StableRef` handles

Keep the error `StableRef` pointing at the top `Throwable`'s `Triple`. Add a `nuget_error_cause` export that, given the error handle, creates and returns a **new** `StableRef<Triple<String,String,String>>` for the cause (or null if no cause):

```kotlin
@CName("nuget_error_cause")
fun export_nuget_error_cause(handle: COpaquePointer): COpaquePointer? {
    val triple = handle.asStableRef<Triple<String, String, String>>().get()
    // ... but the Triple doesn't hold the original Throwable, so cause is unavailable!
}
```

**Fatal flaw:** The current `Triple` payload does not hold the original `Throwable`. To implement this option, the `StableRef` would need to hold the `Throwable` itself rather than a serialised `Triple`. That is a significant change to the existing error encoding established by ADR-023.

To fix this, Option B would require changing the `StableRef` payload to `Throwable` and deriving type/message/stackTrace/cause on demand via exports — this is effectively Option C, described below.

**Rejected:** The existing three exports (`nuget_error_type/message/stacktrace`) would change their `StableRef` type parameter in a way that is semantically different from Option A, and the lazy traversal adds per-level cross-boundary round-trips for a chain of depth N (3N calls total for the chain vs 4N+2 for Option A, a marginal difference). The disposal ownership of intermediate per-cause `StableRef` handles on the C# side adds complexity (caller must dispose each cause handle).

---

#### Option C — Store the `Throwable` itself in the `StableRef`

Change the `StableRef` payload from `Triple<String,String,String>` to the `Throwable` object itself. All three existing exports (`nuget_error_type`, `nuget_error_message`, `nuget_error_stacktrace`) and the new `nuget_error_cause` export derive their values from the live `Throwable` reference:

```kotlin
@CName("nuget_error_type")
fun export_nuget_error_type(handle: COpaquePointer): String =
    handle.asStableRef<Throwable>().get().let {
        it::class.qualifiedName ?: it::class.simpleName ?: "UnknownException"
    }
```

**Pros:**
- Natural: the cause chain is reachable without any pre-serialisation.
- The lazy approach is O(depth) cross-boundary calls; no upfront allocation.

**Cons:**
- Breaks the `StableRef` type parameter for all three existing exports — this is an ABI-incompatible change to the internal type used in `asStableRef<>()`. While the C symbols remain the same, the Kotlin-side implementation changes non-trivially.
- `stackTraceToString()` is called lazily (on the C# side's request) rather than once at catch time. If the Throwable is deeply coroutine-wrapped, `stackTraceToString()` may traverse the chain internally, making the cost of the first call unpredictable.
- The `Throwable` object stays alive (pinned via `StableRef`) until explicitly disposed. With lazy cause resolution, each cause is a nested `StableRef` that C# must dispose independently — the same ownership problem as Option B.
- Requires the cycle guard to be on the C# side (bounded loop depth), which adds C# complexity.

**Rejected:** The `StableRef<Throwable>` approach is cleaner in theory but introduces per-cause handle management complexity and requires a more invasive change to the existing error encoding.

---

### Decision on bridge encoding

**Option A** is the recommended approach.

It extends the existing `StableRef<Triple>` pattern minimally: the payload becomes a recursive `NugetError` data class and one new export (`nuget_error_cause_count`) plus three indexed variants of the existing exports are added. The callback ABI, the `errorOut` parameter shape, and the C symbol names of the existing three exports are all unchanged. Cycle protection is entirely on the Kotlin side. Disposal is one call. Cross-boundary call count is bounded by 2 + 4×depth, always paid only on the error path.

## Alternatives Considered

See the three options in Context above. The final choice is **Option A** (flatten to list on Kotlin side, reconstruct nested `KotlinException` on C# side).

## Decision

**Add `InnerException` support to `KotlinException` by encoding the full cause chain as a recursive `NugetError` data class in the `StableRef`. Add `nuget_error_cause_count` and three indexed exports for cause fields. Update all `catch` blocks to call `buildError()`. Update C# error extraction to reconstruct nested `KotlinException` objects and set `InnerException`.**

### Kotlin side: `NugetError` data class and `buildError` helper

Add a shared data class and helper (in `GenericClassExports.kt` or a shared `Helpers.kt`) — generated once:

```kotlin
data class NugetError(
    val type: String,
    val message: String,
    val stackTrace: String,
    val cause: NugetError?,
)

private fun buildError(e: Throwable): NugetError {
    val seen = mutableSetOf<Throwable>()
    fun build(t: Throwable): NugetError? {
        if (!seen.add(t)) return null // cycle guard — stop if already visited
        return NugetError(
            type = t::class.qualifiedName ?: t::class.simpleName ?: "UnknownException",
            message = t.message ?: "Kotlin error",
            stackTrace = t.stackTraceToString(),
            cause = t.cause?.let(::build),
        )
    }
    return build(e)!! // e is added to seen first, so this is never null
}

private tailrec fun NugetError.at(index: Int): NugetError =
    if (index == 0) this else cause!!.at(index - 1)
```

All `catch (e: Throwable)` blocks across all export files change from:

```kotlin
// Before (ADR-027):
StableRef.create(Triple(
    e::class.qualifiedName ?: e::class.simpleName ?: "UnknownException",
    e.message ?: "Kotlin error",
    e.stackTraceToString()
)).asCPointer()

// After (this ADR):
StableRef.create(buildError(e)).asCPointer()
```

### Kotlin side: updated and new shared exports

The three existing exports change their `StableRef` type parameter to `NugetError` (reading the top-level error):

```kotlin
@CName("nuget_error_type")
fun export_nuget_error_type(handle: COpaquePointer): String =
    handle.asStableRef<NugetError>().get().type

@CName("nuget_error_message")
fun export_nuget_error_message(handle: COpaquePointer): String =
    handle.asStableRef<NugetError>().get().message

@CName("nuget_error_stacktrace")
fun export_nuget_error_stacktrace(handle: COpaquePointer): String =
    handle.asStableRef<NugetError>().get().stackTrace
```

Four new exports:

```kotlin
@CName("nuget_error_cause_count")
fun export_nuget_error_cause_count(handle: COpaquePointer): Int {
    var e: NugetError? = handle.asStableRef<NugetError>().get()
    var n = 0
    while (e != null) { n++; e = e.cause }
    return n
}

@CName("nuget_error_cause_type")
fun export_nuget_error_cause_type(handle: COpaquePointer, index: Int): String =
    handle.asStableRef<NugetError>().get().at(index).type

@CName("nuget_error_cause_message")
fun export_nuget_error_cause_message(handle: COpaquePointer, index: Int): String =
    handle.asStableRef<NugetError>().get().at(index).message

@CName("nuget_error_cause_stacktrace")
fun export_nuget_error_cause_stacktrace(handle: COpaquePointer, index: Int): String =
    handle.asStableRef<NugetError>().get().at(index).stackTrace
```

The `CName` symbol names and parameter types of the three pre-existing exports are **unchanged** from the C ABI perspective.

### C# side: updated `NugetErrorNative` helper

```csharp
internal static class NugetErrorNative
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_error_type")]
    private static extern IntPtr Native_type(IntPtr handle);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_error_message")]
    private static extern IntPtr Native_message(IntPtr handle);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_error_stacktrace")]
    private static extern IntPtr Native_stacktrace(IntPtr handle);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_error_cause_count")]
    private static extern int Native_causeCount(IntPtr handle);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_error_cause_type")]
    private static extern IntPtr Native_causeType(IntPtr handle, int index);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_error_cause_message")]
    private static extern IntPtr Native_causeMessage(IntPtr handle, int index);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_error_cause_stacktrace")]
    private static extern IntPtr Native_causeStackTrace(IntPtr handle, int index);

    internal static string Type(IntPtr handle) => Marshal.PtrToStringUTF8(Native_type(handle))!;
    internal static string Message(IntPtr handle) => Marshal.PtrToStringUTF8(Native_message(handle))!;
    internal static string StackTrace(IntPtr handle) => Marshal.PtrToStringUTF8(Native_stacktrace(handle))!;
    internal static int CauseCount(IntPtr handle) => Native_causeCount(handle);
    internal static string CauseType(IntPtr handle, int index) => Marshal.PtrToStringUTF8(Native_causeType(handle, index))!;
    internal static string CauseMessage(IntPtr handle, int index) => Marshal.PtrToStringUTF8(Native_causeMessage(handle, index))!;
    internal static string CauseStackTrace(IntPtr handle, int index) => Marshal.PtrToStringUTF8(Native_causeStackTrace(handle, index))!;
}
```

### C# side: updated `KotlinException` class

```csharp
public class KotlinException : Exception
{
    public string KotlinType { get; }
    public string KotlinStackTrace { get; }

    public KotlinException(string kotlinType, string message, string kotlinStackTrace,
        Exception? innerException = null)
        : base(message, innerException)
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

The `base.ToString()` call (from `System.Exception`) already renders `InnerException` in the standard
`---> InnerException ... --- End of inner exception ---` format before reaching the Kotlin trace section.
The two blocks are therefore correctly ordered: .NET inner exception chain first, Kotlin trace second.

### C# side: updated error extraction helper

A shared `BuildKotlinException(IntPtr errorPtr)` helper is generated in `NugetErrorNative` (or as a static method in `KotlinException`), called from all error extraction sites:

```csharp
internal static KotlinException BuildException(IntPtr errorPtr)
{
    int causeCount = NugetErrorNative.CauseCount(errorPtr);

    // Build from deepest cause inward
    KotlinException? inner = null;
    for (int i = causeCount - 1; i >= 1; i--)
    {
        string causeType = NugetErrorNative.CauseType(errorPtr, i);
        string causeMsg = NugetErrorNative.CauseMessage(errorPtr, i);
        string causeStack = NugetErrorNative.CauseStackTrace(errorPtr, i);
        inner = new KotlinException(causeType, causeMsg, causeStack, inner);
    }

    string kotlinType = NugetErrorNative.Type(errorPtr);
    string msg = NugetErrorNative.Message(errorPtr);
    string stackTrace = NugetErrorNative.StackTrace(errorPtr);
    NugetMarshal.Dispose(errorPtr);
    return new KotlinException(kotlinType, msg, stackTrace, inner);
}
```

All existing error extraction sites in `CirRenderer.kt` and `CirFunctionTranslator.kt` change from:

```csharp
// Before
string kotlinType = NugetErrorNative.Type(errorPtr);
string msg = NugetErrorNative.Message(errorPtr);
string stackTrace = NugetErrorNative.StackTrace(errorPtr);
NugetMarshal.Dispose(errorPtr);
t.SetException(new KotlinException(kotlinType, msg, stackTrace));
```

to:

```csharp
// After
t.SetException(NugetErrorNative.BuildException(errorPtr));
```

(sync paths: `throw NugetErrorNative.BuildException(error);`)

### Consumer experience

```csharp
try
{
    string result = await service.PerformAsync();
}
catch (KotlinException ex)
{
    // Top-level exception
    logger.LogError("[{KotlinType}] {Message}", ex.KotlinType, ex.Message);

    // Cause chain (identical to standard .NET InnerException pattern)
    if (ex.InnerException is KotlinException cause)
    {
        logger.LogError("Caused by [{KotlinType}] {Message}", cause.KotlinType, cause.Message);
    }

    // ex.ToString() renders the full chain:
    // SampleLibrary.KotlinException: outer message
    //  ---> SampleLibrary.KotlinException: inner cause message
    //   --- End of inner exception stack trace ---
    //  ---> Kotlin stack trace:
    // kotlin.IllegalStateException: outer message
    //   at ...
    //  --- End of Kotlin stack trace ---
}
```

## Consequences

### Breaking changes

- `KotlinException` constructor changes from `(string kotlinType, string message, string kotlinStackTrace)` to `(string kotlinType, string message, string kotlinStackTrace, Exception? innerException = null)`. The new parameter is optional and defaults to `null`, so existing call sites that construct `KotlinException` in tests (not generated code) compile without change.
- The Kotlin `StableRef` payload changes from `Triple<String,String,String>` to a recursive `NugetError` data class. The three existing Kotlin `nuget_error_*` export functions change their internal type parameter. The C symbol names and signatures are unchanged — this is not a C ABI break.
- All `catch (e: Throwable)` blocks change from inline `Triple(...)` to `buildError(e)`.

### New infrastructure

**Kotlin side:**
- `NugetError` data class + `buildError(Throwable): NugetError` shared helper (and the `NugetError.at(index)` accessor) in `GenericClassExports.kt` (or `Helpers.kt`).
- Four new `@CName` exports: `nuget_error_cause_count`, `nuget_error_cause_type`, `nuget_error_cause_message`, `nuget_error_cause_stacktrace`.
- Updated `addNugetErrorHelperExports()` in `GenericClassExports.kt`.

**C# side:**
- `NugetErrorNative.CauseCount/CauseType/CauseMessage/CauseStackTrace` P/Invoke methods.
- `NugetErrorNative.BuildException(IntPtr)` helper method.
- `KotlinException(... , Exception? innerException = null)` constructor overload.
- `renderErrorHelper` in `CirRenderer.kt` updated to emit new P/Invokes, `BuildException`, and the updated constructor.
- All `renderAsyncMethod`, `renderSyncErrorCheckMethod`, and flow error sites in `CirRenderer.kt` updated to call `BuildException`.
- All error sites in `CirFunctionTranslator.kt` updated to call `BuildException`.

### Affected export builders (Kotlin)

All files that contain `StableRef.create(Triple(...))` in `catch` blocks:
- `FunctionExports.kt`
- `ClassExports.kt`
- `SuspendFunctionExports.kt`
- `GenericClassExports.kt` (also adds new exports, the `NugetError` data class, and `buildError`)

### Affected CIR / renderer (C#)

- `CirRenderer.kt` — `renderErrorHelper`: new P/Invoke declarations, `BuildException` helper method, updated `KotlinException` constructor.
- `CirRenderer.kt` — `renderAsyncMethod`, `renderSyncErrorCheckMethod`, `renderFlowHelper` (KotlinFlowEnumerator `onError` lambda): replace multi-line error extraction with `BuildException(errorPtr)` call.
- `CirFunctionTranslator.kt` — all inline error extraction blocks: same replacement.

### `ToString()` interaction with inner exceptions

`base.ToString()` in `KotlinException.ToString()` calls `System.Exception.ToString()`, which recursively renders `InnerException` using .NET's own `---> ... --- End of inner exception stack trace ---` format. The Kotlin trace section appended after that is for the **top-level** exception's Kotlin trace. Inner `KotlinException` objects will have their own `KotlinStackTrace` property accessible directly but the auto-rendered block from `base.ToString()` will include the inner exception's `message` (not its Kotlin trace) — consistent with .NET convention where `Exception.ToString()` shows inner exception messages, not their full sub-traces.

## Scope

**v1 (this ADR):**
- Full cause chain up to Kotlin/Native's cycle-guarded depth (bounded by `seen` set).
- Both sync and async error paths.
- Flow `onError` callback.
- Suspend lambda error paths.
- All exported function types (top-level, class methods, object methods, generic variants).
- `InnerException` is always `KotlinException` (never a C# exception type) — the cause is always a Kotlin-originating exception.

**Deferred (not in this ADR):**
- `@Throws`-based opt-in to skip cause walking on specific functions — not needed given wrap-all approach.
- Mapping cause exceptions to non-`KotlinException` C# types (e.g., wrapping `IOException` cause as `System.IO.IOException`) — rejected per ADR-023 rationale.
- Constructor exceptions — deferred by ADR-024; still deferred.
- Release-build trace quality — library author's concern.

## Proposed C# tests and Kotlin sample source

### Kotlin sample (add to `sample-library`)

```kotlin
// In sample-library, e.g. ExceptionCause.kt
package io.github.xxfast.nuget.sample.cat

class ValidationException(message: String, cause: Throwable? = null)
    : Exception(message, cause)

fun throwWithCause(): Unit {
    val root = IllegalArgumentException("root cause message")
    throw ValidationException("outer error message", root)
}

suspend fun throwWithCauseAsync(): Unit {
    throw ValidationException("async outer error", IllegalStateException("async root cause"))
}

fun throwWithDeepChain(): Unit {
    val root = RuntimeException("deep root")
    val mid = IllegalArgumentException("middle cause", root)
    throw ValidationException("outer error", mid)
}
```

### xUnit tests (add to `sample-app/SampleApp.Tests/ExceptionCauseTests.cs`)

```csharp
using SampleLibrary;
using SampleLibrary.Cat;

namespace SampleApp.Tests;

public class ExceptionCauseTests
{
    [Fact]
    public void SyncThrowWithCause_InnerException_IsNotNull()
    {
        var ex = Assert.Throws<KotlinException>(() => ExceptionCause.ThrowWithCause());
        Assert.NotNull(ex.InnerException);
    }

    [Fact]
    public void SyncThrowWithCause_InnerException_IsKotlinException()
    {
        var ex = Assert.Throws<KotlinException>(() => ExceptionCause.ThrowWithCause());
        Assert.IsType<KotlinException>(ex.InnerException);
    }

    [Fact]
    public void SyncThrowWithCause_OuterMessage_IsCorrect()
    {
        var ex = Assert.Throws<KotlinException>(() => ExceptionCause.ThrowWithCause());
        Assert.Equal("outer error message", ex.Message);
    }

    [Fact]
    public void SyncThrowWithCause_InnerMessage_IsCorrect()
    {
        var ex = Assert.Throws<KotlinException>(() => ExceptionCause.ThrowWithCause());
        var inner = (KotlinException)ex.InnerException!;
        Assert.Equal("root cause message", inner.Message);
    }

    [Fact]
    public void SyncThrowWithCause_InnerKotlinType_IsCorrect()
    {
        var ex = Assert.Throws<KotlinException>(() => ExceptionCause.ThrowWithCause());
        var inner = (KotlinException)ex.InnerException!;
        Assert.Contains("IllegalArgumentException", inner.KotlinType);
    }

    [Fact]
    public void SyncThrowWithCause_InnerException_HasNoFurtherCause()
    {
        var ex = Assert.Throws<KotlinException>(() => ExceptionCause.ThrowWithCause());
        Assert.Null(ex.InnerException!.InnerException);
    }

    [Fact]
    public async Task AsyncThrowWithCause_InnerException_IsNotNull()
    {
        var ex = await Assert.ThrowsAsync<KotlinException>(
            () => ExceptionCause.ThrowWithCauseAsyncAsync());
        Assert.NotNull(ex.InnerException);
    }

    [Fact]
    public async Task AsyncThrowWithCause_InnerMessage_IsCorrect()
    {
        var ex = await Assert.ThrowsAsync<KotlinException>(
            () => ExceptionCause.ThrowWithCauseAsyncAsync());
        var inner = (KotlinException)ex.InnerException!;
        Assert.Equal("async root cause", inner.Message);
    }

    [Fact]
    public void DeepChain_ThrowsKotlinException_WithTwoLevelsOfInnerException()
    {
        var ex = Assert.Throws<KotlinException>(() => ExceptionCause.ThrowWithDeepChain());
        var mid = ex.InnerException as KotlinException;
        Assert.NotNull(mid);
        var root = mid!.InnerException as KotlinException;
        Assert.NotNull(root);
        Assert.Equal("deep root", root!.Message);
        Assert.Null(root.InnerException);
    }

    [Fact]
    public void ThrowWithCause_ToString_ContainsInnerExceptionBlock()
    {
        var ex = Assert.Throws<KotlinException>(() => ExceptionCause.ThrowWithCause());
        // .NET renders inner exception with "--->" marker
        Assert.Contains("--->", ex.ToString());
    }

    [Fact]
    public void ThrowWithNoCause_InnerException_IsNull()
    {
        // Existing single-level exception must still have null InnerException
        var ex = Assert.Throws<KotlinException>(
            () => SyncExceptions.FeedCatTreat("Oreo"));
        Assert.Null(ex.InnerException);
    }
}
```
