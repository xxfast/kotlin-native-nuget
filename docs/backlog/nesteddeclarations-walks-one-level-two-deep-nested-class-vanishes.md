# `nestedDeclarations` walks one level, so a two-deep nested class vanishes with no warning

**A declaration nested two levels deep (`class A { class B { class C } }`) gets no
`SKIPPED_NESTED_DECLARATION` for `C` at all.** `B` is warned; `C` is not, and no member typed with
`C` is warned `UNDECLARED_CLASS` either, since nothing enumerates it as a candidate in the first
place.

`NugetProcessor.kt`'s `nestedDeclarations` (around `:897-898`) collects candidates from
`owner.declarations` for each owner in `allClasses + valueClasses + sealedClasses + objects +
interfaces`, one level: every public direct child `KSClassDeclaration` of an owner. `B`'s own
children (`C`) are never visited, because `B` itself is never added to the owner set the walk reads
from, only to the candidate list the walk produces. The same gap applies to a nested sealed
interface's arms nested inside a nested interface.

It went unnoticed because no fixture nests three deep: `test-library`'s nested-declaration fixture
(`ProbeOuter`/`ProbeOuter.Nested`, `issue54/ProbeOuter.kt`) is exactly one level, the shape the
walk does handle correctly, and `Tier1SealedInterfaceTest` and `NestedClassGateTests.cs` both probe
one level too.

Discovered alongside [ADR-112](../adr/112-sealed-interface-mapping.md)'s 2026-09-10 amendment
(nested-arm single warning), while reading `nestedDeclarations` to place the new
`isArmOfIneligibleSealedInterface()` filter clause. Verified by reading; not reproduced by a
fixture.
