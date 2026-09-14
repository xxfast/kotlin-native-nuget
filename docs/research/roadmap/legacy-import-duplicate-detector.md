# Byte-identical cross-package legacy imports: document the Kotlin-side detector, keep `.distinct()`

- ROADMAP: "`csharpLegacy`'s `.distinct()` means two byte-identical cross-package legacy imports never reach `CONFLICTING_LEGACY_IMPORTS`; detection of that shape rests entirely on `assertMatches`' Kotlin-side multiplicity check instead, correct today but undocumented as the mechanism." (Phase 4 line 34, as of 2026-09-14)
- Researched: 2026-09-14, 3 of 15 minutes, verified by reading
- Restatement: forward ABI contract (ADR-055/117). The detector for two byte-identical legacy imports from different packages is documented and pinned.
- Verdict: document + pin. Do not drop `.distinct()`. No ADR.

## Findings

- `forward/ForwardAbiContract.kt:232` `collected.distinct()`, then `:235-244` groups by name and raises `CONFLICTING_LEGACY_IMPORTS` only where more than one signature survives. Pinned as desired by `ForwardAbiLegacyImportTest.kt:114` (`collapses identical duplicate imports of one entry point`). Reason (ADR-078 `:137-139`, commit `9cb4e5bba`): runtime helper imports render twice by design, `nuget_dispose` at `cir/CirFunctionRenderer.kt:18` and `:208`, `nuget_wrap_*` at `:23-38` and `:213-219`, in any file with both a `Func` and a `SuspendFunc` helper.
- Detector: `NugetProcessor.kt:1304-1324` checks `csharpLegacy` collisions first, then `assertMatches` with `ordinary + legacy.signatures`. `assertMatches` (`:134-135`, `:158-166`) raises one collision when either side has multiplicity, guard `DUPLICATE_KOTLIN_EXPORT` when only Kotlin is duplicated. The cross-package shape mints one `@CName` per declaration (`ForwardExportOwners.kt:67`, `:249-252`), so it always lands here, owners named off the index (`:164`).
- ADR-117 says this in Context (`:85-89`) and labels it "Inferred, not spiked, and moot" (`:315-319`). The gap is the KDoc at `csharpLegacy` `:204-210`, `:233-234` and `assertMatches` `:122-128`.
- Not pinned end to end: `Tier1EntryPointCollisionTest.kt:268` (sealed class in two packages, `loadstate_get_type` raw text at `CirSealedRenderer.kt:56`) asserts the collision and owners but not the guard phrase; `:129` asserts a guard phrase only for `DUPLICATE_CSHARP_IMPORT`.
- Dropping `.distinct()` would false-positive today on the runtime helper imports, need a `NUGET_RUNTIME_EXPORTS` filter duplicating `assertMatches:142-149`, invert the `:114` test, and change the user-visible phrase to "conflicting legacy imports" for imports that do not conflict.

## Recommendation

KDoc on `csharpLegacy` and `assertMatches` naming the Kotlin-side check as the detector and why `.distinct()` stays; one unit test chaining `csharpLegacy` into `assertMatches`; one guard-phrase assertion in the sealed Tier 1 cell; flip ADR-117 `:315-319` to verified and strike `:480-481`.

## Files touched

`forward/ForwardAbiContract.kt` (KDoc), `ForwardAbiContractTest.kt` (unit test), `tier1/Tier1EntryPointCollisionTest.kt:268` (assertion), ADR-117, ROADMAP line 34.

## Sample test

Unit: render `combine` twice via `legacyDeclaration`, `csharpLegacy(...)` has no collisions and one signature; `assertMatches(csharp = legacy.signatures, kotlin = [combine, combine])` yields one collision with guard `DUPLICATE_KOTLIN_EXPORT` naming both owners. Tier 1: `kspErrors.any { "loadstate_get_type" in it && ForwardAbiGuard.DUPLICATE_KOTLIN_EXPORT.phrase in it }`.

## Open what-questions

- The Tier 1 assertion is inferred until run; if `_get_type` turns out to have a structural composer, follow the observed guard (ADR-118 gate instruction 2).
