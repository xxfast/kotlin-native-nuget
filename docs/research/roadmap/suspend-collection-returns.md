# A `suspend fun` returning a nullable collection, or a collection of a sealed base, binds as `Task<IReadOnlyList<T>?>` / `Task<IReadOnlyList<Shape>>`

- ROADMAP (Phase 6, lines 81 and 82 as of 2026-09-29):
  - (a) "A `suspend fun` returning a **nullable** collection (`List<T>?`) is absent and named `SKIPPED_UNSUPPORTED_RETURN` since ADR-119, mirroring ADR-114's nullable-parameter deferral."
  - (b) "A `suspend fun` returning a collection of a **sealed base** (`List<Shape>`) is absent and named `SKIPPED_UNSUPPORTED_RETURN` since ADR-119: the classifier mints `SpecializedProtocol` for it and only the plan routes unwrap that (ADR-105's `sealedAsHandle`)."
- Researched: 2026-09-29, about 12 of 20 minutes used.
- Restatement: forward (Kotlin declares, C# consumes). `suspend fun f(): List<T>?` (and `Set<T>?`, `Map<K, V>?`, mutable variants) binds as `Task<IReadOnlyList<T>?>` (`Task<IReadOnlySet<T>?>`, `Task<IReadOnlyDictionary<K, V>?>`) completing with `null` for a Kotlin `null`; `suspend fun g(): List<Shape>` over an ADR-009 sealed base binds as `Task<IReadOnlyList<Shape>>` whose elements are the concrete arm wrappers. Both on the three suspend owners (class, sealed arm/base, top level). Both replace today's named skip.
- Verdict: fix. No new ADR; an amendment to ADR-119 (text drafted below). Not a new wire: both halves reuse shipped expressions.

## Findings

### Where the two refusals come from

1. **Verified by reading.** `legacyReturnShape` (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardLegacyRouteCollections.kt:539-642`) ends in `val collection = classify(type) as? BridgeType.Collection` then `isBridgeableComponent()` (`:636-641`). Both refusals fall out of that one line:
   - (a) `classify(List<T>?)` is `BridgeType.Nullable(Collection)`, so the `as? Collection` cast is null and the shape is `Refused`. The KDoc at `:532-537` states the nullable refusal is deliberate (ADR-114 mirror).
   - (b) `classify(List<Shape>)` is a `Collection` whose element is `SpecializedProtocol`; the cast succeeds but `isBridgeableComponent()` (`forward/ForwardCallablePlanner.kt:4078-4134`) has no `SpecializedProtocol` arm, so it is `false` and the shape is `Refused`. The parameter twin says the same on purpose: `ForwardLegacyRouteCollections.kt:158-159` ("Deliberately the un-rewritten classification: a `List<Shape>` of a sealed base stays refused").
2. **Verified by reading.** `BridgeType.sealedAsHandle()` (`ForwardCallablePlanner.kt:4051-4062`) already recurses through `Nullable` and `Collection` components, rewriting an eligible sealed base to `ObjectHandle(viaDiscriminator = true)`; a sealed type with a null `sealedHandle` (ineligible sealed interface, out-of-scope class) is returned untouched and stays refused. `legacyReturnShape` already applies it for the bare sealed return (`:553`, ADR-131); only the collection branch does not.
3. **Verified by reading.** The ADR-131 `Discriminated` gate (`:548`) tests the *declaration's* `sealed` modifier; for `List<Shape>` the declaration is `kotlin.collections.List`, so a sealed-element collection never enters it and reaches the collection branch. Moving the unwrap there cannot disturb the bare sealed arm.

### Kotlin half: the wire already carries both

4. **Verified by reading.** `exports/SuspendFunctionExports.kt:216-217` / `:247-248` bind `val result = <call>` as a *local* `val`, then `resultRefExpression` (`:260-262`) emits `if (result == null) null else NugetHandles.retain($boxed)` whenever the declared return is nullable (`isNullable` at `:76` and `:146`, read off `returnType.isMarkedNullable`, independent of the shape). So a null `List<T>?` result already leaves as a null result pointer (issue #108's wire). The boxed expression for a `Marshalled` shape is `collectionResultProjection("result", shape.type)` (`:272`), which sits inside the null test, so `result` is smart-cast to non-null there. Precedent in the same `when`: the `Enum` arm's `result.ordinal` (`:273-276`) relies on exactly this smart cast and ships (ROADMAP Phase 4 line 23). Not spiked on Kotlin/Native (no K/N scratch build in budget); the smart cast of a local `val` after `== null` is ordinary Kotlin and the enum arm is the in-repo proof.
5. **Verified by reading.** `collectionResultProjection` (`forward/ForwardKotlinPlanEmitter.kt:530-570`) only rewrites when `componentNeedsProjection()` holds (value class, enum, or a nested one); an `ObjectHandle` element (the rewritten sealed base) needs none, so a `List<Shape>` is pinned as-is, one `StableRef` on the list whose elements are the concrete arm instances. That is byte for byte what the synchronous plan route pins for `fun everyShape(): List<Issue54Shape>`.
6. **Consequence, verified by reading.** The Kotlin half needs **no** edit beyond what flows through `legacyReturnShape`: `legacyBoxedResult` (`SuspendFunctionExports.kt:271-277`) and the refusal filters (`:64`, and the class builder's twin) already key off the shape.

### C# half

7. **Verified by reading the generated artifact** (`kotlin-native-nuget/test-library/build/generated/ksp/mingwX64/mingwX64Main/resources/Interop.cs`, built on `main` 2026-09-29 10:30): the synchronous plan route reads a sealed-base list as
   ```csharp
   return NugetMarshal.ReadList<global::TestLibrary.Issue54.Issue54Shape>(listHandle, static h1 => NugetMarshal.FromHandle<global::TestLibrary.Issue54.Issue54Shape>(h1)).AsReadOnly();
   ```
   (line 36575, `Issue54Shapes.EveryShape()`), and the shipped ADR-119 suspend read is
   ```csharp
   t.SetResult(NugetMarshal.ReadList<global::TestLibrary.Issue122.Member>(resultPtr, static h1 => NugetMarshal.FromHandle<global::TestLibrary.Issue122.Member>(h1)).AsReadOnly());
   ```
   (line 55006). `legacyCollectionRead` (`ForwardLegacyRouteCollections.kt:831-832`) is `componentCollectionRead` at depth 0 (`forward/ForwardCirCollectionComponents.kt:270-310`), whose element read for a non-enum, non-value-class component is `NugetMarshal.FromHandle<csharpType>(h1)` (`:127-137`). So once the element is an `ObjectHandle(viaDiscriminator)`, the suspend route emits the sync route's expression verbatim.
8. **Verified by reading, runtime-proven by an existing test.** `FromHandle<Shape>` materialises a sealed base: `FromHandle<T>` falls through to `Materialize<T>` (`cir/CirMarshalRenderer.kt:158-325`), which looks `typeof(Shape)` up in `NugetMarshal.Factories` (`:142-156`). Every `CirSealedClass` registers its base there as `global::Ns.Shape.FromHandle(handle)` unconditionally (`cir/CirTranslator.kt:1083-1092`, `viaFromHandle = true`, issue #40), plus one entry per arm. The sealed base is not a value type and *is* a `Factories` key, so the ADR-173 token probe is skipped (`CirMarshalRenderer.kt:151`). `IntegrationTests/Issue54Tests.cs:107-131` exercise this exact read at runtime (`Issue54Sample.Shapes()`, `Issue54Shapes.EveryShape()`), asserting `Issue54Shape.Empty` then `Issue54Shape.Circle(1.0)`.
   **Dependency on the parallel `FromHandle<T>` item: none.** The sealed branch this item needs already exists through `Factories`; the parallel item's enum/sealed/value-class branches would only matter if it *changed* how a `Factories` hit resolves. If that item moves the sealed dispatch out of `Factories` into a `FromHandle<T>` branch, the read expression here is unchanged (it still calls `FromHandle<Shape>`), so the two compose in either merge order. Inferred, not verified against that item's diff (not visible from this worktree).
9. **Verified by reading.** The public spelling for the shape already goes through `collectionReturn.forwardPublicCsharpType()` (`cir/CirClassTranslator.kt:1982`, `cir/CirFunctionTranslator.kt:746`), which the sync route uses for `IReadOnlyList<global::…Issue54Shape>`; a nullable collection needs `?` appended there (the shape carries a bare `Collection`, not the `Nullable` wrapper).
10. **Verified by reading.** The completion arm is `method.asyncResultRead != null -> "t.SetResult(${method.asyncResultRead});"` (`cir/CirConcurrencyRenderer.kt:309`), placed before the primitive and nullable-object arms. `NugetMarshal.ReadList` on `IntPtr.Zero` would call `nuget_list_count(null)`: the read must be guarded, the same guard every other nullable legacy read uses (`legacyBytesRead`, `legacyHandleRead`, `legacyDiscriminatedRead`, `:498-527`, `:847-852`) and the sync route's `if (listHandle == IntPtr.Zero) return null;` (generated line 20161, `Clinic` `Ids()`).
11. **Verified by spike** (scratch dir, net8.0, `LangVersion 12.0`, `Nullable enable`, `TreatWarningsAsErrors true`, the `GeneratedBindingsCheck` settings; stub `ReadList`/`ReadSet`/`ReadMap` with the generated helpers' return types `List<T>`/`HashSet<T>`/`Dictionary<K,V>` from `CirMarshalRenderer.kt:494-525`): a guarded conditional handed straight to `SetResult` compiles with no warning for all three kinds, including a nullable-component map lambda in ADR-083's block form, and short-circuits the read on a null pointer:
    ```csharp
    var t = new TaskCompletionSource<IReadOnlyList<Shape>?>();
    t.SetResult(resultPtr == IntPtr.Zero ? null : M.ReadList<Shape>(resultPtr, static h1 => M.FromHandle<Shape>(h1)).AsReadOnly());
    var s = new TaskCompletionSource<IReadOnlySet<int>?>();
    s.SetResult(resultPtr == IntPtr.Zero ? null : M.ReadSet<int>(resultPtr, static h1 => M.FromHandle<int>(h1)));
    var m = new TaskCompletionSource<IReadOnlyDictionary<string, int?>?>();
    m.SetResult(resultPtr == IntPtr.Zero ? null : M.ReadMap<string, int?>(resultPtr, static k1 => "k", static v1 => { IntPtr valueHandle1 = v1; return valueHandle1 == IntPtr.Zero ? (int?)null : M.FromHandle<int?>(valueHandle1); }));
    ```
    `dotnet run` output (stub `ReadList` throws on `IntPtr.Zero`, so the first line proves the guard short-circuits):
    ```
    ptr=0: list=null set=null map=null
    ptr=1: list=1 set=present map=present
    ```

### What comes for free

12. **Set/Map: free, verified by reading.** The nullable fix is kind-agnostic (one `?` on the declared type, one guard on the read); `componentCollectionRead` already emits `ReadSet`/`ReadMap` (`ForwardCirCollectionComponents.kt:278-307`) and spike 11 covers all three conditional types.
13. **Nullable elements: already shipped, verified by reading.** `isBridgeableComponent` admits `Nullable` components (`ForwardCallablePlanner.kt:4122-4123`) and ADR-119 lists "nullable components" as supported; `List<String?>` binds today. The new case composes: `List<String?>?`.
14. **Sealed map key/value, nested `List<List<Shape>>`, nullable `List<Shape>?`: free, inferred.** `sealedAsHandle` recurses through all of them (finding 2) and the plan route admits the same shapes; not rendered by this research.
15. **Helper gates: free, verified by reading.** `legacyReturnCollectionKinds` (`ForwardLegacyRouteCollections.kt:816-823`) and the C# `tracker.trackCollection(collectionReturn)` (`CirClassTranslator.kt:1963-1965`, `CirFunctionTranslator.kt:699-701`) both read the `Marshalled` shape, so a module whose only collection is a nullable or sealed-element suspend return still emits `nuget_list_*`.
16. **Sealed base members (ADR-175) and sealed arms (ADR-118): free, verified by reading.** Every suspend owner calls the same `legacyReturnShape` (class and sealed routes at `CirClassTranslator.kt:1961`, top level at `CirFunctionTranslator.kt:697`, both Kotlin builders at `SuspendFunctionExports.kt:85`/`:155`).

### Adjacent gaps on the same file (not this item)

17. **Verified by reading.** `legacyFlowElementShape` (`ForwardLegacyRouteCollections.kt:716-750`) ends in the identical `classify(type) as? Collection` + `isBridgeableComponent()`, so `Flow<List<Shape>>` and `Flow<List<T>?>` carry the same two refusals one position over. The sealed-element half is the same one-token unwrap there, read through `legacyFlowElementReadArgument` (`:885-886`, which already calls `legacyCollectionRead`). Whether the Flow Kotlin export's element projection is shape-driven the same way was not read (`exports/FlowExports.kt`), so folding it in is inferred-safe only. The nullable-element half is not free: it is the `StateFlow<T?>` / `nuget_stateflow_value` problem ROADMAP line 77 names. See what-question 2.
18. **Verified by reading.** The diagnostic hint "return a non-nullable List/Set/Map, or a non-generic type" (`NugetProcessor.kt:773`) is shared by the suspend return and the Flow element (ADR-123). After this item it is still true for the Flow element and for every remaining suspend refusal (`Pair`, `Result`, `Flow`), since a nullable collection *return* no longer reaches it; leave the text, or drop "non-nullable" if what-question 2 folds the flow sealed half only.

## Recommendation

Lift both refusals inside `legacyReturnShape`, the classifier every suspend owner and both halves already share, and nothing else structural:

1. `ForwardLegacyReturnShape.Marshalled` gains `val nullable: Boolean = false`.
2. The collection branch (`ForwardLegacyRouteCollections.kt:636-641`) becomes, in effect:
   ```kotlin
   val rewritten: BridgeType = classify(type).sealedAsHandle()   // (b) ADR-105's rewrite
   val collection: BridgeType.Collection? =
     (if (rewritten is BridgeType.Nullable) rewritten.type else rewritten) as? BridgeType.Collection
   return if (collection != null && collection.isBridgeableComponent()) {
     ForwardLegacyReturnShape.Marshalled(collection, nullable = expanded.isMarkedNullable)  // (a)
   } else {
     ForwardLegacyReturnShape.Refused(expanded.legacyDescription())
   }
   ```
   The KDoc at `:532-537` and the class KDoc at `:369-377` drop the "nullable collection is refused" sentence. The parameter twin's comment at `:158-159` stays true (parameters are not in scope) but should say "the parameter side".
3. C# declared type: `collectionReturn.forwardPublicCsharpType()` plus `?` when `returnShape.nullable`, at `CirClassTranslator.kt:1982` and `CirFunctionTranslator.kt:746`. Cleanest as a `ForwardLegacyReturnShape.Marshalled.declaredCsharpType()` beside the existing `Handle`/`ValueClass`/`Enum` ones (`:482-489`), so the two translators cannot drift.
4. C# read: a `legacyCollectionRead(handle, type, nullable)` overload (or a `Marshalled.legacyMarshalledRead(handle)`) returning `"$handle == IntPtr.Zero ? null : ${legacyCollectionRead(handle, type)}"` when nullable, used at `CirClassTranslator.kt:2050-2051` and `CirFunctionTranslator.kt:804`. Spike 11 is the proof this compiles under `GeneratedBindingsCheck`.
5. Kotlin half: no edit (findings 4-6).
6. Fixtures, tests, docs as listed below.

Priced: 3 processor source files (`ForwardLegacyRouteCollections.kt`, `CirClassTranslator.kt`, `CirFunctionTranslator.kt`), about 20 changed lines, plus fixtures and tests. This is already the end-state route: it is ADR-119's classify-then-marshal-or-refuse shape with two refusals removed, and the plan route (ADR-062's direction) would read these results with the identical expressions (finding 7).

Rejected alternatives, one line each:
- A new `ForwardLegacyReturnShape.NullableMarshalled` variant: doubles every exhaustive `when` over the shape (two in the translators, one in the Kotlin builder) for a single boolean; the `Handle`/`ValueClass`/`Enum`/`Bytes` shapes all carry `nullable` as a field, so this follows them.
- Unwrapping the sealed base in `isBridgeableComponent` (teach it `SpecializedProtocol`): changes the planner's admission for every route, including positions ADR-105 deliberately gates on `sealedHandle`; the rewrite-then-admit order is what the plan routes do.
- Leaving (a) refused until the ADR-114 parameter half ships: the ROADMAP line's "one nullability rule" wording, but the return and parameter wires are independent (a null result pointer vs a nullable ABI parameter plus a guarded C# create/dispose), so coupling them delays a free fix. See what-question 1.
- Moving the suspend route onto the callable plan: ADR-119 alternative 4 and ADR-118's reason still hold (no async result shape on the plan).

### Draft ADR-119 amendment (do not apply until the item ships)

```markdown
## Amendment (<date>): nullable collection returns and sealed-base components

The two shapes the Scope section refused "until nullable threading on the legacy routes is done
once" and "rather than half-bound" now bind, because neither needs a new wire:

- **A nullable collection return** (`suspend fun maybe(): List<String>?`, and `Set`/`Map`/mutable
  variants) is `Marshalled` with `nullable = true`, declared `Task<IReadOnlyList<string>?>` and
  completed with `resultPtr == IntPtr.Zero ? null : NugetMarshal.ReadList<string>(...)`. The Kotlin
  half was already right: `resultRefExpression` pins `if (result == null) null else
  NugetHandles.retain(<projection>)` for any nullable return (issue #108), and the per-element
  projection runs on the smart-cast non-null `result`. Verified: the guarded conditional compiles
  under net8.0 / C# 12 / `TreatWarningsAsErrors` for all three helper return types and
  short-circuits the read on a null pointer (scratch spike, output in the research memo).
  This decouples the return side from ADR-114's nullable-*parameter* deferral, which stands
  unchanged: the two positions share no wire.
- **A collection of a sealed base** (`suspend fun sketch(): List<Shape>`) is classified through
  ADR-105's `sealedAsHandle()` before the `isBridgeableComponent()` admission, as the plan routes
  classify it. The element becomes `ObjectHandle(viaDiscriminator = true)`, the Kotlin half pins the
  list untouched (no component projection for a handle), and C# reads each element with
  `NugetMarshal.FromHandle<global::Ns.Shape>(h1)`, which resolves through the sealed base's
  `Factories` entry (issue #40) to `Shape.FromHandle`. This is the synchronous route's
  `EveryShape()` read verbatim. An ineligible sealed type (null `sealedHandle`) stays refused, named.

Still refused, named `SKIPPED_UNSUPPORTED_RETURN`: `Pair`, `Result<T>`, a user generic, `Flow<T>`.
The Flow/StateFlow element position (ADR-123) keeps both refusals: <or: gains the sealed half, per
the item's decision>.
```

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardLegacyRouteCollections.kt`: `Marshalled.nullable`, the collection branch of `legacyReturnShape` (`:636-641`), a `declaredCsharpType()` and a guarded read helper next to `legacyCollectionRead` (`:831`), KDoc at `:369-377`, `:532-537`, `:158-159`.
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirClassTranslator.kt`: `asyncReturnType` (`:1982`) and `asyncResultRead` (`:2050-2051`).
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirFunctionTranslator.kt`: `asyncReturnType` (`:746`) and `asyncResultRead` (`:804`).
- No edit expected: `exports/SuspendFunctionExports.kt`, `cir/CirConcurrencyRenderer.kt`, `cir/CirMarshalRenderer.kt`, `NugetProcessor.kt` (gates and diagnostics key off the shape).
- Tier 1: `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1LegacySuspendCollectionReturnTest.kt` flips its `maybe()` cell (`:60`, `:191-199`: "expected no MaybeAsync member") to a binding cell; a new cell for the sealed-element return on all three owners, plus a refusal cell for an ineligible sealed interface element (stays named).
- Fixtures: `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/issue122/AssignmentSample.kt` (`Headcount.maybe()` `:84-85` loses its "Refused" KDoc and gains a present twin); a sealed-element suspend fixture beside `test-library/.../issue54/Issue54Sample.kt` (proposed: `class Issue54Studio` with `suspend fun sketch(): List<Issue54Shape>` and `suspend fun sketchOrNull(present: Boolean): List<Issue54Shape>?`, plus top-level `suspend fun shapesLater(): List<Issue54Shape>`).
- Integration: `IntegrationTests/Issue122Tests.cs` (`:141-147`, drop `[InlineData("MaybeAsync")]`, add the nullable round-trips), `IntegrationTests/Issue54Tests.cs` (sealed-element async cells).
- Leak: `LeakTests/LiveHandleTests.cs`, a new row beside rows 9d/9e (`:1455-1480`).
- Docs: `docs/topics/supported-features.md` rows at `:104` ("the `suspend`/`Flow` routes do not" becomes "the `Flow` routes do not") and `:157`; `docs/topics/coroutines-and-flow.md` `:95` and `:581` (both name the two refusals); `docs/topics/collections.md` if it repeats `:104`; ROADMAP lines 81 and 82 deleted; ADR-119 amendment; this memo deleted.

## Sample test

xunit (`IntegrationTests/Issue122Tests.cs`), replacing the `MaybeAsync` absence row:

```csharp
[Fact]
public async Task MaybeAsync_NullableListReturn_CompletesWithNull()
{
    using var headcount = new Headcount(Names);

    IReadOnlyList<string>? maybe = await headcount.MaybeAsync();

    Assert.Null(maybe);
}

[Fact]
public async Task MaybeTagsAsync_NullableListReturn_PresentValueRoundTrips()
{
    using var headcount = new Headcount(Names);

    IReadOnlyList<string>? tags = await headcount.MaybeTagsAsync(present: true);

    Assert.Equal(["Oreo", "Mylo", "Biscuit"], tags);
}

[Fact]
public void MaybeAsync_IsDeclaredNullable()
{
    // Task<IReadOnlyList<string>?>: the `?` is an annotation, so read it off NullabilityInfo.
    var info = new NullabilityInfoContext().Create(typeof(Headcount).GetMethod("MaybeAsync")!.ReturnParameter);
    Assert.Equal(NullabilityState.Nullable, info.GenericTypeArguments[0].ReadState);
}
```

xunit (`IntegrationTests/Issue54Tests.cs`), sealed element on the suspend route, compared against the synchronous control on the same data:

```csharp
[Fact]
public async Task SketchAsync_SealedCollectionAtASuspendReturn_YieldsBothArmsInOrder()
{
    using var studio = new Issue54Studio();

    IReadOnlyList<Issue54Shape> shapes = await studio.SketchAsync();

    Assert.Collection(
        shapes,
        mylo => Assert.IsType<Issue54Shape.Empty>(mylo),
        oreo => Assert.Equal(1.0, Assert.IsType<Issue54Shape.Circle>(oreo).Radius));
}

[Fact]
public async Task ShapesLaterAsync_TopLevel_AgreesWithTheSynchronousRoute()
{
    IReadOnlyList<Issue54Shape> later = await Issue54Sample.ShapesLaterAsync();
    IReadOnlyList<Issue54Shape> now = Issue54Sample.Shapes();

    Assert.Equal(now.Select(s => s.GetType()), later.Select(s => s.GetType()));
}

[Fact]
public async Task SketchOrNullAsync_Absent_CompletesWithNull()
{
    using var studio = new Issue54Studio();

    Assert.Null(await studio.SketchOrNullAsync(present: false));
}
```

Leak row (`LeakTests/LiveHandleTests.cs`, beside 9e):

```csharp
// Row 9f. A suspend call returning a List of the sealed BASE, and its nullable twin. Kotlin pins
// one StableRef on the list; `ReadList`'s finally disposes it; each `nuget_list_get` mints one
// element ref that `Shape.FromHandle` hands to the arm wrapper, which the test disposes. The
// null arm mints nothing: a read that reached `ReadList(IntPtr.Zero)` would throw, and a guard
// that minted a list anyway would show here.
// Ledger per iteration: list +1/-1 (ReadList finally), element +1/-1 per arm (wrapper Dispose).
[Fact]
public async Task Suspend_ReturningAListOfTheSealedBase_ReturnsToBaseline()
{
    await AssertNoLeakAsync(async () =>
    {
        await using var studio = new Issue54Studio();
        IReadOnlyList<Issue54Shape> shapes = await studio.SketchAsync();
        foreach (Issue54Shape shape in shapes) shape.Dispose();
        Assert.Null(await studio.SketchOrNullAsync(present: false));
        IReadOnlyList<Issue54Shape>? present = await studio.SketchOrNullAsync(present: true);
        foreach (Issue54Shape shape in present!) shape.Dispose();
    });
}
```

(Whether `Issue54Studio` is `IDisposable` + `IAsyncDisposable` follows ADR-175/drain rules for a class with suspend members: use `await using`. Whether the arm wrappers returned from a collection need an explicit `Dispose` for the ledger, or are finalizer-collected in the harness, follow what the neighbouring `Issue54`/row-9e rows do; not checked.)

Tier 1 (`Tier1LegacySuspendCollectionReturnTest`), the generator-only pins:

```kotlin
assertTrue(result.generatedCSharp.contains("public Task<IReadOnlyList<string>?> MaybeAsync("))
assertTrue(result.generatedCSharp.contains(
  "t.SetResult(resultPtr == IntPtr.Zero ? null : NugetMarshal.ReadList<string>(resultPtr, "))
assertTrue(result.generatedCSharp.contains(
  "public Task<IReadOnlyList<global::Interop.Shape>> SketchAsync("))
assertTrue(result.generatedCSharp.contains(
  "static h1 => NugetMarshal.FromHandle<global::Interop.Shape>(h1)"))
assertTrue(result.diagnostics.none { it.contains("SKIPPED_UNSUPPORTED_RETURN") && it.contains("maybe") })
```

## Deferred scope

- ADR-114's nullable collection **parameter** on the legacy suspend and Flow routes (what-question 1).
- `Flow<List<T>?>` / `StateFlow<List<T>?>` nullable collection element: ADR-067 / ROADMAP line 77 territory (`nuget_stateflow_value` has no null arm).
- `Flow<List<Shape>>` sealed-base element, unless what-question 2 folds it in.
- `Pair`, `Result<T>`, user generics, `Flow<T>` suspend returns: unchanged, still named.

## Open what-questions

1. **Does item (a) include ADR-114's nullable-parameter half?** ROADMAP line 81 says "do it together with the ADR-114 half so the legacy routes keep one nullability rule". Cost of that half (inferred by reading, not priced line by line): a nullable ABI slot and `p?.let { lowered }` in `legacyLoweringStatement` (`ForwardLegacyRouteCollections.kt:1077-1081`) across `SuspendFunctionExports.kt` and `FlowExports.kt` (the `_collect`/`_value`/`_has_value`/`_set_value` family, two of which ROADMAP line 89 says no fixture reaches), plus a guarded `CreateList`/dispose in `CirCollectionParameters.kt:170-178`. Recommendation: **no**. Ship the return halves (no new wire, spiked) and reword line 81 to the parameter half only; the two positions share no wire, so "one nullability rule" is a documentation symmetry, not a mechanism one. Human decision: pending.
2. **Fold the `Flow<List<Shape>>` sealed-element unwrap (finding 17) into this item?** Same file, same one-token change in `legacyFlowElementShape`, and ADR-119's own amendment pattern (ADR-123) already mirrors suspend-return decisions into the flow element. Recommendation: **yes, if** the implementer confirms `FlowExports.kt`'s element boxing is shape-driven (not read here) and adds one integration cell; otherwise leave it and add nothing (there is no ROADMAP line for it today, so leaving it creates one; per the "don't grow the phase" rule, folding is preferred). Human decision: pending.
3. **Fixture home for the sealed-element cells:** a new `Issue54Studio` class beside `Issue54Sample.kt` (proposed), or a member on the existing `Issue54Shapes` object. Recommendation: the class, because an `object`'s suspend route has not been checked here for a static-owner scope, and a class exercises the drain. Human decision: pending.

## Inferred claims (not verified)

- Finding 8's composition with the parallel `FromHandle<T>` item in either merge order (that item's diff not visible).
- Finding 14: sealed map keys/values and nested `List<List<Shape>>` bind through the same change (not rendered).
- Finding 17: `FlowExports.kt` element boxing needs no change for a sealed element.
- The Kotlin/Native compile of `if (result == null) null else NugetHandles.retain(result.map { ... })` for a nullable collection with a projected component (e.g. `List<Temper>?`): relies on the in-repo `result.ordinal` precedent, not a K/N spike. If wrong, `packNuget` fails loudly at the Kotlin compile (not silent).
