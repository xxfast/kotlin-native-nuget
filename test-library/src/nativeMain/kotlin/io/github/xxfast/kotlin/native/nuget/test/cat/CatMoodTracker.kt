package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ADR-065: StateFlow<T> mapping fixture.
 *
 * Tracks a cat's mood, energy level, and current playmate as hot, always-current-value
 * streams -- exercising every branch the `.Value` unwrap / stream element marshalling cascade
 * must cross:
 *  - [energyLevel]: `StateFlow<Int>`    -- primitive, no conversion at the seam
 *  - [mood]:        `StateFlow<String>` -- needs conversion (box unwrap)
 *  - [playmate]:    `StateFlow<Cat>`    -- object element, handle-backed IDisposable wrapper
 *
 * [moodReport] additionally covers `StateFlow<T>` as a **non-suspend function return** (it
 * shares the same underlying [_mood] MutableStateFlow as the [mood] property, so mutating one
 * is observable through both surface positions).
 *
 * All mutations are driven by explicit methods ([bumpEnergy], [setMood], [setPlaymate]) --
 * never a timer -- so tests can assert deterministic conflated updates without racing a
 * background emitter.
 *
 * ADR-067 extends this fixture with the two nullable StateFlow shapes:
 *  - [nickname]:    `StateFlow<String?>` -- nullable REFERENCE element (`.Value` is `string?`)
 *  - [streak]:      `StateFlow<Int?>`    -- nullable VALUE element (`.Value` is `int?`, needs the
 *                    `Nullable<T>`-aware unwrap; a plain `int` would pass trivially and hide the seam)
 *  - [maybeMood]:   `StateFlow<String>?` -- nullable MEMBER, absent until [startTracking]
 *  - [maybeStreak]: `StateFlow<Int?>?`   -- nullable MEMBER *and* nullable VALUE element, together
 *
 * ADR-071 extends this fixture with genuinely-declared (not `.asStateFlow()`-narrowed)
 * `MutableStateFlow<T>` members, so C# gets a settable `.Value`:
 *  - [treatCount]:    `MutableStateFlow<Int>`    -- primitive element, no conversion at the write seam
 *  - [collarColour]:  `MutableStateFlow<String>` -- needs conversion at the write seam
 *  - [favouriteToy]:  `MutableStateFlow<Cat>`    -- object element, crosses as a handle
 *  - [treatJar]:      `MutableStateFlow<Int>` as a non-suspend function return, sharing storage
 *                      with [treatCount]
 *  - [grudge]:        `MutableStateFlow<Grudge>` whose element's `equals` throws, forcing the
 *                      setter's ADR-030 `errorOut` path
 */
class CatMoodTracker(private val catName: String) {
  private val _energyLevel: MutableStateFlow<Int> = MutableStateFlow(100)

  /** StateFlow<Int> as a class property -- primitive element, no conversion at the seam. */
  val energyLevel: StateFlow<Int> = _energyLevel.asStateFlow()

  private val _mood: MutableStateFlow<String> = MutableStateFlow("sleepy")

  /** StateFlow<String> as a class property -- needs conversion (box unwrap) at the seam. */
  val mood: StateFlow<String> = _mood.asStateFlow()

  private val _playmate: MutableStateFlow<Cat> = MutableStateFlow(Cat(catName))

  /** StateFlow<Cat> as a class property -- object element, handle-backed IDisposable wrapper. */
  val playmate: StateFlow<Cat> = _playmate.asStateFlow()

  /**
   * StateFlow<T> as a non-suspend function return, mirroring [mood] verbatim (same underlying
   * MutableStateFlow) so mutation-visibility can be asserted through both surface positions.
   */
  fun moodReport(): StateFlow<String> = mood

  /** Deterministic mutation -- bumps [energyLevel] by [amount]. No timers involved. */
  fun bumpEnergy(amount: Int) {
    _energyLevel.value += amount
  }

  /** Deterministic mutation -- sets [mood] (and therefore [moodReport]) to [newMood]. */
  fun setMood(newMood: String) {
    _mood.value = newMood
  }

  /** Deterministic mutation -- replaces [playmate] with a freshly named [Cat]. */
  fun setPlaymate(name: String) {
    _playmate.value = Cat(name)
  }

  // --- ADR-067: nullable element -- the tracker always exists, its current value can be null ---

  private val _nickname: MutableStateFlow<String?> = MutableStateFlow(null)

  /**
   * StateFlow<String?> -- nullable REFERENCE element. `.Value` is `string?`; a null current
   * value crosses as IntPtr.Zero and reuses `FromHandle<T>` unchanged (already null-safe).
   */
  val nickname: StateFlow<String?> = _nickname.asStateFlow()

  private val _streak: MutableStateFlow<Int?> = MutableStateFlow(null)

  /**
   * StateFlow<Int?> -- nullable VALUE element. `.Value` is `int?`; needs the new
   * `Nullable<T>`-aware unwrap (a plain `int` would need no conversion and pass trivially).
   */
  val streak: StateFlow<Int?> = _streak.asStateFlow()

