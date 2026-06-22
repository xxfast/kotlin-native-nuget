package io.github.xxfast.kotlin.native.nuget.sample.cat

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

suspend fun fetchCatTreat(catName: String): String {
  delay(100.milliseconds)
  if (catName == "Oreo") throw IllegalArgumentException("Oreo is on a diet!")
  return "$catName got a treat"
}
