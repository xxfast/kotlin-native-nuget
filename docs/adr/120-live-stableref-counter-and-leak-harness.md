# ADR-120: Forward, a live `StableRef` counter behind one `NugetHandles.retain`/`release` pair, read from C# as `NugetMarshal.LiveHandles`, with a leak-asserting xunit harness

## Status
Accepted

## Context

Every forward crossing that carries an object, a collection, a boxed element, a callback token, a
coroutine `Job`, an error envelope or an interface bridge mints a `kotlinx.cinterop.StableRef` on
the Kotlin side and hands the pointer to C#. Nothing counts them. Three handle leaks have been found
so far (ADR-073's parameter handle, ADR-099's per-element box, and the still-open outer
returned-collection handle in
[the backlog](../backlog/returned-collection-s-result-handle-leaks-if.md)), every one by an agent
reading source and noticing a `Dispose` after a `throw`. ROADMAP's "Performance & Resource Hygiene"
section asks for the counter first, and a harness that proves itself red on that open leak before
it is trusted green anywhere.

The contract this ADR satisfies (the restatement handed to research): a C# consumer gets a way to
read how many Kotlin `StableRef` handles the **forward** bridge currently holds; Kotlin declares the
export, the generated C# bindings consume it, an xunit harness in `IntegrationTests/` asserts the
count returns to baseline after each crossing family, and the harness goes red on the
returned-collection leak at `collectionMaterializingCore`
(`nuget-processor/.../forward/ForwardCirPlanProjection.kt:1231`).

Correction to the backlog note: it proposed exposing the counter "as a runtime slot beside ADR-089's
`WeakenGcHandle`/`ResolveGcHandle`". That table is the **reverse** pipeline's registration contract
(`nuget-plugin/.../NugetGenerateShimsTask.kt` / `NugetGenerateBindingsTask.kt`, guarded by
`NUGET_RUNTIME_CONTRACT_HASH`). Forward handles are minted by the Kotlin that the KSP processor
(`nuget-processor`) emits as `@CName` exports, and the two generators share no Kotlin runtime module.
So the counter is a forward-side export, and touching the reverse contract hash is unnecessary.

### What the processor emits today (all **Verified** by grep over `nuget-processor/src/main`)

229 lines mention `StableRef` / `asStableRef` / `.dispose()` across 34 files, but the *emitted*
Kotlin text has only five shapes, and only three of them touch the count:

| Shape | Emitted text | Sites | Counter effect |
|---|---|---|---|
| Mint, string renderer | `StableRef.create(X).asCPointer()` | 19 | +1 |
| Mint, KotlinPoet | `%T.create(X).asCPointer()` with `%T` = `Helpers.kt:17 stableRef` | ~35 | +1 |
| Mint into an error out-param | `errorOut.reinterpret<%T>().pointed.value = %T.create(buildError(e)).asCPointer()` | ~14 (subset of the above) | +1 |
| Release | `h.asStableRef<T>().dispose()` (`T` is `Any`, a class name, or `%T`), plus two `ref.dispose()` on an already-typed `StableRef` | 14 + 2 | -1 |
| Read, no release | `h.asStableRef<T>().get()` | 78 | none |

The mint sites are spread over `exports/GenericClassExports.kt` (the shared `nuget_list_create`,
`nuget_dispose`, `nuget_wrap_*`, `nuget_func*_invoke` helpers), `ClassExports.kt`,
`LambdaParameterExports.kt`, `StoredCallbackExports.kt`, `SuspendFunctionExports.kt`,
`SuspendStateFlowExports.kt`, `InterfaceBridgeExports.kt`, `InterfaceBridgeFactoryExports.kt`,
`SealedClassExports.kt`, `InterfaceExports.kt`, `FunctionExports.kt`, `GenericFunctionExports.kt`,
`forward/ForwardKotlinPlanEmitter.kt` and `forward/ForwardPropertyKotlinEmitter.kt`: 14 renderer
files. Every one is a renderer of Kotlin source text; there is no runtime module to hook, so the
chokepoint has to be *emitted* into the generated library and every renderer has to be pointed at it.

