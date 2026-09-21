@file:OptIn(ExperimentalForeignApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ADR-155: the whole shape of a C# `IAsyncEnumerable<T>` bound as a Kotlin `Flow<T>`, owned by the
 * runtime rather than re-emitted per method (ADR-128). A generated member supplies only the two
 * things that are specific to it — how to START an enumeration ([enumerate], which calls the C#
 * method and `GetAsyncEnumerator`) and how to READ the current element ([current], the ordinary
 * synchronous return half) — and the stepping, the ownership and the cancellation rules all live
 * here, once.
 *
 * COLD, in the Kotlin sense: [enumerate] runs inside `flow { }`, so the C# method is called at
 * each `collect` and never at the call that produced this `Flow`. That is also what lets one
 * per-collect `CancellationTokenSource` reach both an elided `CancellationToken` parameter and
 * `GetAsyncEnumerator`, and it is why no long-lived `IAsyncEnumerable` handle needs a `Cleaner`.
 *
 * PULL, not push: one element is produced only once the collector has asked for it, so
 * backpressure is inherent and there is no buffer to size. Each step is one ADR-152 begin/end
 * await, so every callback into Kotlin is still exactly-once and nothing in the reverse bridge
 * has to learn how to call back repeatedly while an operation is in flight.
 *
 * Ownership, on every path:
 * - the enumeration handle is minted by [enumerate] and released by [dispose] from the `finally`,
 *   exactly once, including when the collector throws, cancels or aborts early (`take(1)`).
 * - the per-step `ctx` and `Task` handles belong to [awaitForKotlin] and are unchanged here,
 *   including its verified cancel-then-complete window, on which [release] frees the task handle
 *   the completion delivered to nobody.
 *
 * [dispose] must NOT suspend: it runs from a `finally` that may already be executing under
 * cancellation, where a suspension point would be an immediate second cancellation. The C# side
 * therefore queues the real cleanup (cancel the source, await the pending step, `DisposeAsync`)
 * and returns at once, which is why `collect` can return before the C# iterator's `finally` has
 * run (ADR-155 open question 2).
 *
 * [cancelled] tells C# which arm to take: true means the collector gave up, so the source is
 * cancelled first (a token-honouring C# method stops promptly; one that ignores its token stops
 * at its next element, which no bridge can improve on).
 */
@NugetRuntimeApi
public fun <T> flowForKotlin(
  release: (task: COpaquePointer) -> Unit,
  enumerate: () -> COpaquePointer,
  moveNextBegin:
    (enumeration: COpaquePointer, callback: COpaquePointer, ctx: COpaquePointer) -> Unit,
  moveNextEnd: (task: COpaquePointer) -> Boolean,
  current: (enumeration: COpaquePointer) -> T,
  dispose: (enumeration: COpaquePointer, cancelled: Boolean) -> Unit,
): Flow<T> = flow {
  // Outside the try on purpose: if `enumerate` throws (a synchronous managed throw from argument
  // validation, ADR-104) there is no handle to dispose, and a `finally` around it would call
  // `dispose` with a pointer that was never minted.
  val enumeration: COpaquePointer = enumerate()
  var cancelled = false
  try {
    while (true) {
      // One step: begin the C# MoveNextAsync, suspend, resume with the completed Task's handle.
      // `null` is the CancellationTokenSource slot ADR-153 uses: the enumeration owns its source
      // for its whole life, so no per-step source is minted and the per-step cancel arm is inert.
      val task: COpaquePointer = awaitForKotlin(
        release = release,
        cancel = { _, _ -> },
        begin = { callback, ctx -> moveNextBegin(enumeration, callback, ctx); null },
      )
      // A mid-stream C# throw leaves through `moveNextEnd`'s error slot as a
      // NugetManagedException, on the collector's own coroutine.
      if (!moveNextEnd(task)) break
      // `emit` runs on the collector, never on a .NET thread: element materialisation cannot
      // throw inside an unmanaged callback (the reverse of the open forward `onNext` bug).
      emit(current(enumeration))
    }
  } catch (e: CancellationException) {
    cancelled = true
    throw e
  } finally {
    dispose(enumeration, cancelled)
  }
}
