package io.github.xxfast.kotlin.native.nuget.test.pawpost

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * ADR-240: result-only members, nullable receivers, members on value classes over `Duration` and
 * `Uuid`, a `StateFlow` member taking these types, and `object` and companion owners.
 *
 * The post office opens at the epoch plus a fixed postmark, so every result is predictable.
 */

/** The postmark every result-only member starts from: 2023-11-14T22:13:20Z. */
private val OFFICE_POSTMARK: Instant = Instant.fromEpochSeconds(1_700_000_000)

private val OFFICE_CODE: Uuid = Uuid.parse("1b2c3d4e-5f60-7182-93a4-b5c6d7e8f90a")

/** `Duration` underlying with a suspend member, so the receiver crosses as ticks. */
value class PostWindow(val span: Duration) {
  init {
    require(!span.isNegative()) { "a post window cannot be negative" }
  }

  suspend fun widened(by: Int): PostWindow {
    delay(1.milliseconds)
    return PostWindow(span + by.minutes)
  }

  fun widenings(count: Int): Flow<PostWindow> = flow {
    repeat(count) { emit(PostWindow(span + it.minutes)) }
  }
}

/** `Uuid` underlying with a suspend member, so the receiver crosses as hex-dash text. */
value class PostTicket(val id: Uuid) {
  init {
    require(id != Uuid.NIL) { "the nil uuid is not a ticket" }
  }

  suspend fun stub(): String {
    delay(1.milliseconds)
    return id.toString().take(8)
  }
}

/** A value class over another value class, so the nesting crosses the async routes too. */
value class PostSeal(val print: PostPrint)

/** Result-only members: nothing goes in, each type comes back. */
class PostCounter {
  suspend fun lastStamp(): Instant {
    delay(1.milliseconds)
    return OFFICE_POSTMARK
  }

  suspend fun lastWait(): Duration {
    delay(1.milliseconds)
    return 90.minutes
  }

  suspend fun lastCode(): Uuid {
    delay(1.milliseconds)
    return OFFICE_CODE
  }

  suspend fun lastPrint(): PostPrint {
    delay(1.milliseconds)
    return PostPrint(byteArrayOf(1, 2, 3))
  }

  suspend fun lastBundle(): PostBundle {
    delay(1.milliseconds)
    return PostBundle(listOf("bill", "card"))
  }

  suspend fun lastKnock(): PostKnock {
    delay(1.milliseconds)
    return PostKnock(HouseDoorbell("Oreo"))
  }

  suspend fun lastSeal(): PostSeal {
    delay(1.milliseconds)
    return PostSeal(PostPrint(byteArrayOf(5)))
  }

  suspend fun noStamp(): Instant? {
    delay(1.milliseconds)
    return null
  }

  fun stamps(): Flow<Instant> = flowOf(OFFICE_POSTMARK, OFFICE_POSTMARK + 1.minutes)

  fun waits(): Flow<Duration> = flowOf(1.minutes, 2.minutes)

  fun codes(): Flow<Uuid> = flowOf(OFFICE_CODE)

  fun maybeStamps(): Flow<Instant?> = flowOf(OFFICE_POSTMARK, null)

  fun prints(): Flow<PostPrint> = flowOf(PostPrint(byteArrayOf(7)), PostPrint(byteArrayOf(7, 7)))

  fun bundles(): Flow<PostBundle> = flowOf(PostBundle(listOf("bill")))

  fun knocks(): Flow<PostKnock> = flowOf(PostKnock(HouseDoorbell("Mylo")))

  fun seals(): Flow<PostSeal> = flowOf(PostSeal(PostPrint(byteArrayOf(4, 4))))

  /** A `StateFlow` member taking a handle-backed argument: the bytes are read into the state. */
  fun padState(pads: ByteArray): StateFlow<Int> = MutableStateFlow(pads.size)

  /** ...and one taking a bare `Instant`. */
  fun epochState(at: Instant): StateFlow<Long> = MutableStateFlow(at.epochSeconds)

