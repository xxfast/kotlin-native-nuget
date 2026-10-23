# A `suspend` member of an `object` or companion binds as a static `Task<T>` method

- ROADMAP: line 73 as of 2026-10-22: "A `suspend` member of an `object` or companion has no route, generic or not, named `SKIPPED_UNSUPPORTED_COMBINATION`; found by ADR-214"
- Researched: 2026-10-22, about 12 minutes of a 35 minute budget shared with C2 and C3. Read-only, no Gradle run, no spike build.
- Restatement: forward (Kotlin declares, C# consumes). `object Clock { suspend fun tick(): Int }`, `class Den { companion object { suspend fun open(): Den } }` and their generic forms (`suspend fun <T> echo(value: T): T`) bind as `public static Task<T> TickAsync(..., CancellationToken cancellationToken = default)` on the object's `public static class Clock` and on the companion's owning class `Den`.
- Verdict: fix. Reserved ADR-229. Recommend an **amendment to ADR-019** rather than a new ADR (see Recommendation): the mechanism is the top-level suspend route verbatim with a different receiver expression; there is no genuine alternative with different tradeoffs. If the main thread folds in the sibling owners below (extension, enum member, value-class member), that widening is a design choice (receiver as a parameter) and earns ADR-229.

## Findings

All paths under `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/` unless stated. Line numbers at the checkout `kn-p4f-c` on 2026-10-22.

1. **Skip site, object.** `forward/ForwardCallablePlanner.kt:2472-2509` (`objectEntries`): every member goes through `staticEntry`, which returns `Skipped(SUSPEND)` for a suspend member (`:2667-2668`), and `objectEntries` maps it through `namedSuspend()` (`:2508`), which rewrites it to `UNROUTED_POSITION` with `detail = SUSPEND, structural = true` (`:2604-2612`). That structural skip is what `forward/ForwardDiagnostic.kt:624` renders as `SKIPPED_UNSUPPORTED_COMBINATION`. The comment at `:2505-2507` gives the why: "the suspend route walks classes, never an object". **Verified by reading.**
2. **Skip site, companion.** `forward/ForwardCallablePlanner.kt:2614-2649` (`companionEntries`), same `staticEntry` + `namedSuspend()` (`:2648`); comment `:2643-2647`. **Verified by reading.**
3. **No emitter reads an object or companion.** `NugetProcessor.kt:3777-3803`: the suspend exports are emitted for `suspendFunctions` (top-level) through `addSuspendFunctionExports` and for `classes` through `addSuspendClassMethodExports` (receiver `handle.asStableRef<Owner>()`), plus sealed bases and arms (`:3808-3860`). No loop over objects or companions. **Verified by reading.**
4. **The top-level route is already static and owns its own scope.** `exports/SuspendFunctionExports.kt:61-147` (`addSuspendFunctionExports`) emits `export_<cname>_async(params..., callback, userData): COpaquePointer` and `buildSuspendFunctionBody` (`:258-285`) launches `launchForCSharp(CoroutineScope(Dispatchers.Default), callback, userData, ::nugetMappedType) { ... }`: an ad-hoc root scope per call, no C# scope handle. The comment at `:267-270` says why: "the top-level route has no C#-owned scope to launch on". **Verified by reading.** This is the answer to "what scope does a static suspend use": the same ad-hoc root scope, per call.
5. **Cancellation on the static route is per call, through the job.** `launchForCSharp` returns `NugetHandles.retain(job)` (`nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetLaunch.kt:35-57`), launched with `CoroutineStart.ATOMIC`, and invokes the completion callback on all three arms (success, `CancellationException`, other `Throwable`). The C# side renders static members (`isStatic`) with no `_handle` check, no `GC.KeepAlive(this)` and no `GetOrCreateScope()` (`cir/CirConcurrencyRenderer.kt:328`, `:370`, `:527-544`). **Verified by reading.** So a static owner needs no new scope mechanism at all: an `object` is a process-lifetime singleton and a companion is too; neither is `IAsyncDisposable` in C# (`cir/CirObjectRenderer.kt:5` renders `public static class`), so there is nothing that could cancel an owner scope anyway.
6. **The C# half for top-level is `translateSuspendFunction`** (`cir/CirFunctionTranslator.kt:31-...`), producing a static `CirMember`. It reads `symbols.topLevel(func)` for the stem and `callableCatalog.overloadSuffix(func)`. **Verified by reading.**
7. **The generic form needs nothing new.** ADR-214 put a function's own `T` on the suspend route for both top-level and class owners (`Boxed` shape, `legacyTypeArguments()` at `exports/SuspendFunctionExports.kt:358-361`, `forwardMemberGenericRefusal()` gate at `:84`). `staticEntry` already routes an object's / companion's own `T` on the plan for sync members (`forward/ForwardCallablePlanner.kt:2663-2672`). **Verified by reading.**
8. **Export naming.** `staticEntry` callers mint `"${prefix}_${toCName(name)}$suffix"` for an object (`:2497`) and `"${prefix}_companion_${toCName(name)}$suffix"` for a companion (`:2634`). The suspend export appends `_async`, so the symbols are `<objprefix>_tick_async` and `<clsprefix>_companion_open_async`, disjoint from every existing symbol (a class member is `<clsprefix>_<name>_async`, the companion infix keeps them apart). **Verified by reading** the naming; collision-freedom with ADR-163 qualification is **inferred**.
9. **Sibling gaps of the same shape, found while reading (rule 1: not deferrable).** `namedSuspend()` is also applied to an enum body's members (`:2569-2577`), a value class's members (`:1476-1478`), an interface member when the interface is unreachable (`:1543`), and a suspend extension is named directly (`:2810-2827`, comment "no route emits a suspend EXTENSION at all"). None of these is a ROADMAP line. All four are the same "static suspend with a receiver that crosses as a parameter" problem. **Verified by reading** that they are named drops; that they bind under the design below is **inferred**.
10. **C# name collisions between a companion and a member.** `cir/CirTranslator.kt:887-916` renders member and companion functions into one class and has a collision rule for the sync plan. A companion `suspend fun open()` and a member `suspend fun open()` would both be `OpenAsync` on `Den`, one static and one instance: CS0111 if the parameter lists match. Whether the existing rule (`cir/CirMemberNameCollisions.kt`) sees async members is **inferred, not verified**.

## Recommendation

Generalize the top-level suspend route over a **static receiver expression**, on both halves.

- Kotlin half: `addSuspendFunctionExports` takes the receiver spelling and the export stem instead of deriving both from a top-level `func`. Object: call `com.pkg.Clock.tick(...)`, stem `<objprefix>_tick`. Companion: call `com.pkg.Den.open(...)` (the companion is reached through its owner, the plan route's spelling), stem `<clsprefix>_companion_open`. Body unchanged: `launchForCSharp(CoroutineScope(Dispatchers.Default), ...)`. The `addAcquiredFlowCollectExport`, `addFlowKeyedSharedFlowExports` and `addAwaitedStateFlowCollectionExports` tails come with it, so a static `suspend fun watch(): Flow<Int>` / `StateFlow<Int>` binds too.
- C# half: `translateSuspendFunction` takes the same owner and lands the `CirMember` on the object's static class or the companion's owner class as a static method. `renderAsyncMethod`'s `isStatic` branch (`cir/CirConcurrencyRenderer.kt`) already renders everything a static member needs.
- Planner: `objectEntries` and `companionEntries` stop mapping a SUSPEND skip through `namedSuspend()`: the skip keeps `ForwardPlanSkipReason.SUSPEND` (`droppedFromCSharp = false`), as on a class, and `warnRefusedLegacyRouteMembers` names a refused shape with the route's own reason. A new selector `forwardStaticSuspendRouteMethods(owner)` (beside `forwardSuspendRouteMethods`, `forward/ForwardScopeOwnership.kt:57-85`) is read by the emitter loop, the C# translator and the diagnostic walk, so the three cannot disagree (the ADR-159 lesson).
- Diagnostics: ADR-214's `GENERIC` sentence and the ROADMAP-line parenthetical "(An object's suspend members still have no route at all...)" in ADR-214 come out; `supported-features.md:96` drops "Not on an `object` or companion".

Sibling owners (finding 9), same mechanism, the receiver crossing as a leading parameter of the `_async` export:

- suspend extension `suspend fun Cat.purr(): Int`: receiver handle slot, Kotlin `handle.asStableRef<Cat>().get().purr()`, C# `public static Task<int> PurrAsync(this Cat cat, CancellationToken ct = default)` in the receiver's extension class, ad-hoc scope.
- enum member: ordinal slot, `Mood.entries[ordinal].x()`, C# extension in `MoodExtensions` (ADR-006 amendment's rendering).
- value-class member: the value class's wire slot, rebuilt as the sync route does, C# instance method on the record struct.

These change no shipped symbol. If the main thread takes them into this item, write ADR-229 (receiver as a parameter on the static suspend route is then a real decision: an extension could also have been rendered as an instance-scope member, rejected because it has no owner scope to drain). Otherwise they need their own item, which rule 1 forbids leaving as a ROADMAP line; escalate.

Alternatives rejected:

- A process-wide static `CoroutineScope` owned by the generated library: nothing could ever cancel it (no `DisposeAsync` on a static class), so it buys nothing over per-call root scopes and adds one global.
- Route through the class suspend route with a synthetic `StableRef` of the object instance: mints and leaks a handle per object, and gives C# an instance method on a static class (CS0708).

## Files an implementation touches

- `exports/SuspendFunctionExports.kt` (static receiver parameter on `addSuspendFunctionExports`)
- `cir/CirFunctionTranslator.kt` (`translateSuspendFunction` over a static owner)
- `cir/CirClassTranslator.kt` (companion members join the owner class), `cir/CirTranslator.kt` and `cir/CirObjectRenderer.kt` (object static class gains async members), `cir/CirMemberNameCollisions.kt` (static vs instance `XAsync`)
- `forward/ForwardCallablePlanner.kt` (`objectEntries`, `companionEntries`: drop `namedSuspend()`), `forward/ForwardScopeOwnership.kt` (new selector), `NugetProcessor.kt` (emitter loop at `:3777`, `warnRefusedLegacyRouteMembers`, the `needsCoroutineImports` gate)
- `ForwardAbiContract.kt` / `ForwardAbiLegacyRoutes.kt` (the new `_async` symbols in the scrape)
- `forward/ForwardDiagnostic.kt` (GENERIC sentence text)
- Fixture: `test-library/src/nativeMain/kotlin/.../test/` a new `clock/Clock.kt` (`object Clock` and a class with a companion)
- Tests: `IntegrationTests/StaticSuspendTests.cs`, `LeakTests/LiveHandleTests.cs`, `LeakTests/CollectabilityTests.cs`, Tier 1 cells in `nuget-processor/src/test` (named-skip cell flips to bound)
- Docs: `docs/topics/supported-features.md:96`, `docs/topics/coroutines-and-flow.md`, `docs/topics/objects-and-companions.md`, ADR-019 amendment, ADR-214 text

## Sample test

Fixture:

```kotlin
object Clock {
  private var ticks = 0
  suspend fun tick(): Int { delay(1); return ++ticks }
  suspend fun <T> echo(value: T): T { delay(1); return value }
  suspend fun stall(): Int { awaitCancellation() }
  suspend fun fail(): Int { delay(1); throw IllegalStateException("clock stopped") }
}

class Den(val name: String) {
  companion object {
    suspend fun open(name: String): Den { delay(1); return Den(name) }
  }
}
```

xunit (`IntegrationTests/StaticSuspendTests.cs`):

```csharp
[Fact]
public async Task Clock_TickAsync_CompletesOnTheStaticClass()
{
    int first = await Clock.TickAsync();
    Assert.Equal(first + 1, await Clock.TickAsync());
}

[Fact]
public async Task Clock_EchoAsync_RoundTripsTypeParameter()
{
    Assert.Equal("Mylo", await Clock.EchoAsync("Mylo"));
    using var oreo = new Tabby("Oreo", 3);
    using Tabby same = await Clock.EchoAsync(oreo);
    Assert.Equal("Oreo", same.Name);
}

[Fact]
public async Task Clock_StallAsync_CancelsThroughTheToken()
{
    using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
    await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Clock.StallAsync(cts.Token));
}

[Fact]
public async Task Clock_FailAsync_FaultsWithTheKotlinException()
{
    var ex = await Assert.ThrowsAsync<KotlinException>(() => Clock.FailAsync());
    Assert.Contains("clock stopped", ex.Message);
}

[Fact]
public async Task Den_OpenAsync_IsAStaticOnTheOwnerClass()
{
    using Den den = await Den.OpenAsync("attic");
    Assert.Equal("attic", den.Name);
}
```

(The exception type name follows the existing suspend fault rows; check `IntegrationTests/SuspendTests.cs` for the exact type before writing.)

LeakTests (`LiveHandleTests.cs`, an `// ADR-229` banner at the end of the class):

- `StaticSuspend_ObjectAndCompanion_ReturnsToBaseline`: 200 iterations of `TickAsync`, `EchoAsync("x")`, `EchoAsync(tabby)` (dispose the result), `Den.OpenAsync` (dispose).
- `StaticSuspend_Fault_ReturnsToBaseline`: 200 x `FailAsync` caught.
- `StaticSuspend_Cancel_ReturnsToBaseline`: 200 x `StallAsync` with a pre-canceled token and 50 with a 1 ms timeout, each awaited to completion before the count.
- `StaticSuspend_TightLoop_CompletionBeforeReturn_ReturnsToBaseline`: 1000 x a non-delaying `suspend fun now(): Int = 1` member (add to `Clock`), so `launchForCSharp`'s callback often runs before the P/Invoke returns; asserts the job handle and completion `GCHandle` are both freed (`NugetJobCell`'s existing race handling, ADR-019).

