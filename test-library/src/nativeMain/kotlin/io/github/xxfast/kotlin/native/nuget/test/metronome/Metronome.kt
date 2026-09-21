package io.github.xxfast.kotlin.native.nuget.test.metronome

/**
 * ADR-036 · a **primitive payload** on the per-call lambda parameter route (`(Int) -> Unit`).
 *
 * ADR-036's marshalling table says a primitive crosses by value in both directions, and the
 * interface-bridge route implements exactly that. The per-call lambda route never did: it boxes the
 * payload into a `StableRef` on the Kotlin side and renders `Int arg0 = NugetMarshal.FromHandle<Int>
 * (arg0Ptr)` against a `NugetIntVoidCallback(int, IntPtr)` delegate on the C# side, which does not
 * compile.
 *
 * Its own package, since the payload type is what is under test and an existing package would drag
 * unrelated callback delegates into the same first-registration-wins name pool.
 *
 * One cell per primitive family the by-value branch has to cover, because a fixture trimmed to
 * `Int` alone would go green against a fix that special-cased the one width the delegate suffix
 * already spells:
 *
 *  - [onTick] is the **word-sized integer** cell (`Int`), the payload the delegate name
 *    `NugetIntVoidCallback` is already derived from and the one that collides with the stored
 *    enum-ordinal route's registration of the same name,
 *  - [onBeat] is the **`Boolean`** cell, the only primitive that is not itself the wire type: it
 *    crosses as a `Byte` and needs a widening on the C# side, so it is the cell that catches a fix
 *    that forwards the argument verbatim,
 *  - [onVelocity] is the **signed 8-bit** cell (`Byte`), which shares `Boolean`'s one-byte wire but
 *    not its C# type (`sbyte` against `byte`). It is declared directly after [onBeat]: the
 *    `Boolean` payload binds as `NugetBoolVoidCallback` and the `Byte` payload as
 *    `NugetByteVoidCallback`, so the fixture pins the signed 8-bit family apart from `Boolean`'s.
 *    Its payload goes negative on purpose: read as unsigned, `-100` arrives as `156`, so the cell
 *    catches a sign misread as well as a name mix-up,
 *  - [onTempo] is the **floating point** cell (`Double`), which crosses in a different register
 *    class from the integers, so it is the cell that catches a by-value fix that only ever passes
 *    integer-shaped payloads.
 *
 * Every listener is invoked more than once with a changing payload: a single invocation would pass
 * against a thunk that delivered a constant, and the tests assert the exact sequence.
 *
 * ADR-160 (the per-call callable moving onto the ADR-062 forward callable plan) adds two further
 * axes on the same members, and they are kept apart everywhere below:
 *
 *  - the **METHOD (outer) return**, the member's own result (`fun countTicks(cb: (Int) -> Unit):
 *    Int`). Every outer-axis cell takes a `Unit`-returning lambda, so a failure names the outer
 *    axis and nothing else,
 *  - the **LAMBDA (inner) return**, what the C# lambda hands back (`fun sumWeights(weigh: (Int) ->
 *    Int): Int`). The inner-axis cells below unavoidably carry an outer return too; where the two
 *    are the same Kotlin type ([sumWeights], [totalMicros], [totalTempo]) the cell cannot tell the
 *    axes apart on its own, and it is the Tier 1 text pin (delegate return type, `CFunction`
 *    signature) that isolates the inner half. [joinTicks] and [tickLabel] are green today
 *    (`Cat.describeWith` precedent) and are regression anchors, not reds.
 *
 * Beyond the two return axes the ADR-062 plan is what makes the rest of this file expressible at
 * all, so the fixture crosses exactly the cells the legacy route refuses and no more:
 *
 *  - an **exported-object** outer return ([firstChime]) and its **nullable** twin
 *    ([firstChimeOrNull], the `firstOrNull` shape), which is the only cell that mints a handle out
 *    of a callback member and therefore the only one with LeakTests rows,
 *  - an **enum** outer return ([moodAfter]),
 *  - a **mixed parameter list** ([countAbove]), a non-lambda parameter next to the lambda, which
 *    today's route silently drops,
 *  - the **top-level** and **extension** positions, in the sibling `Tallies.kt` (ADR-007 puts a
 *    top-level function in a static class named after its file, and `Metronome` is taken by the
 *    class),
 *  - a **sealed arm** receiver ([Cadence.Steady.countTicks]), the third owner kind the route has to
 *    key its export prefix from.
 *
 * Deliberately absent, so the cells above stay readable: suspend lambdas, a lambda that returns a
 * lambda, and collections in the lambda payload.
 *
 * Oreo is the tick-counter of this house. Mylo just follows the tempo.
 */
class Metronome(private val beats: Int) {
  /** The configured beat count, so [everyOtherTick] can drive its own loop without nesting. */
  val beatCount: Int get() = beats

  /** The chimes this metronome can pick from. Oreo is the light one, Mylo lands heavier. */
  private val chimes: List<Chime> = listOf(Chime("Oreo", 1), Chime("Mylo", 2), Chime("Both", 3))

