# A nullable return's skip diagnostic names the type it refused

- ROADMAP: line 46 as of 2026-09-29: "**A nullable return's skip diagnostic still names no type at all; only the input-position half, fixed for issue #131, can now name the offending parameter.**" ([details](../../backlog/skipped-unsupported-return-diagnostic-names-boolean-non.md))
- Researched: 2026-09-29, about 6 minutes of a 15 minute budget
- Restatement: forward (Kotlin declares). When the planner drops a callable because its nullable return type has no wire, the `SKIPPED_UNSUPPORTED_RETURN` build-log line (and the `<remarks>` paragraph that quotes its reason sentence) names the Kotlin type the author wrote, for example "the nullable return type `Crate<Int>?` has no wire ...", instead of "a nullable value at this position".
- Verdict: fix. No new ADR: a one-paragraph amendment to [ADR-064](../../adr/064-forward-unsupported-declaration-diagnostics.md), drafted below. It is the return-side mirror of the issue #131 amendment (`064-...md:906`), which added a dedicated `parameter` slot rather than reusing `detail`.

## Findings

1. **The backlog's premise is stale.** The backlog file and the #131 amendment (`docs/adr/064-forward-unsupported-declaration-diagnostics.md:950-953`) say there is no `BridgeType` to Kotlin-spelling renderer in the planner. Two exist now. **Verified** by reading:
   - `BridgeType.diagnosticTypeName()` at `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardDiagnostic.kt:1479-1519`, already used for the `RECEIVER_FAN_OUT` detail (`forward/ForwardCallablePlanner.kt:2201`) and collection components (`ForwardCallablePlanner.kt:3913-3924`).
   - `KSType.kotlinSpelling()` at `forward/ForwardLegacyRouteCollections.kt:1377-1393`: simple names, type arguments, every `?`, aliases expanded, `FunctionN` in arrow syntax.
2. **`diagnosticTypeName()` is the wrong source for this item.** It is lossy exactly where a nullable return reaches `NULLABLE`. **Verified** by reading:
   - `SpecializedProtocol` renders its model name (`ForwardDiagnostic.kt:1512`), which the classifier mints as `"generic declaration $qualifiedName"`, `"flow $qualifiedName"`, `"state flow ..."`, `"lambda ..."` (`forward/ForwardBridgeTypeClassifier.kt:315,321,786-790`). A `Flow<Int>?` would print `flow kotlinx.coroutines.flow.Flow?`.
   - `Collection` renders as the bare word `Collection` (`ForwardDiagnostic.kt:1507`).
   - `Unsupported` renders as its qualified name (`ForwardDiagnostic.kt:1518`, `rendered = qualifiedName` at `ForwardBridgeTypeClassifier.kt:199,905`), with no type arguments.
3. **`KSType.kotlinSpelling()` is the right source, and it is already test-pinned on a nullable return.** The legacy suspend route quotes `List<String>?` in a `SKIPPED_UNSUPPORTED_RETURN` via `legacyDescription() = kotlinSpelling()` (`ForwardLegacyRouteCollections.kt:958,559,599`), pinned by `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1LegacySuspendCollectionReturnTest.kt:194-198`. **Verified** by reading (test source; not re-run here).
4. **The KSType is reachable at the return skip site from `node`.** The one return-position `NULLABLE` producer on the planner route is the `resultShape == null` branch of `planOrSkipUnguarded` (`ForwardCallablePlanner.kt:2651-2669`), which receives `node: KSNode?` (`:2543`). Its callers pass (**verified** by reading `:978-2208`):
   - a `KSFunctionDeclaration` whose `returnType` is the classified result (`:1155`, `:1348`, `:1466`, `:1598`, `:2084`, `:2208`);
   - a `KSPropertyDeclaration` whose `type` is the classified result (`:1092`);
   - a class, arm or constructor whose result is a handle or `T`, never a nullable with no wire (`:978`, `:989`, `:1679`, `:1751`);
   - one covariant-override caller (`:1216-1221`) plans at the kept super's return type, not the method's declared one. For that case the node-derived spelling is the narrower declared type. Both are nullable, and the declared one is what the author wrote, so this is acceptable (**inferred**, no fixture).
   - `:1042` and `:1874` pass no node. Neither is known to produce a return `NULLABLE` (**inferred**). With no node the spelling is null and the shipped generic wording stays.
