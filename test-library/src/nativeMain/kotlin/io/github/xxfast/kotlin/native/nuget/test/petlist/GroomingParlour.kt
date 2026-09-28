package io.github.xxfast.kotlin.native.nuget.test.petlist

import kotlinx.coroutines.yield

/**
 * ADR-176 transitive reachability cell. [Brush] appears ONLY inside collections returned by
 * [Stylist]'s own members, and [Stylist] is reachable only through [GroomingParlour.stylist]. So
 * [Brush] gets its backing wrapper and `Factories` key only if the collection walk also visits
 * the members of interfaces it has already found reachable (a fixed point); without that,
 * `IStylist.Brushes()` binds and throws `NotSupportedException` at the first Kotlin-backed brush.
 *
 * Oreo tolerates the slicker brush. Mylo only tolerates the soft one, and only on Sundays.
 */
interface Brush {
  val name: String
}

/** Reachable through [GroomingParlour.stylist]; its members are the only positions of [Brush]. */
interface Stylist {
  fun brushes(): List<Brush>
  suspend fun brushesLater(): List<Brush>
}

class GroomingParlour {
  fun stylist(): Stylist = object : Stylist {
    override fun brushes(): List<Brush> = listOf(brush("slicker"), brush("soft"))

    override suspend fun brushesLater(): List<Brush> {
      yield()
      return listOf(brush("soft"), brush("slicker"))
    }
  }

  private fun brush(called: String): Brush = object : Brush {
    override val name: String = called
  }
}
