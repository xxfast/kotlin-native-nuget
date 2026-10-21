package io.github.xxfast.kotlin.native.nuget.test.multibound

/**
 * ADR-198: an invariant self-referencing bound (`T : Node<T>`) beside a wrapper bound. No closed
 * type argument is within `Node<T>` (`Node<Any?>` is not a `Node<Node<Any?>>`), so an export that
 * takes a `T` runs inside a local generic function that re-declares both bounds; the C# `where`
 * clause spells `INode<T>` with its argument.
 *
 * Oreo and Mylo string their collar beads into one chain.
 */
interface Node<T> {
  fun link(other: T): T
}

/** A bead that links to another bead, so it is within both bounds below. */
class Bead(override val name: String) : Node<Bead>, Pet {
  override fun link(other: Bead): Bead = Bead("$name-${other.name}")
}

/** The class route: `attach` takes a `T` and calls the bound's own `link` on the Kotlin side. */
class Chain<T>(val head: T) where T : Node<T>, T : Pet {
  val label: String get() = "chain of ${head.name}"

  fun attach(other: T): T = head.link(other)

  /** ADR-217: the class's own trampolined `T` inside a `List`, each bead linked to the head. */
  fun attachAll(others: List<T>): List<T> = others.map { head.link(it) }
}

/** The function route under the same bounds. */
fun <T> lead(value: T): T where T : Node<T>, T : Pet = value

/**
 * ADR-217: a top-level `T : Node<T>` witnessed only inside a `List`, the beads linked left to
 * right by the bound's own `link`.
 */
fun <T : Node<T>> braid(beads: List<T>): T = beads.reduce { braided, bead -> braided.link(bead) }