  /** An acquired `Flow` of a narrow element. */
  suspend fun acquiredStamps(): Flow<Instant> {
    delay(1.milliseconds)
    return flowOf(OFFICE_POSTMARK)
  }
}

/** An `object` owner: the static route of each `suspend` and `Flow` member. */
object PostDesk {
  suspend fun stampAt(at: Instant, pads: ByteArray): Instant {
    delay(1.milliseconds)
    return at + pads.size.minutes
  }

  suspend fun knockWith(bell: Doorbell): PostKnock {
    delay(1.milliseconds)
    return PostKnock(bell)
  }

  fun codeStream(id: Uuid, count: Int): Flow<Uuid> = flow { repeat(count) { emit(id) } }
}

/** A companion owner. */
class PostBox(val owner: String) {
  companion object {
    suspend fun open(window: PostWindow): Duration {
      delay(1.milliseconds)
      return window.span
    }

    fun ticketStream(ticket: PostTicket, count: Int): Flow<PostTicket> =
      flow { repeat(count) { emit(ticket) } }
  }
}

// --- nullable and value-class extension receivers -----------------------------------------------

suspend fun ByteArray?.padsOrNone(): Int {
  delay(1.milliseconds)
  return this?.size ?: -1
}

suspend fun Instant?.stampOrEpoch(): Instant {
  delay(1.milliseconds)
  return this ?: Instant.fromEpochSeconds(0)
}

suspend fun PostPrint?.printOrBlank(): Int {
  delay(1.milliseconds)
  return this?.pads?.size ?: 0
}

suspend fun PostWindow.halved(): PostWindow {
  delay(1.milliseconds)
  return PostWindow(span / 2)
}

suspend fun PostTicket.punched(): PostTicket {
  delay(1.milliseconds)
  return this
}

suspend fun PostSeal.sealed(): Int {
  delay(1.milliseconds)
  return print.pads.size
}

// --- content equality over a set and a map payload -----------------------------------------------

/** `Set` underlying: compared order-insensitively in C#. */
value class PostStack(val tags: Set<String>)

/** `Map` underlying: compared by the same keys with equal values in C#. */
value class PostLedger(val counts: Map<String, Int>)

fun echoStack(stack: PostStack): PostStack = stack

fun echoLedger(ledger: PostLedger): PostLedger = ledger

// --- settable holders of these types -------------------------------------------------------------

/** `MutableStateFlow` and `MutableSharedFlow` of a narrow type and of a wire value class. */
class PostBoard {
  val lastStamp: MutableStateFlow<Instant> = MutableStateFlow(OFFICE_POSTMARK)
  val maybeStamp: MutableStateFlow<Instant?> = MutableStateFlow(null)
  val lastWait: MutableStateFlow<Duration> = MutableStateFlow(1.minutes)
  val lastCode: MutableStateFlow<Uuid> = MutableStateFlow(OFFICE_CODE)
  val lastPrint: MutableStateFlow<PostPrint> = MutableStateFlow(PostPrint(byteArrayOf(1)))
  val stampFeed: MutableSharedFlow<Instant> = MutableSharedFlow(replay = 1)

  /** Reads the board back in Kotlin, so a C# write is observed on this side. */
  fun summary(): String =
    "${lastStamp.value.epochSeconds}|${maybeStamp.value?.epochSeconds}|" +
      "${lastWait.value.inWholeMinutes}|${lastCode.value}|${lastPrint.value.pads.size}|" +
      "${stampFeed.replayCache.lastOrNull()?.epochSeconds}"
}

/** A value class over a nullable underlying: `Nick(null)` and an absent `Nick?` stay distinct. */
value class PostNote(val text: String?)

suspend fun blankNote(): PostNote {
  delay(1.milliseconds)
  return PostNote(null)
}

suspend fun noNote(): PostNote? {
  delay(1.milliseconds)
  return null
}

fun notes(): Flow<PostNote> = flowOf(PostNote("bill"), PostNote(null))

// --- awaited StateFlow of these types ------------------------------------------------------------

class PostClock {
  suspend fun awaitStamp(): StateFlow<Instant> {
    delay(1.milliseconds)
    return MutableStateFlow(OFFICE_POSTMARK)
  }

