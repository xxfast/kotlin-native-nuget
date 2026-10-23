package io.github.xxfast.kotlin.native.nuget.test.pawpost

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * ADR-240: the legacy `suspend` and `Flow` routes carry every type the ordinary route already
 * carries. A bare `Instant`, `Duration`, `Uuid`, `ByteArray` or plain interface, and a value class
 * over any underlying (a `String`, an `Int`, and the ADR-235 ones), crosses as a parameter and a
 * result of a `suspend` member, as a parameter and an element of a `Flow`-returning member, and as
 * the receiver of a static `suspend` member.
 *
 * Oreo (black, white middle) and Mylo (brown and creamy) run the household mail slot: they stamp
 * letters, scan paw prints onto them, bundle them and answer the doorbell, usually after a nap.
 */

/** A plain Kotlin interface, so the interface bridge has to cross the async routes. */
interface Doorbell {
  val owner: String
  fun ring(times: Int): String
}

/** The Kotlin-backed [Doorbell]. */
class HouseDoorbell(override val owner: String) : Doorbell {
  override fun ring(times: Int): String = "$owner: " + "ding".repeat(times)
}

/** `String` underlying: a letter is always signed. */
value class PostName(val name: String) {
  init {
    require(name.isNotEmpty()) { "a letter needs a signature" }
  }
}

/** `Int` underlying: a cat cannot post a negative number of letters. */
value class PostCount(val count: Int) {
  init {
    require(count >= 0) { "a post count cannot be negative" }
  }
}

/** `Instant` underlying: no postmark before the epoch. */
value class PostStamp(val at: Instant) {
  init {
    require(at.epochSeconds >= 0) { "a postmark cannot predate 1970" }
  }

  /** A suspend member over an `Instant` receiver. */
  suspend fun later(minutes: Int): PostStamp {
    delay(1.milliseconds)
    return PostStamp(at + minutes.minutes)
  }
}

/** `Duration` underlying: the post is never early. */
value class PostDelay(val span: Duration) {
  init {
    require(!span.isNegative()) { "a post delay cannot be negative" }
  }
}

/** `Uuid` underlying: the nil uuid is not a postcode. */
value class PostCode(val id: Uuid) {
  init {
    require(id != Uuid.NIL) { "the nil uuid is not a postcode" }
  }
}

/** `ByteArray` underlying: a paw print has at least one pad. */
value class PostPrint(val pads: ByteArray) {
  init {
    require(pads.isNotEmpty()) { "a paw print needs at least one pad" }
  }

  /** A suspend member over a `ByteArray` receiver. */
  suspend fun padCount(): Int {
    delay(1.milliseconds)
    return pads.size
  }
}

/** Collection underlying: a bundle holds at most three letters. */
value class PostBundle(val letters: List<String>) {
  init {
    require(letters.size <= 3) { "a bundle holds at most three letters" }
  }

  /** A suspend member over a collection receiver. */
  suspend fun top(): String {
    delay(1.milliseconds)
    return letters.first()
  }
}

/** Plain interface underlying: a knock nobody owns is no knock. */
value class PostKnock(val bell: Doorbell) {
  init {
    require(bell.owner.isNotEmpty()) { "a knock needs an owner" }
  }

  /** A suspend member over an interface receiver. */
  suspend fun knock(times: Int): String {
    delay(1.milliseconds)
    return bell.ring(times)
  }
}

/** A class with an instance scope, so its members ride the class suspend and Flow routes. */
class Mailslot(val owner: String) {

  // --- bare types: suspend parameter and result ---------------------------------------------

  suspend fun stamp(at: Instant): Instant {
    delay(1.milliseconds)
    return at + 1.minutes
  }

  suspend fun stampOrNull(at: Instant?): Instant? {
    delay(1.milliseconds)
    return at
  }

  suspend fun hold(span: Duration): Duration {
    delay(1.milliseconds)
    return span * 2
  }

  suspend fun holdOrNull(span: Duration?): Duration? {
    delay(1.milliseconds)
    return span
  }

  suspend fun code(id: Uuid): Uuid {
    delay(1.milliseconds)
    return id
  }

  suspend fun codeOrNull(id: Uuid?): Uuid? {
    delay(1.milliseconds)
    return id
  }

  /** The pads come back with a postmark pad appended, so the bytes are read in Kotlin. */
  suspend fun scan(pads: ByteArray): ByteArray {
    delay(1.milliseconds)
    return pads + 9.toByte()
  }

  suspend fun scanOrNull(pads: ByteArray?): ByteArray? {
    delay(1.milliseconds)
    return pads
  }

  suspend fun answer(bell: Doorbell): Doorbell {
    delay(1.milliseconds)
    return bell
  }

  suspend fun answerOrNull(bell: Doorbell?): Doorbell? {
    delay(1.milliseconds)
    return bell
  }

  /** Rings after the suspension, so the bell has to outlive the extern's return. */
  suspend fun hear(bell: Doorbell, times: Int): String {
    delay(5.milliseconds)
    return bell.ring(times)
  }

  // --- value classes: suspend parameter and result ------------------------------------------

  suspend fun sign(name: PostName): PostName {
    delay(1.milliseconds)
    return PostName(name.name + " x")
  }

  suspend fun signOrNull(name: PostName?): PostName? {
    delay(1.milliseconds)
    return name
  }

