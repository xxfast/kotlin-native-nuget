# ADR-037: Stored callbacks — event handlers and observer registrations

## Status

Proposed

## Context

ADR-036 introduced per-call reverse interop: C# passes a lambda to a Kotlin function, Kotlin
invokes it during the call, and the `GCHandle` is freed in a `finally` block when the call
returns. ROADMAP line 102 extends this to **stored callbacks**: lambdas that Kotlin holds beyond
the call and invokes later in response to events or state changes.

ADR-036 explicitly deferred this as **Alternative 4 ("Stored callbacks via opaque registry
handle")** with the note:

> *"Stored callbacks (event handlers, observer registrations) — require the registry pattern;
> warrants a dedicated ADR."*

The lifetime management gap is concrete: the `GCHandle` that keeps a C# delegate alive cannot
be freed in a `finally` block if Kotlin stores the function pointer beyond the call. Freeing it
prematurely causes a crash when Kotlin later invokes the dangling pointer.

### ADR-036 Alternative 4 in detail

The deferred alternative proposed a **C#-side callback registry**: a
`ConcurrentDictionary<int, GCHandle>` maintained by C#, issuing integer keys. Kotlin would be
given both the function pointer and its registry key; when Kotlin is done with the callback, it
calls a C#-exported `nuget_callback_free(key)` function that frees the `GCHandle`.

This requires a new Kotlin→C# mechanism (a C# function exported via `[UnmanagedCallersOnly]`
registered at startup) — an additional reverse-interop direction on top of the one ADR-036 already
established.

### How Kotlin/Native exposes stored callbacks at the C boundary

Kotlin/Native has no built-in concept of stored callback lifetime. A Kotlin function that accepts
a lambda and stores it retains the lambda for as long as the Kotlin object lives. At the C
boundary, the function pointer (`COpaquePointer`) must remain valid for the same lifetime. There
is no Kotlin/Native API to query whether a function pointer is still in use.

