# Expand a typealias extension receiver on the Kotlin half too

- ROADMAP: "Inferred: a typealias extension receiver keeps the alias's own lowercased name in the C entry point while the C# extension class spells the expanded type; harmless today, but a nested-type alias would split the two spellings." (Phase 4 line 32, as of 2026-09-14)
- Researched: 2026-09-14, 2 of 15 minutes, verified by reading
- Restatement: forward. An extension function whose receiver is a `typealias` derives its C entry point and its C# extension class from the same expanded type, so an alias of a nested type cannot split them.
- Verdict: fix, no ADR: ADR-018 (`docs/adr/018-type-alias-mapping.md:47`, "transparent expansion at every `type.resolve()` site") already owns the decision; `extensionEntry` is a site that missed it.

## Findings

- `ForwardCallablePlanner.extensionEntry` (`forward/ForwardCallablePlanner.kt:1986-1997`) does `extensionReceiver.resolve()` with no `expandAliases()`, then `(declaration as? KSClassDeclaration)?.nativePrefix() ?: simpleName.lowercase()`; an alias's declaration is a `KSTypeAlias`, so the elvis yields the alias's own name (the comment says so). `extensionOwnerChain()` (`:1973`) has the same cast, so the ADR-095 counter scope is `""` and the plan symbol drops the owner chain.
- The `BridgeType` for the same receiver IS expanded (`ForwardBridgeTypeClassifier.kt:66`), so the export body and C# parameter type spell the expanded type; only the name string is stale.
- The C# extension class expands (`cir/CirTranslator.kt:566`, keys on `expandAliases().declaration.nestedCsName()`, ADR-126/133).
- Extension **properties** already expand (`forward/ForwardPropertyPlanner.kt:266`). So the function route alone is inconsistent; the ROADMAP overstates.
- Both halves read the entry point from one plan (`ForwardCirPlanProjection.kt:46`, `:214`), so it never breaks linking.
- No fixture has an alias receiver (`test-library/.../TypeAliases.kt` aliases are at return positions only). Zero wire change for the repo.
- Concretely for `typealias Bird = Aviary.Bird; fun Bird.sing()`: entry `bird_sing`, class `AviaryBirdExtensions` in `Aviary`'s namespace. A top-level `class Bird` in another package with `fun Bird.sing()` also exports `bird_sing`: `ERROR_C_ENTRY_POINT_COLLISION` where the expanded spelling gives `aviary_bird_sing` and no collision. Same package: a gratuitous `_2`. Its property twin is already `aviary_bird_get_pitch`.
- **Inferred**: KSP returns the `KSTypeAlias` as the declaration of an alias use (backed by the repo's own `expandAliases()`); JVM erases the alias in bytecode, ObjC/Swift Export attach to the expanded class.

## Recommendation

`.resolve().expandAliases()` in `extensionEntry` (`:1988`) and `extensionOwnerChain()` (`:1974`), rewrite the comment. Two lines plus comment.

## Files touched

`forward/ForwardCallablePlanner.kt`, a Tier 1 cell (new `Tier1AliasReceiverExtensionTest.kt` or in `Tier1NestedTypesTest.kt`), optional `test-library` `nested/Aviary.kt` alias + one xunit line, ROADMAP line 32, ADR-133 `:265-268` residual paragraph, ADR-018 one-line amendment.

## Sample test

```kotlin
package tier1.aliasreceiver
class Aviary { class Bird(val pitch: Int) }
typealias Bird = Aviary.Bird
class Bird2(val n: Int)
fun Bird.sing(): String = "la"
fun Bird2.sing(): String = "lo"
```
Kotlin contains `@CName("aviary_bird_sing")` and `@CName("bird2_sing")`, not `@CName("bird_sing")`; C# has `AviaryBirdExtensions`, no `BirdExtensions`, signature `Sing(this global::...Aviary.Bird receiver)`.

## Open what-questions

- Nullable alias receiver (`typealias MaybeBird = Aviary.Bird?`): should work through `expandAliases()`, no cell covers it.
- Wire change for an external library already shipping an alias-receiver extension (`score_double` becomes `int_double`): upgrade note line.
