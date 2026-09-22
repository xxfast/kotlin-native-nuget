# ADR-161: A C# callback exception reaches Kotlin as a catchable `NugetManagedException`, a materialisation fault ends the stream instead of the process, and a late invocation is a key-table miss

## Status

Accepted

## Context

Every forward callback route (a per-call lambda parameter, ADR-036; a stored callback, ADR-037; a
C#-implemented interface bridge slot, ADR-084) dispatches through the ADR-102 static thunks: a
`[UnmanagedCallersOnly]` method that catches any exception the C# body throws and calls
`Environment.FailFast`. That was ADR-102's "Exception discipline" decision, made because no error
channel existed at the time. It means today a throwing C# lambda, listener, or interface member
kills the whole host process, uncatchable from Kotlin or from the C# caller of the outer export.

Two related gaps share the same root cause, "something fails while Kotlin is talking to C# code,
and there is nowhere for the failure to go but a crash":

1. A bridge-internal failure materialising one `Flow<T>` item (or a `suspend` result) inside a
   generated thunk (for example `NugetMarshal.FromHandle<T>` hitting an unhandled element type)
   also has no channel, so it takes the whole process down instead of failing just that stream or
   call.
2. A Kotlin-side invocation that lands after the C# side already disposed the subscription reads a
   freed `GCHandle`. That slot's *deterministic* reuse (the CLR's free list is LIFO) means the
   thunk either dereferences `null` or silently invokes whatever delegate the next `GCHandle.Alloc`
   put there, not necessarily the one that was unsubscribed.

ADR-104 (the reverse direction's error channel: a bound C# method throwing back into a `suspend`
caller) already recorded, in its "Forward-direction convergence" section, that this forward
direction should use the identical wire shape: a trailing `IntPtr* errOut` and one
`NugetManagedException` type, so a consumer catches one thing regardless of which direction a
managed exception came from.

## Decision

Three parts, all shipped.

### Part A: fault the stream instead of the host

The `Flow` `onNext`/`onError` closures and the `suspend` completion closures now wrap their
materialisation work in a `try`. On catch, the `Flow` route calls `_channel.Writer.TryComplete(ex)`
and cancels the underlying job if it has not already completed synchronously; the `suspend` route
calls `t.TrySetException(ex)`. `MoveNextAsync`/the awaited `Task` then surfaces the failure the
ordinary C# way. The thunk-level `Environment.FailFast` stays as the backstop for a bug in the
generated closure itself. **No ABI change**: `NugetAsyncCallback` stays 4-ary
(`IntPtr, IntPtr, byte, IntPtr`) and the Flow thunks stay 3/1/2-ary
(`onNext(IntPtr, byte, IntPtr)`, `onComplete(IntPtr)`, `onError(IntPtr, IntPtr)`); none of the three
gained an `errOut` slot, because none of them runs user C# code (finding 1 of the research memo:
only the four user-code-invoking thunk families need a channel at all).

Accepted residue: an item whose materialisation failed had already had its element handle handed
over by Kotlin before the C# read failed, so that one `StableRef` per failed item leaks (recorded
in `LeakTests/LiveHandleTests.cs`, not asserted as a passing row).

### Part B: a trailing `IntPtr* errOut` on every user-code callback thunk

Every thunk that invokes user C# code inline (`appendThunkBody`'s four families: per-call lambda,
stored callback, ADR-039 listener bridge, ADR-084 interface bridge slot) now takes one additional
trailing parameter, `IntPtr* errOut`, after `ctx`. It is never passed to the C# delegate itself
(every closure signature stays byte-identical); the thunk's catch becomes:

```C#
catch (Exception ex)
{
    if (errOut != null) { *errOut = NugetErrorNative.CreateManagedError(ex); return default; }
    Environment.FailFast("nuget: unhandled exception in <delegate>", ex);
}
```

`CreateManagedError` P/Invokes one new runtime export, the 71st, `nuget_managed_error_create(type,
message, kind)`, which mints a Kotlin-owned holder (a raw `StableRef<NugetManagedError>`, not
counted by `NugetHandles`/`nuget_live_handles`: it lives for the length of one callback return and
never reaches a consumer, so counting it would move every leak-test baseline for something no test
can observe). The Kotlin invocation site wraps the call in a new runtime helper,
`nugetCallbackCall { err -> fn.invoke(args, ctx, err) }`, which allocates and zeroes the slot,
invokes the callback, checks the slot **before** touching the return value (the same structural
ordering ADR-104 established: a `!!` or a handle read must never fire ahead of the managed
exception that explains why the result is absent), disposes the holder, and throws:

```kotlin
public class NugetManagedException(
  public val managedType: String,
  public val managedMessage: String,
) : RuntimeException("$managedType: $managedMessage")
```

