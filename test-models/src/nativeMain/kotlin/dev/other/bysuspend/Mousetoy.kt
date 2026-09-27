package dev.other.bysuspend

/**
 * ROADMAP Phase 4 line 23 fixture: a dependency package reached **only** through `suspend`
 * members of `:test-library` (`errand/Errands.kt` and `errand/ErrandRunner.kt`). Nothing else in
 * the export scope mentions these two types, so if ADR-066's reachability closure never walks the
 * top-level `suspend fun` input set, neither type is admitted, neither is declared in C#, and the
 * suspend route spells them anyway.
 *
 * Admitted by package prefix (`admit("dev.other.bysuspend")`) rather than `include(...)`, so the
 * types are in scope and the fixture is about the closure reaching them, not about scope.
 *
 * Its own package rather than a new type in `dev.other.bykind` or `dev.other.bytype`: those
 * packages carry other features' assertions, and this one has to render at a namespace the
 * `suspend` owners do not live in (`TestLibrary.Dev.Other.Bysuspend` vs `TestLibrary.Errand`), so
 * a bare simple-name spelling of the type on the suspend route cannot resolve by accident.
 *
 * Oreo bats the mouse under the sofa. Mylo waits for someone else to fetch it back.
 */

/**
 * The handle arm: a dependency class returned from a top-level `suspend fun`, taken as a
 * parameter by another, and mentioned nowhere else. String-only constructor so C# can build one
 * and hand it back in.
 */
class Mousetoy(val squeak: String) {

  /** One member, so the admitted type is exercised rather than merely declared. */
  fun batted(by: String): String = "$by bats the $squeak mouse under the sofa"
}

/**
 * The value-class arm: a dependency **value class** returned from a top-level `suspend fun`. A klib
 * value class reports `Modifier.INLINE` rather than `VALUE` (ADR-066/ADR-154), which the Tier 1
 * jar cannot reproduce, so this bucket is only provable here.
 */
value class Chipcode(val digits: String)

/**
 * The `Flow` element arm, on a class (`ErrandRunner.yarn`). A SEPARATE type from [Mousetoy] on
 * purpose: a class member is walked by the closure through `getAllFunctions()`, so if the Flow
 * element were `Mousetoy` it would admit `Mousetoy` by the back door and hide the top-level gap.
 */
class Yarnball(val colour: String)
