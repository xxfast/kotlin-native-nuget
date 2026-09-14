# Pin the cold owner-scope collision arms; companion members are a gap

- ROADMAP: "The CS0542 arm of ADR-133's owner-scope collision check (a nested type named exactly like its owner) and the value-class/sealed-base/companion owner-scope collision arms are cold: no fixture reaches any of them, only the CS0102 member-name arm does." (Phase 4 line 31, as of 2026-09-14)
- Researched: 2026-09-14, 5 of 15 minutes, one `dotnet build` spike in scratch (verified)
- Restatement: forward. Every arm of ADR-133's owner-scope collision check has a Tier 1 cell that reaches it: nested type named like its owner (CS0542), and a nested type colliding with a member of a sealed-base owner, a sealed-arm owner, a value-class candidate, or a companion member.
- Verdict: fix (four pins, one four-line code fix for companions, one false positive to correct). No ADR.

## Findings

- The check is one function, `NugetProcessor.kt:226` `nestedOwnerScopeCollision()`: CS0542 arm `:229` (`owner.simpleName == name`), CS0102 arm `:230-234` (name in the owner's PascalCased `getAllProperties() + getAllFunctions()`). Call site `:992-1025` runs over candidates with `nestedDeclarationDeferral() == null`, emits `ERROR_CSHARP_SIGNATURE_COLLISION`, skips the nested type. "value-class / sealed-base / companion arms" are owner or candidate kinds passing through the CS0102 arm, not code arms.
- Spike (verified, scratch classlib): `class Owner { class Owner }` is CS0542; nested class + property, abstract sealed base + nested arm + `virtual Detail()`, nested record struct + `Weight()`, nested class + static `Config()` are all CS0102. `public interface ICage { public class Cage { } }` beside namespace-level `class Cage` compiles **clean**.
- Coverage: only the CS0102 property-on-class case is pinned (`Tier1NestedTypesTest.kt:353`). CS0542, sealed-base owner, sealed-arm owner, value-class candidate: cold, all reachable.
- **Gap, red today** (verified by reading): companion members. `nestedOwnerScopeCollision()` reads the owner's own members; companion members belong to the companion `KSClassDeclaration` and `CirClassTranslator.kt:940-965` folds them into the owner's C# class as statics (`const val` too, `:947-950`). `class Owner { class Config; companion object { fun config(): Config } }` emits `public class Config` plus `public static Config Config()`: CS0102 in the consumer with no diagnostic.
- **False positive** (verified by the spike): under an interface owner the CS0542 arm compares Kotlin simple names, so `interface Cage { class Cage }` would fire although `ICage.Cage` is legal C#. Post ADR-134 the comparison should be on the last two `nestedCsName()` segments (`ICage` vs `Cage`), which keeps the arm live for an eligible sealed interface (`Beam.Beam`, no `I`). Separate unspiked risk: nested `ICage.Cage` shadows the namespace-level ADR-040 wrapper `Cage` inside `ICage`'s body.
- "value-class owner" is unreachable: `unsupportedNestedOwnerReason()` `:186` defers it before the check. Not an arm; the ROADMAP means the candidate.
- Kotlin legality of `class A { class A }`: **inferred** (per-scope redeclaration rule, no JLS 8.1-style ban). The cell settles it; if kotlin-compile-testing rejects the source, delete the CS0542 arm instead of pinning it.

## Recommendation

One lane: four pinning cells, the companion fix (~4 lines: add the companion's public properties/functions to `memberNames`), the CS0542 comparison on C# segments with a not-an-error cell for `interface Cage { class Cage }`. Cosmetic: `nestedDeclarationKind()` `:137` has no value-class arm ("class" is rendered); CS0542 hint says "or the colliding member" when there is none.

## Files touched

`NugetProcessor.kt:226-234` (companion members, segment comparison), `tier1/Tier1NestedTypesTest.kt` (cells), ROADMAP line 31, ADR-133 (claim 3 promoted to verified; note the companion fix).

## Sample tests

- CS0542: `class Owner(val name: String) { class Owner(val level: Int); val label: String }`; error names `Owner.Owner`, outer `public class Owner` survives.
- Sealed base: `sealed class Purr { class Detail(val t: String); data class On(val level: Int) : Purr(); fun detail(): Detail }`; error names `Purr.Detail`; `detail()` still exported.
- Sealed arm: `data class On(val level: Int) : Purr() { class Trace(val at: Int); fun trace(): Trace }`.
- Value class candidate: `class Hamper(val id: String) { value class Weight(val grams: Int); fun weight(): Weight }`; no `record struct Weight` in the C#.
- Companion: `class Owner { class Config(val n: Int); companion object { fun config(): Config } }`; expects the CS0102 error, red today.
- Interface owner not-an-error: `interface Cage { class Cage(val n: Int); fun cageAt(): Cage }` compiles clean, no collision error.

## Open what-questions

- Fold the companion fix and the interface-owner false positive into this lane (recommendation: yes, small and adjacent) or split them.
