package io.github.xxfast.kotlin.native.nuget.test.dash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-231: a plain (non-suspend) lambda parameter on a `suspend` member, and on a member that
 * returns a `Flow`, binds as a C# delegate parameter (`Action<int>`, `Func<int, string>`, ...).
 * The delegate's callback context is registered for the whole coroutine (or the whole collection)
 * and released when it ends: on completion, on a fault and on cancellation.
 *
 * [Treadmill] is the class owner and crosses every payload the per-call lambda route carries on a
 * synchronous member (primitives, `String`, a class handle both ways, a generic `T`), a lambda
 * called many times and from another thread, the three terminal arms, a member that never
 * suspends (so the release can beat the P/Invoke's return), a later argument that fails its null
 * guard, and a body that stores the lambda and calls it after the `Task` ended (ADR-160's late-use
 * behaviour: a `Unit` lambda is dropped, a returning one reports `ObjectDisposedException`).
 *
 * The other owners (`object`, companions, top level, extension, enum, value class, generic class)
 * live beside it in this package.
 *
 * Oreo (black with the white middle) and Mylo (brown and creamy) run laps at 3am.
 */
class Treadmill(val runner: String) {
  /** Calls [onStep] once per step, suspending between steps. */
  suspend fun run(steps: Int, onStep: (Int) -> Unit): Int {
    repeat(steps) {
      delay(1.milliseconds)
      onStep(it)
    }
    return steps
  }

  /** Never suspends: the callback context is released before the extern can return. */
  suspend fun quick(onStep: (Int) -> Unit): Int {
    onStep(7)
    return 1
  }

  /** Calls [onStep] once, then only cancellation ends it. */
  suspend fun stall(onStep: (Int) -> Unit): Int {
    onStep(0)
    awaitCancellation()
  }

  /** Calls [onStep] once, then faults after a suspension. */
  suspend fun crash(onStep: (Int) -> Unit): Int {
    onStep(0)
    delay(1.milliseconds)
    throw IllegalStateException("$runner jammed the treadmill")
  }

  /** A returning lambda with a `String` result, called after the suspension. */
  suspend fun label(lap: Int, format: (Int) -> String): String {
    delay(1.milliseconds)
    return format(lap)
  }

  /** A `String` payload in and out. */
  suspend fun cheer(shout: (String) -> String): String {
    delay(1.milliseconds)
    return shout(runner)
  }

  /** Primitive payloads of several widths and a `Boolean` result. */
  suspend fun pace(gauge: (Long, Double, Boolean) -> Boolean): Boolean {
    delay(1.milliseconds)
    return gauge(3_000_000_000L, 2.5, true)
  }

  /** A class handle handed to the delegate, read in C# after the suspension. */
  suspend fun inspect(baton: Baton, read: (Baton) -> String): String {
    delay(1.milliseconds)
    return read(baton)
  }

  /** A class handle returned from the delegate. */
  suspend fun recruit(name: String, make: (String) -> Baton): String {
    delay(1.milliseconds)
    return make(name).holder
  }

  /** ADR-231: the same class-handle result on an ordinary member, on the plan route. */
  fun recruitNow(name: String, make: (String) -> Baton): String = make(name).holder

  /** The member's own `T`, through the lambda both ways. */
  suspend fun <T> relay(value: T, pass: (T) -> T): T {
    delay(1.milliseconds)
    return pass(value)
  }

  /** Calls [onStep] [times] times from the Default dispatcher, several coroutines at once. */
  suspend fun swarm(times: Int, onStep: (Int) -> Unit): Int {
    withContext(Dispatchers.Default) {
      coroutineScope {
        repeat(times) { step -> launch { onStep(step) } }
      }
    }
    return times
  }

  /** The lambda before a later `String` argument, for the null-guard row. */
  suspend fun tag(onStep: (Int) -> Unit, name: String): String {
    delay(1.milliseconds)
    onStep(name.length)
    return name
  }

  private var stashedStep: ((Int) -> Unit)? = null
  private var stashedFormat: ((Int) -> String)? = null

  /** Keeps [onStep] past the end of the coroutine, calling it once while it is still running. */
  suspend fun stash(onStep: (Int) -> Unit): Int {
    stashedStep = onStep
    delay(1.milliseconds)
    onStep(1)
    return 1
  }

  /** Keeps [format] past the end of the coroutine, calling it once while it is still running. */
  suspend fun stashFormat(format: (Int) -> String): String {
    stashedFormat = format
    delay(1.milliseconds)
    return format(1)
  }

  /** Calls the stashed `Unit` lambda after its `Task` ended: a dropped call, never a crash. */
  fun callStashedStep(): String {
    val step = stashedStep ?: return "nothing stashed"
    step(2)
    return "called"
  }

  /** Calls the stashed returning lambda after its `Task` ended, reporting what happened. */
  fun callStashedFormatSafely(): String {
    val format = stashedFormat ?: return "nothing stashed"
    return try {
      "stashed said ${format(2)}"
    } catch (e: Exception) {
      "stale ${e::class.simpleName}: ${e.message}"
    }
  }

  /** A `Flow` member with a plain lambda: the context lives as long as the collection. */
  fun watch(laps: Int, onSeen: (Int) -> Unit): Flow<Int> = flow {
    repeat(laps) {
      onSeen(it)
      emit(it)
    }
  }

  /** A `Flow` member whose lambda returns, called per element. */
  fun commentate(laps: Int, describe: (Int) -> String): Flow<String> = flow {
    repeat(laps) { emit(describe(it)) }
  }

  /** Emits one element, then only cancellation ends the collection. */
  fun watchForever(onSeen: (Int) -> Unit): Flow<Int> = flow {
    onSeen(0)
    emit(0)
    awaitCancellation()
  }

  /** Emits one element, then faults. */
  fun watchCrash(onSeen: (Int) -> Unit): Flow<Int> = flow {
    onSeen(0)
    emit(0)
    delay(1.milliseconds)
    throw IllegalStateException("$runner fell off the treadmill")
  }
}

/** A class handle payload, made through a NAMED companion with a plain-lambda suspend member. */
class Baton(val holder: String) {
  companion object Factory {
    /** The delegate receives the new baton before it is returned. */
    suspend fun hand(holder: String, onHand: (Baton) -> Unit): Baton {
      delay(1.milliseconds)
      val baton = Baton(holder)
      onHand(baton)
      return baton
    }
  }
}

/** An `object` owner: static `Sprint.XAsync(..., Action<int>)` members. */
object Sprint {
  /** A static `suspend` member returning a `Flow` that calls [onLap] once per element. */
  suspend fun warmUpLaps(count: Int, onLap: (Int) -> Unit): Flow<Int> {
    delay(1.milliseconds)
    return flow {
      repeat(count) {
        onLap(it)
        emit(it)
      }
    }
  }

  /** Calls [onLap] once per lap. */
  suspend fun laps(count: Int, onLap: (Int) -> Unit): Int {
    repeat(count) {
      delay(1.milliseconds)
      onLap(it)
    }
    return count
  }

  /** The object's own `T`. */
  suspend fun <T> fetch(value: T, map: (T) -> T): T {
    delay(1.milliseconds)
    return map(value)
  }

  /** Only cancellation ends it. */
  suspend fun hold(onLap: (Int) -> Unit): Int {
    onLap(0)
    awaitCancellation()
  }

  /** Faults after one lap. */
  suspend fun trip(onLap: (Int) -> Unit): Int {
    onLap(0)
    delay(1.milliseconds)
    throw IllegalStateException("Mylo tripped on the rug")
  }

  /** A static `Flow` with a plain lambda. */
  fun stream(count: Int, onLap: (Int) -> Unit): Flow<Int> = flow {
    repeat(count) {
      onLap(it)
      emit(it)
    }
  }
}

/** A default-named companion owner beside instance members. */
class Chase(val prey: String) {
  /** An instance member, on the class route. */
  suspend fun pounce(onLeap: (String) -> Unit): String {
    delay(1.milliseconds)
    onLeap(prey)
    return prey
  }

  companion object {
    /** A companion member: `Chase.BeginAsync(int, Action<int>)`. */
    suspend fun begin(rounds: Int, onRound: (Int) -> Unit): Int {
      repeat(rounds) {
        delay(1.milliseconds)
        onRound(it)
      }
      return rounds
    }

    /** A companion `Flow` with a returning lambda. */
    fun calls(rounds: Int, call: (Int) -> String): Flow<String> = flow {
      repeat(rounds) { emit(call(it)) }
    }
  }
}

/** A chase toy: an enum owner whose members cross the receiver as its ordinal. */
enum class ChaseToy(val label: String) {
  FEATHER("the feather wand"),
  LASER("the laser dot");

  /** An enum body member: `ChaseToyExtensions.SwatAsync(this ChaseToy, Action<string>)`. */
  suspend fun swat(onSwat: (String) -> Unit): Int {
    delay(1.milliseconds)
    onSwat(label)
    return ordinal
  }
}

/** Strides over an `Int`: a value-class owner. */
value class Strides(val count: Int) {
  /** A value-class member with a returning lambda. */
  suspend fun measure(each: (Int) -> Int): Int {
    var total = 0
    repeat(count) {
      delay(1.milliseconds)
      total += each(it)
    }
    return total
  }
}

/** A generic class owner: the lambda's payload is the class's own `T`. */
class Bowlful<T>(val serving: T) {
  /** Hands the serving to [onServe] after the suspension. */
  suspend fun serve(onServe: (T) -> Unit): T {
    delay(1.milliseconds)
    onServe(serving)
    return serving
  }
}

/** A sealed owner: the arm's own plain-lambda suspend member rides the class route. */
sealed class Gait {
  /** A sealed arm with a plain-lambda suspend member. */
  class Trot(val pace: Int) : Gait() {
    /** Calls [onStride] once per stride, suspending between them. */
    suspend fun stride(onStride: (Int) -> Unit): Int {
      repeat(pace) {
        delay(1.milliseconds)
        onStride(it)
      }
      return pace
    }
  }

  /** A second arm, so the base is a real sealed hierarchy. */
  data object Rest : Gait()
}

/** An enum owner whose companion carries a plain-lambda suspend member. */
enum class Lane {
  INSIDE,
  OUTSIDE;

  companion object {
    /** A companion member of an enum: `Lane.PickAsync(Func<int, string>)`. */
    suspend fun pick(describe: (Int) -> String): String {
      delay(1.milliseconds)
      return describe(entries.size)
    }
  }
}

/** A leash over a [Baton] handle: a value-class owner whose underlying is a class. */
value class Leash(val baton: Baton) {
  /** The delegate receives the holder's name after the suspension. */
  suspend fun tug(onTug: (String) -> Unit): String {
    delay(1.milliseconds)
    onTug(baton.holder)
    return baton.holder
  }
}

/**
 * ADR-231: a `suspend` member that RETURNS a `Flow` and takes a plain lambda the returned flow
 * calls. The delegate's callback context lives as long as the returned `KotlinFlow`, so every
 * collection of the cold flow can call it, and is released when the `KotlinFlow` is disposed (or
 * when the call faults, is cancelled or returns no flow).
 */
class FoodBowl(val cat: String) {
  /** Suspends, then returns a cold flow that calls [onBite] once per bite on every collection. */
  suspend fun feed(bites: Int, onBite: (Int) -> Unit): Flow<Int> {
    delay(1.milliseconds)
    return flow {
      repeat(bites) {
        onBite(it)
        emit(it)
      }
    }
  }

  /** A returning lambda the flow calls per element. */
  suspend fun menu(courses: Int, name: (Int) -> String): Flow<String> {
    delay(1.milliseconds)
    return flow { repeat(courses) { emit(name(it)) } }
  }

  /** Calls [onBite] once, then faults before any flow exists. */
  suspend fun spill(onBite: (Int) -> Unit): Flow<Int> {
    onBite(0)
    delay(1.milliseconds)
    throw IllegalStateException("$cat knocked the bowl over")
  }

  /** Calls [onBite] once, then only cancellation ends the call. */
  suspend fun wait(onBite: (Int) -> Unit): Flow<Int> {
    onBite(0)
    awaitCancellation()
  }

  /** Returns no flow when the bowl is empty. */
  suspend fun maybeFeed(full: Boolean, onBite: (Int) -> Unit): Flow<Int>? {
    delay(1.milliseconds)
    if (!full) return null
    return flow {
      onBite(0)
      emit(0)
    }
  }
}
