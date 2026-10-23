package io.github.xxfast.kotlin.native.nuget.test.stickers

import io.github.xxfast.kotlin.native.nuget.test.cat.Box

/**
 * Two nullable underlyings.
 *
 * - [Opt] holds a `T?`. Closed at a value type it is spelled with the nullable argument in C#
 *   (`Opt<int?>`), because C# reads an unconstrained `T?` as `T` for a value type; a reference type
 *   keeps its spelling (`Opt<string>`).
 * - [TreatTin] holds a nullable list: a tin with no list is not the same as no tin.
 *
 * Oreo (black, white in the middle) keeps an empty tin. Mylo (brown and creamy) has no tin at all.
 */

/** Maybe a value. */
value class Opt<T>(val value: T?)

/** Treats in a tin, or a tin with no list of treats. */
value class TreatTin(val treats: List<Int>?)

/** An empty `Opt`. */
fun optNone(): Opt<Int> = Opt(null)

/** An `Opt` of [value]. */
fun optSome(value: Int): Opt<Int> = Opt(value)

/** The value, or `none`. */
fun optState(opt: Opt<Int>): String = opt.value?.toString() ?: "none"

/** An empty named `Opt`. */
fun optNamed(): Opt<String> = Opt(null)

/** The name, or `none`. */
fun optName(opt: Opt<String>): String = opt.value ?: "none"

/** An `Opt` of any [value]. */
fun <T> optOf(value: T?): Opt<T> = Opt(value)

/** A tin, holding no list when not [present]. */
fun tinOf(present: Boolean): TreatTin = TreatTin(if (present) listOf(1, 2) else null)

/** How many treats, or -1 for no list. */
fun tinSize(tin: TreatTin): Int = tin.treats?.size ?: -1

/** No tin, a tin with no list, or a tin of one. */
fun maybeTin(state: Int): TreatTin? = when (state) {
  0 -> null
  1 -> TreatTin(null)
  else -> TreatTin(listOf(state))
}

/** Which of the three it was. */
fun tinState(tin: TreatTin?): String = when {
  tin == null -> "absent"
  tin.treats == null -> "empty"
  else -> "full"
}

/** A boxed tin. */
fun boxedTin(present: Boolean): Box<TreatTin> = Box(TreatTin(if (present) listOf(2) else null))

/** The size of a boxed tin, or -1 for no list. */
fun boxedTinSize(box: Box<TreatTin>): Int = box.value.treats?.size ?: -1
