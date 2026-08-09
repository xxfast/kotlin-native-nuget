package io.github.xxfast.kotlin.native.nuget.test.menagerie

import io.github.xxfast.kotlin.native.nuget.internal.nugetKotlinReleaseCount
import test.menagerie.Ferret
import test.menagerie.IFeedable
import test.menagerie.IKeeper
import test.menagerie.IPerformer
import test.menagerie.ITagged
import test.menagerie.Sanctuary
import test.wellness.EnergyLevel

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

// ADR-085 follow-up fixtures.
//
// (1) Multi-interface dispatch: `RingLeader` implements BOTH `IFeedable` and `IPerformer`, two
// admissible bound interfaces that are deliberately independent of each other (`IPerformer` does
// not derive from `IFeedable`). `nugetMintBridge` dispatches on `is` checks in RIR iteration
// order and ignores which interface the crossing position actually needs
// (`Any.nugetHandle(interfaceName)` receives the needed interface name but drops it on the floor
// before calling `nugetMintBridge(this)`), so a bridge minted for one position can be minted
// against the wrong interface's slot table. Mylo takes centre ring.
//
// (2) Cross-package enum slot import: `IPerformer.Energy` is `test.wellness.EnergyLevel`, a
// bound package independent of `IPerformer`'s own `test.menagerie`. The setter is the INBOUND
// crossing that needs the generated `IPerformerBindings.kt` slot body to import `EnergyLevel`.
private class RingLeader : IFeedable, IPerformer {
  override fun describe(): String = "Mylo the ringleader"

  override val legs: Int get() = 2

  override fun feed(food: String) {
    // Not exercised by these probes; present only so the IFeedable contract is fully satisfied.
  }

  override var nickname: String? = null

  override fun perform(): String = "Mylo takes a bow"

  override var energy: EnergyLevel = EnergyLevel.LOW
}

/**
 * [Sanctuary.introduce] (the [IFeedable] position) called with a Kotlin object that ALSO
 * implements [IPerformer]. Must dispatch into [RingLeader.describe]/[RingLeader.legs], not
 * [IPerformer]'s slot table.
 */
fun ringLeaderIntroduceViaFeedable(): String {
  val sanctuary = Sanctuary()
  return sanctuary.introduce(RingLeader())
}

/**
 * [Sanctuary.applaud] (the [IPerformer] position) called with the SAME dual-interface Kotlin
 * object type. Must dispatch into [RingLeader.perform], not [IFeedable]'s slot table.
 */
fun ringLeaderApplaudViaPerformer(): String {
  val sanctuary = Sanctuary()
  return sanctuary.applaud(RingLeader())
}

/**
 * [Sanctuary.recharge] writes then reads [IPerformer.energy] (cross-package enum setter AND
 * getter) on a Kotlin-implemented [IPerformer] from the C# side of the crossing.
 */
fun ringLeaderRecharge(): String {
  val sanctuary = Sanctuary()
  val result: EnergyLevel = sanctuary.recharge(RingLeader(), EnergyLevel.HIGH)
  return result.name
}

// Phase 13 Wave 2, item 1 (ADR-086 addendum / ADR-085 "Deferred" list): derived-interface
// flattening. `ITagged : IFeedable` today plans no Kotlin bridge factory at all -- a Kotlin class
// implementing `ITagged` is a named `skipped_kotlin_bridge`, so `nugetMintBridge` has no branch
// for it and `Any.nugetHandle("Test.Menagerie.ITagged")` falls through to its `error(...)`
// branch. Tabby (tabby cat, tagged) is the fixture.
private class Tabby : ITagged {
  override fun describe(): String = "Tabby the tagged tabby"

  override val legs: Int get() = 4

  override fun feed(food: String) {
    // Not exercised by these probes; present only so the IFeedable contract is fully satisfied.
  }

  override var nickname: String? = null

  override val tag: String get() = "tabby"
}

/**
 * [Sanctuary.showcase] takes an [ITagged]-typed parameter and calls BOTH [ITagged.tag] (own
 * member) AND [IFeedable.legs] (inherited member) on a Kotlin-implemented [ITagged]. The
 * flattened bridge this needs does not exist today: expect this to fail at the crossing rather
 * than return "tabby: 4 legs".
 */
