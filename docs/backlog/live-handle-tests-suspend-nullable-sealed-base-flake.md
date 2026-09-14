# `LiveHandleTests.Suspend_ReturningTheNullableSealedBase_ReturnsToBaseline` fails intermittently, leaking one handle.

**VERIFIED by execution** for the failure itself: this row failed with "expected 0 live handles after
10 crossings, got 1 (delta 1)" in one checkout, and reproduced when run in isolation there, so it is
not the shared-process flake CLAUDE.md already documents for `LeakTests`. The same row passed 36/36
in a sibling checkout's `verify` in the same hour, so the failure is intermittent rather than
deterministic, not a permanent regression.

**Inferred, not confirmed**, for the cause: either the null arm or the base-typed wrapper return on
the ADR-131 suspend sealed-base route mints a `StableRef` the disposer never sees, or the row is
timing-sensitive in a way unrelated to a real leak. Nothing yet distinguishes the two; the row needs
a repeated local run (not just one CI pass) to tell a genuine leak from a race in the harness itself.

Discovered alongside [ADR-066](docs/adr/066-forward-export-reachability-closure.md)'s 2026-09-14
amendment, while verifying the ROADMAP item it settled; unrelated to that amendment's own subject
(the nested-type owner climb).
