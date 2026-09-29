package io.github.xxfast.kotlin.native.nuget.test.catfeed

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-174 on a NESTED interface: [Bowl]'s `suspend`, `Flow` and `StateFlow` members are declared on
 * `Pantry.IBowl`, and their `BowlNative` carrier nests inside `Pantry` beside the `Pantry.Bowl`
 * backing wrapper (ADR-133). The same async shapes as the top-level `Feed`: a suspend with a real
 * suspension point ([Bowl.fill]), a suspend with none ([Bowl.scoops]), a Flow method
 * ([Bowl.kibbles]), a StateFlow property ([Bowl.level]) and a DEFAULT Flow member
 * ([Bowl.doubled]).
 *
 * The factories are [open] and [openTin], not `bowl()`: a member PascalCasing to `Bowl` beside the
 * nested `Bowl` wrapper is CS0102. This file declares no top-level function, so no file class
 * `Pantry` competes with the class.
 *
 * Oreo's bowl is the ordinary one; Mylo insists on eating straight from the tin.
 */
class Pantry {
  interface Bowl {
    suspend fun fill(scoops: Int): String
    suspend fun scoops(): Int
    fun kibbles(): Flow<Int>
    val level: StateFlow<Int>
    fun doubled(): Flow<Int> = kibbles().map { it * 2 }
  }

  /** Returns the nested interface type around an ordinary implementer. */
  fun open(): Bowl = OreoBowl()

  /** Returns the nested interface type around a GENERIC implementer (ADR-174 Rule 7). */
  fun openTin(): Bowl = MyloTin("tuna")
}

/** The ordinary exported implementer of the nested interface. */
class OreoBowl : Pantry.Bowl {
  override suspend fun fill(scoops: Int): String {
    delay(10.milliseconds)
    return "oreo-$scoops"
  }

  override suspend fun scoops(): Int = 2

  override fun kibbles(): Flow<Int> = flowOf(1, 2)

  override val level: StateFlow<Int> = MutableStateFlow(5)
}

/**
 * The generic implementer: ADR-147 refuses its own async routes, so every async call through
 * `Pantry.IBowl` goes via the explicit implementations over `Pantry.BowlNative`.
 */
class MyloTin<T>(val flavour: T) : Pantry.Bowl {
  override suspend fun fill(scoops: Int): String {
    delay(10.milliseconds)
    return "mylo-$scoops"
  }

  override suspend fun scoops(): Int = 9

  override fun kibbles(): Flow<Int> = flowOf(3, 4, 5)

  override val level: StateFlow<Int> = MutableStateFlow(8)
}
