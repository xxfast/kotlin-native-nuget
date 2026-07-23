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
}
