# A returned collection of handles should release its elements when it is disposed

- ROADMAP: GitHub issue #610 as of 2026-11-09 ("A returned collection of handles should release its elements when it is disposed").
- Researched: 2026-11-09, about 9 of 20 minutes. No `dotnet` SDK in the session and Gradle was off limits, so no spike ran; every compile or runtime claim below that is not a repo read is labelled inferred.
- Restatement: a C# consumer of a Kotlin member returning `List<ExportedClass>` (or an interface, sealed or abstract element) can write `using var pets = shelter.Pets;` and have every element the collection handed out released at the end of the scope, can keep one element past it through a named opt-out, and LeakTests proves zero leaked elements for a disposed collection. Direction: forward (Kotlin to C#). Kotlin declares the collection-returning member; C# consumes it.
- Verdict: fix. ADR-243 drafted (Proposed, `docs/adr/243-disposable-handle-collections.md`).

## Findings

1. The read is eager and the list holds no handle. `NugetMarshal.ReadList` counts, mints one box per element, builds a `List<T>`, disposes the list handle in `finally`, and on a throw disposes what it built. `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirMarshalRenderer.kt:782-795`. **Verified** by reading. `ReadSet` 798-810 and `ReadMap` 813-835 have the same shape. **Verified**.
2. The outer immutable list is `ReadList(...).AsReadOnly()`, so the runtime type today is `ReadOnlyCollection<T>`; `MutableList` returns the `List<T>` itself. `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardCirCollectionComponents.kt:458`. **Verified** by reading.
3. One choke point: `componentCollectionRead` (`ForwardCirCollectionComponents.kt:437`) is called by the plan route (`ForwardCirPlanProjection.kt:2495`, also 473, 497), the property route (`ForwardCirPropertyProjection.kt:693-695`), the legacy suspend route (`ForwardLegacyRouteCollections.kt:1662`) and the bridge (`CirBridgeRenderer.kt:708`). **Verified** by grep. The legacy companion-function route keeps its own inline loop and `IReadOnlyList` spelling (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirClassTranslator.kt:4537-4542`, inside `translateCompanionFunction` at 4475). **Verified** by reading. `translateCallbackMethod` spells `IReadOnlyList<$elemCs>` at `CirClassTranslator.kt:5872`. **Verified**.
4. `forwardPublicCsharpType` spells `IReadOnlyList<E>` for every position (`ForwardCsharpTypes.kt:42`), with 93 call sites including parameters. **Verified** by grep. The return-only spelling must be a separate function, not a change here.
5. `ForwardPublicSignature` carries `isOverride`, `isVirtual`, `isAbstract` (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardMarshallingModel.kt:952-972`). **Verified** by reading. C# implements Kotlin interfaces (ADR-084) and abstract members render `public abstract` (`CirClassRenderer.kt:625`, `CirSealedRenderer.kt:617`). **Verified**. Narrowing those members' return breaks implementers and overriders. **Inferred** from C# rules.
6. Only bridge-built wrappers are disposed: `DisposeMaterialized` and `ReleaseAbandoned` test `item is INugetHandle && item is IDisposable`, and `ReleaseAbandoned` walks nested enumerables and both halves of a dictionary, skipping `string` and `byte[]` (`CirMarshalRenderer.kt:840-866`). **Verified** by reading.
7. No forward identity cache: no `ConditionalWeakTable` or identity map in `nuget-processor/src/main`. **Verified** by grep. LeakTests row 4 confirms a Kotlin-backed element the caller passed in comes back through a factory call, as a fresh wrapper (`LeakTests/LiveHandleTests.cs:4283-4288`). **Verified** by reading.
8. Release is synchronous: `NugetHandles.retain` increments and `release` disposes the `StableRef` and decrements in the same call; `nuget_dispose` calls `release` (`nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetRuntime.kt:68-76,167-170`). **Verified** by reading. So `NugetMarshal.LiveHandles` reads the drop right after `Dispose()` without a GC.
9. `Settle()` forces `GC.Collect`, `WaitForPendingFinalizers` and `NugetMarshal.GcCollect` (`LeakTests/LiveHandleTests.cs:131-155`), and `AssertNoLeak` settles before and after (166-183). **Verified** by reading. Since ADR-187 an undisposed element is finalized during `Settle`, so an `AssertNoLeak` row cannot tell "released by the collection" from "released by the finalizer". The new rows must measure without collecting while keeping every element reachable.
10. Wrapper `Dispose()` is idempotent (`Interlocked.Exchange` to the `NugetKotlinHandle.Null` sentinel) and a zero-valued, never-closed `SafeHandle` argument passes to native as zero. `docs/adr/187-forward-finalizer-contract.md:85,88`. **Verified** by ADR-187's own spike.
11. A plan-route member passes `_handle` with no disposed check (`ForwardCirPlanProjection.kt:706-709`); the only `IsInvalid` guards found are on StateFlow members (`CirClassTranslator.kt:2108,2153`), listener pairs (`CirClassRenderer.kt:1080,1156,1260`) and flows (`CirFlowRenderer.kt:637`). The Kotlin `equals`/`hashCode`/`toString` exports call `handle.asStableRef<T>().get()` without a null guard (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/exports/ClassExports.kt:578-602`). **Verified** by reading. What Kotlin/Native does with a zero receiver is **inferred to crash the process**; nobody has run it. This is the load-bearing open claim.
12. `KotlinFlow<T>` is the precedent for a generated public `Kotlin*` type that implements `IDisposable` (`CirFlowRenderer.kt:84`). **Verified** by reading.
13. `ReadOnlyCollection<T>` is `public class ReadOnlyCollection<T> : IList<T>, IList, IReadOnlyList<T>`, unsealed, with `public ReadOnlyCollection(IList<T> list)` and `protected IList<T> Items`; `Count` and the indexer are not virtual. Source: https://github.com/dotnet/runtime/blob/main/src/libraries/System.Private.CoreLib/src/System/Collections/ObjectModel/ReadOnlyCollection.cs. **Verified** by reading the source, not compiled.
14. `System.Management.ManagementObjectCollection` is an `IDisposable` collection that releases the COM enumerator it owns; per-item disposal is community guidance. https://referencesource.microsoft.com/System.Management/managementobjectcollection.cs.html. **Inferred** from a search summary (learn.microsoft.com did not resolve in this session).
15. JVM, ObjC Export, Swift Export, JS and Wasm consumers are GC or ARC managed and have no disposable-collection analogue. Skipped as not decision-relevant.
16. Fixture already in place: `FosterHome` declares `val residents: List<Pet>`, `fun residentsNow(): List<Pet>`, `maybeResidents(): List<Pet?>`, `echo`, `petSet`, `byName`, `colours`, `litters(): List<List<Pet>>`, `residentsLater`, `headcounts` (`test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/petlist/FosterHome.kt:32-75`), with integration tests in `IntegrationTests/InterfaceCollectionTests.cs:39-215` and leak rows at `LeakTests/LiveHandleTests.cs:4240-4325`. **Verified** by reading. No new fixture is needed for v1.

## Recommendation

ADR-243 option 1. Generate a public `sealed class KotlinList<T> : ReadOnlyCollection<T>, IDisposable` with `T Keep(T element)` and an idempotent `Dispose()` that calls `NugetMarshal.ReleaseAbandoned` on every element not kept (that helper already skips C#-originals, strings and nulls and recurses into nested lists and maps). Build it in a new `NugetMarshal.ReadOwnedList<T>` from `componentCollectionRead`'s `LIST` branch whenever the element carries a handle, so the runtime type changes at the one choke point on the ADR-062 plan route. Narrow the *static* return type to `KotlinList<E>` only at direct, non-override, non-virtual, non-abstract, non-interface returns. Leave handle-free element lists (`string`, primitives, enums) unchanged.

Because of finding 11, the first commit is the red test `Pets_ElementUsedAfterCollectionDispose_ThrowsObjectDisposedException`. If it does not throw `ObjectDisposedException`, a disposed-receiver guard on wrapper members lands first as a prerequisite.

Rejected, one line each:
- Extension method over `IReadOnlyList<T>`: not the `using var pets = shelter.Pets;` statement, pollutes IntelliSense, cannot hold `Keep` state. Deferred as the answer for positions that stay `IReadOnlyList`.
- `IKotlinList<T>` interface on every member: still breaks C# implementers and overriders.
- Lazy view list: reverses ADR-011's eager copy, P/Invoke per index, the list becomes a handle.
- Ambient release scope: releases wrappers the caller returns or stores.
- Finalizer-only: forbidden by the issue.

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirMarshalRenderer.kt` (`ReadOwnedList`, `ReleaseAbandoned` nested-`KotlinList` branch)
- A renderer for `KotlinList<T>`: new `cir/CirOwnedCollectionRenderer.kt`, or next to `KotlinFlow` in `cir/CirFlowRenderer.kt`; gated by a `CirModel.kt` helper flag like `includesList` (`CirModel.kt:741`)
- `forward/ForwardCirCollectionComponents.kt` (`componentCollectionRead` `LIST` branch, a handle-carrying predicate over `BridgeType` with an exhaustive `when`)
- `forward/ForwardCsharpTypes.kt` (a return-position spelling beside `forwardPublicCsharpType`)
- `forward/ForwardCirPlanProjection.kt` and `forward/ForwardCirPropertyProjection.kt` (use the return spelling under the non-overridable rule)
- Possibly the wrapper member renderer, if the disposed-receiver guard is needed (finding 11)
- `nuget-processor/src/test/.../tier1/` a new Tier 1 cell for the return spelling and a strict compile of the compatibility shapes
- `IntegrationTests/InterfaceCollectionTests.cs` (or a new `DisposableCollectionTests.cs`)
- `LeakTests/LiveHandleTests.cs` (two rows; `AotLeakTests` compiles them)
- Docs (documenter): `docs/topics/supported-features.md` collection row, the collections topic page migration note, ADR-243 status

Overlap notes for parallel items: #611 (data-class snapshot) changes whether a data class element carries a handle, so the handle-carrying predicate in `ForwardCirCollectionComponents.kt` must read whatever #611 decides; expect a merge in that file and in `CirMarshalRenderer.kt`. #612 (exception types) touches `Throwable` elements, which read through `NugetErrorNative.BuildException` and carry no handle, so a `List<Throwable>` stays `IReadOnlyList` here; low overlap unless #612 edits `componentReadExpression`.

## Sample test

```csharp
// IntegrationTests
[Fact]
public void Residents_DisposedCollection_ReleasesElements_KeepsTheKeptOne()
{
    using var home = new FosterHome();
    IPet kept;
    IPet released;
    using (KotlinList<IPet> pets = home.Residents)
    {
        kept = pets.Keep(pets[0]);
        released = pets[1];
        Assert.Equal("Mylo", released.Name);
    }
    Assert.Equal("Oreo", kept.Name);                       // Oreo stayed
    Assert.Throws<ObjectDisposedException>(() => released.Name);  // Mylo was put away
    kept.Dispose();
}

[Fact]
public void Residents_StillAssignableToIReadOnlyList_AndDoubleDisposeIsSafe()
{
    using var home = new FosterHome();
    IReadOnlyList<IPet> pets = home.Residents;
    var owned = Assert.IsType<KotlinList<IPet>>(pets);
    owned.Dispose();
    owned.Dispose();
}

[Fact]
public void Echo_DisposedCollection_DoesNotDisposeTheCallersOwnDog() { /* echo a C# IPet, dispose, assert Disposals == 0 */ }
```

```csharp
// LeakTests/LiveHandleTests.cs: measured without Settle so the finalizer cannot be what released them
[Fact]
public void HandleListReturn_DisposedCollection_ReleasesEveryElement_WithoutCollecting()
{
    using var home = new FosterHome();
    Settle();
    long before = NugetMarshal.LiveHandles;
    var held = new List<KotlinList<IPet>>(50);
    for (int i = 0; i < 50; i++)
    {
        using KotlinList<IPet> pets = home.Residents;
        Assert.Equal(2, pets.Count);
        held.Add(pets);                       // every element stays reachable
    }
    long after = NugetMarshal.LiveHandles;   // no GC between
    GC.KeepAlive(held);
    Assert.True(after == before, $"expected {before} live handles after 50 disposed collections, got {after} (delta {after - before})");
}

[Fact]
public void HandleListReturn_KeptElement_SurvivesTheCollection_ThenReturnsToBaseline()
{
    // same loop with Keep(pets[0]): delta must be exactly +50 before disposing the kept list,
    // then 0 after disposing every kept element, again without Settle in between.
}
```

Red first: before the change the first row's delta is +100 (two element wrappers per iteration, list handle already released). The rows also run under NativeAOT through `AotLeakTests`.

## Deferred scope

- `Set<E>` and `Map<K, V>` returns (`KotlinSet<T> : HashSet<T>`, `KotlinDictionary<K, V> : Dictionary<K, V>`, keeping today's runtime base so casts still work).
- `MutableList<E>` returns (caller-added elements need an ownership rule).
- `suspend` (`Task<IReadOnlyList<E>>`, invariant, narrowing breaks typed locals), `Flow`/`StateFlow` list elements, lambda results, `List<T>` on generic carriers.
- Interface, abstract, open and override members (static type stays; runtime is `KotlinList`).
- The legacy companion-function route (`CirClassTranslator.kt:4537-4542`).
- `KeepAll()` / whole-list ownership transfer; the `Releasing()` extension for positions that stay `IReadOnlyList`.

## Open what-questions

1. Use of a released element (finding 11): if it crashes, does the disposed-receiver guard ship inside #610 or as a prerequisite item? Recommendation: prerequisite inside the same branch, first commit, because without it the feature makes a forgotten `Keep` a process abort.
2. Type name: `KotlinList<T>` (matches `KotlinFlow`, `KotlinStateFlow`) or something saying "owned snapshot" (`OwnedList<T>`)? Recommendation: `KotlinList<T>`, with XML docs saying it is a snapshot that owns its elements.
3. Opt-out name and shape: `Keep(T element)` returning the element, or `Detach`/`TakeOwnership`, or index-based `Keep(int)`? Recommendation: `Keep(T element)`; reference-based works unchanged for a future set and dictionary.
4. Should the static narrowing also apply to nested lists (`KotlinList<KotlinList<IPet>>`)? Recommendation: no in v1; inner static type stays `IReadOnlyList<IPet>` (runtime `KotlinList`), outer `Dispose` releases inner lists through their own `Dispose`.
5. Should a list of strings or primitives also become `KotlinList<T>` for uniformity? Recommendation: no; the type marks that the collection owns something.
6. Set/Map in v1 or deferred? Recommendation: deferred, but it is cheap (same helper shape) if the human wants consistency in one pass.
7. Binary-compatibility note: is a version bump with a migration note enough, given the return type is part of the member signature for prebuilt consumers? Recommendation: yes, documented on the collections topic page.

### Decided at the gate (2026-11-09, human)

- Scope: read-only `List` only in v1. `Set`, `Map`, `suspend` and `Flow` are deferred, each to a ROADMAP line.
- The remaining questions keep the memo's recommendations (`KotlinList<T>`, `Keep(T)`, the red use-after-dispose test first).
