package io.github.xxfast.kotlin.native.nuget.test.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// ADR-074 amendment (2026-09-27) fixture: the macos actuals for `PlatformDefaults.kt`. No
// `actual` member here may restate a default (Kotlin forbids it); the public surface and the
// bodies must stay IDENTICAL to the other target's file.

actual class Bowl actual constructor(private val grams: Int) {
  actual fun fill(scoops: Int): Int = grams + scoops * 10

  actual suspend fun refill(scoops: Int): Int = grams + scoops * 100

  actual fun trickle(drops: Int): Flow<Int> = flow { repeat(drops) { emit(grams + it + 1) } }

  actual companion object {
    actual fun of(grams: Int): Bowl = Bowl(grams)
  }
}

actual fun Bowl.topUp(extra: Int): Int = fill(0) + extra

actual sealed class Meal {
  actual abstract fun portion(extra: Int): Int

  /** An arm with state: `grams + extra`. */
  class Dry(val grams: Int) : Meal() {
    override fun portion(extra: Int): Int = grams + extra
  }

  /** A stateless arm: `100 + extra`. */
  data object Wet : Meal() {
    override fun portion(extra: Int): Int = 100 + extra
  }
}

actual object Cupboard {
  actual fun stock(tins: Int): Int = tins * 2
}

actual fun dryMeal(grams: Int): Meal = Meal.Dry(grams)

actual fun wetMeal(): Meal = Meal.Wet
