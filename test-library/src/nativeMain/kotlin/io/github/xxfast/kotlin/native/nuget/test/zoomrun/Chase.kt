package io.github.xxfast.kotlin.native.nuget.test.zoomrun

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-236: lambda-typed properties on a sealed base and on its arms, on the same planned route
 * every other owner takes: a `var` takes a C# delegate, a nullable one reads and writes `null`, a
 * `suspend` one is an async delegate. Oreo chases the toy mouse; Mylo chases Oreo.
 */
sealed class Chase {
  var onPounce: ((Int) -> String)? = null

  val onPurr: suspend (Int) -> Int = { volume ->
    delay(1.milliseconds)
    volume + 1
  }

  /** Calls [onPounce] when set. */
  fun pounce(times: Int): String = onPounce?.invoke(times) ?: "no pounce"

  class Mouse(val name: String) : Chase() {
    var squeak: (String) -> String = { who -> "$name squeaks at $who" }

    var onCaught: (suspend (String) -> String)? = null

    /** Calls whatever [squeak] holds now. */
    fun hear(who: String): String = squeak(who)

    /** Awaits [onCaught] when set. */
    suspend fun catchBy(who: String): String = onCaught?.invoke(who) ?: "$name got away from $who"
  }
}
