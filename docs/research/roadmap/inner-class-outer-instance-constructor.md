# `inner class` as a C# nested type whose constructor takes the outer instance first

- ROADMAP: "Inferred: an `inner class` owner or candidate needs an outer-instance constructor design (`new Host.Guest(host, n)` / `host_guest_create(outer, ...)`, the JVM `host.new Guest(3)` shape), recorded as a future ADR by ADR-134's Alternative 3; `enum class` and generic owners have no such design" (Phase 4 line 30, as of 2026-09-14)
- Researched: 2026-09-14, 9 of 25 minutes, verified by reading (no spike; every inferred claim fails loud as a Kotlin compile error in the Tier 1 harness)
- Restatement: forward. A public Kotlin `inner class` is declared as a C# nested type whose constructor takes the outer instance first (`new Host.Guest(host, 3)`), so a consumer can construct one and its members bridge like any nested class; `enum class` and generic owners stay a named skip.
- Verdict: fix. ADR-141 (`docs/adr/141-inner-class-outer-instance-constructor.md`, Proposed).

## Findings (verified by reading)

- ADR-133 already supports the inner class except for one thing: its constructor plan has no receiver. `NugetProcessor.kt` ~:188 / ~:197 hold the two `Modifier.INNER` gate arms (owner and candidate). `ForwardCallablePlanner.constructorEntry()` ~:1553 plans every ctor with `receiver = ForwardReceiver.Static`.
- `ForwardKotlinPlanEmitter.invocationExpression()` ~:899: the CONSTRUCTOR arm ignores the receiver; the EXTENSION arm already renders `${receiverExpression(receiver)}.name(args)` and `loweredArgument` (~:1103) lowers an `ObjectHandle` receiver to `outer.asStableRef<Q>().get()`. The receiver is found at index 0 by `RECEIVER` role (~:31).
- `ForwardCirPlanProjection.constructor()` ~:148 projects declared params only; `extension()` ~:418 (ADR-132) is the receiver-first template: prepends `CirParameter(name, type, "IntPtr")`, feeds the receiver into `callArgument` (`outer._handle`, ~:564) and the `needsCustomParams` gate.
- `exportedObjectHandles = exportedTypes` (`cir/CirTranslator.kt` ~:155) and `allClasses` includes `nestedDeclared` CLASS entries, so dropping the candidate gate arm alone makes `Host.Guest` resolve at member positions. `ForwardBridgeTypeClassifier` has no `inner` gate of its own.
- Prior art: JVM `host.new Guest(3)`, outer first (JLS 15.9.2). Kotlin/Native ObjC export exports inner constructors with the outer appended **last** (upstream `getMethodBridge.kt`, verified by reading). Swift export docs say nothing. C# hand-written idiom: enclosing instance as first ctor param. Chosen: outer first, matching JVM, C# idiom and ADR-132's one `RECEIVER` slot at index 0.
- Inferred, loud-failing: Kotlin `outer.Guest(n)` syntax, KSP omitting the synthetic outer param, `asStableRef<Host.Guest>`.

## Recommendation

```csharp
public class Host : IDisposable {
    public Host(string name);
    public Host.Guest GuestAt(int visits);
    public class Guest : IDisposable {
        public Guest(Host outer, int visits);
        public int Visits { get; }
        public string Greeting { get; }   // reads this@Host.name
    }
}
```
Export `host_guest_create(outer, visits, error)`, Kotlin body `outer.asStableRef<Host>().get().Guest(visits)`. Rejected: outer-last (fails `validateRoles`, clashes with ADR-091 trailing truncation); factory member `host.NewGuest(3)` (invented name, ADR-134 Alt 2's objection); keep the skip.

## Files touched

`NugetProcessor.kt` (drop the candidate arm), `forward/ForwardCallablePlanner.kt` (receiver in `constructorEntry`), `forward/ForwardKotlinPlanEmitter.kt` (CONSTRUCTOR arm), `forward/ForwardCirPlanProjection.kt` (mirror `extension()` in `constructor()`), new `test-library/.../nested/Inner.kt`, new `IntegrationTests/InnerClassTests.cs`, `Tier1NestedTypesTest.kt` (flip the `Host.Guest` cell), `LeakTests/LiveHandleTests.cs` Row 1c, three fixture comments (`Deferred.kt`, `Aviary.kt`, `ProbeOuter.kt`), docs topic ~:1104, FEATURES, ROADMAP.

## Sample test

```csharp
[Fact]
public void InnerClass_ConstructsWithTheOuterInstanceFirst()
{
    using var host = new Host("Oreo");
    using var guest = new Host.Guest(host, 3);
    Assert.Equal(3, guest.Visits);
    Assert.Equal("Oreo welcomes visit 3", guest.Greeting);
}
```

## Deferred scope

Inner class as owner (inner-of-inner; owner arm stays), inner under a sealed base/arm owner, generic inner, inner of a generic outer.

## Open what-questions

- Silent-loss hazard for the implementer: the receiver must join the `needsCustomParams` / `callArgument` input list in `constructor()`, not only `publicParams`, or a primitive-only inner ctor takes the trivial path (CS1503, loud).
- A declared ctor parameter named `outer` collides (CS0100); same class as ADR-132's `receiver`, recorded not guarded.
- Disposed-outer behaviour rests on the inner's reference keeping the outer alive; the ADR asks for an xunit cell pinning it.
