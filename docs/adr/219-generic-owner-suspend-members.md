# ADR-219: Forward, the `suspend` members of a generic class or generic sealed arm bind over the erased receiver

## Status
Accepted

Narrows [ADR-147](147-generic-class-methods.md)'s refusal of the suspend route on a generic owner.
Reuses [ADR-214](214-suspend-generic-functions-on-the-suspend-route.md)'s boxed `T` shapes,
[ADR-215](215-type-parameter-in-lambda-and-flow.md) lane B's erased receiver and trampoline,
and [ADR-198](198-unspellable-bound-trampoline.md)'s trampoline. Closes the ROADMAP Phase 6 consumer
build break "a generic sealed arm implementing an exported interface with a `suspend` member"
(CS0535 twice).

## Context

A generic owner's `suspend` members have no route. **Verified by reading** at `4a9fdb86`:
`forwardSuspendRouteMethods` (`forward/ForwardScopeOwnership.kt:63`) opens with
`if (forwardTypeParametersInScope().isNotEmpty()) return emptyList()`, because the suspend body
spelled the receiver `handle.asStableRef<$qualifiedName>()` (`exports/SuspendFunctionExports.kt`,
`buildSuspendMethodBody`), and `asStableRef<Platter>()` does not compile for `Platter<T>`. Every
half reads that selector (the Kotlin builder, `translateClass`'s `allSuspendMethods`, the arm loop
in `translateSealedClass`, the export gate in `NugetProcessor`), so the refusal was consistent:
`class Platter<T> { suspend fun take(): T }` projected no `TakeAsync`, named by the planner as
`GENERIC_OWNER_LEGACY_ROUTE`.

The Flow route already solved the receiver. ADR-199 reads a generic arm's Flow members over
`asStableRef<Dish.Hot<Any?>>()`, and [ADR-215](215-type-parameter-in-lambda-and-flow.md) lane B gave a plain generic class the same
spelling (`carriesGenericFlowRoute`, `takesGenericFlowRoute`, `forwardOwnerTypeName()`: the first
bound for a bounded parameter, `*` for a multi-bound one) plus the ADR-198 trampoline for a
multi-bound owner `T` parameter (`flowTrampoline` / `flowTrampolined` in `exports/FlowExports.kt`).

Two consumer-visible breaks follow from the refusal. **Verified by the red pack** (2026-10-22): the
fixture `Buffet.Cart<T> : Buffet<T>(), Grazer` (an arm implementing an exported interface with a
`suspend` member) fails `nugetCompileInterop` with CS0535 twice: `Buffet.Cart<T>` lists `IGrazer`
but implements neither `IGrazer.GrazeAsync(CancellationToken)` nor `IAsyncDisposable.DisposeAsync()`.
The ROADMAP line attributed it to the arm path never calling `forwardAsyncInterfaceForwards`.
That is not the cause the green run shows: with only the early return narrowed, the arm projects
its `GrazeAsync` publicly (an arm's interface-inherited members are its own surface, ADR-118 /
ADR-101 amendment), which implements `IGrazer.GrazeAsync` implicitly, and the arm becomes a scope
owner, so it gets `DisposeAsync`. No arm call to `forwardAsyncInterfaceForwards` was needed.
**Verified** by the green pack and the `Cart_GrazeAsync_GenericArmImplementingASuspendInterface_Binds`
xunit row. The second symptom (`Cart.graze` dropped with no diagnostic) goes with it: it binds.

ADR-214 left a trampolined `T` parameter refused on the suspend route
(`forward/ForwardLegacyRouteCollections.kt`, `legacyParameterShape`: `suspends &&
!shape.type.trampolined`), because "the launch lambda cannot declare the type variable". That is
true of the lambda but not of the export: wrapping the whole export body, launch included, in the
ADR-198 local generic function puts the variable in scope for the lambda. **Verified** by the
compiled `platter_rate_async` / `rateLater_async` exports and the `RateAsync` xunit rows.

## Alternatives Considered

### 1. Lift the early return over the Flow route's erased receiver (chosen)

One predicate per owner kind, the receiver spelling and the trampoline the Flow route already
uses. The ADR-214 boxed shapes need nothing new: an owner `T` is a `BridgeType.TypeParameter`
like a member's own.

### 2. Arm-only: call `forwardAsyncInterfaceForwards` on a generic arm

Fixes the CS0535 alone and leaves every generic owner's own suspend members unbound. Smaller, but
the ROADMAP line's inferred cause turned out not to be needed once alternative 1 lands, so it would
be a second mechanism for the same members.

### 3. A typed receiver everywhere (`Platter<T>` under a trampoline for every member)

Correct but wraps every export in a local generic function for no gain: the erased receiver
compiles for every member that takes no value of a star-projected parameter.

## Decision

Alternative 1.

