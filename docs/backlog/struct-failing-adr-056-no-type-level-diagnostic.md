# A struct that fails ADR-056 gets no type-level diagnostic, and its own members are uncounted

> Discovered by the reverse dogfooding census over real published NuGet packages
> (`nuget-plugin/src/test/resources/dogfood/SUMMARY.md`), 2026-09-22.

When a C# struct fails every [ADR-056](docs/adr/056-csharp-structs-in-kotlin.md) shape rule (no
covering public constructor, private state the bridge can't reconstruct), `ExtractStruct` in
`NugetMetadataReader/Program.cs` returns `StructExtraction.Unsupported(...)` with no diagnostic naming
the struct itself. The struct shows up only indirectly, as `skipped_unsupported_struct` on *other*
types' members that mention it — never as a type-level skip, and never as a count of its own members.

Verified against the committed goldens: NodaTime's `Instant`, `LocalDate`, `Duration`, `Offset` and
nine more all fail this way. `NodaTime.3.2.2.census.json` records `types.struct: 0` for the whole
assembly while `publicSurface.structs: 13` — the reader-visible struct count is zero, but the
independent public-member probe counts 13 real public structs. `SUMMARY.md`'s
`skipped_unsupported_struct` tally for NodaTime is 125 (the cascade onto other members), with no single
diagnostic pointing at the 13 struct types responsible.

Fix shape (not yet built): emit a type-level diagnostic naming the struct and which ADR-056 shape rule
it failed (no public constructor covering every field, a private field with no corresponding public
property, etc.) at the point `ExtractStruct` gives up, instead of only the cascaded
`skipped_unsupported_struct` on unrelated members. That single diagnostic would let a maintainer rank
which ADR-056 shape extension to build next by real demand, the same way the unbound-type-reference
histogram already ranks BCL type mapping.
