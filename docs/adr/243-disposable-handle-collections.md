# ADR-243: Forward, a returned list of handles is a `KotlinList<T>` that releases the elements it handed out when disposed, with `Keep` as the named opt-out

## Status
Accepted (2026-11-23). Proposed 2026-11-09, GitHub issue #610. Implemented and verified; the text
below describes what shipped, with the corrections found during implementation folded in.

## Context

A Kotlin `List<Pet>` return binds as `IReadOnlyList<IPet>` (ADR-011, ADR-176). The read is eager:
`NugetMarshal.ReadList` counts the Kotlin list, mints one element box per index, builds a C#
`List<T>`, and disposes the list handle in its `finally`
(`nuget-processor/.../cir/CirMarshalRenderer.kt:782-795`, **verified** by reading). The outer
immutable list is then wrapped `.AsReadOnly()`
(`nuget-processor/.../forward/ForwardCirCollectionComponents.kt:458`, **verified** by reading). So
the collection itself holds no Kotlin handle; every element is a fresh caller-owned wrapper whose
`SafeHandle` the GC finalizes if forgotten (ADR-187).

To read values promptly and release them, a consumer today writes:

```csharp
IReadOnlyList<IPet> pets = shelter.Pets;
try { Render(pets); }
finally { foreach (IPet pet in pets) pet.Dispose(); }
```

The issue asks for: (1) one statement that releases every element the collection handed out
(`using var pets = shelter.Pets;`); (2) a named opt-out for an element the caller keeps past that
statement; (3) a LeakTests row showing zero leaked elements for a disposed collection. It forbids
relying on the finalizer (ADR-187 already covers forgotten wrappers) and changing every collection
member's return type without a migration note. A collection disposing an element the caller still
holds is called out as worse than today.

Constraints read from the repo:

- **One choke point for the read.** Every outer list read on the plan route
  (`ForwardCirPlanProjection.kt:2495`), the property route (`ForwardCirPropertyProjection.kt:693-695`),
  the legacy suspend route (`ForwardLegacyRouteCollections.kt:1662`) and the bridge
  (`CirBridgeRenderer.kt:708`) goes through `componentCollectionRead`
  (`ForwardCirCollectionComponents.kt:437`). **Verified** by grep. The legacy companion-function
  route's inline loop (`CirClassTranslator.kt:4537-4542`) is dead code: `translateCompanionFunction`
  has no caller, so companion functions take the plan route and narrow like any other member.
- **The public spelling is shared with parameters.** `forwardPublicCsharpType`
  (`ForwardCsharpTypes.kt:42`) spells `IReadOnlyList<E>` for every position, parameters included
  (93 call sites, **verified** by grep). The return spelling must change only at the return
  position.
- **C# can implement or override some members.** A Kotlin interface is implementable from C#
  (ADR-084), and an abstract/open member renders `abstract`/`virtual`
  (`ForwardMarshallingModel.kt:964-972` carries `isOverride`, `isVirtual`, `isAbstract`, **verified**
  by reading). Changing those members' return type breaks every C# implementer or overrider.
- **Only bridge-built wrappers may be disposed.** A list can carry a C#-implemented original the
  ADR-084 token probe resolved; `DisposeMaterialized` and `ReleaseAbandoned` already dispose only
  `INugetHandle` items and walk nested collections and both map halves
  (`CirMarshalRenderer.kt:840-866`, **verified** by reading).
- **No forward identity cache.** Each Kotlin-backed element read mints a fresh wrapper; the same
  Kotlin object twice in one list is two wrappers. **Verified** by grep (no `ConditionalWeakTable` or
  identity map in `nuget-processor/src/main`) and by LeakTests row 4's comment
  (`LeakTests/LiveHandleTests.cs:4283-4288`). So a returned element is never a wrapper the caller
  already held before the call.
- **Release is synchronous.** `nuget_dispose` calls `NugetHandles.release`, which disposes the
  `StableRef` and decrements `live` in the same call
  (`nuget-runtime/.../NugetRuntime.kt:68-76,167-170`, **verified** by reading).
