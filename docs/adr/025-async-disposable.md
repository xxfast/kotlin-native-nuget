# ADR-025: IAsyncDisposable — graceful drain of in-flight async operations

## Status

Proposed

## Context

ADR-021 introduced scope-per-class structured concurrency. When `Dispose()` is called, it cancels the class scope, which cancels all in-flight coroutines immediately. In-flight `Task`s complete with `TaskCanceledException`.

ADR-021 explicitly deferred graceful drain:

> *"IAsyncDisposable / graceful drain — requires tracking in-flight Job count"*

ADR-022 added `CancellationToken` support and similarly deferred it:

> *"IAsyncDisposable / graceful drain — requires tracking in-flight job count; warrants a separate ADR."*

The use case: a C# consumer wants to wait for all in-flight async operations to complete before releasing the native handle, rather than cancelling them. C# 8.0 introduced `IAsyncDisposable` / `await using` precisely for this pattern.

```csharp
// Current: Dispose() cancels in-flight tasks immediately
using var service = new CatNapService();
Task<string> nap = service.LongNapAsync();
// Dispose() fires, nap gets TaskCanceledException

// Desired: DisposeAsync() waits for in-flight tasks to finish
await using var service = new CatNapService();
Task<string> nap = service.LongNapAsync();
// DisposeAsync() waits for nap to finish, then disposes
```

### What IAsyncDisposable is in C#

`IAsyncDisposable` (in `System` since .NET Standard 2.1 / .NET Core 3.0) defines a single method:

```csharp
public interface IAsyncDisposable
{
    ValueTask DisposeAsync();
}
```

The `await using` statement calls `DisposeAsync()` and awaits the returned `ValueTask`. This is the standard C# pattern for resources that need asynchronous cleanup — for example, flushing a write buffer before closing a file, or draining a message queue before shutting down. See: https://learn.microsoft.com/en-us/dotnet/standard/garbage-collection/implementing-disposeasync

The expected contract from C# consumers:
- `await using var obj = new MyService()` calls `DisposeAsync()` and awaits it.
- After `DisposeAsync()` returns, all cleanup is done — no in-flight operations remain.
- Calling methods after `DisposeAsync()` should throw `ObjectDisposedException` (same as synchronous dispose).

`IAsyncDisposable` and `IDisposable` are **independent interfaces**. A class can implement both, one, or neither. The .NET design guidelines recommend implementing both when possible, to allow callers to choose the synchronous or asynchronous cleanup path. See: https://learn.microsoft.com/en-us/dotnet/standard/garbage-collection/implementing-disposeasync#implement-both-dispose-and-async-dispose-patterns

### How other Kotlin interop targets handle graceful drain

#### Java (JVM)

