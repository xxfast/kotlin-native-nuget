package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds
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

  val mealAnnouncements: Flow<String> = flow {
    emit("$catName is hungry")
    delay(50.milliseconds)
    emit("$catName is eating")
    delay(50.milliseconds)
    emit("$catName is full")
  }

  fun treats(count: Int): Flow<String> = flow {
    (1..count).forEach { i ->
      delay(50.milliseconds)
      emit("$catName ate treat #$i")
    }
  }

  val portionSizes: Flow<Int> = flow {
    emit(100)
    delay(50.milliseconds)
    emit(150)
    delay(50.milliseconds)
    emit(200)
  }
}