- **The leak harness collects.** `Settle()` runs `GC.Collect`, `WaitForPendingFinalizers` and
  `NugetMarshal.GcCollect` (`LeakTests/LiveHandleTests.cs:131-155`, **verified** by reading), so after
  ADR-187 an `AssertNoLeak` row returns to baseline whether or not the collection disposed anything.
  A row that proves requirement (3) must measure without collecting while the elements are still
  reachable.

Prior art. JVM, ObjC Export, Swift Export, JS and Wasm consumers are garbage collected or ARC
managed; none of them surfaces a disposable collection, so they do not inform this decision
(skipped, not studied further). The .NET precedents are owning containers:
`System.Management.ManagementObjectCollection` implements `IDisposable` and releases the COM
enumerator it owns, while community guidance still disposes each `ManagementObject` separately
([reference source](https://referencesource.microsoft.com/System.Management/managementobjectcollection.cs.html),
[source.dot.net](https://source.dot.net/System.Management/System/Management/ManagementObjectCollection.cs.html);
**inferred** from a search summary, learn.microsoft.com was not reachable from this session).
`ReadOnlyCollection<T>` is an unsealed `public class ReadOnlyCollection<T> : IList<T>, IList,
IReadOnlyList<T>` with a public `ReadOnlyCollection(IList<T> list)` constructor and a protected
`Items`
([dotnet/runtime source](https://github.com/dotnet/runtime/blob/main/src/libraries/System.Private.CoreLib/src/System/Collections/ObjectModel/ReadOnlyCollection.cs),
**verified** by reading the source, not compiled).

## Alternatives Considered

### 1. `KotlinList<T> : ReadOnlyCollection<T>, IDisposable` at non-overridable direct returns (chosen)

A generated public `sealed class KotlinList<T>` deriving from `ReadOnlyCollection<T>`, built by a
new `NugetMarshal.ReadOwnedList<T>` wherever the list's element statically carries a handle.
`Dispose()` releases every element it holds that the bridge built and the caller did not `Keep`.
The *static* return type becomes `KotlinList<E>` only where C# cannot implement or override the
member; everywhere else the static type stays `IReadOnlyList<E>` and the runtime object is still a
`KotlinList<E>`.

Pros: `using var pets = shelter.Pets;` compiles; every runtime cast that works today against the
`ReadOnlyCollection<T>` it replaces (`IList<T>`, `IList`, `ICollection<T>`,
`ReadOnlyCollection<T>`) keeps working, because it is that type; assigning to `IReadOnlyList<E>`,
`IEnumerable<E>` and `var` still compiles; callers who never write `using` see no behaviour change.
Cons: a new public type per package; a narrower static type on the changed members.

### 2. Extension method over `IReadOnlyList<T>` (`using var scope = pets.Releasing();`)

No signature change, works at every position (Task, Flow, interface members). Cons: not the
`using var pets = shelter.Pets;` statement the issue names; appears in IntelliSense on every
`IReadOnlyList<T>` in the consumer's code; cannot remember a `Keep` on an immutable
`ReadOnlyCollection<T>` without returning a second object. Kept as the deferred answer for the
positions option 1 leaves at `IReadOnlyList<E>` (see Consequences).

### 3. Interface type `IKotlinList<T> : IReadOnlyList<T>, IDisposable` on every member

Lets interface and virtual members change too. Cons: still a hard break for every C# implementer
or overrider of those members (the return type must match), and a C# implementer cannot be asked to
produce bridge ownership. Rejected.

### 4. Lazy view: the list keeps the Kotlin list handle and wraps an element on index

"Handed out" would mean "indexed". Cons: reverses ADR-011's eager copy (which matches ObjC and
Swift Export), costs a P/Invoke per access, mints a new wrapper per repeated `pets[0]`, and the list
itself becomes a handle that must be finalized. Rejected.

### 5. Ambient release scope (`using (KotlinScope.Track()) { ... }`)

An `AsyncLocal` scope releasing every wrapper minted inside it. Cons: captures wrappers the caller
returns or stores, which is exactly the "disposes what the caller still holds" failure the issue
rejects. Rejected.

### 6. Finalizer-only release

Forbidden by the issue; ADR-187 already provides it for forgotten wrappers.

## Decision

### Consumer API

```csharp
// Kotlin: class Shelter { val pets: List<Pet> }  (Pet an interface, or any exported class)
using var shelter = new Shelter();

using var pets = shelter.Pets;            // KotlinList<IPet>
foreach (IPet pet in pets) Console.WriteLine(pet.Name);
// end of scope: every element released, promptly, on this thread

IPet favourite;
using (KotlinList<IPet> pets2 = shelter.Pets)
{
    favourite = pets2.Keep(pets2[0]);     // opt out: the collection will not release it
}
Console.WriteLine(favourite.Name);         // still live
favourite.Dispose();                       // the caller owns it now

IReadOnlyList<IPet> old = shelter.Pets;    // still compiles: KotlinList<IPet> is an IReadOnlyList<IPet>
```

Generated shape (sketch, per package, next to `KotlinFlow<T>` in `CirFlowRenderer.kt:84`'s family):

```csharp
public sealed class KotlinList<T> : ReadOnlyCollection<T>, IDisposable, INugetOwnedList
{
    private HashSet<object>? _kept;
    private int _disposed;

    internal KotlinList(IList<T> items) : base(items) { }

    /// Removes element from what Dispose releases and returns it; the caller now owns it.
    public T Keep(T element)
    {
        if (Volatile.Read(ref _disposed) != 0) throw new ObjectDisposedException(nameof(KotlinList<T>));
        if (element is null || !ContainsReference(element))
            throw new ArgumentException("Not an element of this collection.", nameof(element));
        (_kept ??= new HashSet<object>(ReferenceEqualityComparer.Instance)).Add(element);
        return element;
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;   // double Dispose is a no-op
        foreach (T item in Items)
            if (item is not null && _kept?.Contains(item) != true) NugetMarshal.ReleaseAbandoned(item);
    }
}
```

The shipped class also implements an internal marker, `INugetOwnedList`. `ReleaseAbandoned`
(`CirMarshalRenderer.kt:852-866`) already disposes only `INugetHandle` items, skips strings and
`byte[]`, and recurses into nested collections and both map halves. It gains one first branch: a
nested `INugetOwnedList` is released through its own `Dispose()`, so a `Keep` on an inner list is
honoured.

`NugetMarshal.ReadOwnedList<T>` is `internal`: the issue #126 rule bars a public member that exposes
`IntPtr`, and consumers never call it.

`Keep` is by element reference, not index, because the same rule then serves a future `KotlinSet` and
dictionary value/key, and because elements are distinct wrapper objects (no identity cache, see
Context), so a reference names exactly one slot. `ReferenceEqualityComparer` is .NET 5+; the
consumer floor is net10.0, so it is always available.

### Where the static type changes (v1)

The member's static return type becomes `KotlinList<E>` (or `KotlinList<E>?`, since `using` accepts
`null`) when all of these hold:

- the return is a Kotlin `List<E>` (read-only kind) at a direct return position: `val` property
  getter, method, top-level function, `object`/companion member, extension function;
- the member is not `isOverride`, `isVirtual` or `isAbstract`, and its owner is not an interface
  (`ForwardPublicSignature`, `ForwardMarshallingModel.kt:964-972`). That was not enough alone: a
  sealed arm implementing an interface member carries no override flag and hit CS0738, so the plan
  also carries `overridesNothing`, and the member narrows only when it is true;
- the property is not a `var`: one C# property type serves the getter and the setter, so a `var`
  keeps `IReadOnlyList<E>` (the getter still returns a `KotlinList<E>` at run time);
- `E` carries a handle: `ObjectHandle`, `Interface`, a sealed or abstract base, a generic class
  instantiation, or a nested collection whose components do (computed from `BridgeType` with an
  exhaustive `when`, no `else`, per CLAUDE.md). A value class is excluded: its C# side is a struct
  that `Dispose` cannot reach (**inferred** from reading, not run).

A nested list narrows only the outer type: `List<List<Kitten>>` is `KotlinList<IReadOnlyList<Kitten>>`,
and the inner lists are `KotlinList<Kitten>` at run time.

The runtime object is a `KotlinList<E>` wherever the element carries a handle, including the
positions whose static type stays `IReadOnlyList<E>` (nested inner lists, suspend results, Flow
elements, interface and virtual members, `var` properties), because the change is made once in
`componentCollectionRead`'s `LIST` branch (`ForwardCirCollectionComponents.kt:458`). A list of
strings, primitives, enums or other handle-free elements is unchanged: the type says the collection
owns something.

### Bridge mechanism

Unchanged on the wire. `nuget_list_count` / `nuget_list_get` mint one `StableRef` per element
(`NugetRuntime.kt:173-180`), the list handle is disposed in `ReadList`'s `finally`, and each element
wrapper owns its `SafeHandle` exactly as today. `KotlinList.Dispose()` calls each element's own
`Dispose()`, which swaps in the `NugetKotlinHandle.Null` sentinel with `Interlocked.Exchange` and
releases once (ADR-187, **verified** there), so a later `Dispose()` of an element by the caller, the
collection, or the finalizer stays a single release. No finalizer on `KotlinList<T>`.

### Mechanism claims and their status

- Eager read, list handle released inside `ReadList`, `.AsReadOnly()` outer wrap: **verified** by
  reading.
- Every outer list read funnels through `componentCollectionRead`: **verified** by grep, with the
  dead companion loop excluded.
- Element wrappers are always fresh for Kotlin-backed elements; C#-implemented originals are not
  `INugetHandle`: **verified** by reading (`CirMarshalRenderer.kt:840-846`, LeakTests row 4).
- `ReadOnlyCollection<T>` is unsealed with a public `IList<T>` constructor and protected `Items`:
  **verified** by reading the dotnet/runtime source; **not compiled**.
- Source compatibility of the narrower return (`IReadOnlyList<E> x = shelter.Pets;`, `var`, LINQ,
  `foreach`, passing to an `IEnumerable<E>` parameter): **verified** by compiling the consumer
  tests and the Tier 1 strict compile cell.
- Using a released element: **verified**, and it was the load-bearing gap. A disposed wrapper's
  zero handle reached Kotlin, and a data class's `Equals`/`GetHashCode`/`ToString` overrides aborted
  the process while other members threw `KotlinNullReferenceException`. The prerequisite shipped
  first: every planned wrapper member, and a data class's `Equals`, `GetHashCode` and `ToString`
  (receiver, and the `Equals` argument), now throws `ObjectDisposedException` on a disposed
  receiver. A plain class or interface wrapper's `object` members stay the managed defaults.
- NativeAOT: the type is a closed generic over reference types with no reflection, and
  `ReleaseAbandoned` uses only `is` tests: **inferred** AOT-safe. `AotLeakTests` compiles the
  LeakTests sources (CLAUDE.md), so the new rows run under NativeAOT through `scripts/verify.sh`.

## Consequences

- **Source change, with a migration note.** Members matching the v1 rule narrow from
  `IReadOnlyList<E>` to `KotlinList<E>`. Explicitly typed `IReadOnlyList<E>` locals, `var`,
  `foreach`, LINQ and argument passing keep compiling. A method group or delegate typed
  `Func<IReadOnlyList<E>>` bound to the member is fine (return covariance for delegates of reference
  types). Code that relies on the member's exact `MethodInfo.ReturnType` sees the new type. See
  Breaking below; the collections topic page carries the consumer migration note.
- **Opt-in release, universal disposal guard.** No caller who does not dispose the collection sees
  the collection release anything. The disposed-receiver guard is not opt-in: every consumer who
  used a disposed wrapper now gets `ObjectDisposedException` instead of a
  `KotlinNullReferenceException` or an aborted process. Generic code that disposes any
  `IDisposable` it is handed (a DI container that owns a registered instance, for example) will now
  release the elements.
- **Throw path.** `ReadOwnedList` keeps `ReadList`'s `catch { DisposeMaterialized(result); throw; }`,
  so a half-built list releases what it built. `DisposeMaterialized` also releases the nested sets
  and maps it already built, not only nested lists.
- **Sibling ADRs.** [ADR-242](242-scalar-data-classes-as-values.md) adds a `DataValue`
  `BridgeType` variant that carries no handle, so `List<Toy>` stays `IReadOnlyList<Toy>`; whichever
  lands second adds that arm to the handle-carrying `when`. [ADR-241](241-exported-kotlin-exception-types.md)
  exception elements read through `NugetErrorNative.BuildException` and carry no handle either.
- **Leak rows.** Six `LiveHandleTests` rows (`HandleListReturn_*`): the first two and the nested row
  measure without `Settle` while every element is kept reachable, the others cover a throwing
  element factory (flat and nested) and an undisposed collection returning to baseline. All run
  again under `AotLeakTests`.

## Breaking

Source-compatible for ordinary use, binary-incompatible for prebuilt consumers.

- **A narrowed member returns `KotlinList<E>`, not `IReadOnlyList<E>`** (property getters, methods,
  top-level functions, `object` and companion members and extensions whose element carries a
  handle, except `var` properties and overridable members). Affects every consumer of such a
  member. Source: `IReadOnlyList<E> x = ...`, `var`, `foreach`, LINQ and argument passing still
  compile; code that compares `MethodInfo.ReturnType` fails. Binary: the return type is part of the
  member signature, so a consumer built against the previous package throws
  `MissingMethodException` until rebuilt.
- **A disposed receiver throws `ObjectDisposedException`**, not `KotlinNullReferenceException`,
  including the `TryX` variants (behaviour). Affects every `catch (KotlinNullReferenceException)`
  that guarded a use after dispose, and a data class's `Equals`, `GetHashCode` and `ToString`, which
  used to abort the process. Update the catch sites.
- **A read that throws partway also releases nested sets and maps it already built** (behaviour).
  Observable only as fewer leaked wrappers after a failed read; no code change needed.

## Deferred

Each keeps today's type statically, with a `KotlinList<E>` at run time where the element is a list:

- `Set<E>` and `Map<K, V>` (`KotlinSet<T>` over `HashSet<T>`, `KotlinDictionary<TKey, TValue>` over
  `Dictionary<TKey, TValue>`, same `Keep`): unchecked by the issue; the read helpers are shaped the
  same way (`CirMarshalRenderer.kt:798-835`). `suspend` and `Flow` returns of handle elements are
  deferred with them (decided at the gate, 2026-11-09).
- `MutableList<E>` returns (`IList<E>` over a `List<T>` copy): the caller may add its own wrappers,
  which the collection must then not release; needs its own ownership rule.
- `suspend` returns (`Task<IReadOnlyList<E>>`): `Task<T>` is invariant, so narrowing to
  `Task<KotlinList<E>>` breaks explicitly typed `Task<IReadOnlyList<E>>` locals (**inferred**).
- `Flow<List<E>>` / `StateFlow<List<E>>` elements, lambda results, `T`-typed (`List<T>`) generic
  returns, interface, abstract, open and override members, and nested inner lists' static type.
- Lists of value-class elements and `var` properties (one C# type serves the getter and the setter).
- A `KeepAll()` / ownership transfer of the whole list, and a `Releasing()` extension for the
  positions that stay `IReadOnlyList<E>`.

## Discovered, not fixed

Both are **inferred**, not run.

- A disposed wrapper passed as an argument hands the zero handle to Kotlin
  (`ForwardCirPlanProjection.kt:1209`, `:1254`, `ForwardCirPropertyProjection.kt:956`); a nullable
  argument (`ForwardCirPlanProjection.kt:1291`) probably reads as `null` silently.
- `KotlinFunc`/`KotlinAction` `Invoke*` after dispose has no guard (`CirFunctionRenderer.kt:49` and
  nearby), probably aborting the process.
