# C#-implemented interface round-trips to the original instance on async reads

- ROADMAP: "Inferred: every non-synchronous read of an interface (a `suspend fun` return, a `Flow<T>` element, a collection element, a sealed read) always constructs a fresh ADR-040 backing wrapper, unlike the plan route's synchronous return, which resolves back to a stored C#-implemented original first" (Phase 4, as of 2026-09-14)
- Researched: 2026-09-14, 5 of 20 minutes
- Restatement: forward. C# implements a Kotlin interface and passes it in; when Kotlin hands it back over a suspend return or a Flow element, the consumer gets its original C# instance (reference-equal), as the synchronous plan route already does.
- Verdict: fix. ADR-136 (`docs/adr/136-csharp-identity-on-async-interface-reads.md`, Proposed). Queued behind `interface-parameter-reachability` on the same worktree.

## Findings (all verified by reading, `main` at `112475a1`)

| Read site | Where | Emitted | Result |
|---|---|---|---|
| Sync return, nullable return | `forward/ForwardCirPlanProjection.kt:831-841`, `:929-943` (`interfaceReturnExpression` `:1369`) | `TryResolveCSharp(nativeResult, out IPet o) ? o : new Pet(nativeResult)` | resolves |
| Sync property getter | `forward/ForwardCirPropertyProjection.kt:275-277`, `:310-311` | same | resolves |
| Top-level `suspend fun f(): Pet` | `cir/CirFunctionTranslator.kt:711-722` via `legacyInterfaceRead` (`ForwardLegacyRouteCollections.kt:416-418`) to `CirConcurrencyRenderer.kt:164` | `new Pet(resultPtr)` | fresh wrapper |
| Member `suspend fun` | `cir/CirClassTranslator.kt:1643-1658`, same helper | same | fresh wrapper |
| `Flow<Pet>` / `StateFlow<Pet>` element | `CirClassTranslator.kt:1169-1184`, `:1364-1372` via `legacyInterfaceElementReadArgument` (`:455-460`) to `KotlinFlow<T>._read` (`CirFlowRenderer.kt:117`, `:222`) | `read: static h => new Pet(h)` | fresh wrapper |
| `List<Pet>` and kin | `ForwardCallablePlanner.kt:3419` `isBridgeableComponent` has no `Interface` arm | `SKIPPED_UNSUPPORTED_TYPE`, pinned `Tier1InterfaceReturnTest.kt:224-253` | refused |
| Sealed read | `SealedClassExports.kt:42-46` | Kotlin arm construction | not an interface read |

The token probe already works on async handles: `nuget_csharp_token` is `handle.asStableRef<Any>().get() as? NugetCSharpBridge` (`nuget-runtime/.../NugetRuntime.kt:596-598`); the suspend route pins `NugetHandles.retain(result)` on the bare result (`SuspendFunctionExports.kt:222-236`), the Flow route `NugetHandles.retain(value as Any)` per emission (`FlowExports.kt:439-441`), the same `retain` the sync plan route uses (`ForwardKotlinPlanEmitter.kt:697-701`).

## Recommendation

Give `interfaceReturnExpression` a handle-name parameter (default `nativeResult`, so the three callers and the `ForwardInterfaceCirProjectionTest` / `Tier1InterfaceBridgeFactoryTest` pins do not move) and have `legacyInterfaceRead` and `legacyInterfaceElementReadArgument` call it. No Kotlin emission, runtime, ABI or contract-hash change. Rejected: teaching `FromHandle<T>`/`Factories` to resolve interfaces (touches the AOT factory table for a two-function fix); leave documented.

## Files an implementation touches

`forward/ForwardCirPlanProjection.kt`, `forward/ForwardLegacyRouteCollections.kt`; `test-library/.../cat/Cat.kt` (`suspend fun closestFriendLater(): Pet`, `fun friends(): Flow<Pet>`); `IntegrationTests/BidirectionalTests.cs` (two facts, reuses its `Dog`); `LeakTests/LiveHandleTests.cs` (one row per route: the freeing site moves from the consumer's `using` to the read inside `TryResolveCSharp`); a Tier 1 pin on the emitted read text (none exists; `Tier1NestedTypesTest.kt:660-700` pins only the signature); docs `FEATURES.md:169`, `docs/topics/coroutines-and-flow.md:262-264`, identity-asymmetry notes in ADR-040 `:1024`, ADR-084 `:524`, ADR-133 `:302`.

## Sample test

```csharp
[Fact]
public async Task StoredCSharpPet_RoundTripsToTheOriginalInstance_OverTask()
{
    using var oreo = new Cat("Oreo", 9);
    using IPet dog = new Dog("Rex");
    oreo.Befriend(dog);
    IPet later = await oreo.ClosestFriendLaterAsync();
    Assert.Same(dog, later);
}
```

Same shape for `await foreach (IPet pet in oreo.Friends())`.

## Deferred scope

`List<Interface>` stays refused (ADR-040). `suspend fun` returning `StateFlow<Interface>` is `interface-spelling-sites.md` site (b); it inherits the resolving read once it takes `flowElementRead`. The generic-function route `FromHandle<TResult>` (`CirFunctionRenderer.kt:286`) does not resolve; no interface fixture reaches it.

## Open what-questions

- Fixture home: top-level `Pet` on `Cat` (minimum). Decided 2026-09-14.
- One leak row per route (two). Decided 2026-09-14.
- Ship as a fix, no migration note: consumers get their own object back, which is ADR-084's documented contract. Decided 2026-09-14.
