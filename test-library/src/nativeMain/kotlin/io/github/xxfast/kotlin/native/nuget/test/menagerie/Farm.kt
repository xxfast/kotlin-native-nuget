package io.github.xxfast.kotlin.native.nuget.test.menagerie

import test.menagerie.IFeedable

// ADR-088: a bound C# interface (IFeedable, the ADR-070 stub) surfacing at ORDINARY FORWARD
// parameter/return positions, with original-type identity rather than the enum precedent's
// duplication. `Farm` is the class that needs the ADR-088 prerequisite: unlike `Goat`/
// `RingLeader`/`Tabby`/`Zookeeper`/`NoVacancy` in MenagerieSample.kt (ADR-085/086/087 fixtures,
// deliberately `private` so their `IFeedable`/`IPerformer`/`ITagged`/`IKeeper` supertype never
// needs to be public), `Farm` is a PUBLIC forward-exported class whose own public API takes and
// returns `IFeedable` directly.
//
// EXPECTED TO FAIL TODAY: the ADR-070 stub interface is generated `internal`
// (`NugetGenerateBindingsTask.kt:4402`), and a public declaration exposing an internal type in
// its signature is a Kotlin compile error (`EXPOSED_PARAMETER_TYPE` on `adopt`,
// `EXPOSED_FUNCTION_RETURN_TYPE` on `resident`). This file does not compile until the ADR-088
// visibility flip (pure interface stubs -> `public`) lands; that failure is the whole point of
// this fixture at this stage.
//
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)   TestLibrary.Menagerie.Farm
//       -> Kotlin test-library          Farm.kt (this file)
//         -> (reverse bridge, ADR-070)  test.menagerie.IFeedable
//           -> real C# TestDependency   Test.Menagerie.IFeedable

/**
 * A forward-exported class taking/returning [IFeedable] at ordinary parameter and return
 * positions: the ADR-088 v1 scope (non-nullable, ordinary function parameters/returns only, no
 * properties or collections). Named after the animal home a stray joins, mirroring
 * [test.menagerie.Sanctuary] on the reverse side. Two male cats, Oreo and Mylo, already own the
 * ADR-053/070/085 fixtures
 * elsewhere in this package, so `Farm`'s resident is a stray with no name yet until [adopt]
 * gives it one.
 */
class Farm {
  private var resident: IFeedable? = null

  /**
   * Forward PARAMETER position (C# -> Kotlin): stores whichever [IFeedable] the caller hands in,
   * Kotlin- or C#-implemented alike. Per ADR-088, a C#-implemented instance resolves through the
   * token probe to the original managed object; nothing is copied.
   */
  fun adopt(feedable: IFeedable) {
    resident = feedable
  }

  /**
   * Forward RETURN position (Kotlin -> C#): hands the stored [IFeedable] straight back. For a
   * C#-implemented resident this must be the SAME managed instance the caller passed to [adopt]
   * (ADR-088's identity promise for that origin, via the return-side GCHandle duplicate).
   */
  fun resident(): IFeedable =
    resident ?: error("Farm.resident() called before Farm.adopt()")

  /**
   * Dispatches into the stored resident ([IFeedable.legs]) rather than merely returning it, so a
   * test can assert the call actually reached the C#-implemented instance's own logic, not just
   * that the reference round-tripped unread.
   */
  fun residentLegs(): Int =
    resident?.legs ?: error("Farm.residentLegs() called before Farm.adopt()")
}
