@file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
      val errRef: COpaquePointer = NugetHandles.retain(buildError(e))
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
 * escaping the `@CName` export. Returns the job handle.
 */
@NugetRuntimeApi
public fun collectForCSharp(
  scope: CoroutineScope,
  onNextPtr: COpaquePointer,
  onCompletePtr: COpaquePointer,
  onErrorPtr: COpaquePointer,
  userData: COpaquePointer,
  body: suspend (emit: (COpaquePointer?) -> Unit) -> Unit,
): COpaquePointer {
  val onNext = onNextPtr.reinterpret<CFunction<(COpaquePointer?, Byte, COpaquePointer) -> Unit>>()
  val onComplete = onCompletePtr.reinterpret<CFunction<(COpaquePointer) -> Unit>>()
  val onError = onErrorPtr.reinterpret<CFunction<(COpaquePointer?, COpaquePointer) -> Unit>>()
  val job: Job = scope.launch(start = CoroutineStart.ATOMIC) {
    try {
      body { itemRef -> onNext.invoke(itemRef, 0.toByte(), userData) }
      onComplete.invoke(userData)
    } catch (e: CancellationException) {
      onNext.invoke(null, 1.toByte(), userData)
      throw e
    } catch (e: Throwable) {
      val errRef: COpaquePointer = NugetHandles.retain(buildError(e))
      onError.invoke(errRef, userData)
    }
  }
  return NugetHandles.retain(job)
}
