package io.github.xxfast.kotlin.native.nuget.test.zoomrun

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** ADR-236: lambda-typed properties on a default-named companion, static on [Racetrack]. */
class Racetrack(val runner: String) {
  companion object {
    val onStart: () -> String = { "on your marks" }

    var announce: (String) -> String = { runner -> "$runner takes the lead" }

    var onFinish: ((Int) -> String)? = null

    val onPhotoFinish: suspend (String) -> String = { runner ->
      delay(1.milliseconds)
      "$runner by a whisker"
    }

    /** Calls whatever [announce] holds now. */
    fun call(runner: String): String = announce(runner)

    /** Calls [onFinish] when set. */
    fun finish(place: Int): String = onFinish?.invoke(place) ?: "no finish line"
  }
}

/** ADR-236: lambda-typed properties on a NAMED companion, static on [Burrow]. */
class Burrow(val owner: String) {
  companion object Warren {
    val onDig: (Int) -> Int = { depth -> depth * 3 }

    var measure: (Int) -> String = { depth -> "$depth paws deep" }

    var onCollapse: ((String) -> Unit)? = null

    var collapsed: String = "nothing collapsed"

    /** Calls whatever [measure] holds now. */
    fun survey(depth: Int): String = measure(depth)

    /** Calls [onCollapse] when set, recording that it ran. */
    fun collapse(who: String): String {
      val handler: (String) -> Unit = onCollapse ?: return "no collapse handler"
      handler(who)
      return "collapsed on $who"
    }
  }
}
