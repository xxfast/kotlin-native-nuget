package io.github.xxfast.kotlin.native.nuget.test.dash

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-231: a plain lambda on a top-level `suspend` function, a top-level `Flow` function and
 * `suspend` extensions, the static owners of `Treadmill.kt`'s route.
 */

/** A top-level suspend function: `DashSample.DashAsync(int, Action<int>)`. */
suspend fun dash(meters: Int, onMeter: (Int) -> Unit): Int {
  repeat(meters) {
    delay(1.milliseconds)
    onMeter(it)
  }
  return meters
}

/** A top-level generic `T` through the lambda. */
suspend fun <T> bounce(value: T, back: (T) -> T): T {
  delay(1.milliseconds)
  return back(value)
}

/** A top-level `Flow` with a plain lambda. */
fun dashes(count: Int, onDash: (Int) -> Unit): Flow<Int> = flow {
  repeat(count) {
    onDash(it)
    emit(it)
  }
}

/** A class-receiver suspend extension: `treadmill.WarmUpAsync(Action<int>)`. */
suspend fun Treadmill.warmUp(onStep: (Int) -> Unit): String {
  delay(1.milliseconds)
  onStep(runner.length)
  return "$runner warmed up"
}

/** A string-receiver suspend extension with a returning lambda. */
suspend fun String.zoom(shout: (String) -> String): String {
  delay(1.milliseconds)
  return shout(this)
}
