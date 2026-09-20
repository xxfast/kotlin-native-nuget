# ADR-152: Reverse, a C# `Task` / `Task<T>` method binds as a Kotlin `suspend fun` over a begin/end thunk pair

## Status

Accepted

## Context

A Kotlin consumer of a bound C# NuGet package calls a C# method that returns `Task` or `Task<T>`
as a Kotlin `suspend fun` returning `Unit` or `T`; a faulted task surfaces as a catchable
`NugetManagedException`. Direction: reverse (C# → Kotlin). The C# package declares the method,
Kotlin merely consumes it.

This is the mirror of [ADR-019](019-suspend-function-mapping.md) (`suspend fun` → `Task`), with
the call mechanism of [ADR-041](041-kotlin-to-csharp-call-mechanism.md) (registered
`[UnmanagedCallersOnly]` thunks, no CLR hosting) and the error channel of
[ADR-104](104-reverse-thunk-error-channel.md) (trailing `IntPtr* errOut`, `nugetCall`,
`NugetManagedException`).

What the pipeline does today (all **verified**, by reading and by running a scratch copy of the
reader against a probe assembly, 2026-09-19):

- `Task<T>` returns decode through `GetGenericInstantiation`, which returns a null `TypeRef` with
  no diagnostic for an async shape (`NugetMetadataReader/Program.cs:3129-3135`); `TryMapMethod`
  then names the skip `info_async_not_yet_mapped` (`Program.cs:2122-2141`).
- Non-generic `Task` never reaches that branch. It decodes through `GetTypeFromReference`, which
  knows only `System.String` and otherwise returns a `skipped_unbound_type_reference` diagnostic
  (`Program.cs:3093-3115`); the per-type diagnostic loop (`Program.cs:2106-2118`) returns before
  the async check. Reader output for the probe:

  ```
  skipped_unbound_type_reference | Kennel | Nap()          // public Task Nap()
  info_async_not_yet_mapped      | Kennel | Count()        // public Task<int> Count()
  info_async_not_yet_mapped      | Kennel | V()            // public ValueTask<string?> V()
  ```

  So the async info diagnostic is unreachable for non-generic `Task` (and `ValueTask`) today.
- `RirMethod` is `name, returnType, parameters, isStatic, managedSignature` (`rir/RirModel.kt:151-157`).
  `RirRegistrable.Method` yields exactly one registration slot, and `contractSignature` hashes
  member kind, name, parameter types and return type (`rir/RirBridging.kt:942-956`) under
  `REVERSE_ABI_TAG = "reverse_v2:"` (`RirBridging.kt:907`).
- Every user-code thunk is emitted through `errorChannelThunk` (`NugetGenerateShimsTask.kt:987-1012`)
  and every Kotlin call site through `wrapInvoke` → `nugetCall { err -> ... }`
  (`NugetGenerateBindingsTask.kt:3513-3524`, `:4392-4399`).
- The generated reverse Kotlin imports nothing from `kotlinx.coroutines` (grep of
  `NugetGenerateBindingsTask.kt`: zero hits). It compiles from `nativeMain`
  (`NugetPlugin.kt:192`), while `nuget-runtime` (which carries `api(kotlinx-coroutines-core)`,
  `nuget-runtime/build.gradle.kts:22`) is added only to `${target}MainApi` (`NugetPlugin.kt:241`).
  [ADR-130](130-reverse-error-envelope-on-runtime.md)'s spike (its Variant 1) **verified** that a
  `nativeMain` file naming a declaration reachable only through the per-target `api` fails
  `compileNativeMainKotlinMetadata` with `Unresolved reference`. `test-library` declares
  coroutines on `nativeMain` itself (`test-library/build.gradle.kts:120`), so the fixture would
  hide that failure. ADR-130 shipped the fix shape: an `internal expect` in `nativeMain`, the
  `actual` emitted into `posixMain`/`mingwMain`, where the runtime klib is visible.

## Alternatives Considered

### 1. Begin/end thunk pair, uniform completion callback carrying the `Task`'s `GCHandle` (chosen)

Each async method registers two slots. `Begin` takes the ordinary parameters plus a completion
function pointer and an opaque `ctx`, starts the task, and attaches one continuation that calls
`callback(taskHandle, ctx)`. Kotlin suspends on a continuation held behind `ctx`, resumes with the
task handle, and then calls `End(taskHandle, <the sync return shape>, errOut)`, which is the
ordinary ADR-049/056/104 return thunk with `task.GetAwaiter().GetResult()` as its call expression.

- Pros: every already-supported return kind (primitive, string, handle, struct out-pointers,
  nullable reference, interface, enum) works with **zero new marshalling**, because `End` is
  `buildThunkMethod`'s return half unchanged. A faulted task reuses the ADR-104 channel verbatim,
  and `GetAwaiter().GetResult()` rethrows the original exception, not `AggregateException`
  (verified below). One callback signature for the whole bridge, so one `staticCFunction`, owned
  by `nuget-runtime`, passed per call: **no new `nuget_runtime_register` slot**. It is the shape
  ADR-019 already has on the forward side (callback delivers a handle, a second call unwraps it).
- Cons: two crossings and one `GCHandle` per call; two slots per async method.

### 2. One thunk, typed completion callback `(result, err, ctx)` per result ABI type

The mirror of ADR-019's callback read literally. Rejected: struct results need a per-struct
callback arity, nullable references and strings need their ownership rules re-implemented inside a
callback, and the error envelope would be delivered by a second route next to `errOut`. Every
return kind becomes new code in both generators, which is the ADR-119/122/123 "legacy route grows
a second copy of each kind" story this codebase has been paying down.

### 3. Box the result in a `GCHandle`, unwrap through new runtime accessor slots

Literal inversion of `nuget_unwrap_*`. Rejected: grows `nuget_runtime_register` by one slot per
primitive, cannot express struct results, and is strictly more ABI than Alternative 1 for the same
two crossings.

### 4. Block: `task.GetAwaiter().GetResult()` inside a synchronous thunk

Rejected: blocks a Kotlin thread, deadlocks under a single-threaded `SynchronizationContext`, and
is not a `suspend fun` in any useful sense.

### 5. Surface `Deferred<T>` instead of `suspend`

Rejected: `suspend` is what Kotlin surfaces for a one-shot async call (`CompletionStage.await()`,
play-services `Task.await()`); a consumer who wants a `Deferred` writes `async { }`. Needs a scope
parameter the C# API never had.

## Decision

Alternative 1.

### Consumer-visible Kotlin

```kotlin
// C#: public class Kennel { Task NapAsync(); Task<int> CountAsync(); Task<string> NameAsync(); Task<Cat> AdoptAsync(string name); }
class Kennel : AutoCloseable {
  suspend fun nap()
  suspend fun count(): Int
  suspend fun name(): String
  suspend fun adopt(name: String): Cat
}
```

Naming: a trailing `Async` is dropped (the exact inverse of ADR-019, which appends it) **unless**
the declaring type has any method whose C# name equals the stripped name, in which case the name
is kept (`Read` + `ReadAsync` → `read()` + `suspend fun readAsync()`). Kotlin cannot overload on
`suspend` alone, so an unconditional strip would turn the most common .NET pairing into an
`ERROR_KOTLIN_SIGNATURE_COLLISION`. This is an open question at the gate, see the memo.

### Wire shape

C#, generated per async method (`Begin` through `errorChannelThunk`, so a synchronous throw before
the `Task` exists takes the ordinary ADR-104 path and the callback never fires):

