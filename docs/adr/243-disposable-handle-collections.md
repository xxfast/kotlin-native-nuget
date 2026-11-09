# ADR-243: Forward, a returned list of handles is a `KotlinList<T>` that releases the elements it handed out when disposed, with `Keep` as the named opt-out

## Status
Proposed (2026-11-09, GitHub issue #610). Research memo:
[disposable-handle-collections](../research/roadmap/disposable-handle-collections.md).

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
  (`ForwardCirCollectionComponents.kt:437`). **Verified** by grep. The one exception found is the
  legacy companion-function route, which keeps its own inline loop (`CirClassTranslator.kt:4537-4542`,
  **verified** by reading).
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
public sealed class KotlinList<T> : ReadOnlyCollection<T>, IDisposable
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

`ReleaseAbandoned` (`CirMarshalRenderer.kt:852-866`) already disposes only `INugetHandle` items,
skips strings and `byte[]`, and recurses into nested collections and both map halves. It gains one
first branch: a nested `KotlinList` is released through its own `Dispose()`, so a `Keep` on an inner
list is honoured.

`Keep` is by element reference, not index, because the same rule then serves a future `KotlinSet` and
dictionary value/key, and because elements are distinct wrapper objects (no identity cache, see
Context), so a reference names exactly one slot. `ReferenceEqualityComparer` is .NET 5+, and the
generated code targets net8.0 and later (**inferred**, check the consumer TFM floor when
implementing).

### Where the static type changes (v1)

The member's static return type becomes `KotlinList<E>` (or `KotlinList<E>?`, since `using` accepts
`null`) when all of these hold:

- the return is a Kotlin `List<E>` (read-only kind) at a direct return position: property getter,
  method, top-level function, `object`/companion member, extension function;
- the member is not `isOverride`, `isVirtual` or `isAbstract`, and its owner is not an interface
  (`ForwardPublicSignature`, `ForwardMarshallingModel.kt:964-972`);
- `E` carries a handle: `ObjectHandle`, `Interface`, a sealed or abstract base, a generic class
  instantiation, a value class over one of those, or a nested collection whose components do
  (computed from `BridgeType` with an exhaustive `when`, no `else`, per CLAUDE.md).

The runtime object is a `KotlinList<E>` wherever the element carries a handle, including the
positions whose static type stays `IReadOnlyList<E>` (nested inner lists, suspend results, Flow
elements, interface and virtual members), because the change is made once in
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
- Every outer list read except the legacy companion route funnels through `componentCollectionRead`:
  **verified** by grep.
- Element wrappers are always fresh for Kotlin-backed elements; C#-implemented originals are not
  `INugetHandle`: **verified** by reading (`CirMarshalRenderer.kt:840-846`, LeakTests row 4).
- `ReadOnlyCollection<T>` is unsealed with a public `IList<T>` constructor and protected `Items`:
  **verified** by reading the dotnet/runtime source; **not compiled**.
- Source compatibility of the narrower return (`IReadOnlyList<E> x = shelter.Pets;`, `var`, LINQ,
  `foreach`, passing to an `IEnumerable<E>` parameter) compiles: **inferred** from C# conversion
  rules (implicit reference conversion to a base interface). **Not spiked**: no `dotnet` SDK in the
  research session. The first implementation step must compile these shapes in a Tier 1 strict
  compile cell (`-Pnuget.strictCompileCheck=true`) or a scratch project.
