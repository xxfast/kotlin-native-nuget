`Tier1Result.compiledClean` only reflects whether the generated Kotlin compiled; it says nothing
about whether KSP itself failed. Flipping it to strict (or asserting `kspSucceeded` on every cell)
turns four `Tier1NestedTypesTest` cells red, because they intentionally expect a named KSP error
and use `compiledClean` to mean "the Kotlin still compiles despite the skip":

- `a nested type colliding with a companion member is skipped with a named error`
- `a nested type named like its owner is skipped with a named error (CS0542)`
- `a nested value class colliding with a member is skipped with a named error`
- `a nested type colliding with a sealed base's member is skipped with a named error`

Move those four cells off `compiledClean` (assert `kspErrors`/`kspExitCode` directly instead), then
make `compiledClean` strict, or have every remaining cell assert `kspSucceeded` alongside it.

Measured 2026-09-28 by flipping the flag and running the full processor suite: 6 of 1414 tests
turned red; the other 2 were `Tier1InterfaceBridgeFactoryTest` cells fixed alongside ADR-084's
2026-09-28 amendment.
