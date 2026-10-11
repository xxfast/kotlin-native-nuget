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
   * ADR-209 over the ADR-071 value-class arm: a value class element, written from C# as its
   * underlying `String` and re-wrapped here, so [CatId]'s `init` runs on every emit. Replays
   * two.
   */
  val tags: MutableSharedFlow<CatId> = MutableSharedFlow(replay = 2)

  /**
   * ADR-209 over ADR-071's nullable arms: a nullable enum element, emitted from C# as a has-value
   * slot ahead of the ordinal, so `null` and `HAPPY` (ordinal 0) are different emits. Replays
   * two.
   */
  val hunches: MutableSharedFlow<Mood?> = MutableSharedFlow(replay = 2)

  /** ADR-209: a `Boolean?` element, an `I1` value behind the has-value slot. Replays two. */
  val purrs: MutableSharedFlow<Boolean?> = MutableSharedFlow(replay = 2)

  /** ADR-209: a `Char?` element, a `U2` value behind the has-value slot. Replays two. */
  val initials: MutableSharedFlow<Char?> = MutableSharedFlow(replay = 2)

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

  /** The id of the last tag [tags] replays, read on the Kotlin side. */
  fun latestTag(): String? = tags.replayCache.lastOrNull()?.id

  /** [hunches]' replay cache read on the Kotlin side: each entry's name, `none` for null. */
  fun latestHunches(): List<String> = hunches.replayCache.map { mood -> mood?.name ?: "none" }

  /** [purrs]' replay cache read on the Kotlin side, `none` for null. */
  fun latestPurrs(): List<String> = purrs.replayCache.map { purr -> purr?.toString() ?: "none" }

  /** [initials]' replay cache read on the Kotlin side, `none` for null. */
  fun latestInitials(): List<String> =
    initials.replayCache.map { initial -> initial?.toString() ?: "none" }

  fun publish(headline: String, edition: Int) {
    headlines.tryEmit("$name: $headline")
    _editions.tryEmit(edition)
    _sightings.tryEmit(Cat(name))
  }
}

/**
 * ADR-209: a second owner over one bulletin's [CatBulletin.pulses], with its own C# wrapper and so
 * its own coroutine scope. A collector started through it outlives the bulletin's `Dispose()`,
 * which is what lets a test park an emit on the bulletin and then cancel that emit ALONE. With the
 * stalled collector on the bulletin's own scope the two are cancelled together, and the collector
 * leaving can free the emit before the emit's own cancellation lands.
 */
class CatBulletinMonitor(bulletin: CatBulletin) {
  val pulses: SharedFlow<Int> = bulletin.pulses
}
