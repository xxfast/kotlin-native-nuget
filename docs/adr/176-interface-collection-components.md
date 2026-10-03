# ADR-176: An interface is a collection component: `List<Pet>` binds as `IReadOnlyList<IPet>` on every kind and position

## Status
Accepted

## Context

[ADR-040](040-interface-return-type-mapping.md) deferred "collections of interfaces (`List<Pet>`)".
The deferral is one arm: `BridgeType.isBridgeableComponent()` returns `false` for
`BridgeType.Interface` (`ForwardCallablePlanner.kt:4092`) and `isWrappableComponent()` falls to
`else -> false` (`:4272`), so every method, top-level function and parameter carrying `List<Pet>`,
`Set<Pet>` or `Map<_, Pet>` skips named (**verified**, Tier 1 spike on `main` at `352cb767`,
pinned by `Tier1InterfaceReturnTest.kt:216-253`).

Two things make the deferral worse than a refusal (both **verified** by generated text; the first
also verified at runtime, see the correction):

1. The collection **property getter** already binds. `ForwardPropertyPlanner.isReadableComponent()`
   admits `Interface` (`:1235`), so `val residents: List<Pet>` renders
   `IReadOnlyList<global::Interop.IPet> Residents` reading
   `NugetMarshal.FromHandle<global::Interop.IPet>(h1)` per element. But the interface is not
   reachable through a collection (`interfaceQualifiedNameOrNull`, `NugetProcessor.kt:1857-1860`,
   never looks inside one), so when the collection is the interface's ONLY reachable position there
   is no backing `Pet` class and no `Factories[typeof(IPet)]` entry, and the first Kotlin-backed
   element falls through `Materialize<IPet>` to `NotSupportedException`.
   **Correction (2026-09-29, verified at runtime):** the original text said the getter threw
   unconditionally. It threw only in that case: `LodgerBoard.Lodgers` (a `List<Lodger>` reachable
   nowhere else) threw `NotSupportedException` on `main`, while `FosterHome.Residents` over
   `cat.Pet` worked because `Pet` was reachable through another position.
2. `factoryEntries` (`CirTranslator.kt:1057-1102`) registers namespace-level declarations only.
   On unpatched `main`, `class Aviary { class Perch; fun perches(): List<Perch> }` binds
   `IReadOnlyList<global::Interop.Aviary.Perch>` with no `Aviary.Perch` factory key: the same throw,
   for a nested class, independent of interfaces.

[ADR-173](173-erased-generic-routes-carry-csharp-interface-identity.md) already made the two
per-element primitives interface-aware: `Wrap<T>` falls back to `NugetBridge.HandleFor` for a
C#-implemented value (owned transfer handle), and `Materialize<T>` probes the ADR-084 token before
`Factories` for an interface key. Every collection route reads through `FromHandle<T>` →
`Materialize<T>` and writes through `CreateList/Set/Map` → `Wrap<T>`, so the element mechanism is
already built; what is missing is admission and reachability.

## Alternatives Considered

### 1. Admit `Interface` in both component gates, total reachability, nested factory keys (chosen)

`Interface -> true` in `isBridgeableComponent` and `isWrappableComponent`; an
`elementKotlinTypeName` arm (`ForwardKotlinPlanEmitter.kt:447`); a reachability walk that yields
every interface found in any collection component (element, key, value, recursively, through
`Nullable`), on planned and legacy (suspend, Flow) routes; `factoryEntries` recursing into
`nestedDeclarations`. Pros: one shared gate, so every kind and position (return, parameter,
property get and set, top-level, suspend, Flow, nested collections) binds consistently; fixes the
shipped property-getter throw and the nested-class list throw on the way. Cons: the widest blast
radius of the three; the legacy-route reachability half is unverified (see Decision).

### 2. `List` return only

A result-side-only admission. Pros: smallest surface. Cons: needs a new predicate split out of the
`isBridgeableComponent` that ADR-075/097 deliberately share between positions; leaves the parameter
half and the property setter refused while the getter already binds; does not fix Context item 1
unless reachability is fixed anyway, at which point option 1 costs the same.

