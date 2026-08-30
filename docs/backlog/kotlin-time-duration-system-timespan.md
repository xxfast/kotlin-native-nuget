# `kotlin.time.Duration` → `System.TimeSpan`.

> Extracted verbatim from `ROADMAP.md` (Phase 4: Rich type support) in the 2026-08-31 roadmap slim-down.

**`kotlin.time.Duration` → `System.TimeSpan`.** Named alongside Instant in the original item as worth considering in the same design, but not required to ship it, and [ADR-076](docs/adr/076-instant-mapping.md) deliberately left it out of its fixture. `TimeSpan` is also 100ns ticks, so the mechanism ADR-076 built (a `BridgeType` variant that wires as `INT64` ticks with a required conversion on both sides) extends cheaply as a second known-scalar branch. No current consumer has asked for it.