Java `Closeable` / `AutoCloseable` is synchronous only (maps to C#'s `IDisposable`). There is no Java equivalent of `IAsyncDisposable`. Kotlin coroutine authors typically use `coroutineScope { ... }` to await child coroutines, or call `job.join()` on child jobs before returning from a cleanup method. There is no standard "async dispose" idiom on JVM.

#### ObjC/Swift Export (built-in)

No lifecycle management. ObjC `dealloc` is synchronous. Swift's `deinit` is synchronous. Neither supports a "wait for in-flight async work before dealloc" pattern out of the box.

#### SKIE (Touchlab — third-party for Swift)

SKIE maps Kotlin objects with suspend methods to Swift `actor`-like types with a structured concurrency scope. When the underlying scope is cancelled, in-flight Swift `Task`s get `.cancellation` thrown — there is no "drain" path where Swift awaits all tasks before dealloc. SKIE's model is cancel-on-dispose, not drain-on-dispose.

#### Swift Export (Alpha)

Swift's structured concurrency has task groups (`withTaskGroup`) that can be awaited until all child tasks complete, but this is a call-site pattern, not a type lifecycle pattern. There is no Swift equivalent of "drain all tasks before dealloc."

#### Kotlin/JS

`Promise`s have no cancel/drain lifecycle. No equivalent pattern.

**Summary**: None of the existing interop targets have a built-in graceful drain pattern. C#'s `IAsyncDisposable` is unique to the .NET ecosystem. The bridge must implement this entirely on the C# side.

### The Kotlin-side mechanism: scope.coroutineContext[Job]!.children.toList().forEach { it.join() }

When the C# consumer calls `DisposeAsync()`, the desired behavior is:
1. Stop accepting new async calls (set a "draining" flag so new calls throw `ObjectDisposedException`)
2. Wait for all currently in-flight coroutines to complete (either successfully, with error, or with cancellation)
3. Then cancel and dispose the scope + native handle

On the Kotlin side, the `CoroutineScope` created by `nuget_scope_create` contains a `SupervisorJob`. All per-call `Job`s launched via `scope.launch(...)` are **children** of this `SupervisorJob`. Waiting for all children to complete is achievable by joining the supervisor job's children:

```kotlin
val scope: CoroutineScope = scopeHandle.asStableRef<CoroutineScope>().get()
val supervisor: Job = scope.coroutineContext[Job]!!
supervisor.children.toList().forEach { it.join() }
```

However, this is Kotlin-side logic. The bridge cannot run this from C# — the C# side cannot call a `suspend fun` directly. The drain operation itself must be an exported async function that C# calls through the same callback bridge as regular suspend methods.

### New shared export: nuget_scope_drain

A new shared Kotlin export runs the drain:

```kotlin
@CName("nuget_scope_drain")
fun export_nuget_scope_drain(
  scopeHandle: COpaquePointer,
  callbackPtr: COpaquePointer,
  userData: COpaquePointer,
): COpaquePointer {
  val scope = scopeHandle.asStableRef<CoroutineScope>().get()
  val fn = callbackPtr.reinterpret<CFunction<
    (COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()
  val drainJob = scope.launch(start = CoroutineStart.ATOMIC) {
    scope.coroutineContext[Job]?.children
      ?.filter { it != coroutineContext[Job] }
      ?.forEach { it.join() }
    fn.invoke(null, null, 0.toByte(), userData)
  }
  return StableRef.create(drainJob).asCPointer()
}
```

Key points:
- The drain job is launched **within the same scope** as the object's coroutines. This means it runs as a sibling, not a parent — it does not block the scope from cancelling.
- The drain job filters itself out when collecting children to join (otherwise it would wait on itself forever).
- The drain job calls the callback with `(null, null, 0)` (Unit-success signal) when all other children are done.
- It returns a `StableRef<Job>` handle so the C# side can use the existing `nuget_job_cancel` / `nuget_job_dispose` infrastructure for the drain job itself.
- No new callback ABI is needed — the drain result is `Unit` (success), matching the existing `(null, null, 0)` convention.

### The "new calls after drain starts" race condition

Between the moment `DisposeAsync()` is called (which sets `_handle = IntPtr.Zero`) and the moment the drain completes, no new async calls should be started. The existing `ObjectDisposedException` guard in each async method covers this:

```csharp
if (_handle == IntPtr.Zero)
    throw new ObjectDisposedException(nameof(CatNapService));
```

`DisposeAsync()` must zero out `_handle` first (using `Interlocked.Exchange`), then await the drain. Any new async method calls after `_handle = IntPtr.Zero` will throw `ObjectDisposedException` immediately.

### Interaction with synchronous Dispose()

A class implementing `IAsyncDisposable` should also implement `IDisposable` for backward compatibility and for `using` (non-async) contexts. The .NET guidance (https://learn.microsoft.com/en-us/dotnet/standard/garbage-collection/implementing-disposeasync#implement-both-dispose-and-async-dispose-patterns) recommends:

- `Dispose()`: synchronous path — cancel all in-flight work immediately (existing behavior from ADR-021)
- `DisposeAsync()`: asynchronous path — drain all in-flight work, then clean up

This is the same distinction made by `Stream` in .NET: `Close()` flushes synchronously; `FlushAsync()` flushes asynchronously. The two paths use the same underlying resource but with different completion guarantees.

The generated class implements both:
```csharp
public class CatNapService : IDisposable, IAsyncDisposable
```

## Alternatives Considered

### 1. Track in-flight job count with an atomic counter (not chosen)

Instead of joining all child jobs, maintain an `int _inflightCount` on the C# class. Increment before calling the native async export; decrement in the callback (regardless of success/cancel/error). `DisposeAsync()` polls or waits until the counter reaches zero.

**Pros:**
- No new Kotlin export needed.
- The "drain" is entirely C#-side.
- No scope introspection (no need to access `Job.children`).

**Cons:**
- **Polling or TaskCompletionSource needed**: to await the count reaching zero without spinning, C# needs a `TaskCompletionSource` that's completed when the counter decrements to zero, held as a field. This is significant per-class state.
- **Race with concurrent dispose**: the counter check and scope cancel must be atomic relative to in-flight callbacks. Complex synchronization required.
- **Doesn't generalize**: if a new async call is started just before `_handle` is zeroed, it won't be counted. The `ObjectDisposedException` guard covers this, but the timing is tight.
- **Added complexity**: the C# class gains a counter field, a TCS field, and more complex callback wiring just for the drain case.

### 2. nuget_scope_drain as a shared export (chosen)

A single new shared Kotlin export `nuget_scope_drain` joins all children of the supervisor job, then fires the callback. C# calls this through the existing async callback infrastructure before cancelling and disposing.

**Pros:**
- No new C# per-class state — drain uses the existing `_scopeHandle`.
- Uses the existing callback ABI (`NugetAsyncCallback`) — no new delegate type.
- Uses the existing job infrastructure (`nuget_job_cancel` / `nuget_job_dispose` for the drain job handle).
- Kotlin's `Job.children` correctly tracks all in-flight coroutines launched under the scope — no manual counter.
- Drain is atomic with the scope's coroutine tracking — no missed jobs.
- The pattern matches how Kotlin itself handles "wait for all children" (structured concurrency via `job.join()` or `scope.coroutineContext[Job]!!.join()`).

**Cons:**
- New shared export (`nuget_scope_drain`) must be generated.
- Drain job is a child of the scope — if the scope is cancelled during drain, the drain job itself gets cancelled. This is acceptable: if `Dispose()` is called concurrently with `DisposeAsync()`, the cancel wins and `DisposeAsync()` completes with cancellation (the `Task` returned by `DisposeAsync()` is a `ValueTask` from `TrySetCanceled`).
- `Job.children` is a `Sequence<Job>` that snapshots at the time of the call — jobs launched after the snapshot is taken are not waited on. Since `_handle` is zeroed before calling drain, no new jobs can be launched.

### 3. scope.join() / wait on the SupervisorJob itself (not chosen)

Call `scope.coroutineContext[Job]!!.join()` directly instead of iterating children. The `SupervisorJob` itself completes only when it is cancelled and all children are done. This requires cancelling the scope first, then joining — equivalent to the current `Dispose()` behavior.

**Cons:**
- Cancels first, then joins — the "drain" becomes "cancel then wait", not "wait then cancel". This defeats the purpose.
- There is no way to join a `SupervisorJob` without cancelling it first, because a `SupervisorJob` never completes on its own (it's designed to run indefinitely until cancelled).

### 4. No IAsyncDisposable support (status quo)

Leave graceful drain unimplemented. C# consumers use `using` (synchronous cancel) only.

**Cons:**
- `await using` is a common pattern in ASP.NET Core and modern C# code. Libraries that manage async resources are expected to implement `IAsyncDisposable`.
- Without it, consumers who want to drain must manually await all outstanding tasks before calling `Dispose()` — possible but tedious and error-prone.

## Decision

Use **option 2: `nuget_scope_drain` as a shared export**.

### New shared Kotlin export

```kotlin
@CName("nuget_scope_drain")
fun export_nuget_scope_drain(
  scopeHandle: COpaquePointer,
  callbackPtr: COpaquePointer,
  userData: COpaquePointer,
): COpaquePointer {
  val scope = scopeHandle.asStableRef<CoroutineScope>().get()
  val fn = callbackPtr.reinterpret<CFunction<
    (COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()
  val drainJob = scope.launch(start = CoroutineStart.ATOMIC) {
    val self = coroutineContext[Job]
    scope.coroutineContext[Job]
      ?.children
      ?.filter { it != self }
      ?.forEach { it.join() }
    fn.invoke(null, null, 0.toByte(), userData)
  }
  return StableRef.create(drainJob).asCPointer()
}
```

Added to `addNugetScopeHelperExports()` in `GenericClassExports.kt`.

### New CIR helper node

`CirScopeHelper` already covers `NugetScopeNative` in the C# renderer. Add `NugetScopeNative.Drain(IntPtr scopeHandle, NugetAsyncCallback callback, IntPtr userData)` to the rendered class.

Updated `renderScopeHelper` in `CirRenderer.kt`:

```csharp
internal static class NugetScopeNative
{
    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_scope_create")]
    internal static extern IntPtr Create();

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_scope_cancel")]
    internal static extern void Cancel(IntPtr handle);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_scope_dispose")]
    internal static extern void Dispose(IntPtr handle);

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_scope_drain")]
    internal static extern IntPtr Drain(IntPtr handle, NugetAsyncCallback callback, IntPtr userData);
}
```

### Generated C# class

Classes with suspend methods implement both `IDisposable` and `IAsyncDisposable`:

```csharp
public class CatNapService : IDisposable, IAsyncDisposable
{
    internal IntPtr _handle;
    internal IntPtr _scopeHandle;

    // ... constructors, methods, properties unchanged ...

    [DllImport("sample", CallingConvention = CallingConvention.Cdecl,
        EntryPoint = "catnapservice_dispose")]
    private static extern void Native_Dispose(IntPtr handle);

    public void Dispose()
    {
        IntPtr handle = Interlocked.Exchange(ref _handle, IntPtr.Zero);
        if (handle == IntPtr.Zero) return;
        IntPtr scopeHandle = Interlocked.Exchange(ref _scopeHandle, IntPtr.Zero);
        if (scopeHandle != IntPtr.Zero)
        {
            NugetScopeNative.Cancel(scopeHandle);
            NugetScopeNative.Dispose(scopeHandle);
        }
        Native_Dispose(handle);
    }

    public ValueTask DisposeAsync()
    {
        // Zero _handle first so new async calls throw ObjectDisposedException
        IntPtr handle = Interlocked.Exchange(ref _handle, IntPtr.Zero);
        if (handle == IntPtr.Zero) return ValueTask.CompletedTask;

        IntPtr scopeHandle = Interlocked.Exchange(ref _scopeHandle, IntPtr.Zero);
        if (scopeHandle == IntPtr.Zero)
        {
            // No scope (never called an async method) — dispose synchronously
            Native_Dispose(handle);
            return ValueTask.CompletedTask;
        }

        return new ValueTask(DrainAndDisposeAsync(handle, scopeHandle));
    }

    private Task DrainAndDisposeAsync(IntPtr handle, IntPtr scopeHandle)
    {
        var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        GCHandle tcsHandle = GCHandle.Alloc(tcs);
        NugetAsyncCallback callback = null!;
        GCHandle callbackHandle = default;
        CancellationTokenRegistration reg = default;
        IntPtr drainJobHandle = IntPtr.Zero;

        callback = (resultPtr, errorPtr, isCancelled, userData) =>
        {
            reg.Dispose();
            NugetJobNative.Dispose(drainJobHandle);
            callbackHandle.Free();
            var t = (TaskCompletionSource<bool>)GCHandle.FromIntPtr(userData).Target!;
            GCHandle.FromIntPtr(userData).Free();
            // After drain completes (or is cancelled), synchronously dispose
            NugetScopeNative.Dispose(scopeHandle);
            Native_Dispose(handle);
            if (isCancelled != 0)
                t.TrySetCanceled();
            else
                t.SetResult(true);
        };
        callbackHandle = GCHandle.Alloc(callback);
        drainJobHandle = NugetScopeNative.Drain(scopeHandle, callback, GCHandle.ToIntPtr(tcsHandle));
        return tcs.Task;
    }
}
```

Key design points:

1. `_handle` is zeroed **before** starting the drain. Any new async method call after this point throws `ObjectDisposedException`. The drain only waits for already-started operations.

2. `_scopeHandle` is zeroed atomically alongside `_handle`. The old scope handle is passed to `DrainAndDisposeAsync` for use in the drain call and final dispose.

3. If `scopeHandle == IntPtr.Zero` (the lazy scope was never created — no async method was ever called), `DisposeAsync()` returns `ValueTask.CompletedTask` immediately. No drain needed.

4. `DrainAndDisposeAsync` uses the existing `NugetAsyncCallback` / `TaskCompletionSource` pattern from ADR-019, matching all existing async method wiring.

5. `NugetScopeNative.Dispose(scopeHandle)` and `Native_Dispose(handle)` are called from **inside the callback**, after the drain completes. This ensures handles are not released while the drain coroutine is still running.

6. The `drainJobHandle` is disposed in the callback via `NugetJobNative.Dispose` — same lifetime management as all other job handles from ADR-022.

7. If `Dispose()` is called concurrently with `DisposeAsync()` (both `Interlocked.Exchange` calls race), one of them gets `IntPtr.Zero` back and becomes a no-op. The one that won disposes. No double-free.

8. `DrainAndDisposeAsync` does not accept a `CancellationToken` parameter. Drain is unconditional — the caller is explicitly requesting a graceful drain. If a caller needs a time-bounded drain, they can `await` with `WaitAsync(cancellationToken)` on the returned `ValueTask`.

### Dispose() behavior unchanged (synchronous cancel path)

The existing `Dispose()` behavior is unchanged: it cancels the scope immediately, which cancels all in-flight coroutines. In-flight `Task`s complete with `TaskCanceledException`.

### CirClass.hasSuspendMethods flag drives both IDisposable and IAsyncDisposable

`CirClass.hasSuspendMethods` already exists and controls `_scopeHandle` generation and scope-aware dispose. The same flag now also controls whether the class implements `IAsyncDisposable` and whether `DisposeAsync()` is generated.

The `renderClass` method in `CirRenderer.kt` already branches on `cls.hasSuspendMethods` for the `_scopeHandle` field and `GetOrCreateScope()`. The dispose rendering (`renderDispose`) gains an `isAsync: Boolean` override, or `DisposeAsync` is rendered as a separate method alongside `Dispose`.

### Consumer API

```csharp
// Graceful drain: await all in-flight naps before cleanup
await using var service = new CatNapService();
Task<string> napTask = service.LongNapAsync();
// DisposeAsync() waits for napTask to complete, then releases the handle

// Synchronous cancel: cancel all in-flight naps immediately (unchanged)
using var service2 = new CatNapService();
Task<string> napTask2 = service2.LongNapAsync();
// Dispose() cancels napTask2 (TaskCanceledException)

// Mix: start multiple, drain waits for all
await using var service3 = new CatNapService();
Task<string> nap1 = service3.LongNapAsync();
Task<string> nap2 = service3.QuickNapAsync();
await service3.DisposeAsync(); // waits for both nap1 and nap2
```

## Consequences

### New infrastructure

- `nuget_scope_drain` export added to `addNugetScopeHelperExports()` in `GenericClassExports.kt`.
- `renderScopeHelper` in `CirRenderer.kt` gains the `Drain` P/Invoke declaration.
- `renderClass` updated: classes with `hasSuspendMethods` add `IAsyncDisposable` to their interface list and render `DisposeAsync()` + `DrainAndDisposeAsync()`.
- `CirClass.interfaces` already supports additional interfaces; `"IAsyncDisposable"` is appended when `hasSuspendMethods = true`.
- The `using System` directive (already present) covers `IAsyncDisposable` on .NET 6+ and `System.ValueTask`. No new using directives required.

### Behavioral changes

- Classes with suspend methods now implement `IAsyncDisposable` in addition to `IDisposable`.
- `await using var obj = new CatNapService()` now compiles and produces graceful drain behavior.
- `Dispose()` behavior is unchanged.
- The drain export is **additive**: existing uses of `Dispose()` are not broken.

### Scope

**v1 (this ADR):**
- `IAsyncDisposable` + `DisposeAsync()` for classes with suspend methods (those with `hasSuspendMethods = true`).
- `nuget_scope_drain` shared export.
- Drain uses `Job.children` snapshot at drain-start time — operations started before `_handle` is zeroed are drained; operations started (and rejected) after are not.

**Deferred:**
- `IAsyncDisposable` for sealed class hierarchies with suspend methods.
- `IAsyncDisposable` for abstract class hierarchies with suspend methods.
- `DisposeAsync()` for `KotlinSuspendFunc<T>` / `KotlinSuspendAction` suspend lambda wrappers.
- Top-level suspend functions have no class scope and no `DisposeAsync()` path — callers use `CancellationToken` (ADR-022) for lifecycle control.
