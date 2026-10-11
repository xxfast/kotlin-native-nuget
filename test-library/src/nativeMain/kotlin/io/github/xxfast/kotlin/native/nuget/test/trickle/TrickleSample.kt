package io.github.xxfast.kotlin.native.nuget.test.trickle

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

/**
 * ADR-210 slice (b) fixture: a **`Flow<T>` parameter**, which binds as `IAsyncEnumerable<T>`.
 *
 * Kotlin sees a cold flow over the C# enumerable: `GetAsyncEnumerator` once per `collect`, one pull
 * per element, and the enumerator disposed exactly once however the collection ends. The fixture
 * crosses every seam the feature crosses and no more:
 *
 * ### Hosts
 *  - **class `suspend fun`** (legacy suspend route): everything `suspend` on [WaterFountain].
 *  - **`Flow`-returning member taking a `Flow`** (legacy Flow-return route): [WaterFountain.doubled].
 *  - **`suspend fun` on a sealed arm**: [Bowl.Ceramic.fill].
 *  - **non-suspend member on a sealed arm that stores the flow**: [Bowl.Ceramic.attach], collected
 *    later by [Bowl.Ceramic.fillFromAttached]. Today this one is not skipped but *silently dropped*
 *    (`Tier1AsyncParameterSealedArmTest`, the pinned defect).
 *  - **constructor that stores the flow**: [Dripper], collected later by [Dripper.drain].
 *  - **top-level `suspend fun`** (ADR-007 static class `TrickleSample`): [countDrops].
 *
 * ### Element types, each a different `Current` thunk
 *  - `Int`, by value, no conversion: [WaterFountain.totalDrops].
 *  - `String`, converted at `Current`: [WaterFountain.describe].
 *
 * ### Bodies that make the semantics observable
 *  - **cold**, two collections of one parameter: [WaterFountain.measuredTwice].
 *  - **early abort** after two elements: [WaterFountain.firstTwo].
 *  - Kotlin **catches** what the source threw and reports it: [WaterFountain.recover].
 *  - Kotlin lets it **propagate**: [WaterFountain.totalDrops] and every other member.
 *  - a **slow collector**, so an outer cancel lands between two pulls: [WaterFountain.lapSlowly].
 *  - a **collector that throws** on its own, mid-stream: [WaterFountain.chokeOnSecond].
 *
 * [WaterFountain.level] takes a `StateFlow<Int>` and is the one member here that must stay
 * **unbound**: ADR-210 refuses `StateFlow` / `SharedFlow` / `MutableStateFlow` parameters by name,
 * and that sentence needs a real declaration to name. No C# test calls it.
 *
 * Deliberately absent: a nullable `Flow<T>?`, a handle or nullable element, and a generic host.
 *
 * Oreo (black with the white middle) only drinks running water. Mylo (brown and creamy) watches
 * the drops and counts them.
 */
class WaterFountain(val cat: String) {
  /**
   * `Flow<Int>`: elements cross by value. A plain loop rather than `fold`, so the sum is
   * unreachable from a source that was pulled out of order or more than once per element. Kotlin
   * does not catch, so a throw from the C# iterator faults the outer `Task`.
   */
  suspend fun totalDrops(drops: Flow<Int>): Int {
    var total = 0
    drops.collect { drop -> total += drop }
    return total
  }

  /** `Flow<String>`: each element is converted at `Current`, after the pull that produced it. */
  suspend fun describe(sips: Flow<String>): String = sips.toList().joinToString("|")

  /**
   * **Cold**: the same parameter collected twice, so `GetAsyncEnumerator` must run twice and each
   * run must be disposed. Whether the second pass replays is the C# source's own property.
   */
  suspend fun measuredTwice(drops: Flow<Int>): String {
    val first: List<Int> = drops.toList()
    val second: List<Int> = drops.toList()
    return "${first.joinToString(",")}|${second.joinToString(",")}"
  }

