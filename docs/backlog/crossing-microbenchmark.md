# No benchmark exists for a bridge crossing, so every "if it measurably matters" deferral is unanswerable

> Added 2026-09-09 as part of the Performance & Resource Hygiene section.

**The roadmap already defers two decisions on a measurement nobody can take.** [ADR-106](../adr/106-uuid-mapping.md) defers the binary two-`INT64` `Uuid` wire "if the per-crossing hex-dash string allocation and parse ever measurably matters", and the object-identity caching-wrappers line is gated on "if profiling shows allocation overhead is significant". There is no profiling. The integration suite proves correctness only; nothing records how long a crossing takes or how much it allocates.

Proposed: a `Benchmarks/` BenchmarkDotNet project beside `IntegrationTests/`, consuming the same fresh-versioned `TestLibrary` fixture through `build/FixtureVersions.props` so it can never benchmark a stale package. One benchmark class per crossing family, each with a managed-only baseline so the number reported is the bridge's overhead and not the work: primitive in/out, `string` in/out, `List<int>`/`List<string>`/`List<Handle>` as parameter and as return at sizes 1/100/10k, `Uuid` round trip (answers ADR-106 directly), callback invoke, interface-bridge member call in each direction, a `Flow` of N items to completion, a suspend call. Report allocated bytes per op alongside mean time; the allocation column is the one that will move first.

Not a CI gate. Benchmark numbers on a shared runner are noise; the value is a committed baseline table in `docs/topics/` that a PR touching a marshalling path re-runs locally and updates. A CI job may run it in dry mode to keep the project compiling.
