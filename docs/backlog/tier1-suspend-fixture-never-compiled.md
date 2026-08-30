# No Tier 1 test had ever actually compiled a suspend fixture

> Extracted verbatim from `ROADMAP.md` (Tooling & Test Integrity).

**No Tier 1 test had ever actually compiled a suspend fixture before this fix landed** (`Tier1SuspendOnlyFileTest` only asserts on generated text, never compiles it), so the `Tier1CinteropStub` gap above went unnoticed for the suspend shape specifically: its `CFunction.invoke` stand-ins stopped at arity 2 + ctx, one short of the arity-3 + ctx overload a suspend export's completion callback needs. This fix added the missing overload to unblock its new `Tier1CoroutineFreeModuleTest` positive control (a suspend fixture that must actually compile), but the stub's arity coverage is still whatever the fixture in front of it happens to demand, not the systematic mirror of `CFunction`/`invoke` the item above still asks for. See [ADR-021](docs/adr/021-structured-concurrency.md)'s Implementation Addendum.