Two precedents already exist in the repo for a bridge-side observability counter, both reverse or
ADR-084 machinery (**Verified**, `cir/CirBridgeRenderer.kt:39-55`): `NugetBridge.GcCollect()` P/Invokes
`nuget_gc_collect`, and `NugetBridgeState.ReleasedCount` is an `internal static int` bumped with
`Interlocked.Increment` on the cleaner release path, "the release path's only observable".
`IntegrationTests/BidirectionalTests.cs:207-258` drives it with `GC.Collect()` +
`WaitForPendingFinalizers()` + `NugetBridge.GcCollect()` rounds and a 5-second `ReleaseFiredWithin`
poll. The forward counter follows that shape exactly.

## Alternatives Considered

### 1. One generated `NugetHandles` object in `CNameExports.kt`, every renderer routes mints and releases through it, one export `nuget_live_handles` (chosen)

Emit, once per library, beside `nuget_dispose` in `addNugetHelperExports`:

```kotlin
internal object NugetHandles {
  val live: kotlin.concurrent.AtomicLong = kotlin.concurrent.AtomicLong(0L)
  fun retain(value: Any): COpaquePointer {
    live.incrementAndGet()
    return StableRef.create(value).asCPointer()
  }
  fun release(handle: COpaquePointer) {
    handle.asStableRef<Any>().dispose()
    live.decrementAndGet()
  }
}

@CName("nuget_live_handles")
fun export_nuget_live_handles(): Long = NugetHandles.live.value
```

Every emitted `StableRef.create(X).asCPointer()` becomes `NugetHandles.retain(X)`; every emitted
`h.asStableRef<T>().dispose()` becomes `NugetHandles.release(h)`; `Helpers.kt:17 stableRef`
becomes a `nugetHandles` `ClassName` so the KotlinPoet sites change only their format string. The
two `ref.dispose()` sites on a typed `StableRef` (`StoredCallbackExports.kt:238`,
`InterfaceBridgeExports.kt:182`) become `NugetHandles.release(ref.asCPointer())`.

Pros: one counter, one increment per mint, no per-site bookkeeping, no runtime module. The ABI
contract picks the new pair up with no registration (see Decision). Cons: 14 renderer files and
22 processor unit tests that pin `StableRef.create` in generated text change in one commit.

### 2. Count at the C# side only (`Interlocked` around every `Native_dispose` and every handle-returning P/Invoke)

Rejected. C# sees the handle only after Kotlin minted it and cannot see error-envelope mints the
Kotlin side makes on an exception path, nor a mint Kotlin makes and never returns (which is exactly
the leak class the harness exists to catch). A counter that cannot observe the failure it is for is
not a counter.

### 3. Ask Kotlin/Native for its own count

**Inferred** (Kotlin/Native stdlib and `kotlinx.cinterop` docs, not spiked): `StableRef.create`
lowers to the runtime intrinsic `createStablePointer` and no public API reports how many are
outstanding; the legacy memory model's `Platform.isMemoryLeakCheckerActive` report was an
at-exit object count, not a queryable `StableRef` count, and does not exist under the current
memory manager. Rejected as unavailable.

### 4. Debug-only counter (compiled in under a `binaryOption` or a `nugetInterop { ... }` DSL flag)

Rejected, per the backlog note's own argument: an atomic increment beside a P/Invoke is noise, and a
counter that is absent in production is one nobody trusts. Cost claim is **inferred** (no
benchmark exists yet; the microbenchmark item in ROADMAP is where it gets a number); the decision
does not rest on the exact ratio, only on "an uncontended atomic add is orders of magnitude below a
managed-to-native transition", which is not in dispute.

