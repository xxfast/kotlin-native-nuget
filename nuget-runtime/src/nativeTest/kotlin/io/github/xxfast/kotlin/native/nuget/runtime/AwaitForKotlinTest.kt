@file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class, ObsoleteWorkersApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * ADR-152 inferred claim A, settled by execution rather than by argument: the completion callback
 * `awaitForKotlin` hands to `begin` resumes a `suspendCancellableCoroutine` from ANOTHER thread
 * (the real bridge resumes from a .NET thread-pool thread), tolerates landing before `begin`
 * returns, and hands the task handle to `onCancellation` when the coroutine was cancelled first.
 *
 * The cancel-then-complete row is the reason this file exists: nothing else in the system observes
 * that path, and a task `GCHandle` dropped there is a SILENT leak.
 */
class AwaitForKotlinTest {

  // Stands in for the C# Task's GCHandle: any non-null pointer the callback carries back.
  private fun fakeTask(): COpaquePointer = StableRef.create("task").asCPointer()

  // Stands in for the CancellationTokenSource GCHandle `Begin` returns (ADR-153).
  private fun fakeSource(): COpaquePointer = StableRef.create("cts").asCPointer()

  // The counting fake for the `ReleaseCancellation(handle, cancel)` runtime slot: ADR-153's
  // exactly-once rule is a claim about how MANY times this is called, on every exit path.
  private class Releases {
    val calls: MutableList<Pair<COpaquePointer, Boolean>> = mutableListOf()

    fun record(source: COpaquePointer, cancelled: Boolean) {
      calls.add(source to cancelled)
    }
  }

  private fun never(source: COpaquePointer, cancelled: Boolean): Unit =
    fail("ReleaseCancellation must not run (got $source, cancelled=$cancelled)")

  private fun fire(callback: COpaquePointer, task: COpaquePointer, ctx: COpaquePointer) {
    callback.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>().invoke(task, ctx)
  }

  @Test
  fun `completing inline before begin returns still resumes with the task handle`() {
    val live: Long = NugetHandles.live.value
    val task: COpaquePointer = fakeTask()

    val onResume: (COpaquePointer) -> Unit =
      { fail("release must not run when the callback resumed") }
    val resumed: COpaquePointer = runBlocking {
      awaitForKotlin(release = onResume, cancel = ::never) { cb, ctx ->
        fire(cb, task, ctx)
        null
      }
    }

    assertEquals(task, resumed)
    assertEquals(live, NugetHandles.live.value, "the ctx handle must be released by the callback")
  }

  @Test
  fun `completing from another thread resumes the coroutine`() {
    val live: Long = NugetHandles.live.value
    val task: COpaquePointer = fakeTask()
    val worker: Worker = Worker.start(name = "nuget-await-test")

    val onResume: (COpaquePointer) -> Unit =
      { fail("release must not run when the callback resumed") }
    val resumed: COpaquePointer = runBlocking {
      awaitForKotlin(release = onResume, cancel = ::never) { cb, ctx ->
        val crossing = Triple(cb, task, ctx)
        worker.executeAfter(0L) {
          crossing.first.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>()
            .invoke(crossing.second, crossing.third)
        }
        null
      }
    }

    worker.requestTermination().result
    assertEquals(task, resumed)
    assertEquals(live, NugetHandles.live.value, "the ctx handle must be released by the callback")
  }

  // The silent one. Cancelled first, completed afterwards: the task GCHandle has no other owner,
  // so `resume`'s onCancellation arm is the only thing that can free it.
  @Test
  fun `cancel then complete releases the task handle exactly once`() {
    val live: Long = NugetHandles.live.value
    val task: COpaquePointer = fakeTask()
    val released: MutableList<COpaquePointer> = mutableListOf()
    var callback: COpaquePointer? = null
    var ctx: COpaquePointer? = null

    runBlocking {
      val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
        awaitForKotlin(release = { released.add(it) }, cancel = ::never) { cb, c ->
          callback = cb
          ctx = c
          null
        }
      }
      job.cancel()
      job.join()
      fire(callback!!, task, ctx!!)
    }

