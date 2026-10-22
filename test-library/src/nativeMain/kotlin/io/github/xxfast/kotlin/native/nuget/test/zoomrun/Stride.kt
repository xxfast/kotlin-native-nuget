package io.github.xxfast.kotlin.native.nuget.test.zoomrun

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-236: lambda-typed properties in an enum's body, bound as `StrideExtensions` members, and in
 * the enum's companion, bound static on `StrideExtensions`.
 */
enum class Stride(val paws: Int) {
  TROT(2),
  GALLOP(4);

  val onStride: (Int) -> Int = { steps -> steps * paws }

  var cheer: (String) -> String = { who -> "$who ${name.lowercase()}s" }

  var onTrip: ((Int) -> String)? = null

  val onPace: suspend (Int) -> String = { steps ->
    delay(1.milliseconds)
    "${name.lowercase()} for $steps"
  }

  /** Calls whatever [cheer] holds now. */
  fun shout(who: String): String = cheer(who)

  /** Calls [onTrip] when set. */
  fun trip(step: Int): String = onTrip?.invoke(step) ?: "steady on step $step"

  companion object {
    val onPick: (Int) -> Stride = { paws -> entries.first { it.paws == paws } }

    var fallback: ((Stride) -> String)? = null

    /** Calls [fallback] when set. */
    fun recover(stride: Stride): String = fallback?.invoke(stride) ?: "no fallback for $stride"
  }
}
