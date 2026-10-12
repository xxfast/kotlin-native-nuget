package io.github.xxfast.kotlin.native.nuget.test.outcome

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * ADR-124's flow route on the arms of a generic sealed hierarchy (ADR-199). The generic base
 * projects no flow member (ADR-147), so [served] binds on every arm.
 *
 *  - [Hot] forwards `T`: `Dish.Hot<T>` in C#, its exports reading the erased `Dish.Hot<Any?>`.
 *  - [Bare] fixes the variant `T` to `Nothing`: not generic in Kotlin, a phantom `Dish.Bare<T>` in
 *    C#, so only the C# half has a type argument to spell.
 *
 * [Hot] carries the three callback routes ADR-116 keys to an arm as well: a per-call lambda, a
 * stored-callback pair and an interface-bridge pair, all rung by [Hot.plate].
 *
 * Oreo's dinner is served hot. Mylo licked his plate bare.
 */
/** A diner waiting on a dish: the interface-bridge listener. */
interface Diner {
  fun onPlated(course: Int)
}

sealed class Dish<out T> {
  /** Declared on the generic base, bound on each arm. */
  val served: Flow<Int> = flowOf(1, 2, 3)

  class Hot<T>(val item: T) : Dish<T>() {
    /** The element is the arm's own type parameter. */
    val portions: Flow<T> = flowOf(item, item)

    /** A declared `MutableStateFlow`, so the write crosses too. */
    val heat: MutableStateFlow<Int> = MutableStateFlow(60)

    /** The method half of the route. */
    fun wafts(times: Int): Flow<Int> = flow { repeat(times) { index -> emit(index) } }

    private val bells: MutableList<(Int) -> Unit> = mutableListOf()
    private val diners: MutableList<Diner> = mutableListOf()

    /** A per-call lambda. The `Char` payload keeps it on the hand-written route (ADR-036). */
    fun spell(cb: (Char) -> Unit) = item.toString().forEach(cb)

    /** A stored-callback pair (ADR-037). */
    fun addBell(listener: (Int) -> Unit) {
      bells += listener
    }

    fun removeBell(listener: (Int) -> Unit) {
      bells -= listener
    }

    /** An interface-bridge pair (ADR-039). */
    fun addDiner(listener: Diner) {
      diners += listener
    }

    fun removeDiner(listener: Diner) {
      diners -= listener
    }

    /** Rings every bell and tells every diner; answers how many heard it. */
    fun plate(course: Int): Int {
      bells.forEach { bell -> bell(course) }
      diners.forEach { diner -> diner.onPlated(course) }
      return bells.size + diners.size
    }
  }

  class Bare(val crumbs: Int) : Dish<Nothing>() {
    val drips: StateFlow<Int> = MutableStateFlow(crumbs)

    suspend fun wipe(): Int = crumbs
  }
}
