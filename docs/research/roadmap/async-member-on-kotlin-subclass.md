# An async member on a class with a Kotlin superclass generates non-compiling C# (scope ownership across an inheritance chain)

- ROADMAP: line text as of 2026-09-21 (Phase 6, `ROADMAP.md:96`): "VERIFIED by execution: a `suspend fun` or `Flow`-returning member on a class with a Kotlin superclass generates C# that does not compile, since the scope-owner emission only fires on a base-less class." Folded in, same path: `ROADMAP.md:97` ("`CirClass.hasSuspendMethods` (`CirClassTranslator.kt:900`) scans `cls.getAllFunctions().any { SUSPEND }` with no declared-only filter and no ADR-114 refusal check") and `ROADMAP.md:98` ("Async members ... bypass the ADR-034 signature-collision guard entirely"). Related, assessed: `ROADMAP.md:49` (Phase 4, the ADR-040 interface backing wrapper never derives `hasSuspendMethods`).
- Researched: 2026-09-21, two passes. Pass 1: about 15 of 20 minutes, source reading only. Pass 2 (same day, about 10 of 25 minutes, in a dedicated worktree at `651cb7d0`): every load-bearing inferred claim was spiked, see "Spikes run (2026-09-21)". Claims below are **verified by reading**, **verified by spike** (output quoted), or **inferred**. The spikes compile the real processor's `Interop.cs` in a scratch classlib that mirrors the ADR-138 gate (`NugetCompileInteropTask.kt:87-99`: net8.0, LangVersion 12, Nullable, TreatWarningsAsErrors, no implicit usings); they did not go through `scripts/verify.sh` and nothing was run against a linked Kotlin/Native library.
- Restatement: forward (Kotlin declares, C# consumes). A C# consumer of `class NapLounge : Lounge() { suspend fun rest(): String }` gets `public class NapLounge : Lounge, IAsyncDisposable` with a working `RestAsync()`, `Dispose()` and `await using`, wherever in the inheritance chain the first async member appears; a class whose only async member was refused gets no `IAsyncDisposable`; two async overloads that render one C# signature fail the round with `ERROR_CSHARP_SIGNATURE_COLLISION` instead of CS0111 in the generated file.
- Verdict: fix. A new ADR is needed (owner/inheritor scope model plus the abstract `DisposeAsync` rule; it spans ADR-021, ADR-101 and ADR-118 and has real alternatives). Not drafted: this run was restricted to the memo, and ten parallel agents would race for the next number (last on disk is ADR-157), so the main thread assigns it.

## Findings

### F1. Where the base-less condition lives

- `CirClassRenderer.kt:157-163`, **verified by reading**: the base list branches only on `cls.superClass != null`. With a superclass it renders `: Base, IFoo...` and never consults `cls.hasSuspendMethods`; `IAsyncDisposable` is only added in the base-less arm (`:160-161`).
- `CirClassRenderer.kt:183-194`, **verified by reading**: `_handle`, `renderScopeHandleField()` (`:185`) and `renderGetOrCreateScope()` (`:190-193`) all sit inside `if (cls.superClass == null)`.
- `CirClassRenderer.kt:269-275`, **verified by reading**: `renderDispose` is called with `hasSuspendMethods = cls.hasSuspendMethods` regardless of the superclass, so a derived class with the flag set renders the scope-aware `Dispose()` (`:704-711`) and `DisposeAsync()`/`DrainAndDisposeAsync` (`:714-758`) against a `_scopeHandle` nobody declared. That is the CS0103 family the backlog file records (`docs/backlog/async-member-on-kotlin-subclass-generates-non-compiling.md`), now **verified by spike** (shape a): three distinct sites for a one-suspend-member subclass, `error CS0103: The name 'GetOrCreateScope' does not exist in the current context` once per async body, and `error CS0103: The name '_scopeHandle' does not exist in the current context` once in `Dispose()` and once in `DisposeAsync()`. The backlog's count of four came from a fixture with one more async body; the count scales with members, the two `_scopeHandle` sites are fixed.
- `CirClassRenderer.kt:662`, `:667`, **verified by reading**: the field is `internal IntPtr _scopeHandle;` but `GetOrCreateScope()` is `private`. Async and flow bodies call it unqualified (`CirConcurrencyRenderer.kt:138-139`, `:153`; `CirFlowRenderer.kt:411`; `CirClassTranslator.kt:1269`, `:1304`, `:1307`, `:1514`, `:1516`). A derived class can therefore see the base's field but not the base's scope factory.
- `CirClassRenderer.kt:694-713`, **verified by reading**: a derived `Dispose()` is spelled `override` and **replaces** the base body; it never calls `base.Dispose()`. Whatever scope cleanup the base's `Dispose` does is not run for a derived instance unless the derived body repeats it. **Verified by spike** (shapes b and flowb): no `base.Dispose` string appears anywhere in the generated file; shape b's derived body repeats the `_scopeHandle` cancel/dispose block, shape flowb's derived body is the bare three lines (`Interlocked.Exchange(ref _handle...)`, the zero check, `Native_Dispose(handle)`).
- `CirClassRenderer.kt:363`, `:385`, **verified by reading**: `renderConstructorMember`/`renderConstructor` accept `hasSuspendMethods` and never read it. Dead parameter, one-line cleanup on the same path.

### F2. Hierarchy shapes, all spiked 2026-09-21

Every predicted error below was reproduced; the quoted text is Roslyn's, from the scratch gate compile of the real processor output.

- Shape (a), derived declares async, base has none: the ROADMAP item. **Verified by spike**: CS0103 x3 (see F1), `public class NapSpot : Sunspot` with no `IAsyncDisposable` and no field.
- Shape (b), base declares async, derived declares none: `CirClassTranslator.kt:1133` scans `cls.getAllFunctions()`, which includes inherited members, so the derived class's flag is true. It renders a scope-aware `override Dispose()` (compiles, `_scopeHandle` is internal and same-assembly) plus a second `public ValueTask DisposeAsync()` with no modifier. **Verified by spike**: `error CS0108: 'TimedFeeder.DisposeAsync()' hides inherited member 'Feeder.DisposeAsync()'. Use the new keyword if hiding was intended.` It is the only error in the file. Both compile gates set `TreatWarningsAsErrors` (`GeneratedBindingsCheck/GeneratedBindingsCheck.csproj:14`, `nuget-plugin/.../NugetCompileInteropTask.kt:92`, **verified by reading**). Note the accident: the raw scan is the only thing that makes the derived `Dispose()` scope-aware today.
- Shape (c), base and derived both declare async. **Verified by spike**: (b)'s CS0108 plus `error CS0122: 'Feeder.GetOrCreateScope()' is inaccessible due to its protection level` at the derived async body.
- Abstract owner, base-less: `renderDispose` `:696-697` emits only `public abstract void Dispose();`, and the `DisposeAsync` block is inside the `else`, while `:161` still puts `IAsyncDisposable` on the base list. **Verified by spike**, with and without a concrete subclass: `error CS0535: 'Walker' does not implement interface member 'IAsyncDisposable.DisposeAsync()'`. The abstract class does render `_scopeHandle`, `private GetOrCreateScope()` and a working `WalkAsync`; only `DisposeAsync` is missing. The concrete `DogWalker : Walker` renders its own plain `public ValueTask DisposeAsync()` (no CS0108 there only because the abstract base has none to hide) and the scope-aware `override Dispose()`. Spike-only fact: the Kotlin half already exports `walker_dispose` for the abstract class; only the C# import is absent (`CirNativeImports.kt:246`). No `test-library` abstract class declares a suspend or Flow member, **verified by reading**.
- `override suspend fun` on a derived class over a base `open suspend fun`. **Verified by spike**: `error CS0108: 'TimedFeeder.FillAsync(CancellationToken)' hides inherited member 'Feeder.FillAsync(CancellationToken)'`, plus (c)'s CS0122 and CS0108. Both Kotlin exports exist (`feeder_fill_async`, `timedfeeder_fill_async`), and the base export's body is `handle.asStableRef<spike.ovr.Feeder>().get()` then `obj.fill()`, an ordinary virtual call, which is what recommendation 5 relies on (**verified by spike** for the generated text; the dispatch itself is Kotlin semantics, not run).
- Open sealed arm as the base (`sealed class Roost { open class Perch : Roost() { suspend fun settle() } }`, `class HighPerch : Roost.Perch(9) { suspend fun sway() }`). **Verified by spike**: `error CS0122: 'Roost.Perch.GetOrCreateScope()' is inaccessible due to its protection level` and CS0108 on `HighPerch.DisposeAsync()`. So the arm owner is shape (c) exactly, and the ancestor walk must understand arm owners, as F5 predicted. The Kotlin half also emits the stray `highperch_settle_async`.
- Flow twin of shape (b) (base has a `Flow`-returning method, derived has no async member). **Verified by spike, and this one was not predicted**: it COMPILES, and it is wrong. `flowMethods` is already projected-only, so the derived flag is false and `TimedFeeder : Feeder` renders the bare `override Dispose()` that replaces the base's scope-aware body. `new TimedFeeder()`, collect `Visitors()`, then sync `Dispose()` never cancels or disposes the base's `_scopeHandle`. `DisposeAsync()` is inherited non-virtual from `Feeder` and does drain it, so only the sync path leaks. This is F3's trap, already shipping on the Flow route. Text-verified only; the runtime leak was not measured (needs a linked library and a `LeakTests` row).
- ADR-101 unexported base with a suspend member (`open class OutsideBase { suspend fun nap() }` in a dependency jar, `class Api : OutsideBase()`). **Verified by spike**: compiles clean; `Api : IDisposable, IAsyncDisposable, INugetHandle` owns the field, `NapAsync` and `api_nap_async`, with the expected `SKIPPED_UNEXPORTED_SUPERTYPE` warning. No special case needed, as F5 said.

### F3. `hasSuspendMethods` derivation (ROADMAP:97)

- `CirClassTranslator.kt:1133-1139`, **verified by reading** (the ROADMAP's `:900` has drifted): `cls.getAllFunctions().any { SUSPEND } || flowMethods.isNotEmpty() || cls.getAllProperties().any { Flow or StateFlow typed }`. The first and third terms are raw scans: no visibility filter, no `isForwardMemberOf`, no ADR-114/119/123/147 refusal.
- The projected lists already exist right above it: `filteredMethods` (`:862-882`) applies `isCompilerOwnedMember`, `legacyRefusedParameter`, `legacyRefusedReturn`, the ADR-147 generic-owner refusal and `isForwardMemberOf(cls, superClassDeclaration)`; `asyncMembers` (`:1031-1040`) and `flowRouteMembers` (`:1044-1052`) are built from it. **Verified by reading**.
- The sealed arm is the model: `CirClassTranslator.kt:2187-2188` is `asyncMembers.isNotEmpty() || flowMembers.isNotEmpty() || properties.any { it.isFlow }`, with the comment at `:2183-2186` naming exactly this bug. **Verified by reading**.
- Trap, **verified by spike** (mechanism; the runtime leak itself was not measured): fixing `:1133` to the arm's spelling *alone* makes shape (b) worse. The derived class would render a plain `override Dispose()` that replaces the base's scope-aware one (F1), so the base's scope is never cancelled or disposed for a derived instance: a silent leak instead of a compile error. The flag has to split into "owns" and "has" (see Recommendation). Spike: with `:1133` temporarily changed to `hasSuspendMethods = asyncMembers.isNotEmpty() ||` in the scratch worktree, shape (b) reports `Build succeeded` and `TimedFeeder.Dispose()` is the bare `Interlocked.Exchange(ref _handle...)` / `Native_Dispose(handle)` body with no `_scopeHandle` block and no `base.Dispose()`. The Flow twin (F2, flowb) proves the same output ships today without any edit.
- Kotlin half disagrees with the C# half: `SuspendFunctionExports.kt:98-112` selects from `getAllFunctions()` with no `isForwardMemberOf` filter (only `declaredOnly`, which ordinary classes pass as false), while C# `:881` filters. In shape (b) Kotlin exports the base's suspend member again under the derived prefix; `ForwardAbiContract` filters Kotlin exports to the C# import set so it is never flagged (the comment at `:102-105` says so). `NugetProcessor.kt:2085-2088` gates that call on the same raw scan. **Verified by reading**; the stray export is **verified by spike**: shape (b) emits `@CName("timedfeeder_fill_async")` beside `@CName("feeder_fill_async")` with no C# import for it, likewise `dogwalker_walk_async` (abstract shape) and `highperch_settle_async` (arm shape).
- Refused-only class, **verified by spike, and worse than pass 1 said** (dated note 2026-09-21: pass 1 described this as a spurious `IAsyncDisposable`; it is a build break). `class Board { suspend fun paired(entry: Pair<String, Int>): Int; fun plain(): Int }` in an otherwise coroutine-free module: the member is refused with one `SKIPPED_UNSUPPORTED_INPUT` and no `board_paired_async` export, yet the raw scan still renders `public class Board : IDisposable, IAsyncDisposable, INugetHandle` with the field and `DisposeAsync`. `tracker.needsAsync` (`CirTranslator.kt:812-816`) is only set by projected async members, so the file has no `using System.Threading.Tasks`: `error CS0246: The type or namespace name 'ValueTask' could not be found`, `error CS0246: ... 'Task' could not be found`, `error CS0738: 'Board' does not implement interface member 'IAsyncDisposable.DisposeAsync()'`. In a module that has another projected async member the usings are present and the symptom degrades to the spurious interface pass 1 described.
- Split-out bug found by the same spike, NOT this item (**verified by spike**, gate-mirroring scratch compile only): a coroutine-free module with any ordinary class does not compile without implicit usings. `class Bowl { fun size(): Int = 1 }` renders `Interlocked.Exchange(ref _handle, IntPtr.Zero)` in `Dispose()` (`CirClassRenderer.kt:702`) under `using System; using System.Runtime.InteropServices;` only: `error CS0103: The name 'Interlocked' does not exist in the current context`. `System.Threading` is added only under `tracker.needsAsync` or `needsSubscription` (`CirTranslator.kt:812-819`). `test-library` never sees it because it always has async members, and a consumer with `ImplicitUsings` enabled never sees it; the ADR-138 gate csproj (`NugetCompileInteropTask.kt:87-99`) has no implicit usings, so **inferred**: `packNuget` fails for a coroutine-free author. Not found in ROADMAP or `docs/backlog/`. No repo path exercises it, which is why it has not surfaced: CI packs only `:test-library` (`.github/workflows/ci.yml:51`), `smoke-test` has a coroutine-free `class Greeting` but only runs the two `verify*ResolvesByCoordinate` tasks and never `packNuget`, and every C# consumer project in the repo sets `ImplicitUsings` (verified by grep 2026-09-21). Until it is reproduced through the real `nugetCompileInterop` task it stays a mirrored-gate finding. It matters here because fixing the refused-only class (flag false) lands exactly on this error, as the naive-fix spike showed (`Board : IDisposable, INugetHandle`, then CS0103 `Interlocked`). Either fix it in the same change (one line: make `System.Threading` unconditional) or the refused-only Tier 1 cell must sit in a module with another async member.

### F4. Collision guard (ROADMAP:98)

- `CirClassTranslator.kt:1096-1101`, **verified by reading**: `emitCsharpSignatureCollisions(plannedMethods + companionMembers.filterIsInstance<CirMethod>())` runs before `companionMembers + asyncMembers + flowRouteMembers` is assembled at `:1132`. Async and flow-route methods are never checked. The sealed route has the same gap: `:2063` checks `methods` only, `asyncMembers`/`flowMembers` are built after it (`:2084`, `:2098`).
- The collision is real, **verified by spike**: `suspend fun play(note: String)` plus `@JvmName("playNullable") suspend fun play(note: String?)` on one class gives KSP exit OK, zero diagnostics, and `error CS0111: Type 'Piano' already defines a member called 'PlayAsync' with the same parameter types` from the gate. That the guard will bite once it sees the async methods stays **inferred** from its key (unspiked, it needs the code move; the implementing run's collision cell proves it): the key is name plus parameter types with reference nullability stripped when `param.isReferenceType` (`:2262-2265`); `CirParameter.isReferenceType` defaults to `true` (`CirModel.kt:877`) and `legacyRouteParameters` (`CirCollectionParameters.kt:80-111`) never overrides it; its `Plain` arm spells the type from the declaration's simple name with no `?` at all (`:107-110`), so `play(String)`/`play(String?)` both key as `PlayAsync(string)`. The `CancellationToken` tail is added at render time to every async method, so it cannot separate two keys. Async names carry the `Async` suffix, so they are checked against each other and against any sync method literally named `fooAsync`, which is correct C#.
- ADR-118 numbering gives the two overloads distinct C symbols and extern stems (`suspendMembers` doc, `:1592-1597`), so the collision is purely the public C# signature. **Verified by reading**.

### F5. Facts the ownership model rests on

- `ClassExports.kt:98-104`, **verified by reading**: every class's `{prefix}_dispose` is `NugetHandles.release(handle)`, type-agnostic. A base's `DisposeAsync()` calling the base's `Native_Dispose` on a derived instance's handle is correct, so `DisposeAsync` does not need to be virtual for correctness.
- `CirNativeImports.kt:245-246`, **verified by reading**: an abstract class has no `Native_Dispose` import, so an abstract class cannot host the concrete `DisposeAsync` body today.
- `ForwardClassMembership.kt:262-265`, `:280-297`, `:317-323`, `:377-384`, **verified by reading**: `forwardSuperClass(exportedTypes)` walks past unexported bases (ADR-101) and `isFromDroppedBase` re-homes a dropped base's members, suspend ones included, onto the class. An unexported base therefore needs no special case: its async members project on the derived class, which makes the derived class the owner.
- Sealed arms (ADR-118/124), `CirSealedRenderer.kt:93`, `:111-114`, `:136-139`, `:248-270`, **verified by reading**: an arm with `hasSuspendMethods` renders `: Base, IAsyncDisposable`, its own field, its own `GetOrCreateScope()`, and `renderDispose(hasSuperClass = true, hasSuspendMethods = true)`. This is exactly shape (a)'s correct output, already shipping (`Job.Idle : Job, IAsyncDisposable`). An **open** arm (ADR-009 amendment, `:96-97`) can be the base of an ordinary class, so the ancestor walk has to understand arm owners too.
- Microsoft guidance (learn.microsoft.com, "Implement a DisposeAsync method"), **inferred, not fetched this run**: non-sealed classes implement `public async ValueTask DisposeAsync()` calling `protected virtual ValueTask DisposeAsyncCore()` so each level releases its own resources. Not needed here: there is one native handle and at most one scope per instance, owned at exactly one level, and no per-level resources. Owner-implements, derived-inherits is the degenerate case of that pattern.

### F6. Interface backing wrapper (ROADMAP:49)

- `docs/backlog/interface-backing-wrapper-never-sets-hassuspendmethods.md`, **verified by reading**: `translateInterfaceBackingClass` builds a `CirClass` with the flag left false; harmless while `ForwardCallablePlanner.interfaceEntries` skips suspend members with `ForwardPlanSkipReason.SUSPEND`. Whether a Flow-typed interface property is admitted was not established there and was not established here (time).
- It is a different question from this item (the wrapper is base-less by construction; its gap is "which members project", not "who owns the scope"). Once the flag is derived from projected members through one helper, the wrapper's fix is calling that helper: a one-liner. Fold in the derivation only; do not admit interface suspend members in this change.

## Recommendation

One scope per instance, owned by the first class in the kept chain that projects an async member; everything below reuses it.

1. Split the flag on `CirClass` (and mirror on `CirSealedSubclass` only if an open arm base needs it): `ownsScope` = this class projects at least one scope-using member (`asyncMembers.isNotEmpty() || flowRouteMembers.isNotEmpty() || properties.any { it.isFlow }`) and no kept ancestor has a scope; `hasScope` = `ownsScope` or a kept ancestor has one. Keep the name `hasSuspendMethods` for `hasScope` if churn matters; the new bit is `ownsScope`.
2. Ancestor answer at KS level, beside `forwardSuperClass` in `ForwardClassMembership.kt`: walk `declaredBaseChain()` restricted to `exportedTypes`, and ask each base the same "projects a scope-using member" question through one selector. That selector (`forwardClassSuspendMethods` / flow twin, modelled on `forwardArmFlowMethods`, `FlowExports.kt:84`) becomes the single source for `translateClass`'s `filteredMethods` suspend half, `addSuspendClassMethodExports`, and the `NugetProcessor.kt:2085` gate, which closes F3's Kotlin/C# disagreement. A sealed-arm ancestor answers through `declaresSuspendMember()` / `forwardArmFlowMethods`.
3. Renderer, `CirClassRenderer.kt`: owner emits `IAsyncDisposable` on its base list (both arms of `:157-163`), the field, `GetOrCreateScope()` and `DisposeAsync`, regardless of `superClass`. `GetOrCreateScope()` widens from `private` to `internal` (matches `_scopeHandle`; `private protected` is the tighter alternative, either compiles). Every `hasScope` class renders the scope-aware `Dispose()` override. A non-owner emits nothing else and inherits `DisposeAsync` (safe by F5).
4. Abstract rule: `DisposeAsync` follows `Dispose`'s spelling. An abstract owner renders `public abstract ValueTask DisposeAsync();`; each concrete class under an abstract owner renders `public override ValueTask DisposeAsync()` with the full body and its own `Native_Dispose`. A concrete owner renders today's plain `DisposeAsync`, inherited unchanged below. A concrete `open` owner needs no `virtual`.
5. `override suspend fun` over a base-projected member: do not re-project on the derived class. The base's export calls the member on `asStableRef<Base>().get()`, so Kotlin's dynamic dispatch already reaches the override. Filter it in the shared selector (`overridesBaseClassMember(superClass)`, `ForwardClassMembership.kt:451`) and keep it when the overridee is on a dropped base.
6. Collision guard: move the `emitCsharpSignatureCollisions` call at `:1096` below the async/flow projection and pass `plannedMethods + (companionMembers + asyncMembers + flowRouteMembers).filterIsInstance<CirMethod>()`; same one-line move at `:2063` for arms.
7. Interface wrapper: set its flag from the same projected-members helper (F6).
8. (Added 2026-09-21 after the spikes.) `hasScope` must count a kept ancestor's projected Flow methods and Flow/StateFlow properties, not only suspend members: the Flow twin of shape (b) compiles today and leaks the base scope on sync `Dispose()` (F2). It falls out of rule 1 as written, but it needs its own Tier 1 cell and is the shape the `LeakTests` row should drive, because it is the one hierarchy shape that reaches a consumer today.
9. (Added 2026-09-21.) The refused-only class needs `using System.Threading` to survive losing its flag (F3 split-out bug). Recommendation: make `System.Threading` unconditional at `CirTranslator.kt:808` in this change (one line, `Interlocked` is used by every class's `Dispose()`), and record the coroutine-free-module break as fixed by it. If the human prefers to split it, the refused-only cell has to carry a second, routed suspend member.

Dated note 2026-09-21: no spike contradicted the recommendation's shape. The owner/inheritor split, the abstract rule, skip-the-override and the guard move all stand. Three things changed: rules 8 and 9 are new, the refused-only symptom is a build break rather than a spurious interface, and open question 1's alternative got cheaper (the Kotlin `walker_dispose` export already exists for an abstract class, so hosting the body on the abstract owner is a C# import, not an ABI change).

Price: about 8 processor files (`CirTranslator.kt` added for rule 9), 1 fixture file, 1 xunit file, 3 to 4 Tier 1 files, 1 leak row, 1 ADR, docs. Medium; one worktree, no plugin change.

Alternatives rejected:
- Patch only shape (a) (let the `superClass != null` arm emit the scope when the base has none): leaves (b), (c), abstract owner and ROADMAP:97 open, and ROADMAP:97's later fix would then introduce the F3 leak.
- Always put the scope on the root of the chain: hands `IAsyncDisposable` and a scope to bases with no async member, the exact thing ROADMAP:97 removes and ADR-118 rejected for the sealed base.
- A scope per level: two `_scopeHandle` fields hide each other (CS0108), double drain, no benefit.
- Full `DisposeAsyncCore` pattern: solves per-level resources this design does not have; adds a protected virtual to every open class's public surface.
- `base.Dispose()` chaining instead of repeating the scope block: changes the shipped `Interlocked.Exchange(ref _handle)` body (the base would see a zero handle and return early), larger blast radius for the same result.

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirModel.kt` (`CirClass.hasSuspendMethods :170`, new `ownsScope`; doc at `:336-353`)
- `.../cir/CirClassTranslator.kt` (`:860-891` selector, `:1096-1101` guard move, `:1133-1139` derivation, `:2063` arm guard, `translateInterfaceBackingClass` flag)
- `.../cir/CirClassRenderer.kt` (`:157-163`, `:183-194`, `:269-275`, `:363/:385` dead parameter (anchors re-checked 2026-09-21; the second `renderDispose` call site at `:345-346` passes the same flag and follows the same rule), `:667` visibility, `:682-760` abstract/override `DisposeAsync`)
- `.../cir/CirTranslator.kt` (`:808-816`, `System.Threading` unconditional, rule 9). No Tier 1 cell pins the coroutine-free `using` header: `Tier1CoroutineFreeModuleTest`, `Tier1StructuralInteropCsTest` and `Tier1CoreHelpersAlwaysEmittedTest` contain no `System.Threading` or `using System` assertion (verified by grep 2026-09-21), so the change is safe for existing cells
- `.../cir/CirNativeImports.kt` (`:245` unchanged if the abstract owner stays declaration-only; confirm)
- `.../cir/CirSealedRenderer.kt` (only if an open arm owner must widen `GetOrCreateScope()`, which comes free from the shared `renderGetOrCreateScope`)
- `.../forward/ForwardClassMembership.kt` (ancestor-has-scope predicate, shared selector)
- `.../exports/SuspendFunctionExports.kt` (`:98-112` read the shared selector), `.../exports/FlowExports.kt` (flow twin, confirm the same asymmetry), `.../NugetProcessor.kt` (`:2085-2088` gate)
- Fixture: new `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/lounge/NapLounge.kt` (or a new package; `lounge/Lounger.kt` already exists)
- Tests: `IntegrationTests/SubclassAsyncTests.cs`; Tier 1 `Tier1SubclassScopeOwnerTest.kt` (shapes b, c, abstract owner, override suspend, stray Kotlin export), `Tier1RefusedSuspendOrdinaryClassTest.kt` (mirror of `Tier1SealedArmRefusedSuspendTest.kt`), a collision cell in or beside `Tier1SuspendMethodOverloadTest.kt`
- `LeakTests`: one row on the Flow twin of shape (b): construct the derived class, collect the base's Flow, sync `Dispose()` through the derived type, handle count back to baseline. This is the row that is red today (F2, text-verified); a second row on shape (c) after an async call
- Docs: new ADR, cross-reference notes in ADR-101/118/021, `FEATURES.md`, delete the backlog file and ROADMAP lines 96-98 (and 49 if folded)

## Sample test

Fixture Kotlin:

```kotlin
package io.github.xxfast.kotlin.native.nuget.test.lounge

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.milliseconds

// Shape (a): the base has no async member, the subclass owns the scope.
open class Sunspot(val name: String) {
  fun describe(): String = "$name is warm"
}

class NapSpot(name: String) : Sunspot(name) {
  suspend fun rest(cat: String): String {
    delay(50.milliseconds)
    return "$cat napped on $name"
  }

  fun visitors(): Flow<String> = flowOf("Oreo", "Mylo")
}

// Shape (c): the base owns the scope, the subclass reuses it.
open class Feeder {
  open suspend fun fill(): String = "bowl filled"
}

class TimedFeeder : Feeder() {
  suspend fun schedule(hour: Int): String = "feeding at $hour"
}
```

xunit:

```csharp
using TestLibrary.Lounge;

namespace IntegrationTests;

public class SubclassAsyncTests
{
    [Fact]
    public async Task Subclass_OwnsScope_WhenBaseHasNoAsyncMember()
    {
        // Oreo naps on the windowsill; the subclass is the scope owner
        await using var spot = new NapSpot("windowsill");
        Assert.IsAssignableFrom<IAsyncDisposable>(spot);
        Assert.False(typeof(IAsyncDisposable).IsAssignableFrom(typeof(Sunspot)));
        Assert.Equal("Oreo napped on windowsill", await spot.RestAsync("Oreo"));
        Assert.Equal("windowsill is warm", spot.Describe());
    }

    [Fact]
    public async Task Subclass_ReusesBaseScope_AndDrainsThroughBaseReference()
    {
        // Mylo's feeder: base and subclass both suspend, one scope, drained via the base type
        Feeder feeder = new TimedFeeder();
        Task<string> fill = feeder.FillAsync();
        Task<string> plan = ((TimedFeeder)feeder).ScheduleAsync(7);
        await feeder.DisposeAsync();
        Assert.Equal("bowl filled", await fill);
        Assert.Equal("feeding at 7", await plan);
    }
}
```

Added 2026-09-21: the fixture also needs the Flow twin, `open class Porch { fun visitors(): Flow<String> = flowOf("Oreo", "Mylo") }` with `class ScreenedPorch : Porch() { fun latch(): Boolean = true }`, asserting `ScreenedPorch.Dispose()` contains the `_scopeHandle` block; plus an abstract owner (`abstract class Walker { suspend fun walk(): String }`, `class DogWalker : Walker()`) driven through `await using`. The collision cell is Tier 1 only, and its `@JvmName("playNullable")` is a harness artefact: Tier 1 compiles the fixture for the JVM, where `play(String)` and `play(String?)` clash; do not copy the annotation into `test-library/` (Kotlin/Native does not need it) and do not drop the cell because it fails to compile without it.

Tier 1 cells assert on generated text: `class TimedFeeder : Feeder` with no second `IAsyncDisposable`, no second `_scopeHandle`, no `DisposeAsync` on the subclass, a scope-aware `override void Dispose()`, `internal IntPtr GetOrCreateScope()`, and no `timedfeeder_fill_async` Kotlin export.

## Deferred scope

- Covariant return on an `override suspend fun` (the derived override narrowing the return type): inherits the base's `Task<Base>`; a `new` re-projection is a follow-up.
- Admitting `suspend`/Flow members on exported interfaces through the ADR-040 wrapper (only the flag derivation folds in).
- Generic owners (ADR-147 refuses their legacy-route members; unchanged).
- Moving the suspend/Flow routes onto the ADR-062 callable plan; this design keys off "what projected", so it survives that move unchanged.
- An abstract `suspend fun` declared on an abstract class (no body to export): confirm it is skipped with a named diagnostic; not investigated.

## Open what-questions

1. Abstract owner: `public abstract ValueTask DisposeAsync();` plus `override` on each concrete class (recommended, mirrors `Dispose`), or give abstract classes a `Native_Dispose` import so the abstract owner hosts the one concrete body? Spike note: neither option changes the Kotlin ABI, since `walker_dispose` is already exported for an abstract class (F2); the second option is one C# import (`CirNativeImports.kt:246`) and removes the per-concrete-class `override DisposeAsync`. Recommendation still abstract/override, because it mirrors `Dispose` and keeps an abstract class free of native imports, but it is now a closer call. Human decision: pending.
2. `override suspend fun` on a subclass: skip the re-projection and rely on Kotlin dynamic dispatch (recommended), or render `public new async Task<T>`? Recommendation: skip. Human decision: pending.
3. `GetOrCreateScope()` visibility: `internal` (recommended, matches `_scopeHandle`, works for sibling sealed arms in the same file) or `private protected`? Human decision: pending.
4. Does ROADMAP:49 (interface wrapper flag) close with this item or stay open until interface async members are admitted? Recommendation: close the flag-derivation half here, leave a one-line ROADMAP note for admission. Human decision: pending.
5. RESOLVED 2026-09-21: shapes (b), (c), the abstract owner, the override, the open-arm base and the collision were all reproduced by spike (F2, F4). The implementing run still writes them as failing Tier 1 text cells first, but no longer needs to discover them.
6. ADR number: assign at implementation time (ADR-157 is the last on disk; other parallel research may claim the next ones). Human decision: pending.
7. NEW 2026-09-21: fix the coroutine-free-module `Interlocked` break (rule 9) inside this change, or split it to its own ROADMAP line and keep the refused-only cell in a module with another async member? Recommendation: fix here, it is one line on the path this item already edits and the refused-only fix lands on it. Human decision: pending.

## Spikes run (2026-09-21)

Seam for all: a scratch `Tier1SpikeSubclassScopeTest.kt` in the worktree `kn-w1` (detached `651cb7d0`), one `@Test` per shape, each running the real processor through `Tier1Harness.run(..., libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))` and dumping `Interop.cs` and `CNameExports.kt`; then `dotnet build` (SDK 10.0.301) of each `Interop.cs` alone in a `mktemp -d` classlib with net8.0, LangVersion 12.0, Nullable enable, TreatWarningsAsErrors, AllowUnsafeBlocks, GenerateDocumentationFile, NoWarn CS1591, no implicit usings (the ADR-138 gate's properties). Command: `./gradlew :nuget-processor:test --tests "*Tier1SpikeSubclassScope*"` (about 35 s), then `dotnet build -nologo -v q` per shape. Every shape: KSP exit OK, no KSP errors, generated Kotlin compiles clean on the JVM. The scratch test and the one-line processor edit were deleted afterwards; nothing was committed.

| Spike | Shape | Result |
|---|---|---|
| a | base no async, derived `suspend fun` | CS0103 x3 (`GetOrCreateScope`, `_scopeHandle` x2). Backlog case reproduced |
| b | base `suspend fun`, derived none | CS0108 on `TimedFeeder.DisposeAsync()`; stray `timedfeeder_fill_async` export |
| c | both declare `suspend fun` | CS0122 on `Feeder.GetOrCreateScope()` + CS0108 |
| abs, absonly | abstract class with `suspend fun`, with and without a concrete subclass | CS0535 on `Walker`; Kotlin already exports `walker_dispose` |
| ovr | `override suspend fun` over `open suspend fun` | CS0108 on `FillAsync(CancellationToken)` + CS0122 + CS0108; base export dispatches virtually through `asStableRef<Feeder>()` |
| arm | ordinary class under an open sealed arm that owns a scope | CS0122 on `Roost.Perch.GetOrCreateScope()` + CS0108 |
| flowb | base Flow method, derived none | Build succeeded; derived `Dispose()` has no scope block: silent leak on sync dispose, shipping today. Not predicted by pass 1 |
| naive fix | `:1133` first term replaced by `asyncMembers.isNotEmpty()`, shapes b and refused re-run | b: Build succeeded with the bare derived `Dispose()`: the trap is real. refused: `Board : IDisposable, INugetHandle`, then CS0103 `Interlocked` |
| refused | class whose only suspend member is refused, coroutine-free module | CS0246 `ValueTask`, CS0246 `Task`, CS0738. Pass 1 said spurious interface; it is a build break |
| plain | `class Bowl { fun size(): Int = 1 }`, nothing else | CS0103 `Interlocked`. Pre-existing, separate bug (F3) |
| coll | `play(String)` / `play(String?)` suspend overloads | KSP silent, CS0111 on `PlayAsync` |
| dropped | suspend member on an unexported (ADR-101) base | Build succeeded; derived class is the owner, `api_nap_async` exported |

Not spiked: the runtime leak on flowb and under the naive fix (text-verified only; needs a cold Kotlin/Native link plus a `LeakTests` row, outside the 25-minute budget and not needed to choose the design); that `emitCsharpSignatureCollisions` fires once it sees async methods (needs the code move); the `Interlocked` break through the real `nugetCompileInterop` task rather than its mirrored csproj; F6's Flow-typed interface property question; an abstract `suspend fun` with no body (deferred scope).

## Spike first

Nothing blocks Step 2 or Step 3. What remains can only be checked after implementation:

- The `LeakTests` Flow-twin row is red before the fix and green after (this is the runtime proof of the F2/F3 leak, which the spikes verified in generated text only).
- `await using` on a concrete subclass of an abstract owner drains through `override DisposeAsync` (rule 4), end to end through `scripts/verify.sh`.
- A base-typed reference calling `FillAsync()` on a derived instance reaches the Kotlin override (rule 5), asserted by return value in xunit.
- The moved guard reports `ERROR_CSHARP_SIGNATURE_COLLISION` for the `coll` shape (Tier 1 diagnostic cell).
