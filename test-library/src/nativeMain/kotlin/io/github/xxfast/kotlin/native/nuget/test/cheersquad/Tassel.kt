package io.github.xxfast.kotlin.native.nuget.test.cheersquad

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-239: lambda-returning members on an ordinary class. Each returned lambda closes over the
 * instance, so the C# delegate provably calls back into this tassel. Oreo bats at the tassel.
 */
class Tassel(val colour: String) {
  /** Claps counted by [clap] and [stomp], so a C# call is observable from Kotlin. */
  var claps: Int = 0

  fun cheer(): (String) -> String = { who -> "$colour tassel for $who" }

  fun clap(): (Int) -> Unit = { times -> claps += times }

  fun chant(): suspend (String) -> String = { who ->
    delay(1.milliseconds)
    "$colour chant for $who"
  }

  fun stomp(): suspend (Int) -> Unit = { times ->
    delay(1.milliseconds)
    claps += times
  }

  fun maybeCheer(loud: Boolean): ((String) -> String)? =
    if (loud) { who -> "$colour roar for $who" } else null

  fun maybeChant(loud: Boolean): (suspend (String) -> String)? =
    if (loud) {
      { who ->
        delay(1.milliseconds)
        "$colour howl for $who"
      }
    } else {
      null
    }

  /** A lambda with two parameters, so arity is not only one. */
  fun pair(): (String, Int) -> String = { who, times -> "$who x$times in $colour" }

  /** A lambda with no parameter. */
  fun swish(): () -> String = { "$colour swish" }
}

/** ADR-239: lambda-returning members on an `object`, static on `Bleachers`. */
object Bleachers {
  var stomps: Int = 0

  fun cheer(): (String) -> String = { who -> "the bleachers cheer $who" }

  fun stomp(): (Int) -> Unit = { times -> stomps += times }

  fun chant(): suspend (String) -> String = { who ->
    delay(1.milliseconds)
    "the bleachers chant $who"
  }

  fun maybeCheer(loud: Boolean): ((String) -> String)? =
    if (loud) { who -> "the bleachers roar $who" } else null
}

/** ADR-239: lambda-returning members on a default companion, static on [Mascot]. */
class Mascot(val name: String) {
  /** An instance member beside the companion. */
  fun wave(): (String) -> String = { who -> "$name waves at $who" }

  companion object {
    fun cheer(): (String) -> String = { who -> "the mascot cheers $who" }

    fun chant(): suspend (String) -> String = { who ->
      delay(1.milliseconds)
      "the mascot chants $who"
    }

    fun maybeCheer(loud: Boolean): ((String) -> String)? =
      if (loud) { who -> "the mascot roars $who" } else null
  }
}

/** ADR-239: lambda-returning members on a NAMED companion, static on [Pennant]. */
class Pennant(val team: String) {
  companion object Flagpole {
    fun raise(): (Int) -> String = { height -> "raised $height paws high" }
  }
}

/**
 * ADR-239: a Kotlin interface whose members return lambdas. A Kotlin implementation reaches C#
 * through the backing wrapper ([megaphone]); a C# implementation reaches Kotlin through the bridge,
 * which then calls the delegate C# returned ([rally], [rallyLater], [rallyMaybe]).
 */
interface Chanter {
  fun cheer(): (String) -> String

  fun chant(): suspend (String) -> String

  fun maybeCheer(loud: Boolean): ((String) -> String)?
}

/** ADR-239: lambda-returning members on a sealed base and on an arm. */
sealed class Routine(val label: String) {
  /** Declared on the base, bound on every arm. */
  fun cheer(): (String) -> String = { who -> "$label for $who" }

  class Tumble(val flips: Int) : Routine("tumble") {
    fun flip(): (Int) -> String = { extra -> "${flips + extra} flips" }

    fun spin(): suspend (Int) -> Int = { turns ->
      delay(1.milliseconds)
      turns * flips
    }

    fun maybeFlip(clean: Boolean): ((Int) -> String)? =
      if (clean) { extra -> "${flips + extra} clean flips" } else null

    /** A listener a C# caller sets on the arm, which Kotlin calls in [land], clears in [stick]. */
    var onLand: ((String) -> String)? = null

    /** Calls [onLand] when set. */
    fun land(who: String): String = onLand?.invoke(who) ?: "$who wobbled"

    /** Kotlin drops whatever [onLand] holds. */
    fun stick() {
      onLand = null
    }
  }

  data object Pause : Routine("pause")
}

/** ADR-239: lambda-returning members on a generic class, typed by the closed `T`. */
class Megaphone<T>(val item: T) {
  fun echo(): (T) -> T = { next -> next }

  fun hold(): () -> T = { item }

  fun announce(): (String) -> String = { who -> "$who holds the megaphone" }

  fun maybeHold(loud: Boolean): (() -> T)? = if (loud) { { item } } else null

  fun holdLater(): suspend () -> T = {
    delay(1.milliseconds)
    item
  }
}

/** ADR-239: lambda-returning members on a value class, instance members of its record struct. */
value class Squad(val size: Int) {
  fun count(): (Int) -> Int = { rows -> rows * size }

  fun countLater(): suspend (Int) -> Int = { rows ->
    delay(1.milliseconds)
    rows * size
  }
}

/** ADR-239: a lambda-returning member in an enum body, a `FormationExtensions` member. */
enum class Formation {
  PYRAMID,
  LINE;

  fun call(): (String) -> String = { who -> "$who into the ${name.lowercase()}" }
}

/**
 * The listener half of ADR-239's leak rows: a Kotlin `object` holds a delegate C# hands it, calls
 * it, and later clears it itself ([unplug]), so the delegate must become collectable without C#
 * ever writing `null`. Mylo presses the buzzer for dinner.
 */
object Buzzer {
  var onBuzz: ((String) -> String)? = null

  /** Calls [onBuzz] when set. */
  fun buzz(who: String): String = onBuzz?.invoke(who) ?: "nobody answered $who"

  /** Kotlin drops whatever [onBuzz] holds. */
  fun unplug() {
    onBuzz = null
  }
}
