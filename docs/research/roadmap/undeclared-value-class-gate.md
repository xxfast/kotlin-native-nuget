# Gate `valueClass()` like `interfaceType()`: a nested value class under a deferred owner skips named

- ROADMAP: "Inferred: `ForwardBridgeTypeClassifier.valueClass()` spells `nestedCsName()` with no nested/membership gate (unlike `interfaceType()`), so a `value class` under a still-deferred owner (`inner class`, generic, or `enum class`) emits an undeclared struct name at a member position with no diagnostic" (Phase 4 line 29, as of 2026-09-14)
- Researched: 2026-09-14, 5 of 20 minutes, one Tier 1 spike in a throwaway worktree (verified)
- Restatement: forward. A member positioned with a `value class` nested under an owner ADR-134 still defers (`enum class` or generic; `inner class` cannot hold a nested class, Kotlin forbids it) is skipped named with struct wording instead of emitting an undeclared `readonly record struct` name.
- Verdict: fix. No ADR (mirrors three existing classifier branches); amend ADR-134's Consequences when shipped.

## Findings

- **Verified by spike**: fixture `Box<T> { value class Lid }`, `enum class Season { value class Almanac }`, `class Reader { fun lidOf(): Box.Lid; fun almanacOf(): Season.Almanac; fun yearOf(almanac: Season.Almanac): Int }`. Output: `SKIPPED_NESTED_DECLARATION` for both declarations (correct), then `public global::Interop.Box.Lid LidOf()`, `public global::Interop.Season.Almanac AlmanacOf()`, `public int YearOf(global::Interop.Season.Almanac almanac)` with no `readonly record struct` emitted anywhere and no member-level diagnostic. The consumer's `Interop.cs` cannot compile (exact code inferred: CS0426 when the owner is a declared enum, CS0234 when the generic owner is undeclared).
- Where (verified by reading): `forward/ForwardBridgeTypeClassifier.kt:493-522` `valueClass()` has no `exportedObjectHandles`, `parentDeclaration` or `scopeRefusal` test; straight to `BridgeType.ValueClass(csharpType = csharpTypeNameFor(declaration))` (`:516-521`), and `csharpTypeNameFor` (`:461-471`) spells `nestedCsName()` unconditionally. Contrast `interfaceType()` `:389-414`, class branch `:279-291`, enum branch `:169-204`.
- Value classes are not in `exportedObjectHandles` (`NugetProcessor.kt:1125-1136` builds it from classes, enums, interfaces, sealed, objects); `valueClasses` (`:1057-1058`) is never added. So the gate needs its own set.
- No downstream catch: `ForwardCallablePlanner.kt:3201-3203`, `:3230-3231` only ask whether the underlying is ordinary.
- `Tier1NestedTypesTest.kt`: `deferredSource` (`:87-104`) has no value class and no member typed with a deferred nested type; the "spelled nowhere" assertion at `:317-323` never exercised a classifier gate.
- Enum branch has no such hole (`:175` gates on `exportedObjectHandles`); its wording is ADR-133-stale, covered by `stale-nesting-strings.md`.
- **Verified**: Kotlin rejects a class nested in an `inner class` ("'Class' is prohibited here"), so the `inner class` owner arm is unreachable. Reachable deferred owners: generic, `enum class`, and a chain through either.

## Recommendation

A dedicated `exportedValueClasses: Set<String>` on `ForwardBridgeTypeContext`, wired from the `valueClasses` list at `NugetProcessor.kt:1138`. Do not widen `exportedObjectHandles` (it feeds `forwardSuperClass` at `ForwardClassMembership.kt:202` and the legacy `csTypeArguments` route at `CirFunctionTranslator.kt:150`, unverified there). Gate in `valueClass()` ahead of the underlying checks: not in the set and nested with no scope refusal gives `Unsupported(isUndeclaredValueClass = true)`; otherwise the dependency route with `refusedDependencyTypes`. A purely structural `nestedDeclarationDeferral()` test is smaller but misses the collision-skipped nested value class (`:1024-1026`) and scope-refused cases.

Wording. Sentence: "its value class type `X` is nested under a deferred owner and never declared as a C# record struct (UNDECLARED_VALUE_CLASS)". Hint: "value class `X` is nested and no C# `readonly record struct` is generated for it, so every member typed with it is skipped rather than emitted as a dangling reference; the SKIPPED_NESTED_DECLARATION warning on the declaration names which owner shape defers it (a generic or `enum class` owner), or move it to the top level of its file".

## Files touched

`forward/ForwardBridgeTypeClassifier.kt` (context field, gate), `forward/ForwardMarshallingModel.kt` (`isUndeclaredValueClass` beside `:253`), `forward/ForwardCallablePlanner.kt` (`isUndeclared()` `:3586`, `undeclaredTypeDetail()` `:3626`, reason mapping `:3737`, new `UNDECLARED_VALUE_CLASS` skip reason), `forward/ForwardDiagnostic.kt` (bucket `:463`, sentence `:620`, hint `:893`, KDoc `:693`; exhaustive `when`s force every site), `NugetProcessor.kt:1138`, `ForwardBridgeTypeClassifierTest.kt:25` (add the value-class fixture name to the set, plus a gate cell), `ForwardSkippedCallableWarningTest.kt` (pin like `:174-183`), `tier1/Tier1NestedTypesTest.kt` (cell), ROADMAP line 29, FEATURES.md, ADR-134 Consequences.

## Sample test

Extend `deferredSource`: `Box<T> { @JvmInline value class Seal(val tight: Boolean) }`, `enum class Season { @JvmInline value class Year(val n: Int) }`, `class Reader { fun sealOf(): Box.Seal; fun yearOf(year: Season.Year): Int }`. Assert `compiledClean`; `SKIPPED_NESTED_DECLARATION` for both (green today); `SKIPPED_UNSUPPORTED_TYPE` naming `UNDECLARED_VALUE_CLASS` for `sealOf` and `yearOf` (red today); `generatedCSharp` contains neither `Seal` nor `Year` (red today).

## Deferred scope

- `valueClass()` also has no dependency gate (a closure-refused top-level dependency value class would be spelled too); the membership set closes it for free, no fixture. Inferred.
- `unsupportedNestedOwnerReason()` (`NugetProcessor.kt:177-192`) has no arm for a nested sealed owner; a value class under `Owner.NestedSealed` may be declared under an owner that is never declared. Inferred, unverified, worth a probe.

## Open what-questions

- Drop the `inner class` arm from the ROADMAP wording (Kotlin forbids the shape). Recommendation: yes.
