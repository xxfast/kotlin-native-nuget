package io.github.xxfast.kotlin.native.nuget.test.errand

import dev.other.bysuspend.Spotter
import dev.other.bysuspend.Yarnball
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.milliseconds

/**
 * ROADMAP Phase 4 line 23, class-level half: the same legacy suspend return shape a top-level
 * member uses, reached through a class. [collectNametag] and [lookUpNametag] cross the
 * module-local value-class arm (spelled `new Nametag(resultPtr, out _)` today, which does not
 * compile against the `readonly record struct`); [yarn] crosses an admitted dependency class as a
 * `Flow` element.
 *
 * Oreo runs the errands. Mylo supervises from the windowsill.
 */
class ErrandRunner(val catName: String) {

  /** Module-local value class at a class-level suspend return, non-null. */
  suspend fun collectNametag(): Nametag {
    delay(10.milliseconds)
    return Nametag("$catName's tag, collected from the engraver")
  }

  /** Module-local value class at a NULLABLE class-level suspend return: only Mylo's was lost. */
  suspend fun lookUpNametag(): Nametag? {
    delay(10.milliseconds)
    return if (catName == "Mylo") null else Nametag("$catName's spare tag")
  }

  /** Admitted dependency class as a `Flow` element on a class: the yarn basket, one ball at a
   *  time. */
  fun yarn(): Flow<Yarnball> = flowOf(Yarnball("black-and-white"), Yarnball("milky-brown"))

  /**
   * Issue #487: the ONE route to [Spotter], a sync return that admits it through the export
   * closure. Its `Flow<Sighting>` member is the only place `Sighting` is reachable from; nothing in
   * `:test-library` may mention `Sighting` itself. Named `lookout` rather than `spotter` so the
   * C# member is not spelled the same as the type it returns.
   */
  fun lookout(): Spotter = Spotter(catName)
}