5. **Reusing the `detail` slot would silently move other routes.** **Verified** by reading:
   - The return skip site already fills `detail` for `Nullable(Unsupported)` with the inner qualified name and no `?`, through `unsupportedTypeDetail()` (`ForwardCallablePlanner.kt:2668`, `:4729-4730`, which calls `unwrapNullable()`). So keying `NULLABLE`'s wording on `detail` would print `kotlin.text.Regex` for a `Regex?` return.
   - The property route and the CIR abstract-member route pick up a reason's own sentence as soon as `ownsSentence(detail)` is true (`ForwardDiagnostic.kt:800-801`, `cir/CirClassTranslator.kt:378-393`, `forward/ForwardLegacyRouteCollections.kt:1310`), and both feed `skipDetail()`, which ends in `unsupportedTypeDetail()` (`ForwardCallablePlanner.kt:4713-4721`). A `detail`-keyed `NULLABLE` arm would swap a nullable-unsupported *property's* shipped "no property getter or setter shape" pair for a "nullable return type" sentence. That is the drift the 2026-09-19 amendment described as automatic (`064-...md:1590-1594`).
   - So a dedicated slot, mirroring #131's `parameter` (`ForwardCallablePlanner.kt:324-328`), is required, not just tidier.
6. **Only one call site renders a planner skip.** `NugetProcessor.kt:345-346` calls `diagnosticReason(dropped.detail, dropped.parameter)` and `diagnosticHint(dropped.detail, dropped.parameter, excludeEntries)`. Note that `excludeEntries` is passed **positionally** as the third argument. A new parameter must be added after it or passed by name. **Verified** by reading.
7. **The current wording.** **Verified** by reading:
   - The hint (`ForwardDiagnostic.kt:1241-1249`): with `parameter == null` it reads "expose a non-nullable wrapper, or split the member in two (one that reports whether there is a value and one that returns it), instead of a nullable value at this position".
   - The reason sentence (`ForwardDiagnostic.kt:1007-1009`): the generic "its NULLABLE type combination is not supported".
   - The reason sentence also reaches the generated C# `<remarks>` (`cir/CirSkipRemarks.kt:84`, "Not generated from Kotlin `$member`: $reason ($kind).").
8. **How sibling return skips name their type today.** They use two spelling styles. **Verified** by reading:
   - Qualified name: `UNSUPPORTED` (`ForwardDiagnostic.kt:984-985`, "its type `$detail` is not supported", where `detail` is a qualified name such as `kotlin.sequences.Sequence`, per `064-...md:1597-1600`), and the legacy stored-callback route (`ForwardLegacyRouteCollections.kt:222-228`, "its return type `$returnName?` is nullable, ...", qualified).
   - Simple `kotlinSpelling`: the legacy suspend route (finding 3), and the listener routes (`ForwardLegacyRouteCollections.kt:1362,1432-1433`, "returns `${returned.kotlinSpelling()}`").
   - Reason constant only: `VALUE_CLASS`, `THROWABLE`, `OBJECT` and friends still fall to `genericSentence()` (`ForwardDiagnostic.kt:783-784`).
9. **What pins the current text.** **Verified** by grep over `nuget-processor/src/test`, `IntegrationTests`, `docs`:
   - `ForwardSkippedCallableWarningTest.kt:117-121` pins a return `NULLABLE` with no slot to the generic sentence. It stays green if the new slot is null there.
   - `Tier1GenericReturnTypeArgumentTest.kt:115-131` asserts the live warning for `maybeCrateOfList` / `maybeCrateOfInt` (fixture `:80-82`, `Crate<List<Int>>?` and `Crate<Int>?`) `contains("NULLABLE")`. That substring comes only from the generic sentence, so the new sentence must keep a `(NULLABLE)` tag or this test must change.
   - `Tier1NullableCollectionReturnTest.kt:90` asserts the *absence* of `NULLABLE` for a different route. Unaffected.
   - No test, C# file, JSON or topic page quotes "nullable value at this position".
10. **Which return types still reach `NULLABLE`** (**verified** by reading `skipReason()` `ForwardCallablePlanner.kt:4578-4594` against `nullableResultShape` `:3327-3600`): a nullable whose inner type has no nullable result shape and is not undeclared, a bound interface or a non-bridgeable collection. That covers `Nullable` of:
    - `SpecializedProtocol` (generic class instance, `Flow`, `StateFlow`, lambda protocol);
    - `Unsupported` (not undeclared, for example an unmapped stdlib type);
    - `Throwable`, `Callback`, `ReturnedLambda`;
    - `ValueClass` over a non-pointer, non-scalar underlying;
    - `Unit`.

    The live fixture is `Crate<Int>?` (finding 9).