```csharp
[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe void CountAsync_Begin_Thunk(IntPtr selfHandle, /* in-args */ IntPtr callback, IntPtr ctx, IntPtr* errOut)
{
    try
    {
        Kennel receiver = (Kennel)GCHandle.FromIntPtr(selfHandle).Target!;
        Task task = receiver.CountAsync();
        NugetTasks.Attach(task, callback, ctx);
    }
    catch (Exception ex) { *errOut = GCHandle.ToIntPtr(GCHandle.Alloc(ex)); }
}

[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
private static unsafe int CountAsync_End_Thunk(IntPtr taskHandle, IntPtr* errOut)
{
    GCHandle handle = GCHandle.FromIntPtr(taskHandle);
    try { return ((Task<int>)handle.Target!).GetAwaiter().GetResult(); }
    catch (Exception ex) { *errOut = GCHandle.ToIntPtr(GCHandle.Alloc(ex)); return default; }
    finally { handle.Free(); }
}
```

C#, emitted once into `NugetRuntimeRegistration.cs`:

```csharp
internal static unsafe class NugetTasks
{
    internal static void Attach(Task task, IntPtr callback, IntPtr ctx)
    {
        ArgumentNullException.ThrowIfNull(task);
        IntPtr handle = GCHandle.ToIntPtr(GCHandle.Alloc(task));
        try
        {
            task.ContinueWith(
                static (_, state) =>
                {
                    var (cb, h, c) = ((IntPtr, IntPtr, IntPtr))state!;
                    ((delegate* unmanaged[Cdecl]<IntPtr, IntPtr, void>)cb)(h, c);
                },
                (callback, handle, ctx),
                CancellationToken.None,
                TaskContinuationOptions.None,
                TaskScheduler.Default);
        }
        catch { GCHandle.FromIntPtr(handle).Free(); throw; }
    }
}
```

