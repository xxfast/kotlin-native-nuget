package io.github.xxfast.kotlin.native.nuget.sample.cat

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CatNapService {
  suspend fun longNap(): String {
    delay(10.seconds)
    return "refreshed after nap"
  }

  suspend fun quickNap(): String {
    delay(100.milliseconds)
    return "quick nap done"
  }

  suspend fun silentNap() {
    delay(10.seconds)
  }
}