`kind` is `1` when the C# exception was an `OperationCanceledException`, `0` otherwise, mirroring
ADR-153's reverse-direction mapping: `nugetCallbackCall` throws Kotlin's `CancellationException`
(with the `NugetManagedException` as `cause`) instead, so a cancelled C# callback cancels the
Kotlin coroutine that invoked it rather than merely failing it. Residual: an escaping
`CancellationException` that a consumer catches uncaught in turn re-crosses to C# through the
ordinary ADR-024 channel typed `KotlinType == "kotlin.coroutines.cancellation.CancellationException"`
(stdlib's cancellation type), not the original `OperationCanceledException`; not fixed here.

The Flow/async thunks (part A) and the cleaner's release thunk keep today's arity and
`Environment.FailFast`; the release thunk shares the interface bridge's `void`-shaped delegate but
is always invoked with `errOut == null` from the cleaner, so it still fails fast rather than
reporting into a channel nobody reads.

**Rethrow-original generalises past the per-call route.** The research memo's recommendation
scoped the "rethrow the original C# exception, not a `KotlinException` wrapper" optimisation to the
per-call route, where the call site owns the closure. The shipped mechanism is broader: the stash
is a single `[ThreadStatic] Exception? _lastManagedFault` on the shared `NugetErrorNative` class,
written by `CreateManagedError` (every user-code thunk's catch, whichever route) and read back by
`NugetErrorNative.BuildException`, the one function every generated export's ADR-024 error arm
already calls. `BuildException` returns the stashed original whenever the error it is about to
build has `KotlinType` exactly `NugetManagedException`'s qualified name and a message matching
`"<type>: <message>"` verbatim; otherwise it falls through to the ordinary type-mapped exception.
Since the stash and its consumer are the same synchronous C# thread and every route's uncaught path
crosses back out through one export's `BuildException` call, a stored-callback `Emit()` or an
interface-bridge slot call rethrows the original C# exception the same way a per-call lambda does,
not only the per-call shape the memo scoped this to. This is a deviation from the memo's PR B
"optional, per-call-only" framing, made during implementation; it costs nothing extra (one field,
one extra check already on the shared exception-building path) and gives every route the same
fidelity, so it is kept rather than narrowed back down.

**Deviation from ADR-104's "should not diverge" note.** ADR-104's forward-direction convergence
section recommended the accessor design verbatim: a `GCHandle` in the slot, read back through
function pointers registered via `nuget_runtime_register`. This ADR instead pushes a
`StableRef`-backed holder from C#, and the Kotlin side calls one export directly, because a
forward-only library (no NuGet dependency, no reverse pipeline) has no registration step to hang
accessors off; a forward-only KSP run would have had to invent one for no benefit over the push.
The wire shape ADR-104 asked to converge on (trailing `errOut`, one `NugetManagedException` type,
checked before the return value) is unchanged; only the mechanism that fills the slot differs.
Human-approved (what-question 1).

**No `NugetRuntimeAbi1` bump.** Per ADR-127/128/129, an added runtime export is a compatible change
("`NugetRuntimeAbi1` stays: an added function is a compatible runtime change", ADR-128); this is
the 71st export (70 before), purely additive, so `NugetRuntimeAbi1` is unchanged and `verify-
runtime-exports.sh` needs no hardcoded count (it derives the expected list from `NugetRuntime.kt`
itself). An old runtime under a new C# shim fails loudly: the `CreateManagedError` P/Invoke to a
missing export throws `EntryPointNotFoundException` inside the thunk's own nested `try`, whose
catch is `Environment.FailFast`, so the failure mode for a stale native library is a clear crash
message, not silent corruption.

**Where `NugetManagedException` lives (what-question 2).** It is one public class in
`nuget-runtime`, nameable from `nativeMain` since the runtime klib is on every per-target
`${target}MainApi` classpath. The reverse pipeline's own `internal class NugetManagedException`,
emitted into the consumer's `INTERNAL_PKG` by `NugetGenerateBindingsTask`, is **not** folded onto
this one (the expect/actual-seam spike, memo spike (c), was never run in this pass): the two remain
distinct types with the same simple name, one reached only when the reverse pipeline emits a
runtime registration file, the other always present once any forward callback exists. Deferred, new
ROADMAP item below.

**`@NugetRuntimeApi` omission, flagged for the human.** Every other public runtime export function
in this file carries `@NugetRuntimeApi` (an opt-in marker, ADR-115) so a consumer must
`@OptIn(NugetRuntimeApi::class)` to call it directly. `NugetManagedException` deliberately does
not: it is a type an author catches at an ordinary `try`/`catch`, and requiring `@OptIn` at every
catch site (rather than only at the few call sites that invoke a marked function) would be an
unusual and probably unwanted spread of that annotation. This reads against ADR-127's general rule
that everything the runtime klib exposes carries the marker; recorded here rather than silently
deviating, and left for a human decision rather than resolved in this ADR.

### Part C: a never-reused key replaces the `GCHandle` ctx for stored callbacks and bridge slots

`NugetThunks` gains a `ConcurrentDictionary<IntPtr, object>` and a monotonic `Interlocked.Increment`
counter. Subscribing adds an entry; the subscription's dispose action removes it (after the native
unregister call, same ordering as before); the thunk does `LookupCtx` (a `TryGetValue`) instead of
`GCHandle.FromIntPtr(ctx).Target`, and on a miss takes the late-call path: for a `void`-returning
delegate, return (the subscriber unsubscribed, silence is what was asked for); for a
value-returning delegate, report `System.ObjectDisposedException` through the same part B error
channel, since a default value would let Kotlin's own `!!` null-check fire on a value that was
never really produced. Per-call lambdas and ADR-084 bridge slots use the same table for uniformity.
`Flow` keeps its existing two-party rendezvous release, which already has no such race (Kotlin
guarantees a terminal callback).

This was verified against the rejected alternatives before being chosen: a null check or exception
catch around the freed `GCHandle` read cannot distinguish "freed, never reallocated" from "freed,
and the very next `Alloc` (deterministically, LIFO) put a same-typed delegate in the same slot",
which silently invokes the wrong listener rather than throwing anything; a Kotlin-side in-flight
gate that `remove` waits on deadlocks when `Dispose()` is called from inside the callback itself;
never freeing leaks a subscription per dispose. Accepted residual semantics: a lookup that
succeeds just before the corresponding `TryRemove` still invokes the listener that was subscribed a
moment ago. That is ordinary late delivery, not a wrong-listener bug and not a use-after-free.

## Consequences

- A throwing per-call lambda, stored listener, or C#-implemented interface member no longer ends
  the host process. Kotlin sees `catch (e: Exception)` at its own invocation site (it cannot name
  the runtime type from every source set the way an ordinary consumer of `nuget-runtime` can);
  uncaught, the C# caller of the outer export gets a catchable exception: the original C# exception
  when the escaping Kotlin error is exactly `NugetManagedException`, otherwise `KotlinException`
  wrapping whatever Kotlin threw instead (including a Kotlin author's own wrapper exception).
- A `Flow<T>`/`suspend` materialisation failure faults the stream/`Task` and cancels the Kotlin
  collector instead of crashing; one `StableRef` per failed `Flow` item is an accepted, unfixed
  leak (residual 2 below).
- A late callback invocation after `Dispose()`/`removeListener` is dropped (`void`) or reported as
  `ObjectDisposedException` (value-returning), never a use-after-free or a silently wrong listener.
- An uncaught `NugetManagedException` on a Kotlin coroutine or worker with no handler still
  terminates the process (Kotlin/Native's own default unhandled-exception hook); this ADR narrows
  "always crashes" to "crashes only when Kotlin does not catch it", it does not eliminate crashes on
  every code path.
- `ForwardAbiContract`'s Tier 1 arity-agreement check now also compares each user-code thunk's C#
  `delegate* unmanaged[Cdecl]<...>` parameter count and its trailing-`IntPtr*` slot against the
  Kotlin `CFunction<...>` type the same emitter spells, closing the "neither compiler sees the
  other half's arity" gap the memo's finding 6 named.
- ADR-102's "Exception discipline: decided at the gate: FailFast" section is superseded for every
  user-code thunk family by part B; `Environment.FailFast` remains correct and unchanged for the
  Flow/async runtime thunks and the cleaner's release call.
- ADR-104's forward-direction convergence recommendation is implemented with one recorded
  deviation: a pushed, Kotlin-owned holder through one new export, not GCHandle-plus-accessors.

## Residuals recorded, not fixed here

1. The reverse template's `internal class NugetManagedException` (`NugetGenerateBindingsTask.kt`)
   is not folded onto the runtime class; two types share one simple name until the ADR-130
   expect/actual seam is spiked and applied.
2. A `Flow<T>` item whose read failed leaks one `StableRef` (part A); a shared catch that also
   tried to dispose it risks a double free, so per-branch ownership in `FromHandle` is needed
   first.
3. A dropped late call whose payload was a handle-passed argument (`String`, an exported object)
   leaks that argument's `StableRef`: the delegate that would have read and released it never runs.
4. A cancelled C# callback that a consumer catches, then does not itself catch again further up,
   reaches an uncaught-in-Kotlin C# caller typed `KotlinType ==
   "kotlin.coroutines.cancellation.CancellationException"`, not the original
   `OperationCanceledException`.
5. `NugetErrorNative._lastManagedFault` ([ThreadStatic]) keeps one exception rooted per thread
   until the next fault on that thread overwrites it or reads it back.
6. `NugetManagedException` carries no `@NugetRuntimeApi`, unlike every other public runtime export;
   left for a human decision (above).

## Alternatives considered

- ADR-104's literal accessor design (GCHandle in the slot, function pointers registered through
  `nuget_runtime_register`): rejected, no forward registration step exists to hang accessors off.
- `errOut` on the Flow and async runtime thunks too: rejected, those thunks run no user code; the
  C#-local catch (part A) is already a complete fix with no ABI change.
- Catch-and-swallow in the thunk: rejected at ADR-102's original gate, silent corruption risk.
- Detecting a late call by catching the freed-`GCHandle` read, or a null check on its `Target`:
  rejected, the slot's reuse is deterministic and undetectable from the read side (part C).
- A Kotlin-side in-flight gate `remove` waits on: rejected, deadlocks on a callback that disposes
  its own subscription.
- Never freeing the subscription's key: rejected, leaks one entry per subscription.
