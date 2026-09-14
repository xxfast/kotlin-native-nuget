# Stale nesting wording after ADR-133/134: "never declared in C#"

- ROADMAP: "Inferred: three strings describing nesting the way it worked before ADR-133 were not reached by ADR-066's 2026-09-13 amendment" (Phase 4 line 27, as of 2026-09-14)
- Researched: 2026-09-14, 5 of 15 minutes, all verified by reading `main` 112475a1
- Restatement: forward. Diagnostic sentences, hints, KDoc and comments still say a nested type is never declared in C#; a library author reading a skip warning gets wording that matches today's rule (nested types under supported owners are declared; only `enum class`, generic and `inner class` owners defer, per ADR-134).
- Verdict: fix (wording + pins). No ADR. Nine strings, not three: the interface and enum siblings say the identical false thing.

## Findings

Rules today (`NugetProcessor.kt:177-218`): a public nested `class`/`object`/`interface`/`enum class` is declared as `Owner.Nested` unless the candidate is `inner`, generic or a nested sealed hierarchy, or an owner in its chain is an `enum class`, generic, `inner`, a `value class`, a companion, or a kind other than class/object/interface. Collisions are `ERROR_CSHARP_SIGNATURE_COLLISION` (`:~1014`). So the classifier's membership gates fire only for a deferred or collided shape.

| String | file:line | False because | Replacement | Pin to move |
|---|---|---|---|---|
| `UNDECLARED_CLASS` sentence "is a nested class or object never declared in C#" | `forward/ForwardDiagnostic.kt:621-622` | contradicts ADR-133/134; the hint at `:893-899` already says it right | "is nested and no C# nested type is declared for it" | `ForwardSkippedCallableWarningTest.kt:182-183` (verbatim) |
| `SKIPPED_NESTED_DECLARATION` KDoc "the ADR-066 closure refuses to admit a nested dependency declaration, so nothing is ever generated for one" | `ForwardDiagnostic.kt:194-204` | the warning is emitted only for `nestedDeferred` (`NugetProcessor.kt:1024-1046`); `NESTED_DECLARATION` is a routing marker | KDoc naming the deferral shapes and `nestedDeclarationDeferral()`; collision is a different kind | none |
| classifier class-branch comment "declarable in neither module" | `forward/ForwardBridgeTypeClassifier.kt:263-278` | nested types are declared via the owner walk (ADR-066 edge A/B) | a nested name missing from `exportedObjectHandles` is deferred or collided; the owner walk is the sole declarer | none |
| classifier reason "a nested class/object is never declared in C#" | `ForwardBridgeTypeClassifier.kt:286-287` | same | "a nested class/object with no C# nested type declared for it" | none |
| `UNDECLARED_INTERFACE` sentence "is nested and never declared as a C# interface" | `ForwardDiagnostic.kt:617-618` | nested interfaces declared since ADR-133/134 | "is nested and no C# nested interface is declared for it" | `ForwardSkippedCallableWarningTest.kt:176-177` |
| classifier "a nested interface is never declared as a C# interface" + comment | `ForwardBridgeTypeClassifier.kt:392-400` | same | as above | none |
| classifier "a nested enum class is never declared as a C# enum" | `ForwardBridgeTypeClassifier.kt:197` | nested enums are declared | "a nested enum class with no C# nested enum declared for it" | none (`ForwardDiagnostic.kt:613-614`'s hedged sentence stays) |
| `UNDECLARED_ENUM` hint "only top-level enums are" | `ForwardDiagnostic.kt:872-876` | false | point at `SKIPPED_NESTED_DECLARATION`, keep "move it to the top level" | `ForwardSkippedPropertyWarningTest.kt:72` pins only "move it to the top level" |
| `UNDECLARED_INTERFACE` hint "(only top-level ones are)" | `ForwardDiagnostic.kt:881-885` | false | mirror the amended `UNDECLARED_CLASS` hint at `:893-899` | none |
| closure comment "Declining admission hands it to the classifier's membership gate" | `forward/ForwardReachabilityClosure.kt:264-270` | half-stale: it now hands it to ADR-133's owner walk; only a deferred one falls through | say so | none |

Already correct: `ForwardDiagnostic.kt:888-899`, `ForwardBridgeTypeClassifier.kt:176-180`, `:367-381`, `ForwardReachabilityClosure.kt:54-61`, `:152-186`.

Outside the processor, also stale (fixture KDoc lifted into Writerside pages): `Tier1InterfaceBridgeFactoryTest.kt:230`, `test-library/.../catcam/CatCam.kt:29`, quoted in `docs/topics/enums.md:516` and `interfaces-abstract-sealed.md:2449, 3757`.

## Recommendation

Take all nine processor strings in one lane (fixing only the three named leaves the `UNDECLARED_INTERFACE` sentence one line below still wrong). Leave the fixture KDoc for the documenter to decide, since it drags the topic snippets.

## Files touched

`forward/ForwardDiagnostic.kt` (:194-204, :617-622, :872-885), `forward/ForwardBridgeTypeClassifier.kt` (:197, :263-287, :390-400), `forward/ForwardReachabilityClosure.kt` (:264-270), `ForwardSkippedCallableWarningTest.kt` (:176-177, :182-183), ROADMAP line 27.

## Sample test

Run `:nuget-processor:test --tests '*ForwardSkippedCallableWarningTest' --tests '*ForwardSkippedPropertyWarningTest' --tests '*Tier1ReachabilityClosureTest'`; the two verbatim pins move to the new sentences.

## Open what-questions

- All nine or only the three named? Recommendation: all nine.
- Touch the fixture KDoc too? Recommendation: yes, but as the documenter's step since the topic pages quote it.
- Not verified: whether a module-local nested type whose owner is outside the ADR-063 package filter reaches `UNDECLARED_CLASS` with the nested wording; the new sentence is true either way.
