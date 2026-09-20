# Generic-route `FromHandle<TResult>` does not resolve a stored C#-implemented interface original

- ROADMAP: line 22 as of 2026-09-20 (original wording): "Deferred by ADR-136: the generic-function route's `FromHandle<TResult>` (`cir/CirFunctionRenderer.kt` ~:286) still constructs a fresh wrapper rather than resolving a stored C#-implemented original, since no interface fixture reaches that route; `List<Interface>` also stays refused (ADR-040 scope, unchanged)."
- Researched: 2026-09-20, about 10 of 20 minutes. Source reading only: no Gradle, no `dotnet`, no spike was run (shared-checkout constraint), so every runtime claim below is **inferred** unless it says **verified by reading**.
- Restatement (from the item's own words): a C# consumer whose own `IPet` implementation was stored by Kotlin gets that SAME instance back (`Assert.Same`) when Kotlin hands it back through an erased generic read (`NugetMarshal.FromHandle<TResult>`), as ADR-136 already guarantees on the `Task<IPet>` and `Flow<IPet>` reads. Forward; Kotlin declares, C# consumes and implements. `List<Interface>` out of scope.
- Verdict: **blocked as written; redesign queued.** The main-thread restatement as given (`fun <T> identity(x: T): T` called with a C#-implemented `IFoo`) is blocked/entangled: it needs one shared design across all three erased routes, not a fix local to the cited line. One narrower read-only slice is reachable, but it has a spelling prerequisite (finding 5). Needs a new ADR (not drafted: the scope is a human what-question first). It implements ADR-136's rejected Alternative 2, which ADR-136 says "can be revisited if a generic route ever needs it".
- 2026-09-20 human decision (recorded post-research, at the Phase 4 batch design gate): not built in this batch. The ROADMAP item is rewritten to state the actual argument-step blocker rather than the original line's read-side framing. The recommended end state is the single design below (`Recommendation` section), to be run through `feature-design` as its own item with a new ADR when picked up. This memo stays as the design's starting point.

## Findings

### 0. The main-thread restatement conflates two routes (verified by reading, 2026-09-20 re-check: line numbers current)

`CirFunctionRenderer.kt:286` is `KotlinSuspendFunc<TResult>.InvokeAsync` (arity 0): `t.SetResult(NugetMarshal.FromHandle<TResult>(resultPtr))`. Its twins are `:78` and `:110` (`KotlinFunc.Invoke`) and `:352` (suspend, arity > 0). ADR-136 (`docs/adr/136-...md:83-84`, `:202-204`) calls this lambda-return helper "the generic-function route". It is NOT `fun <T> identity(x: T): T`. That declaration takes the ADR-064 legacy generic-function route (`cir/CirFunctionTranslator.kt:765-943`, Kotlin half `exports/GenericFunctionExports.kt`), which uses neither `WrapArg<T>` nor `FromHandle<TResult>`: it casts `((INugetHandle)value!).Handle` (`CirFunctionTranslator.kt:916`, `:920`) and reads `NugetMarshal.Materialize<T>(result)` (`:922`). There is a third erased route, the ADR-147 generic-class `T` on the ADR-062 plan: `NugetMarshal.Wrap<T>` in (`forward/ForwardCirPlanProjection.kt`), `FromHandle<T>` out.

### 1. LOAD-BEARING: a C#-implemented interface cannot enter ANY erased argument position today (verified by reading, 2026-09-20 re-check: line numbers current; runtime exception types inferred, not run)

| Route | Argument step | What a C# `Dog : IPet` hits |
|---|---|---|
| Lambda `KotlinFunc` / `KotlinAction` | `WrapArg<T>` `cir/CirFunctionRenderer.kt:41-52` | `:50` `value is INugetHandle` is false, `:51` `NotSupportedException("Cannot wrap Dog as lambda argument")` |
| Suspend lambda | `WrapArg<T>` `CirFunctionRenderer.kt:231-242` | same, `:241` |
| Legacy `fun <T>` (`identity`, `adoptPet`) | `((INugetHandle)value!).Handle` `CirFunctionTranslator.kt:916`/`:920` | `InvalidCastException` |
| ADR-147 generic class (`PetBox<T : Pet>`, `PetCrate<T>`) | `Wrap<T>` `cir/CirMarshalRenderer.kt:306-339` | `:337` false, `:338` `NotSupportedException("Cannot pass Dog to a Kotlin collection")` |

So `Helpers.AdoptPet<IPet>(dog)` and `Helpers.AdoptPet<Dog>(dog)` both COMPILE today (`where T : IPet`, fixture `test-library/.../cat/Helpers.kt:7`, pinned by `IntegrationTests/GenericConstraintTests.cs:34-57`) and throw at the argument, before any read. The restatement's scenario never reaches a `FromHandle`/`Materialize` read. The lambda-route half of this is the ROADMAP `WrapArg<T>` item's territory; the other two argument gates have no ROADMAP line of their own.

Suggestion for whoever owns the argument side: `NugetMarshal.HandleOf(object value, out bool owned)` (`CirMarshalRenderer.kt:531-546`) already does the `INugetHandle` test plus the `NugetBridge.HandleFor` fallback with correct ADR-084/135 ownership reporting. `Wrap<T>`'s last two lines, `WrapArg<T>`'s last two lines and the legacy route's cast could all delegate to it. `Wrap<T>` call sites already dispose on `owned`; `WrapArg<T>` and the legacy route have no cleanup and would need one. Inferred: `HandleOf` only falls back when `helper.includesBridge` (`CirMarshalRenderer.kt:520-525`).

### 2. The read side is also broken for `T = IPet`, and not in the way the ROADMAP said (verified by reading, 2026-09-20 re-check: line numbers current)

`FromHandle<T>` falls through to `Materialize<T>` (`CirMarshalRenderer.kt:295`, `:123-127`), a `Factories[typeof(T)]` lookup. `factoryEntries` (`cir/CirTranslator.kt:859-`) registers nothing under an interface type (doc comment `:856-857`: "Enums, value classes, objects, interfaces and open generic wrappers register nothing"), so:
- `T = IPet` (Kotlin-backed or not): `NotSupportedException("No generated factory materialises ...IPet...")`. It THROWS, it does not "construct a fresh wrapper".
- `T = Pet` (the ADR-040 backing wrapper, a `public sealed class`): the backing `CirClass` is added to `namespace.declarations`, so `factoryEntries` registers `typeof(Pet)` and the read is `new Pet(handle)`: a fresh wrapper, never resolved. This is the only case matching the original ROADMAP wording, which is why that wording undersold the problem: it described only this one reachable-but-wrong case, not the argument-step blocker that actually stops a consumer's `Dog : IPet` before this read is ever tried.
- `T = Dog` (consumer type): no factory can ever exist; on any erased route the token resolve is the ONLY correct read, not an optimisation.

### 3. How ADR-136 resolves, and why the probe works on these handles (verified by reading)

No registry keyed by type. `NugetMarshal.TryResolveCSharp<T>(IntPtr, out T) where T : class` (`CirMarshalRenderer.kt:563-`) P/Invokes `nuget_csharp_token` (`nuget-runtime/.../NugetRuntime.kt`: `handle.asStableRef<Any>().get() as? NugetCSharpBridge)?.nugetToken`), and on a hit disposes the transfer handle and returns `GCHandle.FromIntPtr(token).Target`. Keyed by the handle's target object, so it needs no `typeof(TResult)` at all. The erased routes mint the same shape: `nuget_func0_invoke` returns `NugetHandles.retain(fn.invoke() as Any)`; legacy generic `_object` export returns `NugetHandles.retain(f(x.asStableRef<Any-or-bound>().get()))`. Inferred: the suspend-lambda and ADR-147 box paths retain the bare object too (not opened).

### 4. AOT / reflection (ADR-094, ADR-102): no obstacle (inferred, not compiled)

A probe inside `Materialize<T>` is one P/Invoke plus a cast, no reflection, no new table. `TryResolveCSharp` has a `where T : class` constraint and `Materialize<T>` is unconstrained, so it needs an `object`-typed sibling (`TryResolveCSharpObject(IntPtr, out object)`) and a `(T)` cast. A `Factories` line `[typeof(global::Ns.IPet)] = static handle => new global::Ns.Pet(handle)` is a statically written line like every other entry, so trimmer and AOT safe by the ADR-094 argument.

### 5. Prerequisite on the lambda route: an interface type argument is spelled as the wrapper, not the interface (verified by reading, 2026-09-20 re-check: line numbers approximate, drifted since original research; emitted text inferred, no Tier 1 cell exists)

`csTypeArgument` (`cir/CirTypeMapping.kt:440-`) admits an exported interface and spells it through `qualifiedElementCsType` then `nestedCsName()`, which keeps the LAST segment bare. So `fun supplier(): () -> Pet` renders `KotlinFunc<global::TestLibrary.Cat.Pet>`: the backing wrapper at a public declared position, which ADR-040 says no consumer sees, and which makes `Assert.Same(dog, ...)` untypeable (`Dog` is not a `Pet`). The Flow route fixed the same defect with `classifier.legacyFlowElementInterface(...)?.csharpType` (`CirClassTranslator.kt`, now used at several call sites including `:3445`). Also inferred: the backing class is only generated for an interface reachable in a planned return position; an interface reachable ONLY through a lambda type argument may have no `Pet` class at all, making today's spelling a CS0246. No ROADMAP or backlog line covers this spelling defect.

### 6. No fixture reaches any of this (verified by grep, 2026-09-20 re-check: still true)

No `-> Pet` / `(Pet) ->` lambda in `test-library/src` or `nuget-processor/src/test`. `AdoptPet`/`PetBox`/`PetCrate` are only ever instantiated with `T = Cat`.

## Recommendation

End state (one ADR, size M): make the erased generic boundary interface-aware in `NugetMarshal` itself, once, rather than per route.
1. Read: `Materialize<T>` probes the token first (hit: dispose transfer handle, return `(T)original`), then `Factories`. `factoryEntries` registers each interface under `typeof(I<Name>)` with `new <Backing>(handle)` (needs a `backsInterface` marker on `CirClass`, or a walk of `CirInterface` plus its sibling backing class; nested names per ADR-134). This fixes all three routes' reads, including `T = Dog`.
2. Write: `Wrap<T>`, `WrapArg<T>` (both copies) and the legacy route's cast delegate to `HandleOf(value, out owned)`, with an owned-dispose after the native call where none exists.
3. Spell: lambda type arguments spell an interface as `I<Name>` (reuse `legacyFlowElementInterface`).

Priced: about 7 generator files, 1 fixture file, 3 test files, 1 Tier 1 file, 2 docs. Step 2 subsumes the interface half of the ROADMAP `WrapArg<T>` item and must be sequenced with that item's owner (same lines of `CirFunctionRenderer.kt`).

Alternatives rejected:
- Read-only slice (steps 1 and 3, lambda route only, Kotlin stores via `Befriend` then returns through `() -> Pet`): smaller (S/M), delivers the original ROADMAP line's literal words, but leaves `AdoptPet<IPet>(dog)` throwing, so it does NOT deliver the main-thread restatement. Valid as a first PR of a stack.
- Per-site `read:` delegate on `KotlinFunc` (the ADR-136 Alt 1 shape): does not generalise, `T` is open on the legacy and ADR-147 routes so there is no site to specialise.
- Pin and close: leaves a compiling call (`AdoptPet<IPet>`) that throws at runtime.

## Files an implementation touches

Generator: `nuget-processor/src/main/kotlin/.../cir/CirMarshalRenderer.kt` (`Materialize`, `Wrap`, object-typed resolve), `cir/CirTranslator.kt` (`factoryEntries`), `cir/CirModel.kt` (`CirFactoryEntry` / `CirClass` marker), `cir/CirClassTranslator.kt` (`translateInterfaceBackingClass` marker; lambda property spelling), `cir/CirFunctionRenderer.kt` (`WrapArg` x2, owned-dispose in `Invoke`/`InvokeAsync`; sequence with the `WrapArg<T>` ROADMAP item), `cir/CirFunctionTranslator.kt` (lambda return spelling, legacy generic body), `cir/CirTypeMapping.kt` (`csTypeArgument` interface arm). Possibly `exports/SealedClassExports.kt` (its own `KotlinFunc` arm). No Kotlin emission, runtime or ABI change expected (inferred; `nuget_csharp_token` already in `ForwardAbiContract.kt`).
Fixtures: `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/Cat.kt` or `PetSitter.kt` (add `fun friendSupplier(): () -> Pet`), `cat/Helpers.kt` (existing `adoptPet`, no change).
Tests: `IntegrationTests/BidirectionalTests.cs`, `IntegrationTests/GenericConstraintTests.cs`, `LeakTests/LiveHandleTests.cs` (new rows beside the existing 6e/6f rows: one per route, because the freeing site moves from the consumer's `using` to the read, and step 2 adds a minted transfer handle per argument), Tier 1: a new interface-spelling Tier 1 test (lambda type-argument spelling) plus a `Factories` text pin.
Docs: `docs/topics/generics.md`, `docs/topics/interfaces-abstract-sealed.md`, `FEATURES.md` row, ADR-136 deferral paragraph becomes historical.

## Sample test

```csharp
[Fact]
public void StoredCSharpPet_RoundTripsToTheOriginalInstance_OverKotlinFunc()
{
    using var oreo = new Cat("Oreo", 9);
    using IPet dog = new Dog("Rex");
    oreo.Befriend(dog);
    using KotlinFunc<IPet> supplier = oreo.FriendSupplier();
    Assert.Same(dog, supplier.Invoke());
}

[Fact]
public void AdoptPet_CSharpImplementedPet_ReturnsTheSameInstance()
{
    using IPet dog = new Dog("Rex");
    Assert.Same(dog, Helpers.AdoptPet<IPet>(dog));   // today: InvalidCastException (inferred)
}

[Fact]
public void AdoptPet_KotlinBackedPetTypedAsInterface_Materialises()
{
    using var oreo = new Cat("Oreo", 9);
    using IPet adopted = Helpers.AdoptPet<IPet>(oreo); // today: NotSupportedException (inferred)
    Assert.Equal("Oreo", adopted.Name);
}
```

## Deferred scope

- `List<Interface>` stays refused (ADR-040). Note the side effect: once `Materialize<T>` resolves, a `List<T>` read on an ADR-147 generic class instantiated at `T = IPet` would resolve per element. That is not the refused static `List<Pet>` route, but say so in the ADR.
- Non-interface halves of the ROADMAP `WrapArg<T>` item (narrow scalars, value-class arguments in `WrapArg<T>`).
- `T = Dog` when Kotlin returns a different, Kotlin-backed pet: `(T)` cast fails with `InvalidCastException`; inherent to erasure, document only.
- Kotlin-side `===` identity across crossings (unchanged from ADR-084).

## Open what-questions

1. WHAT is the item: the ROADMAP's literal lambda-return read, or the restatement's pass-through `identity<IFoo>(dog)`? Recommendation: the end state above (both), landed as a two-PR stack: read-and-spell first, argument side second, the second coordinated with the `WrapArg<T>` item's owner. Human decision: pending (see item 5 below for the batch-level decision already made).
2. Should `Materialize<T>` probe the token on EVERY erased object read (one extra P/Invoke per object element, all routes), or only when `typeof(T).IsInterface || !Factories.ContainsKey`? Recommendation: probe only on a `Factories` miss or an interface key, so the `T = Cat` hot path is unchanged. (`typeof(T).IsInterface` is reflection-light and AOT-safe; inferred.)
3. Is re-spelling `KotlinFunc<Pet>` as `KotlinFunc<IPet>` a breaking change anyone depends on? Recommendation: no fixture or doc shows it; treat as a defect fix under ADR-040.
4. How: marker on `CirClass` vs deriving the interface key from `interfaces == ["I$name"]`. Recommendation: explicit marker.
5. **Human decision, 2026-09-20 (Phase 4 batch design gate): not built in this batch.** The ROADMAP item is rewritten to state the argument-step blocker (finding 1) rather than the original read-side-only framing, and cross-linked with the `WrapArg<T>` item since step 2 of the recommendation above is that item's interface arm. When this is picked up, run it through `feature-design` as its own item with a new ADR; this memo's `Recommendation` section is the proposed starting design.

## Spike first (for `kotlin-dev`, nothing here was run)

a. `Helpers.AdoptPet<IPet>(new Dog("Rex"))` in `IntegrationTests`: confirm `InvalidCastException` at the argument. If it somehow passes, finding 1 is wrong and the item is not blocked.
b. `Helpers.AdoptPet<IPet>(oreo)`: confirm `NotSupportedException` from `Materialize<IPet>`.
c. Tier 1 cell for `fun supplier(): () -> Pet`: confirm the emitted type is `KotlinFunc<global::...Cat.Pet>` and whether the member is emitted at all; and for an interface reachable only through the lambda, whether the backing class exists.
d. Generated `Interop.cs` for `test-library`: confirm `Factories` has `typeof(global::TestLibrary.Cat.Pet)` and no `typeof(...IPet)`.
e. After the change: `static handle => ...` interface entry and the `(T)` cast compile under the AOT/trim analyzers the repo already runs (ADR-102 leg).
