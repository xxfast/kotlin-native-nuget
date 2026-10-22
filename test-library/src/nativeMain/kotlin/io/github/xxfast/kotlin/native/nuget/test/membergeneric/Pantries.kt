package io.github.xxfast.kotlin.native.nuget.test.membergeneric

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-219: the `suspend` members of a generic class and a generic sealed arm bind on the suspend
 * route over the erased receiver (`asStableRef<Platter<Any?>>()`, the first bound for a bounded
 * owner, `<*>` for a multi-bound one), the spelling the `Flow` route already uses (ADR-124/199,
 * ADR-215 lane B). Each owner `T` crosses on ADR-214's boxed shapes.
 *
 * Every mechanism the lane has to carry is crossed once:
 * - [Platter]: an unbounded owner `T` as a return ([Platter.take]), a parameter ([Platter.put]),
 *   nullable ([Platter.perhaps]), inside a `List`/`Set` ([Platter.stack], [Platter.sort]), a
 *   member that does not mention `T` ([Platter.count]), a member's own `U` ([Platter.swap]), a
 *   trampolined member-own `M : Enum<M>` ([Platter.rate]) and a never-ending call that only
 *   cancellation ends ([Platter.linger]).
 * - [Banquet]: a bounded owner `T : Furry`. The `T` parameter is read through the first bound; a
 *   C#-implemented `IFurry` is called back after the suspension.
 * - [Picnic]: a multi-bound owner (`where T : Furry, T : Tricky`): a `T` return over the `<*>`
 *   receiver, a `T` parameter through ADR-217's trampoline declaring both bounds.
 * - [Buffet.Tureen]: a generic sealed arm's own suspend members.
 * - [Buffet.Cart]: a generic sealed arm implementing an exported interface with a `suspend` member
 *   ([Grazer]), the ROADMAP Phase 6 CS0535 shape (`Hot<T> : Dish<T>(), Feed`).
 * - [rateLater]: a trampolined `T : Enum<T>` parameter and return on the top-level suspend route.
 *
 * Oreo (black with the white middle) and Mylo (brown and creamy) raid the pantry, eventually.
 */
class Platter<T>(private var served: T) {
  /** Whatever is on the platter, a moment later. */
  suspend fun take(): T {
    delay(1.milliseconds)
    return served
  }

  /** Swaps what is on the platter; [take] reads it back. */
  suspend fun put(item: T) {
    delay(1.milliseconds)
    served = item
  }

  /** The serving when [present], otherwise an empty plate. */
  suspend fun perhaps(present: Boolean): T? {
    delay(1.milliseconds)
    return if (present) served else null
  }

  /** A member that does not mention `T`: the length of what is served, as text. */
  suspend fun count(): Int {
    delay(1.milliseconds)
    return served.toString().length
  }

  /** A member's own type parameter on a generic owner. */
  suspend fun <U> swap(item: U): U {
    delay(1.milliseconds)
    return item
  }

  /** The serving appended to [items], so C# can tell the list was rebuilt on the Kotlin side. */
  suspend fun stack(items: List<T>): List<T> {
    delay(1.milliseconds)
    return items + served
  }

  /** A set of `T` with the serving added. */
  suspend fun sort(items: Set<T>): Set<T> {
    delay(1.milliseconds)
    return items + served
  }

  /**
   * A trampolined member-own `M : Enum<M>` (ADR-198). The body uses the enum API, so anything but
   * the real entry answers wrongly. Bronze is no prize.
   */
  suspend fun <M : Enum<M>> rate(medal: M): M {
    delay(1.milliseconds)
    require(medal.ordinal > 0) { "${medal.name} is no prize" }
    return medal
  }

  /** Never served: Mylo waits by the platter until someone calls him off. */
  suspend fun linger(): T = awaitCancellation()
}

/** A bounded owner: every guest is [Furry]. */
class Banquet<T : Furry>(private val guest: T) {
  /** The guest, a moment later. */
  suspend fun seat(): T {
    delay(1.milliseconds)
    return guest
  }

  /** Reads [other] through the bound only after the suspension, so after the extern returned. */
  suspend fun greet(other: T): String {
    delay(5.milliseconds)
    return "${guest.name} greets ${other.name}"
  }

  /** The newcomer takes the seat. */
  suspend fun replace(other: T): T {
    delay(1.milliseconds)
    return other
  }
}

/** A multi-bound owner: every guest is [Furry] and [Tricky]. */
class Picnic<T>(private val host: T) where T : Furry, T : Tricky {
  /** The host, over the `<*>` receiver. */
  suspend fun host(): T {
    delay(1.milliseconds)
    return host
  }

  /** Both bounds read on the Kotlin side. */
  suspend fun invite(guest: T): String {
    delay(1.milliseconds)
    return "${guest.name}:${guest.tricks}"
  }

  /** The one with more tricks wins, so both bounds and the `T` return cross. */
  suspend fun outshine(guest: T): T {
    delay(1.milliseconds)
    return if (guest.tricks > host.tricks) guest else host
  }
}

/** An exported interface with a `suspend` member, implemented by a generic sealed arm. */
interface Grazer {
  suspend fun graze(): Int
}

/** A generic sealed hierarchy whose arms carry suspend members of their own. */
sealed class Buffet<out T> {
  /** A generic arm with its own suspend members. */
  class Tureen<T>(private val ladle: T) : Buffet<T>() {
    /** The ladle, a moment later. */
    suspend fun serve(): T {
      delay(1.milliseconds)
      return ladle
    }

    /** Whatever was poured in comes back out. */
    suspend fun pour(item: T): T {
      delay(1.milliseconds)
      return item
    }

    /** A member's own type parameter on a generic arm. */
    suspend fun <U> garnish(item: U): String {
      delay(1.milliseconds)
      return "$ladle with $item"
    }

    /** Never ready; only cancellation ends it. */
    suspend fun simmer(): T = awaitCancellation()
  }

  /** The ROADMAP Phase 6 shape: a generic arm implementing [Grazer]. */
  class Cart<T>(private val load: T) : Buffet<T>(), Grazer {
    override suspend fun graze(): Int {
      delay(1.milliseconds)
      return load.toString().length
    }
  }

  data object Empty : Buffet<Nothing>()
}

/**
 * Returns the [Grazer] interface, so ADR-174 binds its `suspend` member on `IGrazer` and the arm has
 * to implement it: an interface nothing returns gets no `suspend` member at all, and the CS0535
 * shape needs one.
 */
fun cartOf(load: String): Grazer = Buffet.Cart(load)

/** A trampolined `T : Enum<T>` parameter and return on the top-level suspend route. */
suspend fun <T : Enum<T>> rateLater(medal: T): T {
  delay(1.milliseconds)
  require(medal.ordinal > 0) { "${medal.name} is no prize" }
  return medal
}
