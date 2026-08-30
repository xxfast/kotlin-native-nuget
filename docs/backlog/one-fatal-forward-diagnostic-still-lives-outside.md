# One fatal forward diagnostic still lives outside `ForwardDiagnosticKind`.

> Extracted verbatim from `ROADMAP.md` (Tooling & Test Integrity) in the 2026-08-31 roadmap slim-down.

**One fatal forward diagnostic still lives outside `ForwardDiagnosticKind`.** `CirFunctionTranslator.kt:91-99`'s `enumParamsUnsupported()` (a top-level function with enum parameters and a return shape that can't cast the enum ordinal back down) is a pre-existing `logger.error` fail-fast that predates and was deliberately left outside the [ADR-064](docs/adr/064-forward-unsupported-declaration-diagnostics.md) model: it isn't a signature collision, and it didn't fit cleanly into any of the seven named kinds. Small residual, not urgent; a future tidy-up is to give it its own `ERROR_*` kind so every fatal forward diagnostic routes through the one sink
