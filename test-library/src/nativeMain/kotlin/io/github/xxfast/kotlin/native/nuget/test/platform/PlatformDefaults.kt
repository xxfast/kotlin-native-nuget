package io.github.xxfast.kotlin.native.nuget.test.platform

import kotlinx.coroutines.flow.Flow

// ADR-074 amendment (2026-09-27) fixture: parameter defaults declared on the `expect` half of a
// MEMBER. Kotlin forbids an `actual` from restating a default, so every parameter of the exported
// `actual` member reports `hasDefault = false`; a C# optional parameter exists only if the member
// routes resolve the bit through the owner's `expect class`, as the top-level route (ADR-096) and
// the primary constructor (ADR-091) already did.
//
// One row per member route: the ordinary class plan route, the legacy `suspend` and `Flow`
// member routes (ADR-164 on the legacy routes), the sealed base and its arms (arms are declared
// on the `actual` side, so they reach the default only through their override root), and the
// `expect object`. Every default is declared once, here, and is a value the `actual` bodies turn
// into a distinct number, so a C#-side filler (`0`) reads back as the wrong answer.
//
// The two target files (`PlatformDefaultsMacos.kt` / `PlatformDefaultsMingw.kt`) declare an
// IDENTICAL public surface; their bodies are identical too, because the subject here is where the
// default lives, not which target's body ran (`PlatformResiduals.kt` covers that).

/** Oreo's bowl: [grams] is already in it. */
expect class Bowl(grams: Int) {
  /** The ordinary plan route: `grams + scoops * 10`. */
  fun fill(scoops: Int = 3): Int

  /** The legacy `suspend` member route: `grams + scoops * 100`. */
  suspend fun refill(scoops: Int = 2): Int

  /** The legacy `Flow` member route: emits `grams + 1` through `grams + drops`. */
  fun trickle(drops: Int = 2): Flow<Int>

  companion object {
    /** The companion route: a bowl already holding [grams]. */
    fun of(grams: Int = 25): Bowl
  }
}

/** The extension route: `grams + extra`, with the default on this `expect` only. */
expect fun Bowl.topUp(extra: Int = 15): Int

/** A sealed base whose arms live on the `actual` side. */
expect sealed class Meal {
  /** Declared once on the base; each arm overrides it without restating the default. */
  abstract fun portion(extra: Int = 4): Int
}

/** The ADR-074 `expect object` row, with a defaulted member. */
expect object Cupboard {
  /** `tins * 2`. */
  fun stock(tins: Int = 6): Int
}

/** A [Meal] arm, built on the `actual` side where the arms are declared. */
expect fun dryMeal(grams: Int): Meal

/** The stateless [Meal] arm. */
expect fun wetMeal(): Meal