### 5. Scope fork: also route the reverse-side mints through the same object

`nuget-plugin/.../NugetGenerateBindingsTask.kt` has 10 `StableRef.create`/`asStableRef`/`dispose`
sites (**Verified** by grep, not the ~15 estimated), for ADR-085's ctx `StableRef` and
`nuget_kotlin_release`. The plugin-generated `{Iface}Bindings.kt` compiles into the same
Kotlin/Native binary as `CNameExports.kt`, so it *could* reference
`io.github.xxfast.kotlin.native.nuget.generated.NugetHandles`. Price: 1 file, 10 sites, no contract
hash change (it calls the forward object, adds no slot). Cost: the plugin's output would depend on
a symbol the processor emits under the `needsHelpers` gate, coupling two generators that today share
nothing, and a reverse-only library (no forward exports) would fail to compile. Deferred: the
restatement is forward-only, and ADR-085's ctx release is already observable via its own
SafeHandle path. Revisit if the reverse side gains its own runtime module.

## Decision

Alternative 1. Forward-only. Always compiled in.

### Kotlin side

- `NugetHandles` and `export_nuget_live_handles` are emitted from `addNugetHelperExports`
  (`exports/GenericClassExports.kt`, beside `export_nuget_dispose` at `:435-440`), under the
  existing `needsHelpers` gate (`NugetProcessor.kt:1667-1669`). **Verified**: that gate is at least
  as broad as the C# `needsCoreMarshal` gate that emits `NugetMarshal` (comment and code at
  `NugetProcessor.kt:1657-1669`), which is the same relationship `nuget_dispose` already relies on.
  **Wrong; see "Amendments after implementation" below.** The counter is emitted unconditionally
  from `NugetProcessor.kt` (`addNugetHandlesCounter()`), not gated by `needsHelpers`; the C# import
  stays under `needsCoreMarshal`.
- **Verified** by konanc spike (Kotlin/Native 2.4.10 prebuilt, `-produce library`, macOS arm64
  host): `kotlin.concurrent.AtomicLong` needs **no** opt-in; `incrementAndGet`/`decrementAndGet`/
  `.value` compile clean with only the `ExperimentalForeignApi` + `ExperimentalNativeApi` opt-ins
  the generated file already carries (`NugetProcessor.kt:1313-1314`). The common
  `kotlin.concurrent.atomics.AtomicLong` still requires `@ExperimentalAtomicApi` on 2.4.10 (spike
  output: three `this declaration needs opt-in` errors), so it is not used. `mingwX64` is
  **inferred** to behave identically (same Native stdlib; not spiked, no Windows toolchain here).

  ```
  $ ~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.10/bin/konanc Probe.kt -produce library -o probe1
  (no diagnostics)                        # Probe.kt: kotlin.concurrent.AtomicLong + @CName export
  $ ls
  Probe.kt Probe2.kt probe1.klib
  $ konanc Probe2.kt -produce library -o probe2   # kotlin.concurrent.atomics.AtomicLong
  Probe2.kt:3:24: error: this declaration needs opt-in ... '@kotlin.concurrent.atomics.ExperimentalAtomicApi'
  ```

- `release` disposes through `asStableRef<Any>()` regardless of the site's original `T`.
  **Verified** safe by precedent: the shipped shared `nuget_dispose` already disposes every handle
  kind that way (`GenericClassExports.kt:438`).
- Ordering: `retain` increments before `StableRef.create` (a throwing `create` cannot happen for a
  non-null `Any`); `release` decrements after `dispose`. Net effect is a count that is never below
  the true live count at a quiescent point.

### C# side

`CirMarshalRenderer.kt`, beside `Native_dispose` (`:50-51`):

```csharp
[DllImport("<lib>", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_live_handles")]
private static extern long Native_live_handles();

/// The number of Kotlin StableRef handles the forward bridge currently holds.
/// Baseline is whatever the process has alive; compare deltas, not absolutes.
internal static long LiveHandles => Native_live_handles();
```

