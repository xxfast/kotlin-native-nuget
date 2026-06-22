# ADR-022: CancellationToken support — per-call coroutine cancellation

## Status

Proposed

## Context

ADR-019 introduced suspend function mapping via `Task<T>` and deferred per-call cancellation:

> *"Mapping CancellationToken to Job.cancel() requires passing a cancel function pointer back to C# and wiring it to the CancellationToken.Register callback."*

ADR-021 introduced scope-per-class structured concurrency and deferred this again:

> *"CancellationToken parameter on async methods — requires per-call Job handle and cancel export; warrants its own ADR."*

The current cancellation model only allows coarse-grained cancellation: `Dispose()` cancels the entire class scope and all in-flight coroutines together. A C# developer calling `FetchGreetingAsync("Alice", cancellationToken)` expects to cancel that individual call independently — without affecting other calls or disposing the object.

### What "per-call cancellation" means

For class methods with a scope (ADR-021):
- Each `FetchAsync()` call launches a child coroutine under the class's `SupervisorJob` scope.
- Cancelling one call's `CancellationToken` should cancel that child `Job` only.
- Other in-flight calls and the class scope itself must be unaffected.

For top-level suspend functions (no class, no scope):
- Each call launches `CoroutineScope(Dispatchers.Default).launch { ... }` independently.
- The job returned by `launch` is the only handle for that call.

### How other Kotlin interop targets handle per-call cancellation

#### Java (JVM)

Java callers pass a `CoroutineScope` in from outside (e.g., `viewModelScope`). They cancel the scope to cancel coroutines. There is no direct Java equivalent of `CancellationToken` — Java relies on scope cancellation or `Future.cancel(true)`. There is no standard pattern for cancelling a single in-flight suspend call from Java without cancelling the scope.

#### ObjC/Swift Export (built-in)

No per-call cancellation. The ObjC completion handler pattern has no cancel path — once the completion handler method is called, the coroutine runs to completion (or the whole scope is torn down).

#### SKIE (Touchlab — third-party for Swift)

SKIE implements genuine two-way cancellation. The mechanism (from `Skie_CancellationHandler.kt` and `Skie_SuspendHandler.kt` in the SKIE runtime):

1. Kotlin launches a coroutine and **returns the `Job` handle back to Swift via a cancel callback**:
   ```kotlin
   // Simplified SKIE pattern:
   val scope = CoroutineScope(dispatcher)
   scope.launch {
       cancellationHandler.setCancellationCallback { cancel() }
       // run suspend function...
   }
   ```
2. The `Skie_CancellationHandler` is an object that Swift holds. When Swift's `Task` is cancelled, Swift calls `handler.cancel()`, which invokes the registered Kotlin lambda, which calls `cancel()` on the coroutine's job.
3. Thread safety is managed with an `NSLock` — the state machine handles the race between "cancellation arrives before the callback is registered" (stores `WillBeCanceled` state and fires immediately on registration) and "callback registered before cancellation" (invoked directly on cancellation).
4. This is a **Kotlin→Swift object reference** as the bridge — possible because Kotlin/Native's ObjC export makes Kotlin objects available as ObjC objects to Swift. This specific pattern is not replicable at a C boundary where only function pointers and `IntPtr` are available.

The key insight from SKIE: the cancel path is a **function pointer** registered back to the caller. The caller stores it and invokes it when it wants to cancel. At the C boundary, we model this the same way — but with a raw C function pointer rather than a Kotlin object.

#### Swift Export (Alpha)

Maps `suspend fun` to Swift `async func`. Swift's structured concurrency task tree propagates cancellation automatically when the enclosing `Task` is cancelled (via `withTaskCancellationHandler`). No explicit cancel function pointer — Swift's runtime handles it. This is not achievable through a C boundary.

#### Kotlin/JS

No per-call cancellation at the JS export level. Coroutines exposed as `Promise` have no cancel path from JS (Promises are not cancellable in JS; only `AbortController` patterns exist, which are not automatically wired).

### What C# developers expect

The standard .NET pattern for cancelable async methods:

```csharp
// Every async method in .NET that can be cancelled takes a CancellationToken:
public Task<string> GetAsync(string url, CancellationToken cancellationToken = default)

// Callers create a CancellationTokenSource to get cancellation control:
var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5)); // timeout
string result = await service.FetchAsync(cts.Token);
```

`CancellationToken.Register(callback)` is the standard mechanism for bridging a `CancellationToken` to an external cancel API that doesn't understand `CancellationToken`:

```csharp
token.Register(() => externalSystem.CancelOperation(operationId));
```

The `Register` call returns a `CancellationTokenRegistration` that must be disposed when the operation completes normally (to prevent the callback from firing after success). This is the established .NET idiom used by `HttpClient`, `SqlCommand`, `Process.WaitForExitAsync`, and many others.

`TaskCompletionSource.TrySetCanceled(token)` (with the `CancellationToken` overload) stores the token in the `TaskCanceledException`, so the caller can distinguish scope-cancel (`Dispose()`) from per-call cancel (`CancellationToken`) if they need to.

### CancellationToken.None and optional parameters

The idiomatic C# pattern is to make `CancellationToken` optional with a default of `CancellationToken.None`:

```csharp
public Task<string> FetchAsync(CancellationToken cancellationToken = default)
```

`CancellationToken.None` is a token that can never be cancelled. `default` is identical to `CancellationToken.None` for value types. This means:
- Old call sites that don't pass a token (`FetchAsync()`) continue to work.
- New callers can pass a token: `FetchAsync(cts.Token)`.

This is additive and non-breaking for callers upgrading from the current API.

### The bridge constraint for per-call cancellation

Only raw function pointers and `IntPtr` cross the C boundary. The cancel path must be:

1. **Kotlin side**: `launch { ... }` returns a `Job` handle. A new exported function `nuget_job_cancel(jobHandle)` cancels a specific `Job` by its `StableRef` handle.
2. **C# side**: After calling the native async export, C# registers a `CancellationToken` callback that calls `nuget_job_cancel` with the job handle.
3. The job handle is returned from the Kotlin side — but the current callback ABI `(resultPtr, errorPtr, isCancelled, userData)` cannot carry it, because the job handle must be available **before** the coroutine completes.

This requires a new mechanism to return the `Job` handle to C# synchronously when the async export is called, so C# can register the cancel callback immediately.

### How the Job handle crosses the boundary

Two options exist for returning the job handle to C#:

**Option A — Return value from the async export**: Change the Kotlin export from `void` to `COpaquePointer` (the `Job` `StableRef`). The C# P/Invoke declaration changes from `void` to `IntPtr`. C# stores the returned pointer and uses it for the cancel callback.

```kotlin
@CName("catnapservice_longNap_async")
fun export(...): COpaquePointer {
    val job = scope.launch(start = ATOMIC) { ... }
    return StableRef.create(job).asCPointer()  // return job handle
}
```

```csharp
[DllImport(...)]
private static extern IntPtr Native_LongNapAsync(IntPtr handle, IntPtr scopeHandle, ...);
// returns the job handle
```

**Option B — Extra output parameter**: Add a `jobHandleOut: CPointer<COpaquePointerVar>` parameter. Kotlin writes the handle into this pointer before launching. C# passes a pinned pointer to receive it.

Option A is simpler: the C export already returns a single value, C# reads it from the return value without extra pinned memory. Option B is more complex and error-prone. **Option A is chosen.**

### Top-level suspend functions

Top-level suspend functions (e.g., `fetchGreeting(name)`) use `CoroutineScope(Dispatchers.Default).launch`. The `launch` call also returns a `Job`. The same mechanism applies: return the `Job` `StableRef` from the export, and C# registers the cancel callback the same way.

The difference: for top-level functions there is no class scope. The job is the only cancellable unit. Cancellation via `CancellationToken` is the only cancellation mechanism available (there is no `Dispose()` path). The `CancellationToken` parameter fills the gap ADR-021 noted: *"Top-level suspend functions have no associated class instance and no natural lifecycle owner."*

### Shared job cancel export

A single new shared Kotlin export handles all per-call cancellations:

