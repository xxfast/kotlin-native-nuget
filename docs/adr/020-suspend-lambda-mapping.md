# ADR-020: Suspend lambda mapping — `suspend () -> R` → `KotlinSuspendFunc<R>`

## Status

Accepted

## Context

Kotlin suspend lambdas (`suspend () -> R`, `suspend (T) -> R`, etc.) need to cross the C bridge into C#. This feature combines two existing bridge patterns: the lambda handle pattern (ADR-012) for lifecycle management, and the callback-based async pattern (ADR-019) for invocation.

### How suspend lambdas differ from regular lambdas

Regular lambdas (`() -> R`) are `kotlin.Function0<R>` and can be invoked synchronously via `fn.invoke()`. Suspend lambdas (`suspend () -> R`) are `kotlin.coroutines.SuspendFunction0<R>` — distinct interfaces. They cannot be invoked from a non-suspend context without launching a coroutine.

### How other targets handle suspend lambdas

- **JVM**: `suspend () -> R` compiles to `Function1<Continuation<R>, Object>`. Java callers must implement `Continuation<T>` manually or use a coroutine builder.
- **ObjC/Swift export**: Suspend lambda *values* are not supported. Only `suspend fun` methods on classes get completion handler bridging.
- **Swift Export (Alpha)**: Suspend lambda types are deferred/unsupported.

We are ahead of the official tooling here.

### KSP type detection

Suspend lambdas have distinct qualified names from regular lambdas:

| Kotlin type        | Qualified name                       |
|--------------------|--------------------------------------|
| `() -> R`          | `kotlin.Function0`                   |
| `suspend () -> R`  | `kotlin.coroutines.SuspendFunction0` |
| `(T) -> R`         | `kotlin.Function1`                   |
| `suspend (T) -> R` | `kotlin.coroutines.SuspendFunction1` |

A new `SUSPEND_LAMBDA_TYPES` set is needed alongside the existing `LAMBDA_TYPES`.

## Alternatives Considered

### 1. `KotlinSuspendFunc<TResult>` with callback-based `InvokeAsync()` (chosen)

A dedicated wrapper type paralleling `KotlinFunc<TResult>` but with `InvokeAsync()` returning `Task<TResult>`. Uses the same `StableRef` handle for lifecycle and the same `NugetAsyncCallback` + `TaskCompletionSource` pattern from ADR-019 for invocation.

**Pros:**
- Clear separation: `KotlinFunc` is synchronous, `KotlinSuspendFunc` is async
- `IDisposable` for deterministic cleanup (same as all bridge types)
- Reuses existing `NugetAsyncCallback` delegate — no new callback types needed
- Shared `nuget_suspend_func{N}_invoke` exports (like `nuget_func{N}_invoke`) keep generated code minimal

**Cons:**
- New wrapper types (`KotlinSuspendFunc`, `KotlinSuspendAction`) — but these are architecturally necessary

### 2. `KotlinFunc<Task<TResult>>` — reuse existing types

Make the last type parameter `Task<TResult>` and reuse `KotlinFunc<TResult>.Invoke()`.

**Rejected:** The bridge call itself cannot return a `Task` synchronously — the Kotlin side must launch a coroutine. `KotlinFunc.Invoke()` calls the native `nuget_func0_invoke` which does a synchronous call. A suspend lambda needs `nuget_suspend_func0_invoke` which launches a coroutine + callback. The paradigms are incompatible.

### 3. Blocking `Invoke()` via `runBlocking`

Use `runBlocking` in the native invoke export to make it synchronous.

**Rejected:** Same reasons as ADR-019 Alternative 3 — defeats coroutines, deadlock risk, thread starvation.

## Decision

Use **option 1: `KotlinSuspendFunc<TResult>` with callback-based `InvokeAsync()`**.

### C# API

| Kotlin type             | C# type                              |
|-------------------------|--------------------------------------|
| `suspend () -> R`       | `KotlinSuspendFunc<TResult>`         |
| `suspend (T) -> R`      | `KotlinSuspendFunc<T, TResult>`      |
| `suspend (T1, T2) -> R` | `KotlinSuspendFunc<T1, T2, TResult>` |
| `suspend () -> Unit`    | `KotlinSuspendAction`                |
| `suspend (T) -> Unit`   | `KotlinSuspendAction<T>`             |

