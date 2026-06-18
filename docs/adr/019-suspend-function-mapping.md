# ADR-019: Suspend function mapping — coroutines → Task/async

## Status

Proposed

## Context

Kotlin `suspend` functions are the primary mechanism for expressing asynchronous operations in Kotlin. They must be mapped to something C# developers find natural and ergonomic. This is the most complex feature mapping in the project — it crosses not just a type boundary but a concurrency model boundary.

### What Kotlin/Native produces for suspend functions

Kotlin `suspend` functions **cannot be directly exported with `@CName`**. The Kotlin compiler transforms every `suspend fun` using Continuation-Passing Style (CPS): the function gets an implicit `Continuation<T>` parameter appended and its return type changes from `T` to `Any?`. This CPS signature cannot be expressed as a C-compatible `@CName` export because `Continuation<T>` is a generic Kotlin interface with no C equivalent.

The consequence is that the bridge layer must wrap every suspend function in a non-suspend shim that:
1. Launches a coroutine internally (using `CoroutineScope.launch`)
2. Accepts a C-compatible completion callback (a `CPointer<CFunction<...>>`)
3. Calls the callback when the coroutine completes

This wrapping is always on the Kotlin side — there is no way to push the async machinery to the C header.

### How other Kotlin interop targets handle suspend functions

#### Java interop (JVM)

Kotlin compiles `suspend fun fetch(): String` to `Object fetch(Continuation<String> var0)` in the JVM bytecode. Java callers must either implement `Continuation<T>` manually (pass `resumeWith(Result.success(value))`) or use a coroutine builder bridge:

```kotlin
// Kotlin helper for Java callers:
fun fetchAsync(): CompletableFuture<String> = GlobalScope.future { fetch() }
```

The recommended pattern is not to expose the `Continuation` directly to Java — instead expose a `CompletableFuture`-returning non-suspend wrapper. This is exactly the pattern we must replicate at the C boundary, but with a callback function pointer instead of `CompletableFuture`.

#### ObjC/Swift Export (built-in)

Kotlin/Native's ObjC interop presents suspend functions as completion handler methods. The generated Objective-C header:

```objc
- (void)fetchWithCompletionHandler:(void (^)(NSString * _Nullable, NSError * _Nullable))completionHandler;
```

