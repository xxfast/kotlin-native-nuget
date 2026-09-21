@file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ADR-155, the rows nothing else in the system observes: [flowForKotlin] disposes the C#
 * enumeration EXACTLY ONCE on every exit path, and tells C# whether the collector was cancelled.
 *
 * A leaked enumeration is silent (its `GCHandle` is uncounted by `nuget_live_handles`, like
 * ADR-153's source handle), and a double dispose would free a `GCHandle` twice. So disposal is
 * asserted by a counting fake here rather than by any integration test, which can only see the
 * elements that arrived.
 *
 * The C# side is faked at the ABI boundary: `moveNextBegin` fires the completion callback the
 * runtime handed it, exactly as the real `NugetTasks.Attach` continuation does.
 */
class FlowForKotlinTest {

  /**
   * Stands in for one enumeration's GCHandle. A FRESH object every call, deliberately: a
   * StableRef over the same interned string literal hands back the same address (measured), and
   * "each collect disposed its own handle" would then be unfalsifiable.
   */
  private fun fakeEnumeration(): COpaquePointer = StableRef.create(Any()).asCPointer()

  /** Stands in for the completed `Task<bool>` GCHandle a step resumes with. */
  private fun fakeTask(): COpaquePointer = StableRef.create("task").asCPointer()

  private class Disposals {
    val calls: MutableList<Pair<COpaquePointer, Boolean>> = mutableListOf()
  }

  private fun fire(callback: COpaquePointer, task: COpaquePointer, ctx: COpaquePointer) {
    callback.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>().invoke(task, ctx)
  }

  @Test
  fun `a full collect yields every element and disposes once - not cancelled`() {
    val enumeration: COpaquePointer = fakeEnumeration()
    val disposals = Disposals()
    var step = 0

    val items: List<Int> = runBlocking {
      flowForKotlin(
        release = { _ -> },
        enumerate = { enumeration },
        moveNextBegin = { _, callback, ctx -> fire(callback, fakeTask(), ctx) },
        moveNextEnd = { _ -> step++ < 3 },
        current = { _ -> step },
        dispose = { e, cancelled -> disposals.calls.add(e to cancelled) },
      ).toList()
    }

    assertEquals(listOf(1, 2, 3), items)
    assertEquals(listOf(enumeration to false), disposals.calls)
  }

  /**
   * Cold, the whole point of it: nothing runs until `collect`, and a second collect runs a SECOND
   * enumeration end to end. A flow that captured one enumeration would enumerate once and then
   * hand the second collector an exhausted iterator.
   */
  @Test
  fun `collecting twice runs two enumerations and disposes each`() {
    val disposals = Disposals()
    var enumerations = 0
    var step = 0

    val flow = flowForKotlin(
      release = { _ -> },
      enumerate = { enumerations++; fakeEnumeration() },
      moveNextBegin = { _, callback, ctx -> fire(callback, fakeTask(), ctx) },
      moveNextEnd = { _ -> step++ < 1 },
      current = { _ -> step },
      dispose = { e, cancelled -> disposals.calls.add(e to cancelled) },
    )

    assertEquals(0, enumerations, "the C# method must not run before collect")

    runBlocking {
      flow.toList()
      step = 0
      flow.toList()
    }

    assertEquals(2, enumerations)
    assertEquals(2, disposals.calls.size)
    assertTrue(disposals.calls.none { it.second }, "neither collect was cancelled")
    assertEquals(
      2, disposals.calls.map { it.first }.toSet().size,
      "each collect must dispose its OWN enumeration handle",
    )
  }

  /** `take`-style early abort: the collector stops between steps, C# is told it was cancelled. */
  @Test
  fun `an early abort disposes once`() {
    val enumeration: COpaquePointer = fakeEnumeration()
    val disposals = Disposals()

    val first: Int = runBlocking {
      flowForKotlin(
        release = { _ -> },
        enumerate = { enumeration },
        moveNextBegin = { _, callback, ctx -> fire(callback, fakeTask(), ctx) },
        moveNextEnd = { _ -> true },
        current = { _ -> 7 },
        dispose = { e, cancelled -> disposals.calls.add(e to cancelled) },
      ).first()
    }

    assertEquals(7, first)
    assertEquals(1, disposals.calls.size, "exactly one dispose, never zero and never two")
  }

  /**
   * The row that cannot be observed anywhere else: the collector is cancelled while a step is in
   * flight (the callback never fires, as for a C# method sitting in an uninterruptible wait). The
   * `finally` must still run, and must tell C# `cancelled = true` so the source is cancelled
   * before the queued dispose.
   */
  @Test
  fun `a collector cancelled mid step still disposes - with cancelled true`() {
    val enumeration: COpaquePointer = fakeEnumeration()
    val disposals = Disposals()

    val outcome: Unit? = runBlocking {
      withTimeoutOrNull(50) {
        flowForKotlin(
          release = { _ -> },
          enumerate = { enumeration },
          // Never completes: the step is pending when the timeout cancels the collector.
          moveNextBegin = { _, _, _ -> },
          moveNextEnd = { _ -> true },
          current = { _ -> 1 },
          dispose = { e, cancelled -> disposals.calls.add(e to cancelled) },
        ).toList()
        Unit
      }
    }

    assertNull(outcome, "the collect must have been cancelled, not completed")
    assertEquals(listOf(enumeration to true), disposals.calls)
  }

  /**
   * A mid-stream managed throw: it reaches the collector, and the enumeration is still disposed.
   */
  @Test
  fun `a throwing step disposes once and propagates`() {
    val enumeration: COpaquePointer = fakeEnumeration()
    val disposals = Disposals()

    assertFailsWith<IllegalStateException> {
      runBlocking {
        flowForKotlin(
          release = { _ -> },
          enumerate = { enumeration },
          moveNextBegin = { _, callback, ctx -> fire(callback, fakeTask(), ctx) },
          moveNextEnd = { _ -> error("woof") },
          current = { _ -> 1 },
          dispose = { e, cancelled -> disposals.calls.add(e to cancelled) },
        ).toList()
      }
    }

    // Not a cancellation: a managed fault must not be reported to C# as a collector cancel.
    assertEquals(listOf(enumeration to false), disposals.calls)
  }

  /** If `enumerate` itself throws there is no handle, so `dispose` must NOT run. */
  @Test
  fun `a throwing enumerate disposes nothing`() {
    val disposals = Disposals()

    assertFailsWith<IllegalStateException> {
      runBlocking {
        flowForKotlin<Int>(
          release = { _ -> },
          enumerate = { error("no kennel") },
          moveNextBegin = { _, _, _ -> },
          moveNextEnd = { _ -> true },
          current = { _ -> 1 },
          dispose = { e, cancelled -> disposals.calls.add(e to cancelled) },
        ).toList()
      }
    }

    assertTrue(disposals.calls.isEmpty(), "nothing was minted, so nothing may be disposed")
  }
}
