package io.github.xxfast.kotlin.native.nuget.test.membergeneric

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

/**
 * ADR-215 lane C: a `T` flowing INTO Kotlin asynchronously, on every generic-member owner that
 * has an async route: a class, a generic class and a generic sealed arm. (A `suspend` member on an
 * object or a companion has no route at all, `T` or not.) A `Flow<T>` parameter reads each element
 * as an owned box (`CurrentBoxed()`), a `suspend (T) -> T` lambda hands its result back as an
 * owned box (`EndBoxed()`), and a `suspend (T) -> Unit` payload rides lane A's boxed payload.
 * Kotlin reads every box exactly as lane A's result: read, dispose, cast to the bound.
 *
 * Each member returns its `T` values themselves (a `List<T>`, a `T`) rather than a summary, so a
 * wrapper element proves it is still the same cat after the round trip.
 *
 * Oreo drinks from the siphon; Mylo knocks the cistern over.
 */
class Siphon {
  /**
   * A `Flow<T>` parameter collected to the end:
   * `Task<IReadOnlyList<T>> GulpAsync<T>(IAsyncEnumerable<T>)`.
   */
  suspend fun <T> gulp(source: Flow<T>): List<T> = source.toList()

  /** Kotlin stops the C# source after [count] elements: the early abort disposes the enumerator. */
  suspend fun <T> sip(source: Flow<T>, count: Int): List<T> = source.take(count).toList()

  /** Collects slowly ([millis] per element), so the outer token can land between pulls. */
  suspend fun <T> lap(millis: Int, source: Flow<T>): List<T> = source.map { element ->
    delay(millis.toLong())
    element
  }.toList()

  /** An explicit `T?` element under a non-null bound: the nulls never become boxes. */
  suspend fun <T : Any> skim(source: Flow<T?>): List<T> = source.filterNotNull().toList()

  /** A bounded element, so Kotlin's read of each box casts to `Furry` before `.name`. */
  suspend fun <T : Furry> names(source: Flow<T>): String =
    source.toList().joinToString(",") { pet -> pet.name }

  /** A `suspend (T) -> T`: the lambda's `T` result crosses back as an owned box. */
  suspend fun <T> brew(value: T, step: suspend (T) -> T): T = step(value)

  /** A `suspend (T) -> Unit` payload, invoked once per item, sequentially. */
  suspend fun <T> tip(items: List<T>, cup: suspend (T) -> Unit): Int {
    items.forEach { item -> cup(item) }
    return items.size
  }

  /** A `Flow<T>` and a `suspend (T) -> T` together: each element goes through the lambda. */
  suspend fun <T> slosh(source: Flow<T>, step: suspend (T) -> T): List<T> =
    source.map { element -> step(element) }.toList()

  /** A trampolined `T` (ADR-198) as a `suspend (T) -> T` payload and result. */
  suspend fun <T : Enum<T>> retint(tint: T, step: suspend (T) -> T): String = step(tint).name

  /** A trampolined `T` as a `Flow<T>` element. */
  suspend fun <T : Enum<T>> tints(source: Flow<T>): String =
    source.toList().joinToString(",") { tint -> tint.name }

  /**
   * The control: a `suspend fun <T>` with no protocol at all, on ADR-214's boxed `T` shapes, which
   * every other member here also needs.
   */
  suspend fun <T> dribble(item: T): T = item

  /**
   * A `suspend` member taking a plain (non-suspend) lambda over `T`. Not bound: the legacy suspend
   * route lowers no plain lambda, `T` or not, so this is a named `SKIPPED_UNSUPPORTED_INPUT`.
   */
  suspend fun <T> decant(value: T, pour: (T) -> T): T = pour(value)
}

/** A generic class: its own `T` is the element and the lambda's payload and result. */
class Cistern<T>(val seed: T) {
  /** The seed, then every element of [source]. */
  suspend fun gulp(source: Flow<T>): List<T> = listOf(seed) + source.toList()

  /** The class's `T` as payload and result. */
  suspend fun brew(step: suspend (T) -> T): T = step(seed)

  /** The class's `T` as the payload, invoked twice so per-invocation ownership shows. */
  suspend fun tip(cup: suspend (T) -> Unit) {
    cup(seed)
    cup(seed)
  }
}

/** A generic sealed hierarchy whose arm carries the async members. */
sealed class Spill<out T> {
  class Puddle<T>(val seed: T) : Spill<T>() {
    /** The seed, then every element of [source]. */
    suspend fun gulp(source: Flow<T>): List<T> = listOf(seed) + source.toList()

    /** The arm's `T` as payload and result. */
    suspend fun brew(step: suspend (T) -> T): T = step(seed)
  }

  data object Mopped : Spill<Nothing>()
}
