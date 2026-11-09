# Immutable all-scalar data classes cross as C# values (issue #611)

- ROADMAP: Future Improvements line as of 2026-11-09: "Map data classes to C# `record class` if a safe `with`-expression pattern can be found (see ADR-008)". Issue #611: "Revisit ADR-008 for immutable data classes whose members are all scalars or strings".
- Researched: 2026-11-09, about 12 of 20 minutes. No spike was runnable: no `dotnet`, `kotlinc` or Konan on the sandbox, `dotnet-install.sh` refused by the proxy (403), and Gradle is off limits for research.
- Restatement: Kotlin to C# (forward). Kotlin declares `data class Toy(val name: String, val colors: List<String>)`, C# consumes it. The consumer gets a `Toy` with no handle and no `Dispose`, whose computed members and `Equals`/`GetHashCode`/`ToString`/`Copy` still run Kotlin's code, and whose construction and `Copy` still run Kotlin's constructor validation. A data class holding a handle-typed component keeps ADR-008.
- Verdict: fix. ADR-242 drafted (Proposed), `docs/adr/242-scalar-data-classes-as-values.md`.

## Findings

1. The value class route is the one-component version of this design: constructors route through a Kotlin `_create` export, members re-wrap the receiver on the Kotlin side per call. `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardCallablePlanner.kt:1365-1408` (`VALUE_CLASS_MEMBER`, `withValueClassConstruction`) and `forward/ForwardCirPlanProjection.kt:302-330` (receiver as parameter zero). **Verified by reading.**
2. Every Kotlin export the snapshot design needs already exists for a data class: `_equals`, `_hashcode`, `_tostring` and the planned `_copy` at `exports/ClassExports.kt:567-611`; constructors and properties come off the plan catalog. **Verified by reading.** So the Kotlin side does not change.
3. `Copy` already has ADR-164 "every parameter defaults to the receiver's value" semantics: `forward/ForwardCallablePlanner.kt:2593-2625`. **Verified by reading.**
4. The C# `Equals`/`GetHashCode`/`ToString` bodies today pass `_handle`: `cir/CirClassRenderer.kt:908-921`; `renderDispose` is called unconditionally for a class at `cir/CirClassRenderer.kt:416-424`. **Verified by reading.**
5. A forward handle already releases on finalization: `NugetKotlinHandle : SafeHandle`, `cir/CirRenderer.kt:76-84`. **Verified by reading.** This is why "hide `Dispose`" (option c) is cheap, and why it was rejected: Kotlin memory then lives until a GC that cannot see it.
6. `NugetMarshal.Wrap<T>` already has an owned-mint protocol for a value minted per call (`owned = false; mint; owned = true`), via `INugetGenericValue.NugetBox()`: `cir/CirMarshalRenderer.kt:622-641`, interface at `cir/CirRenderer.kt:43-46`. **Verified by reading.** A snapshot at an erased position (`List<Toy>` write, `Flow<Toy>`, `Box<Toy>`, `Any`) rides this.
7. `ForwardOwnership { BORROWED, OWNED_HANDLE, MATERIALIZED }`: `forward/ForwardMarshallingModel.kt:677`. **Verified by reading.** A snapshot read is MATERIALIZED, like a collection.
8. `BridgeType.ObjectHandle` is tested with `is` at 158 sites in 26 files under `nuget-processor/src/main` (grep count, **verified**). A new `BridgeType.DataValue` variant breaks every exhaustive `when` at compile time, but the plain `is` tests do not, so each needs a hand audit.
9. A klib (dependency-module) declaration reports different modifiers from an in-module one (`INLINE`, not `VALUE`): `forward/ForwardBridgeTypeClassifier.kt:1812-1820`. **Verified by reading** (ADR-066 spike). Whether `Modifier.DATA`, `hasBackingField` and `isDelegated()` read correctly for a klib class is **inferred, unverified**; hence v1 admits in-module data classes only.
10. KSP `KSPropertyDeclaration.hasBackingField` / `isDelegated()` are not used anywhere in the repo (grep, **verified absent**). The admission predicate depends on them. **Inferred** from KSP docs that they report a body `val x = expr` as backed and a getter-only `val` as not. **Red: if wrong, a class with body state is admitted and every member call silently sees a re-initialised field.** Must be pinned by a Tier 1 cell before the predicate ships.
11. Fixture blast radius: 44 `using` sites over `Toy` alone in `IntegrationTests` and `LeakTests` (grep, **verified**); about 129 `data class` declarations in `test-library/src/nativeMain`, of which an unknown number qualify.
12. Prior art: Swift Export keeps a data class a Swift `class`; a JetBrains engineer said unconditional struct conversion would be incorrect and an opt-in might come (https://kotlinlang.org/docs/native-swift-export.html, https://slack-chats.kotlinlang.org/t/26928229/has-anyone-seen-anything-about-value-type-swift-struct-expor, **inferred** from those pages). ObjC export: a class with `doCopy`/`isEqual`/`hash`/`description` (https://www.github.com/kotlin-hands-on/kotlin-swift-interopedia/blob/main/docs/classesandinterfaces/Data%20classes.md, **inferred**). Skipped: Java, JS, Wasm export (all keep a reference class with delegated members, so they do not inform the snapshot decision; the repo's own value class route settles it).
13. C# `record class` with only get-only properties: `with { X = ... }` does not compile and `with { }` yields an equal copy; record-synthesized equality compares list components by reference. **Inferred** from the C# records spec, unspiked (no SDK).
14. Kotlin `copy()` calls the primary constructor, so `init` runs. **Inferred** from Kotlin docs; non-load-bearing, since C# `Copy` keeps calling the existing `_copy` export.

## Recommendation

Option (a), value snapshot with transient re-materialisation, as ADR-242 specifies. C# `public sealed class Toy : IEquatable<Toy>` with get-only component properties; the public constructor calls `Native_Create` (validates, releases the handle); every Kotlin member mints a transient instance, calls the existing export, releases it in a `finally`. Reading a value Kotlin returns copies its components and releases the handle. The wire and every Kotlin export stay as they are. This is the end state the codebase converges on: the value class route generalised to N components, on the ADR-062 plan route with a new `BridgeType.DataValue` variant.

Rejected:
- (b) eager computed properties at crossing: no answer for methods with parameters, freezes non-pure getters.
- (c) finalizer-owned hidden handle: GC-coupled Kotlin lifetime, every component read still crosses.
- (d) hybrid snapshot plus cached handle: (c)'s lifetime again; kept as a post-benchmark optimisation.
- `readonly record struct`: `default(Toy)` bypasses validation, `Toy?` changes meaning.
- A flag on `ObjectHandle` instead of a new variant: an unaudited site leaks or fails to compile.

## Files an implementation touches

- `nuget-processor/.../forward/ForwardMarshallingModel.kt` (new `BridgeType.DataValue`), `forward/ForwardBridgeTypeClassifier.kt` (predicate, near `:465` and `:630`), `forward/ForwardCallablePlanner.kt` (receiver projection for members, constructor, `copy`, `equals`/`hashCode`/`toString` plans), `forward/ForwardCirPlanProjection.kt`, `forward/ForwardCirCollectionComponents.kt` (element read/write, also touched by ADR-243), `forward/ForwardPropertyPlanner.kt`, and every other file among the 26 holding an `is BridgeType.ObjectHandle` test.
- `cir/CirClassRenderer.kt` (`renderClass`, `renderDataClassMethods` at `:908`, skip `renderDispose`), `cir/CirModel.kt`, `cir/CirClassTranslator.kt` (`:1123`, `:1662`), `cir/CirMarshalRenderer.kt` (`Wrap<T>` owned branch), `cir/CirRenderer.kt` (mint interface), `cir/CirAnyRenderer.kt` (`:120`).
- `exports/ClassExports.kt`: no change expected.
- Fixture: new `test-library/src/nativeMain/kotlin/.../plushie/PlushieSample.kt` (`data class Plushie(val name: String, val colors: List<String>, val mood: Mood, val squeaks: Int)` with `init { require(name.isNotBlank()) }`, `val label get()`, `fun describe(loud: Boolean)`, plus `favouritePlushie()`, `echoPlushie(p)`, `plushies()`, a `suspend` and a `Flow` return), and a negative fixture per predicate exclusion (`var` component, body `val x = ...`, `by lazy`, handle component, sealed arm) that stays a handle.
- Tests: `IntegrationTests/PlushieValueTests.cs` (new), `IntegrationTests/DataClassTests.cs` and every other `using` over a qualifying fixture type, `LeakTests/LiveHandleTests.cs` (new row, plus existing `Toy` rows at `:1487-1511` lose `using`), a Tier 1 cell pinning the predicate over klib-free in-module shapes.
- Docs: `docs/topics/data-classes.md`, `docs/topics/supported-features.md` (data class row), ADR-008 amendment note, ROADMAP Future Improvements line, release notes "Breaking changes".

## Sample test

```csharp
[Fact]
public void Plushie_CrossesAsAValue_WithKotlinComputedMembers()
{
    Plushie p = PlushieSampleKt.FavouritePlushie(); // no using, no Dispose
    Assert.Equal("Bun", p.Name);
    Assert.Equal(new[] { "pink", "white" }, p.Colors);
    Assert.Equal("Bun (pink/white)", p.Label);
    Assert.Equal("BUN!", p.Describe(loud: true));
    Assert.Equal(p, new Plushie("Bun", new[] { "pink", "white" }, Mood.Happy, 3));
    Assert.Equal("Plushie(name=Bun, colors=[pink, white], mood=Happy, squeaks=3)", p.ToString());
    Assert.Throws<KotlinArgumentException>(() => p.Copy(name: ""));
    Assert.False(typeof(IDisposable).IsAssignableFrom(typeof(Plushie)));
}

// LeakTests/LiveHandleTests.cs, deterministic, no GC
[Fact]
public void ScalarDataClass_EveryCrossing_ReturnsToBaselineWithoutDispose()
{
    long before = NugetMarshal.LiveHandles;
    for (int i = 0; i < 1000; i++)
    {
        Plushie p = PlushieSampleKt.EchoPlushie(PlushieSampleKt.FavouritePlushie());
        _ = p.Label; _ = p.Describe(false); _ = p.GetHashCode(); _ = p.ToString();
        _ = p.Equals(p.Copy(squeaks: 4));
        _ = PlushieSampleKt.Plushies().Count;
        Assert.Throws<KotlinArgumentException>(() => new Plushie("", [], Mood.Happy, 0));
    }
    Assert.Equal(before, NugetMarshal.LiveHandles);
}
```

The `Plushie`/`PlushieSampleKt` names and the facade class spelling are illustrative; match the fixture's real facade name.

## Deferred scope

Generic data classes; sealed arms; a data class with a superclass or an exported interface; `Throwable` data classes (ADR-241's route); dependency-module data classes; `Deconstruct` for `componentN`; a flattened N-slot wire for direct positions; caching `GetHashCode`; the hybrid cached-handle optimisation (gated on the crossing benchmark); an opt-out switch.

## Open what-questions

1. When does the C# source break land, given ROADMAP says 0.9.0 carried every forward break? Recommendation: default on, next minor, "Breaking changes" entry; alternative a Gradle opt-in until 1.0. Human decision: pending.
2. `sealed class : IEquatable<T>` or `sealed record class`? Recommendation: `sealed class`, plus `==`/`!=` delegating to `Equals` (two crossings of one value are two C# objects). Human decision: pending.
3. Admit value class, `Instant`, `Duration`, `Uuid` and nested scalar data class components (acyclic type graph only) in v1? Recommendation: yes, so `Order(val toy: Toy)` does not keep a `Dispose` that `Toy` lost. Human decision: pending.
4. A side-effecting `init` re-runs per member call and per crossing into Kotlin. Accept and document (value class precedent, ADR-235), or exclude any data class with an `init` block (which also excludes the `require` validation the issue wants kept)? Recommendation: accept and document. Human decision: pending.
5. Strike the ROADMAP "record class if a safe `with`" line? Recommendation: strike, pointing at ADR-242; no safe modifying `with` exists, because an `init` accessor cannot run a cross-field `require`. Human decision: pending.

### Decided at the gate (2026-11-09, human)

- Q1: on by default in the next minor, with a Breaking changes entry. Strike the Future Improvements line about `record class` and point it at ADR-242.
- The remaining questions keep the memo's recommendations.
