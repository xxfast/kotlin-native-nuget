package io.github.xxfast.kotlin.native.nuget.test.time

import kotlin.time.Instant

/**
 * ADR-076: `kotlin.time.Instant` → C# `System.DateTimeOffset` (UTC, Offset = Zero) at every v1
 * position. Wire is `epochSeconds: Long` + `nanosecondsOfSecond: Int`. C# drops sub-100ns
 * (`nanos % 100`), so every fixed sample uses nanos divisible by 100 for exact equality after
 * truncation.
 *
 * Canonical sample (Oreo's microchip implant):
 *   Instant.fromEpochSeconds(1_704_067_200, 123_456_700)
 *   = 2024-01-01T00:00:00.1234567Z
 *
 * Mylo's later implant (null-branch contrast + second known value):
 *   Instant.fromEpochSeconds(1_704_154_800, 500_000_000)
 *   = 2024-01-02T00:20:00.5000000Z
 */
internal val OreoMicrochippedAt: Instant =
  Instant.fromEpochSeconds(1_704_067_200L, 123_456_700)

internal val MyloMicrochippedAt: Instant =
  Instant.fromEpochSeconds(1_704_154_800L, 500_000_000)

/**
 * ADR-076 cell 1: data-class constructor parameter + read-only property getter.
 * NYTimes-KMP `Article.published_date: Instant` shape: non-null Instant on a DTO.
 */
data class CatPassport(
  val catName: String,
  val microchippedAt: Instant,
)

/**
 * ADR-076 cells 2–6: mutable Instant? property (two-call getter + setter), method returns, and
 * method parameters. Oreo arrives on time; Mylo is still in the waiting room (null).
 */
class VetAppointment(var arrivedAt: Instant? = null) {
  /** Cell 3: non-null Instant method return (void + two OUT components). */
  fun nextSlot(): Instant = OreoMicrochippedAt

  /** Cell 4: Instant? method return (has-value + two OUT). */
  fun maybeCheckout(checkedOut: Boolean): Instant? =
    if (checkedOut) OreoMicrochippedAt else null

  /**
   * Cell 5: non-null Instant method parameter. Returns epoch seconds so the C# side can assert
   * the fan-out without needing Instant equality on the Kotlin return path alone.
   */
  fun secondsSinceEpoch(at: Instant): Long = at.epochSeconds

  /**
   * Cell 5b: non-null Instant parameter + return. C# DateTimeOffset → Kotlin Instant → back
   * equals the original within tick (100 ns) precision.
   */
  fun echo(at: Instant): Instant = at

  /** Cell 6: Instant? method parameter. */
  fun describeDeparture(at: Instant?): String =
    if (at == null) "still in clinic" else "left at ${at.epochSeconds}"

  /** Cell 6b: Instant? parameter + Instant? return (null and non-null both ways). */
  fun maybeEcho(at: Instant?): Instant? = at
}

/**
 * ADR-076 cell 7: object methods (static export path). Covers Instant return and Instant
 * parameter on the object carrier without inventing every ordinary-callable family.
 */
object PassportOffice {
  fun defaultMicrochipDate(): Instant = OreoMicrochippedAt

  fun isAfterOreo(at: Instant): Boolean = at > OreoMicrochippedAt
}
