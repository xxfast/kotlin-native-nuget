# ADR-231: Forward, a plain lambda parameter on a `suspend` or `Flow`-returning member binds as a C# delegate

## Status
Accepted

Amends [ADR-215](215-type-parameter-in-lambda-and-flow.md) (a `suspend` member taking a plain lambda
was refused for any payload), [ADR-160](160-callback-parameter-on-the-forward-plan.md) (a lambda
returning an exported class was refused on every route) and
[ADR-210](210-flow-and-suspend-lambda-parameters.md) (the key-ownership rule gains a second,
coroutine-scoped owner).

## Context

A plain `(T) -> R` lambda parameter on an ordinary member is call-scoped: C# registers the delegate's
callback context, passes the key, and unregisters it in a `finally` once the P/Invoke returns
([ADR-160](160-callback-parameter-on-the-forward-plan.md)). A `suspend` member returns to C# long
before the coroutine ends, and a `Flow`-returning member's body runs during the collection, so the
same unregister would free the context while Kotlin could still call the lambda. Both members were
dropped as `SKIPPED_UNSUPPORTED_INPUT`, for every payload and for a member's own `T` too.

## Decision

The member binds with the same delegate the ordinary route uses, on every owner that has a `suspend`
or `Flow` route: a class, an interface Kotlin implements, a sealed base or arm, a generic class, the
top level, an `object`, a default or named companion, an extension, an enum body and companion, and a
value class (including one over a class handle). Every payload the ordinary route carries crosses:
primitives, `String`, an exported class in and out, and a member's own or the class's `T`.

```kotlin
suspend fun run(steps: Int, onStep: (Int) -> Unit): Int
fun watch(laps: Int, onSeen: (Int) -> Unit): Flow<Int>
suspend fun feed(bites: Int, onBite: (Int) -> Unit): Flow<Int>
```

```C#
public Task<int> RunAsync(int steps, Action<int> onStep, CancellationToken cancellationToken = default)
public KotlinFlow<int> Watch(int laps, Action<int> onSeen)
public Task<KotlinFlow<int>> FeedAsync(int bites, Action<int> onBite, CancellationToken cancellationToken = default)
```

### Who owns the context key, and when

C# registers the key and owns it until the native call returns, then hands it over by zeroing its
local; the `finally` that unregisters on a throw before the hand-over is then a no-op (the table's
`UnregisterCtx` is a `TryRemove`, so a second release is harmless). After the hand-over Kotlin owns
it and releases it through the shared `NugetAsyncReleaseContext` thunk, which the export receives as
one more slot:

- **`suspend` member.** The launched body is `try { ... } finally { release }`. The launch starts
  with `CoroutineStart.ATOMIC`, so the body, and its `finally`, run on completion, fault and
  cancellation, including cancellation before dispatch, and before the completion callback. When the
  coroutine finishes before the P/Invoke returns, Kotlin has already released the key and C#'s
  zeroing and `finally` change nothing.
- **`Flow`-returning member.** The wrapped `.collect` body carries the same `finally`. C# registers
  a fresh key inside the collect delegate, so each enumeration has its own, released when that
  collection completes, faults, is cancelled or is abandoned with `break` or `DisposeAsync()`.
- **`suspend` member returning a `Flow`.** The key must live as long as the returned flow can be
  collected again, so Kotlin does not release it on success. C# attaches
  `new NugetContextRelease(key)` to the returned flow's handle: disposing or finalizing the
  `KotlinFlow` releases it. On a fault, a cancellation or a `null` flow Kotlin releases it at once,
  because no flow reaches C#.

The context table is not counted by `nuget_live_handles`, so a leak here shows only in a
collectability row (a `WeakReference` to state the delegate captured), not in the handle count.

### Behaviour a consumer relies on

- A null delegate throws `ArgumentNullException` naming the parameter: eagerly on a `suspend`
  member, at the first enumeration on a `Flow` member, like every other `Flow` argument check.
- The delegate runs on a Kotlin dispatcher thread, many times, and from several threads at once.
  A throw from it faults the `Task` (or the enumeration) with the original exception
  ([ADR-161](161-csharp-callback-exception-into-kotlin.md)).
- Late use follows [ADR-160](160-callback-parameter-on-the-forward-plan.md): Kotlin that stores the
  lambda and calls it after the key is released reaches no C# code. A `Unit` lambda is dropped; a
  returning one reports `ObjectDisposedException` through the error slot.

### A lambda returning an exported class

`BridgeType.ObjectHandle` joins the callback result types the classifier accepts, so
`make: (String) -> Baton` binds on every callback route, ordinary members included (this was
`SKIPPED_UNSUPPORTED_RETURN`). C# returns an owned handle (`NugetMarshal.WrapOwned<T>(make(...))`)
that Kotlin reads and releases; the wrapper the delegate returned stays the caller's.
An enum, `Char` and Kotlin builtins stay refused as results.

### Alternatives considered

- **Release from the C# completion closure.** Rejected: the `Flow` route has no per-member
  completion closure (its enumerator is shared), so a second mechanism would be needed for the
  `Flow` sibling, and the block-scoped key handling of the shared hand-over could not be reused.
- **A `createCleaner` holder, as ADR-210 does for a `suspend` lambda or `Flow` parameter.**
  Rejected: those can be legitimately stored and invoked later; a plain lambda on a synchronous
  member is already call-scoped, so coroutine-scoped is the consistent extension, and a delegate
  pinned until a Kotlin GC round cannot be asserted deterministically.

## Consequences

- The fixture `Siphon.decant` (`membergeneric/Drains.kt`) binds; its xunit fact is live again.
- Pinned by `IntegrationTests/SuspendPlainLambdaTests.cs` (every owner and payload, the three
  terminal arms, a member that never suspends, a null delegate, late use), the `Tier1SuspendPlainLambdaTest`
  and `Tier1GenericAsyncInputTest` cells, `LeakTests/LiveHandleTests.cs` (`SuspendPlainLambda_*`,
  `FlowPlainLambda_*`, `SuspendReturningFlowPlainLambda_*`) and `LeakTests/CollectabilityTests.cs`
  (the same names with the `ItsDelegateIsCollected` suffix).

### Verified vs inferred

- Verified by generated code read in a verified build: the signatures above, the per-enumeration key
  registration and hand-over, `Attach(new NugetContextRelease(key))` on the awaited-flow route.
- Inferred from kotlinx.coroutines documentation, not re-read: `CoroutineStart.ATOMIC` runs the body
  when the job is cancelled before dispatch. The collectability rows are what would catch it
  being wrong.
