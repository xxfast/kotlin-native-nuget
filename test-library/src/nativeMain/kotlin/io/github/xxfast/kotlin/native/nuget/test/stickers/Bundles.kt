package io.github.xxfast.kotlin.native.nuget.test.stickers

import io.github.xxfast.kotlin.native.nuget.test.cat.Box
import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.yield

/**
 * A generic value class over a collection of its own type parameter: `Bundle<T>(val items:
 * List<T>)`, and over a `Set<T>`, a `Map<String, T>`, a `List<T>?` and a `List<T?>`. C# gets one
 * `readonly record struct Bundle<T>(IReadOnlyList<T> Items)`; a closed `Bundle<Int>` crosses on the
 * `List<Int>` wire and an own `T` on the boxed element wire, and Kotlin re-wraps it, so `init` runs
 * at the crossing.
 *
 * Oreo (black, white in the middle) bundles his toys. Mylo (brown and creamy) never keeps more than
 * three, so a bigger bundle is refused.
 */

/** A bundle of toys. `init` refuses more than three. */
value class Bundle<T>(val items: List<T>) {
  init {
    require(items.size <= 3) { "Mylo cannot carry more than three" }
  }

  /** A bundle of [times] copies of [item]. */
  constructor(item: T, times: Int) : this(List(times) { item })

  /** How many toys. */
  val count: Int get() = items.size

  /** The first toy. */
  fun first(): T = items.first()

  /** The bundle with [item] added, refused by `init` past three. */
  fun plus(item: T): Bundle<T> = Bundle(items + item)

  /** How many toys, later. */
  suspend fun countLater(): Int {
    yield()
    return items.size
  }

  /** Each toy, one at a time. */
  fun each(): Flow<T> = items.asFlow()
}

/** A pile of distinct toys. */
value class Pile<T>(val items: Set<T>)

/** Toys by owner. */
value class Ledger<T>(val entries: Map<String, T>)

/** Maybe a bundle: `null` is no list at all. */
value class MaybeBundle<T>(val items: List<T>?)

/** A bundle with gaps. */
value class Gappy<T>(val items: List<T?>)

/** Oreo's bundle of the given toys. */
fun pack(items: List<Int>): Bundle<Int> = Bundle(items)

/** The sum of the bundle. */
fun total(bundle: Bundle<Int>): Int = bundle.items.sum()

/** The names, shouted. */
fun shoutNames(bundle: Bundle<String>): Bundle<String> = Bundle(bundle.items.map { it.uppercase() })

/** The cats' names. */
fun catNames(bundle: Bundle<Cat>): String = bundle.items.joinToString { it.name }

/** A bundle or none. */
fun maybePack(present: Boolean): Bundle<Int>? = if (present) Bundle(listOf(1, 2)) else null

/** The sum, or -1 for no bundle. */
fun maybeTotal(bundle: Bundle<Int>?): Int = bundle?.items?.sum() ?: -1

/** The bundles, as given. */
fun repack(bundles: List<Bundle<Int>>): List<Bundle<Int>> =
  bundles.map { Bundle(it.items.reversed()) }

/** A pile of names. */
fun pileOf(items: Set<String>): Pile<String> = Pile(items)

/** How big the pile is. */
fun pileSize(pile: Pile<String>): Int = pile.items.size

/** A ledger of counts. */
fun ledgerOf(entries: Map<String, Int>): Ledger<Int> = Ledger(entries)

/** The total of the ledger. */
fun ledgerSum(ledger: Ledger<Int>): Int = ledger.entries.values.sum()

/** A maybe bundle, holding no list when not [present]. */
fun maybeBundle(present: Boolean): MaybeBundle<Int> = MaybeBundle(if (present) listOf(1) else null)

/** The size, or -1 for no list. */
fun maybeBundleSize(bundle: MaybeBundle<Int>): Int = bundle.items?.size ?: -1

/** No bundle, a bundle of no list, or a bundle of one. */
fun maybeMaybe(state: Int): MaybeBundle<Int>? = when (state) {
  0 -> null
  1 -> MaybeBundle(null)
  else -> MaybeBundle(listOf(state))
}

/** Which of the three it was. */
fun maybeMaybeState(bundle: MaybeBundle<Int>?): String = when {
  bundle == null -> "absent"
  bundle.items == null -> "empty"
  else -> "full"
}

/** A bundle with one gap. */
fun gappy(): Gappy<Int> = Gappy(listOf(1, null, 3))

/** How many gaps. */
fun gaps(gappy: Gappy<Int>): Int = gappy.items.count { it == null }

/** The maybe bundles, as given. */
fun maybes(bundles: List<MaybeBundle<Int>?>): List<MaybeBundle<Int>?> = bundles

/** Any toys, bundled. */
fun <T> wrap(items: List<T>): Bundle<T> = Bundle(items)

/** The toys back out. */
fun <T> unwrap(bundle: Bundle<T>): List<T> = bundle.items

/** A boxed bundle. */
fun boxedBundle(): Box<Bundle<Int>> = Box(Bundle(listOf(1, 2)))

/** The sum of a boxed bundle. */
fun boxedTotal(box: Box<Bundle<Int>>): Int = box.value.items.sum()

/** The bundle, through [block]. */
fun mapBundle(bundle: Bundle<Int>, block: (Bundle<Int>) -> Bundle<Int>): Bundle<Int> = block(bundle)

/** Two bundles, one after the other. */
fun bundleStream(): Flow<Bundle<Int>> = flowOf(Bundle(listOf(1)), Bundle(listOf(2, 3)))

/** A bundle, later. */
suspend fun bundleLater(): Bundle<Int> {
  yield()
  return Bundle(listOf(4))
}

/** The sum, later. */
suspend fun totalLater(bundle: Bundle<Int>): Int {
  yield()
  return bundle.items.sum()
}

/** Holds a bundle of names, and a board of numbers. */
class Bundler(var current: Bundle<String>) {
  /** The latest numbers. */
  val board: MutableStateFlow<Bundle<Int>> = MutableStateFlow(Bundle(listOf(0)))

  /** A spare bundle, or none. */
  var spare: Bundle<Int>? = null

  /** A spare maybe bundle: none, one holding no list, or one holding a list. */
  var maybeSpare: MaybeBundle<Int>? = null

  /** The board's total. */
  fun boardTotal(): Int = board.value.items.sum()
}
