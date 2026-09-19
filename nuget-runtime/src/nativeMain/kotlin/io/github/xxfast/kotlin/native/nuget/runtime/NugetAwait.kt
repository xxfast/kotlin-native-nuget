@file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.coroutines.resume
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

// ADR-152: the reverse async crossing, owned once by the runtime (the ADR-128 rule: the runtime
// owns the shape, the generated stub owns the call). A C# `Task`-returning member is registered as
// a Begin/End thunk pair; Begin starts the task and attaches one continuation that calls back with
// the task's GCHandle, and the resumed Kotlin stub calls End to unwrap it. Everything between
// those two crossings is here: the one `staticCFunction` the whole bridge shares, the pending
// record it resumes through, and the ownership rules for both handles.

// The Kotlin half of one in-flight call. Lives behind the `ctx` handle Begin carries, so the
// completion callback (which cannot capture) can find the continuation to resume.
private class NugetPendingTask(
  val continuation: CancellableContinuation<COpaquePointer>,
  val release: (task: COpaquePointer) -> Unit,
)

// The uniform completion callback: `(taskHandle, ctx) -> Unit`, called exactly once per Begin that
// returned without writing its error slot. Always arrives on a .NET thread-pool thread
// (TaskContinuationOptions.None), and may arrive before `begin` has returned when the task was
// already completed.
private fun nugetTaskCompleted(task: COpaquePointer?, ctx: COpaquePointer?) {
  val ctxHandle: COpaquePointer = requireNotNull(ctx) {
    "[nuget] the task completion callback was passed a null ctx; the C# shim predates ADR-152."
  }
  val taskHandle: COpaquePointer = requireNotNull(task) {
    "[nuget] the task completion callback was passed a null task handle."
  }
  val pending: NugetPendingTask = ctxHandle.asStableRef<NugetPendingTask>().get()
  // The ctx is done the moment it has been read: the callback fires exactly once, and the
  // continuation below may run arbitrary Kotlin that must not see it alive.
  NugetHandles.release(ctxHandle)
  // Cancelled before the completion arrived: nobody will ever call End, so the task's GCHandle
  // has no other owner. This arm is the only place it can be freed, and its absence is a SILENT
  // leak (AwaitForKotlinTest covers it).
  pending.continuation.resume(taskHandle) { _, value, _ -> pending.release(value) }
}

private val TASK_COMPLETED: COpaquePointer = staticCFunction(::nugetTaskCompleted)

/**
 * ADR-152: suspends until the C# `Task` started by [begin] completes, and returns the `GCHandle`
 * of that task, which the caller unwraps through the member's `End` thunk.
 *
 * [begin] is handed the shared completion callback and a fresh `ctx` handle to pass straight
 * through to the generated `Begin` thunk; it must start the task and attach exactly once, or throw
 * (a synchronous managed throw leaves through its error slot, and then the callback never fires).
 * [release] frees a task `GCHandle` that no `End` call will ever reach, used only when this
 * coroutine was cancelled before the completion arrived.
 *
 * The `ctx` handle is minted with [NugetHandles.retain], so an in-flight reverse await is visible
 * in `nuget_live_handles` (ADR-120) and a leak on either path is observable.
 */
@NugetRuntimeApi
public suspend fun awaitForKotlin(
  release: (task: COpaquePointer) -> Unit,
  begin: (callback: COpaquePointer, ctx: COpaquePointer) -> Unit,
): COpaquePointer = suspendCancellableCoroutine { continuation ->
  val ctx: COpaquePointer = NugetHandles.retain(NugetPendingTask(continuation, release))
  try {
    begin(TASK_COMPLETED, ctx)
  } catch (e: Throwable) {
    // Deliberately broad: whatever `begin` threw (the ADR-104 NugetManagedException for a
    // synchronous managed throw, or a Kotlin-side failure before the call), the callback will
    // never fire, so this is the only path on which `ctx` can be released.
    NugetHandles.release(ctx)
    throw e
  }
}
