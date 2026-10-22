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
 * ADR-240: the top-level `suspend` route, the static `Flow` route (ADR-230) and the suspend
 * extension route (ADR-229) over the same types [Mailslot] takes.
 */

/** A Kotlin-backed [Doorbell], so a C# caller can hand Kotlin its own object back. */
fun oreoBell(): Doorbell = HouseDoorbell("Oreo")

// --- top-level suspend: bare types --------------------------------------------------------------

suspend fun postmark(at: Instant): Instant {
  delay(1.milliseconds)
  return at + 1.minutes
}

suspend fun postWait(span: Duration): Duration {
  delay(1.milliseconds)
  return span + 1.minutes
}

suspend fun postRoute(id: Uuid): Uuid {
  delay(1.milliseconds)
  return id
}

suspend fun postPads(pads: ByteArray): ByteArray {
  delay(1.milliseconds)
  return pads + 7.toByte()
}

suspend fun postPadsOrNull(pads: ByteArray?): ByteArray? {
  delay(1.milliseconds)
  return pads
}

suspend fun postRing(bell: Doorbell, times: Int): String {
  delay(5.milliseconds)
  return bell.ring(times)
}

suspend fun postBell(bell: Doorbell): Doorbell {
  delay(1.milliseconds)
  return bell
}

suspend fun postBellOrNull(bell: Doorbell?): Doorbell? {
  delay(1.milliseconds)
  return bell
}

// --- top-level suspend: value classes over every underlying -------------------------------------

suspend fun signPost(name: PostName): PostName {
  delay(1.milliseconds)
  return PostName(name.name + " x")
}

suspend fun tallyPost(count: PostCount): PostCount {
  delay(1.milliseconds)
  return PostCount(count.count * 2)
}

suspend fun frankPost(stamp: PostStamp): PostStamp {
  delay(1.milliseconds)
  return PostStamp(stamp.at + 1.minutes)
}

suspend fun delayPost(wait: PostDelay): PostDelay {
  delay(1.milliseconds)
  return PostDelay(wait.span + 1.minutes)
}

suspend fun routePost(code: PostCode): PostCode {
  delay(1.milliseconds)
  return code
}

suspend fun pressPost(print: PostPrint): PostPrint {
  delay(1.milliseconds)
  return PostPrint(print.pads + 2.toByte())
}

suspend fun packPost(bundle: PostBundle): PostBundle {
  delay(1.milliseconds)
  return PostBundle(bundle.letters.sorted())
}

suspend fun packPostOrNull(bundle: PostBundle?): PostBundle? {
  delay(1.milliseconds)
  return bundle
}

suspend fun relayPost(knock: PostKnock): PostKnock {
  delay(1.milliseconds)
  return knock
}

/** Only cancellation ends it, with a bytes handle in flight. */
suspend fun lingerPads(pads: ByteArray): ByteArray = awaitCancellation()

/** Only cancellation ends it, with a list handle in flight. */
suspend fun lingerBundle(bundle: PostBundle): PostBundle = awaitCancellation()

/** Mylo chewed the bundle. */
suspend fun jamBundle(bundle: PostBundle): PostBundle {
  delay(1.milliseconds)
  throw IllegalStateException("Mylo chewed ${bundle.letters.size} letters")
}

// --- top-level Flow (ADR-230's static route) taking each type -----------------------------------

fun padStream(pads: ByteArray, count: Int): Flow<ByteArray> = flow {
  repeat(count) { emit(pads + it.toByte()) }
}

fun stampStream(at: Instant, count: Int): Flow<Instant> = flow {
  repeat(count) { emit(at + it.minutes) }
}

fun bellStream(bell: Doorbell, count: Int): Flow<String> = flow {
  repeat(count) { emit(bell.ring(it + 1)) }
}

fun printStream(print: PostPrint, count: Int): Flow<PostPrint> = flow {
  repeat(count) { emit(PostPrint(print.pads + it.toByte())) }
}

fun bundleStream(bundle: PostBundle, count: Int): Flow<PostBundle> = flow {
  repeat(count) { emit(bundle) }
}

fun nameStream(name: PostName, count: Int): Flow<PostName> = flow {
  repeat(count) { emit(PostName(name.name + it)) }
}

// --- suspend extensions: receivers of each type --------------------------------------------------

suspend fun Instant.nudge(minutes: Int): Instant {
  delay(1.milliseconds)
  return this + minutes.minutes
}

suspend fun Duration.stretch(): Duration {
  delay(1.milliseconds)
  return this * 3
}

suspend fun Uuid.shout(): String {
  delay(1.milliseconds)
  return toString().uppercase()
}

suspend fun ByteArray.padTotal(): Int {
  delay(1.milliseconds)
  return sum()
}

suspend fun Doorbell.chime(): String {
  delay(1.milliseconds)
  return ring(2)
}

suspend fun Doorbell?.chimeOrSilence(): String {
  delay(1.milliseconds)
  return this?.ring(1) ?: "silence"
}

suspend fun PostName.greet(): String {
  delay(1.milliseconds)
  return "Dear $name"
}

suspend fun PostCount.doubled(): PostCount {
  delay(1.milliseconds)
  return PostCount(count * 2)
}

suspend fun PostStamp.postmarked(): String {
  delay(1.milliseconds)
  return "postmarked ${at.epochSeconds}"
}

suspend fun PostPrint.smudge(): PostPrint {
  delay(1.milliseconds)
  return PostPrint(pads + 0.toByte())
}

suspend fun PostBundle.letterCount(): Int {
  delay(1.milliseconds)
  return letters.size
}

suspend fun PostKnock.answered(): String {
  delay(1.milliseconds)
  return "answered ${bell.owner}"
}

/** Only cancellation ends it, with a bytes receiver in flight. */
suspend fun ByteArray.lingerOn(): Int = awaitCancellation()
