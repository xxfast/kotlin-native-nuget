package io.github.xxfast.kotlin.native.nuget.test.furcoat

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * ADR-235: `Instant`, `Duration` and `Uuid` as collection components, bare and inside a value
 * class, at a parameter and a result, in a `List`, a `Set` and on both sides of a `Map`. Each
 * element crosses on its own parameter wire (.NET ticks for `Instant` and `Duration`, the
 * hex-dash text for `Uuid`), converted once per element on each side.
 *
 * Oreo's nap log: when each sunbeam nap started, how long it lasted, and whose chip was scanned.
 */

private val OREO_CHIP: Uuid = Uuid.parse("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9")
private val MYLO_CHIP: Uuid = Uuid.parse("1b2c3d4e-5f60-7182-93a4-b5c6d7e8f90a")

// --- bare components ---------------------------------------------------------------------------

fun napStarts(count: Int): List<Instant> =
  (1..count).map { Instant.fromEpochSeconds(1_700_000_000L + it * 60L) }

fun latestNap(starts: List<Instant>): Instant = starts.max()
fun napLengths(): Set<Duration> = setOf(20.minutes, 45.minutes)
fun totalNapMinutes(lengths: Set<Duration>): Long = lengths.sumOf { it.inWholeMinutes }
fun chipIds(): List<Uuid> = listOf(OREO_CHIP, MYLO_CHIP)
fun firstChip(ids: List<Uuid>): Uuid = ids.first()
fun napsByChip(): Map<Uuid, Duration> = mapOf(OREO_CHIP to 20.minutes, MYLO_CHIP to 45.minutes)

fun chipsByNap(naps: Map<Instant, Uuid>): String =
  naps.entries.sortedBy { it.key }.joinToString(",") { "${it.key.epochSeconds}=${it.value}" }

fun echoMaybeChips(ids: List<Uuid?>): List<Uuid?> = ids
fun missingStarts(starts: List<Instant?>): Int = starts.count { it == null }
fun echoMaybeLengths(lengths: Set<Duration?>): Set<Duration?> = lengths

// --- value classes over them -------------------------------------------------------------------

fun sunbeams(): List<SunbeamStart> =
  listOf(SunbeamStart(Instant.fromEpochSeconds(1_700_000_000)), SunbeamStart(Instant.fromEpochSeconds(0)))

fun earliestSunbeam(starts: List<SunbeamStart>): SunbeamStart = starts.minBy { it.at }
fun snoozes(): Set<SnoozeSpan> = setOf(SnoozeSpan(10.minutes), SnoozeSpan(30.minutes))
fun longestSnooze(snoozes: Set<SnoozeSpan>): SnoozeSpan = snoozes.maxBy { it.span }
fun tagsByChip(): Map<ChipSerial, ShelterTag> = mapOf(ChipSerial(OREO_CHIP) to ShelterTag(ChipSerial(MYLO_CHIP)))
fun countTags(tags: Map<ChipSerial, ShelterTag>): Int = tags.size
fun maybeChips(chips: List<ChipSerial?>): List<ChipSerial?> = chips
