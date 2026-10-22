package io.github.xxfast.kotlin.native.nuget.test.stickers

/**
 * ADR-226: a function's own `T` inside a generic value class under `?` and inside a `List`, `Set`
 * or `Map`, and every value class whose underlying can itself be null.
 *
 * When the outer is nullable and the underlying admits null (`Sticker<T>?` for an unconstrained
 * `T`, the closed `Sticker<String?>?`, [Nick]`?`, [TreatCount]`?`), `null` and `Sticker(null)` are
 * different values, so the slot is a box handle: no handle is `null`, a handle is the boxed value
 * class. A `T : Any` underlying has no null of its own and keeps the null pointer in-band. Inside a
 * collection a non-null element projects to its underlying, and a nullable element over a
 * nullable underlying is the same box handle.
 *
 * Oreo (black, white in the middle) leaves gaps in the sticker album. Mylo (brown and creamy) fills
 * them with blank stickers, which are not the same thing as no sticker.
 */

/** A value class over a nullable primitive: `TreatCount(null)` is "Mylo has not been counted". */
value class TreatCount(val count: Int?)

/** `Sticker<T>?` result, own unconstrained `T`: present over a null value, or absent. */
fun <T> maybeStick(value: T, present: Boolean): Sticker<T>? =
  if (present) Sticker(value) else null

/** `Sticker<T>?` parameter, own unconstrained `T`: tells `null` from `Sticker(null)`. */
fun <T> isAbsent(sticker: Sticker<T>?): Boolean = sticker == null

/** `Sticker<T>?` in and out, own unconstrained `T`: whatever arrives goes straight back. */
fun <T> passGap(sticker: Sticker<T>?): Sticker<T>? = sticker

/** `Sticker<T>?` with `T : Any`: the underlying has no null, so the null pointer is in-band. */
fun <T : Any> firmMaybe(value: T?): Sticker<T>? = value?.let { Sticker(it) }

/** `Sticker<T>?` parameter with `T : Any`. */
fun <T : Any> firmOr(sticker: Sticker<T>?, fallback: T): T = sticker?.value ?: fallback

/** `List<Sticker<T>>` result: one element per value. */
fun <T> stickAll(values: List<T>): List<Sticker<T>> = values.map { Sticker(it) }

/** `Set<Sticker<T>>` parameter: each element re-wraps in Kotlin, so `Sticker(-1)` runs `init`. */
fun <T> peelAll(stickers: Set<Sticker<T>>): List<T> = stickers.map { it.value }

/** `Map<String, Sticker<T>>` in and out. */
fun <T> byLabel(stickers: Map<String, Sticker<T>>): Map<String, Sticker<T>> = stickers

/** `Map<Sticker<T>, String>`: the struct as a key. */
fun <T> keyedBy(stickers: Map<Sticker<T>, String>): List<String> = stickers.values.sorted()

/** `MutableList<Sticker<T>>` result. */
fun <T> restack(values: List<T>): MutableList<Sticker<T>> =
  values.map { Sticker(it) }.toMutableList()

/** `List<List<Sticker<T>>>`: nested, per ADR-212's recursion. */
fun <T> stackRows(rows: List<List<T>>): List<List<Sticker<T>>> =
  rows.map { row -> row.map { Sticker(it) } }

/** `List<Sticker<T>?>` in and out: `null` and `Sticker(null)` are different elements. */
fun <T> gaps(stickers: List<Sticker<T>?>): List<Sticker<T>?> = stickers

/** `List<Sticker<T>?>` in: Kotlin counts the gaps and the blanks separately. */
fun <T> countGaps(stickers: List<Sticker<T>?>): String =
  "${stickers.count { it == null }} gaps, " +
    "${stickers.count { it != null && it.value == null }} blanks"

/** `Map<String, Sticker<T>?>` values: a gap keyed by page. */
fun <T> gapPages(pages: Map<String, Sticker<T>?>): Map<String, Sticker<T>?> = pages