```kotlin
@CName("nuget_job_cancel")
fun export_nuget_job_cancel(handle: COpaquePointer?) {
    if (handle == null) return
    handle.asStableRef<Job>().get().cancel()
}

@CName("nuget_job_dispose")
fun export_nuget_job_dispose(handle: COpaquePointer?) {
    if (handle == null) return
    handle.asStableRef<Job>().dispose()
}
```

`nuget_job_cancel` is safe to call from the `CancellationToken` callback thread (the .NET thread pool thread that fires registered callbacks). `nuget_job_dispose` is called from the completion callback (when the coroutine finishes) to release the `StableRef`.

### When the coroutine completes normally

If the coroutine finishes before the `CancellationToken` fires, the cancel registration must be disposed to prevent `nuget_job_cancel` from being invoked after the `Job` `StableRef` has been disposed.

The standard C# pattern: store the `CancellationTokenRegistration` in the callback closure; the completion callback calls `.Dispose()` on it.

```csharp
CancellationTokenRegistration reg = cancellationToken.Register(
    () => NugetJobNative.Cancel(jobHandle));

NugetAsyncCallback callback = null!;
callback = (resultPtr, errorPtr, isCancelled, userData) =>
{
    reg.Dispose();              // unregister cancel callback first
    NugetJobNative.Dispose(jobHandle);  // release job StableRef
    callbackHandle.Free();
    // ... set result/exception/cancel on tcs
};
```

### Race condition: cancellation fires before registration

If the `CancellationToken` is already cancelled when `Register` is called, the callback fires **immediately and synchronously** on the calling thread (documented .NET behaviour). In that case:
- `nuget_job_cancel` is called before the `CancellationToken.Register` returns.
- The job has already been launched (with `CoroutineStart.ATOMIC`), so the cancel reaches the coroutine.
- The callback will fire with `isCancelled = 1`, and `reg.Dispose()` is a no-op on an already-fired registration.

No special handling is needed — the existing `CoroutineStart.ATOMIC` guarantee (the coroutine always reaches its `CancellationException` handler before yielding to cancellation) covers this race.

### ABI change

The Kotlin async exports change return type from `Unit` (void) to `COpaquePointer` (the job handle). This is a breaking ABI change to all async exports. Since the project is pre-1.0 and all existing exports are updated together, this is acceptable.

## Alternatives Considered

### 1. Return the Job StableRef from the async export (chosen)

The Kotlin export returns `COpaquePointer` (the `StableRef<Job>`). C# stores it after calling the native export and uses it in the `CancellationToken.Register` callback.

