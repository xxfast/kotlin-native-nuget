# Three leftover bare or wrapper interface spellings after ADR-133's amendment

- ROADMAP: "Inferred: still bare or wrapper-spelled after ADR-133's 2026-09-13 amendment: the ADR-039 add/remove pair site's bare `I$name`, a `suspend fun` returning `StateFlow<Interface>` (~:1652), a cross-namespace **top-level** interface generic bound, and two top-level interfaces sharing a simple name in different packages" (Phase 4, as of 2026-09-14)
- Researched: 2026-09-14, 5 of 20 minutes
- Restatement: forward. Four generated-C# spelling sites emit a bare or wrapper name where a qualified interface name belongs; the consumer gets compilable, correctly typed C# at each, or a named skip.
- Verdict: fix (a)(b)(c); strike (d) into `docs/backlog/two-exported-types-same-simple-name-different.md`. No ADR: every site is issue #41's "every render site goes through the one qualifier" rule applied with helpers ADR-133's amendment already introduced. Queued third on the `interface-parameter-reachability` worktree.

## Findings

| Site | file:line | Reachable? | Breaks compile? | Label |
|---|---|---|---|---|
| (a) ADR-039 add/remove pair `interfaceCsName = "I$ifaceName"` | `cir/CirClassTranslator.kt:3101`, rendered `CirClassRenderer.kt:809`; callback body class-typed args bare at `:3169` | Yes: `findInterfaceBridgePairs` (`exports/StoredCallbackExports.kt:19-58`) has no package or nesting gate; callers `:815`, `:1965`. Every fixture pair is same-package | CS0246 for a cross-package or nested listener (`CirRenderer.kt:11` emits only System usings, `CirTranslator.kt:770-778`); same-package harmless | verified by reading, CS codes inferred |
| (b) `suspend fun` returning `StateFlow<Interface>` | `CirClassTranslator.kt:1687` (`suspendStateFlowMembers`; the ROADMAP's "~:1652" is the sibling plain-async branch the amendment fixed): `qualifiedElementCsType` spells the ADR-040 wrapper, no `read:` | Yes, class and sealed-arm members (`:918`, `:1915`); top-level refuses Flow returns (`CirFunctionTranslator.kt:55-58`). No fixture (`cat/CatMoodTracker.kt:160` is `StateFlow<Cat>`) | No, wrong type: consumer sees the wrapper, contradicting ADR-040 | verified by reading |
| (c) cross-namespace top-level generic bound | `CirTypeMapping.kt:277` `legacyBoundInterfaceCsName` returns `"I$simpleName"` for a top-level bound; callers `CirClassTranslator.kt:1047`, `CirFunctionTranslator.kt:759`; the `else -> simpleName` class-bound arm has the same defect | Yes, `translateGenericClass` (`:1024-1100`) has no bound gate. Fixtures are same-package | CS0246 on `where T : IPet` cross-namespace | verified by reading |
| (d) two top-level same-simple-name interfaces on one `<Name>BridgeState` | `forward/ForwardInterfaceBridgePlanner.kt:130` | **Dead**: both interfaces' dispatch exports use `nativePrefix()` (`ForwardCallablePlanner.kt:927`), bare for top-level, so ADR-117's `ERROR_C_ENTRY_POINT_COLLISION` fires first (`NugetProcessor.kt:1304`, `ForwardDiagnostic.kt:344`) before any C# is compiled | never observed | verified by reading |

## Recommendation

- (a) spell through `classifier.legacyFlowElementInterface(type)?.csharpType` (`ForwardBridgeTypeClassifier.kt:435`, already `global::`-qualified and owner-chained) and route callback-body arg types through `qualifiedElementCsType` as the ADR-037 sibling does (`:3041`, `:3049`). `translateClass` already holds `classifier` and `context` (`:471-474`).
- (b) mirror the amendment's Flow-route move: `legacyFlowElementInterface` for the element, `legacyInterfaceElementReadArgument(iface, false)` into `CirMethod.flowElementRead` (`CirModel.kt:534`, exists), `CirConcurrencyRenderer.kt:150-158` appends `, ${method.flowElementRead}` as the fourth `KotlinStateFlow` ctor arg (ctor already takes trailing optional `read`, `CirFlowRenderer.kt:215`).
- (c) drop the top-level short-circuit so `legacyBoundInterfaceCsName` qualifies unconditionally, and send the class-bound arm through `qualifiedElementCsType`. Nothing in `nuget-processor/src/test` pins bare `where T : IPet` (only `Tier1NestedTypesTest.kt:788` pins the nested qualified form).
- (d) when the backlog's package-qualified export prefix fix lands, the BridgeState name must be package-qualified in the same change (ADR-133's nested move is the shape) or the collision goes live. A separate diagnostic would duplicate ADR-117.

All three live fixes touch `CirClassTranslator.kt`: one worktree, serial.

## Files an implementation touches

(a) `cir/CirClassTranslator.kt` (`translateInterfaceBridgeMethod` + two call sites), `tier1/Tier1SealedArmLambdaTest.kt:266` (`AddWatcher(IFeedWatcher listener)` becomes the qualified spelling), fixtures under `test-library/.../nested/`, `IntegrationTests/InterfaceBridgingTests.cs`. (b) `cir/CirClassTranslator.kt` (`suspendStateFlowMembers`), `cir/CirConcurrencyRenderer.kt`, fixture `nested/Aviary.kt`, `IntegrationTests/SuspendStateFlowTests.cs`, possibly a `LeakTests` row. (c) `cir/CirTypeMapping.kt`, `cir/CirClassTranslator.kt` (`translateGenericClass`), `cir/CirFunctionTranslator.kt` (`translateGenericFunction`), a new cross-package fixture, Tier 1 test. (d) `ROADMAP.md`, the backlog file.

## Sample tests

(a) `class Roost { fun addKeeper(k: Aviary.Keeper); fun removeKeeper(k: Aviary.Keeper) }` plus a cross-package pair; Tier 1 `assertContains(csharp, "public IDisposable AddKeeper(global::Interop.Aviary.IKeeper listener)")`; xunit subscribes a C# `Aviary.IKeeper` through `AddKeeper`, fires, asserts the callback ran.
(b) `suspend fun keeperReport(): StateFlow<Keeper>` on `Aviary`; Tier 1 asserts `Task<KotlinStateFlow<global::Interop.Aviary.IKeeper>> KeeperReportAsync()` and `read: static h => ...`; xunit `Assert.IsAssignableFrom<Aviary.IKeeper>(flow.Value)`.
(c) `class PetCrate<T : cat.Pet>` declared in `nested`; Tier 1 asserts `where T : global::Interop.Cat.IPet`; class-bound twin asserts `where T : global::Interop.Cat.Cat`.

## Deferred scope

(a) with an out-of-scope listener interface still prints an undeclared bare `IFoo`; a named skip needs the gate shared with `exports/InterfaceBridgeExports.kt` (ADR-064's hoisted-predicate pattern), separate change. Backlog `add-remove-subscription-route-silently-mis-handles.md` stays open. (b) nullable element stays deferred (ADR-068 v1). (c) changes shipped `PetBox<T> where T : IPet` output to the qualified spelling; no test pins the bare form.

## Open what-questions

- (b) runtime: does `KotlinStateFlow<Wrapper>.Value` resolve today through the `FromHandle` factory registry for a backing wrapper? Inferred yes; with the fix the explicit `read:` sidesteps it.
- (d) strike from ROADMAP into the backlog file, ship only (a)(b)(c). Decided 2026-09-14.
