# A collision-skipped nested type may not be consistently re-gated at every member typed with it

> Discovered while pinning the four cold arms of [ADR-133](../adr/133-nested-types.md)'s
> owner-scope collision check (`owner-scope-collision-arms` lane, 2026-09-19). Inferred by reading
> `NugetProcessor.kt`; not probed against every owner shape.

`nestedOwnerScopeCollision()` skips the colliding nested type itself
(`ERROR_CSHARP_SIGNATURE_COLLISION`, the type is never declared), but a member elsewhere in the
module can still be typed with that same nested type. Whether every such member is then routed to
a named skip instead of dangling on an undeclared C# type is unchecked.

The interface-owner Tier 1 cell shows the working half: a member typed with a nested type skipped
for an owner-scope collision under an `interface` owner reaches `SKIPPED_UNSUPPORTED_TYPE`/
`UNDECLARED_CLASS`, naming the type rather than emitting a dangling reference. Whether the same
gate fires for every owner shape the collision check now covers (a plain `class`/`object` owner, a
sealed base, a sealed arm) is not verified either way; the four new pinning cells assert the
collision diagnostic and the absence of the declaration, not what happens at a member typed with
the skipped type.

Site: `nestedCollisions` and the `nestedCandidates` block in `NugetProcessor.kt`.
