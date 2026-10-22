package io.github.xxfast.kotlin.native.nuget.test.membergeneric

/**
 * ADR-215 lane A: a generic owner's `T` inside a non-suspend lambda member. Before this ADR a
 * generic class's callback member was refused (ADR-147) and a generic sealed arm's was dropped as
 * `GENERIC_ARM_ERASED_LAMBDA` (ADR-199). Both now bind on the plan over the erased receiver.
 *
 * Oreo gets a serving of tuna; Mylo always asks for seconds.
 */
class Serving<T>(val portion: T) {
  /** The class's `T` as the payload, called twice so per-invocation ownership shows. */
  fun each(action: (T) -> Unit) {
    action(portion)
    action(portion)
  }

  /** The class's `T` as payload and result. */
  fun map(transform: (T) -> T): T = transform(portion)

  /** The class's `T` and the member's own `U` in one lambda. */
  fun <U> pair(other: U, combine: (T, U) -> String): String = combine(portion, other)
}

/** A generic sealed hierarchy whose arm carries the callback members. */
sealed class Helping<out T> {
  class Seconds<T>(val portion: T) : Helping<T>() {
    /** The arm's `T` as the payload. */
    fun each(action: (T) -> Unit) {
      action(portion)
    }

    /** The arm's `T` as payload and result. */
    fun map(transform: (T) -> T): T = transform(portion)
  }

  data object Licked : Helping<Nothing>()
}
