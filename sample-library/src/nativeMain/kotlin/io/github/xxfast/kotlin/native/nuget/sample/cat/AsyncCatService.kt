package io.github.xxfast.kotlin.native.nuget.sample.cat

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

class AsyncCatService(private val prefix: String) {
  suspend fun fetch(): String {
    delay(5.seconds)
    return "$prefix result"
  }

  suspend fun fetchCat(name: String): Cat {
    delay(5.seconds)
    return Cat(name)
  }
}
