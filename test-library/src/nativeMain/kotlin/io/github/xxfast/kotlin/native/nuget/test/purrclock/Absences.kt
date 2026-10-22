package io.github.xxfast.kotlin.native.nuget.test.purrclock

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-230 amendment: a nullable flow member (`Flow<T>?`, `StateFlow<T>?`, `SharedFlow<T>?`) binds
 * on every owner as the nullable holder (`KotlinFlow<T>?`), `null` when Kotlin returns null. Each
 * owner crosses a present and an absent member, a property and a function, and a generic `T`:
 * - the top level of this file, on the `Absences` file facade;
 * - [Snoozer], an `object`;
 * - [NapBasket.Companion], a default-named companion, and [NapBlanket.Fold], a named one;
 * - [Humour], an enum body (the entry as `this`) and its companion;
 * - [Feeler], a value class;
 * - [Kittens], a class, beside them for comparison.
 *
 * Oreo (black with the white middle) and Mylo (brown and creamy) are sometimes not home.
 */

// --- the top level

/** Oreo's purrs when [home], else no flow at all. */
fun purrsIfHome(home: Boolean): Flow<Int>? = if (home) flowOf(1, 2) else null

/** The top level's own `T`, or no flow. */
fun <T> echoIfHome(value: T, home: Boolean): Flow<T>? = if (home) flowOf(value) else null

/** A nullable `StateFlow` return. */
fun moodIfHome(home: Boolean): StateFlow<String>? = if (home) MutableStateFlow("purring") else null

/** A present nullable flow property. */
val doorbell: Flow<String>? = flowOf("ding")

/** An absent nullable flow property. */
val catFlap: Flow<String>? = null

/** A `suspend` member with a nullable flow result. */
suspend fun laterIfHome(home: Boolean): Flow<Int>? {
  delay(1.milliseconds)
  return if (home) flowOf(7) else null
}

// --- an `object`

/** Where the cats nap, when they nap. */
object Snoozer {
  /** Naps when [tired], else no flow. */
  fun naps(tired: Boolean): Flow<Int>? = if (tired) flowOf(3) else null

  /** The object's own `T`, or no flow. */
  fun <T> dream(value: T, tired: Boolean): Flow<T>? = if (tired) flowOf(value, value) else null

  /** A nullable `SharedFlow` return. */
  fun snores(tired: Boolean): SharedFlow<Int>? =
    if (tired) MutableSharedFlow<Int>(replay = 1).also { it.tryEmit(9) } else null

  /** A present nullable `StateFlow` property. */
  val pillow: StateFlow<Int>? = MutableStateFlow(4)

  /** An absent nullable flow property. */
  val alarm: Flow<Int>? = null

  /** A `suspend` member with a nullable `StateFlow` result. */
  suspend fun dozeIfTired(tired: Boolean): StateFlow<Int>? {
    delay(1.milliseconds)
    return if (tired) MutableStateFlow(5) else null
  }
}

// --- a default-named and a named companion

/** A basket a cat may be in. */
class NapBasket(val owner: String) {
  companion object {
    /** The cat in the basket, if any. */
    fun occupant(full: Boolean): Flow<String>? = if (full) flowOf("Mylo") else null

    /** An absent nullable flow property on a companion. */
    val lining: Flow<String>? = null
  }
}

/** A blanket folded through a NAMED companion. */
class NapBlanket(val colour: String) {
  companion object Fold {
    /** Folds when [neat], else no flow. */
    fun creases(neat: Boolean): Flow<Int>? = if (neat) flowOf(1, 2, 3) else null
  }
}

// --- an enum body and an enum companion

/** How a cat feels. */
enum class Humour {
  CONTENT,
  GRUMPY;

  /** Content cats purr; grumpy ones have no flow. */
  fun purr(): Flow<Int>? = if (this == CONTENT) flowOf(1) else null

  /** A nullable flow property on an enum body. */
  val hiss: Flow<String>? get() = if (this == GRUMPY) flowOf("hiss") else null

  companion object {
    /** The household's mood, when known. */
    fun household(known: Boolean): Flow<Humour>? = if (known) flowOf(CONTENT) else null
  }
}

// --- a value class

/** A whisker's length. */
value class Feeler(val length: Int) {
  /** Twitches when long enough, else no flow. */
  fun twitch(): Flow<Int>? = if (length > 0) flowOf(length) else null

  /** A nullable flow property on a value class. */
  val sway: Flow<Int>? get() = if (length > 0) flowOf(length, length) else null
}

// --- a class, for comparison

/** A litter of kittens. */
class Kittens(val size: Int) {
  /** The kittens' names, if there are any. */
  fun names(): Flow<String>? = if (size > 0) flowOf("Oreo", "Mylo")else null

  /** The class's own `T`, or no flow. */
  fun <T> tag(value: T): Flow<T>? = if (size > 0) flowOf(value) else null
}