`TaskContinuationOptions.None`, not `ExecuteSynchronously`: the callback always arrives from a
thread-pool thread, never inline on the thread that called `Begin` and never inline on whichever
thread completed the task (which would run arbitrary undispatched Kotlin on an I/O completion
thread). The function pointer travels as `IntPtr` inside the state tuple because a function
pointer type cannot be a generic argument.

Kotlin, once in `nuget-runtime` (new `NugetAwait.kt`, next to ADR-128's `launchForCSharp`, same
"runtime owns the shape once" rule, not `inline`, no `@CName`):

```kotlin
@NugetRuntimeApi
public suspend fun awaitForKotlin(
  release: (task: COpaquePointer) -> Unit,
  begin: (callback: COpaquePointer, ctx: COpaquePointer) -> Unit,
): COpaquePointer = suspendCancellableCoroutine { cont ->
  val ctx: COpaquePointer = NugetHandles.retain(NugetPendingTask(cont, release))
  try {
    begin(TASK_COMPLETED, ctx)
  } catch (e: Throwable) {            // broad on purpose: whatever begin threw, the callback will
    NugetHandles.release(ctx)         // never fire, so this is the only place ctx can be released
    throw e
  }
}

// staticCFunction target: (taskHandle, ctx) -> Unit, called exactly once per successful Begin.
private fun taskCompleted(task: COpaquePointer?, ctx: COpaquePointer?) {
  val pending: NugetPendingTask = ctx!!.asStableRef<NugetPendingTask>().get()
  NugetHandles.release(ctx)
  pending.cont.resume(task!!) { _, _, _ -> pending.release(task) }   // cancelled meanwhile: free the Task handle
}
```

Kotlin, generated: `nativeMain/NugetRuntime.kt` gains
`internal expect suspend fun nugetAwaitTask(begin: (COpaquePointer, COpaquePointer) -> Unit): COpaquePointer`;
the per-target file ADR-130 introduced (`posixMain|mingwMain/NugetKotlinErrors.kt`, or a sibling
`NugetTasks.kt` emitted under the same rule) carries the `actual`, a one-line delegate to
`awaitForKotlin(release = { freeGcHandleFn!!.invoke(it) }, begin)`. The stub names only stdlib
and cinterop types:

```kotlin
suspend fun count(): Int {
  val begin = requireNotNull(KennelBindings.countAsyncBegin) { /* ADR-054 computed message */ }
  val end = requireNotNull(KennelBindings.countAsyncEnd) { /* ... */ }
  val self: COpaquePointer = handle.require("Kennel")
  val task: COpaquePointer = nugetAwaitTask { callback, ctx ->
    nugetCall { err -> begin.invoke(self, callback, ctx, err) }
  }
  return nugetCall { err -> end.invoke(task, err) }      // the sync stub's return half, unchanged
}
```

### Ownership, exactly once

| Thing | Minted by | Freed by | On which path |
|---|---|---|---|
| `ctx` (`StableRef<NugetPendingTask>`) | `awaitForKotlin` before `begin` | `taskCompleted` | every path where `Begin` returned without `errOut` |
| same | same | `awaitForKotlin`'s `catch` | `Begin` wrote `errOut` (sync throw), callback never fires |
| `Task` `GCHandle` | `NugetTasks.Attach` | `End` thunk's `finally` | success and fault |
| same | same | `release` (`freeGcHandleFn`) in `resume`'s `onCancellation` | coroutine cancelled before completion arrived |
| same | same | `Attach`'s `catch` | `ContinueWith` itself threw |

Cancellation never releases `ctx`: the callback may still fire later and must find it. A C# task
that never completes leaks one `ctx` and one `GCHandle`, which is the same leak the C# caller of
that task would have.

### RIR and contract

`RirMethod` gains `asyncKind: RirAsyncKind? = null` (`"task"` only in v1; `returnType` is the
awaited type, `RirVoidType` for non-generic `Task`). An enum, not a boolean, so `ValueTask` lands
later as one more value plus `.AsTask()` in `Begin` with no JSON contract change. An async
`RirRegistrable.Method` contributes **two** slots, `Begin` then `End`, adjacent, in both
generators, from the shared `bridgeableRegistrables` list. Its `contractSignature` gains an
`async:` prefix, so a package that changes `T Foo()` to `Task<T> Foo()` drifts the hash even
though name, parameters and awaited type are unchanged. `REVERSE_ABI_TAG` does not bump: no
existing thunk changes arity. `nuget_runtime_register` stays at 5 slots.

