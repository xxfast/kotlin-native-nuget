package io.github.xxfast.kotlin.native.nuget.test.catnap

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-229: `suspend` extensions bind on the static suspend route with the receiver as a leading
 * parameter of the `_async` export, rendered as C# `this` extensions on an ad-hoc scope per call.
 * The receivers cross a class handle, a nullable class handle, a string, an `Int` and a `Boolean`.
 */

/** A class receiver, read after the suspension. */
suspend fun Swing.rock(times: Int): String {
  delay(5.milliseconds)
  return "$sleeper rocked $times times"
}

/** The extension's own `T`. */
suspend fun <T> Swing.tuck(value: T): T {
  delay(1.milliseconds)
  return value
}

/** A nullable receiver: an empty swing stays empty. */
suspend fun Swing?.occupant(): String {
  delay(1.milliseconds)
  return this?.sleeper ?: "empty"
}

/** A string receiver. */
suspend fun String.yawn(): String {
  delay(1.milliseconds)
  return "$this yawns"
}

/** Oreo clawed through it. */
suspend fun Swing.fray(): String {
  delay(1.milliseconds)
  throw IllegalStateException("$sleeper clawed through the swing")
}

/** Never completes. */
suspend fun Swing.dangle(): String = awaitCancellation()

/** A primitive receiver: Oreo naps this many minutes. */
suspend fun Int.napMinutes(cat: String): String {
  delay(1.milliseconds)
  return "$cat naps for $this minutes"
}

/** A second primitive receiver, a `Boolean`, on its one-byte wire. */
suspend fun Boolean.stirring(): String {
  delay(1.milliseconds)
  return if (this) "Mylo is stirring" else "Mylo is out cold"
}
