# ADR-174: Interface `suspend`/`Flow`/`StateFlow` members are declared on `I<Name>` and dispatched by the backing wrapper

## Status

Accepted

## Context

Restatement (the contract): forward, Kotlin declares. A C# caller holding an `IFeed` (the ADR-040
projection of a Kotlin `interface Feed`, whether the object behind it is the `Feed` backing wrapper
or an exported implementing class) can `await feed.FetchAsync(1)`, `await foreach` over
`feed.Ticks()`, read `feed.Level.Value`, and `await using` the reference so the scope that drives
those calls is drained, exactly as it can on an ordinary class (ADR-019, ADR-026, ADR-065, ADR-159).

What happens today, **verified by a Tier 1 spike on this branch** (scratch test
`ZzProbeInterfaceAsyncTest`, deleted after the run; its Kotlin source is in
`docs/research/roadmap/interface-async-members.md`), for

```kotlin
interface Feed {
  fun name(): String
  suspend fun fetch(id: Int): String
  fun ticks(): Flow<Int>
  val level: StateFlow<Int>
  val stream: Flow<String>
  fun flowDefault(): Flow<Int> = flowOf(1, 2)
  fun onEach(cb: (Int) -> Unit) { cb(1) }
  suspend fun fetchDefault(): Int = 3
}
class RssFeed : Feed { /* overrides */ }
class Crate<T>(val item: T) : Feed { /* overrides */ }
fun makeFeed(): Feed = RssFeed()
```

1. **Verified:** the generated interface is `public interface IFeed : IDisposable { string Name(); void OnEach(Action<int> cb); }`. Every `suspend`, `Flow` and `StateFlow` member, abstract or default, method or property, is absent.
2. **Verified:** the backing wrapper is `public sealed class Feed : IFeed, IDisposable, INugetHandle` with only `Name` and `OnEach`. No scope, no `IAsyncDisposable`. So `makeFeed()` hands C# a `Feed` wrapper (`NugetMarshal.TryResolveCSharp(...) ? ... : new Feed(...)`) through which none of the async members is reachable, even though the object behind it is an `RssFeed`.
3. **Verified:** the exported implementer `RssFeed` renders every async member through its own class-owner legacy routes: `Task<string> FetchAsync(int id, CancellationToken cancellationToken = default)`, `Task<int> FetchDefaultAsync(CancellationToken cancellationToken = default)`, `KotlinFlow<int> Ticks()`, `KotlinFlow<int> FlowDefault()`, `KotlinStateFlow<int> Level { get; }`, `KotlinFlow<string> Stream { get; }`, with `IAsyncDisposable` and its own scope. These are the exact signatures an `IFeed` declaration would require, so `RssFeed` would satisfy an extended `IFeed` unchanged.
4. **Verified:** the generic implementer `Crate<T>` renders `Name`, `OnEach`, `Item`, `Dispose`, `DisposeAsync`, and **none** of `FetchAsync`/`Ticks`/`Level`/`Stream` (ADR-147 refuses the legacy async routes on a generic owner). Declaring those members on `IFeed` without a rule for this implementer is `CS0535` on `Crate<T>`.
5. **Verified:** every one of these drops is silent. `kspWarnings` was empty and `NugetDiagnostics.json` was `[]`.
6. **Verified:** no reverse bridge is planned for `Feed` (no `FeedBridgeState`, no `feed_bridge_create`), because `ForwardInterfaceBridgePlanner.slotOf` returns `null` for a `suspend` function (`ForwardInterfaceBridgePlanner.kt:183`) and `plan` then returns `null` for the whole interface (`:133`). **Inferred** from the planner's docstring (`ForwardInterfaceBridgePlanner.kt:105-107`), not spiked: a C#-implemented `IFeed` passed to `takeFeed` therefore throws at `NugetMarshal.HandleOf` today. This ADR does not change that.

Where each drop happens, **verified by reading**:

