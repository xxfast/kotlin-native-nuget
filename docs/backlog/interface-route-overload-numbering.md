# The interface route (`interfaceEntries`, `ForwardCallablePlanner.kt:~640`) has no overload numbering at all.

> Extracted verbatim from `ROADMAP.md` (Phase 4: Rich type support) in the 2026-08-31 roadmap slim-down.

**The interface route (`interfaceEntries`, `ForwardCallablePlanner.kt:~640`) has no overload numbering at all.** `exportName = "${prefix}_${name}"` with no occurrence counter and no `_$n` suffix, unlike every other route ADR-090/ADR-095 numbered. Two same-name interface methods presumably reproduce the duplicate-symbol crash those ADRs fixed elsewhere, though this has not been spiked on the interface route specifically. **Verified in source**, not reproduced by a fixture. This is also why [ADR-096](docs/adr/096-function-default-parameters.md) deliberately synthesizes no defaulted-parameter overload for the interface route in v1: there is nowhere numbered to put one. Recorded once here rather than duplicated at each ADR that defers it.