Tier 1: the existing named-skip cell for an object's suspend member flips to a bound cell asserting `export_<objprefix>_tick_async` and `public static Task<int> TickAsync(`.

## Deferred scope

None. The sibling owners in finding 9 are designed above; whether they ride this item or their own is the open question below, not a deferral.

## Open what-questions

1. Do the sibling suspend owners (extension, enum member, value-class member, unreachable interface member) ride ADR-229? Recommendation: yes, one ADR, because the planner change (`namedSuspend()` removal) touches all five sites at once and the mechanism is the same. If no, the main thread escalates to the user (rule 1).
2. Companion vs member `XAsync` collision on one C# class: rename the static one, or name-skip it? Recommendation: reuse whatever `CirMemberNameCollisions` does for sync companion/member clashes today; verify that it sees async members (finding 10, inferred).
3. Companion call spelling `Den.open()` vs `Den.Companion.open()`: identical for a default-named companion; a named companion (`companion object Factory`) must use the owner-qualified form. Recommendation: owner-qualified, as the plan route spells it (inferred, check `ForwardKotlinPlanEmitter` COMPANION arm).

### Verified vs inferred

- Verified by reading: findings 1 to 7, 9 (the skip sites), 8 (naming).
- Inferred: finding 8's collision-freedom, finding 10, the sibling owners binding with no further blocker, the `KotlinException` type name in the sample.
