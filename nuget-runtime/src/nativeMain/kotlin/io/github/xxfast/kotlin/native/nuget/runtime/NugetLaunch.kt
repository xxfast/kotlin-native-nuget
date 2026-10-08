@file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class, DelicateCoroutinesApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.InternalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

// ADR-128: the coroutine launch shape every suspend and Flow export repeats, owned once. The
// helper owns `reinterpret`, `launch(start = CoroutineStart.ATOMIC)`, the three callback arms and
// `NugetHandles.retain(job)`; the caller owns the call and how its value becomes a handle. Neither
// helper is `inline` (a runtime bump must be able to change the body, ADR-127) and neither carries
// `@CName`: the 66 `nuget_*` names and the whole callback wire protocol are unchanged.

/**
 * Launches [body] for a C# `async` call and reports its outcome through [callbackPtr], whose C
 * function type is fixed at `(result, error, cancelled, userData) -> Unit`.
 *
 * [body] returns the result handle it has **already** minted with [NugetHandles.retain], or `null`
 * for the `Unit` route and for a null result. Returns the job handle, minted with
 * [NugetHandles.retain] so it counts in `nuget_live_handles` (ADR-120).
 */
@NugetRuntimeApi
public fun launchForCSharp(
  scope: CoroutineScope,
  callbackPtr: COpaquePointer,
  userData: COpaquePointer,
  mappedType: (Throwable) -> String?,
  body: suspend () -> COpaquePointer?,
): COpaquePointer {
  val callback = callbackPtr.reinterpret<CFunction<
        (COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()
  val job: Job = scope.launch(start = CoroutineStart.ATOMIC) {
    try {
      val resultRef: COpaquePointer? = body()
      callback.invoke(resultRef, null, 0.toByte(), userData)
    } catch (e: CancellationException) {
      callback.invoke(null, null, 1.toByte(), userData)
      throw e
    } catch (e: Throwable) {
      val errRef: COpaquePointer = NugetHandles.retain(buildError(e, mappedType))
      callback.invoke(null, errRef, 0.toByte(), userData)
    }
  }
  return NugetHandles.retain(job)
}

/**
 * Launches [body] for a C# `IAsyncEnumerable`/callback collect and reports through the fixed Flow
 * callback trio: `onNext(item, cancelled, userData)`, `onComplete(userData)`,
 * `onError(error, userData)`.
 *
 * [body] drives `emit` once per item with the handle it has already minted; the Flow source is
 * reached inside the coroutine, so a source that throws reaches C# as `onError` rather than
 * escaping the `@CName` export. Returns the collection handle, a [NugetFlowCollection].
 *
 * ADR-207: `emit` is credit-gated. It takes one credit before `onNext`, and the C# reader hands one
 * back through `nuget_flow_resume` after each item it reads, so at most one unread item sits on
 * the C# side and the flow body parks inside the next `emit`, exactly as a slow Kotlin collector
 * would park it. The first item rides a pre-filled credit. The cancel and error arms take none.
 */
@NugetRuntimeApi
public fun collectForCSharp(
  scope: CoroutineScope,
  onNextPtr: COpaquePointer,
  onCompletePtr: COpaquePointer,
  onErrorPtr: COpaquePointer,
  userData: COpaquePointer,
  mappedType: (Throwable) -> String?,
  body: suspend (emit: suspend (COpaquePointer?) -> Unit) -> Unit,
): COpaquePointer {
  val onNext = onNextPtr.reinterpret<CFunction<(COpaquePointer?, Byte, COpaquePointer) -> Unit>>()
  val onComplete = onCompletePtr.reinterpret<CFunction<(COpaquePointer) -> Unit>>()
  val onError = onErrorPtr.reinterpret<CFunction<(COpaquePointer?, COpaquePointer) -> Unit>>()
  val credits: Channel<Unit> = Channel(capacity = 1)
  credits.trySend(Unit)
  val job: Job = scope.launch(start = CoroutineStart.ATOMIC) {
    try {
      body { itemRef ->
        try {
          credits.receive()
        } catch (e: CancellationException) {
          // The caller minted this item before `emit`, and it never reaches C#, which releases
          // only what it receives: free it here or a cancel while parked leaks one handle.
          itemRef?.let(NugetHandles::release)
          throw e
        }
        onNext.invoke(itemRef, 0.toByte(), userData)
      }
      onComplete.invoke(userData)
    } catch (e: CancellationException) {
      onNext.invoke(null, 1.toByte(), userData)
      throw e
    } catch (e: Throwable) {
      val errRef: COpaquePointer = NugetHandles.retain(buildError(e, mappedType))
      onError.invoke(errRef, userData)
    }
  }
  NugetFlowCollections.track(job)
  return NugetHandles.retain(NugetFlowCollection(job, credits))
}

/**
 * ADR-207: the handle `collectForCSharp` returns. It IS-A [Job] by delegation, so
 * `nuget_job_cancel` and `nuget_job_dispose` keep reading it as one; [credits] is the gate
 * `nuget_flow_resume` refills. A `Channel<Unit>` of capacity 1 rather than a semaphore because an
 * over-resume must be a silent no-op (`trySend` on a full channel), never a throw.
 *
 * `Job` is subclass-opt-in in kotlinx.coroutines. The delegate is only ever cancelled, joined and
 * read for state through this handle; it is never installed in a coroutine context or used as a
 * parent, which is the use the opt-in guards.
 */
@NugetRuntimeApi
@OptIn(InternalForInheritanceCoroutinesApi::class)
public class NugetFlowCollection(
  job: Job,
  internal val credits: Channel<Unit>,
) : Job by job

/**
 * ADR-207: the collection jobs still running, so `nuget_scope_drain` can tell them from the
 * suspend calls it waits for. A collection whose reader stopped reading without disposing its
 * enumerator is parked on a credit nobody will return; joining it would hang the owner's
 * `DisposeAsync` forever, so the drain cancels every tracked child before it joins.
 *
 * The one window: a collection started concurrently with its owner's drain can be launched before
 * it is tracked here. Starting a collection on an owner that is being disposed is already a race
 * the caller owns (ADR-187).
 */
internal object NugetFlowCollections {
  private val live: AtomicReference<Set<Job>> = AtomicReference(emptySet())

  fun track(job: Job) {
    update { it + job }
    // Registered after `track`, so a job that already finished is untracked straight away.
    job.invokeOnCompletion { update { set -> set - job } }
  }

  fun isCollection(job: Job): Boolean = job in live.value

  private inline fun update(transform: (Set<Job>) -> Set<Job>) {
    while (true) {
      val current: Set<Job> = live.value
      if (live.compareAndSet(current, transform(current))) return
    }
  }
}