`internal`, matching `NugetBridgeState.ReleasedCount`; the source shim compiles into the consumer
(ADR-050), so the consumer's own code and its test project see it. **Verified**:
`IntegrationTests/BidirectionalTests.cs:183,225` already reads the internal
`NugetBridgeState.ReleasedCount` from the test assembly.

### ADR-055 contract

**Verified** from `ForwardAbiContract.kt`: `kotlin(file, expectedNames)` (`:214-217`) checks every
generated `FunSpec` whose `@CName` appears in the C# import name set, and `csharpLegacy` (`:186-212`)
harvests raw-text `[DllImport(... EntryPoint = "...")]` lines from the rendered C#. A new export plus
a new raw-text import therefore registers itself; nothing else to touch. `long`/`kotlin.Long` map
to `ForwardAbiType.LONG` on both halves (`:411`, `:439`). An import without the export, or vice
versa, fails KSP generation with the existing "missing Kotlin export"/"missing C# import" message.

### Harness (`IntegrationTests/LiveHandleTests.cs`)

**Verified**: `IntegrationTests/xunit.runner.json` sets `parallelizeTestCollections: false`, so
no other test mints handles while a theory below runs; a `[Collection]` is not needed for
isolation, only for grouping.

```csharp
public class LiveHandleTests
{
    private static void Settle()
    {
        for (int round = 0; round < 5; round++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            NugetBridge.GcCollect();   // ADR-084 cleaner round; harmless when nothing is pending
            Thread.Sleep(25);
        }
    }

    private static void AssertNoLeak(Action crossing, int iterations = 50)
    {
        Settle();
        long before = NugetMarshal.LiveHandles;
        for (int i = 0; i < iterations; i++) crossing();
        Settle();
        long after = NugetMarshal.LiveHandles;
        Assert.True(after == before, $"expected {before} live handles after {iterations} crossings, got {after}");
    }

    [Fact] public void ClassHandle_UsingDispose() => AssertNoLeak(() => { using var c = new Cat("Oreo", 9); c.Speak(); });
    [Fact] public void ListReturn_Materialized() => AssertNoLeak(() => { using var n = new Newsroom(); foreach (var s in n.Archive()) s.Dispose(); });
    [Fact] public void ListParameter() => AssertNoLeak(() => Zoo.Count(new[] { "a", "b" }));
    [Fact] public void Callback_SubscribeUnsubscribe() => AssertNoLeak(() => { using var b = new Bell(); var s = b.OnRing(_ => { }); s.Dispose(); });
    [Fact] public void CSharpImplementedInterface_Argument() => AssertNoLeak(() => { using var c = new Cat("Oreo", 9); c.Interview(new Dog("Rex")); });
    [Fact] public async Task Flow_EnumerateToCompletion() => await AssertNoLeakAsync(async () => { using var n = new Newsroom(); await foreach (var s in n.Feed()) s.Dispose(); });
    [Fact] public async Task Suspend_Completes() => await AssertNoLeakAsync(async () => { using var n = new Newsroom(); (await n.LatestAsync()).Dispose(); });
}
```

(Fixture names are illustrative; the implementing agent picks real `TestLibrary` members per row.
**The shipped settle/assert logic replaced this sketch; see "Amendments after implementation"
below.**)

**The red case.** The outer returned-collection loop
(`ForwardCirPlanProjection.kt:1231-1300`, **Verified**) reads each element through
`NugetMarshal.FromHandle<T>`, which for a wrapper type falls to `Materialize<T>`
(`CirMarshalRenderer.kt:113-116`, **Verified**), which looks the element type up in
`NugetMarshal.Factories`, an `internal static readonly Dictionary<Type, Func<IntPtr, object>>`
(`:100`, **Verified**). The field is `readonly`; the dictionary is not. A test can therefore replace
one entry with a factory that throws on its second call, which is a deterministic mid-loop throw on
the outer level with no fixture change:

