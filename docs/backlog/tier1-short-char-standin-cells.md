# `Tier1NamedSkipDiagnosticsTest` and `Tier1CompileCellsTest` cells that used `Short`/`Char` as stand-ins for "bridgeable but not wrappable" need a new home.

> Extracted verbatim from `ROADMAP.md` (Tooling & Test Integrity) in the 2026-08-31 roadmap slim-down.

**`Tier1NamedSkipDiagnosticsTest` and `Tier1CompileCellsTest` cells that used `Short`/`Char` as stand-ins for "bridgeable but not wrappable" need a new home.** [ADR-098](docs/adr/098-narrow-primitive-and-char-collection-components.md) moved both onto a nested collection, now the last component type left in that category; a future ADR-099 that closes nested-collection components (`ROADMAP.md:137`) removes that stand-in too, so whoever implements it will need a third shape for those cells. Discovered alongside [ADR-098](docs/adr/098-narrow-primitive-and-char-collection-components.md), noted here for whoever picks up ADR-099 next.
