# A `var` property on a generic class exports no setter, so it is silently read-only from C#.

> Extracted verbatim from `ROADMAP.md` (Phase 4: Rich type support) in the 2026-08-31 roadmap slim-down.

**A `var` property on a generic class exports no setter, so it is silently read-only from C#.** `CirClassTranslator.kt:915` hardcodes `setter = null` for every generic-class property regardless of Kotlin `val`/`var`; the C# side renders a get-only property (`public T Foo => ...`), with no diagnostic that a Kotlin `var` lost its mutability crossing the bridge. Verified by source reading only, not runtime-reproduced. Discovered alongside the nullable generic-property fix above (rides [ADR-083](docs/adr/083-nullable-collection-components.md)'s null-pointer decision), while reading the same property-translation path that needed the nullability branch.