  suspend fun awaitMaybeStamp(): StateFlow<Instant?> {
    delay(1.milliseconds)
    return MutableStateFlow(null)
  }

  suspend fun awaitPrint(): StateFlow<PostPrint> {
    delay(1.milliseconds)
    return MutableStateFlow(PostPrint(byteArrayOf(3, 3)))
  }
}

suspend fun awaitCode(): StateFlow<Uuid> {
  delay(1.milliseconds)
  return MutableStateFlow(OFFICE_CODE)
}

// --- settable holders of a handle-wired element ----------------------------------------------------

/** `MutableStateFlow`/`MutableSharedFlow` of a bare `ByteArray`, a collection and an interface. */
class PostRack {
  val pads: MutableStateFlow<ByteArray> = MutableStateFlow(byteArrayOf(1))
  val maybePads: MutableStateFlow<ByteArray?> = MutableStateFlow(null)
  val letters: MutableStateFlow<List<String>> = MutableStateFlow(listOf("bill"))
  val bell: MutableStateFlow<Doorbell> = MutableStateFlow(HouseDoorbell("Oreo"))
  val padFeed: MutableSharedFlow<ByteArray> = MutableSharedFlow(replay = 1)

  /** Kotlin's own writes, so C# can observe them. */
  fun kotlinWrites() {
    pads.value = byteArrayOf(9, 9, 9)
    letters.value = listOf("card", "parcel")
    bell.value = HouseDoorbell("Mylo")
  }

  /** What Kotlin sees, so a C# write is observed on this side. */
  fun summary(): String =
    "${pads.value.sum()}|${maybePads.value?.size}|${letters.value.joinToString(",")}|" +
      "${bell.value.ring(1)}|${padFeed.replayCache.lastOrNull()?.sum()}"
}

// --- arguments are copied for the call -------------------------------------------------------------

/** Reads the bytes only after the suspension, so a C# mutation after the call would show up. */
suspend fun sumLater(pads: ByteArray): Int {
  delay(50.milliseconds)
  return pads.sum()
}

/** Reads the list only after the suspension. */
suspend fun joinLater(letters: List<String>): String {
  delay(50.milliseconds)
  return letters.joinToString(",")
}

// --- awaited settable holders of a handle-wired element ---------------------------------------------

/** `suspend` members awaiting a `MutableStateFlow`/`MutableSharedFlow` this shelf keeps. */
class PostShelf {
  private val letters: MutableStateFlow<List<String>> = MutableStateFlow(listOf("bill"))
  private val pads: MutableStateFlow<ByteArray> = MutableStateFlow(byteArrayOf(1))
  private val bell: MutableStateFlow<Doorbell> = MutableStateFlow(HouseDoorbell("Oreo"))
  private val letterFeed: MutableSharedFlow<List<String>> = MutableSharedFlow(replay = 1)
  private val padFeed: MutableSharedFlow<ByteArray> = MutableSharedFlow(replay = 1)

  suspend fun awaitLetters(): MutableStateFlow<List<String>> {
    delay(1.milliseconds)
    return letters
  }

  suspend fun awaitPads(): MutableStateFlow<ByteArray> {
    delay(1.milliseconds)
    return pads
  }

  suspend fun awaitBell(): MutableStateFlow<Doorbell> {
    delay(1.milliseconds)
    return bell
  }

  suspend fun awaitLetterFeed(): MutableSharedFlow<List<String>> {
    delay(1.milliseconds)
    return letterFeed
  }

  suspend fun awaitPadFeed(): MutableSharedFlow<ByteArray> {
    delay(1.milliseconds)
    return padFeed
  }

  /** What Kotlin sees, so a C# write through an awaited holder is observed on this side. */
  fun summary(): String =
    "${letters.value.joinToString(",")}|${pads.value.sum()}|${bell.value.ring(1)}|" +
      "${letterFeed.replayCache.lastOrNull()?.joinToString(",")}|${padFeed.replayCache.lastOrNull()?.sum()}"
}
