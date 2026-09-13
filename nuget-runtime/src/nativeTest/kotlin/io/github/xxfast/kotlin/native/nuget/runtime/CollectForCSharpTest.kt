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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * ADR-128: drives [collectForCSharp] through the fixed Flow callback trio
 * (`onNext(item, cancelled, userData)`, `onComplete(userData)`, `onError(error, userData)`), the
 * same C function types every Flow export passes today.
 */
internal class CollectHolder {
  val items: MutableList<COpaquePointer?> = mutableListOf()
  var lastCancelled: Byte = -1
  var completes: Int = 0
  var error: COpaquePointer? = null
}

private val onNextCallback =
  staticCFunction<COpaquePointer?, Byte, COpaquePointer?, Unit> { item, cancelled, userData ->
    val holder: CollectHolder = userData!!.asStableRef<CollectHolder>().get()
    holder.items += item
    holder.lastCancelled = cancelled
  }

private val onCompleteCallback = staticCFunction<COpaquePointer?, Unit> { userData ->
  userData!!.asStableRef<CollectHolder>().get().completes++
}

private val onErrorCallback =
  staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { error, userData ->
    userData!!.asStableRef<CollectHolder>().get().error = error
  }

class CollectForCSharpTest {

  @Test
  fun `every item reaches onNext with cancelled 0 then onComplete once`() {
    val live: Long = NugetHandles.live.value
    val holder = CollectHolder()
    val holderRef: StableRef<CollectHolder> = StableRef.create(holder)

    val jobHandle: COpaquePointer = collectForCSharp(
      scope = CoroutineScope(Dispatchers.Default),
      onNextPtr = onNextCallback,
      onCompletePtr = onCompleteCallback,
      onErrorPtr = onErrorCallback,
      userData = holderRef.asCPointer(),
    ) { emit ->
      flowOf(1, 2, 3).collect { value -> emit(NugetHandles.retain(value as Any)) }
    }

    runBlocking { jobHandle.asStableRef<Job>().get().join() }

    assertEquals(3, holder.items.size)
    assertEquals(listOf(1, 2, 3), holder.items.map { it!!.asStableRef<Any>().get() })
    assertEquals(0.toByte(), holder.lastCancelled)
    assertEquals(1, holder.completes)
    assertNull(holder.error)

    // job + one handle per item.
    assertEquals(live + 4, NugetHandles.live.value)
    holder.items.forEach { NugetHandles.release(it!!) }
    NugetHandles.release(jobHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
  }

  @Test
  fun `cancel arm signals a null item with cancelled 1 and never completes`() {
    val live: Long = NugetHandles.live.value
    val holder = CollectHolder()
    val holderRef: StableRef<CollectHolder> = StableRef.create(holder)

    val jobHandle: COpaquePointer = collectForCSharp(
      scope = CoroutineScope(Dispatchers.Default),
      onNextPtr = onNextCallback,
      onCompletePtr = onCompleteCallback,
      onErrorPtr = onErrorCallback,
      userData = holderRef.asCPointer(),
    ) {
      awaitCancellation()
    }

    val job: Job = jobHandle.asStableRef<Job>().get()
    runBlocking {
      job.cancel()
      job.join()
    }

    assertEquals(1, holder.items.size)
    assertNull(holder.items.single())
    assertEquals(1.toByte(), holder.lastCancelled)
    assertEquals(0, holder.completes)
    assertNull(holder.error)
    assertTrue(job.isCancelled)

    assertEquals(live + 1, NugetHandles.live.value)
    NugetHandles.release(jobHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
  }

  @Test
  fun `a throwing body reaches onError with a NugetError naming the thrown class`() {
    val live: Long = NugetHandles.live.value
    val holder = CollectHolder()
    val holderRef: StableRef<CollectHolder> = StableRef.create(holder)

    val jobHandle: COpaquePointer = collectForCSharp(
      scope = CoroutineScope(Dispatchers.Default),
      onNextPtr = onNextCallback,
      onCompletePtr = onCompleteCallback,
      onErrorPtr = onErrorCallback,
      userData = holderRef.asCPointer(),
    ) {
      throw IllegalArgumentException("flow boom")
    }

    runBlocking { jobHandle.asStableRef<Job>().get().join() }

    assertEquals(0, holder.items.size)
    assertEquals(0, holder.completes)
    val errorHandle: COpaquePointer = assertNotNull(holder.error)
    val error: NugetError = errorHandle.asStableRef<NugetError>().get()
    assertEquals("kotlin.IllegalArgumentException", error.type)
    assertEquals("flow boom", error.message)

    // job + error.
    assertEquals(live + 2, NugetHandles.live.value)
    NugetHandles.release(errorHandle)
    NugetHandles.release(jobHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
  }
}
