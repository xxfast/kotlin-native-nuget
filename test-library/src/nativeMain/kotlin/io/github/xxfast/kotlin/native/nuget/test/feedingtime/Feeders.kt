package io.github.xxfast.kotlin.native.nuget.test.feedingtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-232: a C#-implemented interface whose members are `suspend` or return a `Flow` crosses to
 * Kotlin. Kotlin awaits the C# `FeedAsync(cat, cancellationToken)` and collects the C#
 * `KotlinFlow<string>`, which a C# implementation either got from Kotlin or made from an
 * `IAsyncEnumerable<string>` with `KotlinFlow<T>.From`.
 *
 * One suspend member per result wire the bridge carries (`Int`, `Unit`, `Boolean`, `Long`,
 * `Float`, `Double`, an enum, `String?`, `Throwable`), so a result thunk missing for one of them
 * cannot hide behind another.
 *
 * Oreo's automatic feeder is very punctual. Mylo's is mostly a bowl he knocks over.
 */
interface Feeder {
  val name: String

  suspend fun feed(cat: String): Int

  suspend fun refill()

  suspend fun hungry(cat: String): Boolean

  suspend fun lastMeal(): Long

  suspend fun portion(): Float

  suspend fun weigh(): Double

  suspend fun craving(cat: String): Craving

  suspend fun favourite(cat: String): String?

  suspend fun spill(): Throwable

  fun meals(): Flow<String>
}

/** The enum result of [Feeder.craving]. */
enum class Craving { PECKISH, RAVENOUS }

/** The Kotlin-backed side, so [Feeder] is reachable (a return hands it to C#). */
class AutoFeeder(override val name: String) : Feeder {
  override suspend fun feed(cat: String): Int {
    delay(1.milliseconds)
    return cat.length
  }

  override suspend fun refill() = delay(1.milliseconds)
  override suspend fun hungry(cat: String): Boolean = true
  override suspend fun lastMeal(): Long = 7L
  override suspend fun portion(): Float = 0.5f
  override suspend fun weigh(): Double = 4.5
  override suspend fun craving(cat: String): Craving = Craving.RAVENOUS
  override suspend fun favourite(cat: String): String? = null
  override suspend fun spill(): Throwable = IllegalStateException("kibble everywhere")
  override fun meals(): Flow<String> = flowOf("tuna", "salmon")
}

/** A Kotlin [Feeder] for C#, whose [Feeder.meals] a C# implementation can hand back as is. */
fun autoFeeder(name: String): Feeder = AutoFeeder(name)

/** The headline: a suspend member awaited, with an `Int` result. */
suspend fun serve(feeder: Feeder, cat: String): String =
  "${feeder.name} gave $cat ${feeder.feed(cat)} scoops"

/** A `Unit` suspend member, awaited twice so a slot that completes only once shows up. */
suspend fun topUp(feeder: Feeder): String {
  feeder.refill()
  feeder.refill()
  return "${feeder.name} topped up twice"
}

/** Every other result wire, read in one call, in declaration order. */
suspend fun checkUp(feeder: Feeder, cat: String): String =
  listOf(
    "hungry=${feeder.hungry(cat)}",
    "last=${feeder.lastMeal()}",
    "portion=${feeder.portion()}",
    "weight=${feeder.weigh()}",
    "craving=${feeder.craving(cat)}",
    "favourite=${feeder.favourite(cat)}",
    // The managed exception's message leads with the C# type name (ADR-201); the C# text follows.
    "spill=${feeder.spill().message?.substringAfter(": ")}",
  ).joinToString()

/**
 * Kotlin cancels the C# call on its own timeout and carries on: the C# token must see it, and the
 * outer call completes normally.
 */
suspend fun serveOrGiveUp(feeder: Feeder, cat: String, patienceMillis: Long): String =
  withTimeoutOrNull(patienceMillis.milliseconds) { feeder.feed(cat) }
    ?.let { scoops -> "$cat got $scoops scoops" }
    ?: "$cat gave up waiting"

/**
 * A C# fault arrives in Kotlin as an exception Kotlin can catch. A cancellation is not a fault, so
 * it is rethrown rather than reported.
 */
