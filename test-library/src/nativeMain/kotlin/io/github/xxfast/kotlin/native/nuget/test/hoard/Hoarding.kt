package io.github.xxfast.kotlin.native.nuget.test.hoard

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

/**
 * ADR-237: `kotlin.Any` crosses the bridge. A Kotlin `Any` reaches C# as `object?` holding its
 * runtime C# value (a boxed primitive, a `string`, an exported class's wrapper, the original
 * object of a C#-implemented interface, a value class struct, a collection), and a C# `object`
 * reaches Kotlin as the matching Kotlin value. [kind] names what Kotlin received, so a value that
 * arrived as the wrong Kotlin type shows up in the string.
 *
 * Oreo hoards toy mice under the couch. Mylo hoards anything Oreo has hoarded.
 */
class Mouse(val name: String) {
  fun squeak(): String = "$name squeaks"
}

/** A generic exported class, read through a star projection. */
class Satchel<T>(val item: T)

/** A value class over an `Int`, boxed into `Any` and unboxed back. */
value class Paw(val size: Int)

/** The enum an `Any` carries. */
enum class Fur { TUXEDO, CARAMEL }

/** A C#-implemented interface, so an `Any` can carry a C# object into Kotlin and back. */
interface Catnip {
  fun sniff(): String
}

/** The Kotlin type of [value], with the value itself, as Kotlin sees it. */
fun kind(value: Any?): String =
  when (value) {
    null -> "null"
    is Int -> "Int $value"
    is Long -> "Long $value"
    is Double -> "Double $value"
    is Boolean -> "Boolean $value"
    is String -> "String $value"
    is Mouse -> "Mouse ${value.name}"
    is Catnip -> "Catnip ${value.sniff()}"
    is Fur -> "Fur ${value.name}"
    is Paw -> "Paw ${value.size}"
    is List<*> -> "List [${value.joinToString { kind(it) }}]"
    is Map<*, *> -> "Map {${value.entries.joinToString { "${it.key}=${kind(it.value)}" }}}"
    else -> "Other ${value::class.simpleName}"
  }

/** A non-null `Any` in and out, handed back as is. */
fun stash(value: Any): Any = value

/** A nullable `Any` in and out, handed back as is. */
fun stashOrNull(value: Any?): Any? = value

/** One Kotlin-made value per kind, so C# reads each runtime type Kotlin produces. */
fun fetch(kind: String): Any? =
  when (kind) {
    "int" -> 5
    "long" -> 6_000_000_000L
    "double" -> 2.5
    "bool" -> true
    "string" -> "Oreo"
    "mouse" -> Mouse("Mylo")
    "fur" -> Fur.CARAMEL
    "paw" -> Paw(4)
    "list" -> listOf(1, "Mylo", null)
    else -> null
  }

/** A list of `Any?` in and out, each element echoed and described. */
fun pile(items: List<Any?>): List<Any?> = items + kind(items)

/** A map of `Any?` values in and out. */
fun ledger(entries: Map<String, Any?>): Map<String, Any?> = entries + ("seen" to kind(entries))

/** A star-projected generic class at a result: C# reads its `item`. */
fun rummage(): Satchel<*> = Satchel(Mouse("Oreo"))

/** The class owner: an `Any` constructor parameter, a `var`, members and a flow. */
class Hoard(first: Any?) {
  var last: Any? = first

  fun keep(value: Any): Any {
    last = value
    return value
  }

  fun all(): List<Any?> = listOf(last)

  fun haul(): Flow<Any?> = flowOf(last, "tuna")

  /** A star-projected flow at a class member result: C# reads every element. */
  fun spoils(): Flow<*> = flowOf(1, "Oreo", null, Mouse("Mylo"))

  val bag: Satchel<*> get() = Satchel(last)
}

/** A Kotlin-backed interface with `Any` members and a star-projected flow. */
interface Loot {
  fun grab(value: Any?): Any?

  fun everything(): Flow<*>
}

/** The Kotlin-backed [Loot]. */
class Magpie : Loot {
  override fun grab(value: Any?): Any? = listOf(value, kind(value))

  override fun everything(): Flow<*> = flowOf(Fur.TUXEDO, 2.5)
}

/** A Kotlin [Loot] for C# to call. */
fun thief(): Loot = Magpie()

/** A C#-implemented interface whose slots carry `Any`. */
interface Cache {
  fun hide(value: Any?): Any?

  fun favourite(): Any
}

/** Kotlin hands every kind to the C# [Cache] and describes what came back. */
fun hiding(cache: Cache, value: Any?): String = kind(cache.hide(value))

/** Kotlin reads the C# [Cache]'s favourite and hands it back unchanged. */
fun favourite(cache: Cache): Any = cache.favourite()

/**
 * A member-level type parameter on a C#-implemented generic interface. C# cannot be handed an
 * erased `R`, so the C# member is `object? Sniff(Func<T, object?> f)`, called at `R = Any?`.
 */
interface Sniffer<T> {
  val item: T

  fun <R> sniff(f: (T) -> R): R
}

/** The Kotlin-backed [Sniffer], so the erased member is called from C# too. */
class Nose<T>(override val item: T) : Sniffer<T> {
  override fun <R> sniff(f: (T) -> R): R = f(item)
}

/** A Kotlin [Sniffer] of a name. */
fun snout(item: String): Sniffer<String> = Nose(item)

/** Kotlin calls the C# sniffer at `R = Int` and at `R = String`. */
fun sniffing(sniffer: Sniffer<String>): String =
  "${sniffer.sniff { it.length } + 1} ${sniffer.sniff { it.uppercase() }}"

/** A member-level type parameter on a non-generic C#-implemented interface. */
interface Pouncer {
  fun <R> pounce(f: (String) -> R): R
}

/** Kotlin calls the C# pouncer at `R = Mouse`, a handle crossing through the erased result. */
fun pouncing(pouncer: Pouncer): String = pouncer.pounce { Mouse(it) }.squeak()

/** Every element of a star-projected flow, as Kotlin sees it. */
suspend fun sift(flow: Flow<*>): String = flow.toList().joinToString { kind(it) }
