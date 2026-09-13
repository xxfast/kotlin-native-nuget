@file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

/**
 * ADR-128: drives [launchForCSharp] through a real `staticCFunction` callback, the same C function
 * type every suspend export passes. A `staticCFunction` cannot capture, so the callback records
 * into a holder reached through `userData`, exactly as the C# side reaches its own state.
 */
internal class LaunchResultHolder {
  var calls: Int = 0
  var result: COpaquePointer? = null
  var error: COpaquePointer? = null
  var cancelled: Byte = -1
}

private val resultCallback =
  staticCFunction<
      COpaquePointer?, COpaquePointer?, Byte, COpaquePointer?, Unit,
      > { result, error, cancelled, userData ->
    val holder: LaunchResultHolder = userData!!.asStableRef<LaunchResultHolder>().get()
    holder.calls++
    holder.result = result
    holder.error = error
    holder.cancelled = cancelled
  }

class LaunchForCSharpTest {

  @Test
  fun `result arm delivers the body handle with cancelled 0 and no error`() {
    val live: Long = NugetHandles.live.value
    val holder = LaunchResultHolder()
    val holderRef: StableRef<LaunchResultHolder> = StableRef.create(holder)

    val jobHandle: COpaquePointer = launchForCSharp(
      scope = CoroutineScope(Dispatchers.Default),
      callbackPtr = resultCallback,
      userData = holderRef.asCPointer(),
    ) {
      NugetHandles.retain("forty-two")
    }

    runBlocking { jobHandle.asStableRef<Job>().get().join() }

    assertEquals(1, holder.calls)
    assertEquals(0.toByte(), holder.cancelled)
    assertNull(holder.error)
    val resultHandle: COpaquePointer = assertNotNull(holder.result)
    assertEquals("forty-two", resultHandle.asStableRef<Any>().get())

    // job + result: the only two handles this route mints on the success arm.
    assertEquals(live + 2, NugetHandles.live.value)
    NugetHandles.release(resultHandle)
    NugetHandles.release(jobHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
  }

  @Test
  fun `cancel arm signals cancelled 1 with null result and error`() {
    val live: Long = NugetHandles.live.value
    val holder = LaunchResultHolder()
    val holderRef: StableRef<LaunchResultHolder> = StableRef.create(holder)

    val jobHandle: COpaquePointer = launchForCSharp(
      scope = CoroutineScope(Dispatchers.Default),
      callbackPtr = resultCallback,
      userData = holderRef.asCPointer(),
    ) {
      awaitCancellation()
    }

    val job: Job = jobHandle.asStableRef<Job>().get()
    runBlocking {
      job.cancel()
      job.join()
    }

    assertEquals(1, holder.calls)
    assertEquals(1.toByte(), holder.cancelled)
    assertNull(holder.result)
    assertNull(holder.error)
    assertTrue(job.isCancelled)

    // Only the job handle is minted on the cancel arm.
    assertEquals(live + 1, NugetHandles.live.value)
    NugetHandles.release(jobHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
  }

  @Test
  fun `error arm delivers a NugetError naming the thrown class`() {
    val live: Long = NugetHandles.live.value
    val holder = LaunchResultHolder()
    val holderRef: StableRef<LaunchResultHolder> = StableRef.create(holder)

    val jobHandle: COpaquePointer = launchForCSharp(
      scope = CoroutineScope(Dispatchers.Default),
      callbackPtr = resultCallback,
      userData = holderRef.asCPointer(),
    ) {
      throw IllegalStateException("boom")
    }

    runBlocking { jobHandle.asStableRef<Job>().get().join() }

    assertEquals(1, holder.calls)
    assertEquals(0.toByte(), holder.cancelled)
    assertNull(holder.result)
    val errorHandle: COpaquePointer = assertNotNull(holder.error)
    val error: NugetError = errorHandle.asStableRef<NugetError>().get()
    assertEquals("kotlin.IllegalStateException", error.type)
    assertEquals("boom", error.message)

    // job + error.
    assertEquals(live + 2, NugetHandles.live.value)
    NugetHandles.release(errorHandle)
    NugetHandles.release(jobHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
  }

  @Test
  fun `a body returning null delivers a null result - the Unit route wire shape`() {
    val live: Long = NugetHandles.live.value
    val holder = LaunchResultHolder()
    val holderRef: StableRef<LaunchResultHolder> = StableRef.create(holder)

    val jobHandle: COpaquePointer = launchForCSharp(
      scope = CoroutineScope(Dispatchers.Default),
      callbackPtr = resultCallback,
      userData = holderRef.asCPointer(),
    ) {
      null
    }

    runBlocking { jobHandle.asStableRef<Job>().get().join() }

    assertEquals(1, holder.calls)
    assertEquals(0.toByte(), holder.cancelled)
    assertNull(holder.result)
    assertNull(holder.error)

    assertEquals(live + 1, NugetHandles.live.value)
    NugetHandles.release(jobHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
  }
}
