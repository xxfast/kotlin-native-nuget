package io.github.xxfast.kotlin.native.nuget.test.zoomrun

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-236: lambda-typed properties on an `object`. Each binds as a static C# delegate-typed
 * property: a `val` reads a delegate that calls the Kotlin lambda, a `var` also takes a C# delegate
 * Kotlin then calls, and a nullable `var` reads and writes `null`. Oreo does laps of the landing at
 * 3 a.m.; Mylo times them.
 */
object Landing {
  /** Laps counted by [onLap], so a C# call is observable from Kotlin. */
  var laps: Int = 0

  val onLap: (Int) -> Unit = { count -> laps += count }

  var format: (Double) -> String = { metres -> "Oreo ran $metres m" }

  var onBump: ((String) -> String)? = null

  val onSprint: suspend (Int) -> String = { count ->
    delay(1.milliseconds)
    "Oreo sprinted $count laps"
  }

  var onSettle: (suspend (String) -> String)? = null

  /** Calls whatever [format] holds now, so a C# delegate set into it runs from Kotlin. */
  fun describe(metres: Double): String = format(metres)

  /** Calls [onBump] when set, so a C# delegate set into it runs from Kotlin. */
  fun bump(who: String): String = onBump?.invoke(who) ?: "nobody bumped $who"

  /** Awaits [onSettle] when set. */
  suspend fun settle(who: String): String = onSettle?.invoke(who) ?: "$who never settled"
}
