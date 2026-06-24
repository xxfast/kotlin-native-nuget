# ADR-026: Flow<T> mapping — cold streams → IAsyncEnumerable<T>

## Status

Proposed

## Context

Kotlin `Flow<T>` is the standard mechanism for expressing asynchronous streams of values in Kotlin. It is a cold stream: collection (subscription) does not begin until a terminal operator such as `collect` is called. The bridge must expose Flow in a way that C# developers find natural and ergonomic.

This ADR covers `Flow<T>` (cold, unicast streams). `SharedFlow<T>` and `StateFlow<T>` (hot streams with multicasting/replay) have additional semantics and are explicitly deferred.

### What Kotlin/Native produces for Flow

`Flow<T>` is a generic interface (`interface Flow<out T>`) with a single suspend method:

```kotlin
interface Flow<out T> {
    suspend fun collect(collector: FlowCollector<T>)
}
```

It **cannot be directly exported with `@CName`** for two reasons:
1. It is a generic interface with a type parameter — Kotlin/Native cannot represent `Flow<T>` directly in a C header.
2. Its only method, `collect`, is a `suspend fun` that takes a `FlowCollector<T>` (another generic interface) — neither CPS signatures nor generic Kotlin interfaces can be expressed in C-compatible ABI.

The same constraint that blocked direct `@CName` export of `suspend fun` (ADR-019) applies here, compounded by generics. The bridge layer must wrap the `collect` call in a coroutine and expose it through C-compatible function pointers.

### How other Kotlin interop targets handle Flow

#### Java (JVM)

Kotlin does not provide built-in Java-facing Flow adapters in the core library. The recommended approach is `kotlinx-coroutines-reactive`, which provides `Flow<T>.asPublisher()` (Reactive Streams `Publisher<T>`) and `Publisher<T>.asFlow()`. Java consumers use the Reactive Streams or RxJava 2/3 wrappers (`Flow<T>.asFlowable()`). There is no standard "Java idiom" for Flow — it is an internal Kotlin coroutines concept, and JVM callers are expected to use reactive library adapters.

#### ObjC/Swift Export (built-in)

The built-in Kotlin/Native ObjC export does **not** handle `Flow<T>` specially. A `Flow<T>` property or return type appears in the ObjC header as an opaque `id` (the Kotlin object reference), without any async or streaming semantics. There is no generated `for await` support or cancellation wiring.

#### SKIE (Touchlab — third-party Swift enhancement)

SKIE is the most relevant reference implementation for this problem. It maps:
- `Flow<T>` → `SkieSwiftFlow<T>` (implements Swift `AsyncSequence`)
- `StateFlow<T>` → `SkieSwiftStateFlow<T>`
- `SharedFlow<T>` → `SkieSwiftSharedFlow<T>`
- `MutableStateFlow<T>` / `MutableSharedFlow<T>` → corresponding mutable variants

Key design decisions from SKIE:
- **Two-way cancellation**: Cancelling the Swift `Task` (which wraps the `for await...in` loop) cancels the Kotlin coroutine running `collect`. No `CancellationException` is thrown — Swift exits the loop cleanly.
- **Error propagation limitation**: SKIE's documentation explicitly warns that "custom exceptions originating in `Flow` cannot be propagated to Swift and will cause a runtime crash." SKIE sacrifices error propagation for the sake of a clean, exception-free Swift API.
- **Type preservation**: `Flow<String>` → `SkieSwiftFlow<String>` (generic type argument preserved, no erasure).
- **Bridge mechanism**: SKIE inserts Kotlin/Native ObjC bridging code that maps the Kotlin coroutine context to Swift's structured concurrency. This is not directly replicable at a raw C boundary.

#### Swift Export (Alpha — official, Kotlin 2.1.0+)

Swift Export is still in early stages and does not yet document Flow mapping. The design intent (based on the roadmap and comparison with SKIE) is to map Flow to Swift's `AsyncSequence`. The feature is not stable and Flow mapping may not be implemented yet.

#### Kotlin/JS

`@JsExport` does not support `Flow<T>` directly. Developers wrap flows manually with `GlobalScope.produce { flow.collect { channel.send(it) } }` to expose them as coroutine channels, or use third-party bridge libraries. There is no standard `@JsExport` treatment for Flow.

