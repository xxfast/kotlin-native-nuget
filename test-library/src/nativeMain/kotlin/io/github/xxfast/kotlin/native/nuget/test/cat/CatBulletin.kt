package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * ADR-205: `SharedFlow<T>` (and a declared `MutableSharedFlow<T>`, as the read-only view) binds as
 * `KotlinFlow<T>` at a property, a method return and a `suspend` return.
 *
 * Every stream keeps a replay cache, which is what makes the consumer tests deterministic: there is
 * no bridge-visible signal that the Kotlin collector has subscribed, so a test publishes first and
 * then asserts the replayed items arrive first, in order. None of these streams ever completes.
 */
class CatBulletin(private val name: String) {
  private val _editions: MutableSharedFlow<Int> = MutableSharedFlow(replay = 1)

  /** `SharedFlow<Int>` property: primitive element, no conversion at the seam. */
  val editions: SharedFlow<Int> = _editions.asSharedFlow()

  private val _sightings: MutableSharedFlow<Cat> = MutableSharedFlow(replay = 1)

  /** `SharedFlow<Cat>` property: an exported object element, one handle per emission. */
  val sightings: SharedFlow<Cat> = _sightings.asSharedFlow()

  /** A declared `MutableSharedFlow<String>`: v1 binds the read-only view. Replays two. */
  val headlines: MutableSharedFlow<String> = MutableSharedFlow(replay = 2)

  /** `SharedFlow<Int>` as a non-suspend method return, sharing storage with [editions]. */
  fun editionReport(): SharedFlow<Int> = editions

  /** `SharedFlow<Cat>` as a `suspend` return, sharing storage with [sightings]. */
  suspend fun latestSightings(): SharedFlow<Cat> = sightings

  fun publish(headline: String, edition: Int) {
    headlines.tryEmit("$name: $headline")
    _editions.tryEmit(edition)
    _sightings.tryEmit(Cat(name))
  }
}
