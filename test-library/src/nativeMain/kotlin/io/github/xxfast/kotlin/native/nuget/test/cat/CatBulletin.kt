package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * ADR-205: `SharedFlow<T>` binds at a property, a method return and a `suspend` return. ADR-209
 * spells it `KotlinSharedFlow<T>` (adding `ReplayCache`) and a declared `MutableSharedFlow<T>`
 * `KotlinMutableSharedFlow<T>` (adding `SubscriptionCount`, `EmitAsync` and `TryEmit`).
 *
 * Every stream but [pulses] keeps a replay cache, which is what makes the consumer tests
 * deterministic: a test publishes first and then asserts the replayed items arrive first, in
 * order. None of these streams ever completes.
 */
class CatBulletin(private val name: String) {
  private val _editions: MutableSharedFlow<Int> = MutableSharedFlow(replay = 1)

  /** `SharedFlow<Int>` property: primitive element, no conversion at the seam. */
  val editions: SharedFlow<Int> = _editions.asSharedFlow()

  private val _sightings: MutableSharedFlow<Cat> = MutableSharedFlow(replay = 1)

  /** `SharedFlow<Cat>` property: an exported object element, one handle per emission. */
  val sightings: SharedFlow<Cat> = _sightings.asSharedFlow()

  /** A declared `MutableSharedFlow<String>`: writable from C# (ADR-209). Replays two. */
  val headlines: MutableSharedFlow<String> = MutableSharedFlow(replay = 2)

  /** ADR-209: an enum element, written from C# by ordinal. Replays one. */
  val moods: MutableSharedFlow<Mood> = MutableSharedFlow(replay = 1)

  /** ADR-209: an exported object element, written from C# by handle. Replays one. */
  val visitors: MutableSharedFlow<Cat> = MutableSharedFlow(replay = 1)

  /**
   * ADR-209: no replay and no buffer, so an emit parks for as long as a subscriber is busy with
   * the previous item. The deterministic shape of a parked `EmitAsync`.
   */
  val pulses: MutableSharedFlow<Int> = MutableSharedFlow()

  /** `SharedFlow<Int>` as a non-suspend method return, sharing storage with [editions]. */
  fun editionReport(): SharedFlow<Int> = editions

  /** A `MutableSharedFlow<String>` method return, held by C# (ADR-209), over [headlines]. */
  fun headlineDesk(): MutableSharedFlow<String> = headlines

  /** `SharedFlow<Cat>` as a `suspend` return, sharing storage with [sightings]. */
  suspend fun latestSightings(): SharedFlow<Cat> = sightings

  /** A `MutableSharedFlow<String>` `suspend` return, over [headlines]. */
  suspend fun awaitHeadlineDesk(): MutableSharedFlow<String> = headlines

  /** The Kotlin side's own read of [headlines]' replay cache, for C# to compare against. */
  fun latestHeadlines(): List<String> = headlines.replayCache

  /** The Kotlin side's own read of [moods]' replay cache. */
  fun latestMood(): Mood? = moods.replayCache.lastOrNull()

  /** The name of the last cat [visitors] replays, read on the Kotlin side. */
  fun latestVisitor(): String? = visitors.replayCache.lastOrNull()?.name

  fun publish(headline: String, edition: Int) {
    headlines.tryEmit("$name: $headline")
    _editions.tryEmit(edition)
    _sightings.tryEmit(Cat(name))
  }
}