```C#
// class Platter<T> { suspend fun take(): T; suspend fun put(item: T); suspend fun <M : Enum<M>> rate(medal: M): M }
public class Platter<T> : IDisposable, IAsyncDisposable
public Task<T> TakeAsync(CancellationToken cancellationToken = default)
public Task PutAsync(T item, CancellationToken cancellationToken = default)
public Task<M> RateAsync<M>(M medal, CancellationToken cancellationToken = default)
// class Picnic<T> where T : Furry, T : Tricky { suspend fun invite(guest: T): String }
public Task<string> InviteAsync(T guest, CancellationToken cancellationToken = default)
// suspend fun <T : Enum<T>> rateLater(medal: T): T
public static Task<T> RateLaterAsync<T>(T medal, CancellationToken cancellationToken = default)
```

### Routing

1. `forwardSuspendRouteMethods`' early return keeps ADR-147's refusal only for a generic owner
   that is not `carriesGenericSuspendRoute()`: a `carriesGenericFlowRoute()` class (plain generic,
   no captured enclosing parameters, not sealed), or a generic sealed arm with no captured
   parameters. An inner class of a generic owner and a generic sealed base keep the refusal.
2. `takesGenericSuspendRoute(cls)` filters the members. A generic class's override of an interface
   member keeps ADR-174's explicit forwarding, as on the Flow route, so it is not projected twice.
   An arm projects one as its own member, as a non-generic arm always has.
3. `reProjectsKeptBaseMember`'s two generic-base returns flip for a suspend member too (the
   condition its KDoc named): `Barge : Keel(dropped) : Crate<Int>` now inherits `LoadAsync` from
   `Crate<T>`, and Crate's export dispatches to Barge's override in Kotlin.
4. The planner's `nameGenericOwnerLegacyRoutes` and `nameGenericArmLegacyDrops` leave a member
   that `takesGenericSuspendRoute` alone; a refused one is still named by
   `warnRefusedLegacyRouteMembers` with its own reason.

### Wire

| Position | Kotlin half | C# half |
|---|---|---|
| receiver | `handle.asStableRef<Platter<Any?>>()`, `Banquet<Furry>`, `Picnic<*>`, `Buffet.Tureen<Any?>` (`forwardOwnerTypeName()`) | the generic class; `nameof(Platter<T>)` in the disposed check, as [ADR-215](215-type-parameter-in-lambda-and-flow.md) did for Flow members |
| owner `T` parameter / result | ADR-214's `Boxed` shapes, unchanged | ADR-214's `Wrap<T>` / `FromHandle<T>`, unchanged |
| multi-bound owner `T` parameter | the export inside `fun <T> nugetTrampoline() where T : Furry, T : Tricky`, receiver cast `as Picnic<T>` | unchanged |
| trampolined own `T` parameter (member or top-level) | the export inside `fun <M> nugetTrampoline() where M : Enum<M>`, called at `Nothing` | unchanged |

**Prelude and cast, load-bearing.** The trampoline wraps the whole export, so the parameter prelude
(the erased `asStableRef<Any>()` box read) still runs on the caller's thread before
`launchForCSharp`, which keeps ADR-214's rule that C# may dispose the box once the extern returns.
The checked cast (`bounded as Furry; bounded as Tricky; bounded as T`) stays the call argument
inside the launch, so an out-of-bound value faults the `Task` instead of escaping the C ABI.
**Verified by Tier 1** (`Tier1GenericOwnerSuspendTest`: box read before the launch, cast after)
and the `Platter_RateAsync_Bronze_IsAFaultedTask` xunit row (the `require` failure inside the
trampolined body arrives as a faulted `Task` with `kotlin.IllegalArgumentException`).

### Diagnostics

No `ForwardDiagnostic` text changes: this ADR removes refusals and adds none. A generic owner's
suspend member that a parameter, return or own-type-parameter rule refuses is named exactly as on
a non-generic owner.

## Consequences

- A generic class's and a generic sealed arm's `suspend` members bind; the owner becomes a scope
  owner and implements `IAsyncDisposable`.
- The ROADMAP Phase 6 "generic sealed arm implementing an exported interface with a `suspend`
  member" build break is closed, **verified** by the `Buffet.Cart<T>` fixture packing and a Tier 1
  C# compile of the backlog note's `Dish.Hot<T> : Dish<T>(), Feed` shape. The line and its backlog
  note are removed.
- ADR-214's "Not admitted: a trampolined `T` parameter" no longer holds on the suspend route.
- `Crate<T>.load` in the `parcel` fixture moves from Barge to Crate; `Barge` inherits it. Tier 1
  cells in seven files and one xunit row (`Barge_DeclaresOnlyWhatBargeAndKeelDeclare`) pinned the old
  refusal and are flipped, not deleted.
- Not changed: a generic sealed base's own suspend members, an inner class of a generic owner, a
  generic interface (ADR-174 ruling 3), and `suspend` on an `object` / companion (ROADMAP Phase 6).
- Leak row: `GenericOwner_SuspendMembers_ReturnToBaseline` in `LeakTests/LiveHandleTests.cs`, a
  200-iteration loop over `Platter<int>`, `Platter<Tabby>`, `Banquet<Tabby>`, `Picnic<Tabby>` and
  `Buffet.Tureen<string>`, with the trampolined `RateAsync` and two pre-cancelled calls, at the end
  of the class under an `// ADR-219` banner.
