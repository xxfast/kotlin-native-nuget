package io.github.xxfast.kotlin.native.nuget.test.plushie

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Fixture for [#611](https://github.com/xxfast/kotlin-native-nuget/issues/611), designed in ADR-242:
 * an immutable, all-scalar `data class` crosses to C# as a value (`sealed class : IEquatable<T>`,
 * no `IDisposable`, no handle), while every computed member, `equals`, `hashCode`, `toString` and
 * `copy` still run Kotlin's code.
 *
 * Every seam the value crosses, once each: return, parameter, nullable return and parameter,
 * `List` out and in, `suspend` result, `Flow` element, lambda payload and result, and a property on
 * an ordinary handle class ([Shelf]). The wider components (value class, `Instant`, `Duration`,
 * `Uuid`, a nullable scalar, `Double`, `Set`, `Map`) ride on [PlushieTag]; nested qualifying
 * components on [PlushieBox].
 *
 * Below them, one negative per admission-predicate exclusion. Each must stay an ADR-008
 * `IDisposable` handle class.
 *
 * Oreo (black, white middle) guards the plushie shelf; Mylo (brown and creamy) sleeps on it.
 */

/** An exported enum component. */
enum class Mood { Happy, Sleepy }

/** The ADR's sample value: a string, a read-only list, an enum and a primitive. */
data class Plushie(
  val name: String,
  val colors: List<String>,
  val mood: Mood,
  val squeaks: Int,
) {
  init {
    require(name.isNotBlank()) { "a plushie needs a name" }
  }

  /** A getter-only computed property: Kotlin's code on every read. */
  val label: String get() = "$name (${colors.joinToString("/")})"

  /** A method with a parameter. */
  fun describe(loud: Boolean): String = if (loud) "${name.uppercase()}!" else name

  /** A throwing member: the transient instance behind the call must still be released. */
  fun explode(): String = error("stuffing everywhere")
}

/** Bun, Oreo's favourite. */
fun favouritePlushie(): Plushie = Plushie("Bun", listOf("pink", "white"), Mood.Happy, 3)

/** Parameter and return on one callable. */
fun echoPlushie(plushie: Plushie): Plushie = plushie

/** `List<Plushie>` out. */
fun plushies(): List<Plushie> = listOf(
  favouritePlushie(),
  Plushie("Milo", listOf("brown", "cream"), Mood.Sleepy, 0),
)

/** `List<Plushie>` in. */
fun totalSqueaks(plushies: List<Plushie>): Int = plushies.sumOf { it.squeaks }

/** Nullable return: `null` for an unknown name. */
fun findPlushie(name: String): Plushie? = plushies().firstOrNull { it.name == name }

/** Nullable parameter. */
fun nameOf(plushie: Plushie?): String = plushie?.name ?: "nobody"

/** A `suspend` result, with a real suspension point. */
suspend fun fetchPlushie(): Plushie {
  delay(1)
  return favouritePlushie()
}

/** A `Flow` element. */
fun plushieParade(): Flow<Plushie> = flow {
  for (plushie in plushies()) emit(plushie)
}

/** A lambda whose payload and result are both the value. */
fun restyle(stylist: (Plushie) -> Plushie): Plushie = stylist(favouritePlushie())

/** An ordinary handle class holding a value: `Shelf` itself stays `IDisposable`. */
class Shelf {
  var favourite: Plushie = favouritePlushie()

  private val shelved = mutableListOf<Plushie>()

  fun put(plushie: Plushie): Int {
    shelved += plushie
    return shelved.size
  }
}

/** A value class component. */
value class Stitches(val count: Int)

/** The wider components ADR-242 open question 3 admits. */
data class PlushieTag(
  val stitches: Stitches,
  val madeAt: Instant,
  val washEvery: Duration,
  val serial: Uuid,
  val nickname: String?,
  val weight: Double,
  val fabrics: Set<String>,
  val patches: Map<String, Int>,
)

fun mylosTag(): PlushieTag = PlushieTag(
  stitches = Stitches(42),
  madeAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
  washEvery = 72.hours,
  serial = Uuid.parse("550e8400-e29b-41d4-a716-446655440000"),
  nickname = null,
  weight = 0.25,
  fabrics = setOf("velvet", "fleece"),
  patches = mapOf("ear" to 2, "paw" to 4),
)

fun echoTag(tag: PlushieTag): PlushieTag = tag

/** Nested qualifying components: non-null, nullable and a list. */
data class PlushieBox(
  val star: Plushie,
  val spare: Plushie?,
  val stack: List<Plushie>,
)

fun oreosBox(): PlushieBox = PlushieBox(favouritePlushie(), null, plushies())

fun echoBox(box: PlushieBox): PlushieBox = box

// Negative fixtures: each breaks exactly one predicate clause and stays a handle class.

/** A `var` component. */
data class SquishyPlushie(var squish: Int)

fun squishyPlushie(): SquishyPlushie = SquishyPlushie(1)

/** A body `val` with an initialiser (a backing field). */
data class StampedPlushie(val name: String) {
  val stamp: String = "stamped-$name"
}

fun stampedPlushie(): StampedPlushie = StampedPlushie("Bun")

/** A delegated body property. */
data class LazyPlushie(val name: String) {
  val reversed: String by lazy { name.reversed() }
}

fun lazyPlushie(): LazyPlushie = LazyPlushie("Bun")

/** A body `lateinit var`. */
data class LatePlushie(val name: String) {
  lateinit var owner: String
}

fun latePlushie(): LatePlushie = LatePlushie("Bun")

/** An ordinary handle class, used as a component below. */
class Stuffing(val kind: String)

/** A handle-typed component. */
data class StuffedPlushie(val stuffing: Stuffing)

fun stuffedPlushie(): StuffedPlushie = StuffedPlushie(Stuffing("cotton"))

/** A list of handle-typed elements. */
data class StuffingPile(val stuffings: List<Stuffing>)

fun stuffingPile(): StuffingPile = StuffingPile(listOf(Stuffing("cotton"), Stuffing("beans")))

/** A mutable collection component. */
data class GrowingPlushie(val tags: MutableList<String>)

fun growingPlushie(): GrowingPlushie = GrowingPlushie(mutableListOf("new"))

/** A `ByteArray` component, whose Kotlin `equals` is identity. */
data class ChippedPlushie(val chip: ByteArray)

fun chippedPlushie(): ChippedPlushie = ChippedPlushie(byteArrayOf(1, 2, 3))

/** A sealed arm. */
sealed interface PlushieKind {
  data class Bear(val name: String) : PlushieKind
}

fun bearPlushie(): PlushieKind.Bear = PlushieKind.Bear("Bun")

// No generic data class negative: a top-level `data class PlushieBag<T>(val item: T)` does not
// compile today (its generated `Copy` returns the bare `PlushieBag`, CS0305 in nugetCompileInterop).

/** An open superclass. */
open class Toybox(val shelf: String)

data class BoxedPlushie(val name: String) : Toybox("top")

fun boxedPlushie(): BoxedPlushie = BoxedPlushie("Bun")

/** An exported interface. */
interface Cuddly {
  fun cuddle(): String
}

data class CuddlyPlushie(val name: String) : Cuddly {
  override fun cuddle(): String = "$name is cuddled"
}

fun cuddlyPlushie(): CuddlyPlushie = CuddlyPlushie("Bun")

/** A self-referencing type graph. */
data class PlushieChain(val name: String, val next: PlushieChain?)

fun plushieChain(): PlushieChain = PlushieChain("Bun", PlushieChain("Milo", null))
