package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlin.time.Instant

// ADR-076: kotlin.time.Instant <-> System.DateTimeOffset over a single INT64 of .NET ticks.
// Oreo and Mylo are notoriously camera-shy, so the neighbourhood cat-sighting log only ever
// has entries for one cat at a time. This fixture crosses every position the ADR asks for:
// ctor param (non-null + nullable), val property, var nullable property, method parameter,
// method return, nullable method return (valueOut), top-level return, top-level nullable
// return, and one member that returns an out-of-range Instant to exercise the throwing
// contract.

class SightingLog(val firstSeen: Instant, var lastSeen: Instant?) {
  /** Seconds between [firstSeen] and [until] -- exercises Instant as a method parameter. */
  fun elapsedSeconds(until: Instant): Long = until.epochSeconds - firstSeen.epochSeconds

  /** A day after the last known sighting (or [firstSeen] if Oreo hasn't been seen again yet). */
  fun nextExpected(): Instant =
    Instant.fromEpochSeconds(epochSeconds = (lastSeen ?: firstSeen).epochSeconds + 86_400L)

  /**
   * Null until someone actually reports an unconfirmed sighting -- exercises the valueOut shape.
   */
  fun earliestUnconfirmed(): Instant? = if (lastSeen == null) null else firstSeen

  /**
   * Oreo once "escaped" for what the household insists was an eternity. Returns an Instant
   * outside System.DateTimeOffset's representable range, which must surface as a bridged
   * C# exception rather than a wrapped value.
   */
  fun escapedForEternity(): Instant = Instant.DISTANT_FUTURE
}

/**
 * The neighbourhood watch program's start date -- exercises Instant as a top-level return.
 *
 * The nanosecond adjustment is deliberately not a multiple of 100: 123_456_789 ns has 89 ns
 * below the wire form's 100ns tick resolution, which the Kotlin -> C# conversion must truncate
 * (floor toward the epoch-0001 origin), not round.
 */
fun sightingEpoch(): Instant =
  Instant.fromEpochSeconds(epochSeconds = 1_700_000_000L, nanosecondAdjustment = 123_456_789)

/**
 * Parses a free-form sighting note. Mylo never causes trouble, so a note that doesn't mention
 * "Oreo" is assumed to be about him and produces no sighting -- exercises the top-level
 * nullable return (ADR-002 two-call shape), the shape ADR-069 recorded as crashing
 * `packNuget` if the plan doesn't account for it.
 */
fun parseSighting(text: String): Instant? =
  if (text.contains("Oreo")) sightingEpoch() else null