- `ForwardCallablePlanner.interfaceEntries` (`ForwardCallablePlanner.kt:1216-1221`) turns every `suspend` member into `Skipped(SUSPEND)`; a `Flow`-returning member is skipped as `FLOW_PROTOCOL` and exempted from naming by `nameUnroutedPositions` (`:1258`) on the premise that "the implementing class re-emits it".
- `ForwardPropertyPlanner.interfaceProperties` (`ForwardPropertyPlanner.kt:417`) plans an interface property as `ForwardPropertyPosition.CLASS`; a `Flow`/`StateFlow` type is unplannable and `recordDropped` (`:1043-1054`) suppresses the record because `position == CLASS` and the protocol is legacy-routed. The premise ("`CirClassTranslator`'s flow adapter re-emits it") is false for an interface, which has no flow adapter. This answers the backlog's open question: the property is **not** admitted, so there is no live scope-less rendering bug, only a silent drop.
- `InterfaceExports.addInterfaceExports` (`InterfaceExports.kt:28-60`) emits only catalog plans and `dispose`; it never calls `addSuspendClassMethodExports`, `addFlowMethodExports` or `addFlowPropertyExports` (`SuspendFunctionExports.kt:113`, `FlowExports.kt:259`, `FlowExports.kt:136`).
- `translateInterfaceBackingClass` (`CirClassTranslator.kt:3626-3686`) hard-codes `hasSuspendMethods = false`, `ownsScope = false` (`:3683-3684`), which ADR-159 left explicit precisely so this ADR has one line to change.

Prior decisions this reads against:

- **ADR-062** keeps `suspend` and `Flow` on named legacy routes (`062-forward-callable-plan.md:64-71`). There is no plan-route end state for them, so "retire the legacy dialect" here means: reuse the one legacy async emitter per protocol for a new owner kind, the way ADR-118 (suspend) and ADR-124 (Flow) extended the same emitters to sealed arms, rather than writing an interface-specific async dialect.
- **ADR-159** makes one scope per instance, owned by the root-most class projecting a scope-using member, derived by `forwardScopeOwner` (`ForwardScopeOwnership.kt:137`). The backing wrapper has no base chain, so it is its own owner whenever it projects an async member.
- **ADR-094, 2026-09-10 amendment**: the C# base list advertises what the body implements (`IAsyncDisposable` next to the exported interfaces). The same rule decides the interface's own base list here.
- **ADR-160** already put the per-call lambda parameter on the plan, which is why `OnEach` is declared on `IFeed` today (verified above). It bears on the sibling item only; see the Consequences.

## Alternatives Considered

### 1. Declare on `I<Name>`, dispatch through the interface's own exports, cover non-projecting implementers with an explicit implementation (chosen)

The interface declares every admitted async member with the class route's own C# types. The backing
wrapper implements them through new interface-owner async exports (`feed_fetch_async`,
`feed_ticks_collect`, `feed_get_level_collect`/`_value`, ...), built by the existing legacy emitters
with the interface as owner. An exported implementer that projects the member (`RssFeed`) already
satisfies the declaration. An exported implementer that does not (`Crate<T>`, ADR-147) gets an
explicit interface implementation that calls the interface's dispatch export with its own handle and
its own scope.

Why the last step is correct dispatch and not a workaround, **verified by reading the probe's
generated Kotlin**: the interface's exports spell the receiver as the bare interface
(`handle.asStableRef<tier1.probeasync.Feed>().get().name()`), which compiles regardless of the
implementer's type parameters and reaches `Crate<Int>.fetch` through Kotlin's own virtual dispatch.
ADR-147's refusal exists because the class routes spell `asStableRef<Crate>()` bare; the interface
carrier never has that problem.

Pros: the restatement holds for every holder of an `IFeed`; no new async dialect; the implementer
gate is a completion, not a refusal. Cons: the explicit-implementation step needs the interface's
`DllImport`s reachable from the implementer (they are `private static extern` inside the backing
wrapper today), so they must be hoisted to an `internal static class FeedNative` or duplicated.

### 2. Declare on `I<Name>`, refuse the member with a named skip when any exported implementer does not project it

