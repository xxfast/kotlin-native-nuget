package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ADR-161 fixture: a C# callback that **throws** while Kotlin is calling it, a bridge-internal
 * materialisation failure, and a Kotlin invocation that lands after C# disposed the subscription.
 *
 * Before ADR-161 every throwing member below ended the host process: the generated thunk's
 * catch-all (`CirCallbackRenderer.appendThunkBody`) turned the managed exception into
 * `Environment.FailFast("nuget: unhandled exception in <delegate>", ex)`, which happens *below* the
 * Kotlin frame, so no `catch` on this class could run. The throw now arrives at the Kotlin
 * invocation site as a `NugetManagedException` carrying the managed type and message, which is what
 * [recoverWith] and its siblings catch.
 *
 * The fixture crosses every mechanism that runs user C# code inline (the four rows of the memo's
 * table), because a fixture trimmed to the per-call lambda would go green against a fix applied to
 * one emitter while the other three still fail fast:
 *
 *  - **per-call lambda** (ADR-036/160): [describeWith] (Kotlin does not catch), [recoverWith]
 *    (Kotlin catches, `String` payload, which needs conversion at the seam), [recoverWithCount]
 *    (Kotlin catches, `Int` payload, which crosses by value and needs none), [wrapWith] (Kotlin
 *    catches and rethrows its OWN exception type, the discriminating cell for "rethrow the original
 *    C# exception only when the escaping Kotlin error is the managed-exception type"),
 *  - **stored callback** (ADR-037): [addFaultListener]/[removeFaultListener]/[emit] with a
 *    `String` payload, [addTickListener]/[emitTick] with an `Int` payload, and [emitSafely], which
 *    counts the listeners that failed instead of letting the throw escape,
 *  - **C#-implemented interface member** (ADR-084 bridge slots): [greetVia] and [countVia], one
 *    value-returning member per payload kind. The ADR-039 add/remove listener pair is already
 *    covered by [CatEventSource], so this file does not duplicate it.
 *
 * The late-call half (memo item 3) is deterministic here rather than statistical:
 *
 *  - [callLastRemoved] invokes the stored listener whose subscription C# just disposed. The
 *    listener returns `Unit`, so the invocation must be dropped,
 *  - [callStashed] invokes a per-call lambda after the member that received it returned, which is
 *    the value-returning shape: there is no value to make up, so it must report an error into
 *    Kotlin rather than read a freed `GCHandle`.
 *
 * Both used to read a freed handle, whose slot the next `GCHandle.Alloc` deterministically reuses
 * (memo spike (b)), so they either failed fast or silently invoked a foreign delegate. Part C made
 * the ctx a never-reused key, so both are now lookup misses: the `Unit` one is dropped, the
 * value-returning one reports `System.ObjectDisposedException` through the part B channel.
 *
 * [moodStream] is the materialisation-failure trigger (memo item 2, what-question 5): `Flow<Mood>`
 * is admitted by the flow route but `NugetMarshal.FromHandle<T>` has no enum branch
 * (`docs/backlog/fromhandle-no-enum-branch.md`), so reading an item inside the generated `onNext`
 * throws inside the thunk. Part A contains that throw: the `IAsyncEnumerable<Mood>` faults and the
 * Kotlin collector is cancelled, where the host process used to die. The materialisation gap itself
 * is still open, which is why this member is a fault trigger and not a round trip.
 *
 * The stored-listener list is copy-on-write behind a `@Volatile` reference so the C# stress test
 * can subscribe and dispose on one thread while another thread emits: a plain `mutableListOf` would
 * crash on concurrent mutation and prove nothing about the bridge.
 *
 * Oreo throws the tantrums. Mylo just watches the process die.
 */
class CallbackFaults {
  // --- per-call lambda route (ADR-036/160) ------------------------------------------------------

  /**
   * Kotlin does **not** catch: a throw from [format] has to reach the C# caller of `DescribeWith`
   * as a catchable exception. `String` payload, so the argument handle is minted before the
   * callback runs and has to be released even on the throwing path.
   */
  fun describeWith(format: (String) -> String): String = format("Oreo")

  /**
   * Kotlin **catches** and reports what it caught, `String` payload. The report names the exception
   * class and message so the C# assertion can tell "Kotlin saw the managed exception" apart from
   * "Kotlin saw some other failure". `nativeMain` cannot name the managed-exception type today
   * (the runtime klib is not on this source set's compile classpath), so this catches `Exception`.
   */
  fun recoverWith(format: (String) -> String): String =
    try {
      "described ${format("Oreo")}"
    } catch (e: Exception) {
      "recovered ${e::class.simpleName}: ${e.message}"
    }

  /**
   * Kotlin catches, `Int` payload both ways: the payload crosses by value, so this cell stays red
   * for a fix that only routed the conversion-bearing (`String`) payload shape.
   */
  fun recoverWithCount(weigh: (Int) -> Int): String =
    try {
      "weighed ${weigh(7)}"
    } catch (e: Exception) {
      "recovered ${e::class.simpleName}: ${e.message}"
    }

  /**
   * Kotlin catches and rethrows an exception of its own. The C# caller must see the Kotlin wrapper
   * (`IllegalStateException`), never the original `InvalidOperationException`: the per-call
   * rethrow-the-original optimisation is only allowed when the escaping Kotlin error IS the
   * managed-exception type, and a fix that rethrows the stashed original unconditionally loses the
   * author's wrapper and fails here.
   */
  fun wrapWith(format: (String) -> String): String =
    try {
      format("Mylo")
    } catch (e: Exception) {
      throw IllegalStateException("Mylo knocked it over: ${e.message}", e)
    }

  // --- per-call lambda kept past the call that supplied it (memo item 3, value-returning) -------

  @Volatile
  private var stashed: ((String) -> String)? = null

  /** Keeps [format] for later, then calls it once while the call is still on the stack. */
  fun stashFormatter(format: (String) -> String): String {
    stashed = format
    return format("Oreo")
  }

  /**
   * Invokes the stashed lambda long after `StashFormatter` returned and its `GCHandle` was freed.
   * A value must be returned, so the honest answer is an error reported into Kotlin; what must not
   * happen is a freed-handle read (`null`, or a foreign delegate whose slot was reused).
   */
  fun callStashed(): String = stashed?.invoke("Mylo") ?: "nothing stashed"

  /** Reports whether [callStashed] failed rather than letting the failure escape. */
  fun callStashedSafely(): String =
    try {
      "stashed said ${callStashed()}"
    } catch (e: Exception) {
      "stale ${e::class.simpleName}: ${e.message}"
    }

  // --- stored callback route (ADR-037) ---------------------------------------------------------

  @Volatile
  private var listeners: List<(String) -> Unit> = emptyList()

  @Volatile
  private var lastRemoved: ((String) -> Unit)? = null

  @Volatile
  private var tickListeners: List<(Int) -> Unit> = emptyList()

  /** Stored listener, `String` payload. Copy-on-write so [emit] can run while this is called. */
  fun addFaultListener(listener: (String) -> Unit) {
    listeners = listeners + listener
  }

  /**
   * Removal keeps the removed listener in [lastRemoved], which is what [callLastRemoved] needs to
   * stay reachable: the generated subscription's `Dispose()` calls this and then frees the bridge's
   * `GCHandle`, so this reference is exactly the one whose ctx is dead.
   */
  fun removeFaultListener(listener: (String) -> Unit) {
    listeners = listeners - listener
    lastRemoved = listener
  }

  /** Stored listener, `Int` payload (crosses by value). */
  fun addTickListener(listener: (Int) -> Unit) {
    tickListeners = tickListeners + listener
  }

  fun removeTickListener(listener: (Int) -> Unit) {
    tickListeners = tickListeners - listener
  }

  /** Invokes every `String` listener with no protection: a throwing listener escapes to C#. */
  fun emit(value: String) {
    listeners.forEach { it(value) }
  }

  /** Invokes every `Int` listener with no protection. */
  fun emitTick(tick: Int) {
    tickListeners.forEach { it(tick) }
  }

  /** Counts the listeners that threw, which requires the throw to be catchable in Kotlin. */
  fun emitSafely(value: String): Int =
    listeners.count { listener -> runCatching { listener(value) }.isFailure }

  /**
   * Invokes the listener whose subscription C# most recently disposed. `Unit` return, so the
   * invocation is the one that must be **dropped** silently rather than read off a freed handle.
   */
  fun callLastRemoved(value: String) {
    lastRemoved?.invoke(value)
  }

  /** How many listeners are currently subscribed, so the race test can prove it kept working. */
  fun listenerCount(): Int = listeners.size

  // --- C#-implemented interface member (ADR-084 bridge slots) ----------------------------------

  /** Interface member with a `String` payload and a `String` result. Kotlin does not catch. */
  fun greetVia(listener: FaultListener): String = listener.onName("Oreo")

  /** Interface member with an `Int` payload and an `Int` result. Kotlin does not catch. */
  fun countVia(listener: FaultListener): Int = listener.onCount(7)

  /** Interface member whose throw Kotlin catches and reports. */
  fun greetViaSafely(listener: FaultListener): String =
    try {
      "greeted ${listener.onName("Mylo")}"
    } catch (e: Exception) {
      "recovered ${e::class.simpleName}: ${e.message}"
    }

  // --- bridge-internal materialisation failure (memo item 2) ----------------------------------

  /**
   * `Flow<Mood>`: an enum element, which the generated `onNext` reads through
   * `NugetMarshal.FromHandle<T>`, which has no enum branch. The read therefore throws inside the
   * thunk. The stream must fault (and the Kotlin collector cancel) instead of the host dying.
   */
  fun moodStream(): Flow<Mood> = flow {
    emit(Mood.HAPPY)
    emit(Mood.GRUMPY)
  }
}

/**
 * ADR-161: the interface a C# class implements so its members are called from Kotlin through the
 * ADR-084 bridge slots. Both members return a value, because a slot that has to produce a result
 * cannot answer a throw with a default: `null` would become an NPE at the Kotlin call site.
 */
interface FaultListener {
  /** `String` payload and result: conversion at the seam in both directions. */
  fun onName(name: String): String

  /** `Int` payload and result: crosses by value, no conversion. */
  fun onCount(count: Int): Int
}
