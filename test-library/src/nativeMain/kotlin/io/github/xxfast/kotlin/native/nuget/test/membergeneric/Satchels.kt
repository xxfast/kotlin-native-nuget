package io.github.xxfast.kotlin.native.nuget.test.membergeneric

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-216 (second amendment): an open exported generic instantiation nested inside another one
 * (`Basket<Basket<T>>`, `List<Basket<T>>`, `Basket<Pickup<T>>`, `Basket<Cabinet<T>>`) reaches C#
 * through a position other than a member's own result, before the consumer ever held the inner
 * instantiation. Reading the inner value needs a factory for it, and every generated C# body that
 * brings such a value across has to fill that factory on entry.
 *
 * Every position is crossed once:
 * - [Satchel]: a generic class's own `T` read through getters ([Satchel.nested], three levels in
 *   [Satchel.deep], a list element in [Satchel.baskets], a map value in [Satchel.keyed], a nullable
 *   in [Satchel.maybe], a generic sealed handle in [Satchel.picked], a generic abstract class in
 *   [Satchel.cabinet]) and a callback argument ([Satchel.visit]).
 * - [Porter]: a member's own `T` as a callback argument ([Porter.visit]), a `Flow` element
 *   ([Porter.stream]), a `suspend` result ([Porter.fetch]), a `Result` payload ([Porter.nest]), a
 *   generic abstract class inside a basket ([Porter.wrapCabinet]) and a `T` only the result
 *   mentions ([Porter.conjure]).
 * - [later]: a returned lambda's result, from a top-level function (the position a returned lambda
 *   binds).
 *
 * The toys ([Feather], [Yarn], [Pebble], [Acorn], [Bobbin], [Thimble]) are type arguments no other
 * fixture puts in a [Basket], so no other C# test fills their factories first.
 *
 * Oreo (black with the white middle) packs the satchel; Mylo (brown and creamy) unpacks it again.
 */
class Satchel<T>(val item: T) {
  /** A basket in a basket. */
  val nested: Basket<Basket<T>> = Basket(Basket(item))

  /** Three levels deep. */
  val deep: Basket<Basket<Basket<T>>> = Basket(Basket(Basket(item)))

  /** A list element. */
  val baskets: List<Basket<T>> = listOf(Basket(item), Basket(item))

  /** A map value. */
  val keyed: Map<String, Basket<T>> = mapOf("Oreo" to Basket(item))

  /** A nullable basket in a basket. */
  val maybe: Basket<Basket<T>>? = Basket(Basket(item))

  /** A generic sealed type in a basket: Oreo is fluffed and ready. */
  val picked: Basket<Pickup<T>> = Basket(Pickup.Fluffed(item))

  /** A generic abstract class in a basket. */
  val cabinet: Basket<Cabinet<T>> = Basket(Dresser(item))

  /** The class's `T` nested in a callback argument. */
  fun visit(block: (Basket<Basket<T>>) -> Unit) = block(nested)
}

/** A returned lambda's result, from a top-level function (the position a returned lambda binds). */
fun <T> later(item: T): () -> Basket<Basket<T>> = { Basket(Basket(item)) }

/** A generic abstract class, so a nested slot has to construct its backing. */
abstract class Cabinet<T>(val item: T) {
  abstract val label: String
}

/** The concrete [Cabinet] a consumer holds. */
class Dresser<T>(item: T) : Cabinet<T>(item) {
  override val label: String = "dresser"
}

/** A non-generic class whose members declare their own `T` and nest it at every other position. */
class Porter {
  /** A member's own `T` nested in a callback argument. */
  fun <T> visit(item: T, block: (Basket<Basket<T>>) -> Unit) = block(Basket(Basket(item)))

  /** A `Flow` element. */
  fun <T> stream(item: T): Flow<Basket<Basket<T>>> = flowOf(Basket(Basket(item)))

  /** A `suspend` result. */
  suspend fun <T> fetch(item: T): Basket<Basket<T>> {
    delay(1.milliseconds)
    return Basket(Basket(item))
  }

  /** A `Result` payload, with its `TryNest` twin. */
  fun <T> nest(item: T): Result<Basket<Basket<T>>> = Result.success(Basket(Basket(item)))

  /** A generic abstract class in a basket. */
  fun <T> wrapCabinet(cabinet: Cabinet<T>): Basket<Cabinet<T>> = Basket(cabinet)

  /**
   * A `T` only the result mentions, so C# names it at the call site. The inner item is conjured
   * with an unchecked cast, so it is null whatever `T` is.
   */
  @Suppress("UNCHECKED_CAST")
  fun <T> conjure(): Basket<Basket<T>> = Basket(Basket(null as T))
}

/** Oreo's favourite toy. */
class Feather(val colour: String)

/** Mylo chases it under the sofa. */
class Yarn(val colour: String)

/** Oreo bats it across the floor. */
class Pebble(val weight: Int)

/** Mylo hides it in his bed. */
class Acorn(val size: Int)

/** Oreo unravels it. */
class Bobbin(val length: Int)

/** Mylo knocks it off the table. */
class Thimble(val owner: String)