- Using a released element: **NOT VERIFIED, load-bearing.** ADR-187 verified that a zero-valued,
  never-closed `SafeHandle` argument passes through to native as zero
  (`docs/adr/187-forward-finalizer-contract.md:88`). The plan-route member body passes `_handle`
  with no `IsInvalid` check (`ForwardCirPlanProjection.kt:706-709`, **verified** by reading; the only
  `IsInvalid` checks found are on StateFlow, listener and flow members), and the Kotlin
  `equals`/`hashCode`/`toString` exports call `handle.asStableRef<T>().get()` with no null guard
  (`ClassExports.kt:578-602`, **verified** by reading). What a zero receiver does in Kotlin/Native
  (`ObjectDisposedException`, a mapped `NullPointerException`, or a process crash) is **inferred
  to be a native crash** and nobody has run it. If it crashes, a consumer who forgets `Keep` turns a
  leak into a process abort, which the issue names as worse than today. The implementation must
  therefore start with the red test
  `Pets_ElementUsedAfterCollectionDispose_ThrowsObjectDisposedException`; if it does not throw
  `ObjectDisposedException`, a disposed-receiver guard on wrapper members
  (`if (_handle.IsInvalid) throw new ObjectDisposedException(nameof(Pet));`) is a prerequisite and
  lands first. That guard is the same problem today for a caller who disposes an element and keeps
  using it, so it is a fix in its own right.
- NativeAOT: the type is a closed generic over reference types with no reflection, and
  `ReleaseAbandoned` uses only `is` tests: **inferred** AOT-safe. `AotLeakTests` compiles the
  LeakTests sources (CLAUDE.md), so the new rows run under NativeAOT through `scripts/verify.sh`.

## Consequences

- **Source change, with a migration note required.** Members matching the v1 rule narrow from
  `IReadOnlyList<E>` to `KotlinList<E>`. Explicitly typed `IReadOnlyList<E>` locals, `var`,
  `foreach`, LINQ and argument passing keep compiling (**inferred**, to be compiled first). What can
  break: a C# method group or delegate typed `Func<IReadOnlyList<E>>` bound to the member is fine
  (return covariance for delegates of reference types, **inferred**), but code that relies on the
  member's exact `MethodInfo.ReturnType` via reflection sees the new type. Binary compatibility is
  broken for prebuilt consumers of a republished package (the return type is part of the member
  signature); this ships in a version bump and the topic page and release notes carry the
  migration note.
- **Behaviour change only for opt-in callers.** No caller who does not dispose the collection sees a
  difference. Generic code that disposes any `IDisposable` it is handed (a DI container that owns a
  registered instance, for example) will now release the elements; noted in the migration text.
- **Throw path.** `ReadOwnedList` keeps `ReadList`'s `catch { DisposeMaterialized(result); throw; }`,
  so a half-built list still releases what it built.
- **Sibling Proposed ADRs.** [ADR-242](242-scalar-data-classes-as-values.md) adds a `DataValue`
  `BridgeType` variant that carries no handle, so `List<Toy>` stays `IReadOnlyList<Toy>`; whichever
  lands second adds that arm to the handle-carrying `when`. [ADR-241](241-exported-kotlin-exception-types.md)
  exception elements read through `NugetErrorNative.BuildException` and carry no handle either.
- **Leak rows.** Two new `LiveHandleTests` rows that measure without `Settle` while every element is
  kept reachable (see the memo's sample); both run again under `AotLeakTests`.

Deferred (each keeps `IReadOnlyList<E>` / its current type statically, with a `KotlinList<E>` at
runtime where the element is a list):

- `Set<E>` and `Map<K, V>` (`KotlinSet<T>` over `HashSet<T>`, `KotlinDictionary<TKey, TValue>` over
  `Dictionary<TKey, TValue>`, same `Keep`): unchecked by the issue; the read helpers are shaped the
  same way (`CirMarshalRenderer.kt:798-835`).
- `MutableList<E>` returns (`IList<E>` over a `List<T>` copy): the caller may add its own wrappers,
  which the collection must then not release; needs its own ownership rule.
- `suspend` returns (`Task<IReadOnlyList<E>>`): `Task<T>` is invariant, so narrowing to
  `Task<KotlinList<E>>` breaks explicitly typed `Task<IReadOnlyList<E>>` locals (**inferred**).
- `Flow<List<E>>` / `StateFlow<List<E>>` elements, lambda results, `T`-typed (`List<T>`) generic
  returns, interface, abstract, open and override members.
- The legacy companion-function route's inline loop (`CirClassTranslator.kt:4537-4542`).
- A `KeepAll()` / ownership transfer of the whole list.
