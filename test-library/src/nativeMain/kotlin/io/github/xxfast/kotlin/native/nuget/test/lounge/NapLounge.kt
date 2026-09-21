package io.github.xxfast.kotlin.native.nuget.test.lounge

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The scope-owner-across-an-inheritance-chain fixture. One coroutine scope per instance, owned by
 * the first class in the kept chain that projects an async member; every class below it reuses that
 * one scope and inherits `DisposeAsync`.
 *
 * Today the C# scope emission only fires on a base-less class, so most of this file generates
 * `Interop.cs` that does not compile (CS0103 on `GetOrCreateScope`/`_scopeHandle`, CS0108 on a
 * second `DisposeAsync`, CS0122 on the base's private scope factory, CS0535 on an abstract owner).
 * Every shape below is one of those, plus the one shape that compiles today and leaks instead.
 *
 * The base of the derived-owner shape is deliberately NOT called `Lounge`: the Kotlin package is
 * `lounge`, so a class of that name would be `TestLibrary.Lounge.Lounge` and collide with the
 * namespace whenever a consumer has `using TestLibrary;` in scope (LeakTests does). Oreo does not
 * care what the couch is called as long as it is warm.
 */
open class SunShelf(val spot: String) {
  fun describeLounge(): String = "$spot is warm"
}

/**
 * Shape: the first async member appears on the DERIVED class (the base has none). `NapLounge` is
 * the scope owner, so it must render `: SunShelf, IAsyncDisposable`, its own `_scopeHandle`, an
 * `internal IntPtr GetOrCreateScope()` and `DisposeAsync`. `SunShelf` must gain nothing at all.
 *
 * [rest] returns a String (marshalled result, needs conversion) and [restMinutes] returns an Int
 * (raw result), so both async return conversions ride on the owner.
 */
class NapLounge(spot: String) : SunShelf(spot) {
  suspend fun rest(cat: String): String {
    delay(10.milliseconds)
    return "$cat napped on $spot"
  }

  suspend fun restMinutes(): Int {
    delay(10.milliseconds)
    return 12
  }

  fun nappers(): Flow<String> = flowOf("Oreo", "Mylo")
}

/**
 * Shape: the BASE owns the scope and the derived class declares no async member of its own, with
 * both async routes on the base at once. [settle] is the suspend half, [watchers] is the Flow twin,
 * and [settleHeight] is a suspend fun with NO suspension point (it can complete before the P/Invoke
 * that started it returns, which is the window the tight-loop leak row drives).
 *
 * `WindowSeat` is the owner. `PaddedWindowSeat` must render no second `IAsyncDisposable`, no second
 * `_scopeHandle` and no `DisposeAsync` of its own, and its `override void Dispose()` must still
 * cancel and dispose the inherited scope: the Flow half of this shape compiles today and drops that
 * block, so a sync `Dispose()` after a collect leaks the base's scope.
 */
open class WindowSeat(val height: Int) {
  suspend fun settle(cat: String): String {
    delay(10.milliseconds)
    return "$cat settled at $height"
  }

  suspend fun settleHeight(): Int = height

  /**
   * Long enough that a sync `Dispose()` through the subclass always lands while it is still in
   * flight, which is what makes the cancellation observable: [settle] and [watchers] both finish
   * too fast to tell a cancelled scope from a completed one.
   */
  suspend fun doze(minutes: Int): String {
    delay(minutes.seconds)
    return "dozed for $minutes"
  }

  fun watchers(): Flow<String> = flowOf("Oreo", "Mylo")
}

class PaddedWindowSeat(height: Int) : WindowSeat(height) {
  fun cushion(): String = "$height cushioned"
}

/**
 * Shape: base and derived BOTH declare async members, and the derived one OVERRIDES the base's.
 * `Feeder` owns the scope; `TimedFeeder` reuses it.
 *
 * [fill] is overridden, so the override must NOT be re-projected on `TimedFeeder`: the base's
 * export calls `fill()` on a `StableRef<Feeder>`, and Kotlin's own dynamic dispatch reaches
 * `TimedFeeder.fill`. A C# consumer holding a `Feeder` reference therefore reads the override's
 * answer through the base's `FillAsync`.
 */
open class Feeder(val bowls: Int) {
  open suspend fun fill(): String {
    delay(10.milliseconds)
    return "$bowls bowls filled"
  }
}

class TimedFeeder(bowls: Int, val hour: Int) : Feeder(bowls) {
  override suspend fun fill(): String {
    delay(10.milliseconds)
    return "$bowls bowls filled at $hour"
  }

  suspend fun schedule(): Int {
    delay(10.milliseconds)
    return hour
  }
}

/**
 * Shape: an ABSTRACT scope owner with two concrete subclasses. The abstract class projects the only
 * async member, so it owns the scope and its `DisposeAsync` follows `Dispose`'s spelling: the
 * abstract owner declares `public abstract ValueTask DisposeAsync();` and each concrete subclass
 * renders `public override ValueTask DisposeAsync()` with the full drain body over its own
 * `Native_Dispose`. Two subclasses so the per-concrete-class override is not a single-instance
 * accident.
 */
abstract class Brusher {
  suspend fun groom(cat: String): String {
    delay(10.milliseconds)
    return "$cat groomed with ${tool()}"
  }

  abstract fun tool(): String
}

class MittBrusher : Brusher() {
  override fun tool(): String = "a brush"
}

class CombBrusher : Brusher() {
  override fun tool(): String = "a comb"
}

/**
 * The negative control: a class whose ONLY async member is refused. `Pair<String, Int>` is not a
 * bridgeable parameter, so [pair] is skipped named (`SKIPPED_UNSUPPORTED_INPUT`) and no
 * `*_pair_async` export exists. Nothing on this class uses a scope, so it must get no
 * `IAsyncDisposable`, no `_scopeHandle` and no `DisposeAsync`: the scope flag has to be derived
 * from what PROJECTED, not from a raw scan of the declarations. [cushions] is the member that
 * survives.
 */
class NapRegistry {
  suspend fun pair(entry: Pair<String, Int>): Int = entry.second

  fun cushions(): Int = 3
}
