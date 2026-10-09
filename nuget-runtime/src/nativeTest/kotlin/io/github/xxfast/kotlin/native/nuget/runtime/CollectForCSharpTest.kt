@file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.concurrent.AtomicInt
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * ADR-128: drives [collectForCSharp] through the fixed Flow callback trio
 * (`onNext(item, cancelled, userData)`, `onComplete(userData)`, `onError(error, userData)`), the
 * same C function types every Flow export passes today.
 *
 * ADR-207: the collection is credit-gated. The first item is produced on the one pre-filled
 * credit; every later `emit` parks until `nuget_flow_resume` hands a credit back, which the C#
 * reader does after each `MoveNextAsync`. The tests resume from the test thread after observing
 * [CollectHolder.calls] grow, never from inside `onNext`: the first `onNext` can run before
 * `collectForCSharp` has returned the handle.
 */
internal class CollectHolder {
  val items: MutableList<COpaquePointer?> = mutableListOf()
  var lastCancelled: Byte = -1
  var completes: Int = 0
  var error: COpaquePointer? = null

  /** Bumped last in `onNext`, so a reader that observes it also sees the item it counts. */
  val calls: AtomicInt = AtomicInt(0)
}

private val onNextCallback =
  staticCFunction<COpaquePointer?, Byte, COpaquePointer?, Unit> { item, cancelled, userData ->
    val holder: CollectHolder = userData!!.asStableRef<CollectHolder>().get()
    holder.items += item
    holder.lastCancelled = cancelled
    holder.calls.incrementAndGet()
  }

private val onCompleteCallback = staticCFunction<COpaquePointer?, Unit> { userData ->
  userData!!.asStableRef<CollectHolder>().get().completes++
}

private val onErrorCallback =
  staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { error, userData ->
    userData!!.asStableRef<CollectHolder>().get().error = error
  }

/** The drain's completion, `(result, error, cancelled, userData)`, as `nuget_scope_drain` fires. */
private val drainCallback =
  staticCFunction<COpaquePointer?, COpaquePointer?, Byte, COpaquePointer?, Unit> { _, _, _, data ->
    data!!.asStableRef<AtomicInt>().get().incrementAndGet()
  }

private fun awaitCalls(holder: CollectHolder, count: Int) = runBlocking {
  withTimeout(10_000) { while (holder.calls.value < count) delay(1) }
}

