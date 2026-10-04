package io.github.xxfast.kotlin.native.nuget.test.cubby

/**
 * Fixture for the backing wrapper on a GENERIC abstract class (ADR-009, ADR-196): a handle that
 * materialises as `Trove<string>` cannot construct `new Trove<string>(...)` (CS0144), and a wrapper
 * cannot nest in the generic class either (CS7042), so it sits on the non-generic `Trove` holder.
 *
 * - [Trove], a generic abstract class with an abstract fun returning `T`, an abstract val, an open
 *   member and a constructor val of type `T`. [OreoTrove] fixes `T` to `String` (a conversion type)
 *   and [MyloTrove] to `Int` (a primitive).
 * - Returned at a closed type from a top-level fun ([stock], [rations]), the one position a generic
 *   class binds today: a property, a list element, a nullable return and a parameter typed
 *   `Trove<String>` are each a named skip whose reason is the generic type, not the abstract class,
 *   so none is declared here.
 * - [Coffer], a concrete generic subclass, returned as the base ([coffer]).
 * - [Alcove], an abstract class below the generic abstract base, returned as itself ([Hutch.alcove]).
 * - [Reckoner], a two-parameter generic abstract class, returned closed ([reckoner]).
 * - [peek], which takes the returned [Alcove] back as a parameter.
 *
 * Oreo hides his treats in every cubby in the house. Mylo counts his kibble twice.
 */
abstract class Trove<T>(val first: T) {
  /** The treat on top, overridden by every concrete subclass. */
  abstract fun pick(): T

  /** Who guards it, overridden by every concrete subclass. */
  abstract val keeper: String

  /** An open member [OreoTrove] overrides and [MyloTrove] inherits. */
  open fun describe(): String = "$keeper keeps $first"
}

/** Oreo's stash of fish treats. */
class OreoTrove(first: String) : Trove<String>(first) {
  override fun pick(): String = "$first flake"

  override val keeper: String = "Oreo"

  override fun describe(): String = "Oreo guards the $first"
}

/** Mylo's kibble, counted twice. */
class MyloTrove(first: Int) : Trove<Int>(first) {
  override fun pick(): Int = first * 2

  override val keeper: String = "Mylo"
}

/** A concrete generic subclass; whoever holds it is written on the lid. */
class Coffer<T>(first: T) : Trove<T>(first) {
  override fun pick(): T = first

  override val keeper: String = "coffer"
}

/** An abstract class below the generic abstract base; it leaves [pick] and [keeper] open. */
abstract class Alcove : Trove<String>("catnip") {
  abstract fun depth(): Int
}

/** The alcove behind the sofa, where Oreo keeps his catnip. */
class OreoAlcove : Alcove() {
  override fun pick(): String = "catnip mouse"

  override val keeper: String = "Oreo"

  override fun depth(): Int = 2
}

/** A two-parameter generic abstract class: counts how many of [K] there are as a [V]. */
abstract class Reckoner<K, V>(val first: K) {
  abstract fun count(key: K): V
}

/** Mylo's tally of meals. */
class MyloReckoner : Reckoner<String, Int>("breakfast") {
  override fun count(key: String): Int = key.length
}

/** Oreo's fish treats, typed as the generic base at a closed reference type. */
fun stock(): Trove<String> = OreoTrove("tuna")

/** Mylo's kibble, typed as the generic base at a closed primitive type. */
fun rations(): Trove<Int> = MyloTrove(3)

/** A concrete generic subclass, typed as the base. */
fun coffer(): Trove<String> = Coffer("ribbon")

/** A two-parameter generic abstract class, returned closed. */
fun reckoner(): Reckoner<String, Int> = MyloReckoner()

/** Takes a returned abstract class back into Kotlin, which dispatches to the subclass behind it. */
fun peek(alcove: Alcove): String = "${alcove.keeper}: ${alcove.pick()} at ${alcove.depth()}"

/** The hutch by the window; hands the abstract class below the generic base back as itself. */
class Hutch {
  fun alcove(): Alcove = OreoAlcove()
}
