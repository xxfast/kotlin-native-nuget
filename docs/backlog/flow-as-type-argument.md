# `Flow<T>` / `StateFlow<T>` as a generic type argument is blocked on three things

`class Box<T>(val value: T)` with `Box<Flow<String>>` or `Box<StateFlow<String>>` in a signature
produces no binding today. Everything below was established by reading source on 2026-10-08;
nothing was run.

## Blockers

1. **A plain generic instantiation is refused at every member position before its argument is
   looked at.** `ForwardBridgeTypeClassifier.kt` classifies a reference to a non-sealed user generic
   class as `SpecializedProtocol("generic declaration ...")`, the planner turns that into
   `ForwardPlanSkipReason.GENERIC`, and `GENERIC` is an unrouted reason, so a property, a method
   return or a parameter gets `UNROUTED_POSITION`. [ADR-147](../adr/147-generic-class-methods.md)
   calls `Crate<Cat>` as a parameter "a different feature". Only generic sealed hierarchies bind an
   instantiation at a member (`genericSealedReference`), and their argument check
   (`isErasedSealedArgument`) rejects a Flow. The prerequisite is a ROADMAP item of its own.
2. **The one binding position refuses a Flow by name.** A top-level function return binds
   `Box<T>` on the legacy route (`CirFunctionTranslator.kt`, `isGenericReturnType`), but
   `csTypeArgument` (`CirTypeMapping.kt`) deliberately reports `Flow` and `StateFlow` as
   unnameable: the carrier reads `T` through `NugetMarshal.FromHandle<T>`, which has no
   materialiser for a Flow. The skip is `SKIPPED_UNSUPPORTED_RETURN`, pinned for `Flow` by
   `Tier1GenericReturnTypeArgumentTest` (`crateOfFlow`); a `StateFlow` twin is not pinned but takes
   the same path (inferred).
3. **A C# materialiser needs a handle-keyed collect export.** `KotlinFlow<T>` is built from a
   collect delegate, and the generated `_collect` export is per declaring member, so a flow that
   arrives as an erased `T` has nothing to call. `StateFlow` already has the runtime exports
   `nuget_stateflow_collect` and `nuget_stateflow_value`, so a `Factories` entry per closed
   instantiation would work with no ABI change (inferred). Plain `Flow` has no equivalent;
   [ADR-194](../adr/194-suspend-returning-flow.md) Alternative 3 (a shared handle-keyed
   `nuget_flow_collect`) was rejected because it cannot project enum or value-class elements.
   Narrowed to primitive, `String` and object elements it could be reopened.

## Not covered by this item

- Passing a `Box<Flow<T>>` or a consumer-built flow back to Kotlin: that is the Phase 7 flow
  parameter mechanism.
- Enum, value-class, collection and nullable elements inside the boxed flow: the module-wide
  StateFlow export boxes `value as Any` with no per-member projection.
- Scope ownership: a flow materialised this way has no owner scope, so disposing the box does not
  cancel a running collection; whether to borrow the producing class's scope is undecided.
- Each `box.Value` read would mint a fresh wrapper that the consumer must dispose.

## Files an implementation would touch

`cir/CirTypeMapping.kt` (`csTypeArgument`), `forward/ForwardGenericSealed.kt`,
`cir/CirTranslator.kt` (factory entries), `cir/CirMarshalRenderer.kt` (`Factories`),
`cir/CirFlowRenderer.kt`, `exports/FlowExports.kt` or the runtime (plain-`Flow` collect), the
ADR-147 and ADR-194 amendments, and `docs/topics/supported-features.md` (the Flow row already says
"not yet supported").
