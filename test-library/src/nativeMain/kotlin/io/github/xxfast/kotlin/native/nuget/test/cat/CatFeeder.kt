package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

  // Issue #98: ADR-020 promised suspend lambda arities 0-3, but only 0 and 1 had a Kotlin export.
  val onFeedPortion: suspend (String, Int) -> String = { food, grams ->
    delay(50.milliseconds)
    "$catName devoured ${grams}g of $food!"
  }

  val onFeedSchedule: suspend (String, Int, String) -> String = { food, grams, time ->
    delay(50.milliseconds)
    "$catName devoured ${grams}g of $food at $time!"
  }

  val onLogMeal: suspend (String, String) -> Unit = { _, _ ->
    delay(50.milliseconds)
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

  // Issue #299: the Flow/StateFlow member routes share the legacy parameter classifier with the
  // suspend route, so a nullable parameter was exported as non-null here too.

  /** Nullable Int parameter on a Flow member: one emission spelling what Kotlin received. */
  fun snacks(limit: Int?): Flow<String> = flow {
    emit(if (limit == null) "$catName snacks: unlimited" else "$catName snacks: $limit")
  }

  /** Nullable String parameter on a StateFlow member: an unlabelled bowl is still a bowl. */
  fun bowlStatus(bowl: String?): StateFlow<String> =
    MutableStateFlow(if (bowl == null) "$catName bowl: unlabelled" else "$catName bowl: $bowl")

  /**
   * Nullable Char parameter on a suspend member returning StateFlow (ADR-068): the third legacy
   * bucket sharing the classifier. The letter stamped on the bowl, if anyone stamped one.
   */
  suspend fun awaitBowlInitial(initial: Char?): StateFlow<String> {
    delay(10.milliseconds)
    return MutableStateFlow(initial?.let { "$catName bowl initial: $it" } ?: "$catName bowl initial: none")
  }
}
