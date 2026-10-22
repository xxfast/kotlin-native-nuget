package io.github.xxfast.kotlin.native.nuget.test.stickers

import io.github.xxfast.kotlin.native.nuget.test.cat.Box
import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.yield

/**
 * ADR-228: a generic value class at every erased position. Kotlin boxes [Sticker] there as one
 * runtime class whatever its type argument, so one box/unbox pair per generic value class serves
 * every instantiation. A `Box<Sticker<Int>>`, a lambda payload or result, a `Flow`/`StateFlow`
 * element and an awaited `suspend` result each cross as that box, in both directions.
 *
 * Several functions below use a type argument (`Long`, `Short`, `Double`, `Float`, `Boolean`) that
 * no other fixture reads as a [Sticker], so the C# side meets that closed struct type first through
 * a Kotlin-made value and the per-instantiation registration has to come from the call site.
 *
 * Oreo (black, white in the middle) trades stickers. Mylo (brown and creamy) trades them back.
 */

/**
 * ADR-228: a non-generic value class whose underlying is itself nullable. `Nick(null)` and a
 * `null` `Nick?` are different values at an erased position: a present box over a null name, or
 * no box at all. Mylo has no nickname yet.
 */
value class Nick(val name: String?)

/** A closed instantiation inside a generic class. */
fun boxed(): Box<Sticker<Int>> = Box(Sticker(1))

/** A closed instantiation inside a generic class that no C# code constructs first. */
fun boxedLong(): Box<Sticker<Long>> = Box(Sticker(7L))

/** The C#-built half: the struct crosses in boxed, and Kotlin reads the underlying. */
fun peelBox(box: Box<Sticker<Int>>): Int = box.value.value

/** A present `Sticker(null)` at an erased position. */
fun boxedBlank(): Box<Sticker<String?>?> = Box(Sticker(null))

/** No sticker at all at an erased position. */
fun boxedNone(): Box<Sticker<String?>?> = Box(null)

/** Tells a `null` from a `Sticker(null)` on the way in. */
fun blankness(box: Box<Sticker<String?>?>): String =
  box.value.let { if (it == null) "no sticker" else it.value ?: "blank sticker" }

/** A function's own `T` inside the struct inside a generic class, out. */
fun <T> boxedOf(value: T): Box<Sticker<T>> = Box(Sticker(value))

/** A function's own `T` inside the struct inside a generic class, in. */
fun <T> unboxOf(box: Box<Sticker<T>>): T = box.value.value

/** A closed object-handle instantiation inside a generic class. */
fun boxedCat(name: String): Box<Sticker<Cat>> = Box(Sticker(Cat(name)))

/** A phantom instantiation inside a generic class. */
fun boxedDecal(): Box<Decal<Cat>> = Box(Decal(9L))

/** A phantom instantiation inside a generic class, in. */
fun decalSerial(box: Box<Decal<Cat>>): Long = box.value.serial

/** The nullable-underlying value class inside a generic class. */
fun nickBox(name: String?): Box<Nick> = Box(Nick(name))

/** `Nick(null)` or no `Nick` at all inside a generic class. */
fun nickBoxOrNone(present: Boolean): Box<Nick?> = Box(if (present) Nick(null) else null)

/** Tells a `null` from a `Nick(null)` on the way in. */
fun nameOn(box: Box<Nick?>): String =
  box.value.let { if (it == null) "no nick" else it.name ?: "nameless" }

/**
 * The lambda, `Flow`, `StateFlow` and `suspend` halves. Members on a class so each route is the
 * ordinary member route and only the erased value class is new.
 */
class StickerCourier(val desk: String) {

  /** Payload and result, two closed instantiations. */
  val onSticker: (Sticker<String>) -> Sticker<Int> = { Sticker(it.value.length) }

  /** A result whose closed type C# has not constructed first. */
  val nextShort: () -> Sticker<Short> = { Sticker(desk.length.toShort()) }

  /** A nullable payload over a nullable underlying: `null` and `Sticker(null)` both arrive. */
  val onBlank: (Sticker<String?>?) -> String =
    { if (it == null) "no sticker" else it.value ?: "blank sticker" }

  /** The nullable-underlying value class as a nullable payload. */
  val onNick: (Nick?) -> String = { if (it == null) "no nick" else it.name ?: "nameless" }

  /** The nullable-underlying value class as a result, present with a null name. */
  val nextNick: () -> Nick = { Nick(null) }

  /** The `suspend` lambda arm. */
  val refileLater: suspend (Sticker<Int>) -> Sticker<Int> = { Sticker(it.value + 1) }

  /** A C# lambda Kotlin calls: the payload crosses out boxed and the result crosses back in. */
  fun relay(sticker: Sticker<Int>, through: (Sticker<Int>) -> Sticker<Int>): Int =
    through(sticker).value

  /** A C# lambda over the nullable-underlying value class. */
  fun relayNick(through: (Nick) -> Nick?): String =
    through(Nick(null)).let { if (it == null) "no nick" else it.name ?: "nameless" }

  /** A `Flow` element over an object-handle instantiation. */
  fun stream(): Flow<Sticker<Cat>> = flowOf(Sticker(Cat("Oreo")), Sticker(Cat("Mylo")))

  /** A `Flow` element whose closed type C# has not constructed first. */
  fun doubles(): Flow<Sticker<Double>> = flowOf(Sticker(1.5), Sticker(2.5))

  /** A `Flow` element over the nullable-underlying value class: present, `Nick(null)`, `null`. */
  fun nicks(): Flow<Nick?> = flowOf(Nick("Oreo"), Nick(null), null)

  /** A `Flow` element over a member's own `T` inside the struct. */
  fun <T> streamOf(value: T): Flow<Sticker<T>> = flowOf(Sticker(value), Sticker(value))

  /** A `StateFlow` element whose closed type C# has not constructed first. */
  val latest: StateFlow<Sticker<Float>> = MutableStateFlow(Sticker(0.5f))

  /** A `MutableStateFlow` element, written from C#. Re-wrapping `-1` runs [Sticker]'s `init`. */
  val board: MutableStateFlow<Sticker<Int>> = MutableStateFlow(Sticker(2))

  /** A `Flow` parameter: each C# element crosses in boxed. */
  suspend fun total(stickers: Flow<Sticker<Int>>): Int {
    var sum = 0
    stickers.collect { sum += it.value }
    return sum
  }

  /** An awaited result whose closed type C# has not constructed first. */
  suspend fun later(): Sticker<Boolean> {
    yield()
    return Sticker(true)
  }

  /** An awaited parameter and result. */
  suspend fun bumpLater(sticker: Sticker<Int>): Sticker<Int> {
    yield()
    return Sticker(sticker.value + 1)
  }

  /** An awaited result over the nullable-underlying value class: `Nick(null)` or `null`. */
  suspend fun nickLater(present: Boolean): Nick? {
    yield()
    return if (present) Nick(null) else null
  }

  /** An awaited result over a member's own `T` inside the struct. */
  suspend fun <T> laterOf(value: T): Sticker<T> {
    yield()
    return Sticker(value)
  }
}
