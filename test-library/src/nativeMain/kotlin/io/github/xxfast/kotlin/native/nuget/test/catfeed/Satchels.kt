package io.github.xxfast.kotlin.native.nuget.test.catfeed

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-174 amendment, the two hierarchy shapes beyond [Manger]'s, each with a generic implementer so
 * the consumer build compiles its explicit interface forwards.
 *
 * Generic super: [Satchel] is generic, so it never carries async members (ADR-174 ruling 3) and is
 * never made reachable. Its `fetch` is declared on `IHaversack`, the nearest carrying interface, as
 * an unexported super's member is; otherwise `IHaversack : IAsyncDisposable` leaves [Pannier]
 * without a `DisposeAsync` or forwards (CS0535).
 *
 * Diamond: [Scuttle] and [Funnel] both declare `fetch`, and [Grainbin] extends both. Both supers
 * are reachable (they carry the member), so `IGrainbin` redeclares `FetchAsync` with `new` (the
 * sync DIAMOND_OVERRIDE), and [Sack] forwards it under all three interfaces.
 *
 * Names dodge the sibling `catfeed` fixtures and the process-global C entry points (ADR-117).
 * Oreo's kibble is in the satchel; Mylo tips the grainbin over.
 */
interface Satchel<T> {
  suspend fun fetch(id: Int): T
}

/** Declares one member of its own; its async member is inherited from a GENERIC super. */
interface Haversack : Satchel<Int> {
  fun own(): Int
}

class Knapsack : Haversack {
  override suspend fun fetch(id: Int): Int {
    delay(10.milliseconds)
    return id * 10
  }

  override fun own(): Int = 5
}

/** The generic implementer of [Haversack]. */
class Pannier<T>(val load: T) : Haversack {
  override suspend fun fetch(id: Int): Int {
    delay(10.milliseconds)
    return id * 100
  }

  override fun own(): Int = 6
}

fun makeHaversack(): Haversack = Knapsack()

fun makePannier(): Haversack = Pannier("kibble")

interface Scuttle {
  suspend fun fetch(id: Int): String
}

interface Funnel {
  suspend fun fetch(id: Int): String
}

/** Two carrying supers both declare `fetch`: the diamond. */
interface Grainbin : Scuttle, Funnel {
  fun own(): Int
}

class Silo : Grainbin {
  override suspend fun fetch(id: Int): String {
    delay(10.milliseconds)
    return "silo-$id"
  }

  override fun own(): Int = 7
}

/** The generic implementer of [Grainbin]. */
class Sack<T>(val grain: T) : Grainbin {
  override suspend fun fetch(id: Int): String {
    delay(10.milliseconds)
    return "sack-$id"
  }

  override fun own(): Int = 8
}

fun makeGrainbin(): Grainbin = Silo()

fun makeSack(): Grainbin = Sack(3)