  /** Invokes [listener] with `1..beats`, in order. */
  fun onTick(listener: (Int) -> Unit) = repeat(beats) { listener(it + 1) }

  /** Invokes [listener] once per beat, `true` on the downbeats (even indices). */
  fun onBeat(listener: (Boolean) -> Unit) = repeat(beats) { listener(it % 2 == 0) }

  /**
   * Invokes [listener] once per beat with a velocity that climbs from `-100` in steps of `40`.
   *
   * Oreo lands his first pounce well below the line; Mylo's only ever creeps up from there.
   */
  fun onVelocity(listener: (Byte) -> Unit) = repeat(beats) { listener((it * 40 - 100).toByte()) }

  /** Invokes [listener] once per beat with a tempo that climbs by a half step each time. */
  fun onTempo(listener: (Double) -> Unit) = repeat(beats) { listener(60.0 + it * 0.5) }

  // ---------------------------------------------------------------------------------------------
  // METHOD (outer) return axis. Inner lambda is always `Unit` here.
  // ---------------------------------------------------------------------------------------------

  /** Outer `Int`: the beats it ticked. The headline cell of ROADMAP line 75. */
  fun countTicks(listener: (Int) -> Unit): Int {
    repeat(beats) { listener(it + 1) }
    return beats
  }

  /** Outer `Boolean`, the one scalar that is not its own wire (it crosses as a byte). */
  fun ranAnyTick(listener: (Int) -> Unit): Boolean {
    repeat(beats) { listener(it + 1) }
    return beats > 0
  }

  /**
   * Outer `Double`: a floating result comes back in a different register class from the
   * integers.
   */
  fun runSeconds(listener: (Int) -> Unit): Double {
    repeat(beats) { listener(it + 1) }
    return beats * 0.5
  }

  /** Outer `String`, green today (`Cat.describeWith`): the regression anchor of the outer axis. */
  fun tickLabel(listener: (Int) -> Unit): String {
    repeat(beats) { listener(it + 1) }
    return "$beats beats for Oreo"
  }

  // ---------------------------------------------------------------------------------------------
  // LAMBDA (inner) return axis. The C# lambda hands a scalar back by value.
  // ---------------------------------------------------------------------------------------------

  /** Inner `Int`, by value in both directions. */
  fun sumWeights(weigh: (Int) -> Int): Int = (1..beats).sumOf { weigh(it) }

  /** Inner `Long`, the 64-bit integer wire. */
  fun totalMicros(microsOf: (Int) -> Long): Long = (1..beats).sumOf { microsOf(it) }

  /** Inner `Double`, the floating wire. */
  fun totalTempo(tempoOf: (Int) -> Double): Double = (1..beats).sumOf { tempoOf(it) }

  /** Inner `String`, green today (`Cat.describeWith`): the regression anchor of the inner axis. */
  fun joinTicks(nameOf: (Int) -> String): String = (1..beats).joinToString("-") { nameOf(it) }

  // ---------------------------------------------------------------------------------------------
  // Outer returns the legacy route refuses: exported object, its nullable twin, enum.
  // ---------------------------------------------------------------------------------------------

  /**
   * Outer **exported object**: the first chime the C# predicate accepts, retained on the way out so
   * the consumer disposes it. Throws when nothing matches, which is why the nullable twin exists.
   */
  fun firstChime(where: (Chime) -> Boolean): Chime = chimes.first(where)

  /** Outer **nullable exported object**, the `firstOrNull` shape: both branches are reachable. */
  fun firstChimeOrNull(where: (Chime) -> Boolean): Chime? = chimes.firstOrNull(where)

  /** Outer **enum**: crosses as its ordinal, not as a handle. */
  fun moodAfter(listener: (Int) -> Unit): Mood {
    repeat(beats) { listener(it + 1) }
    return when {
      beats >= 6 -> Mood.FRANTIC
      beats >= 3 -> Mood.BRISK
      else -> Mood.CALM
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Mixed parameter list: a non-lambda parameter next to the lambda. Today both halves drop it.
  // ---------------------------------------------------------------------------------------------

  /** Invokes [listener] only for beats above [min], and returns how many times it fired. */
  fun countAbove(min: Int, listener: (Int) -> Unit): Int {
    var fired = 0
    (1..beats).forEach { beat ->
      if (beat > min) {
        listener(beat)
        fired++
      }
    }
    return fired
  }
}

/** A chime Oreo or Mylo can set off. Exported, so [Metronome.firstChime] returns a handle. */
class Chime(val name: String, val weight: Int)

/** How wound up the house is once the metronome has run. */
enum class Mood { CALM, BRISK, FRANTIC }

/**
 * The **sealed arm** owner kind: a per-call lambda member declared on an arm, which has to key its
 * export prefix off the arm (ADR-116/118/124 did the same for the plain, suspend and flow rows).
 */
sealed class Cadence {
  /** Oreo's pulse: steady, and it counts its own ticks. */
  class Steady(val span: Int) : Cadence() {
    /** Outer `Int` on an arm receiver. */
    fun countTicks(listener: (Int) -> Unit): Int {
      repeat(span) { listener(it + 1) }
      return span
    }
  }
}