  suspend fun tally(count: PostCount): PostCount {
    delay(1.milliseconds)
    return PostCount(count.count + 1)
  }

  suspend fun tallyOrNull(count: PostCount?): PostCount? {
    delay(1.milliseconds)
    return count
  }

  suspend fun frank(stamp: PostStamp): PostStamp {
    delay(1.milliseconds)
    return PostStamp(stamp.at + 1.minutes)
  }

  suspend fun frankOrNull(stamp: PostStamp?): PostStamp? {
    delay(1.milliseconds)
    return stamp
  }

  suspend fun linger(wait: PostDelay): PostDelay {
    delay(1.milliseconds)
    return PostDelay(wait.span * 2)
  }

  suspend fun route(code: PostCode): PostCode {
    delay(1.milliseconds)
    return code
  }

  suspend fun press(print: PostPrint): PostPrint {
    delay(1.milliseconds)
    return PostPrint(print.pads + 1.toByte())
  }

  suspend fun pressOrNull(print: PostPrint?): PostPrint? {
    delay(1.milliseconds)
    return print
  }

  suspend fun pack(bundle: PostBundle): PostBundle {
    delay(1.milliseconds)
    return PostBundle(bundle.letters.reversed())
  }

  suspend fun packOrNull(bundle: PostBundle?): PostBundle? {
    delay(1.milliseconds)
    return bundle
  }

  suspend fun relay(knock: PostKnock): PostKnock {
    delay(1.milliseconds)
    return knock
  }

  suspend fun relayOrNull(knock: PostKnock?): PostKnock? {
    delay(1.milliseconds)
    return knock
  }

  // --- only cancellation ends these, with a handle-backed argument in flight -----------------

  suspend fun awaitStamp(at: Instant): Instant = awaitCancellation()
  suspend fun awaitScan(pads: ByteArray): ByteArray = awaitCancellation()
  suspend fun awaitAnswer(bell: Doorbell): Doorbell = awaitCancellation()
  suspend fun awaitPress(print: PostPrint): PostPrint = awaitCancellation()
  suspend fun awaitPack(bundle: PostBundle): PostBundle = awaitCancellation()
  suspend fun awaitRelay(knock: PostKnock): PostKnock = awaitCancellation()

  // --- faults, with a handle-backed argument in flight ---------------------------------------

  suspend fun jamScan(pads: ByteArray): ByteArray {
    delay(1.milliseconds)
    throw IllegalStateException("$owner chewed ${pads.size} pads")
  }

  suspend fun jamAnswer(bell: Doorbell): Doorbell {
    delay(1.milliseconds)
    throw IllegalStateException("$owner hid from ${bell.owner}")
  }

  suspend fun jamPack(bundle: PostBundle): PostBundle {
    delay(1.milliseconds)
    throw IllegalStateException("$owner sat on ${bundle.letters.size} letters")
  }

  suspend fun jamRelay(knock: PostKnock): PostKnock {
    delay(1.milliseconds)
    throw IllegalStateException("$owner ignored ${knock.bell.owner}")
  }

  // --- Flow-returning members taking each type, emitting it back -----------------------------

  fun stampTicks(at: Instant, count: Int): Flow<Instant> = flow {
    repeat(count) { emit(at + it.minutes) }
  }

  fun holdTicks(span: Duration, count: Int): Flow<Duration> = flow {
    repeat(count) { emit(span * (it + 1)) }
  }

  fun codeEchoes(id: Uuid, count: Int): Flow<Uuid> = flow {
    repeat(count) { emit(id) }
  }

  fun scanEchoes(pads: ByteArray, count: Int): Flow<ByteArray> = flow {
    repeat(count) { emit(pads + it.toByte()) }
  }

  fun rings(bell: Doorbell, count: Int): Flow<String> = flow {
    repeat(count) { emit(bell.ring(it + 1)) }
  }

  fun bells(bell: Doorbell, count: Int): Flow<Doorbell> = flow {
    repeat(count) { emit(bell) }
  }

  fun signEchoes(name: PostName, count: Int): Flow<PostName> = flow {
    repeat(count) { emit(PostName(name.name + it)) }
  }

  fun tallyTicks(start: PostCount, count: Int): Flow<PostCount> = flow {
    repeat(count) { emit(PostCount(start.count + it)) }
  }

  fun frankTicks(stamp: PostStamp, count: Int): Flow<PostStamp> = flow {
    repeat(count) { emit(PostStamp(stamp.at + it.minutes)) }
  }

  fun pressEchoes(print: PostPrint, count: Int): Flow<PostPrint> = flow {
    repeat(count) { emit(PostPrint(print.pads + it.toByte())) }
  }

  fun packEchoes(bundle: PostBundle, count: Int): Flow<PostBundle> = flow {
    repeat(count) { emit(bundle) }
  }

  fun relayEchoes(knock: PostKnock, count: Int): Flow<PostKnock> = flow {
    repeat(count) { emit(knock) }
  }

  /** Never completes, so only the enumerator's cancellation ends it. */
  fun endlessScans(pads: ByteArray): Flow<ByteArray> = flow {
    while (true) {
      emit(pads)
      delay(1.milliseconds)
    }
  }

  /** Never completes, with an interface argument captured by the flow. */
  fun endlessRings(bell: Doorbell): Flow<String> = flow {
    while (true) {
      emit(bell.ring(1))
      delay(1.milliseconds)
    }
  }
}
