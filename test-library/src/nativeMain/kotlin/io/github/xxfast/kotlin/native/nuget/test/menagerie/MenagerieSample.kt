package io.github.xxfast.kotlin.native.nuget.test.menagerie

import io.github.xxfast.kotlin.native.nuget.internal.nugetKotlinReleaseCount
import test.menagerie.Ferret
import test.menagerie.IFeedable
import test.menagerie.ITagged
import test.menagerie.Sanctuary

// ADR-070: C#-declared interfaces surfacing in Kotlin as a Kotlin `interface`, backed by a
// generated handle implementation (the "Invoker" shape), the reverse mirror of ADR-040.
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)   MenagerieSample.*
//       -> Kotlin test-library          MenagerieSample.kt
//         -> (reverse bridge, ADR-070)  test.menagerie.{IFeedable,ITagged,Ferret,Sanctuary}
//           -> real C# TestDependency   Test.Menagerie.{IFeedable,ITagged,Ferret,Sanctuary}

/**
 * [Ferret] declares every [IFeedable] member with an identical public signature, so per
 * Decision 5 the bound Kotlin class declares the [IFeedable] supertype directly — no interface
 * plumbing needed to call [test.menagerie.Ferret.describe] itself.
 */
fun ferretDescribe(): String = Ferret().describe()

/** [test.menagerie.Ferret.legs] — pass-through int, needs no marshalling. */
fun ferretLegs(): Int = Ferret().legs

/**
 * [test.menagerie.Ferret.feed] takes a marshalled string parameter;
 * [test.menagerie.Ferret.nickname] is a nullable, SETTABLE string property — a getter AND a
 * setter slot (ADR-053 x ADR-070).
 */
fun ferretFeedAndNickname(food: String, nickname: String): String? {
  val ferret = Ferret()
  ferret.feed(food)
  ferret.nickname = nickname
  val current: String? = ferret.nickname
  ferret.nickname = null
  check(ferret.nickname == null) { "Ferret.nickname did not clear back to null" }
  return current
}

/**
 * [Sanctuary.star] returns [IFeedable]. Per Decision 3 the Kotlin value is always an
 * `IFeedableHandle`, never the concrete `Ferret` — [describe] still dispatches correctly through
 * the interface's own slot table.
 */
fun starDescribe(): String {
  val sanctuary = Sanctuary()
  val star: IFeedable = sanctuary.star()
  return star.describe()
}

/**
 * [Sanctuary.hiddenResident] returns an [IFeedable] whose runtime type (`Nocturnal`) is
 * `internal` and never bound. Verifies the mechanism: interface dispatch through a handle needs
 * no bound, public, or even named runtime type on the Kotlin side.
 */
fun hiddenResidentLegs(): Int {
  val sanctuary = Sanctuary()
  val hidden: IFeedable = sanctuary.hiddenResident()
  return hidden.legs
}

/**
 * [Sanctuary.introduce] takes an [IFeedable]-typed parameter. Passes a bound [Ferret] at an
 * interface-typed position (Decision 4's `nugetHandle()` lowering via `NugetHandleOwner`).
 */
fun introduce(): String {
  val sanctuary = Sanctuary()
  val ferret = Ferret()
  ferret.feed("egg")
  return sanctuary.introduce(ferret)
}

/**
 * [Sanctuary.featured] is a nullable, SETTABLE interface-typed property: set to a bound
 * [Ferret], read back through the interface, then cleared to null.
 */
fun featuredRoundTrip(): String? {
  val sanctuary = Sanctuary()
  sanctuary.featured = Ferret()
  val description: String? = sanctuary.featured?.describe()
  sanctuary.featured = null
  check(sanctuary.featured == null) { "Sanctuary.featured did not clear back to null" }
  return description
}

/**
 * [Sanctuary.flagship] returns [ITagged], a DERIVED interface (Decision 5's interface
 * inheritance case): both `tag` (declared on `ITagged`) and `describe`/`legs` (inherited from
 * `IFeedable`) must dispatch through the same handle.
 */
fun flagshipTagAndLegs(): String {
  val sanctuary = Sanctuary()
  val flagship: ITagged = sanctuary.flagship()
  return "${flagship.tag}/${flagship.legs}"
}