  /** Deterministic mutation -- sets [nickname], which may be null. */
  fun setNickname(name: String?) {
    _nickname.value = name
  }

  /** Deterministic mutation -- sets [streak], which may be null. */
  fun setStreak(n: Int?) {
    _streak.value = n
  }

  // --- ADR-067: nullable member -- the whole StateFlow can be absent until tracking starts ---

  private var _maybeMood: MutableStateFlow<String>? = null

  /**
   * StateFlow<String>? -- nullable MEMBER. Null until [startTracking] is called; the `_has_value`
   * presence-probe backs the C# getter, which returns `null` before subscription.
   */
  val maybeMood: StateFlow<String>? get() = _maybeMood?.asStateFlow()

  /** Deterministic mutation -- brings [maybeMood] into existence with [initial]. */
  fun startTracking(initial: String) {
    _maybeMood = MutableStateFlow(initial)
  }

  /** Deterministic mutation -- sets [maybeMood]'s current value, once tracking has started. */
  fun setMaybeMood(m: String) {
    _maybeMood?.value = m
  }

  // --- ADR-067: both together -- nullable member AND nullable value element ---

  private var _maybeStreak: MutableStateFlow<Int?>? = null

  /** StateFlow<Int?>? -- nullable member AND nullable value element, exercised together. */
  val maybeStreak: StateFlow<Int?>? get() = _maybeStreak?.asStateFlow()

  /** Deterministic mutation -- brings [maybeStreak] into existence with [initial] (may be null). */
  fun startStreakTracking(initial: Int?) {
    _maybeStreak = MutableStateFlow(initial)
  }

  /** Deterministic mutation -- sets [maybeStreak]'s current value, once tracking has started. */
  fun setMaybeStreak(n: Int?) {
    _maybeStreak?.value = n
  }

  // --- ADR-068: suspend fun returning StateFlow<T> -- outer suspend kept as Task, inner is ADR-065's
  // KotlinStateFlow<T> unchanged. Both genuinely suspend (a real delay) before handing back the SAME
  // underlying MutableStateFlow already exposed elsewhere, so mutation is observable across every
  // surface position and the outer suspend is not vestigial. ---

  /**
   * ADR-068: `suspend fun` returning `StateFlow<String>` -- primitive/value-element variant.
   * Genuinely suspends (a real await) before handing back the SAME underlying [_mood]
   * MutableStateFlow as [mood]/[moodReport].
   */
  suspend fun awaitMoodReport(): StateFlow<String> {
    kotlinx.coroutines.delay(1)
    return mood
  }

  /** ADR-068: object-element variant -- suspend fun returning StateFlow<Cat>. */
  suspend fun awaitPlaymateReport(): StateFlow<Cat> {
    kotlinx.coroutines.delay(1)
    return playmate
  }

  // --- ADR-071: MutableStateFlow<T> declared PUBLICLY -- settable .Value from C#. Contrast with
  // [mood]/[energyLevel] above, which are MutableStateFlow-backed but declared as read-only
  // StateFlow views and must keep their get-only .Value. ---

  /** MutableStateFlow<Int> -- primitive element, no conversion at the write seam. */
  val treatCount: MutableStateFlow<Int> = MutableStateFlow(0)

  /** MutableStateFlow<String> -- needs conversion (string marshalling) at the write seam. */
  val collarColour: MutableStateFlow<String> = MutableStateFlow("red")

  /** MutableStateFlow<Cat> -- object element; crosses as a handle in both directions. */
  val favouriteToy: MutableStateFlow<Cat> = MutableStateFlow(Cat("Mittens"))

  /**
   * MutableStateFlow<T> as a non-suspend function return, sharing [treatCount]'s storage so a
   * write through one surface position is observable through the other.
   */
  fun treatJar(): MutableStateFlow<Int> = treatCount

  /** Kotlin-side read-back: proves a C# write really landed in Kotlin, not just in a C# cache. */
  fun treatsGivenSoFar(): Int = treatCount.value

  val grudge: MutableStateFlow<Grudge> = MutableStateFlow(Grudge("the vet"))
}

/**
 * ADR-071: an element type whose `equals` throws, so the Kotlin `value` setter itself throws
 * (MutableStateFlow conflates by Any.equals -- StateFlow.kt:332). Forces the ADR-030 errorOut
 * path on the setter export to be genuinely reachable rather than defensive-only.
 *
 * Deliberately a top-level class (not nested inside [CatMoodTracker]) -- nested-class bridging
 * is not an established, tested feature of this generator, and this fixture must not risk an
 * unrelated build failure that masks the real ADR-071 failure signal.
 */
class Grudge(val reason: String) {
  override fun equals(other: Any?): Boolean = error("Cats never forgive: $reason")
  override fun hashCode(): Int = reason.hashCode()
}
