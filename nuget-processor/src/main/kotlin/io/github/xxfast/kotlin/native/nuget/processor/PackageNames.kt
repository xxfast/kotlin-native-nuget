package io.github.xxfast.kotlin.native.nuget.processor

/**
 * Segment-bounded "under" test: `a.b` is under `a` and under `a.b`, never under `a.bc`.
 *
 * The one copy of the predicate that both halves of the forward pipeline ask: admission
 * (`PackageScope.covers` / `PackageScope.excludes`, `NugetProcessor.isExported`'s bound packages)
 * and naming (`mapPackageToNamespace`'s "under root"). It lives at the processor root, not in
 * `forward`, so `cir` can share it without `forward` gaining a dependency on `cir` or the reverse.
 *
 * Bounded on purpose. An unbounded `startsWith(prefix)` made `com.examples.x` under root
 * `com.example` strip a literal prefix and render a namespace out of half a package segment
 * (ADR-066 section 5 amendment, 2026-09-13), which no admission decision agreed with. Drift
 * between the copies is exactly the defect this single definition removes.
 *
 * The qualified-name half of `exclude` (issue #53) asks the same question of a declaration name,
 * whose segments are `.`-separated too, so it shares this predicate.
 */
internal fun isUnderPackage(packageName: String, prefix: String): Boolean =
  packageName == prefix || packageName.startsWith("$prefix.")