fun kotlinTabbyShowcase(): String {
  val sanctuary = Sanctuary()
  return sanctuary.showcase(Tabby())
}

/**
 * The SAME Kotlin [Tabby] instance, crossing instead at the BASE [IFeedable]-typed position
 * ([Sanctuary.introduce]). A flattened `ITagged` bridge must satisfy both this and
 * [kotlinTabbyShowcase]'s crossing, per the item-1 brief.
 */
fun kotlinTabbyIntroduceViaFeedable(): String {
  val sanctuary = Sanctuary()
  return sanctuary.introduce(Tabby())
}

// Phase 13 Wave 2, item 2 (ADR-086): object- and interface-typed slots on a Kotlin-implemented
// bound interface. `IKeeper` plans no Kotlin bridge factory today either -- `Ferret`/`IFeedable`
// parameters, returns, and the nullable `Ferret?` property are all outside the v1 slot vocabulary
// (`isKotlinBridgeSlotType` only admits `Unit`/primitive/`Boolean`/enum/`String`/`String?`), so
// every `IKeeper` member is a named `skipped_kotlin_bridge` and minting hits the same
// `error(...)` fallback item 1 hits.
private class Zookeeper : IKeeper {
  /** Set by [groom]; proves the received [Ferret] parameter transferred ownership into Kotlin
   * (a borrowed handle could not be safely stored past the call that received it). */
  var stored: Ferret? = null
    private set

  /** Set by [pair]; proves the received [IFeedable] parameter resolves to the ORIGINAL Kotlin
   * object when it is Kotlin-implemented (the token-probe identity ADR-086 promises). */
  var lastPaired: IFeedable? = null
    private set

  override var favorite: Ferret? = null

  override fun groom(pet: Ferret): Ferret {
    stored = pet
    pet.feed("brush")
    return pet
  }

  override fun pair(other: IFeedable): IFeedable {
    lastPaired = other
    return other
  }
}

/**
 * [Sanctuary.keeperRoundTrip] drives every [IKeeper] crossing from the C# side of a
 * Kotlin-implemented interface: a bound-object PARAMETER+RETURN ([IKeeper.groom] -- ownership
 * transfers to Kotlin, [Zookeeper.stored] proves it, and the SAME [Ferret] returns to C#), a
 * nullable bound-object PROPERTY ([IKeeper.favorite], null then non-null), and a
 * bound-INTERFACE parameter+return ([IKeeper.pair]) with a REAL C# [Ferret] as the partner: per
 * ADR-086's identity table, C#-side identity IS promised for a C#-originated object, so the
 * embedded `pairedIsSamePartner` check inside [Sanctuary.keeperRoundTrip] must be true.
 */
fun kotlinZookeeperRoundTripWithFerretPartner(): String {
  val sanctuary = Sanctuary()
  val zookeeper = Zookeeper()
  val pet = Ferret()
  val partner = Ferret()
  val result = sanctuary.keeperRoundTrip(zookeeper, pet, partner)
  // ADR-086's identity table: a C# object crossing INTO a Kotlin slot parameter gets a fresh
  // wrapper per crossing, so `zookeeper.stored === pet` is deliberately NOT promised (the two
  // wrappers hold two GCHandles to the one C# object). What transfer ownership does promise, and
  // what the borrow alternative could not, is that the stored wrapper is still usable AFTER the
  // call that delivered it returned: a borrowed handle would have been freed at that point.
  // Rendered C#-style so the consumer test reads one uniform token set across all six fields.
  // mealCount (groom() fed the pet once) reads through the STORED wrapper's own handle and lands
  // on the same C# instance `pet` wraps, which is the substantive half of what `===` was reaching
  // for: same object, still alive, after the delivering call returned.
  val storedStillUsable: Boolean = zookeeper.stored?.mealCount == 1 && pet.mealCount == 1
  return "$result|${if (storedStillUsable) "True" else "False"}"
}