    assertEquals(listOf(task), released)
    assertEquals(live, NugetHandles.live.value, "the ctx handle must be released by the callback")
  }

  @Test
  fun `a throwing begin releases the ctx and propagates`() {
    val live: Long = NugetHandles.live.value

    val thrown: IllegalStateException = assertFailsWith {
      runBlocking {
        awaitForKotlin(
          release = { fail("release must not run when begin threw") },
          cancel = ::never,
        ) { _, _ ->
          error("Kennel.RejectAsync threw before any Task existed")
        }
      }
    }

    assertTrue(thrown.message!!.contains("before any Task existed"))
    assertEquals(live, NugetHandles.live.value, "the ctx handle must be released by begin's catch")
  }

  // ------------------------------------------------------------ ADR-153: the CTS handle

  // The normal exit: the task completed (or faulted, which is the same path here, the fault
  // surfaces from End), so the source is released with cancel=0 and the C# side disposes it.
  @Test
  fun `a completed token call releases the source once without cancelling`() {
    val task: COpaquePointer = fakeTask()
    val source: COpaquePointer = fakeSource()
    val releases = Releases()

    runBlocking {
      awaitForKotlin(
        release = { fail("release must not run") },
        cancel = releases::record,
      ) { cb, c ->
        fire(cb, task, c)
        source
      }
    }

    assertEquals(listOf(source to false), releases.calls)
  }

  // Cancelled before the completion arrived: the handler owns the handle and asks C# to cancel.
  // The `finally` must then see an empty cell, or the C# side frees the same GCHandle twice.
  @Test
  fun `cancelling before completion cancels the source exactly once`() {
    val task: COpaquePointer = fakeTask()
    val source: COpaquePointer = fakeSource()
    val releases = Releases()
    var callback: COpaquePointer? = null
    var ctx: COpaquePointer? = null

    runBlocking {
      val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
        awaitForKotlin(release = { }, cancel = releases::record) { cb, c ->
          callback = cb
          ctx = c
          source
        }
      }
      job.cancel()
      job.join()
      fire(callback!!, task, ctx!!)
    }

    assertEquals(listOf(source to true), releases.calls)
  }

  // ADR-153 inferred claim A: `invokeOnCancellation` registered on an ALREADY cancelled
  // continuation fires immediately. If it does not, the cancel that landed while `Begin` was
  // running is lost and the C# work runs on with a token nobody ever cancels. The `finally` still
  // releases the handle, so the only way this is visible is right here.
  @Test
  fun `a cancel landing while begin runs still cancels the source`() {
    val source: COpaquePointer = fakeSource()
    val releases = Releases()
    // The cancel has to land while `begin` is still on the stack, so it comes from a parent job
    // the begin lambda can name (the coroutine's own Job is not reachable from a non-suspend
    // lambda that runs before `launch` returns).
    val controller = Job()

    runBlocking {
      val job: Job = launch(controller, start = CoroutineStart.UNDISPATCHED) {
        awaitForKotlin(release = { }, cancel = releases::record) { _, _ ->
          controller.cancel()
          source
        }
      }
      job.join()
    }

    assertEquals(
      listOf(source to true),
      releases.calls,
      "a cancel that landed during begin must still reach the CancellationTokenSource",
    )
  }

  // ADR-153 inferred claim B: the coroutine cannot leave `suspendCancellableCoroutine` before the
  // block returns, so the handle `begin` returned is always visible to the release path even when
  // the task was already complete and resumed inline. If it is not, every already-completed call
  // leaks one CTS GCHandle.
  @Test
  fun `an already completed token call still releases the source`() {
    val task: COpaquePointer = fakeTask()
    val source: COpaquePointer = fakeSource()
    val releases = Releases()

    val resumed: COpaquePointer = runBlocking {
      awaitForKotlin(
        release = { fail("release must not run") },
        cancel = releases::record,
      ) { cb, c ->
        fire(cb, task, c)
        source
      }
    }

    assertEquals(task, resumed)
    assertEquals(listOf(source to false), releases.calls)
  }

  // Resumed, then cancelled before the resume was dispatched: kotlinx invokes BOTH the cancel
  // handler and `resume`'s onCancellation. Whichever of the handler and the `finally` swaps the
  // cell first owns the handle; the count is one either way.
  @Test
  fun `cancelling after the completion landed releases the source exactly once`() {
    val task: COpaquePointer = fakeTask()
    val source: COpaquePointer = fakeSource()
    val releases = Releases()
    var callback: COpaquePointer? = null
    var ctx: COpaquePointer? = null

    runBlocking {
      val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
        awaitForKotlin(release = { }, cancel = releases::record) { cb, c ->
          callback = cb
          ctx = c
          source
        }
      }
      fire(callback!!, task, ctx!!)
      job.cancel()
      job.join()
    }

    assertEquals(1, releases.calls.size, "exactly once, got: ${releases.calls}")
    assertEquals(source, releases.calls.single().first)
  }

  // The method threw before `Begin` minted anything: there is no handle, so nothing to release.
  @Test
  fun `a throwing begin releases no source`() {
    val releases = Releases()

    assertFailsWith<IllegalStateException> {
      runBlocking {
        awaitForKotlin(release = { }, cancel = releases::record) { _, _ ->
          error("Kennel.StayAsync threw before any CancellationTokenSource existed")
        }
      }
    }

    assertTrue(releases.calls.isEmpty(), "no handle was minted, got: ${releases.calls}")
  }

  // A member without a token: its Begin thunk still returns void, so the stub's begin lambda
  // hands back null and the release slot is never crossed.
  @Test
  fun `a token less call never crosses the release slot`() {
    val task: COpaquePointer = fakeTask()
    val releases = Releases()

    runBlocking {
      awaitForKotlin(
        release = { fail("release must not run") },
        cancel = releases::record,
      ) { cb, c ->
        fire(cb, task, c)
        null
      }
    }

    assertTrue(
      releases.calls.isEmpty(),
      "a null handle must not be released, got: ${releases.calls}",
    )
  }

  private fun fail(message: String): Nothing = throw AssertionError(message)
}
