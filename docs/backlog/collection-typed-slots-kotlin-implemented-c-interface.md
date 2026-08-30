# Collection-typed slots for a Kotlin-implemented C# interface.

> Extracted verbatim from `ROADMAP.md` (Phase 13: Reverse bidirectional — implementing C# contracts in Kotlin) in the 2026-08-31 roadmap slim-down.

**Collection-typed slots for a Kotlin-implemented C# interface.** Split out of the item above at [ADR-086](docs/adr/086-object-interface-slots-kotlin-bridge.md)'s own scoping gate, on sequencing rather than difficulty: the reverse direction cannot marshal a BCL collection (`List`/`Map`/`Set`) at *any* position today (`List<int>` is `skipped_unbound_generic_instantiation`; no `nuget_list_*`-style reverse helpers exist anywhere, the forward `NugetMarshal`/`nuget_list_*` machinery is forward-pipeline-only), so a collection-typed slot would have to invent the entire reverse collection vocabulary as a side effect of an interface feature. Blocked on the Phase 10 "BCL collection instantiations as Kotlin collections" item landing first; rides whatever wire that item chooses.