### 3. Admit, but refuse nested interface components named

Option 1 without the `factoryEntries` recursion, skipping `List<Outer.Inner>` named. Pros: avoids
pinning ADR-134 nested spellings. Cons: the `List<Aviary.Perch>` throw on `main` needs the same
recursion regardless; the one-level spelling is already pinned by the spike.

## Decision

Option 1.

Consumer C#:

```csharp
IReadOnlyList<IPet> pets = shelter.Residents();              // Kotlin-backed: Pet wrappers
int n = shelter.Roll(new IPet[] { new Dog("Rex"), cat });     // Dog crosses over the ADR-084 bridge
IReadOnlyList<IPet> back = shelter.Echo(new IPet[] { rex });  // Assert.Same(rex, back[0])
IReadOnlyDictionary<string, IPet> byName = shelter.ByName();
Task<IReadOnlyList<IPet>> later = shelter.ResidentsLaterAsync();
```

Element semantics are the sync interface return's (ADR-040, ADR-136): a Kotlin-backed element
materializes as a fresh backing wrapper (`Pet`, never `Cat`) the caller disposes; a C#-implemented
element resolves to the consumer's original object. `NugetBridge.HandleFor` does not cache
(**verified**, generated `PetBridgeState.Create`), so `[dog, dog]` crosses as two Kotlin bridge
objects, as two `befriend(dog)` calls do today.

Mechanism, labelled:

- **Verified** (Tier 1, temporary five-line patch, reverted): with the two gate arms and the
  `elementKotlinTypeName` arm, the generator emits `ReadList<global::Interop.IPet>(..., static h1 =>
  NugetMarshal.FromHandle<global::Interop.IPet>(h1))`, `CreateList(pets)` in the ADR-073 `finally`,
  Kotlin `...map { it as tier1.spikelist.Pet }`, the ADR-083 nullable arm for `List<Pet?>`,
  `ReadSet`/`ReadMap`/nested `ReadList`, and the legacy `Task<IReadOnlyList<IPet>>` and
  `KotlinFlow<IReadOnlyList<IPet>>` reads; generated Kotlin compiles. With the reachability walk
  extended, the backing `Pet`, the `[typeof(global::Interop.IPet)]` factory key and the
  `PetBridgeState` appear.
