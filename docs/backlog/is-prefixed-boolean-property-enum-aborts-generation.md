# An `is`-prefixed `Boolean` property on an enum aborts generation

**Symptom.** `enum class Mood(val isSecond: Boolean) { FIRST(false), SECOND(true) }` throws the
processor out entirely instead of generating (or naming a skip):

```
IllegalArgumentException: Forward ABI missing Kotlin export for mood_get_issecond;
expected mood_get_issecond(in int) -> bool
```

The same throw fires for a body property (`val isSecond: Boolean get() = ...`) on an ordinary
all-caps enum, so it is not specific to a constructor parameter or to a non-standard entry name.

**Cause.** `ForwardAbiContract.kt:180`, reached from `NugetProcessor.kt:1610`: the ABI contract
check expects a Kotlin export named after the property's `is`-stripped getter spelling
(`mood_get_issecond`) but the enum route's own export naming does not go through the same
`is`-prefix handling every other property route uses, so the two disagree and the contract check
fails hard.

**Coverage gap.** No `enum class` fixture in `test-library` has an `is`-prefixed `Boolean`
property. `Issue285Sample.kt` (issue #285) deliberately avoids adding one to its `Example` enum for
this reason.

**Discovered by:** the issue #285 research memo (`docs/research/roadmap/enum-entry-casing.md`,
finding 12, spikes S9a-S9c), while spiking whether enum members typed with `Boolean` interact with
the casing fix. Verified by spike (Tier 1 harness run in a scratch worktree), not re-verified during
the ADR-006 amendment's implementation. Unrelated to entry-name casing: an all-caps entry reproduces
it too.
