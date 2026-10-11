package io.github.xxfast.kotlin.native.nuget.test.treatprep

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ADR-210 slice (a) fixture: a **suspend lambda parameter**, `suspend (T) -> R`, which binds as
 * `Func<T, CancellationToken, Task<R>>` (`Func<T, CancellationToken, Task>` for `Unit`).
 *
 * Today every member below that takes one is a named skip, so the C# tests fail to compile on
 * exactly these members. The fixture crosses every seam the feature crosses and no more:
 *
 * ### Hosts
 *  - **class `suspend fun`** (legacy suspend route, the realistic host): everything on [TreatChef].
 *  - **`suspend fun` on a sealed arm** (ADR-118's arm suspend route): [Prep.Batch.cook].
 *  - **non-suspend member on a sealed arm that stores the lambda** (the ADR-062 plan route, pinned
 *    as a skip by `Tier1AsyncParameterSealedArmTest` cell (b)): [Prep.Batch.keep], invoked later
 *    by [Prep.Batch.cookKept].
 *  - **constructor that stores the lambda**: [TreatTimer], invoked later by [TreatTimer.ring]. The
 *    storing position the Kotlin-owned (cleaner) lifetime exists for.
 *  - **top-level `suspend fun`** (ADR-007 static class `TreatPrepSample`): [prepOnce].
 *
 * ### Lambda shapes, each one a different thunk
 *  - `String` result, which needs conversion in the End thunk: [TreatChef.plate].
 *  - `Int` result, which crosses by value and needs none: [TreatChef.weigh].
 *  - `Unit` result, the `Task` (not `Task<R>`) delegate: [TreatChef.serve].
 *  - arity 0: [TreatChef.announce]. Arity 2: [TreatChef.mix].
 *  - `String` payload, a handle-passed slot the Begin thunk has to free: [TreatChef.label].
 *
 * ### Bodies that make the semantics observable
 *  - sequential, several invocations per call: [TreatChef.plate], [TreatChef.weigh].
 *  - Kotlin **catches** what the lambda threw and reports it: [TreatChef.recover].
 *  - Kotlin lets it **propagate**: every other member, [TreatChef.plate] in the tests.
 *  - Kotlin **cancels** the invocation, so the C# token is seen cancelled: [TreatChef.impatient].
 *
 * Deliberately absent: a nullable `(suspend (T) -> R)?`, a generic host, a value class or
 * collection payload, and `Flow<T>` parameters (ADR-210 slice (b)).
 *
 * Oreo (black with the white middle) supervises the treat prep. Mylo (brown and creamy) eats it.
 */
class TreatChef(val cat: String) {
  /**
   * `String` result, invoked [count] times **sequentially**: invocation `n + 1` starts only after
   * invocation `n`'s `Task` completed. Kotlin does not catch, so a throw from [prepare] faults the
   * outer `Task`, and cancelling the outer token cancels the token [prepare] received.
   */
  suspend fun plate(count: Int, prepare: suspend (Int) -> String): String {
    val plated: MutableList<String> = mutableListOf()
    for (treat in 1..count) plated += prepare(treat)
    return plated.joinToString(",")
  }

  /**
   * `Int` result, by value. [times] is the leak harness's tight-loop dial: thousands of awaits
   * inside one coroutine, each of which can complete before its Begin thunk has returned.
   */
  suspend fun weigh(times: Int, scale: suspend (Int) -> Int): Int {
    var total = 0
    for (treat in 1..times) total += scale(treat)
    return total
  }

  /** `Unit` result: the lambda binds as `Func<int, CancellationToken, Task>`. */
  suspend fun serve(count: Int, bowl: suspend (Int) -> Unit): Int {
    for (treat in 1..count) bowl(treat)
    return count
  }

  /** Arity 0: `Func<CancellationToken, Task<string>>`. */
  suspend fun announce(call: suspend () -> String): String = "$cat hears: ${call()}"

  /** Arity 2: `Func<int, int, CancellationToken, Task<int>>`. Both payloads must arrive in order. */
  suspend fun mix(wet: Int, dry: Int, blend: suspend (Int, Int) -> Int): Int = blend(wet, dry)

  /** `String` payload and result: the UTF8 pair on the invocation and on the completion. */
  suspend fun label(tag: suspend (String) -> String): String = tag(cat)

  /**
   * Kotlin **catches** whatever [prepare] threw and reports the class and message it saw. A
   * cancellation is rethrown on purpose: a catch-all that swallowed it would hide whether a
   * cancelled C# `Task` really cancels the invoking coroutine.
   */
  suspend fun recover(prepare: suspend (Int) -> String): String =
    try {
      "prepared ${prepare(1)}"
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      "recovered ${e::class.simpleName}: ${e.message}"
    }

  /**
   * Kotlin **cancels** the invocation itself: [prepare] is given [millis] and then abandoned, so
   * the token the C# lambda received must read cancelled while the outer call still completes
   * normally.
   */
  suspend fun impatient(millis: Int, prepare: suspend (Int) -> String): String =
    withTimeoutOrNull(millis.milliseconds) { prepare(1) } ?: "$cat gave up waiting"
}

/** The sealed owner: a suspend lambda parameter on an **arm**, at both positions an arm has. */
sealed class Prep {
  /** A batch of [size] treats. */
  class Batch(val size: Int) : Prep() {
    // Written on the C# caller's thread by [keep], read on a Kotlin dispatcher thread by [cookKept].
    @Volatile
    private var kept: (suspend (Int) -> String)? = null

    /** `suspend` on an arm, invoking the lambda during the call. */
    suspend fun cook(prepare: suspend (Int) -> String): String {
      val cooked: MutableList<String> = mutableListOf()
      for (treat in 1..size) cooked += prepare(treat)
      return cooked.joinToString("+")
    }

    /**
     * **Non-suspend** on an arm: it can only store the lambda. Returns [size] so the call has a
     * result that proves it ran without invoking anything.
     */
    fun keep(prepare: suspend (Int) -> String): Int {
      kept = prepare
      return size
    }

    /** Invokes the lambda [keep] stored, long after `Keep` returned to C#. */
    suspend fun cookKept(): String {
      val prepare: suspend (Int) -> String = kept ?: return "nothing kept"
      val cooked: MutableList<String> = mutableListOf()
      for (treat in 1..size) cooked += prepare(treat)
      return cooked.joinToString("+")
    }
  }

  /** Control arm: no lambda anywhere near it. */
  data object Resting : Prep()
}

/**
 * The **constructor** host: the lambda is stored by the constructor and first invoked by [ring],
 * after the constructor has returned. Private storage, so the class's only async-lambda seam is
 * the constructor parameter itself.
 */
class TreatTimer(private val prepare: suspend (Int) -> String) {
  /** Invokes the constructor's lambda [count] times, sequentially. */
  suspend fun ring(count: Int): String {
    val rung: MutableList<String> = mutableListOf()
    for (treat in 1..count) rung += prepare(treat)
    return rung.joinToString(",")
  }
}

/** The **top-level** suspend route, on the ADR-007 static class `TreatPrepSample`. */
suspend fun prepOnce(seed: Int, prepare: suspend (Int) -> String): String = "once:${prepare(seed)}"
