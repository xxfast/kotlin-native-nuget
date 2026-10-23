package io.github.xxfast.kotlin.native.nuget.test.purrclock

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-230: a `Flow`, `StateFlow`, `MutableStateFlow`, `SharedFlow` or `MutableSharedFlow` return or
 * property on an owner with no C# instance scope binds as a static `KotlinFlow<T>` (or its hot
 * variant) collected on an ad-hoc root scope, one per collection. Each owner crosses every flow
 * kind it can hold, a function and a property, a generic `T`, a fault, a member only cancellation
 * ends, and a `suspend` member with a flow result:
 * - the top level of this file, on the `ChimesKt` file facade;
 * - [PurrClock], an `object`;
 * - [Cuckoo.Companion], a default-named companion, beside the class's own instance members;
 * - [Hourglass.Sand], a NAMED companion, so the Kotlin call has to be owner-qualified.
 *
 * Elements cross the `int` (no conversion), `string` (box), enum (ordinal) and class handle seams.
 *
 * Oreo (black with the white middle) and Mylo (brown and creamy) keep their own time.
 */

/** How a cat is lying when the clock strikes. */
enum class Posture { CURLED, SPRAWLED, LOAF }

/** A cat the clock hands out by handle. */
class ClockCat(val name: String)

// --- the top level

/** `count` purrs, numbered from zero. */
fun purrs(count: Int): Flow<Int> = flow { repeat(count) { emit(it) } }

/** The top level's own `T`, echoed [times] times. */
fun <T> echoes(value: T, times: Int): Flow<T> = flow { repeat(times) { emit(value) } }

/** Mylo knocked the clock off the shelf after one purr. */
fun droppedPurrs(): Flow<Int> = flow {
  emit(1)
  throw IllegalStateException("Mylo knocked the clock off the shelf")
}

/** Purrs until someone stops listening. */
fun endlessPurrs(): Flow<Int> = flow {
  var n = 0
  while (true) {
    emit(n++)
    delay(1.milliseconds)
  }
}

/** The dinner bell's current line. */
fun dinnerBell(): StateFlow<String> = dinnerBellState

private val dinnerBellState: MutableStateFlow<String> = MutableStateFlow("Oreo, dinner")

/** A top-level cold flow property: re-read on every collection. */
val chorus: Flow<String> = flowOf("Oreo", "Mylo")

/** A top-level held `MutableStateFlow` property: the hour the cats go to bed. */
val bedtime: MutableStateFlow<Int> = MutableStateFlow(21)

/** A top-level `MutableSharedFlow` property, replaying the last two yowls. */
val yowls: MutableSharedFlow<String> = MutableSharedFlow(replay = 2)

// --- an `object`

/** The house clock the cats nap by. */
object PurrClock {
  private val hourState: MutableStateFlow<Int> = MutableStateFlow(9)
  private val postureState: MutableStateFlow<Posture> = MutableStateFlow(Posture.LOAF)
  private val bulletinSource: MutableSharedFlow<Int> =
    MutableSharedFlow<Int>(replay = 1).also { it.tryEmit(7) }

  /** A cold flow property on the object. */
  val beats: Flow<Int> = flowOf(1, 2)

  /** A read-only `StateFlow` property over an enum. */
  val pose: StateFlow<Posture> = postureState

  /** A held `MutableStateFlow` property: the hour on the clock face. */
  val hour: MutableStateFlow<Int> = hourState

  /** A read-only `SharedFlow` property. */
  val bulletin: SharedFlow<Int> = bulletinSource

  /** A held `MutableSharedFlow` property, replaying the last two sightings. */
  val sightings: MutableSharedFlow<String> = MutableSharedFlow(replay = 2)

  /** [count] ticks, numbered from zero. */
  fun ticks(count: Int): Flow<Int> = flow { repeat(count) { emit(it) } }

  /** The object's own `T`, echoed [times] times. */
  fun <T> echo(value: T, times: Int): Flow<T> = flow { repeat(times) { emit(value) } }

