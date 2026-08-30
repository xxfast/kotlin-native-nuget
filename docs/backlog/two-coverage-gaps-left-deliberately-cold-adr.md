# Two coverage gaps left deliberately cold by [ADR-098](docs/adr/098-narrow-primitive-and-char-collection-components.md).

> Extracted verbatim from `ROADMAP.md` (Tooling & Test Integrity) in the 2026-08-31 roadmap slim-down.

**Two coverage gaps left deliberately cold by [ADR-098](docs/adr/098-narrow-primitive-and-char-collection-components.md).** The `char` branch at `CirEnumRenderer.kt:30` needs an enum with a `Char` property to exercise, and no fixture declares one. Separately, `renderLegacyMethodNativeImport` and the `MutableStateFlow` setter line were already uncovered by the JVM unit suite before this change, and stay that way, since both only execute through a real `packNuget` round trip rather than the JVM-only test path. Discovered alongside [ADR-098](docs/adr/098-narrow-primitive-and-char-collection-components.md).