Same as 1 without the explicit implementation. Pros: smaller. Cons: one generic implementer anywhere
in the library strips the interface's whole async surface, and the author learns it from a warning on
a declaration they did not touch. Kept as the fallback if the hoisting step in 1 is priced out, never
the default.

### 3. Declare the Flow members on the interface as `IAsyncEnumerable<T>`

Rejected: C# has no covariant return for an interface implementation (covariant returns, C# 9, are
class overrides only), so every implementer needs an explicit shim, and `StateFlow`'s `.Value` is lost
through `IAsyncEnumerable<T>`. The class route already renders `KotlinFlow<T>`/`KotlinStateFlow<T>`.

### 4. Leave the interface as is and name the drop

Rejected: fails the restatement. `makeFeed()` would keep returning an object through which the author's
async API is unreachable.

## Decision

Option 1, with these six rulings (the contract the implementation is held to):

1. **One admission predicate.** An async member is declared on `I<Name>` only when every
   non-generic implementer's class route will project it. The interface selector *calls* the class
   route's own selectors (`forwardSuspendRouteMethods`, `forwardClassFlowMethods`,
   `forwardClassFlowProperties`, `ForwardScopeOwnership.kt`) on the interface, so an ADR-114/119/123
   refusal drops the member from `I<Name>`, the backing wrapper, the Kotlin exports and every
   implementer together. Only a **reachable** interface (one with an ADR-040 backing wrapper) carries
   the surface: an unreachable one has no dispatch exports for a generic implementer to forward to.
2. **Sealed interfaces are out, silently.** The classifier never makes a sealed type an interface
   type (`ForwardBridgeTypeClassifier.kt:280-306`), so a sealed interface is never reachable and
   `I<Shape>` declares no `Task<>` member. No warning: the arms already bind those members (ADR-118).
3. **Generic interfaces are a named skip.** `interface Feed<T>` would need `asStableRef<Feed<...>>()`,
   the ADR-147 reason, so its async members are named `SKIPPED_GENERIC_INTERFACE_ASYNC_MEMBER` at
   the interface and never declared on `IFeed<T>` (including through `typeParameterMethods`).
4. **Generic implementers forward.** `Crate<T>`, which ADR-147 refuses on the class async routes,
   gets C# *explicit* interface implementations (`Task<string> IFeed.FetchAsync(...)`) calling the
   interface's imports, which live in an `internal static class FeedNative` beside the wrapper. Its
   public surface is unchanged (the ADR-147 refusal of its own members stands); its scope comes from
   the split `forwardDeclaresScopeMember`, which counts interface-forwarded members on a generic
   implementer and keeps ADR-147's own-member refusal.
5. **Disposal.** `IFeed : IDisposable, IAsyncDisposable` when it projects a scope-using member; the
   backing wrapper's hard-coded `false`/`false` becomes the same scope answer every class reads.
6. **Breaking**, below.

## Breaking

A hand-written C# class implementing such an interface stops compiling (CS0535 on the new members,
and on `DisposeAsync`). Precedent: ADR-160, which grew `I<Name>` by the per-call lambda members the
same way. No such class could cross into Kotlin before (there is no bridge for an interface with a
scope-using member, Rule 8), so the break is confined to C#-only uses of the interface.

Rejected alternative: declaring the new members as default interface members (`=> throw new
NotSupportedException()`) would keep such classes compiling, but it turns a compile error into a
runtime throw on exactly the calls this ADR exists to make work.

### Consumer API

```csharp
IFeed feed = Library.MakeFeed();           // backing wrapper, or the C# original (ADR-136)
string item = await feed.FetchAsync(7);
int fallback = await feed.FetchDefaultAsync();
await foreach (int tick in feed.Ticks()) { ... }
int level = feed.Level.Value;
await using (feed) { }                     // IFeed is IAsyncDisposable
```