```csharp
[Fact]
public void ListReturn_ThrowingElementFactory_ReleasesTheListHandle()
{
    Func<IntPtr, object> original = NugetMarshal.Factories[typeof(TopStory)];
    int calls = 0;
    NugetMarshal.Factories[typeof(TopStory)] = handle =>
    {
        if (++calls == 2) { NugetMarshal.Dispose(handle); throw new InvalidOperationException("boom"); }
        return new TopStory(handle);
    };
    try
    {
        Settle();
        long before = NugetMarshal.LiveHandles;
        using var newsroom = new Newsroom();
        Assert.Throws<InvalidOperationException>(() => newsroom.Archive());   // 2+ elements: Newsroom.kt:41
        Settle();
        Assert.Equal(before, NugetMarshal.LiveHandles);   // RED today: the listHandle Dispose sits after the loop
    }
    finally
    {
        NugetMarshal.Factories[typeof(TopStory)] = original;
    }
}
```

The test disposes the element box it was handed before throwing, so the only unreleased handle is
the list's own: the delta is exactly +1 per call, which is the leak the backlog names. When the outer
loop is routed through ADR-099's `finally`-guarded `ReadList`/`ReadSet`/`ReadMap`, the same test
goes green with no edit. **Wrong; delta was +3, not +1. See "Amendments after implementation" below
for the accounting.** Whether `Factories` stays the injection seam after the ADR-094 table
evolves is a maintenance question, not a correctness one; if a future change makes the dictionary
immutable, the fallback trigger is a `test-library` class whose C# wrapper is hand-shadowed, which
is more machinery, so keep the dictionary mutable.

**Inferred (not run)**: the element box a `FromHandle<T>` wrapper factory receives is owned by the
wrapper it constructs (`new T(handle)`), so a factory that throws *without* disposing it leaks that
box too (+2 per call). The test above disposes it explicitly to keep the assertion about the list
handle alone; the implementing agent should confirm the +1 by reading the number before making the
fix, and record it in the test's failure message.

### Which families are strict and which are eventual

| Crossing family | Release path | Harness assertion |
|---|---|---|
| Class handle via `using`/`Dispose` | `{type}_dispose` on `Dispose()` (**Verified**, `ClassExports.kt:78`) | strict |
| `string` on the ordinary route | no `StableRef` (UTF-8 wire); as an element, `nuget_wrap_string` box disposed by the writer's `finally` (ADR-099) | strict |
| `List`/`Map`/`Set` parameter | `CreateList` + per-element box, both released in `finally` (ADR-073/099) | strict |
| `List`/`Map`/`Set` return, happy path | list handle disposed as the loop's last statement; element boxes owned by wrappers the test disposes | strict |
| Callback subscribe/unsubscribe | `StableRef.create(unregister)` returned, `ref.dispose()` on unsubscribe (**Verified**, `StoredCallbackExports.kt:216,238`) | strict |
| ADR-084 C#-implemented interface argument | transfer `StableRef` disposed after the native call (**Verified** by the `BidirectionalTests.cs:214-217` comment and ADR-084 Decision); the Kotlin bridge *object* is GC-owned but is not a `StableRef` | strict for the counter |
| ADR-085 Kotlin-implemented interface | ctx `StableRef` minted by the **plugin**, freed by a .NET `SafeHandle` finalizer | not counted in v1 (scope fork above) |
| `Flow` enumerate-to-completion | per-item box unwrapped and disposed by C#; job `StableRef` disposed by the enumerator. **Verified, not Inferred** (see "Amendments after implementation" below): `nuget_scope_dispose` routes through `NugetHandles.release`. | strict |
| `Flow` abandoned via `DisposeAsync` | `NugetJobNative.Cancel` from `DisposeAsync` (**Verified**, `:175-180`); handle dispose after cancel **Verified, not Inferred**: same `nuget_scope_dispose` route. | strict after `await DisposeAsync()`; the shipped test asserts strict, not eventual/polled (see amendment) |
| `suspend` call completes | result box unwrapped+disposed by C#, job handle disposed on completion. **Verified, not Inferred**: `nuget_job_dispose` routes through `NugetHandles.release`. | strict |
| `suspend` cancelled | job handle dispose after cancel. **Verified, not Inferred**: same `nuget_job_dispose` route. Not shipped as its own row; the two rows above cover the strict assertion. | not a separate shipped test |

