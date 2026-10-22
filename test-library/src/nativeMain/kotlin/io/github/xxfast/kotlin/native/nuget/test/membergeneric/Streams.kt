package io.github.xxfast.kotlin.native.nuget.test.membergeneric

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-215 lane B: a type parameter as the element of a `Flow` Kotlin hands to C#. Each element
 * crosses on the boxed-handle wire a bare `T` uses (ADR-147), read by `KotlinFlow<T>` with
 * `FromHandle<T>`, so a builtin, a string and a generated wrapper all come out as themselves.
 *
 * Every mechanism the lane has to carry is crossed once:
 * - [Brook]: a generic class's own `T`. Its flow properties were dropped from C# with no
 *   diagnostic (ROADMAP "generic class flow property dropped whole"); they bind over the erased
 *   receiver the way `Dish.Hot<T>.portions` already does. [Brook.updates] is the `T` element,
 *   [Brook.ripples] a flow that does not mention `T`, [Brook.level] a `MutableStateFlow<T>` whose
 *   C# `Value` write crosses a `T` back in, [Brook.gaps] a `Flow<T?>` that emits a null between
 *   two values, [Brook.babble] a flow that never ends, so only cancellation stops it.
 * - [Creek]: a member's own `T` on a non-generic class: a bare `T` parameter on the Flow route
 *   ([Creek.stream], [Creek.echo]), a `Flow<T?>` that emits null for an unconstrained `T`
 *   ([Creek.hush]), a `Flow` that does not mention `T` on a generic member ([Creek.ticks]), a
 *   trampolined `T : Enum<T>` parameter ([Creek.cheer], [Creek.crown]) and a multi-bound `T`
 *   parameter ([Creek.rally]).
 * - [Pride]: a multi-bound owner `T` (`where T : Furry, T : Tricky`) as a `Flow` element, on a
 *   property and on a method that also takes a `T`.
 *
 * Oreo (black with the white middle) and Mylo (brown and creamy) sit by the brook watching it run.
 */
class Brook<T>(val seed: T) {
  /** The class's own `T`: two ripples of whatever was dropped in. */
  val updates: Flow<T> = flowOf(seed, seed)

  /** A flow on a generic class that does not mention `T`. */
  val ripples: Flow<Int> = flowOf(1, 2, 3)

  /** A `MutableStateFlow<T>`: C# reads, writes and collects a `T`. */
  val level: MutableStateFlow<T> = MutableStateFlow(seed)

  /** A nullable element: the seed, a gap, then the seed again. */
  val gaps: Flow<T?> = flowOf(seed, null, seed)

  /** The method half: the seed [times] times. */
  fun flowing(times: Int): Flow<T> = flow {
    repeat(times) { emit(seed) }
  }

  /** Never ends: Mylo keeps batting at the water until someone calls him in. */
  fun babble(): Flow<T> = flow {
    while (true) {
      emit(seed)
      delay(1.milliseconds)
    }
  }
}

/** A non-generic class whose Flow-returning members declare their own `T`. */
class Creek {
  /** A bare `T` parameter, one element out. */
  fun <T> stream(value: T): Flow<T> = flowOf(value)

  /** A bare `T` and a primitive together: the value [times] times. */
  fun <T> echo(value: T, times: Int): Flow<T> = flow {
    repeat(times) { emit(value) }
  }

  /** An unconstrained `T` element that is null in the middle. */
  fun <T> hush(value: T): Flow<T?> = flowOf(value, null)

  /** A generic member whose `Flow` does not mention `T`: the length of the name, counted down. */
  fun <T> ticks(value: T): Flow<Int> = flow {
    (value.toString().length downTo 1).forEach { tick -> emit(tick) }
  }

  /**
   * A trampolined `T : Enum<T>` parameter (ADR-198). The body uses the enum API, so anything but
   * the real entry answers wrongly.
   */
  fun <T : Enum<T>> cheer(medal: T): Flow<String> = flowOf(medal.name, "#${medal.ordinal}")

  /** The same bound with the `T` coming back out as the element. Bronze is no prize. */
  fun <T : Enum<T>> crown(medal: T): Flow<T> = flow {
    require(medal.ordinal > 0) { "${medal.name} is no prize" }
    emit(medal)
  }

  /** A multi-bound `T` parameter: both bounds are used on the Kotlin side. */
  fun <T> rally(pet: T): Flow<String> where T : Furry, T : Tricky =
    flowOf("${pet.name}:${pet.tricks}")
}

/** A multi-bound owner `T` returned as a `Flow` element. Oreo leads, Mylo follows. */
class Pride<T>(val leader: T) where T : Furry, T : Tricky {
  /** The leader, once. */
  val members: Flow<T> = flowOf(leader)

  /** The leader then the newcomer, so C# can tell both crossed. */
  fun join(pet: T): Flow<T> = flowOf(leader, pet)
}
