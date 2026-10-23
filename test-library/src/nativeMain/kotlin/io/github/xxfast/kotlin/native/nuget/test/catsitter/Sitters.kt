package io.github.xxfast.kotlin.native.nuget.test.catsitter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * ADR-233: a C#-implemented interface slot carries every type an ordinary member plan carries.
 * The slot wire is the plan's wire, so [Sitter] takes and returns an exported class handle, a
 * generic class handle, collections of strings, numbers and handles, a value class, nullable
 * primitives, a nullable enum, a nullable handle, `Char`, `Instant`, `Duration`, `Uuid`,
 * `ByteArray`, a plain lambda in and out, a suspend lambda, a generic interface result and a
 * member with four parameters. Each Kotlin function below reads one slot and echoes what the C#
 * implementation handed back, so a slot that delivered a constant shows up.
 *
 * Oreo needs a sitter who knows about his tuna. Mylo needs one who can count to four.
 */
class Kitten(val name: String) {
  fun purr(): String = "$name purrs"

  /** A class member whose `bytes` parameter and `ByteArray` result each need a C# local. */
  fun echo(bytes: ByteArray): ByteArray = bytes.reversedArray()
}

/** A generic exported class, so the slot crosses a generic handle too. */
class Crate<T>(val item: T)

/** A value class over an `Int`, unwrapped at the slot like at any plan position. */
value class Whisker(val length: Int)

/** The enum a nullable slot carries. */
enum class Coat { TUXEDO, CARAMEL }

/** A generic interface a [Sitter] member returns, the case a `Keepsake<String>` result names. */
interface Trinket<T> {
  val item: T
}

/** The Kotlin-backed [Trinket], so the generic interface is reachable from Kotlin too. */
class Bauble<T>(override val item: T) : Trinket<T>

/** A Kotlin [Trinket] a C# [Sitter] can hand back as is. */
fun bauble(item: String): Trinket<String> = Bauble(item)

interface Sitter {
  fun adopt(name: String): Kitten

  fun cuddle(kitten: Kitten): String

  fun foster(kitten: Kitten?): Kitten?

  fun pack(item: String): Crate<String>

  fun unpack(crate: Crate<Int>): Int

  fun names(): List<String>

  fun weigh(weights: Map<String, Int>): Int

  fun litter(kittens: List<Kitten>): Set<String>

  fun trim(whisker: Whisker): Whisker

  fun treats(cat: String): Int?

  fun indoor(cat: String): Boolean?

  fun coat(cat: String): Coat?

  fun initial(cat: String): Char

  fun lastFed(at: Instant): Instant

  fun nap(length: Duration): Duration

  fun chip(id: Uuid): Uuid

  fun photo(bytes: ByteArray): ByteArray

  fun onPurr(callback: (String) -> Int): Int

  fun purrer(): (Int) -> String

  suspend fun whenHungry(callback: suspend (String) -> Int): Int

  fun favourite(): Trinket<String>

  fun groom(cat: String, strokes: Int, minutes: Double, gentle: Boolean): String
}

/** Kotlin asks the C# sitter for a kitten handle and calls a member on it. */
fun adoption(sitter: Sitter, name: String): String = sitter.adopt(name).purr()

/** A Kotlin-made handle passed into a C# slot as a borrowed argument. */
fun cuddling(sitter: Sitter, name: String): String = sitter.cuddle(Kitten(name))

/** A nullable handle in and out: a value and a null, in one call. */
fun fostering(sitter: Sitter, name: String): String =
  "${sitter.foster(Kitten(name))?.name}/${sitter.foster(null)?.name}"

/** A generic class handle in each direction. */
fun crating(sitter: Sitter, item: String, count: Int): String =
  "${sitter.pack(item).item}/${sitter.unpack(Crate(count))}"

/** Collections in each direction, of strings, numbers and handles. */
fun census(sitter: Sitter): String =
  listOf(
    "names=${sitter.names().joinToString()}",
    "weight=${sitter.weigh(mapOf("Oreo" to 5, "Mylo" to 6))}",
    "litter=${sitter.litter(listOf(Kitten("Oreo"), Kitten("Mylo"))).sorted().joinToString()}",
  ).joinToString("; ")

/** A value class, nullable primitives, a nullable enum and a `Char`, each for both cats. */
fun details(sitter: Sitter): String =
  listOf("Oreo", "Mylo").joinToString("; ") { cat ->
    "$cat trim=${sitter.trim(Whisker(cat.length)).length} treats=${sitter.treats(cat)} " +
      "indoor=${sitter.indoor(cat)} coat=${sitter.coat(cat)} initial=${sitter.initial(cat)}"
  }

