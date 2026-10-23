package io.github.xxfast.kotlin.native.nuget.test.bindle

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import test.menagerie.IFeedable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * ADR-238: an extension binds on every receiver, sync and `suspend`, generic or not.
 *
 * Every receiver kind is crossed at all three extension positions (a sync function, an extension
 * property and a `suspend` function), because a fixture trimmed to the easiest receiver would
 * pick the one kind that needs no work at the seam:
 *
 *  - an exported generic class at the extension's own `T` ([Bindle]), a closed `Bindle<Int>` and
 *    a star projection `Bindle<*>` (a synthesized method type parameter in C#);
 *  - a bare `T`: bounded ([Dozer], home `DozerExtensions`), an intersection bound (homes on the
 *    first declared bound), unbounded `T` and `T : Any` (home `ObjectExtensions`), and `T?`;
 *  - a collection, a nullable collection and a star-projected `List<*>`;
 *  - a Kotlin interface ([Dozer]) and a bound C# interface ([IFeedable]), each nullable too;
 *  - value classes over a primitive ([Paws]) and over a string ([BindleTag]), each nullable too;
 *  - an enum ([Drowse]) and a nullable enum;
 *  - `ByteArray` and `ByteArray?`;
 *  - the converting stdlib types `Uuid`, `Instant` and `Duration`, and the in-band and fan-out
 *    nullables `String?`, `Char?` and `Int?`.
 *
 * Beside them: a `suspend` extension returning `Result<T>` (and its `reified` twin), a `Flow`
 * result of a `suspend` extension, and member extensions on a class ([Groomhand]).
 *
 * Oreo (black, white middle) is carried in the bindle; Mylo (brown and creamy) loafs beside it.
 */

/** The generic class every generic receiver below extends. */
class Bindle<T>(var top: T)

/** A Kotlin interface: the bare-`T` bound and the plain interface receiver. */
interface Dozer {
  val name: String
}

/** The second bound of the intersection receiver. */
interface Preener {
  fun preen(): String
}

/** The Kotlin-backed [Dozer] (and [Preener]). */
class Loafer(override val name: String) : Dozer, Preener {
  override fun preen(): String = "$name preens"
}

/** An enum with no members of its own, so its `DrowseExtensions` is only this file's. */
enum class Drowse { CATNAP, DEEP }

/** A value class over a primitive: how many paws are tucked in. */
value class Paws(val count: Int)

/** A value class over a string: the tag on Oreo's bindle. */
value class BindleTag(val id: String)

// ---- sync functions: generic receivers ---------------------------------------------------------

/** The extension's own `T` at the receiver's type argument. */
fun <T> Bindle<T>.unpack(): T = top

/** The own `T` at the receiver and a parameter: Oreo is swapped for Mylo. */
fun <T> Bindle<T>.swap(value: T): T {
  val old = top
  top = value
  return old
}

/** A closed generic receiver. */
fun Bindle<Int>.doubled(): Int = top * 2

/** A star projection: a synthesized type parameter in C#. */
fun Bindle<*>.isPacked(): Boolean = top != null

/** A star-projected collection. */
fun List<*>.headcount(): Int = size

/** A bare `T` with an exported bound: homes on `DozerExtensions`. */
fun <T : Dozer> T.introduce(): String = "I am $name"

/** A bare nullable `T` with an exported bound. */
fun <T : Dozer> T?.introduceOrNobody(): String = this?.name ?: "nobody"

/** An intersection bound: homes on the first declared bound, [Dozer]. */
fun <T> T.primp(): String where T : Dozer, T : Preener = "${name}: ${preen()}"

/** An unbounded `T`: homes on `ObjectExtensions`. */
fun <T> T.tag(): String = "tag:$this"

/** `T : Any`, also unbounded in C#. */
fun <T : Any> T.nonNullTag(): String = "tag!:$this"

// ---- sync functions: every other receiver ------------------------------------------------------

fun List<Int>.tallyUp(): Int = sum()

fun List<Int>?.tallyOrNone(): Int = this?.sum() ?: -1

fun Dozer.snoreLine(): String = "$name snores"

fun Dozer?.snoreOrSilence(): String = this?.name?.let { "$it snores" } ?: "silence"

fun IFeedable.bowlLine(): String = "${describe()} gets $legs bowls"

fun IFeedable?.bowlsOrNone(): Int = this?.legs ?: -1

fun Paws.tuck(): Int = count + 1

fun Paws?.tuckOrNone(): Int = this?.count ?: -1

fun BindleTag.stamp(): String = "[${id}]"

fun BindleTag?.stampOrBlank(): String = this?.id ?: "blank"

fun Drowse.depth(): Int = ordinal + 1

fun Drowse?.depthOrAwake(): String = this?.name ?: "awake"

fun ByteArray.crumbs(): Int = sum()

fun ByteArray?.crumbsOrNone(): Int = this?.size ?: -1

fun Uuid.chipHead(): String = toString().substringBefore('-')

fun Instant?.dayOrEpoch(): Long = this?.epochSeconds?.floorDiv(86_400L) ?: 0L

fun Duration.napMinutes(): Long = inWholeMinutes

// ---- extension properties ---------------------------------------------------------------------

/** A generic `val`: the own `T` at the receiver's type argument. */
val <T> Bindle<T>.peek: T get() = top

/** A generic `var`: the setter carries the own `T` as its value. */
var <T> Bindle<T>.lid: T
  get() = top
  set(value) {
    top = value
  }

/** A star-projected `val`. */
val Bindle<*>.packed: Boolean get() = top != null

/** A bare bounded `T`. */
val <T : Dozer> T.badge: String get() = "badge:$name"

/** A bare unbounded `T`: `extension<T>(T receiver)` on `ObjectExtensions`. */
val <T> T.label: String get() = "label:$this"

val List<Int>.tally: Int get() = sum()

val List<Int>?.tallyOrZero: Int get() = this?.sum() ?: 0

val List<*>.rollCall: Int get() = size

val Dozer.snore: String get() = "$name zzz"

val IFeedable.bowls: Int get() = legs

val IFeedable?.bowlsOrZero: Int get() = this?.legs ?: 0

val Paws.toeBeans: Int get() = count * 4

val Paws?.toeBeansOrNone: Int get() = this?.count?.times(4) ?: -1

val BindleTag?.tagOrBlank: String get() = this?.id ?: "blank"

val Drowse?.drowsiness: String get() = this?.name ?: "awake"

val ByteArray.crumbTotal: Int get() = sum()

val ByteArray?.isBlank: Boolean get() = this == null || isEmpty()

val Uuid.chipTail: String get() = toString().substringAfterLast('-')

val Duration?.napOrNone: Long get() = this?.inWholeMinutes ?: -1L

// ---- suspend functions -------------------------------------------------------------------------

suspend fun <T> Bindle<T>.unpackLater(): T {
  delay(1.milliseconds)
  return top
}

suspend fun Bindle<Int>.doubledLater(): Int {
  delay(1.milliseconds)
  return top * 2
}

suspend fun Bindle<*>.isPackedLater(): Boolean {
  delay(1.milliseconds)
  return top != null
}

suspend fun <T : Dozer> T.yawnLater(): String {
  delay(1.milliseconds)
  return "$name yawns"
}

suspend fun <T> T.tagLater(): String {
  delay(1.milliseconds)
  return "tag:$this"
}

suspend fun List<Int>.total(): Int {
  delay(1.milliseconds)
  return sum()
}

suspend fun List<Int>?.totalOrZero(): Int {
  delay(1.milliseconds)
  return this?.sum() ?: 0
}

suspend fun Dozer.greet(): String {
  delay(5.milliseconds)
  return "hi $name"
}

suspend fun Dozer?.greetOrNobody(): String {
  delay(1.milliseconds)
  return this?.let { "hi ${it.name}" } ?: "nobody"
}

suspend fun IFeedable.bowlsLater(): Int {
  delay(5.milliseconds)
  return legs
}

suspend fun IFeedable?.bowlsLaterOrNone(): Int {
  delay(1.milliseconds)
  return this?.legs ?: -1
}

suspend fun Paws.tuckLater(): Int {
  delay(1.milliseconds)
  return count + 1
}

suspend fun Paws?.tuckLaterOrNone(): Int {
  delay(1.milliseconds)
  return this?.count ?: -1
}

suspend fun BindleTag.badgeLater(): String {
  delay(1.milliseconds)
  return id
}

suspend fun BindleTag?.badgeOrStray(): String {
  delay(1.milliseconds)
  return this?.id ?: "stray"
}

suspend fun Drowse.deepen(): Drowse {
  delay(1.milliseconds)
  return Drowse.DEEP
}

suspend fun Drowse?.loudly(): String {
  delay(1.milliseconds)
  return this?.name ?: "quiet"
}

suspend fun Int?.orNone(): Int {
  delay(1.milliseconds)
  return this ?: -1
}

suspend fun Char?.pawOrNone(): String {
  delay(1.milliseconds)
  return this?.let { "$it-paw" } ?: "no paw"
}

suspend fun String?.nameOrStray(): String {
  delay(1.milliseconds)
  return this ?: "stray"
}

suspend fun Uuid.short(): String {
  delay(1.milliseconds)
  return toString().take(8)
}

suspend fun Uuid?.shortOrNone(): String {
  delay(1.milliseconds)
  return this?.toString()?.take(8) ?: "none"
}

suspend fun Instant?.laterOrEpoch(): Instant {
  delay(1.milliseconds)
  return this?.plus(1.minutes) ?: Instant.fromEpochSeconds(0)
}

suspend fun Duration?.longerOrZero(): Duration {
  delay(1.milliseconds)
  return this?.plus(1.minutes) ?: Duration.ZERO
}

suspend fun ByteArray.checksum(): Int {
  delay(1.milliseconds)
  return sum()
}

suspend fun ByteArray?.checksumOrNone(): Int {
  delay(1.milliseconds)
  return this?.sum() ?: -1
}

/** Only cancellation ends it, with a collection receiver in flight. */
suspend fun List<Int>.tallyForever(): Int = awaitCancellation()

/** A `Result<T>` result: `Task<string>`, a failure faults the task. */
suspend fun Dozer.wakeOrFail(ok: Boolean): Result<String> {
  delay(1.milliseconds)
  return if (ok) {
    Result.success("$name is awake")
  } else {
    Result.failure(IllegalStateException("$name refuses to wake"))
  }
}

/** The `reified T` twin: `T` reifies at its bound. */
suspend inline fun <reified T> Dozer.recall(value: T): Result<T> {
  delay(1.milliseconds)
  return Result.success(value)
}

/** A `Flow` result of a `suspend` extension. */
suspend fun Loafer.watch(count: Int): Flow<Int> {
  delay(1.milliseconds)
  return flow { repeat(count) { emit(it) } }
}

/** A top-level `suspend` function returning `Result<T>`. */
suspend fun fetchKibble(ok: Boolean): Result<String> {
  delay(1.milliseconds)
  return if (ok) {
    Result.success("kibble")
  } else {
    Result.failure(IllegalArgumentException("the bowl is empty"))
  }
}

// ---- Flow results and Flow parameters ----------------------------------------------------------

/** A `Flow` result on a class receiver: the static flow route, the receiver crossing first. */
fun Loafer.ticks(count: Int): Flow<Int> = flow { repeat(count) { emit(it) } }

/** A nullable receiver: a null C# `Loafer?` crosses as the null handle. */
fun Loafer?.ticksOrNone(): Flow<String> {
  val name: String = this?.name ?: "nobody"
  return flowOf(name)
}

/** The own `T` at a generic receiver. */
fun <T> Bindle<T>.drip(): Flow<T> = flowOf(top, top)

/** A collection receiver, on the parameter path. */
fun List<Int>.stream(): Flow<Int> = asFlow()

/** A nullable enum receiver, its has-value pair rebuilt before the call. */
fun Drowse?.drift(): Flow<String> = flowOf(this?.name ?: "awake")

/** A `StateFlow` result. */
fun Loafer.purr(): StateFlow<Int> = MutableStateFlow(name.length)

/** A `SharedFlow` result, replaying the name. */
fun Loafer.chatter(): SharedFlow<String> =
  MutableSharedFlow<String>(replay = 1).also { shared -> shared.tryEmit(name) }

/** A `Flow` parameter beside a `Flow` result on a generic receiver: `top`, then the events. */
fun <T> Bindle<T>.relay(events: Flow<T>): Flow<T> = flow {
  emit(top)
  emitAll(events)
}

/** A `Flow` parameter on a nullable receiver, held rather than collected. */
fun Loafer?.hears(events: Flow<Int>): String = if (this == null) "nobody" else "$name listens"

// ---- member extensions -------------------------------------------------------------------------

/** A class whose members are extensions: `groomhand.Brush(loafer, 3)` in C#. */
class Groomhand(val name: String) {
  fun Loafer.brush(strokes: Int): String = "${this@Groomhand.name} brushes $name $strokes times"

  suspend fun Loafer.brushLater(): String {
    delay(1.milliseconds)
    return "${this@Groomhand.name} brushed $name"
  }

  /** A member extension property: `GetNick(Loafer receiver)`, Kotlin/JVM's own lowering. */
  val Loafer.nick: String get() = "${this@Groomhand.name}'s $name"

  /** A nullable receiver: `GetNickOrNone(Loafer? receiver)`. */
  val Loafer?.nickOrNone: String get() = this?.name ?: "nobody"

  private val moods: MutableMap<String, Int> = mutableMapOf()

  /** A `var`: `GetMood(Loafer receiver)` and `SetMood(Loafer receiver, int value)`. */
  var Loafer.mood: Int
    get() = moods[name] ?: 0
    set(value) {
      moods[name] = value
    }

  /** A generic receiver: `GetPeek<T>(Bindle<T> receiver)`. */
  val <T> Bindle<T>.peekInside: T get() = top

  /** A `Flow` typed one is a flow method: `Stream(Loafer receiver)`. */
  val Loafer.stream: Flow<String> get() = flowOf(this@Groomhand.name, name)

  /** A `Result<T>` result on a class's own suspend route. */
  suspend fun appointment(ok: Boolean): Result<String> {
    delay(1.milliseconds)
    return if (ok) {
      Result.success("booked with $name")
    } else {
      Result.failure(IllegalStateException("$name is out"))
    }
  }
}
