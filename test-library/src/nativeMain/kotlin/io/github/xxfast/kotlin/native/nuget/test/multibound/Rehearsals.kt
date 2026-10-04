package io.github.xxfast.kotlin.native.nuget.test.multibound

/**
 * Generic declarations whose type parameter has several upper bounds. No Kotlin type names the
 * intersection, so the generated Kotlin reads a `T` through its first bound, smart-casts it to the
 * rest and types the receiver from that value; the C# `where` clause lists every exportable bound.
 *
 * Oreo and Mylo are pets that know tricks; the one with more tricks wins.
 */
interface Pet {
  val name: String
}

/** Something that knows tricks. */
interface Trainable {
  val tricks: Int
}

/** A pet that knows tricks, ranked by them, so it is within every bound below. */
class Performer(override val name: String, override val tricks: Int) :
  Pet, Trainable, Comparable<Performer> {
  override fun compareTo(other: Performer): Int = tricks.compareTo(other.tricks)
}

/** Two wrapper bounds on the class route. */
class Arena<T>(val champion: T) where T : Pet, T : Trainable {
  val summary: String get() = "${champion.name} knows ${champion.tricks} tricks"

  fun challenge(challenger: T): T =
    if (challenger.tricks > champion.tricks) challenger else champion
}

/**
 * A builtin bound beside a wrapper bound: `Comparable<T>` is dropped from the C# `where` clause
 * (ADR-015), `Pet` is kept, and the Kotlin half still compares.
 */
class Podium<T>(val first: T) where T : Comparable<T>, T : Pet {
  fun best(other: T): T = maxOf(first, other)
}

/** Nullable bounds: a bare `T` may hold null on both halves. */
class Hamper<T>(val occupant: T) where T : Pet?, T : Trainable? {
  val label: String get() = occupant?.name ?: "empty"

  fun swap(next: T): T = next
}

/** Two wrapper bounds on the generic-function route. */
fun <T> rehearse(performer: T): T where T : Pet, T : Trainable = performer

/** A builtin bound beside a wrapper bound on the generic-function route. */
fun <T> headline(performer: T): T where T : Comparable<T>, T : Pet = performer
