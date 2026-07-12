package io.github.xxfast.kotlin.native.nuget.sample.household

import sample.household.Cat
import sample.household.Household
import sample.household.StrayCat
import sample.household.Toy

// ROADMAP "Tooling & Test Integrity" adversarial reverse fixture round trip. Every reverse
// fixture before this one had exactly one bound class per namespace and no exported
// nullable-returning function ever threw — that shallowness is why three latent bugs sat green
// in CI through the ADR-053 feature:
//   1. the metadata reader's positional (not SequenceNumber-keyed) parameter lookup, guarded
//      here by findToyLabel's several parameters ahead of its nullable annotated return;
//   2. the colliding top-level `internal var ctorFn` when a namespace binds 3+ classes, guarded
//      by this single namespace binding Cat, Toy, and Household together;
//   3. the forward two-call nullable P/Invoke's missing hasSyncErrorOut, guarded here by
//      fetchFavoriteToyLabelOrThrow — a Kotlin-*exported* nullable-returning function that
//      throws.
//
// A fixed household name keeps every function's own parameter list focused on the case it
// guards, rather than threading an extra unrelated string through every call.
private const val HOUSEHOLD_NAME = "Sunny Court"

/**
 * [Cat.describe] and [Toy.describe] deliberately share a method name across two different bound
 * types in this namespace — not an overload set, since each type declares it exactly once.
 */
fun describeCat(name: String, age: Int): String = Cat(name, age).describe()

/** See [describeCat]: [Toy.describe] shares its method name with [Cat.describe]. */
fun describeToy(label: String): String = Toy(label).describe()

/**
 * [Cat.favoriteToy] is a settable, nullable handle-typed property that cross-references [Toy]
 * from the same namespace (ADR-053 "rule 4": a handle-typed settable property is no longer
 * collapsed to a read-only `val`). Assigned, read back, then cleared to null.
 */
fun favoriteToyRoundTrip(name: String, age: Int, label: String): String? {
  val cat = Cat(name, age)
  cat.favoriteToy = Toy(label)
  val current: String? = cat.favoriteToy?.label
  cat.favoriteToy = null
  check(cat.favoriteToy == null) { "Cat.favoriteToy did not clear back to null" }
  return current
}

/**
 * Guards bug 1: [Household.findToy] has several parameters ahead of its nullable annotated
 * return. If the metadata reader's parameter lookup were still positional instead of
 * `SequenceNumber`-keyed, one of `minAge`/`indoorOnly`/`label` would silently bind under the
 * wrong name or type.
 */
fun findToyLabel(catName: String, minAge: Int, indoorOnly: Boolean, label: String): String? =
  Household(HOUSEHOLD_NAME).findToy(catName, minAge, indoorOnly, label)?.label

/**
 * Guards bug 3: an *exported* nullable-returning function that throws. The reverse-bound
 * [Household.fetchFavorite] itself never throws — reverse exception propagation out of a C#
 * thunk is Phase 11 (ADR-049 "let it crash"), so a throwing reverse call would fast-fail the
 * whole host process today, not just this call. The throw here is ordinary Kotlin validation
 * ahead of the reverse call, so this exercises the forward two-call nullable P/Invoke's
 * throwing path (the pattern bug 3 guards) without ever routing an exception back across the
 * reverse thunk boundary.
 */
fun fetchFavoriteToyLabelOrThrow(catName: String): String? {
  require(catName.isNotBlank()) { "catName must not be blank" }
  return Household(HOUSEHOLD_NAME).fetchFavorite(catName)?.label
}

/**
 * Oblivious-island round trip: [StrayCat] is compiled under `#nullable disable`, so
 * [StrayCat.announce] binds non-null per ADR-053 decision 1a. Passing a name that does not
 * match `recognisedName` makes the real C# method return a genuine null, which the generated
 * stub's fail-fast guard must turn into an `IllegalStateException` naming the member — instead
 * of corrupting memory or silently returning garbage.
 */
fun announceStray(name: String, recognisedName: String): String =
  StrayCat(name).announce(recognisedName)
