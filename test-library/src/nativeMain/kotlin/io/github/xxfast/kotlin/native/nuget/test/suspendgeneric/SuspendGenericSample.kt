package io.github.xxfast.kotlin.native.nuget.test.suspendgeneric

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-214: a `suspend fun <T>` binds on the suspend route, one `_async` export each, every `T`
 * position on ADR-147's boxed-handle wire. C# gets `Task<T> FetchLaterAsync<T>(T value,
 * CancellationToken cancellationToken = default)` on the file facade.
 *
 * Every mechanism the route has to carry is crossed once:
 * - [fetchLater]: one bare `T` parameter and a `T` return. Before ADR-214 this one shape landed on
 *   the width-variant route and broke the consumer's Kotlin compile.
 * - [describeLater]: a bounded `T : Any` and a `String` return.
 * - [pickLater]: two `T` parameters.
 * - [perhapsLater]: a nullable `T?` in and out.
 * - [weighLater]: a dropped builtin bound (`Number`), so C# can pass a `string` and the ADR-015
 *   checked cast throws in the suspend prelude. That throw has to come back as a faulted `Task`.
 * - [snoozeLater]: an interface bound C# can implement. Kotlin calls it back after a suspension,
 *   so after the `_async` extern has returned and its `Wrap<T>` transfer handle is gone.
 * - [holdLater]: never completes, so only cancellation ends it with an owned box in flight.
 * - [shuffleLater], [dedupeLater], [keepLater]: ADR-212's `List<T>`, `Set<T>` and `Map<K, V>` of
 *   the function's own type parameters, in and out.
 * - [Courier]: a non-generic class's own suspend generics.
 * - [Delivery.Porch]: a non-generic sealed arm's own suspend generic.
 *
 * Oreo (black with the white middle) and Mylo (brown and creamy) get everything delivered late.
 */
suspend fun <T> fetchLater(value: T): T {
  delay(1.milliseconds)
  return value
}

/** Oreo's age, described a moment later. */
suspend fun <T : Any> describeLater(value: T): String {
  delay(1.milliseconds)
  return "$value: ${value::class.simpleName}"
}

/** Mylo still goes for the second bowl, eventually. */
suspend fun <T> pickLater(first: T, second: T): T {
  delay(1.milliseconds)
  return second
}

/** A nullable `T`: a missing treat stays missing. */
suspend fun <T> perhapsLater(value: T?): T? {
  delay(1.milliseconds)
  return value
}

/** `Number` has no C# spelling, so the read of the box is a checked cast. */
suspend fun <T : Number> weighLater(amount: T): Double {
  delay(1.milliseconds)
  return amount.toDouble()
}

/** Something that dozes off, and can be implemented on the C# side. */
interface Snoozer {
  fun murmur(): String
}

/** Calls the [Snoozer] back only after a suspension, so after the extern returned. */
suspend fun <T : Snoozer> snoozeLater(snoozer: T): String {
  delay(5.milliseconds)
  return "zzz ${snoozer.murmur()}"
}

/** Never completes: Mylo waits by the door for a delivery that never comes. */
suspend fun <T> holdLater(value: T): T = awaitCancellation()

/** Reversed, so C# can tell the list was rebuilt on the Kotlin side. */
suspend fun <T> shuffleLater(list: List<T>): List<T> {
  delay(1.milliseconds)
  return list.reversed()
}

/** A set of `T`, in and out. */
suspend fun <T> dedupeLater(items: Set<T>): Set<T> {
  delay(1.milliseconds)
  return items
}

/** A map of `K` to `V`, in and out. `K : Any` so the key is never null. */
suspend fun <K : Any, V> keepLater(map: Map<K, V>): Map<K, V> {
  delay(1.milliseconds)
  return map
}

/** A non-generic class whose suspend members declare their own `T`. */
class Courier {
  /** Hands back whatever it was given, after the trip. */
  suspend fun <T> ferry(value: T): T {
    delay(1.milliseconds)
    return value
  }

  /** A list of the member's own `T`, reversed on the way. */
  suspend fun <T> ferryAll(items: List<T>): List<T> {
    delay(1.milliseconds)
    return items.reversed()
  }

  /** Never arrives; only cancellation ends it. */
  suspend fun <T> stall(value: T): T = awaitCancellation()
}

/** A sealed base whose arm has no type parameter of its own. */
sealed class Delivery {
  /** Left on the porch, where Oreo sits on it. */
  class Porch(val mat: String) : Delivery() {
    suspend fun <T> leave(value: T): T {
      delay(1.milliseconds)
      return value
    }
  }

  data object Lost : Delivery()
}
