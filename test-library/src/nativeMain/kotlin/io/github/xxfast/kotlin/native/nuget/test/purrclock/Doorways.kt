package io.github.xxfast.kotlin.native.nuget.test.purrclock

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-210 amendment: a nullable `Flow<T>?` parameter takes a C# `IAsyncEnumerable<T>?`; a C#
 * `null` reaches Kotlin as `null`, and a present source is the ordinary cold flow. Every owner
 * crosses one, a `suspend` member, and a generic `T`.
 *
 * Oreo (black with the white middle) and Mylo (brown and creamy) come in through the cat flap,
 * when there is one.
 */

/** The cats through the flap, or `-1` when there is no flap at all. */
suspend fun countVisitors(visitors: Flow<Int>?): Int = visitors?.toList()?.sum() ?: -1

/** Synchronous: whether a flap was handed over. */
fun hasFlap(flap: Flow<String>?): Boolean = flap != null

/** The top level's own `T`: the first of [items], or [fallback] when there is no source. */
suspend fun <T> firstOr(items: Flow<T>?, fallback: T): T = items?.toList()?.firstOrNull() ?: fallback

/** A door on an `object`. */
object Doorman {
  /** The names through the door, joined, or `"nobody"`. */
  suspend fun greet(names: Flow<String>?): String = names?.toList()?.joinToString() ?: "nobody"

  /** Synchronous presence check on an `object`. */
  fun isOpen(door: Flow<Int>?): Boolean = door != null
}

/** A door on a class, its companion and a named companion. */
class Porch(val name: String) {
  /** An instance member: the count of [steps], or `0`. */
  suspend fun climb(steps: Flow<Int>?): Int {
    delay(1.milliseconds)
    return steps?.toList()?.size ?: 0
  }

  companion object {
    /** A companion member. */
    fun isLit(lamp: Flow<Int>?): Boolean = lamp != null
  }
}

/** A gate with a named companion. */
class Gate(val width: Int) {
  companion object Latch {
    /** A named companion's member. */
    suspend fun swing(times: Flow<Int>?): Int = times?.toList()?.size ?: -1
  }
}

/** An enum body member and a value-class member. */
enum class Hinge {
  OILED,
  RUSTY;

  /** The enum entry's own door. */
  suspend fun creak(squeaks: Flow<Int>?): Int = (squeaks?.toList()?.size ?: 0) + ordinal
}

/** A threshold height. */
value class Threshold(val height: Int) {
  /** The value class's door. */
  suspend fun step(over: Flow<Int>?): Int = (over?.toList()?.sum() ?: 0) + height
}
