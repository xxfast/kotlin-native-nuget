package io.github.xxfast.kotlin.native.nuget.test.menagerie

import test.menagerie.IFeedable

/**
 * ADR-239: a bound C# interface ([IFeedable], ADR-088) as a callback payload and a callback result,
 * on the plain and the `suspend` callback routes. Both cross as the GCHandle the reverse pipeline
 * mints, so a C#-implemented resident reaches the C# lambda as the same managed object.
 */
class Pen {
  private var resident: IFeedable? = null

  /** Stores the resident the callbacks below are handed. */
  fun adopt(feedable: IFeedable) {
    resident = feedable
  }

  /** Hands the resident to [look] as a payload. */
  fun inspect(look: (IFeedable) -> Int): Int = look(resident ?: error("adopt first"))

  /** Takes a resident from [make] as a result, and reads its legs. */
  fun fetch(make: () -> IFeedable): Int = make().legs

  /** The `suspend` twin of [inspect]. */
  suspend fun inspectLater(look: suspend (IFeedable) -> Int): Int =
    look(resident ?: error("adopt first"))

  /** The `suspend` twin of [fetch]. */
  suspend fun fetchLater(make: suspend () -> IFeedable): Int = make().legs

  // The erased positions: a stored pair, a returned lambda and a lambda property, each crossing
  // the bound interface through `NugetMarshal`'s bound tables.
  private val keepers: MutableList<(IFeedable) -> Int> = mutableListOf()

  fun addKeeper(listener: (IFeedable) -> Int) {
    keepers.add(listener)
  }

  fun removeKeeper(listener: (IFeedable) -> Int) {
    keepers.remove(listener)
  }

  /** Hands the resident to every stored keeper; returns the sum of what they answered. */
  fun rollCall(): Int = keepers.sumOf { keeper -> keeper(resident ?: error("adopt first")) }

  /** A returned lambda taking the bound interface. */
  fun legCounter(): (IFeedable) -> Int = { feedable -> feedable.legs }

  /** A returned lambda handing the resident back out. */
  fun residentSupplier(): () -> IFeedable = { resident ?: error("adopt first") }

  /** A lambda property returning a bound interface, C# settable and Kotlin readable. */
  var onSwap: () -> IFeedable = { resident ?: error("adopt first") }

  /** Calls [onSwap] and reads the legs of what it handed back. */
  fun swapLegs(): Int = onSwap().legs
}
