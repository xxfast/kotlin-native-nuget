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
 * Oreo's dinner is served hot. Mylo licked his plate bare.
 */
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
  }

  class Bare(val crumbs: Int) : Dish<Nothing>() {
    val drips: StateFlow<Int> = MutableStateFlow(crumbs)

    suspend fun wipe(): Int = crumbs
  }
}
