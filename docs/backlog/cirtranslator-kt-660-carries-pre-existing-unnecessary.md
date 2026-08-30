# `CirTranslator.kt:660` carries a pre-existing "unnecessary safe call on a non-null receiver of type `KSType`" compiler warning.

> Extracted verbatim from `ROADMAP.md` (Tooling & Test Integrity) in the 2026-08-31 roadmap slim-down.

**`CirTranslator.kt:660` carries a pre-existing "unnecessary safe call on a non-null receiver of type `KSType`" compiler warning.** Unrelated to any specific feature; noticed and left as-is in build output while implementing [ADR-096](docs/adr/096-function-default-parameters.md). **Verified** from build output; not fixed, since it's an unrelated pre-existing nit and touching it was out of that feature's scope.
