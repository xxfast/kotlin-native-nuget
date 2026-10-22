package io.github.xxfast.kotlin.native.nuget.test.furcoat

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * ADR-235: a value class over an underlying that already has a C# spelling and a wire at every
 * ordinary position (`Instant`, `Duration`, `Uuid`, `ByteArray`, a collection, another value class,
 * a plain interface) binds as a record struct over that spelling and crosses on that wire. Every
 * `init` below runs in Kotlin when C# hands the struct back, so a C# caller sees the usual
 * `require` failure at the crossing.
 *
 * Oreo (black, white middle) and Mylo (brown and creamy) each own a coat of record: when their
 * sunbeam nap started, how long it lasts, a shelter microchip, a scanned paw print, a toy basket
 * and the voice of their purr.
 */

/** `Instant` underlying: no sunbeam starts before the Unix epoch. */
value class SunbeamStart(val at: Instant) {
  init {
    require(at.epochSeconds >= 0) { "a sunbeam cannot start before 1970" }
  }

  /** Minutes from this sunbeam to [other]; a member over an `Instant` receiver and parameter. */
  fun minutesUntil(other: SunbeamStart): Long = (other.at - at).inWholeMinutes

  /** The sunbeam one hour later, a value class result from its own member. */
  fun anHourLater(): SunbeamStart = SunbeamStart(at + 60.minutes)

  val epochMinutes: Long get() = at.epochSeconds / 60
}

/** `Duration` underlying: a snooze is never negative. */
value class SnoozeSpan(val span: Duration) {
  /** A secondary constructor in whole minutes; its `init` still runs. */
  constructor(minutes: Int) : this(minutes.minutes)

  init {
    require(!span.isNegative()) { "a snooze cannot be negative" }
  }

  fun doubled(): SnoozeSpan = SnoozeSpan(span * 2)

  val minutes: Long get() = span.inWholeMinutes
}

/** `Uuid` underlying: the nil chip is not a chip. */
value class ChipSerial(val id: Uuid) {
  init {
    require(id != Uuid.NIL) { "the nil uuid is not a chip serial" }
  }

  val prefix: String get() = id.toString().take(8)
}

/** `ByteArray` underlying: a paw print has at least one pad. */
value class PawPrint(val pads: ByteArray) {
  /** [count] pads of the same size. */
  constructor(pad: Byte, count: Int) : this(ByteArray(count) { pad })

  init {
    require(pads.isNotEmpty()) { "a paw print needs at least one pad" }
  }

  val padCount: Int get() = pads.size

  fun withPad(pad: Byte): PawPrint = PawPrint(pads + pad)
}

/** Collection underlying: a basket holds at most three toys. */
value class ToyBasket(val toys: List<String>) {
  /** A basket of one toy. */
  constructor(toy: String) : this(listOf(toy))

  init {
    require(toys.size <= 3) { "a basket holds at most three toys" }
  }

  fun has(toy: String): Boolean = toy in toys
}

/** Another value class as the underlying: a shelter tag wraps a chip serial. */
value class ShelterTag(val serial: ChipSerial) {
  /** A tag for the chip whose serial reads [id]. */
  constructor(id: String) : this(ChipSerial(Uuid.parse(id)))

  init {
    require(serial.id.toString().first() != 'f') { "shelter tags never start with f" }
  }

  val chipPrefix: String get() = serial.prefix
}

/** A plain Kotlin interface, the underlying of [PurrVoice]. */
interface Rumbler {
  val owner: String
  fun rumble(times: Int): String
}

/** The Kotlin-backed [Rumbler]. */
class HouseRumbler(override val owner: String) : Rumbler {
  override fun rumble(times: Int): String = "$owner: " + "prr".repeat(times)
}

/** Plain interface underlying: a voice nobody owns is no voice. */
value class PurrVoice(val rumbler: Rumbler) {
  init {
    require(rumbler.owner.isNotEmpty()) { "a purr needs an owner" }
  }

  fun purr(times: Int): String = rumbler.rumble(times)
}

/** Every value class above as a constructor parameter, a `val`, and a nullable `var`. */
class CoatRack(
  val start: SunbeamStart,
  val snooze: SnoozeSpan,
  val chip: ChipSerial,
  val paw: PawPrint,
  val basket: ToyBasket,
  val tag: ShelterTag,
  val voice: PurrVoice,
) {
  var lastStart: SunbeamStart? = null
  var lastSnooze: SnoozeSpan? = null
  var lastChip: ChipSerial? = null
  var lastPaw: PawPrint? = null
  var lastBasket: ToyBasket? = null
  var lastTag: ShelterTag? = null
  var lastVoice: PurrVoice? = null

  /** Method parameter and return: the snooze grows by [extra] minutes. */
  fun lengthen(extra: SnoozeSpan): SnoozeSpan = SnoozeSpan(snooze.span + extra.span)

  /** A member reading every underlying back out, proving what arrived in Kotlin. */
  fun describe(): String = "${start.at.epochSeconds}|${snooze.span.inWholeMinutes}|${chip.id}|" +
      "${paw.pads.joinToString(",")}|${basket.toys.joinToString(",")}|${tag.serial.id}|" +
      voice.rumbler.rumble(2)
}

