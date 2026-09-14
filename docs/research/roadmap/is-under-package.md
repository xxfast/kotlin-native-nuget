# One `isUnderPackage` predicate for admission and naming

- ROADMAP: "The 'is this package under that prefix' predicate is hand-copied in four places (`PackageScope.covers`, `PackageScope.excludes` twice, `NugetProcessor.isExported`'s `matches`, and now `mapPackageToNamespace`)... a shared `isUnderPackage(packageName, prefix)` in ForwardPublishedScope.kt is the follow-up" (Phase 4 line 35, as of 2026-09-14)
- Researched: 2026-09-14, 3 of 15 minutes, verified by reading
- Restatement: forward. One predicate replaces the copies so admission and naming cannot drift; generated output byte-identical.
- Verdict: fix (refactor + one unit test). No ADR.

## Findings

Six textual copies at five sites, all `pkg == prefix || pkg.startsWith("$prefix.")` today; no divergence remains:

| # | Site | file:line |
|---|---|---|
| 1 | `PackageScope.excludes`, package half | `forward/ForwardPublishedScope.kt:24` |
| 2 | `PackageScope.excludes`, qualified-name half (issue #53) | `:25` |
| 3 | `PackageScope.covers` | `:31` |
| 4 | `NugetProcessor.isExported`'s local `matches` (bound packages, ADR-063) | `NugetProcessor.kt:604` |
| 5 | `mapPackageToNamespace`'s `relative` `when` (needs the remainder too) | `cir/CirTypeMapping.kt:250-252` |

Site 5 has an `if (rootPackage.isEmpty()) return rootNamespace` guard; sites 1-4 never see an empty prefix (`NugetProcessorProvider.kt:40-45` and `ForwardPublishedScope.kt:89-90` filter blanks). `CirTypeMapping.kt:245-249`'s comment hand-cites the copies and goes stale; `CirOrdinaryRendererTest.kt:1079-1080` KDoc cites a dead line number. No copies in `nuget-plugin`.

Pins: sites 1/3 by `ForwardPublishedScopeTest.kt:54-64` and `Tier1ExportScopingTest.kt:51, :71, :90, :110`; site 2 by `Tier1TypeLevelExcludeTest.kt:42-98` (`:98` is the only admission-side `a.bc` pin, qualified-name half); site 4 by `Tier1ExportScopingTest.kt:128, :152`; site 5 by `CirOrdinaryRendererTest.kt:1046-1132` (`:1092` is the naming-side `a.bc` pin) and `Tier1OutOfRootNamespaceTest.kt:51`. Gap: no package-half `a.bc` pin on the admission side.

## Recommendation

```kotlin
/** Segment-bounded "under" test: `a.b` is under `a` and under `a.b`, never under `a.bc`. */
internal fun isUnderPackage(packageName: String, prefix: String): Boolean =
  packageName == prefix || packageName.startsWith("$prefix.")
```
Sites 1-3 delegate (site 2 reuses it on the qualified name), site 4 deletes `matches`, site 5 gates on the predicate and strips inside. Byte-identical by construction. Adds the first `cir` to `forward` import (`ForwardBridgeTypeClassifier.kt:15` already imports the other way); a neutral home (`cir/CirTypeMapping.kt` or a tiny `PackageNames.kt`) avoids it, implementer's call. One unit test for the predicate (`a.b` under `a`, `a.b` under `a.b`, `a.bc` not under `a.b`, `a` not under `a.b`) covers every site once they delegate.

## Files touched

`forward/ForwardPublishedScope.kt`, `NugetProcessor.kt:604`, `cir/CirTypeMapping.kt:245-252`, `forward/ForwardPublishedScopeTest.kt`, optional `cir/CirOrdinaryRendererTest.kt:1079` KDoc, ROADMAP line 35.

## Sample test

Targeted run: `:nuget-processor:test --tests '*ForwardPublishedScopeTest' --tests '*CirOrdinaryRendererTest' --tests '*Tier1ExportScopingTest' --tests '*Tier1TypeLevelExcludeTest' --tests '*Tier1OutOfRootNamespaceTest'`.

## Open what-questions

- Helper home: `ForwardPublishedScope.kt` per the ROADMAP or a neutral file. Recommendation: neutral, to avoid the new import direction.
- Reuse for the qualified-name half: yes.