- **Inferred** (reading plus ADR-173's verified erased-route mechanics, not run on a list):
  `nuget_list_add` stores `element?.asStableRef<Any>()?.get()` (`NugetRuntime.kt:163-166`), i.e.
  the Kotlin bridge object, so `it as Pet` succeeds; the owned transfer handle is disposed by the
  fill loop after `Add`; the token probe resolves a C# original and disposes the element box.
- **Verified, and it is a trap**: a walk over planned signatures alone does NOT reach legacy-only
  positions. With the gate lifted and the plan walk extended, three fixtures whose only interface
  component is `class Shelter { suspend fun pets(): List<Pet> }`, top-level
  `suspend fun pets(): List<Pet>`, or `class Shelter { fun stream(): Flow<List<Pet>> }` each bound
  (`Task<IReadOnlyList<global::Interop.IPet>> PetsAsync(...)`,
  `KotlinFlow<IReadOnlyList<global::Interop.IPet>> Stream()`) with no `[typeof(global::Interop.IPet)]`
  factory key and no backing `Pet` in the generated C#. Those members would compile and throw
  `NotSupportedException` at the first Kotlin-backed element. The walk must also cover legacy suspend
  returns and parameters and Flow/StateFlow element types (a KSType walk shaped like
  `erasedInterfaceArguments`, `NugetProcessor.kt:1881-1892`, recursing into collection type
  arguments), pinned by those three fixtures asserting the factory key; failing that, the legacy
  routes (`ForwardLegacyRouteCollections.kt:638`, `:745`, `:943`) keep refusing interface
  components.
- **Unverified**: nested factory spelling beyond one level and for ADR-134 interface-owned nested
  types. One level is pinned: `typeof(global::Interop.Aviary.IKeeper)` →
  `new global::Interop.Aviary.Keeper(handle, out _)`, `typeof(global::Interop.Aviary.Perch)`.

The reachability walk must return a set over every component. A walk that returns the first
interface only (the spike's one-liner) silently leaves `Map<Pet, Owner>` or `Map<Pet, String>`
without a factory key.

`DisposeMaterialized` (`CirMarshalRenderer.kt:549-552`) changes to dispose only `INugetHandle`
items: with interface elements, an element built before a mid-loop throw can be the consumer's own
C# object, which the marshaller must not dispose (**inferred** by reading).

The C#-side list gate (`tracker.needsList` → `includesList`) is the only list gate left: the list
exports are unconditional in `nuget-runtime` (`NugetRuntime.kt:148-166`, **verified** by reading;
`needsListSupport` no longer exists), so this change cannot make a Kotlin/C# list-support mismatch.
The ROADMAP item "the C#-side list gate and the Kotlin-side export gate are two independently
computed predicates" and its backlog file no longer apply and were removed.

## What shipped (2026-09-29)

Option 1, as decided, with these confirmed and added details.

- **Admission.** An interface is a component of `List`, `Set`, `Map` value and `Map` key (the key
  compares by reference on the C# side), at return, parameter, property get and set, top level,
  `suspend` and `Flow`, nested collections and the nullable arm included.
- **Element semantics.** A Kotlin-backed element arrives as the ADR-040 wrapper (`Pet`, never the
  concrete `Cat`); a C#-implemented element handed in comes back as the caller's own object
  (`Assert.Same`), through `echo` and `echoColours`.
- **Reachability.** The walk collects every interface in every collection component (element, key,
  value, through `Nullable`), including the legacy `suspend` and `Flow` routes, then closes to a
  fixed point over the reachable interfaces' own members (`interfaceMemberCollectionClosure`), so an
  interface whose only appearance is a `List<Other>` on another interface's member is reached too.
  The three legacy-route fixtures the ADR flagged as a trap (`NapRoster`, `ZoomiesFeed`, a top-level
  suspend) pin the factory key.
- **Nested keys.** `factoryEntries` now registers nested declarations, which fixes the
  `List<Aviary.Perch>` throw (the nested-class defect was pre-existing and independent of
  interfaces).
- **`DisposeMaterialized`** disposes only `INugetHandle` items. Every materialisable wrapper
  implements `INugetHandle` (confirmed by grep), so a throwing element factory disposes the wrappers
  it built and never the caller's own C# object.
- **Found on the way, fixed in the same change** (pre-existing from ADR-174): a module whose only
  `suspend`/`Flow` members sat on a reachable interface generated Kotlin without the coroutine
  imports (unresolved `CoroutineScope`, `launchForCSharp`). `hasSuspendFunctions` and
  `needsFlowImports` now consider reachable interfaces.
- **Evidence.** `IntegrationTests/InterfaceCollectionTests.cs` (22 facts),
  `Tier1InterfaceCollectionTest.kt`, four `LiveHandleTests` rows; `verify.sh` green (IT 2778, Leak
  124, processor 1446).

## Consequences

- `Tier1InterfaceReturnTest`'s `List of interface return fires a named skip diagnostic` flips to a
  binding assertion; ADR-040's Scope deferral of "collections of interfaces" is lifted; ADR-136's
  table row for `List<Pet>` elements changes from "refused" to "resolves"; ADR-173's nested-key
  deferral closes.
- Fixes two shipped runtime throws: an interface-element collection property getter, and a
  collection of a nested class.
- New `LeakTests/LiveHandleTests.cs` rows: interface list return, mixed Kotlin/C# list parameter,
  echo with a token hit, and a throwing element factory asserting a C# element is not disposed.
- Deferred: `(List<Pet>) -> Unit` callback payloads (callback rule, unchanged), `BoundInterface`
  components (ADR-088), generic-interface components.

## Amendment 2026-10-03: callback payloads

The interface reachability walk also enters a lambda parameter's payload types; see the "interface and unsigned payload coverage"
amendment to [ADR-160](160-callback-parameter-on-the-forward-plan.md).
