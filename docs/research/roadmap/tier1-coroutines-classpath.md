# Tier 1 harness: a Flow fixture without the coroutines jar must fail loudly

- ROADMAP: "A Tier 1 fixture mentioning `Flow`/`StateFlow` with no `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` degrades silently ... make the omission loud, or default the resolution classpath when `coroutinesOnCompileClasspath` is true. On a method the drop is reported through the unnamed 'UNSUPPORTED type combination' sentence, while the same drop on a property names the offending error type" (Phase 4 line 33, as of 2026-09-14)
- Researched: 2026-09-14, 5 of 15 minutes, verified by reading source and KSP 2.3.10 bytecode
- Restatement: test-harness tooling plus one diagnostic. A Tier 1 cell that mentions `kotlinx.coroutines` without the jar fails at the harness precondition; a method whose type resolves to an error type is reported naming the type, as a property already is.
- Verdict: fix in two halves, (a) harness `require`, (b) processor detail arm. No ADR.

## Findings

- Two separate classpaths, one flag: `Tier1Harness.runIn` (`tier1/Tier1Harness.kt:180`) puts only `kotlinStdlib + libraries` on the KSP path; `coroutinesOnCompileClasspath` (`:288-291`, KDoc `:68-72`, `Tier1Classpath.kt:36-45`) adds coroutines to the `K2JVMCompiler` classpath only. Default `true` (`:42`, `:72`, `:156`, `:263`), so "on the compile classpath by default, never on the KSP path by default" is the trap.
- What `Flow` becomes without the jar (KSP bytecode): `KSErrorType.getDeclaration()` is a `KSErrorTypeClassDeclaration` (a `KSClassDeclaration`) with null `qualifiedName` and simple name `<ERROR TYPE: Flow>`. `ForwardBridgeTypeClassifier.classifyNonNullable` (`:83-91`) returns `Unsupported(rendered = "<ERROR TYPE: Flow>", reason = "local and anonymous declarations are not bridgeable")`. `skipReason()` fallback (`ForwardCallablePlanner.kt:3761`) is `UNSUPPORTED`; `skipDetail()` (`:3774-3778`) and the three inline detail chains (`:1839`, `:2172`, `:2209`) yield null.
- Why the routes differ: method `warnDroppedForwardCallables` (`NugetProcessor.kt:251`) calls `diagnosticReason(detail)`, which has no `UNSUPPORTED` arm (`ForwardDiagnostic.kt:532`) and falls to `genericSentence()` (`:492-493`) "its UNSUPPORTED type combination is not supported". Property `warnDroppedForwardProperties` (`NugetProcessor.kt:347-371`) takes the `else` branch "its type ${typeDescription} has no property getter or setter shape" and `diagnosticTypeName()` renders `rendered` (`ForwardDiagnostic.kt:968`); same at `CirClassTranslator.kt:340-347`.
- 22 test files carry a copy-pasted "Load-bearing" comment about the jar (e.g. `Tier1NestedTypesTest.kt:662-665`); `Tier1EntryPointCollisionTest.kt:147-157` records the incident. One live victim: `Tier1NullableParameterDiagnosticTest.kt:27-41` imports `Flow` with no `libraries`, so its `Flow<Int>?` positions classify as `Nullable(Unsupported(...))`, not `Nullable(SpecializedProtocol(Flow))`.

## Recommendation

(a) A `require` in `Tier1Harness.runIn` after the fixture files are written (`:159-166`): if any source contains `kotlinx.coroutines` and `libraries` lacks `Tier1Classpath.kotlinxCoroutinesCore`, fail with a message naming the flag's real scope. Not a default: `coroutinesOnCompileClasspath` is a compile-step seam `Tier1CoroutineFreeModuleTest` depends on, and defaulting the KSP path changes ~100 non-Flow cells' resolution environment silently. Key on the import text, not on `suspend`. Fallout: `Tier1NullableParameterDiagnosticTest` goes red and gets the jar; its assertions then run against a real `Flow<Int>?`. The 22 comments can go.
(b) `skipDetail()` and the three inline chains gain a last link returning `Unsupported.rendered` (through `Nullable`); `diagnosticReason` gains `UNSUPPORTED -> detail?.let { "its type `$it` is not supported" } ?: generic`. `ownsSentence` then claims the property route too; accept one sentence for both routes.

## Files touched

(a) `tier1/Tier1Harness.kt`, `Tier1NullableParameterDiagnosticTest.kt`, a small precondition test. (b) `forward/ForwardCallablePlanner.kt`, `forward/ForwardDiagnostic.kt`, one Tier 1 cell, ADR-064 amendment line (owns the sentence table). ROADMAP line 33.

## Sample tests

(a) `assertFailsWith<IllegalArgumentException> { Tier1Harness.run("import kotlinx.coroutines.flow.Flow\nfun f(): Flow<Int> = TODO()") }` plus a positive control with `libraries`.
(b) A stdlib-only source that classifies to `Unsupported` with a `rendered` (check `ForwardBridgeTypeClassifier.kt:141-313` for a reachable one); assert the warning contains "its type `" and not "UNSUPPORTED type combination".

## Open what-questions

- Is (b) in scope for this line or harness only? Recommendation: both, one lane, (b) is user-facing.
- One sentence for both routes? Recommendation: yes.
