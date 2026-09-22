# A three-level chain with an unexported middle base may re-project an `override suspend fun`

`Dinghy : Skiff() : Vessel()`, where `Skiff` is dropped (unexported, ADR-101) and `Vessel` is kept,
and all three declare the same `override suspend fun`: [ADR-159](../adr/159-async-member-on-kotlin-subclass.md)'s `reProjectsKeptBaseMember` (`ForwardClassMembership.kt:423-447`) decides
whether to skip re-projecting `Dinghy`'s override by checking whether the *overridee* it finds sits
on a dropped base. `baseClassOverridee` returns the **nearest** overridee, which here is `Skiff`'s
(dropped), so the check says "kept only when the overridee is on a dropped base" is satisfied and
`Dinghy` re-projects, even though `Vessel` (kept, two levels up) already carries the member and
would collide with `Dinghy`'s re-projection: `CS0108`.

Inferred from reading `ForwardClassMembership.kt:423-447`, no fixture reproduces it: nothing in
`test-library` declares a three-level chain with a dropped middle base and a repeated
`override suspend fun` at every level. Needs a fixture and, if confirmed, `reProjectsKeptBaseMember`
walking past a dropped overridee to the nearest *kept* one before answering.