  /**
   * **Early abort**: `take(2)` ends the collection while the C# iterator still has elements, so
   * the enumerator has to be disposed with the iterator parked at a `yield`, which is what runs
   * its `finally`.
   */
  suspend fun firstTwo(drops: Flow<Int>): String = drops.take(2).toList().joinToString(",")

  /**
   * The **Flow-return** route as host: an operator over the parameter. Nothing is enumerated until
   * C# collects the result, which is after this call has returned.
   */
  fun doubled(source: Flow<Int>): Flow<Int> = source.map { drop -> drop * 2 }

  /**
   * Kotlin **catches** whatever the source threw and reports the class, the message and how many
   * elements arrived first. A cancellation is rethrown on purpose, so a catch-all cannot hide it.
   */
  suspend fun recover(drops: Flow<Int>): String {
    var seen = 0
    return try {
      drops.collect { seen++ }
      "drank $seen"
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      "recovered after $seen ${e::class.simpleName}: ${e.message}"
    }
  }

  /**
   * The **collector throws**: a Kotlin exception raised inside `collect { }` on the second element.
   * Not a source fault and not a cancellation, so it is the fourth way a collection ends: the
   * enumerator must still be disposed exactly once, with the C# iterator parked at a `yield`, and
   * the outer `Task` faults with Kotlin's own exception.
   */
  suspend fun chokeOnSecond(drops: Flow<Int>): Int {
    var swallowed = 0
    drops.collect { drop ->
      if (swallowed == 1) throw IllegalStateException("$cat choked on drop $drop")
      swallowed++
    }
    return swallowed
  }

  /**
   * A **slow collector**: [millis] of suspension inside the collector for every element, so an
   * outer cancel lands while no pull is pending and the enumerator is disposed between two steps.
   */
  suspend fun lapSlowly(millis: Int, drops: Flow<Int>): Int {
    var laps = 0
    drops.collect { drop ->
      delay(millis.milliseconds)
      laps += drop
    }
    return laps
  }

  /**
   * The refusal's declaration: a `StateFlow<Int>` **parameter** is not bound (ADR-210). It must be
   * a named skip with its own sentence, and absent from C#.
   */
  fun level(gauge: StateFlow<Int>): Int = gauge.value
}

/** The sealed owner: a `Flow` parameter on an **arm**, at both positions an arm has. */
sealed class Bowl {
  /** A ceramic bowl that holds [capacity] drops before Oreo complains. */
  class Ceramic(val capacity: Int) : Bowl() {
    // Written on the C# caller's thread by [attach], read on a dispatcher thread afterwards.
    @Volatile
    private var attached: Flow<Int>? = null

    /** `suspend` on an arm, collecting during the call: how many drops fit under [capacity]. */
    suspend fun fill(drops: Flow<Int>): String {
      val poured: List<Int> = drops.toList()
      return "${poured.joinToString("+")}/$capacity"
    }

    /**
     * **Non-suspend** on an arm: it can only store the flow. Returns [capacity], so the call has a
     * result that proves it ran without enumerating anything.
     */
    fun attach(drops: Flow<Int>): Int {
      attached = drops
      return capacity
    }

    /** Collects the flow [attach] stored, long after `Attach` returned to C#. */
    suspend fun fillFromAttached(): String {
      val drops: Flow<Int> = attached ?: return "nothing attached"
      return "${drops.toList().joinToString("+")}/$capacity"
    }
  }

  /** Control arm: no flow anywhere near it. */
  data object Upturned : Bowl()
}

/**
 * The **constructor** host: the flow is stored by the constructor and first collected by [drain],
 * after the constructor has returned. Private storage, so the class's only flow seam is the
 * constructor parameter itself.
 */
class Dripper(private val drops: Flow<Int>) {
  /** Collects the constructor's flow. Each call is a fresh enumeration. */
  suspend fun drain(): String = drops.toList().joinToString(",")
}

/** The **top-level** suspend route, on the ADR-007 static class `TrickleSample`. */
suspend fun countDrops(drops: Flow<Int>): Int = drops.count()
