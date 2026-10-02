# ADR-192: Namespace groups with their own Kotlin package stay on `alias()`; a repeated `bind {}` keeps merging

## Status

Accepted

## Gate

The open questions were decided by a stand-in reviewer agent while the owner was away (2026-10-03), not by the owner. "Keep `alias` exact for now" is the decision most open to being overruled.

## Context

ROADMAP Phase 8 carries "Multiple `bind {}` blocks per dependency (distinct namespace groups with
different `packageName` values)". [ADR-044](044-nuget-dependency-dsl.md) (line 524) and
[ADR-047](047-per-package-namespace-filters-at-reader-cli.md) (line 415) both deferred it.

The consumer need: one NuGet package ships several C# namespaces, and the Kotlin author wants each
group in its own Kotlin package.

What the code does today, read at `dd1b74fe`:

- **Verified by reading.** A second `bind {}` runs its action on the same `NugetBindConfig`
  (`NugetDependency.kt:22`, `:31-36`). It merges. `NugetExtensionTest.kt:167-177` pins the merge for
  `includeNamespaces`, and `docs/topics/nuget-dsl.md:408` documents it.
  [ADR-180](180-gradle-dsl-on-property.md) line 105 decided it, for `publish {}`, `bind {}`,
  `dependency("X")` and `nuget("x")` alike.
- **Verified by `git show 0.8.0:.../NugetDependency.kt`.** In released 0.8.0 a second `bind {}`
  replaced the first (`bind = config`). No released version has ever treated two blocks as two
  groups.
- **Verified by test** (`NugetExtensionTest` "a second bind block with its own packageName overrides the first"). Two blocks that each assign
  `packageName` leave the last value. So the ROADMAP item's literal script binds both groups into
  the second block's package.
- **Verified by reading.** A namespace already maps to its own Kotlin package through
  `alias(csharpNamespace, kotlinPackage)` (`NugetBindConfig.kt:44-46`). `kotlinPackage()`
  (`NugetGenerateBindingsTask.kt:123-130`, alias lookup at `:128`) resolves alias, then `packageName`, then the sanitised
  package id. The fixture maps fourteen namespaces to fourteen packages this way
  (`test-library/build.gradle.kts:291-320`), and
  `NugetGenerateBindingsTaskTest.kt:1418-1432` covers two packages with a cross-package import.
- **Verified by reading.** The Kotlin package is a stub-only concern. `reverse-ir.json` carries no
  Kotlin package (`RirModel.kt`, `RirParsing.kt`: zero `packageName` hits). The register symbol is
  `nuget_{ns_snake}_{type_snake}_register`, built from the C# namespace (`RirBridging.kt:1603-1609`).
  `contractHash` hashes the type name and the registrable signatures (`RirBridging.kt:1440-1448`).
  `NugetGenerateShimsTask.kt` takes neither `packageNameOverrides` nor `namespaceAliases`. So one
  IR, one registration table and one hash per type already serve any number of Kotlin packages.
- **Verified by reading.** `includeNamespaces` and `excludeNamespaces` are prefix matches
  (`NugetMetadataReader/Program.cs:2629-2633`). `alias` is an exact map lookup
  (`NugetGenerateBindingsTask.kt:128`), and `docs/topics/nuget-dsl.md:395-398` documents it as "one
  specific C# namespace".

Constraints:

- `bind {}` is in the reverse direction, which [ADR-181](181-reverse-direction-opt-in.md) marks experimental and breakable in 1.x. Changing `alias` or the block rules later is therefore allowed, but still a breaking change for reverse consumers.
- GOALS: the DSL should feel native to a Gradle author. In Gradle a repeated configuration block
  configures the same object.

Prior art, to the depth that changes the decision:

- CocoaPods `pod("X") { packageName = "..." }` and a cinterop `.def` `package = ...` take one
  package per dependency. Neither has groups (inferred from the Kotlin docs, already recorded in
  ADR-044's prior-art section).
- Kotlin consuming Java keeps the foreign package one to one. A per-namespace mapping is the closer
  mirror of that than a per-group one.

## Alternatives Considered

### 1. No new DSL: `alias()` is the group mechanism, a repeated `bind {}` keeps merging (chosen)

```kotlin
dependency("Acme.Utilities", version = "2.0.0") {
  bind {
    packageName = "acme"
    alias("Acme.Utilities.Core", "acme.core")
    alias("Acme.Utilities.Math", "acme.math")
  }
}
```

- Pros: already shipped and covered by a fixture. No source change. ADR-180's merge rule stays
  uniform across all four blocks. Shortest script of the four alternatives.
- Cons: `alias` is exact, so a sub-namespace pulled in by an `includeNamespaces` prefix needs its own
  `alias` line or it lands in the `packageName` package. A group of N namespaces is N lines.

### 2. Each `bind {}` call is its own group

```kotlin
bind { packageName = "acme.core"; includeNamespaces("Acme.Utilities.Core") }
bind { packageName = "acme.math"; includeNamespaces("Acme.Utilities.Math") }
```

- Pros: the ROADMAP line's literal wording.
- Cons: reverses ADR-180's merge rule for one block out of four. Changes the type of the public
  `NugetDependency.bind` property (`explicitApi`, ADR-183). Needs new rules: overlapping prefixes,
  a block with no includes, two blocks with no includes, whether `excludeNamespaces` is per group.
  A convention plugin could no longer add to a script's `bind {}`. No shorter than alternative 1.

### 3. Keyed groups: `bind("acme.core") { includeNamespaces("Acme.Utilities.Core") }`

- Pros: consistent with ADR-180 (a keyed element merges by key, like `dependency("X")`). Additive,
  so it can land after 1.0.0 without breaking anything.
- Cons: a second way to say what `alias` says. Same new rules as alternative 2. No consumer has
  asked for it.

### 4. Make `alias` a prefix match, longest prefix wins

- Pros: matches `includeNamespaces`. Closes the sub-namespace gap in one function.
- Cons: a behaviour change to a documented rule: an existing `alias("Test", "x")` would start
  capturing `Test.Text`. Flattens a sub-namespace into its parent's package, which raises the odds
  of two same-named types meeting in one package, and that collision is silent today (see Decision).
  Three candidate rules exist: exact (today), prefix flattening, and prefix keeping the suffix so
  `A.B.C` lands in `x.c`. None is chosen.

## Decision

Alternative 1. The item closes without a DSL change.

- A repeated `bind {}` merges (ADR-180, unchanged).
- A namespace group gets its own Kotlin package through one `alias` per namespace.
- `alias` stays exact for now. Prefix matching is an open gap, not a rejection (ROADMAP Phase 8).
- Three pin tests and a docs update land with the close:
  - `NugetExtensionTest` "a second bind block with its own packageName overrides the first";
  - `NugetGenerateBindingsTaskWiringTest` "two bind blocks contribute both namespace aliases to nugetGenerateBindings": two `bind {}` blocks contribute two aliases to one
    `nugetGenerateBindings.namespaceAliases` entry;
  - `NugetGenerateBindingsTaskTest` "aliased namespaces share one kotlin package and unaliased ones use packageName".
- The docs say that two blocks assigning `packageName` leave the last one, and point to
  `alias`.
- Same-name collision, verified by reading, no fixture: two same-named types from two namespaces
  that resolve to ONE Kotlin package silently overwrite each other's generated file.
  `NugetGenerateBindingsTask.kt` writes with a plain `writeText` (~:7480-7484) and no duplicate-path
  check, and `qualifiedTypeNames` (~:3094-3112) only qualifies same names in different packages.
  It already applies to the default config, where every namespace flattens into one package, so it
  is an argument for care with prefix `alias`, not only against it. Recorded on the ROADMAP.

Bridge mechanism: none changes. One `nugetExtractApi`, one `reverse-ir.json`, one
`nugetGenerateBindings`, one output directory (`build/nuget-interop/kotlin`), with one directory per
Kotlin package under `nativeMain/`. All verified by reading (`NugetPlugin.kt:228-279`,
`NugetGenerateBindingsTask.kt:447-460`).

The three pin tests named in the Decision were added; `packageName` last-wins is verified by test, not inferred.

## Consequences

- No behaviour change, no semver impact.
- The deferral notes in ADR-044 (line 524) and ADR-047 (line 415) are superseded by this ADR.
- The cross-package resolver-import gaps on the bound-interface route and the generic-witness route
  (two ROADMAP Phase 9 lines, both inferred with no fixture) are unchanged. They already apply to
  any script with two aliases, so they limit this decision and do not block it.
- Deferred: keyed groups (alternative 3) stay available as an additive change if a consumer needs
  prefix-scoped groups.
- Not built: block-per-group (alternative 2). It would reverse ADR-180's merge rule; the reverse
  direction is experimental (ADR-181), so it can be revisited without a 1.0 freeze.
