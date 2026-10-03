# An unsupported struct's own public members are uncounted

> Discovered by the reverse dogfooding census over real published NuGet packages
> (`nuget-plugin/src/test/resources/dogfood/SUMMARY.md`), 2026-09-22.

The type-level half shipped 2026-10-03: a struct that fails the [ADR-056](../adr/056-csharp-structs-in-kotlin.md)
and [ADR-058](../adr/058-csharp-shape-b-structs-in-kotlin.md) shape rules (or is a `ref struct` or generic) gets one
`SKIPPED_UNSUPPORTED_STRUCT_TYPE` diagnostic naming it and the rules it failed. The original report said no
diagnostic named the struct. That was wrong: the reader already emitted an entry per struct, but under the member kind
`SKIPPED_UNSUPPORTED_STRUCT` with the struct's own name as `memberName`, so it read as a skipped member. NodaTime's
tally in the golden was 120 (not 125) before the split.

What remains: the struct's own public members. `ExtractStruct` returns `StructExtraction.Unsupported(...)`, so no
`RirStruct` exists, the members are never walked, and the build log and the census never say how many were lost.
NodaTime has 13 such structs (`Instant`, `LocalDate`, `Duration`, `Offset` and nine more).

Options, none chosen:

- **(a) One member-level entry per own public member.** No schema change, but it adds hundreds of warnings
  (NodaTime alone has 13 such structs).
- **(b) An additive `memberCount` on the type-level diagnostic, plus a census bucket.** It becomes a struct-only
  field on every `RirDiagnostic`, and `reverse-ir.json` is versioned.
- **(c) The count in the reason text, parsed by the census.** No schema change, but brittle.
