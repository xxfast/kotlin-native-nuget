package io.github.xxfast.kotlin.native.nuget.test.catnap

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-229: a `suspend` member of an owner with no C# instance scope binds on the top-level suspend
 * route with a static receiver expression: one `_async` export per member, an ad-hoc root scope
 * per call, and `Task<T> XAsync(..., CancellationToken cancellationToken = default)` in C#.
 *
 * Every owner the route reaches is crossed once, each with a result, a generic `T`, a fault and a
 * member that only cancellation ends:
 * - [Siesta]: an `object`, so a static method on `public static class Siesta`.
 * - [Swing.Companion]: a default-named companion, static on `Swing` beside its instance
 *   members. `sway(times)` shares its C# name with the instance `sway()`, with a different
 *   parameter list, so the two must render as a legal static/instance overload pair.
 * - [Quilt.Factory]: a NAMED companion, so the Kotlin call has to be owner-qualified.
 * - [NapSpot]: an enum body's members (receiver crosses as the ordinal) and its companion, both on
 *   `NapSpotExtensions`.
 * - [NapLength], [NapTag]: value-class members, over an `Int` and over a class handle, as
 *   instance methods on the record struct.
 * - the suspend extensions in `SwingExtensions.kt`.
 *
 * [Siesta.now] and [Swing.ready] never suspend, so the completion callback can run before the
 * `_async` P/Invoke returns.
 *
 * Oreo (black with the white middle) and Mylo (brown and creamy) nap wherever the sun is.
 */
object Siesta {
  /** Ten minutes of nap per letter of the name: Oreo gets forty, Mylo forty too. */
  suspend fun minutesFor(name: String): Int {
    delay(1.milliseconds)
    return name.length * 10
  }

  /** The object's own `T`, handed back after a doze. */
  suspend fun <T> echo(value: T): T {
    delay(1.milliseconds)
    return value
  }

  /** A class argument, read only after the suspension, so after the extern returned. */
  suspend fun wake(swing: Swing): String {
    delay(5.milliseconds)
    return "${swing.sleeper} is up"
  }

  /** A `Unit` return: `Task`, not `Task<T>`. */
  suspend fun settle() {
    delay(1.milliseconds)
  }

  /** Never completes: Mylo keeps sleeping until someone cancels. */
  suspend fun linger(): Int = awaitCancellation()

  /** Never completes with a boxed `T` in flight. */
  suspend fun <T> lingerWith(value: T): T = awaitCancellation()

  /** Oreo sat on the alarm clock. */
  suspend fun alarm(): Int {
    delay(1.milliseconds)
    throw IllegalStateException("Oreo sat on the alarm clock")
  }

  /** Never suspends, so completion can beat the P/Invoke's return. */
  suspend fun now(): Int = 1
}

/** A swing a cat sleeps in, made through its default-named companion. */
class Swing(val sleeper: String) {
  /** An instance suspend member, on the class's own scope. */
  suspend fun sway(): String {
    delay(1.milliseconds)
    return "$sleeper sways"
  }

  companion object {
    /** A companion factory on the suspend route: `Task<Swing> Swing.HangAsync(string)`. */
    suspend fun hang(sleeper: String): Swing {
      delay(1.milliseconds)
      return Swing(sleeper)
    }

    /** The companion's own `T`. */
    suspend fun <T> carry(value: T): T {
      delay(1.milliseconds)
      return value
    }

    /** Same C# name as the instance [Swing.sway], different parameters: a legal overload. */
    suspend fun sway(times: Int): String {
      delay(1.milliseconds)
      return "swayed $times times"
    }

    /** Never completes. */
    suspend fun creak(): String = awaitCancellation()

    /** Mylo is too heavy. */
    suspend fun snap(): Swing {
      delay(1.milliseconds)
      throw IllegalArgumentException("Mylo is too heavy for the swing")
    }

    /** Never suspends. */
    suspend fun ready(): Int = 2
  }
}

/** A quilt made through a NAMED companion, so the call is spelled `Quilt.knit`. */
class Quilt(val colour: String) {
  companion object Factory {
    /** Knitted after a short wait. */
    suspend fun knit(colour: String): Quilt {
      delay(1.milliseconds)
      return Quilt(colour)
    }
  }
}

/** Where a cat naps. The receiver of every body member crosses as its ordinal. */
enum class NapSpot(val label: String) {
  WINDOWSILL("the windowsill"),
  LAUNDRY("the laundry basket"),
  BOX("a cardboard box");

  /** A string argument and a string return. */
  suspend fun claim(cat: String): String {
    delay(1.milliseconds)
    return "$cat claims $label"
  }

  /** An enum return: the next spot round. */
  suspend fun next(): NapSpot {
    delay(1.milliseconds)
    return entries[(ordinal + 1) % entries.size]
  }

  /** The member's own `T`. */
  suspend fun <T> stash(value: T): T {
    delay(1.milliseconds)
    return value
  }

  /** Never completes: Oreo guards the spot. */
  suspend fun guard(): String = awaitCancellation()

  /** Mylo gets evicted. */
  suspend fun evict(): String {
    delay(1.milliseconds)
    throw IllegalStateException("Mylo got evicted from $label")
  }

  companion object {
    /** An enum companion member: `NapSpotExtensions.FavouriteAsync()`. */
    suspend fun favourite(): NapSpot {
      delay(1.milliseconds)
      return BOX
    }
  }
}

/** A nap's length, over an `Int`. */
value class NapLength(val minutes: Int) {
  /** A value-class return. */
  suspend fun doubled(): NapLength {
    delay(1.milliseconds)
    return NapLength(minutes * 2)
  }

  /** A string argument. */
  suspend fun describe(cat: String): String {
    delay(1.milliseconds)
    return "$cat naps for $minutes minutes"
  }

  /** The member's own `T`. */
  suspend fun <T> label(value: T): String {
    delay(1.milliseconds)
    return "$value: $minutes"
  }

  /** Never completes. */
  suspend fun oversleep(): Int = awaitCancellation()

  /** A zero-length nap is an error. */
  suspend fun cut(): NapLength {
    delay(1.milliseconds)
    throw IllegalStateException("Oreo never cuts a $minutes minute nap short")
  }
}

/** A tag over a class handle: the receiver's wire slot is the swing's handle. */
value class NapTag(val swing: Swing) {
  /** Reads the swing only after the suspension. */
  suspend fun read(): String {
    delay(5.milliseconds)
    return "tag: ${swing.sleeper}"
  }
}
