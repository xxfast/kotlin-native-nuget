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
      awaitForKotlin(release = onResume) { cb, ctx ->
        fire(cb, task, ctx)
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
      awaitForKotlin(release = onResume) { cb, ctx ->
        val crossing = Triple(cb, task, ctx)
        worker.executeAfter(0L) {
          crossing.first.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>()
            .invoke(crossing.second, crossing.third)
        }
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
        awaitForKotlin(release = { released.add(it) }) { cb, c ->
          callback = cb
          ctx = c
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
        awaitForKotlin(release = { fail("release must not run when begin threw") }) { _, _ ->
          error("Kennel.RejectAsync threw before any Task existed")
        }
      }
    }

    assertTrue(thrown.message!!.contains("before any Task existed"))
    assertEquals(live, NugetHandles.live.value, "the ctx handle must be released by begin's catch")
  }

  private fun fail(message: String): Nothing = throw AssertionError(message)
}
