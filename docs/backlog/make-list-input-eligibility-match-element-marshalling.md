# Make list-input eligibility match element marshalling.

> Extracted verbatim from `ROADMAP.md` (Post-migration hardening) in the 2026-08-31 roadmap slim-down.

**Make list-input eligibility match element marshalling.** Every `List<T>`/`MutableList<T>` input, while `NugetMarshal.CreateList<T>` can box only a subset, so admitted element types such as `Byte`, `Short`, unsigned primitives, `Char`, enums, and nullable elements can fail at runtime. Compose element lowering from `BridgeType`, or reject unsupported element types during planning with a named diagnostic; add consumer coverage for every admitted element category.
