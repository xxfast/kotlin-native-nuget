# `NugetMarshal.FromHandle<T>` has no enum branch, so `StateFlow<SomeEnum>` is unsupported

- ROADMAP: line 74 as of 2026-09-29: "**`NugetMarshal.FromHandle<T>` has no enum branch, so `StateFlow<SomeEnum>` is unsupported** ([details](docs/backlog/fromhandle-no-enum-branch.md))"
- Researched: 2026-09-29, about 10 minutes of a 20 minute budget
- Restatement (forward, Kotlin declares): a C# consumer reads `StateFlow<Mood>.Value`, `StateFlow<Mood?>.Value`, `await foreach` over `Flow<Mood>` / `Flow<Mood?>`, and `Box<Mood>.Value` on an exported generic class, and gets the mapped C# enum instead of `NotSupportedException`. The backlog's `List<Mood>` getter, sealed-base and value-class claims were checked and are stale (see Findings 1 to 3).
- Verdict: fix. One `NugetMarshal.Factories` entry per enum (the ADR-094 / ADR-171 registry), no Kotlin shim change. No new ADR recommended; an amendment to ADR-094 (text below). If the human wants the alternative weighed on the record, it is ADR-177 material (next free number).

## Findings

1. **`List<Mood>` property getter already works; the backlog is stale on it.** ADR-097 made a bare enum collection component cross as its `Int` ordinal. Generated code (main checkout `test-library/build/generated/ksp/mingwX64/.../CNameExports.kt:13618`): `NugetHandles.retain(... .moods.map { it.ordinal })`; C# (`.../resources/Interop.cs:17704`): `NugetMarshal.ReadList<Mood>(nativeResult, static h1 => (Mood)NugetMarshal.FromHandle<int>(h1))`. `IntegrationTests/CollectionPropertyIndependenceTests.cs:155-163` (`Chart_Moods_EnumElement_RoundTripsThroughItsSetter`) calls the getter for real (`Assert.Equal(new[] { Mood.Anxious, Mood.Calm }, chart.Moods)`). **Verified** by reading generated output and the shipped test. No `MissingMethodException` to reproduce, so no runtime repro was attempted.
2. **Sealed-base element on `Flow`/`StateFlow` already works.** Issue #40 registers the sealed BASE in `Factories` via its discriminator (`nuget-processor/.../cir/CirTranslator.kt:1083-1084`, `CirMarshalRenderer.kt:120`). `IntegrationTests/Issue40Tests.cs:22-83` exercises `StateFlow<LoadState>.Value` and `await foreach` over `Flow<LoadState>`. **Verified** by reading code and the shipped tests.
3. **Value-class element on `Flow`/`StateFlow`: the generator output is correct; the runtime is unpinned.** ADR-171 registers every value class under `Factories` via `NugetUnbox` (`CirTranslator.kt:1111`, `CirMarshalRenderer.kt:121`). Tier 1 spike (below) shows `StateFlow<ChartId>`, `StateFlow<ChartId?>`, `StateFlow<Temperament>` and `Flow<ChartId>` spelled as the record struct, with the Kotlin shim retaining the boxed value (`NugetHandles.retain(obj.id.value as Any)`) and `[typeof(global::Tier1.ChartId)] = static handle => global::Tier1.ChartId.NugetUnbox(handle)` present. **Verified** (generator, spike). That `FromHandle<ChartId>` reaches that entry at runtime and disposes the box is **inferred** (read of `FromHandle<T>`/`Materialize<T>` at `CirMarshalRenderer.kt:142-156`, `:158-325`: a record struct is not `Nullable<>`, not a primitive, so it falls to `Materialize<T>`, `key.IsValueType` skips the ADR-173 probe, the key hits). No test-library fixture has a value-class flow element (checked: every `Kotlin*Flow<global::...>` in the main checkout's generated `Interop.cs` against its 26 `record struct` names, zero overlap). ADR-171 `docs/adr/171-value-classes-at-erased-generic-positions.md:351-353` records this as unchecked.
4. **An enum element crosses as a StableRef of the Kotlin enum object, not its ordinal.** Tier 1 spike: `StateFlow<Mood>` value shim is `NugetHandles.retain(obj.mood.value as Any)`, `Flow<Mood>` collect is `NugetHandles.retain(value as Any)`, `StateFlow<Mood?>` is `if (v != null) NugetHandles.retain(v) else null`. The emitter is `nuget-processor/.../exports/FlowExports.kt:458-463` (`itemBoxExpr`) and `:538-551` (value body). **Verified** (spike output, and main checkout generated `CNameExports.kt:7772-7773` for `CallbackFaults.moodStream`).
5. **The C# side passes no `read:` delegate for an enum element, so every read is `NugetMarshal.FromHandle<Mood>`.** Spike output: `new KotlinStateFlow<global::Tier1.Mood>(collect, () => Native_GetMoodValue(_handle))`, same for `Mood?`, `Flow<Mood>`, `Flow<Mood?>` and the `suspend`-less `StateFlow<Mood>` method return. `KotlinFlow<T>` defaults `_read = read ?? NugetMarshal.FromHandle<T>`. `FromHandle<Mood>` misses every branch and calls `Materialize<Mood>`, which throws `NotSupportedException` because "Enums, objects ... register nothing" (`CirTranslator.kt:1052-1053`). **Verified** by reading plus the shipped fault test `IntegrationTests/CallbackFaultTests.cs:251-270`, which asserts `Flow<Mood>` faults (it asserts `ThrowsAnyAsync<Exception>`, not the exception type).
6. **`Box<Mood>.Value` on an exported generic class (ADR-147) has the same hole and a per-member delegate cannot reach it.** Spike: `public T Value { get { ... return NugetMarshal.FromHandle<T>(nativeResult); } }` inside `public class Box<T>`, Kotlin `if (result == null) null else NugetHandles.retain(result)`. The member is generic in C#; it does not know `T = Mood`. **Verified** (spike). This is the finding that decides the recommendation.
7. **`MutableStateFlow<Mood>` degrades to a read-only `KotlinStateFlow<Mood>`** (no `_set_..._value` export; spike output). `isMutableStateFlowElementSupported` returns false for `ENUM_CLASS` (`cir/CirTypeMapping.kt:214`), ADR-071's deliberate deferral. Its `.Value` read is fixed by this item for free; its setter stays deferred. **Verified** (spike, reading).
8. **Generated C# enums carry explicit ordinals** (`Calm = 0, Playful = 1, Grumpy = 2`), so `(Mood)ordinal` is correct. **Verified** (spike output).
9. **Unboxing mechanics.** Scratch console app on .NET 10.0.9: `(T)(object)ordinal` works for `T = Mood` but throws `InvalidCastException` for `T = Mood?`; `(T)Enum.ToObject(key, ordinal)` works for both. A `Dictionary<Type, Func<IntPtr, object>>` entry `[typeof(Mood)] = static h => (Mood)ordinal` read through the exact `Materialize<T>` shape (`key = Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T); return (T)factory(h)`) returns `Grumpy` for `T = Mood` and `Playful` for `T = Mood?`. **Verified** (spike, JIT). Under NativeAOT: `dotnet publish -p:PublishAot=true` got through ILC with no `IL2xxx`/`IL3xxx` warnings for either shape, then failed at the native link step (`vswhere.exe` not found on this box), so AOT runtime behaviour is **inferred**, not run. The `Factories` shape is the same statically-written-lambda shape ADR-094 already relies on for AOT.
10. **The runtime already has the box-unwrap family** (`nuget-runtime/.../runtime/NugetRuntime.kt:103-141`, `nuget_unwrap_int` ... `nuget_unwrap_char`, each `handle.asStableRef<Any>().get() as X`), listed in the fixed ABI at `nuget-processor/.../ForwardAbiContract.kt:665-671`. An ordinal reader `(handle.asStableRef<Any>().get() as Enum<*>).ordinal` fits that family. **Verified** by reading; the new export itself is unwritten.
11. **Fixing this breaks the ADR-161 fault trigger.** `test-library/.../cat/CallbackFaults.kt:47-52,212-220` uses `Flow<Mood>` precisely because it fails to materialise; `CallbackFaultTests.cs:251-270` asserts `Assert.Empty(seen)`. After the fix it collects two moods and the test fails. **Verified** by reading.
12. **The parallel item (suspend fun returning `List<SealedBase>`) does not depend on this one.** A sealed base's `Factories` entry already exists (Finding 2), so any `FromHandle<Base>` element read there already dispatches. **Verified** by reading `CirTranslator.kt:1083-1084`; whether that route reads its elements through `FromHandle<Base>` or `Base.FromHandle` directly was not checked, and either works.
13. **Dependency-module enums are not covered by the registry walk.** `factoryEntries` walks this module's `namespaces` only (`CirTranslator.kt:1054-1116`), so an admitted dependency enum (ADR-154) on a `Flow` element keeps throwing. **Inferred** by reading; the same limit already applies to dependency value classes and classes.

### Spike commands

Tier 1 spike (scratch test file in the worktree, run, then deleted; not committed): `Tier1Harness.run(<fixture>, processorOptions = mapOf("nuget.namespace" to "Tier1"), libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))` over `enum class Mood`, `value class ChartId(String)`, `value class Temperament(Mood)`, `class Box<T>(val value: T)`, and a `Tracker` with `StateFlow<Mood>`, `StateFlow<Mood?>`, `MutableStateFlow<Mood>`, `StateFlow<ChartId>`, `StateFlow<ChartId?>`, `StateFlow<Temperament>`, `List<Mood>`, `fun moodStream(): Flow<Mood>`, `fun maybeMoods(): Flow<Mood?>`, `fun ids(): Flow<ChartId>`, `fun moodNow(): StateFlow<Mood>`. `./gradlew :nuget-processor:test --tests "*ZzSpikeEnumFlowTest*"`: `compiledClean=true`, no KSP errors. Quoted output lines are in Findings 3 to 8.

.NET spike: `dotnet new console`, the two generic readers of Finding 9, `dotnet run` printed `Grumpy`, `InvalidCastException` (for `(T)(object)ordinal` at `Mood?`), `Grumpy`, `Playful`, `10.0.9`; the `Factories` variant printed `Grumpy`, `Playful`.

## Recommendation

**Register every exported enum in `NugetMarshal.Factories`** (ADR-094's table, the exact move ADR-171 made for value classes and issue #40 for sealed bases), backed by one new fixed-ABI runtime export:

```kotlin
// nuget-runtime NugetRuntime.kt, beside nuget_unwrap_char
@NugetRuntimeApi
@CName("nuget_unwrap_enum_ordinal")
public fun export_nuget_unwrap_enum_ordinal(handle: COpaquePointer): Int =
  (handle.asStableRef<Any>().get() as Enum<*>).ordinal
```

```csharp
// NugetMarshal, beside nuget_unwrap_char
[DllImport("test", CallingConvention = CallingConvention.Cdecl, EntryPoint = "nuget_unwrap_enum_ordinal")]
private static extern int nuget_unwrap_enum_ordinal(IntPtr handle);

internal static int UnwrapEnumOrdinal(IntPtr handle)
{
    try { return nuget_unwrap_enum_ordinal(handle); }
    finally { Native_dispose(handle); }
}

// Factories, one statically written line per enum (root and nested, ADR-176 spelling)
[typeof(global::TestLibrary.Cat.Mood)] = static handle => (global::TestLibrary.Cat.Mood)UnwrapEnumOrdinal(handle),
```

What the consumer then sees (unchanged signatures, reads that now succeed):

```csharp
using var tracker = new CatMoodTracker();
Mood now = tracker.Temper.Value;                   // KotlinStateFlow<Mood>
Mood? maybe = tracker.MaybeTemper.Value;           // KotlinStateFlow<Mood?>, null stays null (IntPtr.Zero short-circuit)
await foreach (Mood m in faults.MoodStream()) { }  // KotlinFlow<Mood>
Mood boxed = CrateKt.MoodCrate().Value;            // Box<Mood>.Value, ADR-147
```

Why this and not the per-member delegate: it is the only option that covers Finding 6 (`Box<Mood>.Value`, and the legacy `fun <T>` read through `Materialize<T>`), it needs no Kotlin shim change (the handle already holds the enum object, Finding 4), `Mood?` falls out of `Materialize<T>`'s existing `Nullable.GetUnderlyingType` key (Finding 9), and every other element kind at an erased position already goes this way (sealed base, value class, interface backing class). The ADR-123/151 per-member `read:` seam exists for element kinds that are not a single closed `Type` key (a collection, `byte[]`, an interface needing a C# token probe); a bare enum is a closed type key. Price: 5 production files, about 30 lines.

Rejected, each priced:

- **`typeof(T).IsEnum` branch inside `FromHandle<T>`** (the item's literal wording): same runtime export, but the conversion must be `(T)Enum.ToObject(key, ordinal)` because `(T)(object)ordinal` throws at `T = Mood?` (Finding 9). Works under JIT; ILC was silent but not run under AOT. Covers `FromHandle<T>` callers but not a direct `Materialize<T>` caller (the legacy `fun <T>` return, which ADR-171 `:345-350` says reads through `Materialize<T>`; inferred, not spiked), so it fixes fewer sites than the table for the same code. 2 production files plus the runtime export.
- **Per-member `read:` delegate with Kotlin-side ordinal projection** (ADR-097's wire on ADR-123's seam: `NugetHandles.retain(value.ordinal as Any)` in `itemBoxExpr` and the two value bodies, `read: static h => (Mood)NugetMarshal.FromHandle<int>(h)` in C#): no new runtime export, but touches `FlowExports.kt` (4 bodies), `ForwardLegacyRouteCollections.kt` (a new `Enum(nullable)` shape), `CirClassTranslator.kt` (property and method sites), `CirFunctionTranslator.kt:800` (top-level and suspend-`StateFlow`), and leaves `Box<Mood>.Value` and `fun <T>` broken, so the table entry would still be needed later. Two wires for one element kind. About 6 files, strictly less coverage.

## ADR-094 amendment text (proposed, for the documenter)

> **Amendment (2026-09-29): enums register too.** An exported enum class registers under its own `typeof` key, root or nested, and constructs as `(E)UnwrapEnumOrdinal(handle)`, where `UnwrapEnumOrdinal` calls the fixed-ABI `nuget_unwrap_enum_ordinal` (`(asStableRef<Any>().get() as Enum<*>).ordinal`) and disposes the handle. This closes the erased read of an enum element on `Flow<E>`, `StateFlow<E>`, `StateFlow<E?>` and an ADR-147 generic class instantiated at `E`, all of which retain the Kotlin enum object and read through `FromHandle<T>`/`Materialize<T>`. The collection-component route is unchanged: ADR-097 projects ordinals on the Kotlin side and never reaches this entry. The sentence "Enums, objects ... register nothing" in `factoryEntries`' KDoc loses "Enums".

ADR-123 `docs/adr/123-collection-elements-on-the-flow-routes.md:315-318` ("A bare enum element ... stays open") and ADR-161's fault-trigger rationale need a one-line pointer to the amendment. ADR-071's enum-setter deferral is untouched.

## Files an implementation touches

- `nuget-runtime/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/runtime/NugetRuntime.kt` (new `nuget_unwrap_enum_ordinal`, after `:141`)
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/ForwardAbiContract.kt` (`:665-671` list, add the name)
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirModel.kt` (`CirFactoryEntry` at `:563-577`, a `viaEnumOrdinal` flag)
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirTranslator.kt` (`factoryEntries` `:1054-1116`: a `CirEnum` arm in `own`, KDoc `:1052-1053`; enums nested in classes/interfaces/objects/sealed arms ride the existing `walk`)
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirMarshalRenderer.kt` (extern + `UnwrapEnumOrdinal` near `:47-49`, render arm at `:119-122`)
- Tier 1: `nuget-processor/src/test/.../tier1/Tier1ReflectionFreeDispatchTest.kt:129` inverts (`assertFalse(cs.contains("[typeof(global::Tier1.Mood)]"))` becomes an `assertContains` of the new line), plus a nested-enum line
- Fixtures: `test-library/.../cat/CatMoodTracker.kt` (or a new sample) gains `StateFlow<Mood>`, `StateFlow<Mood?>`; a `Flow<Mood?>` method; an ADR-147 generic class instantiated at an enum if none exists (check `test-library` for an existing `Crate<T>`/`Box<T>` fixture first); for the fold, `StateFlow<V>` / `Flow<V?>` over an existing value class
- `test-library/.../cat/CallbackFaults.kt:47-52,212-220` and `IntegrationTests/CallbackFaultTests.cs:242-270` (retarget or invert, what-question 2)
- `IntegrationTests/` new `FlowEnumElementTests.cs`; `LeakTests/LiveHandleTests.cs` new row
- Docs: `docs/topics/supported-features.md` (a `StateFlow`/`Flow` enum element row near `:153-162`), `docs/topics/coroutines-and-flow.md`, `docs/backlog/fromhandle-no-enum-branch.md` (delete), ROADMAP line 74 (delete)

## Sample test

```csharp
public class FlowEnumElementTests
{
    [Fact]
    public void StateFlowOfEnum_Value_ReadsTheCurrentMood_NonZeroOrdinal()
    {
        using var tracker = new CatMoodTracker();
        tracker.Sulk();                                    // Kotlin sets the flow to GRUMPY (ordinal 2)
        Assert.Equal(Mood.Grumpy, tracker.Temper.Value);
    }

    [Fact]
    public void StateFlowOfNullableEnum_Value_NullThenValue()
    {
        using var tracker = new CatMoodTracker();
        Assert.Null(tracker.MaybeTemper.Value);
        tracker.Sulk();
        Assert.Equal(Mood.Grumpy, tracker.MaybeTemper.Value);
    }

    [Fact]
    public async Task FlowOfEnum_AwaitForeach_YieldsEveryMoodInOrder()
    {
        using var faults = new CallbackFaults();
        var seen = new List<Mood>();
        await foreach (Mood mood in faults.MoodStream()) seen.Add(mood);
        Assert.Equal(new[] { Mood.Happy, Mood.Grumpy }, seen);   // the ADR-161 fixture's own emissions
    }

    [Fact]
    public void GenericClassAtEnum_Value_Materialises()
    {
        using var crate = CrateKt.MoodCrate();                   // Box<Mood>(GRUMPY) on the Kotlin side
        Assert.Equal(Mood.Grumpy, crate.Value);
    }
}
```

Member names `Temper`, `MaybeTemper`, `Sulk()`, `MoodSwings()`, `CrateKt.MoodCrate()` are proposed fixture members, not existing ones; `MoodStream()` exists. Each asserts a non-zero ordinal, so a projection that always yields `0` cannot pass (ADR-097's rule).

LeakTests row (new, beside Row 8d in `LeakTests/LiveHandleTests.cs:1236-1256`): the factory must dispose the enum's StableRef; a factory that forgets `Native_dispose` leaks exactly one handle per emission and per `.Value` read, and nothing else measures that.

```csharp
// Row 8x. ADR-094 amendment: an enum element on the Flow and StateFlow routes mints one StableRef
// per emission and per `.Value` read, released by the `Factories` enum entry after it reads the
// ordinal. A factory that forgets the release leaks one handle per read.
[Fact]
public async Task EnumFlowElement_EnumerationAndValueRead_ReturnsToBaseline()
{
    await AssertNoLeakAsync(
        async () =>
        {
            using var tracker = new CatMoodTracker();
            Assert.Equal(Mood.Calm, tracker.Temper.Value);
            await foreach (Mood _ in tracker.MoodSwings()) { }
        },
        iterations: 200);
}
```

Tier 1 cell: the inverted `Tier1ReflectionFreeDispatchTest` assertion, `assertContains(cs, "[typeof(global::Tier1.Mood)] = static handle => (global::Tier1.Mood)UnwrapEnumOrdinal(handle),")`, plus a nested enum keyed `Outer.Inner`.

## Deferred scope

- `MutableStateFlow<Mood>` settable `.Value` (ADR-071's enum deferral; would be a `value: Int` set shim with `Mood.entries[value]` and `(int)v` in C#). Stays read-only. What-question 3.
- Erased WRITE of an enum (`new Box<Mood>(Mood.Calm)`, `Echo<Mood>(...)`): needs a per-enum Kotlin export `entries[ordinal]` and a `Boxers` twin; `Wrap<T>` has no enum branch (ADR-097 `docs/adr/097-enum-collection-components.md`, inferred for `Box<T>`'s constructor, not spiked).
- Dependency-module enums (Finding 13).

## Open what-questions

1. **Fold the value-class flow element as a regression pin only?** Recommendation: yes, fold: add `StateFlow<V>` / `Flow<V?>` fixture members and two C# tests, zero production code expected (Finding 3). If the test goes red, that is a real bug on the same path and belongs here. Sealed base needs nothing (already tested). Human decision: pending.
2. **What replaces `CallbackFaults.moodStream` as the ADR-161 part A materialisation-failure trigger?** After this fix no admitted element kind is known to fail materialisation. Recommendation: invert the existing cell into the `Flow<Mood>` round trip above, and keep part A's containment covered by a deterministic fault injected at the read seam if one exists (for example an element whose `NugetUnbox`/`FromHandle` throws); if none exists, record in ADR-161 that part A's materialisation arm has no live trigger. Human decision: pending.
3. **Fold the `MutableStateFlow<Mood>` setter?** Recommendation: no, different seam (ADR-071 write path), a new small item only if the human wants it. Human decision: pending.
4. **Rewrite `docs/backlog/fromhandle-no-enum-branch.md`?** It is deleted when the item closes; until then its `List<Mood>`, sealed-base and value-class claims are wrong. Recommendation: leave it for the closing commit.
