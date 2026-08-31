package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

// ADR-103: kotlin.time.Duration <-> System.TimeSpan over a single INT64 of TimeSpan ticks.
// Mylo naps professionally and Oreo supervises, so the household keeps a nap tracker. This
// fixture crosses every position the ADR asks for and no more: ctor param (non-null +
// nullable), val property, var nullable property, method parameter, method return, nullable
// method return (valueOut), a nullable Duration as a plain method parameter, an echo method
// that round-trips a nullable Duration both ways, an echo method that round-trips a non-null
// Duration both ways, an `object` (static export) crossing a non-null Duration return and a
// non-null Duration parameter plus the two members that reach the throwing paths, a top-level
// return and a top-level nullable return (the ADR-002 two-call shape).

class NapTracker(val longestNap: Duration, var lastNap: Duration?) {
  /** Mylo's nap plus a bonus snooze. Exercises Duration as a method parameter and a return. */
  fun extend(extra: Duration): Duration = longestNap + extra

  /**
   * The shorter of the last nap and the longest one, or null when Mylo hasn't napped yet.
   * Exercises the nullable-return (valueOut) shape.
   */
  fun shortestNap(): Duration? {
    val last = lastNap ?: return null
    return if (last < longestNap) last else longestNap
  }

  /**
   * Describes a reported nap, or the absence of one. Exercises `Duration?` as a plain method
   * parameter (the adjacent-pair `${name}HasValue` + INT64 shape), distinct from the nullable
   * ctor param above.
   */
  fun describe(nap: Duration?): String =
    if (nap == null) "no nap recorded" else "napped for ${nap.inWholeMilliseconds}ms"

  /** Echoes a possibly-unrecorded nap straight back. Nullable Duration in, nullable out. */
  fun maybeEcho(nap: Duration?): Duration? = nap

  /** Echoes a recorded nap straight back. Non-null Duration in, non-null Duration out. */
  fun echo(nap: Duration): Duration = nap
}

/**
 * The household's shared nap clock. Exercises Duration on a Kotlin `object` (the static export
 * path, ForwardCallableOrigin.OBJECT), which flows through the same staticEntry -> planOrSkip
 * path as ordinary class methods.
 */
object NapClock {
  /** A cat's baseline afternoon nap. Static non-null return. */
  fun defaultNap(): Duration = 90.minutes

  /** True if [nap] outlasts an hour. Static non-null parameter, Boolean return. */
  fun isLong(nap: Duration): Boolean = nap > 1.hours

  /**
   * Oreo's opinion of how long Mylo sleeps. Duration.INFINITE has no System.TimeSpan
   * counterpart, so this must surface the Kotlin `isFinite()` require as a bridged C#
   * exception rather than a wrapped or clamped value.
   */
  fun infiniteNap(): Duration = Duration.INFINITE

  /**
   * A finite nap of about 200,000 years. Well inside Duration's millisecond band, well outside
   * System.TimeSpan's range (about 29,228 years), so this reaches the range require.
   */
  fun aeonNap(): Duration = (200_000L * 365).days
}

/**
 * The smallest nap the household bothers to record. Exercises Duration as a top-level return.
 *
 * 150 ns is deliberately not a multiple of 100: 50 ns of it sits below the wire form's 100ns
 * tick resolution, which the Kotlin -> C# conversion must truncate toward zero (1 tick), not
 * round (2 ticks).
 */
fun napEpsilon(): Duration = 150.nanoseconds

/**
 * Parses a free-form nap note. Oreo naps in twenty-minute bursts; a note that doesn't mention
 * him is Mylo dozing off the record and produces no nap. Exercises the top-level nullable
 * return (ADR-002 two-call shape).
 */
fun parseNap(text: String): Duration? = if (text.contains("Oreo")) 20.minutes else null
