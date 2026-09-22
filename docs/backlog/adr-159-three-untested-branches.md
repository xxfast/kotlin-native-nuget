# ADR-159: three branches with no fixture

Three branches of [ADR-159](../adr/159-async-member-on-kotlin-subclass.md)'s scope-ownership logic
were written deliberately but never exercised by a test:

1. `forwardDeclaresScopeMember`'s sealed-arm-ancestor arm (`ForwardScopeOwnership.kt`): the branch
   that answers the scope question for an **open sealed arm** serving as the base of an ordinary
   class, through `forwardArmFlowMethods`/`forwardArmFlowProperties` rather than the ordinary-class
   selectors.
2. `reProjectsKeptBaseMember`'s dropped-base return (`ForwardClassMembership.kt`): the branch that
   keeps an `override suspend fun` re-projected because its overridee sits on a *dropped*
   (unexported) base rather than a kept one.
3. `overridesDisposeAsync` with a concrete **open** class between an abstract owner and a further
   subclass: whether the middle class's `Dispose()`/`DisposeAsync()` render correctly when it is
   itself neither the owner nor a leaf.

No `test-library` fixture reaches any of the three. Each needs its own Tier 1 or fixture cell before
it can be called verified rather than "written to match the design."
