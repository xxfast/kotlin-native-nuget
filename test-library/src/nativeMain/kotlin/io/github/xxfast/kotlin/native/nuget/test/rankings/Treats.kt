package io.github.xxfast.kotlin.native.nuget.test.rankings

/**
 * Generic declarations bounded by Kotlin builtins. C# has no spelling for `Comparable<T>` or
 * `Number`, so each bound is dropped from the `where` clause (`where T : notnull` is kept, the
 * bounds being non-null) and reported as INFO_DROPPED_BOUND; the declarations still bind.
 *
 * Oreo ranks his treats; Mylo just counts them.
 */
class Ranked<T : Comparable<T>>(val value: T) {
  fun outranks(other: T): Boolean = value > other
}

/** The class-bound twin of [Ranked]: `Number` is a builtin class, not an interface. */
class Tally<T : Number>(val value: T) {
  val asDouble: Double get() = value.toDouble()
}

/** The generic-function route with an interface builtin bound. */
fun <T : Comparable<T>> favourite(value: T): T = value

/** The generic-function route with a class builtin bound. */
fun <T : Number> weigh(value: T): T = value

/**
 * A generated wrapper that satisfies [favourite]'s bound. The function route crosses `T` as a
 * handle and materialises the result through a generated factory, so a C# scalar cannot be its
 * type argument; a wrapper can.
 */
class Treat(val name: String) : Comparable<Treat> {
  override fun compareTo(other: Treat): Int = name.compareTo(other.name)
}
