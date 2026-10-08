package dev.other.bysuspend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Issue #487 fixture: a bare dependency **interface** reached **only** as the element of a `Flow`
 * result on an admitted dependency class. [Spotter] is reached through the export closure from a
 * single sync member on `:test-library` (`errand/ErrandRunner.lookout`); [Sighting] is reached
 * only by walking [Spotter.watch]'s `Flow` element.
 *
 * [Sighting] must appear in NO other position in the export scope: no parameter, no sync return,
 * no property, no collection component, no supertype of an exported class. Any of those is a
 * planned position that already gives the interface its backing class (ADR-040, ADR-135), which
 * would hide the gap this cell is about: the `Flow` element walk alone has to plan it, or the
 * element read spells `new ...Sighting(h, out _)` against a type that is never declared (CS0234).
 * The Kotlin-backed elements below are anonymous objects inside [Spotter.watch] for the same
 * reason: a named implementing class would be one more route to the interface.
 *
 * Same package as [Yarnball], the class `Flow` element arm, so the interface arm renders in the
 * same `TestLibrary.Dev.Other.Bysuspend` namespace and the two cells differ only in the element
 * kind.
 *
 * Oreo keeps watch from the fence. Mylo reports whatever Oreo saw, a little later, from the sofa.
 */
interface Sighting {

  /** Who was seen, or `null` when something rustled the hedge and nobody could say what. */
  val name: String?
}

/**
 * The admitted dependency class that owns the `Flow<Sighting>` member. String-only constructor so
 * the watcher can be told apart in the elements, which proves each element came from this handle.
 */
class Spotter(val watcher: String) {

  /** Three Kotlin-backed sightings, the last one unnamed. */
  fun watch(): Flow<Sighting> {
    fun seen(who: String?): Sighting = object : Sighting {
      override val name: String? = who
    }
    return flowOf(
      seen("$watcher saw Oreo on the fence, black with a white middle"),
      seen("$watcher saw Mylo under the hedge, milky-brown"),
      seen(null),
    )
  }
}
