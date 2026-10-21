package io.github.xxfast.kotlin.native.nuget.test.membergeneric

/**
 * ADR-197: member functions that declare their own type parameter, on every owner that routes
 * one: an ordinary class and its companion, an object, a generic class, an abstract class and its
 * override, a sealed base and a sealed arm. Each `T` crosses on the boxed-handle wire a class's own
 * `T` uses (ADR-147), so a builtin `T` (`Echo(4)`, `Echo("x")`) and a generated wrapper both
 * round-trip.
 *
 * Oreo visits the groomer; Mylo refuses to be brushed.
 */
interface Furry {
  val name: String
}

/** Something that knows tricks. */
interface Tricky {
  val tricks: Int
}

/** A cat that is furry and knows tricks, so it is within every bound below. */
class Tabby(override val name: String, override val tricks: Int) : Furry, Tricky

/** An ordinary class, with a companion. */
class Groomer {
  /** Hands back whatever it was given. */
  fun <T> echo(item: T): T = item

  /** A bounded `T`: C# says `where T : IFurry`. */
  fun <T : Furry> adopt(pet: T): T = pet

  /** A `T` only the return mentions, so the Kotlin call names its type argument. */
  fun <T> nothing(): T? = null

  /** Two type parameters at once. */
  fun <T, U> pair(first: T, second: U): String = "$first+$second"

  /** A multi-bound `T`, inferred on the Kotlin side from the argument. */
  fun <T> rank(pet: T): Int where T : Furry, T : Tricky = pet.tricks

  /**
   * A builtin bound C# cannot spell (ADR-015 drops `Number` from the `where` clause), so C# can
   * pass a `uint` or a `string`. The read of the box is a checked cast, so that fails before the
   * body calls `toInt()`.
   */
  fun <T : Number> portion(amount: T): Int {
    require(amount.toInt() >= 0) { "no negative portions" }
    return amount.toInt()
  }

  /** Throws after the box crossed in, so the throw path releases it. */
  fun <T> refuse(item: T): T = throw IllegalStateException("Mylo refuses to be brushed: $item")

  /** A `Result<T>`, with its non-throwing `TryWrap` twin. */
  fun <T> wrap(item: T): Result<T> =
    if (item == "knot") Result.failure(IllegalStateException("a knot")) else Result.success(item)

  // ADR-216: the own `T` inside an exported generic class ([Basket]) and inside a `Result` payload.

  /** A returned `Basket<T>`: C# says `Basket<T> Basket<T>(T item)`. */
  fun <T> basket(item: T): Basket<T> = Basket(item)

  /** A passed `Basket<T>`, borrowed: Oreo comes back out of the basket he went in. */
  fun <T> unpack(basket: Basket<T>): T = basket.item

  /** A bounded `T` witnessed only inside the basket: C# says `where T : IFurry`. */
  fun <T : Furry> brushBasket(basket: Basket<T>): T = basket.item

  /** A nullable basket, in and out. Mylo hides in no basket at all. */
  fun <T> maybeBasket(basket: Basket<T>?): Basket<T>? = basket

  /** A basket in a basket, unpacked one level. */
  fun <T> nestBasket(basket: Basket<Basket<T>>): Basket<T> = basket.item

  /** A basket in a basket, packed. */
  fun <T> doubleBasket(item: T): Basket<Basket<T>> = Basket(Basket(item))

  /** A `List<Basket<T>>` return, one basket per item. */
  fun <T> baskets(items: List<T>): List<Basket<T>> = items.map { Basket(it) }

  /** A `List<Basket<T>>` parameter, reversed so the order proves Kotlin rebuilt it. */
  fun <T> unpackAll(baskets: List<Basket<T>>): List<T> = baskets.map { it.item }.reversed()

  /** A generic sealed return: a ready pet is fluffed, otherwise waitlisted. */
  fun <T> pickUp(pet: T, ready: Boolean): Pickup<T> =
    if (ready) Pickup.Fluffed(pet) else Pickup.Waitlisted("not yet: $pet")

  /** `Result<List<T>>`, with its `TryGather` twin. */
  fun <T> gather(items: List<T>): Result<List<T>> = Result.success(items)

  /** `Result<List<T>>` that always fails, so `TrySpill` returns `false`. */
  fun <T> spill(items: List<T>): Result<List<T>> =
    Result.failure(IllegalStateException("Mylo spilled ${items.size}"))

  /** `Result<List<T?>>`: one empty seat added at the end of the queue. */
  fun <T> queue(items: List<T?>): Result<List<T?>> = Result.success(items + null)

  /** `Result<Set<T>>`. */
  fun <T> herd(items: Set<T>): Result<Set<T>> = Result.success(items)

