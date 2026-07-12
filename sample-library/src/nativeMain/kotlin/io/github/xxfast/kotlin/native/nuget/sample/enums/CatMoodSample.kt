package io.github.xxfast.kotlin.native.nuget.sample.enums

import sample.enums.CatMood
import sample.enums.CatMoodService

/**
 * ADR-050 reverse round trip: C# enum -> generated Kotlin enum class -> C# enum.
 *
 * It covers an enum argument and return value through [CatMoodService.advance], plus its
 * static [CatMoodService.defaultMood] and instance [CatMoodService.currentMood] properties.
 */
fun catMoodRoundTrip(): CatMood {
  CatMoodService.defaultMood = CatMood.SLEEPY

  val service = CatMoodService(CatMood.PLAYFUL)
  service.currentMood = CatMood.HUNGRY

  check(service.readDefault() == CatMood.SLEEPY) {
    "CatMoodService.defaultMood did not round trip through its instance method"
  }

  return service.advance(service.currentMood)
}

/**
 * ADR-006 forward round trip: the enum crosses the C ABI as its ordinal Int in both directions.
 *
 * It covers an enum argument on a top-level function, which [catMoodRoundTrip] cannot: that one
 * takes no parameters.
 */
fun advanceMood(mood: CatMood): CatMood = CatMoodService(mood).advance(mood)
