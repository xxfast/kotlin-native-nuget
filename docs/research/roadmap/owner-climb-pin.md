# ADR-066 edge A's unconditional owner climb is intentional: pin it

- ROADMAP: "Inferred: edge A of ADR-066's 2026-09-13 amendment (climb to a nested type's owner) is unconditional, so a nested type ADR-133 still defers still admits and declares its owner, a dead admission with no consumer of the nested type itself. Not reproduced by a fixture." (Phase 4, as of 2026-09-14)
- Researched: 2026-09-14, 4 of 20 minutes
- Restatement (as asked): forward. A dependency nested type under an owner ADR-133/134 still defers (`enum class`, generic, `inner class`) no longer admits and declares that owner for nothing. **Not satisfiable**: gating the climb removes the named skip.
- Verdict: pin and close, no ADR. In flight on `ir/owner-climb-pin` since 2026-09-14.

## Findings (all verified by reading)

- Edge A is unconditional: `ForwardReachabilityClosure.kt:285-286` runs `owner?.let(::visitDeclaration)` before the nested test, no `nestedDeclarationDeferral()` guard; the nested type itself is refused at `:288-301`. Edge B (`:184-186`) is gated.
- What the admitted owner becomes (`NugetProcessor.kt:953-969`): an `enum class` owner is a full `public enum` with entries and extensions; a generic owner takes the ADR-072 route through `allClasses` (`:1049`), `genericClasses` (`:1098`), `addGenericClassExports`, the same as any `fun box(): Box<Int>`; an `inner class` owner is itself nested, so only its top-level owner is admitted. Nothing dead.
- The named skip depends on the admission: `nestedCandidates` (`NugetProcessor.kt:980-982`) is built from the declared lists only; `nestedClassDeclarations()` (`:147-159`) descends into an enum owner explicitly "to be reported at all"; `SKIPPED_NESTED_DECLARATION` is emitted from `nestedDeferred` at `:1031-1044`. A non-admitted dependency owner puts nothing on that walk.
- `ForwardDiagnostic.kt:893-899`'s `UNDECLARED_CLASS` hint points the reader at that skip warning; gate edge A and the hint names a warning that never fires for dependency types.
- No deferred-owner cell with a dependency owner exists; `Tier1NestedTypesTest.kt:300-350` is module-local; `test-models/.../Almanac.kt` is the positive edge A cell.

## Recommendation

Gate nothing. Add the Tier 1 cell below, a one-line comment at `ForwardReachabilityClosure.kt:285`, and amend ADR-066 (`:346`, `:393-395`, "dead admission ... no fixture") to say unconditional on purpose, pinned.

## Files touched

`tier1/Tier1ReachabilityClosureTest.kt` (new cell), `docs/adr/066-forward-export-reachability-closure.md`, `ForwardReachabilityClosure.kt:285` (comment), `ROADMAP.md:26` (delete).

## Sample test

Beside `an owner reachable only through its nested type is admitted, and its dependency too` (`:309`): dependency jar `package dep.deferred; enum class Season { WINTER; class Almanac(val n: Int) }`, fixture `class Newsroom { fun almanac(): Season.Almanac = Season.Almanac(1) }`. Assert `compiledClean`; `public enum Season` declared; `Almanac` absent from the C#; no `newsroom_almanac` export; a `SKIPPED_NESTED_DECLARATION` warning naming `dep.deferred.Season.Almanac`; manifest names `dep.deferred.Season` only.

## Deferred scope

A generic dependency owner cell (`class Box<T> { class Lid }`) would also exercise `addGenericClassExports` on a klib-read class, which ADR-066 `:886` records as never exercised cross-module. Pre-existing ADR-066 gap, its own cell, not this one.

## Open what-questions

- Whether `SKIPPED_NESTED_DECLARATION` fires with `symbol = null` for the klib case (`NugetProcessor.kt:1037` handles `containingFile == null`): the cell answers it.
- Pin and close rather than gate. Decided 2026-09-14.
