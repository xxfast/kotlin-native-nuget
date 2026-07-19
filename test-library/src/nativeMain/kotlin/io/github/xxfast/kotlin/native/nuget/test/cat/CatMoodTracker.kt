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
}