/** Gives a producer that is about to park time to do something else instead, if it were able to. */
private fun settle() = runBlocking { delay(200) }

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
      mappedType = ::nugetStdlibMappedType,
    ) { emit ->
      flowOf(1, 2, 3).collect { value -> emit(NugetHandles.retain(value as Any)) }
    }

    // One credit back per item read, as the C# reader does.
    for (read in 1..2) {
      awaitCalls(holder, read)
      export_nuget_flow_resume(jobHandle)
    }
    runBlocking { withTimeout(10_000) { jobHandle.asStableRef<Job>().get().join() } }

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
  fun `a producer with no credit parks inside emit until the reader resumes it`() {
    val live: Long = NugetHandles.live.value
    val holder = CollectHolder()
    val holderRef: StableRef<CollectHolder> = StableRef.create(holder)
    val produced = AtomicInt(0)

    val jobHandle: COpaquePointer = collectForCSharp(
      scope = CoroutineScope(Dispatchers.Default),
      onNextPtr = onNextCallback,
      onCompletePtr = onCompleteCallback,
      onErrorPtr = onErrorCallback,
      userData = holderRef.asCPointer(),
      mappedType = ::nugetStdlibMappedType,
    ) { emit ->
      repeat(3) { index ->
        produced.incrementAndGet()
        emit(NugetHandles.retain(index as Any))
      }
    }

    awaitCalls(holder, 1)
    settle()
    // Item 0 rode the pre-filled credit; the body reached the second `emit` and parked in it.
    assertEquals(1, holder.calls.value)
    assertEquals(2, produced.value)

    export_nuget_flow_resume(jobHandle)
    awaitCalls(holder, 2)
    settle()
    assertEquals(2, holder.calls.value)
    assertEquals(0, holder.completes)

    // An over-resume is a silent no-op, never a throw: the credit channel holds one token.
    export_nuget_flow_resume(jobHandle)
    export_nuget_flow_resume(jobHandle)
    runBlocking { withTimeout(10_000) { jobHandle.asStableRef<Job>().get().join() } }

    assertEquals(listOf(0, 1, 2), holder.items.map { it!!.asStableRef<Any>().get() })
    assertEquals(1, holder.completes)
    holder.items.forEach { NugetHandles.release(it!!) }
    NugetHandles.release(jobHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
  }

  @Test
  fun `cancelling a parked producer reaches the cancel arm and releases the parked item`() {
    val live: Long = NugetHandles.live.value
    val holder = CollectHolder()
    val holderRef: StableRef<CollectHolder> = StableRef.create(holder)

    val jobHandle: COpaquePointer = collectForCSharp(
      scope = CoroutineScope(Dispatchers.Default),
      onNextPtr = onNextCallback,
      onCompletePtr = onCompleteCallback,
      onErrorPtr = onErrorCallback,
      userData = holderRef.asCPointer(),
      mappedType = ::nugetStdlibMappedType,
    ) { emit ->
      repeat(3) { index -> emit(NugetHandles.retain(index as Any)) }
    }

    awaitCalls(holder, 1)
    settle()
    val job: Job = jobHandle.asStableRef<Job>().get()
    runBlocking {
      job.cancel()
      withTimeout(10_000) { job.join() }
    }

    // Item 0, then the cancel signal; item 1 was minted but never crossed, so the gate freed it.
    assertEquals(2, holder.items.size)
    assertNull(holder.items[1])
    assertEquals(1.toByte(), holder.lastCancelled)
    assertEquals(0, holder.completes)
    assertNull(holder.error)
    assertTrue(job.isCancelled)

    // job + item 0, and nothing for the item parked in `emit`.
    assertEquals(live + 2, NugetHandles.live.value)
    NugetHandles.release(holder.items[0]!!)
    NugetHandles.release(jobHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
  }

  @Test
  fun `a body that throws after a parked emit reaches onError and never completes`() {
    val live: Long = NugetHandles.live.value
    val holder = CollectHolder()
    val holderRef: StableRef<CollectHolder> = StableRef.create(holder)

    val jobHandle: COpaquePointer = collectForCSharp(
      scope = CoroutineScope(Dispatchers.Default),
      onNextPtr = onNextCallback,
      onCompletePtr = onCompleteCallback,
      onErrorPtr = onErrorCallback,
      userData = holderRef.asCPointer(),
      mappedType = ::nugetStdlibMappedType,
    ) { emit ->
      emit(NugetHandles.retain(1 as Any))
      emit(NugetHandles.retain(2 as Any))
      throw IllegalStateException("conveyor jammed")
    }

    awaitCalls(holder, 1)
    settle()
    assertNull(holder.error)
    export_nuget_flow_resume(jobHandle)
    runBlocking { withTimeout(10_000) { jobHandle.asStableRef<Job>().get().join() } }

    assertEquals(listOf(1, 2), holder.items.map { it!!.asStableRef<Any>().get() })
    assertEquals(0, holder.completes)
    val errorHandle: COpaquePointer = assertNotNull(holder.error)
    val error: NugetError = errorHandle.asStableRef<NugetError>().get()
    assertEquals("kotlin.IllegalStateException", error.type)
    assertEquals("conveyor jammed", error.message)

    holder.items.forEach { NugetHandles.release(it!!) }
    NugetHandles.release(errorHandle)
    NugetHandles.release(jobHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
  }

  @Test
  fun `scope drain cancels a parked collection instead of waiting on it forever`() {
    val live: Long = NugetHandles.live.value
    val holder = CollectHolder()
    val holderRef: StableRef<CollectHolder> = StableRef.create(holder)
    val drained = AtomicInt(0)
    val drainedRef: StableRef<AtomicInt> = StableRef.create(drained)
    val scopeHandle: COpaquePointer =
      NugetHandles.retain(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    // The enumerator that read one item and was then neither read nor disposed: its producer is
    // parked on the credit when the owner's `DisposeAsync` drains the scope.
    val jobHandle: COpaquePointer = collectForCSharp(
      scope = scopeHandle.asStableRef<CoroutineScope>().get(),
      onNextPtr = onNextCallback,
      onCompletePtr = onCompleteCallback,
      onErrorPtr = onErrorCallback,
      userData = holderRef.asCPointer(),
      mappedType = ::nugetStdlibMappedType,
    ) { emit ->
      repeat(3) { index -> emit(NugetHandles.retain(index as Any)) }
    }
    awaitCalls(holder, 1)
    settle()

    val drainHandle: COpaquePointer =
      export_nuget_scope_drain(scopeHandle, drainCallback, drainedRef.asCPointer())
    runBlocking { withTimeout(10_000) { drainHandle.asStableRef<Job>().get().join() } }

    assertEquals(1, drained.value)
    assertEquals(1.toByte(), holder.lastCancelled)
    assertTrue(jobHandle.asStableRef<Job>().get().isCancelled)

    NugetHandles.release(holder.items[0]!!)
    NugetHandles.release(drainHandle)
    NugetHandles.release(jobHandle)
    NugetHandles.release(scopeHandle)
    drainedRef.dispose()
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
      mappedType = ::nugetStdlibMappedType,
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
      mappedType = ::nugetStdlibMappedType,
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
