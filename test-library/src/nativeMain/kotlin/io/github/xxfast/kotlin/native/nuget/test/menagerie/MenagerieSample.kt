package io.github.xxfast.kotlin.native.nuget.test.menagerie

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
