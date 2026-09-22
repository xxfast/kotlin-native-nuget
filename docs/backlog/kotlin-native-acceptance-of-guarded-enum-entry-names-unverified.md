# Kotlin/Native's acceptance of `_1ST`, `_`, `__` as enum entry names is inferred, not run

**Symptom.** The issue #285 casing fix guards two enum-entry name shapes Kotlin accepts and C#
cannot spell (`_1ST` -> `_1st`, `_`/`__` -> `_`), and both guards are pinned by a Tier 1 test
(`Tier1EnumEntryCasingTest.kt`). Tier 1 compiles the fixture only through the K2 **JVM** frontend
(`Tier1Harness.run`), never through Kotlin/Native's own frontend or `packNuget`.

**Cause / gap.** Kotlin/Native shares the K2 frontend with the JVM target for parsing and name
resolution, so acceptance of `_1ST`/`_`/`__` as enum entry names is expected to hold, but nothing in
this repository's test suite runs a Kotlin/Native compile of a fixture using these names to confirm
it. `test-library` (the fixture module that does compile through Kotlin/Native and `packNuget`)
deliberately excludes these entries from `Issue285Sample.kt`, precisely because they were unverified
at the Kotlin/Native level and their pre-fix output is illegal C# (CS1001), which would have broken
`packNuget`.

**Coverage gap.** No `test-library` fixture, `scripts/verify.sh` run, or Kotlin/Native compile has
exercised an enum entry named `_1ST`, `_`, or `__`.

**Discovered by:** the issue #285 research memo (`docs/research/roadmap/enum-entry-casing.md`,
finding 8 and "Spikes run", S4/S5), verified only by the K2 JVM frontend; Kotlin/Native acceptance
stays inferred after the ADR-006 amendment shipped, since the amendment's own Tier 1 coverage uses
the same JVM-frontend harness.
