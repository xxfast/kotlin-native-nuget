# Pin the enum (and interface) exclude / cross-module-off hint branches

- ROADMAP: "Inferred: the enum gate's exclude/cross-module-disabled hint branch ADR-066's 2026-09-13 amendment added has no fixture; only the class/interface branches and the plain out-of-scope enum case are covered." (Phase 4 line 28, as of 2026-09-14)
- Researched: 2026-09-14, 5 of 15 minutes, verified by reading
- Restatement: forward. When a dependency enum is refused because its package is excluded or cross-module admission is off, the skip names that reason with the right remedy, and a Tier 1 cell pins it.
- Verdict: fix (fixture pins) plus one small real bug in the excluded-type hint. No ADR.

## Findings

- The "branch": `32d473f2` changed the enum arm of `ForwardBridgeTypeClassifier.classify` (`forward/ForwardBridgeTypeClassifier.kt:176-192`) to pass `unexportedDependencyRefusal = scopeRefusal(qualifiedName)`, as the class arm (`:304`) and interface arm (`:413`) already did. Before it, every unadmitted dependency enum got the `include(...)` hint even when the closure recorded `EXCLUDED_BY_CONFIG` or `CROSS_MODULE_ADMISSION_DISABLED` (`forward/ForwardReachabilityClosure.kt:248-260`; owner refusal propagates onto nested names at `:290-299`). All four refusal kinds share `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` (`ForwardDiagnostic.kt:418-424`); only sentence and hint differ.
- Coverage: class `EXCLUDED_BY_CONFIG` and `CROSS_MODULE_ADMISSION_DISABLED` are pinned by `tier1/Tier1ExcludedDependencyTypeHintTest.kt` cells 1 and 2 and by `ForwardSkippedCallableWarningTest.kt:57`, `:156`. Enum `NOT_INCLUDED` is pinned by `Tier1UndeclaredEnumSkipTest` (`Airwave`). Enum exclude, enum cross-module-off, nested enum under an excluded owner: **no cell**. The **interface** exclude and cross-module-off cases have no cell either; the ROADMAP line overstates.
- Hint text is fine for an enum: neither the sentences (`ForwardDiagnostic.kt:541-543`, `:600-602`) nor the hints (`:733-742`, `:747-755`) say "class".
- Real inconsistency (**inferred from code, not run**): the `EXCLUDED_DEPENDENCY_TYPE` hint still uses `substringBeforeLast('.')` (`ForwardDiagnostic.kt:734-736`) while the amendment switched the other two hints to `dependencyPackageName()`. For `exclude("dep.models")` propagated onto nested `Broadcast.AdBand`, the hint says `exclude("dep.models.Broadcast")`, an entry the author never wrote. For a type-level `exclude("dep.models.Broadcast")` (issue #53) that spelling is accidentally right; the hint cannot tell which entry matched because `Skipped` carries only the rendered name.

## Recommendation

Add three enum cells and one interface cell to `Tier1ExcludedDependencyTypeHintTest.kt` (or a sibling file). Cell 3's package-spelling assertion is expected red today; pick `dependencyPackageName()` for consistency and note the type-level shape. Carrying the matched exclude entry through `Skipped.detail` is the real fix but touches the planner; not this lane.

## Files touched

`tier1/Tier1ExcludedDependencyTypeHintTest.kt` (cells), `forward/ForwardDiagnostic.kt:734-736` (one helper swap), ROADMAP line 28 (and correct its "interface covered" claim).

## Sample test

Dependency jar `package dep.models; enum class Airwave { AM, FM }; class Broadcast { enum class AdBand { AM, FM } }`; fixture `package tier1.excluded; class Tuner { fun airwave(): Airwave; fun band(): Broadcast.AdBand }`.
1. `includePackages=tier1.excluded,dep.models`, `excludePackages=dep.models`: warning for `Tuner.airwave` contains `exclude("dep.models")` and "remove the exclude", not "add include(".
2. `run(emptyMap())`: warning contains "cross-module export is off" and "rootPackage", not "never declared as a C# enum".
3. Same config as 1 for `Tuner.band`: "remove the exclude", not the nested wording, and `exclude("dep.models")` rather than `dep.models.Broadcast`.
4. Interface twin: `interface Feed` in `dep.models`, `fun feed(): Feed`, cases 1 and 2.

## Open what-questions

- Which package spelling the excluded hint should carry for a nested type (package-level vs type-level exclude). Recommendation: `dependencyPackageName()`, pin it, note the other shape.
- `EXPECT_IN_DEPENDENCY` for an `expect enum class` not checked.
