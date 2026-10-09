@file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The runtime pair behind a `suspend fun (): StateFlow<T?>`: `nuget_stateflow_value_or_null`
 * returns a null handle for a null value, and `nuget_stateflow_collect` sends a null value as a
 * null item with `isCancelled = 0` instead of faulting the channel.
 */
private class Kitten(val name: String)

/** One `(item, isCancelled)` pair per `onNext`, appended together so a reader never sees half. */
private class NullableCollectHolder {
  val items: MutableList<Pair<COpaquePointer?, Byte>> = mutableListOf()
  var completes: Int = 0
  var error: COpaquePointer? = null
}

private val nullableOnNext =
  staticCFunction<COpaquePointer?, Byte, COpaquePointer?, Unit> { item, cancelled, userData ->
    userData!!.asStableRef<NullableCollectHolder>().get().items += item to cancelled
  }

private val nullableOnComplete = staticCFunction<COpaquePointer?, Unit> { userData ->
  userData!!.asStableRef<NullableCollectHolder>().get().completes++
}

private val nullableOnError =
  staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { error, userData ->
    userData!!.asStableRef<NullableCollectHolder>().get().error = error
  }

class StateFlowNullableTest {

  @Test
  fun `value or null reads a null value as a null handle and mints nothing`() {
    val live: Long = NugetHandles.live.value
    listOf<StateFlow<*>>(
      MutableStateFlow<Int?>(null),
      MutableStateFlow<String?>(null),
      MutableStateFlow<Kitten?>(null),
    ).forEach { flow ->
      val flowRef: StableRef<StateFlow<*>> = StableRef.create(flow)
      assertNull(export_nuget_stateflow_value_or_null(flowRef.asCPointer()))
      flowRef.dispose()
    }
    assertEquals(live, NugetHandles.live.value)
  }

  @Test
  fun `value or null boxes a present scalar string and object like the shipped read`() {
    val live: Long = NugetHandles.live.value
    val oreo = Kitten("Oreo")
    val streak = MutableStateFlow<Int?>(5)
    val nickname = MutableStateFlow<String?>("Mylo")
    val stray = MutableStateFlow<Kitten?>(oreo)
    val refs: List<StableRef<StateFlow<*>>> =
      listOf(streak, nickname, stray).map { StableRef.create(it) }

    val handles: List<COpaquePointer> = refs.map {
      assertNotNull(export_nuget_stateflow_value_or_null(it.asCPointer()))
    }
    assertEquals(5, export_nuget_unwrap_int(handles[0]))
    assertEquals("Mylo", export_nuget_unwrap_string(handles[1]))
    assertSame(oreo, handles[2].asStableRef<Any>().get())

    handles.forEach { NugetHandles.release(it) }
    refs.forEach { it.dispose() }
    assertEquals(live, NugetHandles.live.value)
  }

  @Test
  fun `collect sends a null value as a null item that is not a cancellation`() {
    val live: Long = NugetHandles.live.value
    val streak = MutableStateFlow<Int?>(null)
    val flowRef: StableRef<StateFlow<*>> = StableRef.create(streak)
    val holder = NullableCollectHolder()
    val holderRef: StableRef<NullableCollectHolder> = StableRef.create(holder)

    val jobHandle: COpaquePointer = export_nuget_stateflow_collect(
      flowHandle = flowRef.asCPointer(),
      scopeHandle = null,
      onNextPtr = nullableOnNext,
      onCompletePtr = nullableOnComplete,
      onErrorPtr = nullableOnError,
      userData = holderRef.asCPointer(),
    )
    runBlocking {
      withTimeout(10_000) {
        while (holder.items.size < 1) delay(1)
        // ADR-207: the first item spent the one credit; hand it back as the C# reader does.
        export_nuget_flow_resume(jobHandle)
        streak.value = 7
        while (holder.items.size < 2) delay(1)
      }
    }

    // Read before the cancel, which appends its own `(null, isCancelled = 1)` signal.
    val firstTwo: List<Pair<COpaquePointer?, Byte>> = holder.items.take(2)
    assertNull(firstTwo[0].first)
    assertEquals(0.toByte(), firstTwo[0].second)
    assertEquals(7, firstTwo[1].first!!.asStableRef<Any>().get())
    assertEquals(0.toByte(), firstTwo[1].second)
    assertNull(holder.error)

    val job: Job = jobHandle.asStableRef<Job>().get()
    runBlocking {
      job.cancel()
      job.join()
    }
    assertEquals(0, holder.completes)
    assertNull(holder.error)

    NugetHandles.release(firstTwo[1].first!!)
    NugetHandles.release(jobHandle)
    holderRef.dispose()
    flowRef.dispose()
    assertEquals(live, NugetHandles.live.value)
  }
}
