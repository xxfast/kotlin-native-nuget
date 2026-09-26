package io.github.xxfast.kotlin.native.nuget.hidden

/**
 * The UNEXPORTED super-interface of `io.github.xxfast.kotlin.native.nuget.test.lineage.ShowCat`
 * (ROADMAP Phase 4, "`interface Derived : Base` flattens").
 *
 * Same recipe as [Nesting] and [Skiff]: the package is
 * `io.github.xxfast.kotlin.native.nuget.hidden`, outside `rootPackage` and not a subpackage of it,
 * so KSP never exports it. The ADR-066 reachability closure admits returns, parameters and type
 * arguments, never a supertype, and both members are `String`-typed so nothing here can drag this
 * interface into the export set.
 *
 * `ShowCat : Named, Pedigree` puts one kept and one dropped super in the same base list. The
 * decision (Step 2 gate, 2026-09-26, the ADR-101 mirror) is that `IShowCat` does not list an
 * `IPedigree` (there is none) and instead declares [breed] and [registry] itself, so a C#
 * implementer of `IShowCat` can satisfy the ADR-084 bridge that reads them. Today they vanish from
 * `IShowCat` with no diagnostic while the bridge still reads `impl.Breed`, which is CS1061.
 *
 * Oreo's papers say "Domestic Shorthair". Oreo disputes this.
 */
interface Pedigree {
  /** Re-homes onto `IShowCat` as a get-only `string Breed`. */
  val breed: String

  /** Re-homes onto `IShowCat` as `string Registry()`. */
  fun registry(): String
}
