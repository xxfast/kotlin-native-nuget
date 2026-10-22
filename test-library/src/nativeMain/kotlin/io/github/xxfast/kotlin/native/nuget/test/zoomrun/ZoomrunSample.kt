package io.github.xxfast.kotlin.native.nuget.test.zoomrun

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/*
 * ADR-236: lambda-typed properties at the top level, bound as static delegate-typed properties on
 * the file facade (`ZoomrunSample`), plus the Kotlin calls that run whatever C# set into them.
 */

private var zooms: Int = 0

/** How many zooms [onZoom] has counted. */
fun zoomCount(): Int = zooms

val onZoom: (Int) -> Unit = { count -> zooms += count }

var zoomFormat: (Int) -> String = { count -> "$count zooms" }

var onZoomOut: ((String) -> String)? = null

val onZoomAsync: suspend (Int) -> Int = { count ->
  delay(1.milliseconds)
  count * 2
}

/** Calls whatever [zoomFormat] holds now. */
fun formatZooms(count: Int): String = zoomFormat(count)

/** Calls [onZoomOut] when set. */
fun zoomOut(who: String): String = onZoomOut?.invoke(who) ?: "$who stayed in"

/** Reads a [Pouncer]'s plain lambda property from Kotlin, so a C# implementation is called. */
fun pounce(pouncer: Pouncer, times: Int): String = pouncer.onPounce(times)

/** Reads a [Pouncer]'s nullable `var`, or names its absence. */
fun cue(pouncer: Pouncer, word: String): String = pouncer.cue?.invoke(word) ?: "no cue for $word"

/** Writes a Kotlin lambda into a [Pouncer]'s `var`, through the bridge when C# implements it. */
fun teach(pouncer: Pouncer, prefix: String) {
  pouncer.cue = { word -> "$prefix $word" }
}

/** A Kotlin-backed [Pouncer], so C# reads the properties through the backing wrapper. */
fun laserDot(): Pouncer = LaserDot()

private class LaserDot : Pouncer {
  override val onPounce: (Int) -> String = { times -> "Mylo pounced $times times" }
  override var cue: ((String) -> String)? = null
  override val onLeap: suspend (Int) -> Int = { height ->
    delay(1.milliseconds)
    height + 1
  }
}

/** A [Chase.Mouse] seen as its sealed base, so the base's properties are read off the arm. */
fun mouse(name: String): Chase = Chase.Mouse(name)
