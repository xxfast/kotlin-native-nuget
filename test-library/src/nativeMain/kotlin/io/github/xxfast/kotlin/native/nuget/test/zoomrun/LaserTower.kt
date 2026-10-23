package io.github.xxfast.kotlin.native.nuget.test.zoomrun

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-236: a `suspend` member that RETURNS a `StateFlow` or `SharedFlow` and takes a plain lambda
 * that keeps feeding the returned flow after the call completed ([pulse], [flick]). The delegate's
 * callback context must live as long as the returned flow, as it does for a `Flow` result
 * (ADR-231), and be released when the flow is disposed.
 */
class LaserTower(val watcher: String) {
  private var brightness: ((Int) -> Int)? = null
  private var glow: MutableStateFlow<Int>? = null
  private var flicker: ((Int) -> String)? = null
  private var flashes: MutableSharedFlow<String>? = null

  /** Seeds the state with `brightness(0)`; every [pulse] stores `brightness(level)`. */
  suspend fun beam(brightness: (Int) -> Int): StateFlow<Int> {
    delay(1.milliseconds)
    val state = MutableStateFlow(brightness(0))
    this.brightness = brightness
    glow = state
    return state.asStateFlow()
  }

  /** Calls the lambda [beam] was given, after its `Task` completed, and publishes the result. */
  fun pulse(level: Int): Int {
    val call: (Int) -> Int = brightness ?: return -1
    val state: MutableStateFlow<Int> = glow ?: return -1
    state.value = call(level)
    return state.value
  }

  /** Returns a replaying shared flow every [flick] feeds through [describe]. */
  suspend fun flash(describe: (Int) -> String): SharedFlow<String> {
    delay(1.milliseconds)
    val shared = MutableSharedFlow<String>(replay = 8)
    flicker = describe
    flashes = shared
    return shared.asSharedFlow()
  }

  /** Calls the lambda [flash] was given, after its `Task` completed, and emits the result. */
  fun flick(times: Int): Boolean {
    val call: (Int) -> String = flicker ?: return false
    val shared: MutableSharedFlow<String> = flashes ?: return false
    return shared.tryEmit(call(times))
  }
}

/** ADR-236: the same `StateFlow` result on an object. */
object Lighthouse {
  private var sweep: ((Int) -> Int)? = null
  private var beam: MutableStateFlow<Int>? = null

  suspend fun shine(sweep: (Int) -> Int): StateFlow<Int> {
    delay(1.milliseconds)
    val state = MutableStateFlow(sweep(0))
    this.sweep = sweep
    beam = state
    return state.asStateFlow()
  }

  fun turn(angle: Int): Int {
    val call: (Int) -> Int = sweep ?: return -1
    val state: MutableStateFlow<Int> = beam ?: return -1
    state.value = call(angle)
    return state.value
  }
}
