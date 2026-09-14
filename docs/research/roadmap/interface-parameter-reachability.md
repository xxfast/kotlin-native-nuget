# Parameter-only interface: bridge it instead of crashing the host

- ROADMAP: "**VERIFIED by execution:** ADR-084's bridge plan is return-reachability-driven (`CirTranslator.interfaceBackingClasses`), so a nested interface reachable only at a **parameter** position gets no wrapper or bridge plan at all; passing a C# implementation at that position crashes the host process" (Phase 4, as of 2026-09-14)
- Researched: 2026-09-14, 6 of 20 minutes
- Restatement: forward. Kotlin declares an interface; a C# consumer passes its own implementation where a Kotlin function takes that interface. The consumer gets a working ADR-084 bridge or a named skip. Never a host crash.
- Verdict: fix. ADR-135 (`docs/adr/135-interface-parameter-reachability.md`, Proposed). In flight on `ir/interface-parameter-reachability` since 2026-09-14.

## Findings

- Reachability is return-only and not nested-specific (**verified by reading**). `NugetProcessor.kt:1165-1174` builds `reachableInterfaceNames` from `plan.publicSignature.result` and `propertyPlan.type` only, never `publicSignature.parameters`. A top-level `interface Greeter` used only as `fun greet(g: Greeter)` is in the same state as a nested one. Fixtures never exposed it because every positioned interface is also returned somewhere (`Pet` via `Cat.closestFriend`, `Aviary.Keeper` via `currentKeeper()`). The list feeds `CirTranslator.interfaceBackingClasses` (`cir/CirTranslator.kt:122`, consumed at `:409`, `:492`, `:661`) and the Kotlin factory exports (`NugetProcessor.kt:1558-1565`).
- The crash chain (**verified by reading**, last step inferred): `CirMarshalRenderer.kt:508-517` sets `owned = true` before the throwing `HandleOf(value)`; `CirBridgeRenderer.kt:27-41` `HandleFor` has no arm for the unplanned interface and throws `NotSupportedException`; `ForwardCirPlanProjection.kt:721-722` `interfaceCleanup` is `if (owned) Dispose(handle)` with no `IntPtr.Zero` guard (unlike `collectionCleanup` at `:758-759`); `nuget-runtime` `nuget_dispose(0)` dereferences a null `COpaquePointer`. **Inferred**: K/N raises `NullPointerException` there and an uncaught exception in a `@CName` export kills the process.
- Same throw-unsafe shape on the property-setter route (`ForwardCirPropertyProjection.kt:452-462`) and on an ADR-132 interface-typed extension receiver. So an interface whose bridge plans to `null` (a `var` member) crashes at every position today.
- `Tier1Issue112InterfaceProjectionTest`'s non-reachability pin uses an interface never positioned at all, so widening the walk does not break it.

## Recommendation

Widen the walk to `publicSignature.parameters`, set `owned` only after `HandleOf` succeeds, add the zero guard to `interfaceCleanup` and the setter cleanup. Four processor files. Rejected: a bridge-only second list (three files plus a signature change), refuse named (regresses Kotlin-backed arguments at the same position), throw-safety alone (no bridge).

## Files an implementation touches

`NugetProcessor.kt` (`:1165`), `cir/CirMarshalRenderer.kt` (`:508`), `forward/ForwardCirPlanProjection.kt` (`:721`), `forward/ForwardCirPropertyProjection.kt`; fixture under `test-library/.../nested/`; `IntegrationTests/InterfaceParameterTests.cs`; `LeakTests/LiveHandleTests.cs` (one bridge row, one fault-injection row `UnbridgeableInterface_Argument_ThrowsAndReturnsToBaseline`); a Tier 1 cell asserting `*_bridge_create` exists for a parameter-only interface.

## Sample test

```kotlin
object Registry {
  interface Clerk { fun stamp(): String }
  fun fileVia(clerk: Clerk): String = "${clerk.stamp()} filed"
}
```

```csharp
[Fact]
public void ParameterOnlyNestedInterface_CSharpImplementation_Bridges()
{
    var clerk = new DeskClerk();
    Assert.Equal("stamped filed", Registry.FileVia(clerk));
}
```

Plus `Assert.Throws<NotSupportedException>` for an interface that plans to `null`.

## Deferred scope

Build-time named skip for an interface that plans to `null` (needs `ForwardInterfaceBridgePlanner.plan` to return a reason; archive item). Async identity asymmetry is `async-interface-identity.md`.

## Open what-questions

- Does an ADR-132 receiver's interface type appear in `publicSignature.parameters` or only on the RECEIVER-role slot (`ForwardMarshallingModel.kt:332`)? Settled by the lane's red test: the fixture includes a receiver-only interface.
- ADR-055 ABI growth from dead dispatch exports per parameter-only interface: accepted, same trade ADR-040 took. Decided 2026-09-14.