  /** The cats by name, each a fresh class handle. */
  fun clockCats(): Flow<ClockCat> = flowOf(ClockCat("Oreo"), ClockCat("Mylo"))

  /** Ticks until cancelled. */
  fun forever(): Flow<Int> = flow {
    var n = 0
    while (true) {
      emit(n++)
      delay(1.milliseconds)
    }
  }

  /** Oreo stops the clock after one tick. */
  fun broken(): Flow<Int> = flow {
    emit(1)
    throw IllegalStateException("Oreo stopped the clock")
  }

  /** The posture as a `StateFlow` return. */
  fun stance(): StateFlow<Posture> = postureState

  /** The hour as a held `MutableStateFlow` return, sharing [hour]'s storage. */
  fun alarm(): MutableStateFlow<Int> = hourState

  /** The bulletin as a `SharedFlow` return. */
  fun news(): SharedFlow<Int> = bulletinSource

  /** The sightings as a held `MutableSharedFlow` return, sharing [sightings]'s storage. */
  fun gossip(): MutableSharedFlow<String> = sightings

  /** The bulletin as it stands, so a C# write can be read back from Kotlin. */
  fun latestSightings(): List<String> = sightings.replayCache

  /** A `suspend` member with a cold flow result: awaited first, then collected. */
  suspend fun warmUp(count: Int): Flow<Int> {
    delay(1.milliseconds)
    return flow { repeat(count) { emit(it * 10) } }
  }

  /** A `suspend` member with a `StateFlow` result. */
  suspend fun dial(): StateFlow<Int> {
    delay(1.milliseconds)
    return hourState
  }

  /** A `suspend` member with a held `MutableStateFlow` result, sharing [hour]'s storage. */
  suspend fun winder(): MutableStateFlow<Int> {
    delay(1.milliseconds)
    return hourState
  }

  /** A `suspend` member with a `SharedFlow` result. */
  suspend fun latestNews(): SharedFlow<Int> {
    delay(1.milliseconds)
    return bulletinSource
  }
}

// --- a default-named companion

/** A cuckoo clock a cat sits on; its flows come from the companion. */
class Cuckoo(val sitter: String) {
  /** An instance flow member, on the class's own scope, beside the companion's statics. */
  fun wobbles(): Flow<String> = flowOf("$sitter wobbles")

  companion object {
    private val perchState: MutableStateFlow<String> = MutableStateFlow("Oreo")

    /** A cold flow property on the companion. */
    val chirps: Flow<Int> = flowOf(3, 2, 1)

    /** A `StateFlow` property on the companion. */
    val perched: StateFlow<String> = perchState

    /** The cuckoo calls. */
    fun calls(): Flow<String> = flowOf("cuckoo", "cuckoo")

    /** The companion's own `T`. */
    fun <T> repeat(value: T): Flow<T> = flowOf(value, value)

    /** Who is on the perch, as a held `MutableStateFlow` return. */
    fun perch(): MutableStateFlow<String> = perchState

    /** The cuckoo calls until cancelled. */
    fun endless(): Flow<String> = flow {
      while (true) {
        emit("cuckoo")
        delay(1.milliseconds)
      }
    }

    /** Mylo sat on the cuckoo. */
    fun jammed(): Flow<String> = flow {
      emit("cuck")
      throw IllegalArgumentException("Mylo sat on the cuckoo")
    }

    /** A `suspend` member with a cold flow result on a companion. */
    suspend fun warble(): Flow<String> {
      delay(1.milliseconds)
      return flowOf("warble")
    }

    /** A `suspend` member with a held `MutableStateFlow` result on a companion. */
    suspend fun roost(): MutableStateFlow<String> {
      delay(1.milliseconds)
      return perchState
    }
  }
}

// --- a NAMED companion

/** An hourglass Mylo keeps turning over. */
class Hourglass(val turns: Int) {
  companion object Sand {
    /** Grains of sand, by the handful. */
    fun grains(handfuls: Int): Flow<Int> = flow { repeat(handfuls) { emit(it + 1) } }

    /** A `StateFlow` property on a named companion. */
    val level: StateFlow<Int> = MutableStateFlow(100)
  }
}