No spike was run. The load-bearing claim (that `KSType.kotlinSpelling()` of a resolved nullable generic return reads `Crate<Int>?`) is **verified by reading** the function (`simpleName` + resolved arguments + `?` when `isMarkedNullable`), and its nullable-return output is already Tier 1 pinned (finding 3). The sample test below is what proves it for this fixture. If it were wrong, the result would be a wrong string in a warning, not wrong generated code.

## Recommendation

Carry the return spelling on the skip entry the way #131 carries the parameter name.

1. Add `val returnType: String? = null` to `ForwardCallableCatalogEntry.Skipped` (`ForwardCallablePlanner.kt:313-351`). Document it as: set only at a return-position skip, and read only by `NULLABLE`.
2. At the return skip site (`ForwardCallablePlanner.kt:2651-2669`), when the reason is `NULLABLE`, set `returnType = node.declaredResultType()?.kotlinSpelling()`. `declaredResultType()` is a small private helper: a `KSFunctionDeclaration` gives `returnType?.resolve()`, a `KSPropertyDeclaration` gives `type.resolve()`, anything else gives null. Leave `detail` untouched.
3. Give `diagnosticReason` and `diagnosticHint` (`ForwardDiagnostic.kt:825`, `:1092`) a trailing `returnType: String? = null` parameter. Read it only in the `NULLABLE` arm, and only when `parameter == null`. Pass it by name from `NugetProcessor.kt:345-346`.
4. Wording, keeping the `(NULLABLE)` search tag the `RECEIVER_FAN_OUT` / `UNDECLARED_*` sentences keep:
   - reason: "its nullable return type `Crate<Int>?` has no supported wire (NULLABLE)"
   - hint: "the nullable return type `Crate<Int>?` has no wire at a return position; expose a non-nullable wrapper, or split the member in two (one that reports whether there is a value and one that returns it), instead"
   - with `returnType == null`, both keep the shipped text.

Price: 3 main files (`ForwardCallablePlanner.kt`, `ForwardDiagnostic.kt`, `NugetProcessor.kt`), 2 test files, 1 ADR amendment, 1 ROADMAP line and 1 backlog file deleted.

Alternatives rejected:
- **Build the spelling from `BridgeType` (`diagnosticTypeName()`).** It prints model constants for exactly the protocol types that reach `NULLABLE` (finding 2).
- **Reuse `detail`.** It silently rewrites the property and CIR routes' wording, and prints `?`-less inner names (finding 5).
- **Thread a `KSType` parameter through all 14 `planOrSkip` callers.** It is correct but touches every caller for a diagnostic string, and `node` already carries it at every caller that can produce a return `NULLABLE`.
- **Spell `KSType.toString()`.** KSP's `toString` format is unspecified across KSP1/KSP2 (**inferred**, not checked), and the repo already standardised on `kotlinSpelling()`.

### Draft ADR-064 amendment

```markdown
## Amendment (2026-09-29): a return-position `NULLABLE` names the type

Judgement: an **amendment**, not a new ADR. It closes the return half the issue #131 amendment left
open. Its premise ("no `BridgeType` to Kotlin-spelling renderer") no longer holds; the spelling comes
from the declaration instead, through the `KSType.kotlinSpelling()` the legacy routes already print.
No new kind, no new reason. Status stays Accepted.

`ForwardCallableCatalogEntry.Skipped` gains `returnType`, the Kotlin spelling of the declared result
(`KSFunctionDeclaration.returnType` or `KSPropertyDeclaration.type`, aliases expanded, every `?` kept),
set only by the planner's return skip for `NULLABLE`. `diagnosticReason` and `diagnosticHint` read it
for `NULLABLE` only, and only when no parameter is named. It is a dedicated slot and not `detail`,
because the property and CIR abstract-member routes adopt any reason sentence that `ownsSentence(detail)`
admits, and `detail` already carries the `?`-less inner name for a nullable unsupported type. With no
declaration in hand, the shipped wording stands.

    [nuget:SKIPPED_UNSUPPORTED_RETURN] Skipping tier1.catcam.maybeCrateOfInt: its nullable return type `Crate<Int>?` has no supported wire (NULLABLE). the nullable return type `Crate<Int>?` has no wire at a return position; expose a non-nullable wrapper, or split the member in two (one that reports whether there is a value and one that returns it), instead
```