Swift 5.5+ can call these as `async` functions automatically. Limitations of the built-in approach:
- No scope or dispatcher control (runs on Kotlin's internal thread)
- No cancellation propagation across the language boundary
- No Flow support

#### SKIE (Touchlab — third-party enhancement for Swift)

SKIE generates genuine Swift `async` functions (not just Swift's automatic bridging of ObjC completion handlers). Its key design decisions:
- Two-way cancellation: cancelling the Swift Task cancels the Kotlin coroutine and vice versa
- Thread safety: SKIE removes the main-thread-only restriction, allowing suspend calls from any Swift thread
- The original Kotlin function is renamed with `__` prefix; the generated Swift wrapper replaces it
- The custom runtime bridges Swift's concurrency context to Kotlin's coroutine context

#### Swift Export (Alpha — official)

Swift Export maps `suspend fun` directly to Swift `async func`. Flows are exported as `AsyncSequence`. Runs on `Dispatchers.Default` by default; `withContext(Dispatchers.Main)` works. This is the gold standard — zero boilerplate for the consumer.

#### Kotlin/JS

Until Kotlin 2.3.0, `@JsExport` prohibited suspend functions entirely. Developers wrapped them manually with `GlobalScope.promise { ... }` to return `Promise<T>`. Starting with Kotlin 2.3.0, suspend functions can be directly exported as `Promise`-returning functions. The idiom is identical to what we need at the C boundary — wrap the suspend call in an async primitive that the target platform understands.

### Kotlin/Native threading model (post memory-model redesign)

Since Kotlin/Native adopted the new shared-heap memory model (Kotlin 1.7.20+, enabled by default in 1.9.20+):
- **Objects can be shared across threads** — the old `freeze()` requirement is gone
- `Dispatchers.Default` is backed by a pool of background worker threads
- Coroutines can resume on any thread (they are not pinned to the originating thread)
- Calling C function pointers from a Kotlin coroutine's continuation thread is safe — the new memory model lifts the threading restrictions on interop callbacks

This means the following pattern is safe in the current Kotlin/Native runtime:
```kotlin
CoroutineScope(Dispatchers.Default).launch {
    val result = userSuspendFunction()          // runs on Kotlin thread pool
    callbackFnPtr(resultPtr, errorPtr, userData) // can call C fn ptr from background thread
}
```

### What's idiomatic in C#

C# async operations use `Task<T>` (`System.Threading.Tasks.Task<T>`) with the `async`/`await` keywords. `Task<T>` is the standard type for any asynchronous operation that produces a value; `Task` (without generic parameter, equivalent to `Task<void>`) represents completion without a value.

`TaskCompletionSource<TResult>` is the standard bridge between callback-based async APIs and `Task<T>`:

```csharp
var tcs = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
// pass tcs.SetResult / tcs.SetException as callback
// expose tcs.Task to the caller
```

Key properties:
- `tcs.Task` — the `Task<T>` the caller awaits
- `tcs.SetResult(value)` — completes the Task with a value
- `tcs.SetException(ex)` — faults the Task with an exception
- `tcs.SetCanceled()` — cancels the Task
- `TaskCreationOptions.RunContinuationsAsynchronously` — prevents the callback thread from running continuations synchronously, avoiding deadlocks when Kotlin's thread pool calls the completion

### The bridge mechanism constraint

The C boundary only supports raw function pointers and `IntPtr` handles. There is no shared memory manager, no automatic callback registration, and no runtime on either side that can inspect the other's concurrency state. This means:

1. Every async call must carry a **completion callback** — a C function pointer that Kotlin calls when the coroutine finishes
2. The result must be marshalled back through `IntPtr` (for objects) or primitive values (for scalars)
3. Error state must be conveyed — either via a separate error `IntPtr` or via a flag + `IntPtr` error detail
4. The C# side needs a **stable reference to itself** while Kotlin holds the callback — a `GCHandle` prevents the `TaskCompletionSource` from being collected before the callback fires

## Alternatives Considered

### 1. Callback-based bridge with TaskCompletionSource (chosen for v1)

The Kotlin shim accepts a C function pointer and an opaque context pointer (`userData: COpaquePointer`). When the coroutine completes, it invokes the function pointer. On the C# side, the `userData` is a `GCHandle` pointing to a `TaskCompletionSource<T>`; the callback sets the result.

**Kotlin side (generated shim for `suspend fun fetchName(): String`):**
```kotlin
@CName("fetch_name_async")
fun fetchNameAsync(
    completionPtr: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>,
    userData: COpaquePointer,
) {
    CoroutineScope(Dispatchers.Default).launch {
        try {
            val result: String = fetchName()
            val resultPtr: COpaquePointer = StableRef.create(result).asCPointer()
            completionPtr(resultPtr, /* errorPtr */ nativeNullPtr, userData)
        } catch (e: Throwable) {
            val errorPtr: COpaquePointer = StableRef.create(e.message ?: "error").asCPointer()
            completionPtr(/* resultPtr */ nativeNullPtr, errorPtr, userData)
        }
    }
}
```

**C# side (generated):**
```csharp
[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
private delegate void FetchNameCompletion(IntPtr resultPtr, IntPtr errorPtr, IntPtr userData);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "fetch_name_async")]
private static extern void Native_FetchNameAsync(FetchNameCompletion completion, IntPtr userData);

public static Task<string> FetchNameAsync()
{
    var tcs = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
    GCHandle handle = GCHandle.Alloc(tcs);
    FetchNameCompletion callback = (resultPtr, errorPtr, userData) =>
    {
        GCHandle h = GCHandle.FromIntPtr(userData);
        var tcs = (TaskCompletionSource<string>)h.Target!;
        h.Free();
        if (errorPtr != IntPtr.Zero)
            tcs.SetException(new KotlinException(Marshal.PtrToStringUTF8(errorPtr)!));
        else
            tcs.SetResult(Marshal.PtrToStringUTF8(resultPtr)!);
    };
    GCHandle callbackHandle = GCHandle.Alloc(callback); // prevent GC of delegate
    Native_FetchNameAsync(callback, GCHandle.ToIntPtr(handle));
    callbackHandle.Free(); // safe: P/Invoke duration is synchronous, callback is stored in native
    return tcs.Task;
}
```

**Pros:**
- Directly maps to the proven pattern used by every other Kotlin interop target (ObjC completion handler, Java `CompletableFuture` bridge, JS `Promise` wrapper)
- `Task<T>` is perfectly idiomatic C# — `await FetchNameAsync()` just works
- `TaskCompletionSource` is the standard tool for exactly this use case
- Error propagation via exceptions is natural on both sides
- Thread safety: Kotlin calls the completion on its own thread pool; `RunContinuationsAsynchronously` ensures the C# continuation runs asynchronously

**Cons:**
- Requires a per-signature delegate type — but these can be generated (like `nuget_func0_invoke`)
- The `GCHandle` for the `TaskCompletionSource` must be freed in the callback (not before); leaks if Kotlin crashes without calling the callback
- Result `StableRef` for object types must be disposed by C# after extraction (same as all other object returns)
- No cancellation in v1 (see Scope section)

### 2. Polling-based "Deferred handle" pattern

Kotlin launches the coroutine and immediately returns a `COpaquePointer` to a Kotlin `Deferred<T>`. C# polls for completion via an exported `is_complete(handle): Boolean` + `get_result(handle): T`.

**Pros:**
- No function pointer needed — simpler C ABI
- No `GCHandle` complexity on the C# side

**Cons:**
- **Polling is not idiomatic C#** — it burns CPU, introduces latency, and doesn't compose with `async`/`await`
- The `Deferred` handle must remain live (in a `StableRef`) until C# retrieves the result — hard to bound the lifetime
- No way to expose this as a `Task<T>` without spinning a background polling thread, which defeats the purpose
- No analogue in any other Kotlin interop target — ObjC, Swift, Java, and JS all use completion callbacks, not polling

### 3. Synchronous blocking (runBlocking wrapper)

Generate a non-suspend `@CName` wrapper that calls `runBlocking { suspendFun() }`. The blocking call occupies a thread while the coroutine runs.

**Kotlin side:**
```kotlin
@CName("fetch_name")
fun fetchName_blocking(): String = runBlocking { fetchName() }
```

**Pros:**
- Trivially simple — synchronous call, no callbacks, no `Task`
- C# side is identical to a regular function

**Cons:**
- **Blocks a thread on both sides** — the C# thread is blocked; the Kotlin `runBlocking` occupies a coroutine thread
- Defeats the purpose of `suspend` — the entire reason for coroutines is non-blocking async
- `runBlocking` on Kotlin/Native's main thread causes deadlocks if the coroutine needs to resume on the main thread
- Not scalable — 100 concurrent requests = 100 blocked threads
- Misrepresents the Kotlin API to C# consumers — they would not know the function is async

### 4. Event-based via registered global callback

Maintain a registered global completion callback (`nuget_set_async_handler(fnPtr)`) and identify completions by a correlation ID. Kotlin calls the global handler with `(correlationId, resultPtr, errorPtr)`.

**Pros:**
- Single C export for all async completions — fewer generated exports

**Cons:**
- Requires a correlation ID registry (dictionary) on both sides — thread-safe access needed
- Non-obvious ownership: which call produced which completion?
- Harder to compose with `async`/`await` — need to route by ID in the C# callback
- No analogue in other Kotlin interop targets — all use per-call callbacks
- Significantly more generated infrastructure for no ergonomic gain

## Decision

Use **option 1: callback-based bridge with `TaskCompletionSource<T>`**.

Every Kotlin `suspend fun` gets a non-suspend `@CName` wrapper on the Kotlin side that:
1. Launches a coroutine on `Dispatchers.Default`
2. Accepts a C completion callback `(resultPtr: COpaquePointer, errorPtr: COpaquePointer, userData: COpaquePointer) -> Unit`
3. Calls the callback on success (with a `StableRef`-pinned result and null error) or failure (with null result and a `StableRef`-pinned error message string)

On the C# side, the generated code:
1. Creates a `TaskCompletionSource<T>` with `RunContinuationsAsynchronously`
2. Pins it via `GCHandle.Alloc`
3. Constructs a delegate matching the completion signature
4. Calls the native async export
5. Returns `tcs.Task` — a `Task<T>` the caller awaits
6. The callback (invoked by Kotlin on its thread pool) sets the result/exception and frees the `GCHandle`

### Generated C# API — consumer view

```csharp
// suspend fun fetchName(): String
public static Task<string> FetchNameAsync() { ... }

// suspend fun fetchCat(id: Int): Cat
public static Task<Cat> FetchCatAsync(int id) { ... }

// suspend fun saveName(name: String)
public static Task SaveNameAsync(string name) { ... }

// class Service { suspend fun fetch(): String }
public class Service : IDisposable
{
    public Task<string> FetchAsync() { ... }
}
```

Naming convention: `Async` suffix on the C# method, matching .NET's established convention for async operations (`HttpClient.GetAsync`, `File.ReadAllTextAsync`, etc.).

### Detailed bridge for each case

#### `suspend fun fetchName(): String`

**Kotlin export (generated):**
```kotlin
@CName("fetch_name_async")
fun fetchNameAsync(
    callback: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer) -> Unit>>,
    userData: COpaquePointer,
) {
    CoroutineScope(Dispatchers.Default).launch {
        try {
            val result: String = fetchName()
            val resultRef = StableRef.create(result).asCPointer()
            callback.invoke(resultRef, nativeNullPtr, userData)
        } catch (e: Throwable) {
            val errRef = StableRef.create(e.message ?: "Kotlin error").asCPointer()
            callback.invoke(nativeNullPtr, errRef, userData)
        }
    }
}
```

**C# generated:**
```csharp
[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
private delegate void AsyncCompletion(IntPtr resultPtr, IntPtr errorPtr, IntPtr userData);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "fetch_name_async")]
private static extern void Native_FetchNameAsync(AsyncCompletion callback, IntPtr userData);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_unwrap_string")]
private static extern IntPtr Native_UnwrapString(IntPtr handle);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_dispose")]
private static extern void Native_Dispose(IntPtr handle);

public static Task<string> FetchNameAsync()
{
    var tcs = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
    GCHandle tcsHandle = GCHandle.Alloc(tcs);
    AsyncCompletion callback = null!;
    GCHandle callbackHandle = default;
    callback = (resultPtr, errorPtr, userData) =>
    {
        callbackHandle.Free();
        var t = (TaskCompletionSource<string>)GCHandle.FromIntPtr(userData).Target!;
        GCHandle.FromIntPtr(userData).Free();
        if (errorPtr != IntPtr.Zero)
        {
            IntPtr msgPtr = Native_UnwrapString(errorPtr);
            string msg = Marshal.PtrToStringUTF8(msgPtr)!;
            Native_Dispose(errorPtr);
            t.SetException(new KotlinException(msg));
        }
        else
        {
            IntPtr strPtr = Native_UnwrapString(resultPtr);
            string value = Marshal.PtrToStringUTF8(strPtr)!;
            Native_Dispose(resultPtr);
            t.SetResult(value);
        }
    };
    callbackHandle = GCHandle.Alloc(callback);
    Native_FetchNameAsync(callback, GCHandle.ToIntPtr(tcsHandle));
    return tcs.Task;
}
```

#### `suspend fun fetchCat(id: Int): Cat` (object return)

The result is passed as a `StableRef<Cat>` pinned `COpaquePointer`. C# wraps it in a `Cat` instance (which takes ownership of the `IntPtr` and disposes via `cat_dispose`).

```csharp
public static Task<Cat> FetchCatAsync(int id)
{
    var tcs = new TaskCompletionSource<Cat>(TaskCreationOptions.RunContinuationsAsynchronously);
    GCHandle tcsHandle = GCHandle.Alloc(tcs);
    AsyncCompletion callback = null!;
    GCHandle callbackHandle = default;
    callback = (resultPtr, errorPtr, userData) =>
    {
        callbackHandle.Free();
        var t = (TaskCompletionSource<Cat>)GCHandle.FromIntPtr(userData).Target!;
        GCHandle.FromIntPtr(userData).Free();
        if (errorPtr != IntPtr.Zero)
        {
            IntPtr msgPtr = Native_UnwrapString(errorPtr);
            string msg = Marshal.PtrToStringUTF8(msgPtr)!;
            Native_Dispose(errorPtr);
            t.SetException(new KotlinException(msg));
        }
        else
            t.SetResult(new Cat(resultPtr)); // Cat takes ownership; disposes via cat_dispose
    };
    callbackHandle = GCHandle.Alloc(callback);
    Native_FetchCatAsync(id, callback, GCHandle.ToIntPtr(tcsHandle));
    return tcs.Task;
}
```

#### `suspend fun saveName(name: String)` (Unit return)

```csharp
public static Task SaveNameAsync(string name)
{
    var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
    GCHandle tcsHandle = GCHandle.Alloc(tcs);
    AsyncUnitCompletion callback = null!;
    GCHandle callbackHandle = default;
    callback = (errorPtr, userData) =>
    {
        callbackHandle.Free();
        var t = (TaskCompletionSource<bool>)GCHandle.FromIntPtr(userData).Target!;
        GCHandle.FromIntPtr(userData).Free();
        if (errorPtr != IntPtr.Zero)
        {
            // ... same error handling
        }
        else
            t.SetResult(true);
    };
    callbackHandle = GCHandle.Alloc(callback);
    Native_SaveNameAsync(name, callback, GCHandle.ToIntPtr(tcsHandle));
    return tcs.Task; // exposed as Task, not Task<bool>
}
```

Unit-returning suspend functions use a simplified callback signature `(errorPtr, userData)` — no result pointer needed.

#### `class Service { suspend fun fetch(): String }`

The member function export takes the object handle as the first parameter (same as all other member functions):

```kotlin
@CName("service_fetch_async")
fun serviceFetchAsync(
    handle: COpaquePointer,
    callback: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer) -> Unit>>,
    userData: COpaquePointer,
) {
    val service = handle.asStableRef<Service>().get()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            val result = service.fetch()
            callback(StableRef.create(result).asCPointer(), nativeNullPtr, userData)
        } catch (e: Throwable) {
            callback(nativeNullPtr, StableRef.create(e.message ?: "error").asCPointer(), userData)
        }
    }
}
```

```csharp
public class Service : IDisposable
{
    internal IntPtr _handle;

    public Task<string> FetchAsync()
    {
        var tcs = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        // ... same GCHandle + callback pattern, passing _handle to native
    }
}
```

### Callback signature conventions

Two delegate shapes cover all cases:

| Kotlin return | C callback signature |
|---|---|
| `String`, primitive, object | `(resultPtr: COpaquePointer, errorPtr: COpaquePointer, userData: COpaquePointer) -> Unit` |
| `Unit` | `(errorPtr: COpaquePointer, userData: COpaquePointer) -> Unit` |

These translate to two C# delegate types:
```csharp
[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
private delegate void AsyncCompletion(IntPtr result, IntPtr error, IntPtr userData);

[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
private delegate void AsyncUnitCompletion(IntPtr error, IntPtr userData);
```

### Naming convention

Generated async methods carry the `Async` suffix: `FetchAsync()`, `SaveNameAsync()`. This follows .NET's established convention and allows a class to have both a synchronous and async version of the same operation without naming conflicts.

### Error type

Kotlin exceptions become a `KotlinException` on the C# side. The exception message is the `Throwable.message` string (passed as a `StableRef<String>` through the error pointer). A shared `KotlinException : Exception` class is generated alongside the marshal helpers.

### GCHandle lifetime

- `tcsHandle` (holding the `TaskCompletionSource`): freed inside the callback, after extracting the reference
- `callbackHandle` (holding the delegate): freed at the start of the callback, before the delegate reference can be dropped by Kotlin's side
- If Kotlin's coroutine scope is cancelled before completing (e.g., process exit), the callback may never fire and the `GCHandle`s will leak — this is acceptable for v1 (same as any unreachable `IDisposable`)

## Consequences

### New CIR nodes needed

- `CirSuspendMethod` — a `CirMethod` variant with `isAsync = true`, `asyncReturnType: String`
- `CirAsyncHelper` — generates the two shared delegate types and `KotlinException` class

### New Kotlin export pattern

Suspend functions detected by KSP via the `SUSPEND` modifier get a non-suspend `@CName` wrapper generated by a new `SuspendFunctionExports.kt`. The wrapper:
- Accepts `(callback, userData)` parameters appended after the regular parameters
- Launches `CoroutineScope(Dispatchers.Default).launch { ... }`
- Calls the callback in the try/catch with result/error StableRefs

### CirRenderer changes

- `renderSuspendMethod` renders the `[DllImport]` for the native export, constructs `GCHandle`/`TaskCompletionSource`/delegate wiring, and returns `Task<T>` or `Task`
- `renderAsyncHelper` renders the two shared delegate types and `KotlinException`

### New C# `using` directives

Generated files with async methods need `System.Threading.Tasks` added to usings (in addition to existing `System` and `System.Runtime.InteropServices`).

### Scope: what's in v1 vs deferred

**v1 (Phase 5):**
- Top-level suspend functions
- Suspend member functions on classes
- `String`, primitive, and object return types
- `Unit` return
- Exception propagation as `KotlinException`

**Deferred (post-Phase 5):**
- **Cancellation** — mapping `CancellationToken` to `Job.cancel()` requires passing a cancel function pointer back to C# and wiring it to the `CancellationToken.Register` callback. Complex; warrants its own ADR.
- **Suspend lambdas** (`suspend () -> R`) — extends the lambda bridge (ADR-012); a `KotlinSuspendFunc<R>` type whose `InvokeAsync()` returns `Task<R>`
- **Flow<T>** — maps to `IAsyncEnumerable<T>`; requires a subscription/cancellation model; significantly more complex
- **Suspend extension functions** — same pattern as regular suspend functions; can be added alongside extension function support
- **Nullable suspend returns** (`suspend fun (): String?`) — requires the same two-call pattern as ADR-002 but async; deferred to keep v1 focused
