# `ForwardReachabilityClosure` has no edge from "a member returns a nested type" to "admit its owner", and never walks a declared nested type's own members either

**A dependency member whose return or parameter type is a nested type, with no *other* member
reaching the nested type's owner, admits nothing and stays a named skip, even though the member
names the nested type directly.** `ForwardReachabilityClosure.kt` is untouched by
[ADR-133](../adr/133-nested-types.md): a nested dependency type is declared only by riding along
once its owner is independently admitted through an ordinary member-type edge, since
`NugetProcessor.kt`'s `nestedCandidates` walk (`owner.nestedClassDeclarations()`) runs over
already-admitted owners, not over the closure's own edge set. Today's fixture cell works only
because `Newsroom.broadcast(): Broadcast` admits `Broadcast` on its own; `Broadcast.Schedule` is
then found as `Broadcast`'s own nested declaration, regardless of whether
`Newsroom.schedule(): Broadcast.Schedule` exists at all. If the only member referencing `Broadcast`
anywhere were `Newsroom.schedule(): Broadcast.Schedule` (no member returning `Broadcast` itself),
`Broadcast` would never be admitted and `Schedule` would never be declared, even though a member
names it directly.

A second, related gap: the closure never walks a nested dependency type's *own* member types once
it is declared (`walkClassMembers` is never called on a nested candidate). Concretely, if a
dependency module declared `Broadcast.Schedule.detail(): Broadcast.Detail`, a further nested type
reachable only through `Schedule`'s own member, `Detail` would never be walked or admitted, even
though `Schedule` itself is declared and `Detail` is exactly the kind of type ADR-133 now knows how
to nest.

Unnoticed because the `test-models` fixture (`Broadcast.kt`) always has some *other* member
returning the owner itself (`Newsroom.broadcast(): Broadcast`) alongside the member returning its
nested type, and no nested type in the fixture has a member of its own returning a further
dependency type.

Discovered alongside [ADR-133](../adr/133-nested-types.md), which shipped nested-type declaration
but no closure change at all. Verified by reading `ForwardReachabilityClosure.kt` (no diff) and
`NugetProcessor.kt`'s `nestedCandidates` walk; not reproduced by a fixture.