/** Closed `Sticker<String?>` at a return, with a null underlying. */
fun blankLabel(): Sticker<String?> = Sticker(null)

/** Closed `Sticker<String?>` at a parameter. */
fun labelOr(sticker: Sticker<String?>): String = sticker.value ?: "blank"

/** Closed `Sticker<String?>?` at a return: `Sticker(null)` or `null`. */
fun maybeLabel(present: Boolean): Sticker<String?>? = if (present) Sticker(null) else null

/** Closed `Sticker<String?>?` at a parameter. */
fun labelState(sticker: Sticker<String?>?): String =
  if (sticker == null) "no sticker" else sticker.value ?: "blank sticker"

/** Closed `List<Sticker<String?>?>` at a return: a gap, a blank and Oreo's. */
fun albumPage(): List<Sticker<String?>?> = listOf(null, Sticker(null), Sticker("Oreo"))

/** `Nick` at a return, with a null name. */
fun blankNick(): Nick = Nick(null)

/** `Nick` at a parameter. */
fun nickOrAnon(nick: Nick): String = nick.name ?: "anon"

/** `Nick?` at a return: `Nick(null)` or `null`. */
fun maybeNick(present: Boolean): Nick? = if (present) Nick(null) else null

/** `Nick?` at a parameter. */
fun nickState(nick: Nick?): String = if (nick == null) "no nick" else nick.name ?: "nameless"

/** `List<Nick?>` at a return: shipped before ADR-226 and lossy, `Nick(null)` came back `null`. */
fun nicknames(): List<Nick?> = listOf(null, Nick(null), Nick("Oreo"))

/** `List<Nick?>` at a parameter: Kotlin tells the gaps from the nameless. */
fun countNicks(nicks: List<Nick?>): String =
  "${nicks.count { it == null }} missing, " +
    "${nicks.count { it != null && it.name == null }} nameless"

/** `TreatCount` at a return, uncounted. */
fun uncounted(): TreatCount = TreatCount(null)

/** `TreatCount` at a parameter. */
fun treatsOr(treats: TreatCount): Int = treats.count ?: -1

/** `TreatCount?` at a return: `TreatCount(null)` or `null`. */
fun maybeTreats(present: Boolean): TreatCount? = if (present) TreatCount(null) else null

/** `TreatCount?` at a parameter. */
fun treatState(treats: TreatCount?): String =
  if (treats == null) "no count" else treats.count?.toString() ?: "uncounted"

/** A star-projected parameter: `string Starred<TSticker>(Sticker<TSticker> sticker)`. */
fun starred(sticker: Sticker<*>): String = "star ${sticker.value}"

/** ADR-132 extension receiver over a nullable-underlying `V?`. */
fun Nick?.callName(): String = if (this == null) "no nick" else name ?: "nameless"

/** ADR-132 extension receiver over `Sticker<T>?`, own unconstrained `T`. */
fun <T> Sticker<T>?.presence(): String =
  if (this == null) "gap" else if (value == null) "blank" else "stuck"

/**
 * Oreo's gap album: the box-handle rule at a constructor parameter, a property getter and setter,
 * and a member's own `T` under `?` and inside a collection.
 */
class GapAlbum(var spare: Nick?, var cover: Sticker<String?>?) {
  /** A `var` over `TreatCount?`. */
  var treats: TreatCount? = TreatCount(null)

  /** A `val` over `List<Nick?>`. */
  val roll: List<Nick?> get() = listOf(spare, null)

  /** A member's own `T` under `?`, in and out. */
  fun <T> swapGap(sticker: Sticker<T>?): Sticker<T>? = sticker

  /** A member's own `T` inside a collection. */
  fun <T> fill(values: List<T>): List<Sticker<T>> = values.map { Sticker(it) }

  /** A member's star-projected parameter. */
  fun peek(sticker: Sticker<*>): String = "peek ${sticker.value}"
}
