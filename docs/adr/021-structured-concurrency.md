# ADR-021: Structured concurrency — scope-per-class with cancellation on dispose

## Status

Accepted

## Context

ADR-019 and ADR-020 introduced async support for suspend functions and suspend lambdas. Both use `CoroutineScope(Dispatchers.Default).launch { ... }` — a new, unstructured scope per call.

C# does not have structured concurrency as a language concept. Tasks are fire-and-forget by default, `CancellationToken` is cooperative and opt-in, and there is no parent-child scope hierarchy. The closest C# has is `IDisposable` as a convention for resource cleanup. This ADR brings Kotlin's structured concurrency guarantees to the bridge, surfaced through C#'s `IDisposable` contract — the C# developer never sees a `CoroutineScope`, they just get the behavior they already expect: `Dispose()` stops all background work.

The current fire-and-forget pattern creates three problems:

1. **No cancellation on dispose**: if C# calls `Dispose()` while a coroutine is in flight, the coroutine continues running as fire-and-forget. The `StableRef` is released, but the coroutine's closure still holds the object reference — the behavior of whatever the coroutine does with the object is unpredictable.

2. **GCHandle leak**: the `GCHandle` pinning the `TaskCompletionSource` and callback delegate is freed inside the callback. If the coroutine never completes (e.g., object disposed mid-flight), the callback may never fire and the `GCHandle`s leak.

3. **No backpressure**: a C# caller invoking async methods in a tight loop creates unbounded concurrent coroutines with no way to cancel them.

ADR-019 acknowledged this explicitly: *"If Kotlin's coroutine scope is cancelled before completing, the callback may never fire and the GCHandles will leak."*

### How other Kotlin interop targets handle structured concurrency

**Java (JVM)**: Kotlin libraries use `viewModelScope` / `lifecycleScope` — a `CoroutineScope` tied to the host object's lifecycle, cancelled in `onCleared()` or `onDestroy()`. Java callers rely on the scope owner to manage cancellation. There is no automatic scope creation — it's a pattern enforced by convention.

**ObjC/Swift Export (built-in)**: No scope management. Each `suspend fun` call creates an independent coroutine internally. The `dealloc` of the ObjC wrapper does not cancel pending coroutines. This is the same fire-and-forget pattern we have today.

**SKIE (Touchlab)**: Adds two-way cancellation — cancelling a Swift `Task` cancels the Kotlin coroutine via `withTaskCancellationHandler`. The mechanism uses a per-call child `Job` under a parent scope. SKIE does *not* tie the scope to the object lifetime — it ties it to the individual Swift Task.

**Swift Export (Alpha)**: Maps `suspend fun` directly to Swift `async func`. Swift's structured concurrency (`Task`, `TaskGroup`) naturally handles cancellation through the task tree. No explicit scope-per-object model.

### What's idiomatic in C#

C# structured concurrency relies on `CancellationToken` and `IDisposable`:

- `CancellationTokenSource` creates a token; calling `Cancel()` signals all tasks holding that token
- `IDisposable.Dispose()` is the standard cleanup hook — C# developers expect `Dispose()` to release *all* resources, including cancelling background work
- `ObjectDisposedException` is thrown when methods are called on a disposed object
- `IAsyncDisposable` with `DisposeAsync()` supports graceful drain (wait for in-flight work to finish)

The key C# expectation: **`Dispose()` should stop all background work associated with the object.** A C# `HttpClient` that kept sending requests after `Dispose()` would be considered broken.

### The bridge constraint

The KSP processor does not modify user source code. It generates `@CName` wrapper exports. This means the `CoroutineScope` cannot be a field on the user's Kotlin class — it must be managed externally by the generated bridge infrastructure.

### Scope ownership: bridge concern, not Kotlin idiom

In Kotlin, structured concurrency is **caller-driven**. A class with suspend methods does not own a `CoroutineScope` — the *caller* provides one:

```kotlin
myScope.launch { catService.fetch() }
```

Android's `viewModelScope` is an explicit opt-in, not implicit behavior. A Kotlin developer writing `class CatService { suspend fun fetch(): String }` does not expect the class to manage coroutine lifecycles.

In the bridge, the "caller" is C# — which has no concept of providing a Kotlin `CoroutineScope`. The bridge must own the scope on behalf of the C# consumer. This is invisible to both sides: the Kotlin author writes normal suspend functions, the C# consumer sees `Dispose()` doing the right thing.

