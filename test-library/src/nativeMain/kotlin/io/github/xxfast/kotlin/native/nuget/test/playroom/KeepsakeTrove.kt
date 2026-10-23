package io.github.xxfast.kotlin.native.nuget.test.playroom

import io.github.xxfast.kotlin.native.nuget.test.cat.Box
import io.github.xxfast.kotlin.native.nuget.test.membergeneric.Furry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-224: a generic interface reference binds at every position, not only at the ADR-222 input
 * positions in `Keepsakes.kt`. [Keepsake] comes back to C# as `IKeepsake<T>` from a return, a
 * getter, a class, object, companion and top-level property, a nullable, a suspend result, a
 * `Flow`/`StateFlow` element, a collection component, a [Box] argument, a lambda payload and
 * result, a nested `Keepsake<Keepsake<String>>`, and an open `Keepsake<T>` in a generic function or
 * a generic class.
 *
 * A C# implementation [Trove] stores comes back as the same C# object; a Kotlin [Locket] comes back
 * as the generic backing wrapper `Keepsake<T>` the caller disposes.
 *
 * Oreo hides his treasures in the trove; Mylo keeps knocking them off the mantel.
 */
class Trove {
  /** A class property with a getter and a setter. */
  var kept: Keepsake<String> = Locket("Oreo")

  /** A nullable class property. */
  var spare: Keepsake<String>? = null

  /** A member return. */
  fun keep(): Keepsake<String> = kept

  /** A nullable member return. */
  fun keepOrNull(): Keepsake<String>? = spare

  /** A collection component at an output. */
  fun all(): List<Keepsake<String>> = listOfNotNull(kept, spare)

  /** A map value component at an output. */
  fun byName(): Map<String, Keepsake<String>> = mapOf("kept" to kept)

  /** A suspend result, with a real suspension point. */
  suspend fun later(): Keepsake<String> {
    delay(10.milliseconds)
    return kept
  }

  /** A `Flow` element. */
  fun updates(): Flow<Keepsake<String>> = flowOf(kept)

  /** A `StateFlow` element, read through `.Value`. */
  fun current(): StateFlow<Keepsake<String>> = MutableStateFlow(kept)

  /** A collection component at an input, stored and read back. */
  fun stockAll(keepsakes: List<Keepsake<String>>): Int {
    kept = keepsakes.first()
    spare = keepsakes.getOrNull(1)
    return keepsakes.size
  }

  companion object {
    /** A companion property. */
    var showcase: Keepsake<String> = Locket("sunbeam")
  }
}

/** An object property. */
object Mantelpiece {
  var ornament: Keepsake<String> = Locket("yarn")
}

/** A top-level property over a closed `Long` instantiation no other row uses. */
var lastKeepsake: Keepsake<Long> = Locket(41L)

/** An open return: `IKeepsake<T>` read through the open slot. */
fun <T> locketOf(item: T): Keepsake<T> = Locket(item)

/** An open collection component at an input and an output. */
fun <T> stash(items: List<Keepsake<T>>): List<Keepsake<T>> = items.reversed()

/** A closed [Box] argument at an input and an output. */
fun boxed(box: Box<Keepsake<String>>): Box<Keepsake<String>> = box

/** An open [Box] argument at an input and an output. */
fun <T> boxedOpen(box: Box<Keepsake<T>>): Box<Keepsake<T>> = box

/** A nested generic interface at an output. */
fun nested(): Keepsake<Keepsake<String>> = Locket(Locket("Mylo"))

/** A nested generic interface at an input, read on the Kotlin side. */
fun unwrap(outer: Keepsake<Keepsake<String>>): String = outer.item.item

/** A lambda payload and a lambda result, then a return. */
fun visit(block: (Keepsake<String>) -> Keepsake<String>): Keepsake<String> = block(Locket("Oreo"))

/** A nullable lambda result. */
fun visitOrNull(block: () -> Keepsake<String>?): Keepsake<String>? = block()

/** A suspend top-level open result. */
suspend fun <T> locketLater(item: T): Keepsake<T> {
  delay(10.milliseconds)
  return Locket(item)
}

/** A generic class whose member returns `Keepsake<T>` over the class's own `T`. */
class Mantel<T>(private val first: T) {
  var shelved: Keepsake<T> = Locket(first)

  fun keep(): Keepsake<T> = Locket(first)

  fun swap(next: Keepsake<T>): Keepsake<T> {
    val old: Keepsake<T> = shelved
    shelved = next
    return old
  }
}

/**
 * ADR-224 with ADR-174: a generic interface carrying `suspend` and `Flow` members. Today every
 * such member is skipped by name; the Kotlin-backed side binds over the erased receiver.
 */
interface Heirloom<T> {
  val label: T
  suspend fun polish(): T
  fun glints(): Flow<T>
}

/** The Kotlin-implemented side of [Heirloom]. */
class Brooch<T>(override val label: T) : Heirloom<T> {
  override suspend fun polish(): T {
    delay(10.milliseconds)
    return label
  }

  override fun glints(): Flow<T> = flowOf(label, label)
}

/** A closed [Heirloom] return. */
fun heirloom(): Heirloom<String> = Brooch("Oreo's whisker")

/** An open [Heirloom] return, over an instantiation only the caller picks. */
fun <T> heirloomOf(label: T): Heirloom<T> = Brooch(label)

/**
 * ADR-224: a constrained generic interface. `IPerch<T>` restates its bridge's
 * `where T : IFurry`, so its erased mint compiles and a C# perch crosses inside a collection.
 */
interface Perch<T : Furry> {
  val sitter: T
}

/** An erased input over a C# implementation of a constrained generic interface. */
fun <T : Furry> perchNames(perches: List<Perch<T>>): String =
  perches.joinToString { it.sitter.name }

/** The same over [Podium]'s self-referencing bound, which C# drops (`IComparable` is unspelled). */
fun <T : Comparable<T>> crownAll(podiums: List<Podium<T>>): String =
  podiums.joinToString { "crowned ${it.best}" }

/**
 * ADR-224: an unchecked cast hands a stored `Keepsake<Int>` back at a `Keepsake<String>` position,
 * so C# reads a C# `IKeepsake<int>` where its signature promises `IKeepsake<string>`.
 */
@Suppress("UNCHECKED_CAST")
fun launder(keepsake: Keepsake<Int>): Keepsake<String> = (keepsake as Keepsake<*>) as Keepsake<String>