**Pros:**
- Simple ABI: one return value, no extra pointers or pinned memory.
- C# reads the job handle synchronously before returning the `Task` to the caller.
- Job lifecycle is clear: created by Kotlin, cancel/dispose by C#.
- Matches the pattern used by ADR-021 for scope handles (both are `StableRef` opaque pointers managed by C#).

**Cons:**
- Breaking ABI change: all existing async exports change from `void` to `IntPtr` on C# side.
- C# must dispose the job handle even when no `CancellationToken` is used (`CancellationToken.None`) — but `nuget_job_dispose` is called in the completion callback anyway, so this is always correct.

### 2. Out-pointer parameter for job handle

Add a `jobHandleOut: CPointer<COpaquePointerVar>` parameter to the Kotlin export. Kotlin writes the handle before launching. C# passes a pinned `IntPtr*` and reads it after the call.

**Pros:**
- Does not change the return type (exports remain `void`).

**Cons:**
- Requires pinned memory on the C# side (`fixed` or `GCHandle` on an `IntPtr` array).
- More complex generated C# code.
- No real advantage over option 1 — the return value approach is cleaner and used by many existing exports (constructors, property getters).

### 3. Pre-allocated "cancel token" handle returned from a separate call

Before calling the async export, C# calls `nuget_job_token_create()` to get a job token handle. The async export accepts the token handle and wires the job to it. C# uses `nuget_job_token_cancel(handle)` and `nuget_job_token_dispose(handle)`.

**Pros:**
- Avoids returning a value from the async export.

**Cons:**
- Two C calls per async invocation instead of one.
- More infrastructure (new exports, new handle type).
- No practical advantage — the job StableRef returned from option 1 already serves as the cancel token.

### 4. No CancellationToken support (status quo)

Leave per-call cancellation unimplemented. C# callers can only cancel via `Dispose()` (all calls) or by not passing a token.

**Cons:**
- Missing a fundamental .NET async pattern — `async` methods without `CancellationToken` are considered incomplete APIs in .NET.
- No way for callers to implement timeouts on individual calls.
- No way for ASP.NET Core request cancellation to propagate to Kotlin.

## Decision

Use **option 1: return the Job `StableRef` from the async export**.

### New shared exports

```kotlin
@CName("nuget_job_cancel")
fun export_nuget_job_cancel(handle: COpaquePointer?) {
    if (handle == null) return
    handle.asStableRef<Job>().get().cancel()
}

@CName("nuget_job_dispose")
fun export_nuget_job_dispose(handle: COpaquePointer?) {
    if (handle == null) return
    handle.asStableRef<Job>().dispose()
}
```

### Modified suspend method export (class method)

```kotlin
@CName("catnapservice_longNap_async")
fun export_catnapservice_longNap_async(
    handle: COpaquePointer,
    scopeHandle: COpaquePointer,
    callbackPtr: COpaquePointer,
    userData: COpaquePointer,
): COpaquePointer {                      // NEW: returns Job StableRef
    val obj = handle.asStableRef<CatNapService>().get()
    val scope = scopeHandle.asStableRef<CoroutineScope>().get()
    val fn = callbackPtr.reinterpret<CFunction<
        (COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()

    val job = scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            val result = obj.longNap()
            val resultRef = StableRef.create(result).asCPointer()
            fn.invoke(resultRef, null, 0.toByte(), userData)
        } catch (e: CancellationException) {
            fn.invoke(null, null, 1.toByte(), userData)
            throw e
        } catch (e: Throwable) {
            val errRef = StableRef.create(e.message ?: "Kotlin error").asCPointer()
            fn.invoke(null, errRef, 0.toByte(), userData)
        }
    }
    return StableRef.create(job).asCPointer()  // NEW: return job handle
}
```

### Modified suspend function export (top-level)

```kotlin
@CName("fetchGreeting_async")
fun export_fetchGreeting_async(
    name: String,
    callbackPtr: COpaquePointer,
    userData: COpaquePointer,
): COpaquePointer {                      // NEW: returns Job StableRef
    val fn = callbackPtr.reinterpret<CFunction<
        (COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()

    val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.ATOMIC) {
        try {
            val result = fetchGreeting(name)
            val resultRef = StableRef.create(result).asCPointer()
            fn.invoke(resultRef, null, 0.toByte(), userData)
        } catch (e: CancellationException) {
            fn.invoke(null, null, 1.toByte(), userData)
            throw e
        } catch (e: Throwable) {
            val errRef = StableRef.create(e.message ?: "Kotlin error").asCPointer()
            fn.invoke(null, errRef, 0.toByte(), userData)
        }
    }
    return StableRef.create(job).asCPointer()  // NEW: return job handle
}
```

Note: top-level suspend functions now also catch `CancellationException` explicitly (previously they only caught `Throwable`, which would have accidentally swallowed cancellation without setting `isCancelled = 1`).

### NugetJobNative helper class (generated once)

```csharp
internal static class NugetJobNative
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_job_cancel")]
    internal static extern void Cancel(IntPtr handle);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_job_dispose")]
    internal static extern void Dispose(IntPtr handle);
}
```

### Generated C# async method (class method)

```csharp
[DllImport("sample", CallingConvention = CallingConvention.Cdecl,
    EntryPoint = "catnapservice_longNap_async")]
private static extern IntPtr Native_LongNapAsync(    // returns IntPtr (job handle)
    IntPtr handle, IntPtr scopeHandle,
    NugetAsyncCallback callback, IntPtr userData);

public Task<string> LongNapAsync(CancellationToken cancellationToken = default)
{
    if (_handle == IntPtr.Zero)
        throw new ObjectDisposedException(nameof(CatNapService));

    var tcs = new TaskCompletionSource<string>(
        TaskCreationOptions.RunContinuationsAsynchronously);
    GCHandle tcsHandle = GCHandle.Alloc(tcs);
    NugetAsyncCallback callback = null!;
    GCHandle callbackHandle = default;
    CancellationTokenRegistration reg = default;

    callback = (resultPtr, errorPtr, isCancelled, userData) =>
    {
        reg.Dispose();                              // unregister cancel hook
        NugetJobNative.Dispose(jobHandle);          // release job StableRef
        callbackHandle.Free();
        var t = (TaskCompletionSource<string>)
            GCHandle.FromIntPtr(userData).Target!;
        GCHandle.FromIntPtr(userData).Free();
        if (isCancelled != 0)
        {
            t.TrySetCanceled(cancellationToken);    // carry the token for diagnostics
        }
        else if (errorPtr != IntPtr.Zero)
        {
            string msg = NugetMarshal.FromHandle<string>(errorPtr);
            t.SetException(new KotlinException(msg));
        }
        else
        {
            t.SetResult(NugetMarshal.FromHandle<string>(resultPtr));
        }
    };
    callbackHandle = GCHandle.Alloc(callback);

    IntPtr jobHandle = Native_LongNapAsync(
        _handle, GetOrCreateScope(), callback, GCHandle.ToIntPtr(tcsHandle));

    // Register cancellation only if the token can be cancelled
    if (cancellationToken.CanBeCanceled)
        reg = cancellationToken.Register(() => NugetJobNative.Cancel(jobHandle));

    return tcs.Task;
}
```

Key points:
- `jobHandle` is captured by the callback closure — the closure executes after the job handle is known, so the closure can dispose it.
- `reg` is assigned after the native call, before the callback can fire (`CoroutineStart.ATOMIC` ensures the coroutine does not complete before the first suspension point, and `Register` is called synchronously on the same thread before returning `tcs.Task`).
- `TrySetCanceled(cancellationToken)` carries the caller's token, so `TaskCanceledException.CancellationToken` matches what the caller passed.
- `cancellationToken.CanBeCanceled` guard skips registration for `CancellationToken.None` (the common case), avoiding a no-op delegate allocation.

### Generated C# async method (top-level function)

```csharp
[DllImport("sample", CallingConvention = CallingConvention.Cdecl,
    EntryPoint = "fetchGreeting_async")]
private static extern IntPtr FetchGreetingAsync_native(
    string name, NugetAsyncCallback callback, IntPtr userData);

public static Task<string> FetchGreetingAsync(
    string name, CancellationToken cancellationToken = default)
{
    var tcs = new TaskCompletionSource<string>(
        TaskCreationOptions.RunContinuationsAsynchronously);
    GCHandle tcsHandle = GCHandle.Alloc(tcs);
    NugetAsyncCallback callback = null!;
    GCHandle callbackHandle = default;
    CancellationTokenRegistration reg = default;

    callback = (resultPtr, errorPtr, isCancelled, userData) =>
    {
        reg.Dispose();
        NugetJobNative.Dispose(jobHandle);
        callbackHandle.Free();
        var t = (TaskCompletionSource<string>)
            GCHandle.FromIntPtr(userData).Target!;
        GCHandle.FromIntPtr(userData).Free();
        if (isCancelled != 0)
            t.TrySetCanceled(cancellationToken);
        else if (errorPtr != IntPtr.Zero)
            t.SetException(new KotlinException(NugetMarshal.FromHandle<string>(errorPtr)));
        else
            t.SetResult(NugetMarshal.FromHandle<string>(resultPtr));
    };
    callbackHandle = GCHandle.Alloc(callback);

    IntPtr jobHandle = FetchGreetingAsync_native(
        name, callback, GCHandle.ToIntPtr(tcsHandle));

    if (cancellationToken.CanBeCanceled)
        reg = cancellationToken.Register(() => NugetJobNative.Cancel(jobHandle));

    return tcs.Task;
}
```

### Consumer API

```csharp
// Class method — optional CancellationToken
var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
string result = await service.FetchAsync(cts.Token);

// Top-level function — same pattern
string greeting = await AsyncFunctions.FetchGreetingAsync("Alice", cts.Token);

// No token (default) — works as before, no overhead
string result2 = await service.FetchAsync();

// ASP.NET Core: pass the request's HttpContext.RequestAborted token
string result3 = await service.FetchAsync(httpContext.RequestAborted);
```

### Interaction with Dispose() cancellation (ADR-021)

When `Dispose()` is called on the class:
1. `NugetScopeNative.Cancel(_scopeHandle)` cancels the class scope, which cancels all child jobs.
2. In-flight coroutines fire their callbacks with `isCancelled = 1`.
3. Each callback calls `reg.Dispose()` and `NugetJobNative.Dispose(jobHandle)`.
4. `TrySetCanceled(cancellationToken)` is called — but the `CancellationToken` here is whatever the caller originally passed. If they passed `CancellationToken.None`, the task is still cancelled (just without the token stored in the exception).
5. If the caller had registered a cancel token that fires concurrently with Dispose(), `nuget_job_cancel` may be called after the coroutine is already cancelled — this is safe because `Job.cancel()` on an already-cancelled job is a no-op.

The two cancel paths commute safely.

### CancellationToken.Register callback thread safety

`CancellationToken.Register` callbacks fire on the .NET thread pool thread that calls `Cancel()`. The `NugetJobNative.Cancel(jobHandle)` call crosses into Kotlin's runtime on that thread. This is safe in Kotlin/Native's new memory model — inter-thread calls into the Kotlin runtime are allowed.

The `CancellationTokenRegistration.Dispose()` call (from the completion callback) is thread-safe relative to the cancel callback. If cancel fires concurrently with normal completion:
- `reg.Dispose()` is called from the completion callback: this deregisters the callback. If the cancel callback is already running, `Dispose()` blocks until it completes.
- `nuget_job_cancel` calls `job.cancel()` on an already-completed job — a no-op.
- `tcs.TrySetCanceled` is `Try*` and races safely with `tcs.SetResult` — whichever fires first wins.

## Consequences

### ABI changes

- All async exports change return type: Kotlin `Unit` → `COpaquePointer`, C# `void` → `IntPtr`.
- All generated `[DllImport]` declarations for async exports must be updated.
- Top-level suspend function exports now catch `CancellationException` explicitly (matches class method behaviour established in ADR-021).

### New infrastructure

- `nuget_job_cancel` and `nuget_job_dispose` shared exports in `GenericClassExports.kt`.
- `CirJobNativeHelper` CIR declaration → `NugetJobNative` class in `CirRenderer`.
- `buildSuspendFunctionBody` updated: returns `COpaquePointer`, adds `CancellationException` handler (matches class method pattern).
- `buildSuspendMethodBody` updated: returns `COpaquePointer`.
- `renderAsyncMethod` updated: reads `IntPtr jobHandle` from return value, adds `CancellationToken cancellationToken = default` parameter, adds `CancellationTokenRegistration reg` lifecycle, updates `[DllImport]` return type.
- `renderAsyncFunction` (top-level) updated: same changes as `renderAsyncMethod`.

### New C# using directives

`System.Threading.CancellationToken` is already in `System.Threading`, which is imported via `System.Threading.Tasks`. No new `using` required.

### Scope

**v1 (this ADR):**
- `CancellationToken cancellationToken = default` parameter on all generated async methods.
- Works for class methods (with scope) and top-level suspend functions.
- Correct `TrySetCanceled(cancellationToken)` for proper exception diagnostics.
- `nuget_job_cancel` / `nuget_job_dispose` shared exports.

**Deferred:**
- `KotlinSuspendFunc<T>.InvokeAsync(CancellationToken)` — suspend lambdas currently use a standalone scope with no per-call Job returned. Requires the same return-value ABI change to `nuget_suspend_func{N}_invoke`.
- Timeout via `CancellationTokenSource(TimeSpan)` — works automatically once `CancellationToken` is supported (no bridge change needed; caller creates their own timeout CTS).
- `IAsyncDisposable` / graceful drain — requires tracking in-flight job count; warrants a separate ADR.
