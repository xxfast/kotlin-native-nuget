package io.github.xxfast.kotlin.native.nuget.sample.cat

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

class CatFeeder(val catName: String) {
  val onFeed: suspend () -> String = {
    delay(1.seconds)
    "$catName gobbled up the food!"
  }

  val onFeedWith: suspend (String) -> String = { food ->
    delay(1.seconds)
    "$catName devoured the $food!"
  }

  val onCleanup: suspend () -> Unit = {
    delay(1.seconds)
  }
}