Source: [Kotlin/Native C interop](https://kotlinlang.org/docs/native-c-interop.html)

### How other Kotlin interop targets handle stored callbacks

#### Java/JVM

Kotlin lambdas compile to SAM-compatible function interfaces on the shared JVM heap. No function
pointer or GCHandle lifetime problem exists — both sides share the GC. Not applicable at a C
boundary.

#### ObjC/Swift Export (built-in)

Kotlin/Native's ObjC export wraps Kotlin closures as ObjC blocks under ARC. The ARC runtime
manages retain/release across the Kotlin/ObjC bridge, giving blocks the same lifetime semantics
as any ObjC heap object. Observer registration (`addObserver:`/`removeObserver:`) is handled by
the ObjC NSNotificationCenter / delegate pattern — no bridge-specific lifetime infrastructure.
Not replicable at a C boundary.

#### SKIE (Touchlab)

SKIE exposes Kotlin `SharedFlow<T>` as `AsyncSequence`. Stored subscriptions are handled through
Swift's structured concurrency task tree, which cancels automatically. For traditional observer
registrations, SKIE relies on the underlying ObjC memory model. No special stored-callback
lifetime mechanism at the ABI level.

#### Kotlin/JS

Both sides share the JS GC. No lifetime management needed.

#### Summary

Every other target benefits from a shared or bridged memory manager. At the C boundary, the
GCHandle is the only mechanism that keeps a C# delegate alive for Kotlin's use. Its lifetime must
be explicitly managed.

### What's idiomatic in C# for observer registration

Three patterns exist in .NET for registering and unregistering handlers:

**1. `event` keyword** (`cat.MoodChanged += handler; cat.MoodChanged -= handler;`)

The standard .NET event pattern. Works well when the caller retains a reference to the handler
delegate. Breaks with anonymous lambdas: `cat.MoodChanged += mood => ...` cannot be unregistered
because the lambda instance is discarded. For a generated bridge, the C# class would need a
per-event dictionary mapping `Delegate → subscriptionHandle`, with thread-safety overhead.

Source: [Events (C# Programming Guide)](https://learn.microsoft.com/en-us/dotnet/csharp/programming-guide/events/)

**2. `IDisposable` subscription** (Rx-style: `IDisposable sub = cat.AddMoodListener(handler)`)

The subscriber receives a disposable token. Calling `sub.Dispose()` unregisters. Works with
anonymous lambdas — the caller holds the token, not the handler reference. Used by Rx.NET's
`Observable.Subscribe`, by `CancellationToken.Register`, and by `IAsyncEnumerator<T>` (which
is itself `IAsyncDisposable`). Source:
[IDisposable (System)](https://learn.microsoft.com/en-us/dotnet/api/system.idisposable)

**3. `event` with custom `add`/`remove` accessors**

Allows a generated class to intercept `+=`/`-=` and call Kotlin exports. But requires tracking
the delegate instance for removal: `remove` must find the subscription registered by `add`.
C# delegate equality is reference equality, so anonymous lambdas still cannot be unregistered
via `-=` after being registered via `+=`. The same dictionary-tracking problem as option 1
applies, but now hidden inside the accessor.

The `IDisposable` subscription pattern solves the anonymous-lambda problem that both `event`
variants share, requires no per-event dictionary on the C# side, and is the established .NET
idiom for subscriptions that outlive a single method call.

### The closest existing precedent: `KotlinFlowEnumerator`

`CirFlowRenderer.kt` already handles a stored-callback scenario: a Flow subscription keeps three
GCHandles (`_onNextHandle`, `_onCompleteHandle`, `_onErrorHandle`) alive for the lifetime of the
enumerator, not just for the duration of a single call. GCHandles are freed in `DisposeAsync()`.
Kotlin never calls back into C# to signal "free the handle." Instead:

- When Kotlin's flow exhausts, it calls `onComplete` → C# drains the channel → `DisposeAsync()`
  frees handles.
- When C# cancels, it calls `NugetJobNative.Cancel(_jobHandle)` → Kotlin fires `onNext` with
  `isCancelled=1` → C# drains and calls `DisposeAsync()`.

In both cases C# controls when GCHandles are freed. Kotlin never calls into C# for cleanup.

Flow is the right reference point for stored callbacks. The difference is:
- Flow has a natural endpoint (`onComplete`/`onError`/cancellation) — handles are freed when the
  stream ends.
- Stored callbacks have no natural endpoint — C# must explicitly unsubscribe.

### The hard lifetime problem restated

For a stored callback:
1. C# creates a delegate, pins it with `GCHandle.Alloc`, obtains its function pointer.
2. C# calls a Kotlin export which stores the function pointer in a Kotlin data structure.
3. Kotlin invokes the function pointer on future events.
4. At some point, C# wants to stop receiving events. It must:
   a. Tell Kotlin not to call the function pointer anymore.
   b. Wait until it is certain Kotlin is not in the middle of calling it.
   c. Free the `GCHandle`.

Step (b) is the critical synchronization point. Two approaches differ on who controls it.

**Registry approach (ADR-036 Alt 4):** Kotlin controls step (b). When Kotlin is done, it calls
`nuget_callback_free(key)` to tell C#. C# frees the GCHandle only when Kotlin says so.

**IDisposable subscription approach:** C# calls Kotlin's `_unsubscribe` synchronously. Kotlin
removes the callback from its store and guarantees no further invocations before returning.
C# frees the GCHandle after `_unsubscribe` returns. C# controls step (b).

### How the `add*`/`remove*` Kotlin pattern enables C#-controlled unsubscription

Kotlin's conventional observer pattern pairs an `add*Listener` function with a `remove*Listener`
function. Crucially, `remove*Listener` takes the **same lambda instance** that was passed to
`add*Listener` (referential equality). The bridge can exploit this: the generated Kotlin export
for `add*` creates a **bridge-lambda** (a Kotlin wrapper closure that holds the C# function
pointer), registers it with the Kotlin object, and returns an opaque handle for that bridge-lambda.
When C# calls `_remove`, the bridge retrieves the original bridge-lambda by handle and passes it to
Kotlin's `remove*Listener`. Kotlin removes it and will not invoke it again.

After `_unsubscribe` returns, C# is guaranteed Kotlin holds no reference to the bridge-lambda, so
the function pointer it captures will not be called. The GCHandle can be freed safely.

### Thread-safety scope for v1

A race can occur if Kotlin invokes the callback from a background thread concurrently with C#
calling `_unsubscribe` on another thread. The bridge-lambda is removed from Kotlin's store
atomically by the Kotlin concurrent map, but an already-dispatched invocation may be mid-flight.

For v1: the guarantee is that **after `_unsubscribe` returns, no new invocations will start**.
Invocations that were already dispatched (mid-flight) may complete. C# must not free the GCHandle
until after `_unsubscribe` returns. This matches the contract of standard Kotlin single-threaded
observer lists (which are the common case: `mutableListOf` is not thread-safe; callers that fire
events from a coroutine dispatcher use structured concurrency for sequencing).

Full concurrent-safe "call Dispose while callback is mid-flight from another thread" is deferred.

## Alternatives Considered

### 1. Registry approach — C# dictionary + Kotlin-callable `nuget_callback_free` (ADR-036 Alt 4)

C# maintains a `ConcurrentDictionary<int, GCHandle>`. On registration, C# allocates a `GCHandle`,
stores it in the registry, and passes both the function pointer and the integer key to Kotlin.
When Kotlin finishes with the callback, it calls the C#-exported `nuget_callback_free(key)` which
frees the GCHandle.

**Pros:**
- Kotlin controls when the GCHandle is freed, so there is no race: the free happens after the last
  invocation.
- The registry is a shared helper generated once per library.
- Works even when Kotlin decides the subscription lifetime (e.g., after a "completion" event).

**Cons:**
- Requires a new Kotlin→C# export (`nuget_callback_free`) implemented via `[UnmanagedCallersOnly]`
  or a registered delegate. This is a second reverse-interop direction on top of what ADR-036
  already established. The project currently has NO Kotlin→C# export mechanism; this would be the
  first.
- Kotlin must remember to call `nuget_callback_free` — this is a discipline requirement on the
  generated Kotlin code (or the user's Kotlin code, if the Kotlin function manages the lifetime).
  Missing a call leaks the GCHandle indefinitely.
- Integer key allocation adds a per-callback indirection.
- C# cannot unsubscribe proactively (C# cannot "push" unsubscription — it must wait for Kotlin to
  decide). This is correct when Kotlin owns the lifetime, but wrong for most observer patterns
  where the C# caller wants to stop receiving events on demand.

### 2. IDisposable subscription handle + `add*`/`remove*` pairing (chosen)

The KSP generator detects pairs of `add{X}` / `remove{X}` (or `subscribe{X}` / `unsubscribe{X}`)
functions that both accept the same lambda type. For each such pair, it generates:

- A Kotlin-side `_subscribe` export that creates a bridge-lambda, registers it in a per-class
  `ConcurrentHashMap`, passes it to the Kotlin `add*` function, and returns an opaque subscription
  handle.
- A Kotlin-side `_unsubscribe` export that retrieves the bridge-lambda by handle, passes it to the
  Kotlin `remove*` function, removes it from the map, and disposes the handle `StableRef`.
- A C# method `Add{X}(Action<T> listener): IDisposable` that creates the delegate, pins it, calls
  `_subscribe`, and returns a `NugetSubscription` whose `Dispose()` calls `_unsubscribe` then frees
  the `GCHandle`.
- `NugetSubscription` is a shared helper generated once.

**Pros:**
- No new Kotlin→C# mechanism. All new exports go in the same Kotlin→C# direction as every other
  export in the project. This is the smallest possible addition to the infrastructure.
- C# controls subscription lifetime — consistent with `IDisposable`/`IAsyncDisposable` used
  throughout (ADRs 003, 021, 025, 026).
- Works with anonymous lambdas — the caller holds the `IDisposable` token, not the delegate.
- Mirrors the Flow pattern exactly: Flow uses `NugetJobNative.Cancel` + `NugetJobNative.Dispose`
  (C# calls Kotlin) for teardown; stored callbacks use `Native_Remove*` (C# calls Kotlin).
- `NugetSubscription` is straightforward; no dictionary per-event, no integer key allocation.
- The bridge-lambda approach preserves referential identity for `remove*Listener`, which is what
  Kotlin's typical `MutableList<T>.remove()` requires.

**Cons:**
- Requires the Kotlin API to follow the `add*`/`remove*` or `subscribe*`/`unsubscribe*` naming
  convention (or use an explicit annotation). Kotlin APIs that manage their own lifetime without
  exposing a `remove` function are not handled by this mechanism.
- The bridge-lambda added to Kotlin's `mutableListOf` is never cleaned up if C# disposes the
  parent object without calling `Dispose()` on the subscription first. This is a degenerate use;
  documented as a precondition.
- Thread-safety of concurrent `Dispose()` while a callback is mid-flight is deferred (v1 scope).

### 3. `event` keyword with custom `add`/`remove` accessors

The C# class exposes `public event Action<Mood> MoodChanged` with custom `add`/`remove` accessors
that call Kotlin exports. A per-event dictionary maps `Delegate → subscriptionHandle`.

**Pros:**
- Feels most like traditional C# event syntax.

**Cons:**
- `+=`/`-=` with anonymous lambdas is non-functional: `cat.MoodChanged += mood => ...` cannot be
  unregistered because the lambda instance is not retained by the caller. This is a documented
  footgun in C#. Source: [C# event pattern — lambda pitfall](https://learn.microsoft.com/en-us/dotnet/csharp/programming-guide/events/how-to-subscribe-to-and-unsubscribe-from-events#unsubscribing)
- The generated C# class would need a per-event `ConcurrentDictionary<Delegate, IntPtr>` with
  locking — complexity proportional to the number of events.
- `Delegate.Equals` for equality comparison of multicast delegates has subtle semantics; relying on
  it for lookup is error-prone.

## Decision

Use **Alternative 2: IDisposable subscription handle with `add*`/`remove*` pairing**.

### Detection rule

The KSP processor identifies a stored-callback pair when both conditions hold in the same class:

1. A public method `add{X}` (or `subscribe{X}`) with exactly one lambda parameter `(T) -> Unit`
   returning `Unit`.
2. A public method `remove{X}` (or `unsubscribe{X}`) with exactly one parameter of the same
   lambda type `(T) -> Unit` returning `Unit`.

Where `{X}` is the same suffix. If no matching `remove*` exists, the `add*` function is treated as
a per-call callback (existing behavior from ADR-036).

Libraries that do not follow this naming convention can opt in explicitly with the
`@NugetStoredCallback(removeFunction = "...")` annotation from the `nuget-annotations` module.

### Sample Kotlin API (to add to `Cat.kt`)

```kotlin
private val moodListeners: MutableList<(Mood) -> Unit> = mutableListOf()

fun addMoodListener(listener: (Mood) -> Unit) = moodListeners.add(listener)

fun removeMoodListener(listener: (Mood) -> Unit) = moodListeners.remove(listener)

fun triggerMoodChange(mood: Mood) {
  this.mood = mood
  moodListeners.forEach { it(mood) }
}
```

`triggerMoodChange` is a test-helper method; the bridge treats it as a regular Kotlin function
(not part of the subscription pair).

### Kotlin export pattern (generated, for `addMoodListener`/`removeMoodListener` on `Cat`)

```kotlin
// Bridge-lambda registry — global per subscription pair, keyed by Long
private val cat_moodListenerBridge = ConcurrentHashMap<Long, CatMoodListenerEntry>()
private val cat_moodListenerNextKey = AtomicLong(0L)

private data class CatMoodListenerEntry(
  val obj: Cat,
  val bridgeLambda: (Mood) -> Unit,
)

@CName("cat_add_moodlistener")
fun export_cat_add_moodlistener(
  handle: COpaquePointer,
  listenerPtr: COpaquePointer,
  userData: COpaquePointer,
  errorOut: COpaquePointer?,
): COpaquePointer? = try {
  val obj = handle.asStableRef<Cat>().get()
  val fn = listenerPtr.reinterpret<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>()
  val key = cat_moodListenerNextKey.getAndIncrement()

  val bridgeLambda: (Mood) -> Unit = { mood ->
    val moodRef = StableRef.create(mood as Any).asCPointer()
    fn.invoke(moodRef, userData)
    moodRef.asStableRef<Any>().dispose()
  }

  cat_moodListenerBridge[key] = CatMoodListenerEntry(obj, bridgeLambda)
  obj.addMoodListener(bridgeLambda)

  StableRef.create(key).asCPointer()
} catch (e: Throwable) {
  if (errorOut != null)
    errorOut.reinterpret<COpaquePointerVar>().pointed.value =
      StableRef.create(buildError(e)).asCPointer()
  null
}

@CName("cat_remove_moodlistener")
fun export_cat_remove_moodlistener(
  handle: COpaquePointer,
  subscriptionHandle: COpaquePointer,
) {
  val key = subscriptionHandle.asStableRef<Long>().get()
  subscriptionHandle.asStableRef<Long>().dispose()
  val entry = cat_moodListenerBridge.remove(key) ?: return
  entry.obj.removeMoodListener(entry.bridgeLambda)
}
```

The `handle` parameter on `_unsubscribe` is included for symmetry and disposed-object guard
(`ObjectDisposedException`) but the registry lookup by key is sufficient for removal.

### C# generated pattern

```csharp
[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
internal delegate void NugetMoodCallback(IntPtr moodPtr, IntPtr userData);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl,
    EntryPoint = "cat_add_moodlistener")]
private static extern IntPtr Native_AddMoodListener(
    IntPtr handle, IntPtr listenerPtr, IntPtr userData, out IntPtr error);

[DllImport("sample", CallingConvention = CallingConvention.Cdecl,
    EntryPoint = "cat_remove_moodlistener")]
private static extern void Native_RemoveMoodListener(IntPtr handle, IntPtr subscriptionHandle);

public IDisposable AddMoodListener(Action<Mood> listener)
{
    if (_handle == IntPtr.Zero)
        throw new ObjectDisposedException(nameof(Cat));
    NugetMoodCallback nativeCallback = (moodPtr, _) =>
    {
        Mood mood = NugetMarshal.FromHandle<Mood>(moodPtr);
        NugetMarshal.Dispose(moodPtr);
        listener(mood);
    };
    GCHandle cbHandle = GCHandle.Alloc(nativeCallback);
    IntPtr fnPtr = Marshal.GetFunctionPointerForDelegate(nativeCallback);
    IntPtr subscriptionHandle = Native_AddMoodListener(
        _handle, fnPtr, IntPtr.Zero, out IntPtr error);
    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);
    return new NugetSubscription(() =>
    {
        Native_RemoveMoodListener(_handle, subscriptionHandle);
        cbHandle.Free();
    });
}
```

`NugetSubscription` is a shared helper (generated once):

```csharp
internal sealed class NugetSubscription : IDisposable
{
    private Action? _disposeAction;

    internal NugetSubscription(Action disposeAction) => _disposeAction = disposeAction;

    public void Dispose()
    {
        Action? action = Interlocked.Exchange(ref _disposeAction, null);
        action?.Invoke();
    }
}
```

`Interlocked.Exchange` makes `Dispose()` idempotent: calling it twice is a no-op.

### Consumer API (what C# developers write)

```csharp
using var cat = new Cat("Whiskers");

// Registration with explicit disposal
IDisposable sub = cat.AddMoodListener(mood => Console.WriteLine($"Mood: {mood}"));

cat.TriggerMoodChange(Mood.HAPPY);   // callback fires → "Mood: HAPPY"

sub.Dispose();                         // unregisters; no more callbacks

cat.TriggerMoodChange(Mood.SLEEPY);  // no output — listener was removed

// RAII style — callback active for the lifetime of the using block
using (cat.AddMoodListener(mood => Console.WriteLine($"Mood: {mood}")))
{
    cat.TriggerMoodChange(Mood.SLEEPY);
}
// subscription disposed here automatically
```

### Expected C# test structure (mirrors `ReverseLambdaTests.cs` style)

```csharp
[Fact]
public void Cat_AddMoodListener_CallbackFiresOnTrigger()
{
    using var cat = new Cat("Whiskers");
    var recorded = new List<string>();
    using IDisposable sub = cat.AddMoodListener(mood => recorded.Add(mood.ToString()));

    cat.TriggerMoodChange(Mood.HAPPY);

    Assert.Equal(new[] { "HAPPY" }, recorded);
}

[Fact]
public void Cat_AddMoodListener_NoCallbackAfterDispose()
{
    using var cat = new Cat("Whiskers");
    var recorded = new List<string>();
    IDisposable sub = cat.AddMoodListener(mood => recorded.Add(mood.ToString()));

    sub.Dispose();
    cat.TriggerMoodChange(Mood.HAPPY);

    Assert.Empty(recorded);
}

[Fact]
public void Cat_AddMoodListener_MultipleSubscriptions_BothFire()
{
    using var cat = new Cat("Whiskers");
    var a = new List<string>();
    var b = new List<string>();
    using IDisposable sub1 = cat.AddMoodListener(mood => a.Add(mood.ToString()));
    using IDisposable sub2 = cat.AddMoodListener(mood => b.Add(mood.ToString()));

    cat.TriggerMoodChange(Mood.HAPPY);

    Assert.Equal(new[] { "HAPPY" }, a);
    Assert.Equal(new[] { "HAPPY" }, b);
}

[Fact]
public void Cat_AddMoodListener_CapturingLambda_CapturedStateIsAccessible()
{
    using var cat = new Cat("Whiskers");
    int callCount = 0;
    using IDisposable sub = cat.AddMoodListener(_ => callCount++);

    cat.TriggerMoodChange(Mood.HAPPY);
    cat.TriggerMoodChange(Mood.SLEEPY);

    Assert.Equal(2, callCount);
}
```

### `remove*` method visibility on C# side

The `removeMoodListener` Kotlin function is consumed internally by the bridge as the
`_unsubscribe` export. It must NOT be re-emitted as a separate public C# method (unlike regular
Kotlin functions); doing so would expose an API that takes a Kotlin lambda instance, which has no
useful meaning from C#. The generator suppresses public C# emission for the `remove*` half of a
detected pair.

### Ordering: Dispose before parent Dispose

C# callers are responsible for calling `subscription.Dispose()` before calling `cat.Dispose()`.
If the parent object is disposed first, `_handle` is zeroed; the subsequent `_remove` call from
the subscription's `Dispose()` will see a zeroed handle. The generator should guard this with an
early return in the `_unsubscribe` export (not an error — the bridge-lambda is being cleaned up
anyway because the object is gone).

## Consequences

### New CIR nodes needed

- `CirStoredCallbackMethod` — represents one side of a stored-callback pair with fields:
  `subscribeEntryPoint`, `removeEntryPoint`, `delegateName`, `csParamType`, `callbackBody`.
  Similar to `CirCallbackMethod` (per-call) but returns `IDisposable` instead of the wrapped
  return type.
- `CirSubscriptionHelper` — generates `NugetSubscription` in the shared namespace (generated
  once per library, like `NugetJobNative` or `KotlinFlow<T>`).

### New Kotlin export pattern

`StoredCallbackExports.kt` (new file, parallel to `LambdaParameterExports.kt`):
- Detects `add*`/`remove*` and `subscribe*`/`unsubscribe*` pairs via KSP.
- Emits a per-pair bridge-lambda registry (`ConcurrentHashMap<Long, *Entry>` + `AtomicLong`).
- Emits `_subscribe` and `_unsubscribe` `@CName` exports.
- The bridge-lambda for arity-1 callbacks uses the same argument marshalling table as ADR-036:
  objects as `StableRef`, primitives by value, `Boolean` as `Byte`, `String` as `StableRef`.

### New C# patterns

- `IDisposable AddMoodListener(Action<Mood> listener)` — generated per detected subscription pair.
- `NugetSubscription` shared helper class (generated once).
- Delegate type reuse: the delegate type (`NugetMoodCallback`) follows the same naming convention
  as ADR-036 (`Nuget{Arg}{Return}Callback`), potentially reusing an existing delegate type if the
  signature shape matches.

### Breaking changes

None. Stored-callback pairs are new methods. Existing per-call lambda exports are unaffected.

### Consistency with existing code

- The `NugetSubscription` wrapper parallels `NugetJobNative` (a shared helper for job lifecycle).
- The bridge-lambda registry parallels the Job handle returned from async exports (ADR-022): both
  are opaque StableRef handles returned from a subscribe/launch export and consumed by a
  cancel/remove export.
- GCHandle freed by C# after calling Kotlin is exactly the Flow pattern
  (`_onNextHandle.Free()` in `DisposeAsync()`).

### Scope

**In v1:**
- `add{X}(listener: (T) -> Unit)` + `remove{X}(listener: (T) -> Unit)` pairs, arity-1 callback.
- `subscribe{X}(listener: (T) -> Unit)` + `unsubscribe{X}(listener: (T) -> Unit)` pairs.
- Element types for `T`: object handles (classes, enums), `String`, and primitives (same set as
  per-call callbacks in ADR-036).
- Single-threaded Kotlin invocation (fire from the same coroutine dispatcher / thread). The v1
  guarantee: no new invocations after `_unsubscribe` returns; mid-flight concurrent invocations
  are not guarded.
- Arity-0 callbacks (`()`→`Unit`, `add{X}(listener: () -> Unit)`) are included — same marshalling
  as ADR-036 arity-0.

**Deferred:**
- **Exception propagation from a C# stored callback into Kotlin** — explicitly tracked on ROADMAP
  line 103; same analysis as the per-call case in ADR-024/028/029 but with additional complexity
  because the call stack crosses the boundary mid-event.
- **Arity-2+ stored callbacks** — extend once arity-1 is proven; same bridge-lambda pattern.
- **Thread-safe concurrent Dispose + mid-flight invocation** — requires a completion acknowledgment
  from Kotlin before the GCHandle is freed. One approach: Kotlin calls a C#-side "fence" function
  after the last invocation before removing from the map. Deferred.
- **Kotlin-owned lifetime** (Kotlin decides when the callback is no longer needed, not C#) —
  closer to the registry approach (ADR-036 Alt 4). Needed when Kotlin fires a "completion" event
  that terminates the subscription. Not needed for the common observer pattern; deferred.
- **`@NugetStoredCallback` annotation support** for non-standard naming — design straightforward
  (annotation carries `removeFunction` name), deferred until a real-world case arises.
- **Arity-1 stored callbacks where `T` is non-nullable object returning a value** — arity-0 and
  arity-1 `Unit`-returning covers the overwhelming majority of observer patterns.
