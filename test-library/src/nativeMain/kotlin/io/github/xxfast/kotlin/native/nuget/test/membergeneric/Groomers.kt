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

  companion object {
    /** A companion member's own `T`. */
    fun <T> first(item: T): T = item
  }
}

/** An object's member. */
object Salon {
  fun <T : Number> weigh(amount: T): Double = amount.toDouble()

  fun <T> echo(item: T): T = item
}

/** A generic class whose member declares a second type parameter of its own. */
class Basket<T>(val item: T) {
  fun <U> swap(next: U): U = next

  fun <U> describe(other: U): String = "$item&$other"

  fun <U : Number> measure(amount: U): Double = amount.toDouble()
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
  }

  data object Rest : Chore()
}