Where a row says **Inferred**, a strict assertion that fails on implementation is a finding about
the *bridge*, not the harness: the counter's job is exactly to turn those into numbers.

### Renderer change list

`Helpers.kt` (the `stableRef` `ClassName` becomes `nugetHandles`), `GenericClassExports.kt`,
`ClassExports.kt`, `LambdaParameterExports.kt`, `StoredCallbackExports.kt`,
`SuspendFunctionExports.kt`, `SuspendStateFlowExports.kt`, `InterfaceBridgeExports.kt`,
`InterfaceBridgeFactoryExports.kt`, `SealedClassExports.kt`, `InterfaceExports.kt`,
`FunctionExports.kt`, `GenericFunctionExports.kt`, `forward/ForwardKotlinPlanEmitter.kt`,
`forward/ForwardPropertyKotlinEmitter.kt`, `cir/CirMarshalRenderer.kt` (the import and property),
`NugetProcessor.kt` (the `StableRef` file imports at `:1183-1188` stay; `NugetHandles` lives in the
same file). Plus the 22 unit tests under `nuget-processor/src/test` that assert `StableRef.create`
or `asStableRef` in generated text, and one new `IntegrationTests/LiveHandleTests.cs`.

## Consequences

- Every forward mint and release is one `AtomicLong` operation heavier. Unmeasured; the ROADMAP
  microbenchmark item records it.
- The generated `CNameExports.kt` gains one object and one export; the generated `NugetMarshal`
  gains one import and one property. No public C# surface changes (`internal`).
- ADR-055 coverage grows by one entry point automatically.
- `NugetMarshal.Factories` becomes a load-bearing test seam. Its mutability is now a documented
  property, not an accident.
- Deferred: reverse-side mints (10 sites in `NugetGenerateBindingsTask.kt`); a Kotlin-side
  "object actually freed after last dispose" check (its own ROADMAP line); a per-type breakdown of
  the count (one global `Long` is enough to go red; naming the leaking type is what
  `NUGET_INTEROP_TRACE` and source reading are for).
- The harness is only trusted after the red case above is observed red, then green after the
  outer loop is routed through `ReadList`/`ReadSet`/`ReadMap`. Ship the counter, the red test, and
  the fix in that order, in that commit sequence, so the red run is on record.

## Amendments after implementation (2026-09-09)

The implementation run corrected six mechanism claims this ADR got wrong or left as a sketch. Recorded
here rather than silently edited into the body, per the "the ADR is wrong, fix it, don't bend the code"
rule.

1. **The `needsHelpers` gate claim was wrong.** The Decision section's Kotlin-side bullet said
   `NugetHandles`/`export_nuget_live_handles` are emitted under the existing `needsHelpers` gate. They
   are not: the counter is emitted **unconditionally** from `NugetProcessor.kt`
   (`addNugetHandlesCounter()`). A module exporting only value-class members or extension properties,
   which leaves `needsHelpers` off, still mints handles through it, so gating the counter the same way
   would have left it blind on exactly those modules. Discovered because 12 Tier 1 tests failed on an
   unresolved `NugetHandles` reference before this was corrected. The C# side is unaffected: the
   `NugetMarshal.LiveHandles` import stays under the pre-existing `needsCoreMarshal` gate, as designed.

