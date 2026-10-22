package io.github.xxfast.kotlin.native.nuget.test.membergeneric

/**
 * ADR-215 lane A: a member's own `T` inside a non-suspend lambda. A `T` payload crosses Kotlin to
 * C# on the boxed `Object` wire (Kotlin retains, C# `FromHandle<T>` consumes), and a `T` result
 * crosses back as an owned box (`WrapOwned<T>`, through the generated `<lib>__retain_handle` when
 * C# hands back a wrapper it does not own), which Kotlin reads, disposes and casts to the bound.
 *
 * Oreo deals the cards, Mylo bats them off the table.
 */
class Dealer {
  /** A `T` payload: `Each<T>(IReadOnlyList<T>, Action<T>)`. */
  fun <T> each(items: List<T>, action: (T) -> Unit) {
    items.forEach(action)
  }

  /** A `T` payload and a `T` result: `Map<T>(T, Func<T, T>)`. */
  fun <T> map(value: T, transform: (T) -> T): T = transform(value)

  /** Two `T` payloads and a `T` result per invocation. */
  fun <T> fold(items: List<T>, initial: T, combine: (T, T) -> T): T = items.fold(initial, combine)

  /** A bounded `T` result, so Kotlin's read of the box casts to `Furry` before `.name`. */
  fun <T : Furry> rename(pet: T, pick: (T) -> T): String = pick(pet).name

  /** An explicit `T?` payload under a non-null bound. */
  fun <T : Any> peek(seat: T?, look: (T?) -> Unit) {
    look(seat)
  }

  /** A `T` result with no payload: only called when the seat is empty. */
  fun <T : Any> fill(seat: T?, fallback: () -> T): T = seat ?: fallback()

  /** A trampolined `T` (ADR-198) inside a lambda, both as the payload and as the result. */
  fun <T : Enum<T>> restyle(tint: T, step: (T) -> T): String = step(tint).name

  /** A lambda on a generic member that does not mention `T`. */
  fun <T> deal(item: T, count: Int, done: (Int) -> Unit): T {
    repeat(count) { index -> done(index) }
    return item
  }
}

/** A coat for [Dealer.restyle]: `T : Enum<T>` has no closed C# spelling, so it trampolines. */
enum class Tint { TUXEDO, MOCHA, CREAM }

/** ADR-211 top-level `fun <T>` returning a lambda over its own `T` (`ReturnedLambda`). */
fun <T> echoer(): (T) -> T = { item -> item }