### Reader

`GetTypeFromReference` recognises `System.Threading.Tasks.Task` and `GetGenericInstantiation`
recognises `` Task`1 `` with one admissible argument; both produce a reader-internal async wrapper
that `TryMapMethod` unwraps **after** nullability resolution. It is admitted only as a method
return: an async shape as a parameter, a property type, a constructor parameter, a type argument,
or a struct component keeps today's skip. `Task<T>?` (outer byte 2) is skipped with a named
diagnostic. `ValueTask`, `ValueTask<T>` and `IAsyncEnumerable<T>` keep `info_async_not_yet_mapped`,
and non-generic `ValueTask` is moved onto it from `skipped_unbound_type_reference`.

Nullability is the one place this can go silently wrong. `NullableAttribute` bytes are pre-order
over the **whole** tree, `Task` node included. The wrapper must therefore count as one annotatable
node in `CountAnnotatableNodes` and consume one byte in `ApplyPreOrder`
(`Program.cs:2542-2547`, `:2608-2638`), exactly as `RirGenericInstanceType` does. Unwrapping to `T`
before `ResolveTree` would hand a 2-byte array to a 1-node tree.

### Mechanism claims ledger

**Verified by spike, 2026-09-19** (scratch dirs under `mktemp -d`, .NET SDK 10.0.300, `konanc`
2.4.10, macOS arm64):

1. A `ContinueWith` continuation can call a `delegate* unmanaged[Cdecl]<IntPtr, IntPtr, void>`
   smuggled as `IntPtr` through the state tuple, from inside an `[UnmanagedCallersOnly]` `Begin`;
   `GetAwaiter().GetResult()` on a faulted task rethrows the **original** exception;
   `.Result` throws `AggregateException`; a cancelled task yields `TaskCanceledException`; a
   synchronous throw in a non-`async` `Task`-returning method lands in `Begin`'s `errOut` and the
   callback never fires. Output (cases: 0 `Task`, 1 `Task<int>`, 2 `Task.FromResult`, 3 faulting,
   4 sync throw, 5 `Task.FromCanceled`):

   ```
   Options=None
     case 0: result=void endErr=none cbThread=7 callerThread=1 cbBeforeBeginReturned=False
     case 1: result=42 endErr=none cbThread=7 callerThread=1 cbBeforeBeginReturned=False
     case 2: result=7 endErr=none cbThread=5 callerThread=1 cbBeforeBeginReturned=False
     case 3: result=0 endErr=System.InvalidOperationException: boom cbThread=5 callerThread=1 cbBeforeBeginReturned=False
     case 4: Begin errOut = System.ArgumentNullException; callback fired within 300ms: False
     case 5: result=0 endErr=System.Threading.Tasks.TaskCanceledException: A task was canceled. cbThread=5 ...
   Options=ExecuteSynchronously
     case 2: result=7 endErr=none cbThread=1 callerThread=1 cbBeforeBeginReturned=True
     case 5: ... cbThread=1 callerThread=1 cbBeforeBeginReturned=True
   .Result throws System.AggregateException
   ```

   With `None` an already-completed task still calls back from a pool thread (case 2, thread 5).
   `cbBeforeBeginReturned=False` there is a race outcome, not a guarantee: the Kotlin side must
   tolerate the callback landing before `begin` returns, and claim 2 shows it does.
2. Kotlin/Native resumes a stdlib `Continuation` held in a `StableRef` from a **foreign** pthread
   (one the Kotlin runtime never created) through a `staticCFunction`, and also inline before the
   `suspendCoroutine` block returns:

   ```
   inline=false result=84 resumedOnThread=CPointer(raw=0x16fcdb000)
   inline=true result=84 resumedOnThread=CPointer(raw=0x1f7f318c0)
   main thread=CPointer(raw=0x1f7f318c0)
   ```

   The spike used stdlib `suspendCoroutine`, not `suspendCancellableCoroutine` (no coroutines klib
   on a bare `konanc` line). See inferred claim A.
3. `NullableAttribute` on the return pseudo-parameter of a `Task<T>` method, decoded with
   `System.Reflection.Metadata` from a net10.0 build (attribute ctor is a `MemberReference`):

   ```
   Task<string?>       param#0 NullableAttribute = 01-00-02-00-00-00-01-02-00-00   // bytes [1, 2]
   Task<string>?       param#0 NullableAttribute = 01-00-02-00-00-00-02-01-00-00   // bytes [2, 1]
   Task<Cat?>          param#0 NullableAttribute = ...01-02                        // bytes [1, 2]
   ValueTask<string?>  param#0 NullableAttribute = ...00-02                        // bytes [0, 2]
   Task<string> under method NullableContext(2): param#0 NullableAttribute = 01-00-01-00-00  // single-byte form, all nodes 1
   Task, Task<int>, Task<int?>, Task<Point>: no attribute
   ```
4. The reader's present behaviour (Context, above), by running a scratch copy of
   `NugetMetadataReader` against the same probe.

**Verified by reading:** string arguments are copied inside the thunk before the call
(`paramConversion`, `NugetGenerateShimsTask.kt:435-438`), so the `memScoped` `cstr` buffer dying
when `Begin` returns is safe; forward suspend exports run on `Dispatchers.Default`
(`SuspendFunctionExports.kt:179`), so in the xunit round trip the continuation is re-dispatched
off the .NET pool thread.

**Settled during implementation (2026-09-19), all four were inferred at design time:**

- A. **Verified.** `suspendCancellableCoroutine`'s `resume(value, onCancellation)` is safe from
  another thread on Kotlin/Native, tolerates the callback landing before the block returns, and
  hands the task handle to `onCancellation` when the coroutine was cancelled first.
  `nuget-runtime/src/nativeTest/.../AwaitForKotlinTest.kt` covers all four arms (inline
  completion, completion from another thread, cancel-then-complete releasing the task handle
  exactly once, a throwing `begin` releasing the ctx); the real .NET thread-pool resume is
  covered end to end by `KennelRoundTripTests`. `onCancellation` takes the 3-argument
  `(cause, value, context)` form on kotlinx.coroutines 1.10.2.
- B. **Verified.** An `internal expect suspend fun nugetAwaitTask` in `nativeMain` with its
  `actual` in `posixMain`/`mingwMain` compiles under the ADR-130 wiring, unchanged from the
  non-`suspend` case. No adjustment was needed.
- C. **Verified, the shape is admitted.** `Board_KotlinImplementedInterface_UsedAfterTheAwait`
  passes: the C# callee still reaches the Kotlin `IFeedable` after the `await`, and the Kotlin
  object observes the call (`goat.meals == 1`), even though the ADR-085 transfer scope freed the
  bridge handle when `Begin` returned. No skip, no diagnostic.
- D. **False, and fixed in the reader.** `ResolveByteArray` did NOT broadcast the single-byte
  `NullableAttribute(byte)` form to the node count, so a member whose method sits in a
  `NullableContext(2)` (`Kennel.LedgerAsync`) was skipped outright. Corrected in
  `NugetMetadataReader/Program.cs`; `Ledger_SingleByteNullableEncoding_Binds` is the regression
  row.

## Consequences

- Async methods on bound classes (instance and static) bind. Bound-interface members, struct
  methods, generic-class witness thunks, `ValueTask`, async-typed parameters and properties stay
  skipped with a named diagnostic.
- A cancelled C# task surfaces as `NugetManagedException("System.Threading.Tasks.TaskCanceledException")`
  in v1. The cancellation item maps it to `CancellationException` and adds the token; nothing
  here forecloses that: `awaitForKotlin` is already cancellable, `begin` is the seam where a
  `CancellationTokenSource` handle would be minted, and `invokeOnCancellation` is unused and free.
- Cancelling the Kotlin coroutine in v1 stops the wait promptly but does not stop the C# work.
- `nuget-runtime` gains one public function, so the runtime and plugin versions must move
  together (they already do, ADR-127/129).
- The first reverse-side handles counted in `nuget_live_handles` (ROADMAP line 279 notes the rest
  are uncounted). Three `LeakTests` rows assert the count returns to baseline after an await:
  `ReverseAsync_AwaitedCall_ReturnsToBaseline` (Row 9), the already-completed task hammered in a
  tight loop, `ReverseAsync_AlreadyCompletedTask_TightLoop_ReturnsToBaseline` (Row 9a), and the
  faulted task, `ReverseAsync_FaultedTask_ReturnsToBaseline` (Row 9b).
- `info_async_not_yet_mapped` stops firing for `Task`/`Task<T>` method returns; the non-generic
  `Task`/`ValueTask` misclassification as `skipped_unbound_type_reference` is fixed on the way.
