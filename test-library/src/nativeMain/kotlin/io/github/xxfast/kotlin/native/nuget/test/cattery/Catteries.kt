package io.github.xxfast.kotlin.native.nuget.test.cattery

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-233 amendment: the slot shapes a C# implementation could not yet carry, and the `var` flow
 * setters a class owner kept get-only. Every Kotlin function below reads what C# handed it and
 * echoes it, so a slot or setter that dropped the value shows up.
 *
 * Oreo runs the cattery; Mylo inspects every bowl, twice.
 */

/** A generic interface in no position. Its `suspend` member is inherited at a closed `Int`. */
interface Tote<T> {
  suspend fun fetch(id: Int): T
}

/** The interface Kotlin takes; its async member comes from the generic [Tote]. */
interface Duffel : Tote<Int> {
  fun own(): Int
}

/** The generic implementer of [Duffel], whose C# forwards must name `Int`, not `T`. */
class Carryall<T>(val load: T) : Duffel {
  override suspend fun fetch(id: Int): Int {
    delay(1.milliseconds)
    return id * 100
  }

  override fun own(): Int = 6
}

/** A Kotlin [Duffel] for C#. */
fun carryall(): Duffel = Carryall("kibble")

/** Kotlin reads both members of a [Duffel], whoever implements it. */
suspend fun rummage(duffel: Duffel): String = "own=${duffel.own()} fetch=${duffel.fetch(4)}"

/** Hot flows returned by member FUNCTIONS, so a Kotlin-backed one can be handed back through C#. */
interface Intercom {
  fun chime(): StateFlow<Int>

  fun alert(): MutableStateFlow<Int>

  fun gossip(): SharedFlow<String>
}

/** The Kotlin-backed [Intercom]. */
class WallIntercom : Intercom {
  private val chimes: MutableStateFlow<Int> = MutableStateFlow(3)
  private val headlines: MutableSharedFlow<String> = MutableSharedFlow(replay = 1)

  init {
    headlines.tryEmit("Oreo is in the cattery")
  }

  override fun chime(): StateFlow<Int> = chimes

  override fun alert(): MutableStateFlow<Int> = chimes

  override fun gossip(): SharedFlow<String> = headlines
}

/** A Kotlin [Intercom] for C#. */
fun wallIntercom(): Intercom = WallIntercom()

/** Kotlin reads every hot flow of an [Intercom], and writes through the mutable one. */
suspend fun buzz(intercom: Intercom): String {
  intercom.alert().value += 1
  return "chime=${intercom.chime().value} gossip=${intercom.gossip().first()}"
}

/** A Kotlin-backed interface `var` of a flow, whose setter C# calls. */
interface Chute {
  var feed: Flow<String>
}

/** The Kotlin-backed [Chute]. */
class SteelChute : Chute {
  override var feed: Flow<String> = flowOf("kibble")
}

/** A Kotlin [Chute] for C#. */
fun steelChute(): Chute = SteelChute()

/** Kotlin collects whatever flow the chute holds now. */
suspend fun drain(chute: Chute): String = chute.feed.toList().joinToString()

/** A class `var` of a cold flow, set from C#. */
class Hopper(var source: Flow<String>) {
  suspend fun drain(): String = source.toList().joinToString()
}

/** A Kotlin [Hopper] for C#, preloaded with [items]. */
fun hopper(items: List<String>): Hopper = Hopper(flowOf(*items.toTypedArray()))

/** A class `var` of every hot flow, each set from C#. */
class Gauge {
  var level: StateFlow<Int> = MutableStateFlow(1)

  var dial: MutableStateFlow<Int> = MutableStateFlow(2)

  var news: SharedFlow<String> = MutableSharedFlow<String>(replay = 1).apply { tryEmit("Mylo") }

  var bell: MutableSharedFlow<String> = MutableSharedFlow(replay = 1)

  suspend fun read(): String =
    "level=${level.value} dial=${dial.value} news=${news.first()} " +
      "bell=${bell.replayCache.joinToString()}"
}

/** A sealed owner with a `var` flow on the base and on an arm. */
sealed class Bowl {
  var portions: Flow<Int> = flowOf(1)

  class Wet(val grams: Int) : Bowl() {
    var toppings: Flow<String> = flowOf("gravy")
  }

  suspend fun count(): Int = portions.toList().sum()
}

/** A Kotlin [Bowl.Wet] for C#. */
fun wetBowl(grams: Int): Bowl.Wet = Bowl.Wet(grams)

/** Every topping on a wet bowl. */
suspend fun garnish(bowl: Bowl.Wet): String = bowl.toppings.toList().joinToString()

/** A generic owner with a `var` flow of its own `T`. */
class Trough<T>(var source: Flow<T>) {
  suspend fun all(): List<T> = source.toList()
}

/** A Kotlin [Trough] of names for C#. */
fun nameTrough(): Trough<String> = Trough(flowOf("Oreo"))

/** A value class no other module position wraps in a `Flow`, so its factory entry is new. */
value class Paw(val size: Int)

/** An interface whose ONLY `Flow<Paw>` is a `var`, so the factory entry is never sighted elsewhere. */
interface Pawprints {
  var trail: Flow<Paw>
}

/** Kotlin reads the C# trail, replaces it with a Kotlin flow, and reads it back. */
suspend fun retrace(prints: Pawprints, size: Int): String {
  val before: String = prints.trail.toList().joinToString { it.size.toString() }
  prints.trail = flowOf(Paw(size))
  return "$before -> ${prints.trail.toList().joinToString { it.size.toString() }}"
}

/** A flow element the class route carries and the slot boxes nowhere: `Throwable`. */
interface Hairballs {
  fun coughs(): Flow<Throwable>
}

/**
 * Kotlin collects the C# exceptions and reads their messages. Each arrives as the managed
 * exception a Throwable slot result is, whose message leads with the .NET type name.
 */
suspend fun inspect(hairballs: Hairballs): String =
  hairballs.coughs().toList().joinToString { fault -> fault.message.orEmpty().substringAfter(": ") }

/** A class member returning a lambda, released by the GC once C# drops the delegate. */
class Purrbox(private val cat: String) {
  fun purrer(): (Int) -> String = { times -> "$cat purrs x$times" }
}
