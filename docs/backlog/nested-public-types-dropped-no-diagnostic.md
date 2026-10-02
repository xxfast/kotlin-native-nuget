# Bind nested public types

> Discovered by the reverse dogfooding census over real published NuGet packages
> (`nuget-plugin/src/test/resources/dogfood/SUMMARY.md`), 2026-09-22. The reader now names every
> dropped nested public type with `SKIPPED_NESTED_TYPE` (shipped 2026-10-03); this file covers
> only actually binding them.

`NugetMetadataReader/Program.cs` scopes the reader to top-level public types only ("v1 scope:
top-level public types only"). A nested public type is skipped with `SKIPPED_NESTED_TYPE`, naming
the type and its declaring type, but its members are still not bound.

Humanizer.Core is the extreme case: its `On.January.The1st` / `In.TheYear` date DSL is 47 nested public
types, verified in the committed golden
(`nuget-plugin/src/test/resources/dogfood/Humanizer.Core.2.14.1.census.json`:
`publicSurface.nestedTypes = 47`). Humanizer's bound share is 111 of 1488 public members (7.5 percent,
`SUMMARY.md`); most of the missing majority is inside these nested types.

Fix shape (not yet built): extend the reader past the top-level-only scope to walk nested public
types the same way it walks top-level ones. This needs decisions on naming and on owner nesting on
the Kotlin side (a nested type's namespace comes from its outermost declaring type).