// --- top-level parameter and return, one echo per underlying ---------------------------------

fun echoStart(start: SunbeamStart): SunbeamStart = start
fun echoSnooze(snooze: SnoozeSpan): SnoozeSpan = snooze
fun echoChip(chip: ChipSerial): ChipSerial = chip
fun echoPaw(paw: PawPrint): PawPrint = paw
fun echoBasket(basket: ToyBasket): ToyBasket = basket
fun echoTag(tag: ShelterTag): ShelterTag = tag
fun echoVoice(voice: PurrVoice): PurrVoice = voice

// --- top-level nullable parameter and return -------------------------------------------------

fun maybeStart(start: SunbeamStart?): SunbeamStart? = start
fun maybeSnooze(snooze: SnoozeSpan?): SnoozeSpan? = snooze
fun maybeChip(chip: ChipSerial?): ChipSerial? = chip
fun maybePaw(paw: PawPrint?): PawPrint? = paw
fun maybeBasket(basket: ToyBasket?): ToyBasket? = basket
fun maybeTag(tag: ShelterTag?): ShelterTag? = tag
fun maybeVoice(voice: PurrVoice?): PurrVoice? = voice

// --- values minted in Kotlin, so a C# read sees a struct Kotlin's `init` accepted ------------

fun oreoStart(): SunbeamStart = SunbeamStart(Instant.fromEpochSeconds(1_700_000_000))
fun myloSnooze(): SnoozeSpan = SnoozeSpan(45.minutes)
fun oreoChip(): ChipSerial = ChipSerial(Uuid.parse("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9"))
fun myloPaw(): PawPrint = PawPrint(byteArrayOf(4, 1, 1, 1, 1))
fun oreoBasket(): ToyBasket = ToyBasket(listOf("mouse", "feather"))
fun myloTag(): ShelterTag = ShelterTag(ChipSerial(Uuid.parse("1b2c3d4e-5f60-7182-93a4-b5c6d7e8f90a")))
fun oreoVoice(): PurrVoice = PurrVoice(HouseRumbler("Oreo"))

/** Reads the minutes back in Kotlin, so a C# struct's `TimeSpan` has to reach Kotlin intact. */
fun snoozeMinutes(snooze: SnoozeSpan): Long = snooze.span.inWholeMinutes

/** Reads the interface underlying in Kotlin, so a C#-implemented `IRumbler` has to dispatch. */
fun hearPurr(voice: PurrVoice, times: Int): String = voice.rumbler.rumble(times)

// --- collection elements, for the underlyings whose bare type is a shipped component --------

fun pawPrints(count: Int): List<PawPrint> = (1..count).map { PawPrint(byteArrayOf(it.toByte())) }
fun countPads(prints: List<PawPrint>): Int = prints.sumOf { it.pads.size }
fun baskets(): List<ToyBasket> = listOf(ToyBasket(listOf("mouse")), ToyBasket(listOf("yarn", "box")))
fun countToys(baskets: List<ToyBasket>): Int = baskets.sumOf { it.toys.size }
fun choir(): List<PurrVoice> = listOf(PurrVoice(HouseRumbler("Oreo")), PurrVoice(HouseRumbler("Mylo")))
fun owners(voices: List<PurrVoice>): String = voices.joinToString(",") { it.rumbler.owner }

// --- a nested value class over a reference underlying, and a `ByteArray` struct in a Set/Map ---

/** A plaster cast of a paw print: a value class over a value class over a `ByteArray`. */
value class PawCast(val print: PawPrint)

fun echoCast(cast: PawCast): PawCast = cast

fun pawSet(): Set<PawPrint> = setOf(PawPrint(byteArrayOf(1)), PawPrint(byteArrayOf(2, 2)))
fun countSetPads(prints: Set<PawPrint>): Int = prints.sumOf { it.pads.size }
fun pawsByOwner(): Map<String, PawPrint> = mapOf("Oreo" to PawPrint(byteArrayOf(4, 4, 4)))
fun countMapPads(prints: Map<String, PawPrint>): Int = prints.values.sumOf { it.pads.size }
fun ownersByPaw(prints: Map<PawPrint, String>): String = prints.values.sorted().joinToString(",")
