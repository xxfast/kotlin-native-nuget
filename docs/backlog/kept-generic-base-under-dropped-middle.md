# A kept generic base under a dropped middle class fails generation

Given `Barge : Keel : Crate<Int>`, where `Keel` is outside the export root
([ADR-101](../adr/101-unexported-supertype-skip.md)), `Crate<T>` is kept, and `Keel` and `Barge` both
override the same `suspend fun` of `Crate`, generation stops with an internal error ("Cannot render
the base class of Barge: its base Crate declares 1 type parameter(s) but the declaration supplies 0
argument(s)").

- Cause: `forwardBaseSpelling` in `cir/CirClassTranslator.kt` (the `check` near line 775) finds the
  base's type arguments among `cls.superTypes`, which lists direct supertypes only. The kept base
  is reached through the dropped `Keel`, so no argument is found.
- Tried: reading the arguments from `getAllSuperTypes()` removes the error, but the generated C#
  then fails with `CS0506` on the override of `Crate<int>`'s member (verified, fix reverted), so
  the override projection for this shape also needs a decision. This is the generic-kept-base half
  of `reProjectsKeptBaseMember`'s dropped-base guard, which has no cell for that reason.
- Coverage gap: `Tier1ScopeOwnerChainBranchesTest` pins the non-generic shapes
  (`Canoe : Paddle(dropped) : Hull(kept)`) and notes this one as not generating today.
- Found while pinning the branches of [ADR-159](../adr/159-async-member-on-kotlin-subclass.md).