2. **The red-case delta was 3, not 1.** `LiveHandleTests.ListReturn_ThrowingElementFactory_ReleasesTheListHandle`
   measured a delta of +3 on the first red run, not the +1 this ADR's harness section predicted. The
   accounting: (a) the returned list's own handle, exactly as predicted; (b) element 0's wrapper,
   stranded in the half-built `List` because `ReadList`/`ReadSet`/`ReadMap` (ADR-099) disposed the
   collection handle in their `finally` but nothing disposed elements already added to `result` before
   the throw, a bug in ADR-099's own helpers, not new to this feature, closed by the same `catch {
   DisposeMaterialized(result); throw; }` addition recorded in
   [ADR-099's amendment](099-nested-collection-components.md); (c) the `Newsroom` fixture's own handle,
   a test-scoping issue, not a bridge leak: this ADR's illustrative red-case sketch used a
   method-scoped `using var newsroom` that was still alive when `after` was read, so the shipped test
   instead scopes `newsroom` to a `using (...) { }` block that disposes it before `after` is read.

3. **The Flow and suspend job-handle rows move from Inferred to Verified.** `nuget_job_dispose` and
   `nuget_scope_dispose` both route through `NugetHandles.release`, confirmed by the harness's
   `Flow_EnumeratedToCompletion_ReturnsToBaseline`, `Flow_AbandonedViaDisposeAsync_ReturnsToBaseline`,
   and `Suspend_Completes_ReturnsToBaseline` tests, all of which pass **strict** (no eventual/polled
   assertion was needed, unlike the ADR's "Which families are strict and which are eventual" table
   assumed for the abandon and cancel rows; updated in place above).

4. **`Tier1CinteropStub.kt` needed a new stub.** Tier 1 compiles `CNameExports.kt` for the JVM against
   hand-written `kotlinx.cinterop` stubs. Since the counter is unconditionally emitted (amendment 1),
   every Tier 1 module now references `kotlin.concurrent.AtomicLong`, so `Tier1CinteropStub.kt` gained
   a stub for it. Not anticipated by this ADR, which named no Tier 1 impact.

5. **The shipped settle/assert logic replaced the ADR's 5-round sketch.** `LiveHandleTests.Settle()`
   loops until `NugetMarshal.LiveHandles` is stable for 6 consecutive rounds (cap 60), not a fixed 5
   rounds. A one-time class-level drain (`static LiveHandleTests()`) settles to 20 stable rounds
   (cap 120) before the first measurement, to absorb handle backlog owed by earlier test classes that
   would otherwise be misread as this class's own leak. `AssertNoLeak`/`AssertNoLeakAsync` re-measure
   up to 3 attempts when the delta is negative (a release owed by earlier work landing inside the
   window), and fail immediately, with no tolerance band, on any positive delta. Measured: about 11s
   for `LiveHandleTests` in isolation, 12-15s added to the full suite.

6. **The gate decisions the ADR left open are confirmed as shipped.** Forward-only (Alternative 5,
   reverse-side mints, deferred, tracked in ROADMAP.md); `internal` visibility, matching
   `NugetBridgeState.ReleasedCount`; the counter, the red test, and the outer-loop fix shipped as
   their own commit each, in that order (`d4031d1`, `c5e0710`, `e926bc7`), so the red run is on
   record in git history rather than only in this ADR.

7. **The harness's first Windows CI run already found a real leak, in a neighbouring feature.** A
   +1-over-50-crossings flake on `LiveHandleTests.SetParameter_ReturnsToBaseline` traced to the
   ADR-019 suspend route's job-handle disposal race, not to the setter test itself; closed the same
   day by [ADR-019](019-suspend-function-mapping.md)'s 2026-09-09 amendment. No-tolerance-band
   assertions (amendment 2) are what turned a one-in-a-thousand leak into a build failure instead of
   a silently passing green run.