suspend fun serveOrRecover(feeder: Feeder, cat: String): String =
  try {
    "served ${feeder.feed(cat)}"
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (fault: Throwable) {
    "recovered ${fault::class.simpleName}: ${fault.message}"
  }

/** The C# flow collected to the end in Kotlin. */
suspend fun menu(feeder: Feeder): String = feeder.meals().toList().joinToString()

/** Kotlin stops collecting after [count] meals, so the C# enumeration ends early. */
suspend fun firstMeals(feeder: Feeder, count: Int): String =
  feeder.meals().take(count).toList().joinToString()

/** A C# flow that faults mid-stream, caught in Kotlin with what it collected so far. */
suspend fun menuOrRecover(feeder: Feeder): String {
  val eaten: MutableList<String> = mutableListOf()
  return try {
    feeder.meals().collect { meal -> eaten += meal }
    "ate ${eaten.joinToString()}"
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (fault: Throwable) {
    "ate ${eaten.joinToString()} then ${fault::class.simpleName}: ${fault.message}"
  }
}

/**
 * The C# flow handed straight back to C# as a `Flow` return. A class member, so it rides the
 * `Flow` return the class route already binds.
 */
class FeedingStation {
  fun relay(feeder: Feeder): Flow<String> = feeder.meals()
}

/**
 * ADR-232 with ADR-222: a generic interface with a suspend member over its own `T` and a `Flow<T>`
 * member. Kotlin reads both through the erased bridge.
 */
interface Larder<T> {
  suspend fun fetch(shelf: Int): T

  fun stock(): Flow<T>
}

/** The Kotlin-backed [Larder], so the generic interface is reachable too. */
class Cupboard<T>(private val items: List<T>) : Larder<T> {
  override suspend fun fetch(shelf: Int): T {
    delay(1.milliseconds)
    return items[shelf]
  }

  override fun stock(): Flow<T> = items.asFlow()
}

/** A Kotlin [Larder] for C#. */
fun cupboard(items: List<String>): Larder<String> = Cupboard(items)

/** An open `T`: the suspend member's boxed `T` result handed straight back. */
suspend fun <T> raid(larder: Larder<T>, shelf: Int): T = larder.fetch(shelf)

/** A closed `Int` instantiation: the suspend result and every flow element unboxed in Kotlin. */
suspend fun tally(larder: Larder<Int>): Int = larder.fetch(0) + larder.stock().toList().sum()

/** An open `T` flow collected in Kotlin and returned as a list. */
suspend fun <T> inventory(larder: Larder<T>): List<T> = larder.stock().toList()

/**
 * ADR-232: an interface nothing returns, so no Kotlin-backed instance ever reaches C#. Its only
 * implementations are C# ones, and its suspend member is declared on `IAlarmClock` and bridged
 * all the same.
 */
interface AlarmClock {
  suspend fun ring(cat: String): String
}

/** The only position [AlarmClock] appears in: an input. */
suspend fun breakfast(clock: AlarmClock): String = "breakfast after ${clock.ring("Mylo")}"

/**
 * ADR-232: an interface no position reaches. No Kotlin-backed instance ever reaches C# and nothing
 * takes one, yet `ISnooze` declares its suspend member for a C# implementation all the same.
 */
interface Snooze {
  suspend fun snooze(minutes: Int): String
}

/** ADR-232: a suspend member returning a Flow, awaited on the C# side and then collected. */
interface Pantry {
  suspend fun delivery(): Flow<String>
}

/** The awaited C# flow collected to the end. */
suspend fun unpack(pantry: Pantry): String = pantry.delivery().toList().joinToString()

/**
 * ADR-232: hot flows on a C#-implemented interface. A C# implementation returns a
 * `KotlinMutableStateFlow<T>.Create` or `KotlinMutableSharedFlow<T>.Create` flow, a real Kotlin
 * flow, so a write on either side is seen on the other.
 */
interface Thermostat {
  val level: StateFlow<Int>

  val dial: MutableStateFlow<Int>

  fun news(): SharedFlow<String>

  fun bell(): MutableSharedFlow<String>
}

/** The Kotlin-backed [Thermostat], whose flows C# reads through member wrappers. */
class WallThermostat : Thermostat {
  override val level: StateFlow<Int> = MutableStateFlow(21)
  override val dial: MutableStateFlow<Int> = MutableStateFlow(0)
  private val headlines = MutableSharedFlow<String>(replay = 1)
  override fun news(): SharedFlow<String> = headlines
  override fun bell(): MutableSharedFlow<String> = headlines
}

/** A Kotlin [Thermostat] for C#. */
fun wallThermostat(): Thermostat = WallThermostat()

/** Kotlin reads the C# state flow's current value. */
fun reading(thermostat: Thermostat): Int = thermostat.level.value

/** Kotlin writes the C# mutable state flow, and reads back what it wrote. */
fun turnUp(thermostat: Thermostat): Int {
  thermostat.dial.value += 1
  return thermostat.dial.value
}

/** Kotlin collects the C# state flow until C# writes a value above [floor]. */
suspend fun warmerThan(thermostat: Thermostat, floor: Int): Int =
  thermostat.level.first { level -> level > floor }

/** Kotlin collects the first value of the C# shared flow (a replay, or one C# emits later). */
suspend fun headline(thermostat: Thermostat): String = thermostat.news().first()

/** Kotlin emits into the C# mutable shared flow. */
fun ring(thermostat: Thermostat, sound: String): Boolean = thermostat.bell().tryEmit(sound)