```csharp
public interface IFeed : IDisposable, IAsyncDisposable
{
    string Name();
    void OnEach(Action<int> cb);
    Task<string> FetchAsync(int id, CancellationToken cancellationToken = default);
    Task<int> FetchDefaultAsync(CancellationToken cancellationToken = default);
    KotlinFlow<int> Ticks();
    KotlinFlow<int> FlowDefault();
    KotlinStateFlow<int> Level { get; }
    KotlinFlow<string> Stream { get; }
}

public sealed class Feed : IFeed, IDisposable, IAsyncDisposable, INugetHandle { /* scope + dispatch */ }
public class RssFeed : IFeed, IDisposable, IAsyncDisposable, INugetHandle { /* unchanged */ }
public class Crate<T> : IFeed, IDisposable, IAsyncDisposable, INugetHandle
{
    // unchanged members, plus, for each member ADR-147 refuses on this owner:
    Task<string> IFeed.FetchAsync(int id, CancellationToken cancellationToken) => /* FeedNative.FetchAsync(_handle, GetOrCreateScope(), ...) */;
}
```

Expected xunit shape (the fixture is a new `test-library` interface with an exported implementer, a
generic implementer, and a top-level factory returning the interface):

```csharp
[Fact]
public async Task SuspendMemberIsAwaitableThroughTheInterface()
{
    await using IFeed feed = Feeds.MakeFeed();
    Assert.Equal("item-7", await feed.FetchAsync(7));
}

[Fact]
public async Task FlowMemberIsCollectableThroughTheInterface()
{
    await using IFeed feed = Feeds.MakeFeed();
    var ticks = new List<int>();
    await foreach (int tick in feed.Ticks()) ticks.Add(tick);
    Assert.Equal(new[] { 1, 2, 3 }, ticks);
}

[Fact]
public async Task StateFlowPropertyReadsThroughTheInterface()
{
    await using IFeed feed = Feeds.MakeFeed();
    Assert.Equal(1, feed.Level.Value);
}

[Fact]
public async Task GenericImplementerAnswersThroughTheInterface()
{
    await using IFeed crate = Feeds.MakeCrate();   // Crate<int> held as IFeed
    Assert.Equal("crate-7", await crate.FetchAsync(7));
}

[Fact]
public void InterfaceAdvertisesAsyncDisposal()
{
    Assert.True(typeof(IAsyncDisposable).IsAssignableFrom(typeof(IFeed)));
}
```

### Rules