// ADR-085: Kotlin-implemented C# interfaces passed back to C#. `Goat` is a plain Kotlin class
// implementing `IFeedable` — no `NugetHandleOwner`, so today's `nugetHandle()` fallback hits its
// `error(...)` branch instead of minting a bridge. Two male cats, Oreo (black, white middle) and
// Mylo (brown and creamy, like the drink) already own the ADR-053/070 fixtures above, so this one
// gets its own cat-flavoured resident: Nibbles the goat.
private class Goat : IFeedable {
  var meals: Int = 0
    private set

  override fun describe(): String = "Nibbles the goat"

  override val legs: Int get() = 4

  override fun feed(food: String) {
    meals++
  }

  override var nickname: String? = null
}

/**
 * [Sanctuary.introduce] taking a Kotlin-implemented [IFeedable] (Decision 4's `nugetHandle()`
 * fallback, the ADR-085 insertion point): a String-returning member ([IFeedable.describe]) PLUS
 * an Int getter ([IFeedable.legs]), both dispatched back into Kotlin through a minted bridge.
 */
fun kotlinGoatIntroduce(): String {
  val sanctuary = Sanctuary()
  val goat = Goat()
  return sanctuary.introduce(goat)
}

/**
 * [Sanctuary.feedAnimal] calls [IFeedable.feed] (string PARAMETER, `Unit` return) on a
 * Kotlin-implemented [IFeedable]. Asserts the dispatch actually reached the Kotlin object by
 * reading `goat.meals` back on the Kotlin side afterwards — not just that the call didn't throw.
 */
fun kotlinGoatFeedCount(food: String): Int {
  val sanctuary = Sanctuary()
  val goat = Goat()
  sanctuary.feedAnimal(goat, food)
  return goat.meals
}

/**
 * [Sanctuary.rename] writes then reads [IFeedable.nickname] (nullable string, getter AND setter
 * slots) on a Kotlin-implemented [IFeedable] from the C# side of the crossing. Exercises both a
 * real value and the null branch, so the null-vs-empty-string distinction is observable.
 */
fun kotlinGoatRename(nickname: String?): String? {
  val sanctuary = Sanctuary()
  val goat = Goat()
  return sanctuary.rename(goat, nickname)
}

/**
 * [Sanctuary.featured] stores a Kotlin-implemented [IFeedable] and hands it back. Per ADR-085,
 * Kotlin-side identity is promised (unlike C#-side identity, which is not): the value read back
 * must be the SAME [Goat] instance, not merely an equal one — `===`, not `describe() ==`.
 */
fun kotlinGoatFeaturedIsSameInstance(): Boolean {
  val sanctuary = Sanctuary()
  val goat = Goat()
  sanctuary.featured = goat
  val featured: IFeedable? = sanctuary.featured
  return featured === goat
}

// ADR-085 lifetime: the bridge C# holds for a Kotlin object is released by the .NET GC, so the
// only way to observe a release is to count it. `nugetKotlinReleaseCount()` is the reverse mirror
// of the forward direction's `NugetBridgeState.ReleasedCount`; the counter moves when a collected
// bridge's SafeHandle calls the `nuget_kotlin_release` export.

/** How many Kotlin objects the reverse bridge has released so far, process-wide. */
fun kotlinBridgeReleaseCount(): Int = nugetKotlinReleaseCount()

// A Sanctuary that C# keeps a bridge alive through, held across two calls so the host can force a
// collection in between. Nulled by [kotlinGoatDropHeld] so the pair does not outlive its test.
private var heldSanctuary: Sanctuary? = null
private var heldGoat: Goat? = null

/** Stores a Kotlin-implemented [IFeedable] in C#, which keeps its own reference to the bridge. */
fun kotlinGoatStoreFeatured() {
  val sanctuary = Sanctuary()
  val goat = Goat()
  sanctuary.featured = goat
  heldSanctuary = sanctuary
  heldGoat = goat
}

/**
 * Reads the stored value back after the host has forced a collection: resolving it goes through
 * the LIVE bridge (its identity-token probe, and therefore its ctx StableRef), so a prematurely
 * released bridge cannot pass this.
 */
fun kotlinGoatStoredFeaturedIsSameInstance(): Boolean = heldSanctuary?.featured === heldGoat

/** Drops both held references so the stored bridge becomes collectible again. */
fun kotlinGoatDropHeld() {
  heldSanctuary = null
  heldGoat = null
}