Because this is a non-obvious behavior imposed by the bridge, Kotlin library authors must **opt in** via `@OptIn(ExperimentalNugetCoroutineApi::class)`. This:
- Makes the scope attachment explicit at the declaration site
- Surfaces the behavior in a way the developer must acknowledge
- Communicates that the mechanism may evolve (e.g., when `CancellationToken` support is added)
- Follows Kotlin's established `@RequiresOptIn` pattern (`@ExperimentalCoroutinesApi`, `@DelicateCoroutinesApi`)

Without the opt-in, the KSP processor emits a warning explaining the scope behavior. The suspend methods are still exported, but the developer is informed.

## Alternatives Considered

### 1. Scope handle as a separate C# field with dedicated exports (chosen)

The scope is created and managed as an independent `StableRef<CoroutineScope>` handle. C# classes with suspend methods hold two handles: `_handle` (the object) and `_scopeHandle` (the scope). Three new shared Kotlin exports manage scope lifecycle:

- `nuget_scope_create()` → creates a `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, returns a `StableRef` handle
- `nuget_scope_cancel(scopeHandle)` → cancels the scope
- `nuget_scope_dispose(scopeHandle)` → disposes the `StableRef`

Suspend method exports take `scopeHandle` as an additional parameter and use it to launch coroutines instead of creating a new scope.

**Pros:**
- No wrapper type — no `.target` unwrapping on property/method accessors. Existing export patterns for properties, methods, constructors are completely unchanged.
- No global mutable state — each scope is a `StableRef` managed by C#, same as all other bridge handles.
- Clean separation: object lifecycle and scope lifecycle are independent but coordinated by C#'s `Dispose()`.
- Only 3 new shared exports (like `nuget_dispose`) — not per-class.
- Suspend method exports only add one parameter (`scopeHandle`).

**Cons:**
- C# class layout changes: a second `IntPtr _scopeHandle` field (only for classes with suspend methods).
- Constructor and dispose logic become slightly more complex (create/cancel/dispose scope).

### 2. NugetScopedHandle wrapper in StableRef

Wrap the object in a `NugetScopedHandle<T>(val target: T, val scope: CoroutineScope)`. The `StableRef` holds the wrapper instead of the raw object. All generated exports for classes with suspend methods unwrap via `.target`.

**Pros:**
- Single handle (one `IntPtr` per class, not two).
- No shared exports — scope is embedded in the handle.

**Cons:**
- **Every property/method accessor for classes with suspend methods must change** to unwrap `.target`. This is a pervasive change to `ClassExports.kt`, `SuspendFunctionExports.kt`, and any other code that generates exports referencing the class handle.
- The `StableRef` type changes from `StableRef<T>` to `StableRef<NugetScopedHandle<T>>`, making the generated code harder to read and debug.
- Inheritance and sealed class hierarchies become complex — subclass exports must know about the wrapper.

### 3. Side map with global state

A per-file `MutableMap<Long, CoroutineScope>` maps handle pointer addresses to their scopes. Created on construction, cancelled+removed on dispose.

**Pros:**
- No change to existing export signatures — only suspend method exports and dispose change.
- No C# layout change.

**Cons:**
- **Global mutable state** — requires thread-safe access (`AtomicReference` with copy-on-write or synchronization).
- Kotlin/Native's `HashMap` is not thread-safe for concurrent writes; requires `kotlinx.atomicfu` dependency or manual locking.
- Implicit lifecycle — the scope is created/destroyed as a side effect, not as an explicit handle.
- Harder to test and debug.

### 4. Keep fire-and-forget (status quo)

Add only `ObjectDisposedException` guards to async methods — check `_handle != IntPtr.Zero` before calling. No scope management.

**Pros:**
- Minimal change.

**Cons:**
- **In-flight coroutines continue after dispose** — the core problem remains.
- `GCHandle` leak risk remains.
- Does not match C# expectations for `Dispose()`.

## Decision

Use **option 1: scope handle as a separate C# field with dedicated exports**.

### Opt-in annotation

```kotlin
@RequiresOptIn(
    message = "Classes with suspend functions exported via kotlin-native-nuget " +
        "will have a bridge-managed CoroutineScope attached. " +
        "The scope is cancelled when the C# consumer calls Dispose(), " +
        "cancelling all in-flight coroutines.",
    level = RequiresOptIn.Level.WARNING,
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ExperimentalNugetCoroutineApi
```

Kotlin library authors acknowledge the behavior at the declaration site:

```kotlin
@OptIn(ExperimentalNugetCoroutineApi::class)
class CatNapService {
    suspend fun longNap(): String { ... }
}
```

The KSP processor checks for the opt-in:
- **With `@OptIn`**: exports suspend methods with scope management, no warnings
- **Without `@OptIn`**: exports suspend methods with scope management, emits a KSP warning explaining the behavior

Both paths produce the same output — the annotation is informational, not gating. This matches how `@ExperimentalCoroutinesApi` works: the API is usable either way, but the developer is informed.

### Kotlin-side exports (shared helpers)

```kotlin
@CName("nuget_scope_create")
fun export_nuget_scope_create(): COpaquePointer =
    StableRef.create(
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ).asCPointer()

@CName("nuget_scope_cancel")
fun export_nuget_scope_cancel(handle: COpaquePointer) {
    handle.asStableRef<CoroutineScope>().get().cancel()
}

@CName("nuget_scope_dispose")
fun export_nuget_scope_dispose(handle: COpaquePointer) {
    handle.asStableRef<CoroutineScope>().dispose()
}
```

`SupervisorJob()` is used because failure of one child coroutine (one async call) should not cancel other in-flight calls on the same object. This matches Android's `viewModelScope` semantics.

### Suspend method export (per-class, changed signature)

```kotlin
@CName("catnapservice_longnap_async")
fun export_catnapservice_longnap_async(
    handle: COpaquePointer,
    scopeHandle: COpaquePointer,     // NEW: scope handle
    callbackPtr: COpaquePointer,
    userData: COpaquePointer,
) {
    val obj = handle.asStableRef<CatNapService>().get()
    val scope = scopeHandle.asStableRef<CoroutineScope>().get()
    val fn = callbackPtr.reinterpret<CFunction<
        (COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()

    scope.launch {                   // uses instance scope, not a new scope
        try {
            val result = obj.longNap()
            val resultRef = StableRef.create(result).asCPointer()
            fn.invoke(resultRef, null, 0, userData)
        } catch (e: CancellationException) {
            fn.invoke(null, null, 1, userData)
            throw e                  // re-throw to preserve structured concurrency
        } catch (e: Throwable) {
            val errRef = StableRef.create(
                e.message ?: "Kotlin error"
            ).asCPointer()
            fn.invoke(null, errRef, 0, userData)
        }
    }
}
```

Key changes from ADR-019:
1. `scopeHandle` parameter replaces `CoroutineScope(Dispatchers.Default)`
2. Callback ABI gains an `isCancelled: Byte` parameter (see below)
3. `CancellationException` is caught **before** `Throwable` and signals cancellation via `isCancelled = 1`
4. `CancellationException` is re-thrown after calling the callback to preserve structured concurrency invariants

### Callback ABI change

The callback signature changes from `(resultPtr, errorPtr, userData)` to `(resultPtr, errorPtr, isCancelled, userData)`. The new `isCancelled: Byte` parameter (0 or 1) provides an explicit cancellation signal without relying on sentinel strings or overloaded null semantics:

| Signal           | `resultPtr` | `errorPtr` | `isCancelled` | C# action                              |
|------------------|-------------|------------|---------------|----------------------------------------|
| Success (value)  | non-null    | null       | 0             | `tcs.SetResult(value)`                 |
| Success (Unit)   | null        | null       | 0             | `tcs.SetResult(true)`                  |
| Error            | null        | non-null   | 0             | `tcs.SetException(new KotlinException)` |
| **Cancelled**    | **null**    | **null**   | **1**         | **`tcs.TrySetCanceled()`**             |

This is a breaking change to `NugetAsyncCallback` — all existing async methods (suspend functions and suspend lambdas) must be updated. Since we're already modifying suspend method exports (adding `scopeHandle`) and this is pre-1.0, the ABI change is acceptable.

### C# class (generated)

```csharp
public class CatNapService : IDisposable
{
    internal IntPtr _handle;
    internal IntPtr _scopeHandle;    // NEW: scope handle

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "catnapservice_create")]
    private static extern IntPtr Native_Create();

    public CatNapService()
    {
        _handle = Native_Create();
        _scopeHandle = NugetScopeNative.Create();   // NEW
    }

    internal CatNapService(IntPtr handle)
    {
        _handle = handle;
        _scopeHandle = NugetScopeNative.Create();   // NEW
    }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "catnapservice_longnap_async")]
    private static extern void Native_LongNapAsync(
        IntPtr handle,
        IntPtr scopeHandle,          // NEW
        NugetAsyncCallback callback,
        IntPtr userData);

    public Task<string> LongNapAsync()
    {
        if (_handle == IntPtr.Zero)
            throw new ObjectDisposedException(nameof(CatNapService));

        var tcs = new TaskCompletionSource<string>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        GCHandle tcsHandle = GCHandle.Alloc(tcs);
        NugetAsyncCallback callback = null!;
        GCHandle callbackHandle = default;
        callback = (resultPtr, errorPtr, isCancelled, userData) =>
        {
            callbackHandle.Free();
            var t = (TaskCompletionSource<string>)
                GCHandle.FromIntPtr(userData).Target!;
            GCHandle.FromIntPtr(userData).Free();
            if (isCancelled != 0)
            {
                t.TrySetCanceled();
            }
            else if (errorPtr != IntPtr.Zero)
            {
                string msg = NugetMarshal.FromHandle<string>(errorPtr);
                t.SetException(new KotlinException(msg));
            }
            else
            {
                t.SetResult(
                    NugetMarshal.FromHandle<string>(resultPtr));
            }
        };
        callbackHandle = GCHandle.Alloc(callback);
        Native_LongNapAsync(
            _handle, _scopeHandle, callback,
            GCHandle.ToIntPtr(tcsHandle));
        return tcs.Task;
    }

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "catnapservice_dispose")]
    private static extern void Native_Dispose(IntPtr handle);

    public void Dispose()
    {
        if (_handle != IntPtr.Zero)
        {
            NugetScopeNative.Cancel(_scopeHandle);     // cancel scope first
            Native_Dispose(_handle);
            NugetScopeNative.Dispose(_scopeHandle);
            _handle = IntPtr.Zero;
            _scopeHandle = IntPtr.Zero;
        }
    }
}
```

### NugetScopeNative helper class (generated once)

```csharp
internal static class NugetScopeNative
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_scope_create")]
    internal static extern IntPtr Create();

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_scope_cancel")]
    internal static extern void Cancel(IntPtr handle);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "nuget_scope_dispose")]
    internal static extern void Dispose(IntPtr handle);
}
```

### Dispose ordering

The dispose sequence is intentional:

1. `NugetScopeNative.Cancel(_scopeHandle)` — cancels all child coroutines. In-flight callbacks fire with `isCancelled = 1`, freeing their `GCHandle`s.
2. `Native_Dispose(_handle)` — disposes the Kotlin object `StableRef`.
3. `NugetScopeNative.Dispose(_scopeHandle)` — disposes the scope `StableRef`.
4. Zero out both handles.

Cancel before dispose ensures the coroutines complete (with cancellation) before the scope's `StableRef` is released.

### ObjectDisposedException

Async methods check `_handle == IntPtr.Zero` at entry and throw `ObjectDisposedException`. This is a standard C# pattern — calling methods on a disposed object is a programming error and should fail fast.

### Top-level suspend functions

Top-level suspend functions have no associated class instance and no natural lifecycle owner. They continue to use `CoroutineScope(Dispatchers.Default).launch` as before. If the caller needs cancellation, that comes with `CancellationToken` support in a separate ADR.

### Suspend lambdas

`KotlinSuspendFunc<T>` and `KotlinSuspendAction` (ADR-020) continue to use standalone scopes. Their `Dispose()` does not currently cancel in-flight `InvokeAsync()` calls. Adding scope management to suspend lambdas is deferred — the lambda value lives independently once retrieved from its parent class.

## Consequences

### New infrastructure

- `ExperimentalNugetCoroutineApi` annotation in the annotations module
- KSP warning when classes with suspend methods lack `@OptIn(ExperimentalNugetCoroutineApi::class)`
- `nuget_scope_create`, `nuget_scope_cancel`, `nuget_scope_dispose` exports in `GenericClassExports.kt`
- `CirScopeHelper` CIR declaration → `NugetScopeNative` class in `CirRenderer`
- `CirClass.hasSuspendMethods: Boolean` flag to conditionally generate `_scopeHandle`
- `NugetAsyncCallback` delegate updated: `(resultPtr, errorPtr, isCancelled, userData)` — all existing async methods updated
- Modified `renderAsyncMethod` to pass `_scopeHandle` and handle `isCancelled` flag
- Modified `renderDispose` to cancel scope before disposing (for classes with suspend methods)
- Modified `buildSuspendMethodBody` to accept `scopeHandle`, catch `CancellationException`, use instance scope

### Behavioral changes

- In-flight `Task`s complete with `TaskCanceledException` when `Dispose()` is called (previously they hung or leaked)
- Async methods throw `ObjectDisposedException` after `Dispose()` (previously they crashed with an access violation)
- `GCHandle` leak on dispose is fixed — cancellation fires the callback, which frees the handles

### Deferred

- `CancellationToken` parameter on async methods — requires per-call `Job` handle and cancel export; warrants its own ADR
- `IAsyncDisposable` / graceful drain — requires tracking in-flight `Job` count
- Top-level suspend function scope — no natural lifecycle owner
- Suspend lambda structured cancellation — needs a separate mechanism
- Sealed class / abstract class hierarchy scope management