/**
 * Same [IKeeper.pair] crossing, but the partner is a Kotlin-implemented [Goat] instead of a real
 * [Ferret]. ADR-086's identity table promises only KOTLIN-side identity for this origin (the
 * token probe resolving the parameter back to the ORIGINAL [Goat], checked here via
 * [Zookeeper.lastPaired] `===`) -- C#-side `ReferenceEquals` is explicitly NOT promised for a
 * plain Kotlin return (a fresh bridge mints per crossing), so this probe asserts only the
 * Kotlin-side identity, never [Sanctuary.keeperRoundTrip]'s own `pairedIsSamePartner`.
 */
fun kotlinZookeeperRoundTripWithGoatPartner(): Boolean {
  val sanctuary = Sanctuary()
  val zookeeper = Zookeeper()
  val pet = Ferret()
  val goat = Goat()
  sanctuary.keeperRoundTrip(zookeeper, pet, goat)
  return zookeeper.lastPaired === goat
}

// Phase 13 Wave 2, item 3 (ADR-087 stage 2): catchable propagation from a Kotlin-implemented
// slot. Stage 1 (shipped) wraps every slot body in try/catch/rethrow that only NAMES the
// offending member before the process still terminates (SIGABRT) on an actual throw -- these
// fixtures exist for the C# tests that exercise them, but those tests are Skip'd until stage 2's
// error-envelope propagation lands (see `MenagerieRoundTripTests.cs`).
private class NoVacancy : IFeedable {
  override fun describe(): String = throw IllegalStateException("no vacancy")

  override val legs: Int get() = 4

  override fun feed(food: String) {
    // Not exercised; present only so the IFeedable contract is fully satisfied.
  }

  override var nickname: String? = null
}

/**
 * [Sanctuary.introduce] calls BOTH [IFeedable.describe] (throws here) AND [IFeedable.legs] on a
 * Kotlin-implemented [IFeedable]. Crosses the not-yet-shipped ADR-087 stage 2 envelope; today
 * this terminates the whole host process (stage 1's rethrow), so the C# test calling this is
 * Skip'd, never run by `scripts/verify.sh`.
 */
fun kotlinNoVacancyIntroduceThrows(): String {
  val sanctuary = Sanctuary()
  return sanctuary.introduce(NoVacancy())
}

/**
 * [Sanctuary.legsOnly] calls ONLY [IFeedable.legs] on the SAME kind of throwing implementation as
 * [kotlinNoVacancyIntroduceThrows]: a non-throwing sibling slot on an interface that also has a
 * throwing member must keep working today (no ABI change from stage 1) and must not gain a tax
 * once the stage 2 envelope lands. This test is NOT Skip'd.
 */
fun kotlinNoVacancyLegsOnly(): Int {
  val sanctuary = Sanctuary()
  return sanctuary.legsOnly(NoVacancy())
}

// Phase 13 Wave 3, item 1: bridge reuse per Kotlin object. Today `nugetMintBridge` mints a fresh
// bridge on EVERY crossing, so two crossings of the SAME Kotlin object give C# two distinct
// IFeedable instances even while the first bridge is still alive on the C# side (held here by
// `Sanctuary.remember`). Reuses `Goat` again -- Nibbles gets a third outing.

/**
 * The SAME Kotlin [Goat] instance crosses [Sanctuary.remember] TWICE, back to back. `Sanctuary`
 * holds the first reference (a live bridge) across the second crossing, so once bridge reuse
 * lands, [Sanctuary.rememberedAreSame] must report `true` — today it reports `false`, because
 * each crossing mints a fresh bridge regardless of the live one `Sanctuary` still references.
 */
fun kotlinGoatRememberedTwiceAreSame(): Boolean {
  val sanctuary = Sanctuary()
  val goat = Goat()
  sanctuary.remember(goat)
  sanctuary.remember(goat)
  return sanctuary.rememberedAreSame()
}

/**
 * Regression pin: TWO DIFFERENT Kotlin [Goat] instances must never report same, whether or not
 * bridge reuse lands. Passes today and must keep passing after — reuse is keyed on the Kotlin
 * object's identity, not merely on it being a [Goat].
 */
fun kotlinGoatsRememberedTwiceAreNotSame(): Boolean {
  val sanctuary = Sanctuary()
  sanctuary.remember(Goat())
  sanctuary.remember(Goat())
  return sanctuary.rememberedAreSame()
}
