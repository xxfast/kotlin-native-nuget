package io.github.xxfast.kotlin.native.nuget.test.zoomrun

/**
 * ADR-236: an ADR-039 listener interface carrying lambda-typed properties. A C# listener supplies
 * each as a delegate, and the Kotlin side reads them back through the subscription's bridge and
 * calls them ([Stopwatch.lap], [Stopwatch.split]), and writes one ([Stopwatch.coach]). Mylo
 * holds the stopwatch while Oreo laps the hallway.
 */
interface LapListener {
  val onLap: (Int) -> String
  var onSplit: ((Int) -> Int)?
}

class Stopwatch(val holder: String) {
  private val listeners: MutableList<LapListener> = mutableListOf()

  fun addLapListener(listener: LapListener) {
    listeners.add(listener)
  }

  fun removeLapListener(listener: LapListener) {
    listeners.remove(listener)
  }

  /** Every listener's [LapListener.onLap] for [count], joined. */
  fun lap(count: Int): String = listeners.joinToString("; ") { it.onLap(count) }

  /** The sum of every listener's [LapListener.onSplit] of [seconds], or -1 for each unset one. */
  fun split(seconds: Int): Int = listeners.sumOf { it.onSplit?.invoke(seconds) ?: -1 }

  /** Writes a Kotlin lambda into every listener's [LapListener.onSplit]. */
  fun coach(offset: Int) {
    listeners.forEach { it.onSplit = { seconds -> seconds + offset } }
  }

  /** How many listeners are subscribed. */
  fun count(): Int = listeners.size
}