/** `Instant`, `Duration`, `Uuid` and `ByteArray` in and out. */
fun records(sitter: Sitter): String {
  val fed: Instant = sitter.lastFed(Instant.fromEpochMilliseconds(1_792_656_000_000L))
  val nap: Duration = sitter.nap(20.minutes)
  val chip: Uuid = sitter.chip(Uuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
  val photo: ByteArray = sitter.photo(byteArrayOf(1, 2, 3))
  return "fed=${fed.toEpochMilliseconds()} nap=${nap.inWholeMinutes} chip=$chip " +
    "photo=${photo.joinToString(",")}"
}

/** A Kotlin lambda handed to C#, which calls it, and a C# lambda handed back for Kotlin to call. */
fun purring(sitter: Sitter): String =
  "${sitter.onPurr { cat -> cat.length * 10 }}/${sitter.purrer()(3)}"

/** A suspend Kotlin lambda handed to a suspend C# slot. */
suspend fun hungerPangs(sitter: Sitter): Int = sitter.whenHungry { cat -> cat.length + 100 }

/** A generic interface the C# slot returns, read through its own bridge. */
fun treasure(sitter: Sitter): String = "favourite ${sitter.favourite().item}"

/** Four parameters of four wires, in declaration order. */
fun grooming(sitter: Sitter): String = sitter.groom("Mylo", 4, 2.5, true)

/** A C# fault from a handle-returning slot, caught in Kotlin. */
fun adoptionOrRecover(sitter: Sitter, name: String): String =
  try {
    sitter.adopt(name).purr()
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (fault: Throwable) {
    "recovered ${fault.message}"
  }

/** A C# fault from a collection-returning slot, caught in Kotlin. */
fun censusOrRecover(sitter: Sitter): String =
  try {
    sitter.names().joinToString()
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (fault: Throwable) {
    "recovered ${fault.message}"
  }

/**
 * ADR-233: every `Flow` shape a C#-implemented slot carries. Flows of a class handle, an enum, a
 * nullable string and a value class, a `var` of a flow, nullable
 * flows and a suspend member returning a hot flow.
 */
interface Tracker {
  fun kittens(): Flow<Kitten>

  fun coats(): Flow<Coat>

  fun sightings(): Flow<String?>

  fun whiskers(): Flow<Whisker>

  var feed: Flow<String>

  fun maybe(): Flow<String>?

  val mood: StateFlow<Int>?

  suspend fun level(): StateFlow<Int>
}

/** Every element of every cold flow, in one string. */
suspend fun sightings(tracker: Tracker): String =
  listOf(
    "kittens=${tracker.kittens().toList().joinToString { it.name }}",
    "coats=${tracker.coats().toList().joinToString()}",
    "sightings=${tracker.sightings().toList().joinToString()}",
    "whiskers=${tracker.whiskers().toList().joinToString { it.length.toString() }}",
  ).joinToString("; ")

/** Kotlin reads the C# `var`, then replaces it with a Kotlin flow and reads it back. */
suspend fun refeed(tracker: Tracker, meal: String): String {
  val before: String = tracker.feed.toList().joinToString()
  tracker.feed = kotlinx.coroutines.flow.flowOf(meal)
  return "$before -> ${tracker.feed.toList().joinToString()}"
}

/** Nullable flows: a C# flow or a C# null, either way. */
suspend fun maybes(tracker: Tracker): String =
  "maybe=${tracker.maybe()?.toList()?.joinToString()} mood=${tracker.mood?.value}"

/** A suspend member returning a hot flow, awaited and then read. */
suspend fun levelling(tracker: Tracker): Int = tracker.level().first()

/**
 * ADR-233: a generic interface in no position, only a supertype. [Hamper] inherits its property,
 * result and parameter types from [Basket]'s `T`, substituted with `String`.
 */
interface Basket<T> {
  val item: T

  fun put(item: T): T
}

/** The C#-implemented sub-interface, reached only as an input. */
interface Hamper : Basket<String> {
  fun label(): String
}

/** Every inherited slot read through the C# hamper. */
fun unpackHamper(hamper: Hamper): String =
  "${hamper.label()}: ${hamper.item} + ${hamper.put("tuna")}"

/**
 * ADR-233: a backticked member with no `@CSharpName`. A C# implementation gets the same
 * synthesized name the class route gives the keyword member.
 */
interface Doorman {
  fun `in`(`object`: String): String
}

/** Kotlin calls the backticked slot. */
fun knock(doorman: Doorman): String = doorman.`in`("Oreo")

/**
 * ADR-233: a generic interface whose `suspend` member returns a Flow of its own `T`. The
 * Kotlin-backed collect export reads the awaited flow over the erased owner, as ADR-224 reads the
 * owner itself.
 */
interface Litter<T> {
  suspend fun kittens(): Flow<T>
}

/** The Kotlin-backed [Litter]. */
class Bundle<T>(private val items: List<T>) : Litter<T> {
  override suspend fun kittens(): Flow<T> = items.asFlow()
}

/** A Kotlin [Litter] of names, Oreo first. */
fun roll(): Litter<String> = Bundle(listOf("Oreo", "Mylo"))

/** The first kitten of a litter. */
suspend fun head(litter: Litter<String>): String = litter.kittens().first()