The sample log line above is **illustrative**. The documenter should replace it with the real line from the green test run.

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardCallablePlanner.kt`: the `Skipped.returnType` field (`:313-351`), the return skip site (`:2651-2669`), and the `declaredResultType()` helper.
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardDiagnostic.kt`: the `NULLABLE` arms in `diagnosticReason` (`:1007-1009`) and `diagnosticHint` (`:1227-1249`, including the comment that says it "deliberately does not name a type"), plus the KDoc `@param` lines (`:817-821`, `:1088-1090`).
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/NugetProcessor.kt:345-346`: pass `returnType = dropped.returnType` by name.
- Tests:
  - `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1GenericReturnTypeArgumentTest.kt`: the new cell below.
  - `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/ForwardSkippedCallableWarningTest.kt:117-121`: add a sibling row with `returnType = "Widget?"`, and keep the slotless row.
- Docs:
  - the ADR-064 amendment;
  - delete ROADMAP line 46 and `docs/backlog/skipped-unsupported-return-diagnostic-names-boolean-non.md`;
  - delete this memo.
- No fixture, leak row, IntegrationTests or generated C# change. Only the `<remarks>` text of any existing return-`NULLABLE` skip moves. No checked-in snapshot quotes it (finding 9).

## Sample test

A Tier 1 cell, added to `Tier1GenericReturnTypeArgumentTest`, reusing its fixture (`:80-82`) and `skipDiagnostic` helper (`:213-220`). It fails today: the hint reads "instead of a nullable value at this position" and no line contains a backticked `Crate<...>?`.

```kotlin
@Test
fun `a nullable return skip names the refused type, in the sentence and the hint`() {
  val result = run()

  mapOf(
    "maybeCrateOfInt" to "`Crate<Int>?`",
    "maybeCrateOfList" to "`Crate<List<Int>>?`",
  ).forEach { (function, spelled) ->
    val diagnostic: String = skipDiagnostic(result, function)
    assertTrue(
      diagnostic.contains("its nullable return type $spelled has no supported wire"),
      "expected the reason sentence to name $spelled; got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains("the nullable return type $spelled has no wire at a return position"),
      "expected the hint to name $spelled; got: $diagnostic",
    )
    assertFalse(
      diagnostic.contains("a nullable value at this position"),
      "expected the unnamed wording gone; got: $diagnostic",
    )
  }
}
```

The existing `contains("NULLABLE")` assertion at `:127` stays green through the `(NULLABLE)` tag.

## Deferred scope

- **`Nullable(Unsupported)` at a return keeps `NULLABLE`.** For example an unmapped stdlib `Regex?`. That is the issue #54 trap in miniature: the non-null `Regex` is refused as `UNSUPPORTED` with ADR-151's stdlib hint, while the nullable one gets "expose a non-nullable wrapper". Re-attributing it is a reason change (`skipReason()` `ForwardCallablePlanner.kt:4578-4594`) with its own pins. Not this item. Named here so it is not lost; it gets no ROADMAP line unless the human asks.
- **The property route (`SKIPPED_UNSUPPORTED_PROPERTY`) and the CIR abstract-member route** keep their own type naming (`diagnosticTypeName()` at `cir/CirClassTranslator.kt:398,468`). They already name something, and neither goes through the planner return skip.
- **Spelling-style unification** across the shipped return diagnostics (finding 8). Out of scope.

## Open what-questions

1. **Simple or qualified spelling?** Should the named type be the simple `kotlinSpelling()` form (`Crate<Int>?`), or qualified (`tier1.catcam.Crate?`) to match `UNSUPPORTED`'s "its type `kotlin.sequences.Sequence`"? Recommendation: simple `kotlinSpelling()`. It keeps type arguments, which is what distinguishes `Crate<Int>?` from `Crate<List<Int>>?`, and it matches the other shipped nullable-return diagnostic (`List<String>?`, finding 3). Human decision: pending.
2. **Keep the `(NULLABLE)` tag?** Should the new reason sentence keep the tag? Recommendation: yes. It follows the `RECEIVER_FAN_OUT` / `UNDECLARED_*` search-key precedent and keeps `Tier1GenericReturnTypeArgumentTest.kt:127` unchanged. The #131 parameter sentence has no tag; adding one there is out of scope. Human decision: pending.
3. **Also name the type at the input position?** The input position already names the parameter. Recommendation: no. #131 judged the name enough there, and widening it moves pinned text for no new information. Human decision: pending.