  /** `Result<Map<K, V>>` with a non-null key. */
  fun <K : Any, V> ledger(map: Map<K, V>): Result<Map<K, V>> = Result.success(map)

  /** `Result<List<List<T>>>`: each litter reversed. */
  fun <T> litters(groups: List<List<T>>): Result<List<List<T>>> =
    Result.success(groups.map { it.reversed() })

  /** `Result<Basket<T>>`, failing on a knot like [wrap]. */
  fun <T> stash(item: T): Result<Basket<T>> =
    if (item == "knot") {
      Result.failure(IllegalStateException("a knot in the basket"))
    } else {
      Result.success(Basket(item))
    }

  companion object {
    /** A companion member's own `T`. */
    fun <T> first(item: T): T = item

    /** ADR-216: a companion member returning `Basket<T>`. */
    fun <T> firstBasket(item: T): Basket<T> = Basket(item)
  }
}

/** An object's member. */
object Salon {
  fun <T : Number> weigh(amount: T): Double = amount.toDouble()

  fun <T> echo(item: T): T = item

  /** ADR-216: an object member returning `Basket<T>`. */
  fun <T> exhibit(item: T): Basket<T> = Basket(item)
}

/** A generic class whose member declares a second type parameter of its own. */
class Basket<T>(val item: T) {
  fun <U> swap(next: U): U = next

  fun <U> describe(other: U): String = "$item&$other"

  fun <U : Number> measure(amount: U): Double = amount.toDouble()

  /** ADR-216: the member's own `U` inside a `Basket<U>`, beside the class's `T`. */
  fun <U> regift(next: U): Basket<U> = Basket(next)

  /** ADR-216 amendment: the class `T` inside a basket in a basket (pre-existing on main). */
  fun rebasket(): Basket<Basket<T>> = Basket(this)
}

/** An abstract class whose generic member is implemented by a subclass. */
abstract class Brush {
  abstract fun <T> stroke(item: T): T
}

/** The implementation behind [Brush.stroke]. */
class SoftBrush : Brush() {
  override fun <S> stroke(item: S): S = item
}

/** A sealed base with an open generic member, and an arm with one of its own. */
sealed class Chore {
  open fun <T> carry(item: T): T = item

  class Fetch(val toy: String) : Chore() {
    fun <T> pick(item: T): T = item

    // Renames the base's `T`; still the C# override of `Chore.Carry<T>`.
    override fun <U> carry(item: U): U = item

    /** A dropped builtin bound on an arm: `Comparable` is checked at the read. */
    fun <T : Comparable<T>> best(first: T, second: T): T = maxOf(first, second)

    /** ADR-216: a sealed arm member returning `Basket<T>`. */
    fun <T> stow(item: T): Basket<T> = Basket(item)
  }

  data object Rest : Chore()
}

/**
 * ADR-212: a member's own `T` as the element of a `List`, `Set` or `Map`, in and out. Each element
 * crosses on the same boxed-handle wire a bare `T` uses, so builtins and wrappers both round-trip.
 *
 * Oreo and Mylo get dealt in reverse, so the order proves the list really went through Kotlin.
 */
class Deck {
  /** Reversed, so C# can tell the list was rebuilt on the Kotlin side. */
  fun <T> shuffle(list: List<T>): List<T> = list.reversed()

  /** A nullable element: one extra empty seat at the end. */
  fun <T> pad(list: List<T?>): List<T?> = list + null

  /** A bounded element: C# says `where T : IFurry`. */
  fun <T : Furry> groom(pets: List<T>): List<T> = pets

  /** A set of `T`, in and out. */
  fun <T> dedupe(items: Set<T>): Set<T> = items

  /** A non-null key `K`, so it binds. */
  fun <K : Any, V> tally(map: Map<K, V>): Int = map.size

  /** A map of `K` to `V` on the return side. */
  fun <K : Any, V> keep(map: Map<K, V>): Map<K, V> = map

  /** An unconstrained `K` may be null, so this stays a named `NULLABLE_MAP_KEY` refusal. */
  fun <K, V> loose(map: Map<K, V>): Int = map.size
}

/**
 * ADR-216: a generic sealed hierarchy a member's own `T` returns. Oreo is fluffed and ready;
 * Mylo is waitlisted.
 */
sealed class Pickup<out T> {
  /** Forwards `T`. */
  data class Fluffed<T>(val pet: T) : Pickup<T>()

  /** Fixes the variant `T` to `Nothing`: a phantom `Pickup.Waitlisted<T>` in C#. */
  data class Waitlisted(val note: String) : Pickup<Nothing>()
}
