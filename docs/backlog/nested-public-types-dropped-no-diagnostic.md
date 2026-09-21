# Nested public types are dropped with no diagnostic

> Discovered by the reverse dogfooding census over real published NuGet packages
> (`nuget-plugin/src/test/resources/dogfood/SUMMARY.md`), 2026-09-22.

`NugetMetadataReader/Program.cs:161-165` scopes the reader to top-level public types only ("v1 scope:
top-level public types only"), with a bare `continue` and no diagnostic emitted for the type it drops.
A nested public type's members are therefore invisible to the census (and to a consumer): not bound,
not named-skipped, simply absent.

Humanizer.Core is the extreme case: its `On.January.The1st` / `In.TheYear` date DSL is 47 nested public
types, verified in the committed golden
(`nuget-plugin/src/test/resources/dogfood/Humanizer.Core.2.14.1.census.json`:
`publicSurface.nestedTypes = 47`). Humanizer's bound share is 111 of 1488 public members (7.5 percent,
`SUMMARY.md`); most of the missing majority is inside these nested types, which the reader never
reports a skip for at all.

Fix shape (not yet built, two separable pieces):

- **Minimum**: emit a named diagnostic (e.g. `skipped_nested_type`) for every dropped nested public
  type so the census — and a real consumer — can see the gap instead of it reading as "member count is
  low" with no cause.
- **Binding**: extend the reader past the top-level-only scope to walk nested public types the same way
  it walks top-level ones. This is a separate, larger feature (naming, owner nesting on the Kotlin
  side) and should not block the diagnostic.