1. **Admission.** An interface member is admitted when it passes the same refusals the class route applies for its protocol (`legacyRefusedParameter`, `legacyRefusedReturn`, the ADR-114/119/123 element rules), evaluated on the interface. The generic-owner refusal (ADR-147) does not apply to an interface route whose receiver is spelled bare, but a generic interface (`interface Feed<T>`) stays refused for the same reason ADR-147 gives: its receiver would need type arguments. **Inferred** (not spiked): `interfaceEntries` already plans a generic interface's sync members; whether the async emitters accept an `asStableRef<Feed<*>>()` spelling was not checked.
2. **Kotlin half.** `addInterfaceExports` calls `addSuspendClassMethodExports`, `addFlowMethodExports` and `addFlowPropertyExports` with the interface as owner and its own prefix, using the same selectors the C# half reads (ADR-159 single-selector rule). The planner stops treating the interface's `SUSPEND`/`FLOW_PROTOCOL` skips as "re-emitted elsewhere" for the members the interface route now carries, and names the ones it refuses.
3. **Placement.** Declared-vs-inherited follows `ForwardInterfaceHierarchy` exactly as the sync route does: the interface declares a member at its DECLARED placement; the backing wrapper implements every member, own and inherited. **Inferred**: the async emitters iterate `getAllFunctions()` of the owner, which on an interface includes inherited members, so they are expected to cover the wrapper; not spiked.
4. **Overload numbering.** Same-name async members carry the ADR-090 suffix into both the export name and the C# `nativeName` (the ADR-118 lesson). **Inferred** from ADR-118; not spiked for an interface owner.
5. **Scope.** `translateInterfaceBackingClass` reads a scope-owner answer instead of `false`/`false` (`CirClassTranslator.kt:3683-3684`). `forwardScopeOwner` walks `declaredBaseChain()`, which an interface does not have; it needs an interface branch that answers "the wrapper itself, iff it projects a scope-using member". **Inferred** by reading `ForwardScopeOwnership.kt:137-145`.
6. **Interface base list.** `renderInterface` adds `IAsyncDisposable` next to `IDisposable` iff the interface (own or inherited members) projects a scope-using member. Every exported implementer already renders `DisposeAsync` when it projects one (verified: `RssFeed`, `Crate<T>`). An implementer that gets a member only through Rule 7 needs a scope and a `DisposeAsync`; **inferred**: ADR-159's `forwardDeclaresScopeMember` must count Rule 7 members, or `Crate<T>` is `CS0535` on `DisposeAsync`. (It happens to render `DisposeAsync` today with no projected async member, verified, which contradicts ADR-159's stated rule; see Consequences.)
7. **Non-projecting implementer.** For each exported class implementing the interface directly or through a kept super-interface, every admitted interface async member the class does not project itself is rendered as an explicit interface implementation calling the interface's dispatch import with `_handle` and `GetOrCreateScope()`. The interface's async `DllImport`s move to an `internal static class {Name}Native` so the implementer can reach them. Precedent, verified in the spike output: the renderer already emits `internal static class CrateNative` for the generic class `Crate<T>` (the ADR-094 static carrier); reuse that shape. **Inferred** (not spiked): the explicit implementation compiles against a `sealed` backing wrapper's hoisted imports; the dispatch itself is verified by reading the bare `asStableRef<Feed>()` receiver.
8. **C#-implemented interfaces.** Unchanged and out of scope: `ForwardInterfaceBridgePlanner` still returns no plan for an interface with a `suspend` member (verified, `ForwardInterfaceBridgePlanner.kt:183`), and it also returns none for a `Flow` return or lambda parameter (verified: no `FeedBridgeState`, no `LedgerBridgeState` for `interface Ledger { fun total(): Int; fun each(cb: (Int) -> Unit) }`). A C# class can compile against `IFeed` but cannot practically implement `Ticks()` (`KotlinFlow<T>`'s constructor is `internal`) and cannot cross into Kotlin.

## Consequences

- `IFeed` gains members, so a hand-written C# class implementing `IFeed` stops compiling. No such class could cross into Kotlin before (no bridge), so the break is confined to C#-only uses of the interface. State this in the release notes.
- **Item 3 of the same batch is absorbed.** Its Flow half is this ADR (the interface route does not distinguish a default member from an abstract one; verified: `flowDefault` and `fetchDefault` behave exactly like `ticks` and `fetch`). Its lambda half is already fixed by ADR-160 (verified: `IManifest` declares `void CallbackParamOnInterface(Action<int> cb);`). See `docs/research/roadmap/interface-default-flow-lambda-on-interface.md`.
- Residuals, not decided here, each **verified** by the spike: (a) the missing reverse bridge for any interface with a scope-using or lambda member is silent (no warning, `NugetDiagnostics.json` `[]`); (b) `Crate<T>` drops `FetchAsync`/`Ticks`/`Level`/`Stream` silently; (c) `Crate<T>` renders `IAsyncDisposable` while projecting no async member, contrary to ADR-159.
- Residual (c) is fixed here, not deferred: `forwardClassFlowProperties` lacked the generic-owner guard
  its two sibling selectors had, so `Crate<T>` owned a scope for a StateFlow property the C# half never
  projected. With the guard, `Crate<T>`'s scope now comes only from the ruling-4 forwards (verified: it
  still renders `IAsyncDisposable`, now with members that use the scope).
- Leak rows: one `LeakTests` row per new handle path (the wrapper's scope created and drained through `DisposeAsync`; a `KotlinStateFlow` read through the interface), mirroring ADR-159's rows.
- Nothing here verifies the runtime path on a real `.so`/`.dll`. The spike was Tier 1 (KSP plus JVM compile of the generated Kotlin, C# text only). The implementing run must prove it with `scripts/verify.sh`.
