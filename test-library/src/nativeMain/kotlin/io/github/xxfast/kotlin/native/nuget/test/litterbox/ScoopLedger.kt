package io.github.xxfast.kotlin.native.nuget.test.litterbox

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * ADR-170 · a top-level function returning a nullable scalar calls Kotlin once, on the ADR-061
 * `valueOut` route.
 *
 * Every function here is top-level and returns a nullable primitive, `Char?`, enum, scalar value
 * class, `Instant?` or `Duration?`, and takes a parameter that needs a C#-side prelude: a `List`,
 * a nullable `List`, a `Map`, a `Set`, a `ByteArray`, a Kotlin interface or a callback. On the
 * retired ADR-002 two-call route each of those spelled its handle (`catsHandle`, `onDigCtx`, ...)
 * in the call but never declared it, so the generated C# did not compile, and the Kotlin function
 * ran twice per C# call.
 *
 * Mylo is the one who digs, Oreo is the one who inspects the result, and Rex the dog is not
 * allowed anywhere near the tray: [countVisits] throws on him with the list handle live.
 */

/** The litter in the tray. The enum-return cell. */
enum class Grit { CLUMPING, CRYSTAL, PINE }

/** Scoops owed for a visitor. The Primitive-underlying value class return cell. */
value class Scoops(val count: Int)

/** Someone who uses the tray. Implemented in Kotlin by [Regular], and in C# by the tests. */
interface Visitor {
  val name: String

  fun visits(): Int
}

/** A Kotlin-side [Visitor]. */
class Regular(override val name: String, private val count: Int) : Visitor {
  override fun visits(): Int = count
}

/** `List<String>` in, `Int?` out: null for an empty tray, throws for the dog. */
fun countVisits(cats: List<String>): Int? {
  require("Rex" !in cats) { "Rex is not allowed in the litter box" }
  return if (cats.isEmpty()) null else cats.size
}

/** Nullable `List<Int>` in, `Int?` out: null when there is no list at all. */
fun sumDigs(digs: List<Int>?): Int? = digs?.sum()

/** `List<String>` plus an ADR-164 optional default, `Int?` out. */
fun countVisitsWithBonus(cats: List<String>, bonus: Int = 2): Int? =
  if (cats.isEmpty()) null else cats.size + bonus

/** `Map<String, Int>` in, `Duration?` out: total minutes in the tray, null when nobody went. */
fun timeInTray(minutesByCat: Map<String, Int>): Duration? =
  if (minutesByCat.isEmpty()) null else minutesByCat.values.sum().minutes

/** `Set<String>` in, `Boolean?` out: null for nobody, otherwise whether Mylo dug. */
fun myloDug(diggers: Set<String>): Boolean? = if (diggers.isEmpty()) null else "Mylo" in diggers

/** `List<String>` in, `Char?` out: the first digger's initial, null for an empty tray. */
fun firstDigger(cats: List<String>): Char? = cats.firstOrNull()?.firstOrNull()

/** `ByteArray` in, enum `Grit?` out: the first byte is the ordinal, null for no bytes. */
fun gritFromLabel(label: ByteArray): Grit? =
  if (label.isEmpty()) null else Grit.entries[label[0].toInt()]

/** Kotlin interface in, value class `Scoops?` out: null for a visitor who never went. */
fun scoopsFor(visitor: Visitor): Scoops? {
  val visits = visitor.visits()
  return if (visits == 0) null else Scoops(visits * 2)
}

/** `List<Int>` in, `Instant?` out: the latest cleaning, epoch seconds, null for no cleanings. */
fun lastCleaned(epochSeconds: List<Int>): Instant? =
  epochSeconds.maxOrNull()?.let { Instant.fromEpochSeconds(it.toLong()) }

/**
 * Callback in, `Int?` out. Invokes [onDig] exactly once per call: the C# side counts invocations,
 * so the retired two-call route (which would invoke it twice) is visible as a count of 2.
 */
fun digDepth(onDig: (Int) -> Int): Int? {
  val depth = onDig(3)
  return if (depth <= 0) null else depth
}

private var scoopsIssued = 0

/** No prelude at all, but a side effect: the single-call check without a callback. */
fun nextScoop(): Int? {
  scoopsIssued += 1
  return if (scoopsIssued % 2 == 0) null else scoopsIssued
}

/** How many times Kotlin actually ran [nextScoop]. */
fun scoopsIssued(): Int = scoopsIssued
