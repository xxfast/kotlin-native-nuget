package io.github.xxfast.kotlin.native.nuget.test.catfeed

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-174 amendment: an async member inherited from a super-interface is declared on the SUPER's
 * `I<Name>` and inherited through the derived one, as the sync route already does.
 *
 * Shape A: nothing types a position [Trough]; only [Manger] is reachable (through [makeManger]
 * and [makeNosebag]). The async members [Trough.fetch], [Trough.ticks] and [Trough.level] must
 * still be callable on an `IManger`, so `ITrough` is made reachable and declares them.
 * [Nosebag] is the generic implementer: ADR-147 refuses its own async routes, so it must get
 * explicit `ITrough` forwards and a `DisposeAsync`, or `IManger`'s `IAsyncDisposable` is CS0535.
 *
 * Shape E: both [Chute] and [Hayrack] are reachable and [Hayrack] restates `fetch` as an identical
 * override. It must be declared once, on `IChute`, or `IHayrack.FetchAsync` hides the inherited
 * one (CS0108, an error under `TreatWarningsAsErrors`).
 *
 * Type names dodge the process-global C entry-point space (ADR-117) and the sibling `catfeed`
 * fixtures. The file is `Mangers.kt` so the factories land on a static class `Mangers`, not on a
 * second `Manger`.
 *
 * Oreo eats from the trough in the stall; Mylo sticks his creamy brown face in the nosebag.
 */
interface Trough {
  suspend fun fetch(id: Int): String
  fun ticks(): Flow<Int>
  val level: StateFlow<Int>
  fun name(): String
}

/** Declares one member of its own; every async member it has is inherited from [Trough]. */
interface Manger : Trough {
  fun own(): Int
}

/** The ordinary exported implementer of the derived interface. */
class Stall : Manger {
  override suspend fun fetch(id: Int): String {
    delay(10.milliseconds)
    return "stall-$id"
  }

  override fun ticks(): Flow<Int> = flowOf(1, 2, 3)

  override val level: StateFlow<Int> = MutableStateFlow(1)

  override fun name(): String = "stall"

  override fun own(): Int = 11
}

/** The generic implementer of the derived interface (the CS0535 shape before the fix). */
class Nosebag<T>(val item: T) : Manger {
  override suspend fun fetch(id: Int): String {
    delay(10.milliseconds)
    return "nosebag-$id"
  }

  override fun ticks(): Flow<Int> = flowOf(7, 8)

  override val level: StateFlow<Int> = MutableStateFlow(4)

  override fun name(): String = "nosebag"

  override fun own(): Int = 22
}

/** Returns the derived interface around a [Stall]. Nothing in this file returns a [Trough]. */
fun makeManger(): Manger = Stall()

/** Returns the derived interface around a GENERIC implementer. */
fun makeNosebag(): Manger = Nosebag("oats")

/** Shape E's base: reachable in its own right through [makeChute]. */
interface Chute {
  suspend fun fetch(id: Int): String
}

/** Shape E's derived interface: restates [Chute.fetch] as an identical override. */
interface Hayrack : Chute {
  override suspend fun fetch(id: Int): String
  fun own(): Int
}

class Hayloft : Hayrack {
  override suspend fun fetch(id: Int): String {
    delay(10.milliseconds)
    return "hayloft-$id"
  }

  override fun own(): Int = 33
}

fun makeHayrack(): Hayrack = Hayloft()

fun makeChute(): Chute = Hayloft()
