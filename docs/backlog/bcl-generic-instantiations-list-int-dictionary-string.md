# BCL generic instantiations (`List<int>`, `Dictionary<string,int>`) are a deliberate narrowing, not an oversight: their definition lives outside the bound…

> Extracted verbatim from `ROADMAP.md` (Phase 10: Reverse rich type support) in the 2026-08-31 roadmap slim-down.

BCL generic instantiations (`List<int>`, `Dictionary<string,int>`) are a deliberate narrowing, not an oversight: their definition lives outside the bound assemblies, so they have no extracted members to bind as a generic type. [ADR-072](docs/adr/072-closed-constructed-generics-in-kotlin.md) Decision 9 diagnoses them (`skipped_unbound_generic_instantiation`) instead of binding an unreadable handle; mapping them to a Kotlin collection idiom is the item directly below, which is the only item that can give `List<int>` a `count` and an iterator