Consumer usage:
```csharp
using var cat = new CatFeeder("Oreo");
using var onFeed = cat.OnFeed;
string result = await onFeed.InvokeAsync();
```

### Bridge mechanism

**Kotlin getter** (same as regular lambda — StableRef):
```kotlin
@CName("catfeeder_get_onFeed")
fun export_catfeeder_get_onFeed(handle: COpaquePointer): COpaquePointer =
    StableRef.create(handle.asStableRef<CatFeeder>().get().onFeed).asCPointer()
```

**Kotlin invoke** (shared helper — callback pattern from ADR-019):
```kotlin
@CName("nuget_suspend_func0_invoke")
fun export_nuget_suspend_func0_invoke(
    handle: COpaquePointer,
    callbackPtr: COpaquePointer,
    userData: COpaquePointer,
) {
    val fn = handle.asStableRef<SuspendFunction0<*>>().get()
    val callback = callbackPtr.reinterpret<CFunction<
        (COpaquePointer?, COpaquePointer?, COpaquePointer) -> Unit>>()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            val result = fn.invoke()
            callback.invoke(StableRef.create(result as Any).asCPointer(), null, userData)
        } catch (e: Throwable) {
            callback.invoke(null, StableRef.create(e.message ?: "Kotlin error").asCPointer(), userData)
        }
    }
}
```

**C# `KotlinSuspendFunc<TResult>`:**
```csharp
public class KotlinSuspendFunc<TResult> : IDisposable
{
    internal IntPtr _handle;

    internal KotlinSuspendFunc(IntPtr handle) { _handle = handle; }

    public Task<TResult> InvokeAsync()
    {
        var tcs = new TaskCompletionSource<TResult>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        GCHandle tcsHandle = GCHandle.Alloc(tcs);
        NugetAsyncCallback callback = null!;
        GCHandle callbackHandle = default;
        callback = (resultPtr, errorPtr, userData) =>
        {
            callbackHandle.Free();
            var t = (TaskCompletionSource<TResult>)GCHandle.FromIntPtr(userData).Target!;
            GCHandle.FromIntPtr(userData).Free();
            if (errorPtr != IntPtr.Zero)
                t.SetException(new KotlinException(NugetMarshal.FromHandle<string>(errorPtr)));
            else
                t.SetResult(NugetMarshal.FromHandle<TResult>(resultPtr));
        };
        callbackHandle = GCHandle.Alloc(callback);
        NugetSuspendFuncNative.Invoke0(_handle, callback, GCHandle.ToIntPtr(tcsHandle));
        return tcs.Task;
    }

    public void Dispose()
    {
        if (_handle != IntPtr.Zero)
        {
            NugetSuspendFuncNative.Dispose(_handle);
            _handle = IntPtr.Zero;
        }
    }
}
```

### Dispose

Reuses existing `nuget_dispose` — the handle is a plain `StableRef` regardless of whether the lambda is suspend or not.

### Arity support

Arities 0–3 in v1, matching `KotlinFunc` limits.

## Consequences

### New infrastructure

- `SUSPEND_LAMBDA_TYPES` set in `CirTypeMapping.kt`
- `suspendLambdaArities: MutableSet<Int>` in `CollectionHelperTracker`
- `CirSuspendFuncNativeHelper` and `CirSuspendFuncHelper` CIR declarations
- `renderSuspendFuncNativeHelper` and `renderSuspendFuncHelper` in `CirRenderer`
- `addNugetSuspendFunc{N}HelperExports` in the Kotlin export generator
- Suspend lambda detection in `CirClassTranslator` property handling

### Deferred

- Arity 4+
- Suspend lambda parameters (C# → Kotlin) — Phase 6
- Nullable suspend lambda properties
- Suspend lambdas as function return types
- Suspend lambdas as generic type arguments