### What's idiomatic in C#

The C# standard for cold, asynchronous pull streams is `IAsyncEnumerable<T>` (added in C# 8.0 / .NET Standard 2.1):

```csharp
public interface IAsyncEnumerable<out T>
{
    IAsyncEnumerator<T> GetAsyncEnumerator(CancellationToken cancellationToken = default);
}

public interface IAsyncEnumerator<out T> : IAsyncDisposable
{
    ValueTask<bool> MoveNextAsync();
    T Current { get; }
}
```

Consumers use `await foreach`:

```csharp
await foreach (var item in GetUpdatesAsync())
{
    Console.WriteLine(item);
}
```

Cancellation is passed via `WithCancellation(token)` or through the `[EnumeratorCancellation]` attribute on `async IAsyncEnumerable` methods:

```csharp
await foreach (var item in GetUpdatesAsync().WithCancellation(cts.Token))
{
    Console.WriteLine(item);
}
```

`IAsyncEnumerable<T>` is explicitly a **pull model**: the consumer controls the pace by calling `MoveNextAsync()`. The producer (Kotlin's Flow) is a **push model**: items are emitted as fast as `emit()` is called. The bridge must convert push to pull.

#### Alternative: IObservable<T> (Rx.NET)

`IObservable<T>` is .NET's built-in push/reactive pattern. It maps naturally to Kotlin Flow's push model (both are push). However:
- `IObservable<T>` requires `System.Reactive` (Rx.NET) for any useful operators — it is not a zero-dependency choice.
- Most modern C# code prefers `IAsyncEnumerable<T>` for async streams and `IObservable<T>` for event-driven reactive pipelines.
- .NET's own documentation explicitly recommends `IAsyncEnumerable<T>` for async pull-based sequences and `IObservable<T>` for push-based event notification — Flow is cold/pull in its consumer contract (the caller controls when to subscribe), which makes `IAsyncEnumerable<T>` the better fit.
- SKIE chose `AsyncSequence` (Swift's pull-based equivalent of `IAsyncEnumerable<T>`), not a reactive push model.
- `await foreach` is zero-dependency standard C#; `IObservable<T>` requires an `IObserver<T>` implementation or Rx.NET.

#### Alternative: Channel<T> / ChannelReader<T>

`System.Threading.Channels.Channel<T>` is a producer-consumer pipeline type. `ChannelReader<T>.ReadAllAsync()` returns `IAsyncEnumerable<T>`. Using `Channel<T>` as an intermediate buffer is a **bridge implementation detail** — it is not the consumer-visible API. The consumer still sees `IAsyncEnumerable<T>`.

### The bridge constraint: push vs pull across the C boundary

The core challenge is that Kotlin Flow pushes values, but `IAsyncEnumerable<T>` expects a pull model where C# calls `MoveNextAsync()` and waits for the next value.

The C boundary only supports raw function pointers and `IntPtr`. There is no shared concurrency model. The bridge must:

1. **Start collection on demand**: When `MoveNextAsync()` is first called, launch `flow.collect { ... }` in a Kotlin coroutine.
2. **Buffer one item at a time**: Each emitted value must be held until `MoveNextAsync()` requests it. A `Channel<T>` (unbounded or bounded-1) is the natural buffer between Kotlin's push and C#'s pull.
3. **Synchronize producer and consumer**: Kotlin emits a value and suspends; C# reads it via `MoveNextAsync()` and suspends waiting for the next one. A `Channel<T>` or equivalent provides this rendezvous.
4. **Signal completion**: When `flow.collect` returns normally (flow exhausted), signal end-of-stream so `MoveNextAsync()` returns `false`.
5. **Propagate exceptions**: When `flow.collect` throws (flow failed), propagate the exception to the awaiting `MoveNextAsync()`.
6. **Propagate cancellation**: When the C# `CancellationToken` is cancelled, cancel the Kotlin coroutine running `collect`.

The simplest mechanism that satisfies all these requirements at the C boundary uses three callbacks:

- `onNext(itemPtr, userData)` — Kotlin calls this for each emitted value. C# stores the item and signals that `MoveNextAsync()` should return `true`.
- `onComplete(userData)` — Kotlin calls this when `collect` returns normally. C# signals `MoveNextAsync()` to return `false`.
- `onError(errorPtr, userData)` — Kotlin calls this when `collect` throws. C# faults the current `MoveNextAsync()` awaiter.

This is equivalent to an `IObserver<T>` (`OnNext`, `OnCompleted`, `OnError`) at the C boundary, but exposed to C# consumers as `IAsyncEnumerable<T>`.

### Backpressure

Kotlin Flow has natural backpressure: `emit()` is a suspend function that suspends the producer until the collector resumes. The bridge preserves this:

- Kotlin's `emit()` calls the `onNext` callback and then **suspends** (via a `Channel` or a `CompletableDeferred`) until C# acknowledges by calling a `nuget_flow_next(subscriptionHandle)` export.
- C# calls `nuget_flow_next` from the `MoveNextAsync()` implementation after consuming the current item.
- This makes the producer-consumer rhythm: Kotlin emits → Kotlin suspends → C# reads via MoveNextAsync → C# calls flow_next → Kotlin resumes and emits next item.

Alternatively, an **unbounded Channel<T>** can be used on the Kotlin side to decouple producer and consumer (no backpressure). This is simpler but consumes memory if the producer is faster than the consumer. For v1, the unbounded channel approach is simpler and matches how SKIE handles it (no explicit backpressure between Swift and Kotlin).

### Subscription lifecycle

A `Flow<T>` subscription (a single `collect` call) must be represented as an opaque handle so C# can:
- Cancel it (via `CancellationToken`)
- Dispose it (via `IAsyncEnumerator<T>.DisposeAsync()`)

This is analogous to the existing `Job` handle (ADR-022). The Kotlin coroutine running `collect` is a `Job`; its `StableRef` handle is the subscription handle.

## Alternatives Considered

### 1. Channel<T>-buffered push bridge with IAsyncEnumerable<T> wrapper (chosen)

Kotlin launches a coroutine that calls `flow.collect { emit(value) }` and for each emitted value, writes to an unbounded `Channel`. The channel's `ReadAllAsync()` is wrapped in a C# class that implements `IAsyncEnumerable<T>`. Cancellation of the `CancellationToken` passed to `GetAsyncEnumerator` cancels the Kotlin coroutine.

**Kotlin side (generated, for `val updates: Flow<String>`):**

```kotlin
@CName("myservice_get_updates_collect")
fun export_myservice_get_updates_collect(
    handle: COpaquePointer,
    scopeHandle: COpaquePointer,
    onNextPtr: COpaquePointer,         // callback: (itemPtr, isCancelled, userData) -> Unit
    onCompletePtr: COpaquePointer,     // callback: (userData) -> Unit
    onErrorPtr: COpaquePointer,        // callback: (errorPtr, userData) -> Unit
    userData: COpaquePointer,
): COpaquePointer {                    // returns Job StableRef (for cancellation)
    val obj = handle.asStableRef<MyService>().get()
    val scope = scopeHandle.asStableRef<CoroutineScope>().get()
    val onNext = onNextPtr.reinterpret<CFunction<
        (COpaquePointer?, Byte, COpaquePointer) -> Unit>>()
    val onComplete = onCompletePtr.reinterpret<CFunction<
        (COpaquePointer) -> Unit>>()
    val onError = onErrorPtr.reinterpret<CFunction<
        (COpaquePointer?, COpaquePointer) -> Unit>>()

    val job = scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            obj.updates.collect { value ->
                val itemRef = StableRef.create(value as Any).asCPointer()
                onNext.invoke(itemRef, 0.toByte(), userData)
            }
            onComplete.invoke(userData)
        } catch (e: CancellationException) {
            onNext.invoke(null, 1.toByte(), userData) // signal cancellation via onNext with isCancelled=1
            throw e
        } catch (e: Throwable) {
            val errRef = StableRef.create(
                Pair(e::class.qualifiedName ?: "UnknownException", e.message ?: "Kotlin error")
            ).asCPointer()
            onError.invoke(errRef, userData)
        }
    }
    return StableRef.create(job).asCPointer()
}
```

**C# side — `KotlinFlow<T>` class (generated once as a shared helper):**

```csharp
// KotlinFlow<T> implements IAsyncEnumerable<T>.
// It is returned by Flow property getters and acts as a lazy async stream.
// Each call to GetAsyncEnumerator starts a new collection (Flow is cold).
public class KotlinFlow<T> : IAsyncEnumerable<T>
{
    private readonly Func<NugetFlowCallbacks, IntPtr> _startCollect;

    internal KotlinFlow(Func<NugetFlowCallbacks, IntPtr> startCollect)
    {
        _startCollect = startCollect;
    }

    public IAsyncEnumerator<T> GetAsyncEnumerator(CancellationToken cancellationToken = default)
        => new KotlinFlowEnumerator<T>(_startCollect, cancellationToken);
}

internal class KotlinFlowEnumerator<T> : IAsyncEnumerator<T>
{
    private readonly Channel<(T? value, bool isCancelled, Exception? error, bool isComplete)> _channel;
    private readonly CancellationTokenRegistration _cancelReg;
    private IntPtr _jobHandle;
    private bool _done;

    public T Current { get; private set; } = default!;

    internal KotlinFlowEnumerator(
        Func<NugetFlowCallbacks, IntPtr> startCollect,
        CancellationToken cancellationToken)
    {
        _channel = Channel.CreateUnbounded<(T?, bool, Exception?, bool)>(
            new UnboundedChannelOptions { SingleReader = true, SingleWriter = true });

        // Pin callbacks for the lifetime of this enumerator
        NugetFlowCallbacks callbacks = new NugetFlowCallbacks(
            onNext: (itemPtr, isCancelled, userData) =>
            {
                if (isCancelled != 0)
                {
                    _channel.Writer.TryComplete();
                    return;
                }
                T value = NugetMarshal.FromHandle<T>(itemPtr);
                _channel.Writer.TryWrite((value, false, null, false));
            },
            onComplete: (userData) =>
            {
                _channel.Writer.TryComplete();
            },
            onError: (errorPtr, userData) =>
            {
                var (type, msg) = NugetMarshal.FromErrorHandle(errorPtr);
                _channel.Writer.TryComplete(new KotlinException(msg, type));
            }
        );

        _jobHandle = startCollect(callbacks);

        if (cancellationToken.CanBeCanceled)
            _cancelReg = cancellationToken.Register(() => NugetJobNative.Cancel(_jobHandle));
    }

    public async ValueTask<bool> MoveNextAsync()
    {
        if (_done) return false;

        try
        {
            while (await _channel.Reader.WaitToReadAsync())
            {
                if (_channel.Reader.TryRead(out var item))
                {
                    Current = item.value!;
                    return true;
                }
            }
            _done = true;
            return false;
        }
        catch (ChannelClosedException ex) when (ex.InnerException is KotlinException ke)
        {
            _done = true;
            throw ke;
        }
    }

    public async ValueTask DisposeAsync()
    {
        _cancelReg.Dispose();
        NugetJobNative.Cancel(_jobHandle);
        NugetJobNative.Dispose(_jobHandle);
        _jobHandle = IntPtr.Zero;
        // Drain any remaining items so the channel can be GC'd
        _channel.Writer.TryComplete();
        await _channel.Reader.Completion;
    }
}
```

**Consumer API (what C# developers write):**

```csharp
// Flow property
await foreach (var update in service.Updates)
    Console.WriteLine(update);

// With cancellation
await foreach (var update in service.Updates.WithCancellation(cts.Token))
    Console.WriteLine(update);

// Flow return from function
await foreach (var result in service.GetResults("query"))
    Console.WriteLine(result);

// Flow as suspend function return (rare — collect the collected Flow)
await foreach (var item in await service.StreamResultsAsync())
    Console.WriteLine(item);
```

**Pros:**
- `IAsyncEnumerable<T>` is the idiomatic C# 8.0+ pattern for cold async streams — `await foreach` works out of the box.
- `Channel<T>` is the standard .NET decoupling primitive for producer-consumer. `ReadAllAsync()` / `WaitToReadAsync()` gives a clean `MoveNextAsync()` without implementing the interface manually.
- The three-callback ABI (`onNext`, `onComplete`, `onError`) is a clean C boundary — no shared state between Kotlin and C#.
- Cancellation reuses the existing `nuget_job_cancel` / `nuget_job_dispose` infrastructure from ADR-022.
- Error propagation is natural — exceptions cross through `Channel.Writer.TryComplete(exception)` and surface as thrown exceptions in `MoveNextAsync()`.
- Completion is natural — `Channel.Writer.TryComplete()` causes `WaitToReadAsync()` to return `false`, so `MoveNextAsync()` returns `false`.
- SKIE chose this same paradigm (push→AsyncSequence pull), validating the approach.
- `KotlinFlow<T>` is a shared helper class generated once (like `KotlinFunc<T>`), not per-class. Flow property getters generate a small per-property export that calls the shared infrastructure.

**Cons:**
- Three callbacks per subscription export (vs two for suspend functions). However, these can be packed into a single `NugetFlowCallbacks` struct or passed as three separate `IntPtr`s.
- Unbounded `Channel<T>` consumes memory if Kotlin emits faster than C# consumes. For v1 this is acceptable; bounded channels require a synchronization round-trip for backpressure.
- `KotlinFlowEnumerator<T>` holds `GCHandle`s for the callbacks for the lifetime of the enumerator — not just for the duration of a single call (unlike suspend functions). This is safe but means pinned memory lives longer.
- `KotlinFlow<T>` is not itself `IDisposable` — each call to `GetAsyncEnumerator` starts a new independent subscription. This is correct semantics for cold Flow, but callers must `DisposeAsync()` or use `await foreach` (which calls `DisposeAsync()` in the generated `finally` block).

### 2. Direct callback-per-item bridge without Channel<T>

Instead of using `Channel<T>` on the C# side, implement `IAsyncEnumerator<T>` directly using `TaskCompletionSource<bool>` — one TCS per `MoveNextAsync()` call. Kotlin's `onNext` callback sets the TCS result.

**Kotlin side:**
Same as option 1 — three callbacks.

**C# side:**
```csharp
// Manual IAsyncEnumerator<T> without Channel<T>:
public async ValueTask<bool> MoveNextAsync()
{
    var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
    // register: next onNext call -> tcs.SetResult(true); next onComplete -> tcs.SetResult(false); next onError -> tcs.SetException(...)
    // ... complex state machine to route the next callback invocation to this TCS ...
    return await tcs.Task;
}
```

**Pros:**
- No `Channel<T>` dependency.
- Can implement backpressure naturally (Kotlin suspends between `onNext` calls until `MoveNextAsync` signals readiness).

**Cons:**
- Requires a complex state machine to route callbacks to the right `TaskCompletionSource<bool>`. Each `MoveNextAsync()` call must register itself as "the next waiter" and the callback must locate and complete the right TCS.
- Thread safety is harder to reason about — `MoveNextAsync()` and the callbacks run on different threads.
- The `Channel<T>` approach handles all this correctly out of the box (`Channel<T>` is thread-safe and designed for exactly this producer-consumer use case).
- Harder to implement correctly; more likely to have subtle race conditions.

### 3. IObservable<T> (Rx.NET / push model)

Map `Flow<T>` to `IObservable<T>`. Kotlin launches a coroutine and calls `OnNext`, `OnCompleted`, and `OnError` on the C#-provided `IObserver<T>`. The subscription is returned as `IDisposable`.

**C# consumer API:**
```csharp
service.Updates.Subscribe(
    onNext: update => Console.WriteLine(update),
    onError: ex => Console.WriteLine($"Error: {ex.Message}"),
    onCompleted: () => Console.WriteLine("Done"));
```

**Pros:**
- Push model matches Flow's push semantics exactly — no producer/consumer impedance.
- `IObservable<T>` is in `System` (no extra dependencies for the interface itself).
- Standard `.NET` infrastructure for push-based streams.

**Cons:**
- **`IObservable<T>` without Rx.NET is nearly useless** — the interface provides no operators, no `await` support, no backpressure. C# developers use `System.Reactive` (Rx.NET) for any real work, which is a significant dependency.
- **Not composable with `async`/`await`** — subscribers are callbacks, not awaitables. C# developers in modern code use `IAsyncEnumerable<T>` with `await foreach`, not `IObservable<T>` with callbacks.
- **No backpressure**: `IObservable<T>` has no mechanism to signal the producer to slow down. With Flow's natural backpressure semantics, this is a loss of an important feature.
- SKIE chose `AsyncSequence` (the pull model) over a reactive push model. This validates `IAsyncEnumerable<T>` as the right choice.
- .NET's own documentation explicitly recommends `IAsyncEnumerable<T>` for async pull sequences and reserves `IObservable<T>` for event-driven reactive pipelines.

### 4. Expose Flow as KotlinFlow opaque handle + CollectAsync() method (no IAsyncEnumerable)

Instead of generating an `IAsyncEnumerable<T>`, expose the Flow as an opaque `KotlinFlow<T>` wrapper with a `CollectAsync(Action<T> onNext, ...)` method, or as an explicit `Subscribe(IObserver<T>)` API.

**Pros:**
- Simpler bridge — no `Channel<T>`, no `IAsyncEnumerable<T>` implementation.

**Cons:**
- **Not idiomatic C#** — forces callers to use callbacks instead of `await foreach`.
- Harder to use: no LINQ, no `await foreach`, no cancellation via `WithCancellation`.
- The consumer API is worse than `IAsyncEnumerable<T>` in every way.
- ADR-012 already rejected the "opaque handle with manual invoke" pattern for lambdas in favor of typed C# APIs where possible.

## Decision

Use **option 1: Channel<T>-buffered push bridge with `IAsyncEnumerable<T>` wrapper**.

### Kotlin export signature

For each `Flow<T>` property or return type, generate a `*_collect` export:

```kotlin
@CName("{prefix}_{name}_collect")
fun export_{prefix}_{name}_collect(
    handle: COpaquePointer,             // object handle (if class member)
    scopeHandle: COpaquePointer,        // scope handle (same as suspend methods)
    onNextPtr: COpaquePointer,          // (itemPtr: COpaquePointer?, isCancelled: Byte, userData: COpaquePointer) -> Unit
    onCompletePtr: COpaquePointer,      // (userData: COpaquePointer) -> Unit
    onErrorPtr: COpaquePointer,         // (errorPtr: COpaquePointer?, userData: COpaquePointer) -> Unit
    userData: COpaquePointer,
): COpaquePointer                       // Job StableRef (for cancellation via nuget_job_cancel)
```

For top-level `Flow<T>` returns (functions), `handle` and `scopeHandle` are omitted; the coroutine uses `CoroutineScope(Dispatchers.Default)` with no structured lifecycle (same as top-level suspend functions in ADR-022).

### Callback conventions

| Event         | Callback        | Signature                                                              | Action                                |
|---------------|-----------------|------------------------------------------------------------------------|---------------------------------------|
| Next item     | `onNext`        | `(itemPtr: COpaquePointer?, isCancelled: Byte, userData: COpaquePointer) -> Unit` | `isCancelled=0`: item available; `isCancelled=1`: stream cancelled |
| Completion    | `onComplete`    | `(userData: COpaquePointer) -> Unit`                                   | Flow exhausted normally               |
| Error         | `onError`       | `(errorPtr: COpaquePointer?, userData: COpaquePointer) -> Unit`        | Flow threw an exception               |

`onNext` with `isCancelled=1` reuses the existing cancellation signal pattern from ADR-021/022 — null `itemPtr` + `isCancelled=1` means "cancelled, no item". This avoids a fourth callback for cancellation.

### Generated Kotlin body (for `val updates: Flow<String>` on class `MyService`)

```kotlin
@CName("myservice_get_updates_collect")
fun export_myservice_get_updates_collect(
    handle: COpaquePointer,
    scopeHandle: COpaquePointer,
    onNextPtr: COpaquePointer,
    onCompletePtr: COpaquePointer,
    onErrorPtr: COpaquePointer,
    userData: COpaquePointer,
): COpaquePointer {
    val obj = handle.asStableRef<MyService>().get()
    val scope = scopeHandle.asStableRef<CoroutineScope>().get()
    val onNext = onNextPtr.reinterpret<CFunction<
        (COpaquePointer?, Byte, COpaquePointer) -> Unit>>()
    val onComplete = onCompletePtr.reinterpret<CFunction<
        (COpaquePointer) -> Unit>>()
    val onError = onErrorPtr.reinterpret<CFunction<
        (COpaquePointer?, COpaquePointer) -> Unit>>()
    val job = scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            obj.updates.collect { value ->
                val itemRef = StableRef.create(value as Any).asCPointer()
                onNext.invoke(itemRef, 0.toByte(), userData)
            }
            onComplete.invoke(userData)
        } catch (e: CancellationException) {
            onNext.invoke(null, 1.toByte(), userData)
            throw e
        } catch (e: Throwable) {
            val errRef = StableRef.create(
                Pair(e::class.qualifiedName ?: "UnknownException", e.message ?: "Kotlin error")
            ).asCPointer()
            onError.invoke(errRef, userData)
        }
    }
    return StableRef.create(job).asCPointer()
}
```

### Generated C# property getter

```csharp
public KotlinFlow<string> Updates
{
    get
    {
        if (_handle == IntPtr.Zero)
            throw new ObjectDisposedException(nameof(MyService));
        IntPtr handle = _handle;
        IntPtr scopeHandle = GetOrCreateScope();
        return new KotlinFlow<string>(callbacks =>
        {
            return Native_GetUpdatesCollect(
                handle, scopeHandle,
                callbacks.OnNextPtr, callbacks.OnCompletePtr, callbacks.OnErrorPtr,
                IntPtr.Zero); // userData is managed inside KotlinFlowEnumerator
        });
    }
}

[DllImport("sample", CallingConvention = CallingConvention.Cdecl,
    EntryPoint = "myservice_get_updates_collect")]
private static extern IntPtr Native_GetUpdatesCollect(
    IntPtr handle, IntPtr scopeHandle,
    IntPtr onNextPtr, IntPtr onCompletePtr, IntPtr onErrorPtr, IntPtr userData);
```

### Shared C# helper — KotlinFlow<T> and KotlinFlowEnumerator<T>

`KotlinFlow<T>` and `KotlinFlowEnumerator<T>` are generated once (like `KotlinFunc<T>`) and placed in the same namespace as all other shared helpers. They require `System.Threading.Channels` and `System.Collections.Generic`.

`NugetFlowCallbacks` is a helper struct that pins the three delegate instances and exposes their `IntPtr`s for passing to the native export. All three `GCHandle`s are freed in `DisposeAsync()` on the enumerator.

### Cancellation flow

1. Caller passes `CancellationToken` via `.WithCancellation(token)` or `GetAsyncEnumerator(token)`.
2. `KotlinFlowEnumerator` registers: `token.Register(() => NugetJobNative.Cancel(_jobHandle))`.
3. `NugetJobNative.Cancel` calls `job.cancel()` on the Kotlin coroutine running `collect`.
4. Kotlin's `CancellationException` is caught; `onNext(null, 1, userData)` is called.
5. `onNext` callback calls `_channel.Writer.TryComplete()`.
6. `WaitToReadAsync()` in `MoveNextAsync()` returns `false` → `MoveNextAsync()` returns `false`.
7. `await foreach` exits cleanly (no exception thrown — matching SKIE's behavior).
8. `DisposeAsync()` disposes the cancel registration and the job handle.

### Error propagation

1. Kotlin flow throws an exception during `collect`.
2. `onError(errorPtr, userData)` is called with the error `Pair<String,String>` handle.
3. `onError` callback calls `_channel.Writer.TryComplete(new KotlinException(message, type))`.
4. `WaitToReadAsync()` throws `ChannelClosedException` with `InnerException = KotlinException`.
5. `MoveNextAsync()` catches and re-throws the `KotlinException`.
6. `await foreach` propagates the exception to the caller.

### Completion

1. Kotlin's `flow.collect` lambda returns normally (flow exhausted).
2. `onComplete(userData)` is called.
3. `onComplete` callback calls `_channel.Writer.TryComplete()` (no exception).
4. `WaitToReadAsync()` returns `false` → `MoveNextAsync()` returns `false`.
5. `await foreach` exits cleanly.

### Consumer API

```csharp
using var service = new MyService();

// Simple consumption
await foreach (var update in service.Updates)
    Console.WriteLine(update);

// With cancellation
var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
await foreach (var update in service.Updates.WithCancellation(cts.Token))
    Console.WriteLine(update);

// Flow from function return
await foreach (var result in service.GetResults("query"))
    Console.WriteLine(result);

// Multiple independent subscriptions (Flow is cold — each foreach starts a new collect)
var sub1 = service.Updates.GetAsyncEnumerator();
var sub2 = service.Updates.GetAsyncEnumerator();
```

### Property vs function naming

- `val updates: Flow<String>` → C# property `public KotlinFlow<string> Updates { get; }` (no method rename)
- `fun getUpdates(): Flow<String>` → C# method `public KotlinFlow<string> GetUpdates()` (synchronous; returns `KotlinFlow<string>`)
- `suspend fun streamResults(): Flow<Result>` → C# method `public Task<KotlinFlow<Result>> StreamResultsAsync()` (async outer call returns a flow; rare pattern)

The property and function wrappers both return `KotlinFlow<T>`, which is lazy (no collection starts until `await foreach` or `GetAsyncEnumerator()`).

## Consequences

### New CIR nodes needed

- `CirFlowHelper` — generates `KotlinFlow<T>`, `KotlinFlowEnumerator<T>`, and `NugetFlowCallbacks` in the shared namespace.
- `CirFlowNativeHelper` — generates the shared `[DllImport]` helper class (like `NugetJobNative`) for the flow callbacks.
- `CirProperty` (or `CirMethod`) — `isFlow: Boolean` flag causes the getter to return `KotlinFlow<T>` instead of the unwrapped type and emit the `_collect` DllImport.

### New Kotlin export pattern

Flow properties detected by KSP via type `kotlinx.coroutines.flow.Flow` get a `*_collect` wrapper generated by a new `FlowExports.kt`. KSP identifies the Flow type by checking `qualifiedName == "kotlinx.coroutines.flow.Flow"`.

### Flow type detection in KSP

```kotlin
internal val FLOW_TYPES = setOf(
    "kotlinx.coroutines.flow.Flow",
    // SharedFlow and StateFlow are deferred; listed here for future reference:
    // "kotlinx.coroutines.flow.SharedFlow",
    // "kotlinx.coroutines.flow.StateFlow",
)
```

### New C# using directives

Files with Flow properties need `System.Threading.Channels` and `System.Collections.Generic` in addition to existing usings.

### Scope and structured concurrency

Flow collection uses the class's `CoroutineScope` (same as suspend methods). This means:
- `Dispose()` cancels all in-flight collections (same as in-flight suspend calls — ADR-021).
- `DisposeAsync()` drains in-flight collections before releasing (ADR-025 `nuget_scope_drain` already covers this because flow collection jobs are children of the scope).
- `ObjectDisposedException` guard in the property getter (`_handle == IntPtr.Zero`) prevents new collections after dispose.

### Scope: what's in v1 vs deferred

**v1 (this ADR):**
- `Flow<T>` as a class property (getter only — `val updates: Flow<String>`)
- `Flow<T>` as a function return type (non-suspend function returning Flow)
- Element types: primitives, `String`, object types (same as all other return types)
- Cancellation via `CancellationToken` / `WithCancellation()`
- Error propagation as `KotlinException`
- Clean completion when flow exhausts

**Deferred:**
- **`SharedFlow<T>`** — hot stream with subscribers; semantically different from cold Flow; needs separate mapping (may be `IAsyncEnumerable<T>` with replay, or `IObservable<T>`, or a custom type).
- **`StateFlow<T>`** — hot stream with always-current-value; may map to a C# property + change notification; needs separate analysis.
- **`Flow<T>` as a function parameter** — passing a C#-implemented `IAsyncEnumerable<T>` into Kotlin as a `Flow<T>` (bidirectional); requires Phase 6 C#→Kotlin support.
- **`suspend fun` returning `Flow<T>`** — the outer `suspend` rarely matters (returning a Flow is not a suspend operation); treat as non-suspend for v1.
- **Nullable `Flow<T>?`** — deferred (requires the two-call nullable pattern from ADR-002, combined with the flow export).
- **Backpressure** — bounded `Channel<T>` with explicit resume signaling; deferred for v1 (unbounded channel is safe for most use cases).
- **`Flow<T>` as a generic type argument** — e.g., `Box<Flow<String>>`; deferred (generics containing Flow types).
