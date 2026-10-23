# An open exported generic instantiation nested two or more levels deep, read through a getter or any non-result position, has no factory

- ROADMAP: line text as of 2026-10-22 (`ROADMAP.md:58`): "An open exported generic instantiation nested two or more levels deep, read through a property getter or any position outside a member's result before the consumer holds one, has no factory ([ADR-216](docs/adr/216-own-type-parameter-in-result-and-generic-class.md))"
- Researched: 2026-10-22, about 20 minutes, reading only (no Gradle, no dotnet run; the brief forbade Gradle in the checkout)
- Restatement: forward (Kotlin declares, C# consumes). A C# consumer that obtains a value whose type nests an open exported generic instantiation (`Basket<Basket<T>>`, `List<Basket<T>>`, `Basket<Pickup<T>>`) through a property getter, a callback argument, a `Flow` element, a returned lambda, a `suspend` result, a `Result` payload or an extension property, and then reads the inner value, gets a working wrapper instead of `NotSupportedException: No generated factory materialises Basket<int> from a Kotlin handle`.
- Verdict: fix. Recommended as a second amendment to ADR-216 (same mechanism, more fill sites); ADR-223 not needed.

## Findings

1. **The only fill site is a member's result projection.** `fillingOpenSlots` (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardCirPlanProjection.kt:1879-1896`) prepends `NugetFactory<X>.Create ??= static handle => new X(handle, out _);` for each handle `result.openSlotHandles()` returns. It is called once, at the end of `ForwardCallablePlan.resultProjection` (`:1879`). **Verified by reading.** Nothing in `ForwardCirPropertyProjection.kt` (getter body `:280-302`, setter body `:304+`) or any callback, flow or constructor projection calls it. **Verified by reading** (grep for `fillingOpenSlots|openSlotHandles` finds only `ForwardCirPlanProjection.kt:1879,1889,1900,1906`).
2. **The fill silently drops on a direct body.** `if (open.isEmpty() || !hasCustomBody) return this` (`ForwardCirPlanProjection.kt:1890`): a member whose result needs fills but renders an expression-bodied direct call gets none, with no diagnostic. **Verified by reading.** Whether any admitted shape reaches it today is **inferred** unknown.
3. **`openSlotHandles` stops at every composite but `Nullable` and `Collection`.** `Callback`, `AsyncCallback`, `FlowSource`, `ReturnedLambda`, `SpecializedProtocol` and `ValueClass` all return `emptyList()` (`ForwardCirPlanProjection.kt:1898-1914`). So even a member's own result `Flow<Basket<Basket<T>>>`, `() -> Basket<Basket<T>>` or a `suspend` result (`AsyncCallback`) carries no fill. **Verified by reading.**
4. **A sealed nested instantiation is never collected.** The classifier collects `openArguments` only for `nested is BridgeType.ObjectHandle && !nested.viaDiscriminator` (`forward/ForwardBridgeTypeClassifier.kt:794-803`), and the `Collection` branch of `openSlotHandles` repeats the `!handle.viaDiscriminator` filter (`ForwardCirPlanProjection.kt:1903`). A generic sealed `Pickup<T>` nested in `Basket<Pickup<T>>` or `List<Pickup<T>>` relies only on ADR-199's static constructor (`cir/CirSealedRenderer.kt:137-147`), which runs once the consumer touched `Pickup<int>`. **Verified by reading** that the slot is unfilled; the runtime red is **inferred** (not run).
5. **An abstract generic class has no slot at all.** The ADR-216 static constructor is gated `cls.typeParameters.isNotEmpty() && !cls.isAbstract` (`cir/CirClassRenderer.kt:313-323`). The classifier already records the backing spelling as `constructType` (`ForwardBridgeTypeClassifier.kt:813-816`, `:552`). So `Basket<Shelf<T>>.Item`, or `Wrap(shelf).Item` for a consumer-held `Shelf<int>`, has no factory even after the consumer holds one. **Verified by reading**; runtime red **inferred**.
6. **The read that throws.** `NugetMarshal.Materialize<T>` tries `Factories`, then `NugetFactory<T>.Create`, then throws `NotSupportedException` (`cir/CirMarshalRenderer.kt:201-231`). The slot class is `internal static class NugetFactory<T> { internal static Func<NugetKotlinHandle, object>? Create; }` (`:770-775`), emitted when `includesFactorySlot` (`cir/CirTranslator.kt:1812`). **Verified by reading.**
7. **Why a getter is red and the result was green.** The result case (`DoubleBasket(1).Item`) is green because the member fills before the read (ADR-216 amendment, verified there by `scripts/verify.sh`). On `class Hamper<T> { val nested: Basket<Basket<T>> }` the getter constructs the outer `Basket<Basket<int>>` directly, so `Basket<Basket<int>>`'s static constructor fills only its own slot, and `.Item` asks for `NugetFactory<Basket<int>>`, which nothing filled. **Inferred** from findings 1 and 6 (not run).
8. **The induction that makes member-entry fills sufficient.** Every C# value of a generic class reaching the consumer arrives one of three ways: (a) a closed instantiation, which mints a `Factories` line (`ForwardBridgeTypeClassifier.kt:817-823`); (b) a value the consumer handed in, whose wrapper construction already ran its type's static constructor (`CirClassRenderer.kt:315-323`); (c) an open instantiation nested in a generated member's signature, which that member fills. If every generated body that can bring a Kotlin value to C# fills (c) for its whole signature, every `Materialize<X>` finds a slot. **Inferred** (reasoned, not proved; the xunit rows below test each position). One residue: a Kotlin body that fabricates a non-null `T` with no `T` input (an unchecked `null as T` style cast) yields an `X` the consumer never touched. No reflection-free C# can spell a factory for an unknown `T`; ADR-216 already rejected `RuntimeHelpers.RunClassConstructor`. That residue is not a nesting gap and is not in this item.
9. **C#-implemented interface slots never carry a class handle.** `ForwardInterfaceBridgePlanner.bridgeType` admits only the own `T`, `Throwable`, enums, `Unit`, `String` and five primitives (`forward/ForwardInterfaceBridgePlanner.kt:498-575`). So "Kotlin calls a C# implementation with a `Basket<Basket<T>>`" cannot arise; it is not a fill position. **Verified by reading.** (That interface slots refuse every exported class handle, generic or not, is a separate, wider observation, reported to the main thread rather than designed here.)
10. **`Basket<List<T>>` is not a shape.** `isErasedTypeArgument` refuses a `Collection` and a generic `ValueClass` argument (`forward/ForwardGenericSealed.kt:183-197`). **Verified by reading.** Nesting reaches the fill rule only through generic classes, generic sealed types and collections around them.
11. **Fixture today.** `Basket<T>` with `rebasket(): Basket<Basket<T>>` (`test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/membergeneric/Groomers.kt:143-155`), generic sealed `Pickup<out T>` (`:220-226`). Existing C# tests instantiate `Basket<int>` (18 times), `Basket<string>`, `Basket<Tabby>`, `Basket<Cat>`, `Basket<double>`, `Basket<long>`. **Verified by grep.** These instantiations are polluted for a red-first test: any row reusing one can go green because another test's static constructor filled the slot.

## Recommendation

**Fill at the entry of every generated member body whose signature mentions an open exported generic instantiation, at any depth, through every composite.** It is the ADR-216 amendment's part 2 extended from "a member's result" to "every generated C# member body", plus two missing slot kinds.

1. **One collector, on the model.** Move `openSlotHandles` to `ForwardMarshallingModel.kt` as `BridgeType.openSlots(): List<OpenSlot>` (an `OpenSlot(csharpType, factory)` pair) recursing through every variant: `ObjectHandle` (itself if `open`, plus `openArguments`), `Nullable`, `Collection` components, `Callback` / `AsyncCallback` parameters and result, `FlowSource.element`, `ReturnedLambda.typeArguments`, `SpecializedProtocol.sealedHandle`. Every other variant lists `emptyList()` explicitly (no `else`, per CLAUDE.md). The factory spelling is `new ${constructType ?: csharpType}(handle, out _)` for a class (an abstract one constructs its backing) and `${csharpType}.FromHandle(handle)` for a sealed handle (the `NugetKotlinHandle` overload at `CirSealedRenderer.kt:161`, the same expression ADR-199's static constructor uses at `:144`).
2. **The classifier stops excluding sealed.** `ForwardBridgeTypeClassifier.kt:799` collects a `viaDiscriminator` nested handle too, so `Basket<Pickup<T>>` and `Basket<Basket<Pickup<T>>>` record `Pickup<T>`.
3. **One renderer helper, `openSlotFills(types: List<BridgeType>): String`**, producing the `??=` lines, deduplicated by C# type. Called by:
   - `ForwardCallablePlan.resultProjection` over the result **and every parameter type** (a callback parameter's arguments, an input `Flow`'s element). Every owner: class member, object, companion, sealed arm, top-level, extension, constructor, interface backing. When fills are non-empty the projection takes the custom-body path (the existing `forceCustomBody` / `needsCustomParams` switch), and `fillingOpenSlots`' `!hasCustomBody` early return becomes a `check(...)` with a message, so the silent drop (finding 2) cannot recur.
   - The `TryX` twin body (same call, `tryNames` set): it already goes through `resultProjection`, so it inherits the fill. **Inferred**; a Tier 1 cell pins it.
   - `ForwardCirPropertyProjection.getterBody` and `setterBody` over `plan.type` (a setter: a lambda-typed `var` whose payload nests one), for class, static, extension and enum-member properties. Getters already have custom bodies (`ForwardCirPropertyProjection.kt:166,179`).
4. **Slots on every generic class.** Drop `!cls.isAbstract` at `CirClassRenderer.kt:315`; an abstract class's static constructor stores `new ${cls.name}$typeParameters.Backing(handle, out _)` (the backing name from `abstractBackingName()`). A backing instance's construction runs its base type's static constructor first. **Inferred** (ECMA-335 precise type initialisation: an explicit static constructor makes the type non-`beforefieldinit`, and the base instance constructor is a member of the base type); the `GenericAbstractBackingTests` row below proves it.

Ownership is unchanged: a fill stores a factory, mints no handle and runs no P/Invoke. The handle the factory wraps is the one `Materialize` already owns (`CirMarshalRenderer.kt:207-229`). No Kotlin emitter, ABI or runtime-package change.

Alternatives rejected:

- **Fill in the owner's static constructor** (class `Hamper<T>`'s `static Hamper()` fills `NugetFactory<Basket<T>>` for every open slot its members mention). Cheaper (once per closed owner), but it covers only a class `T`; a member's own `T`, a top-level `fun <T>` and an extension `T` still need the member fill, so it adds a second mechanism without removing the first.
- **`RuntimeHelpers.RunClassConstructor(typeof(T).TypeHandle)` in each generic class's static constructor.** Reflection, unverified under NativeAOT; ADR-216 already rejected it.
- **A static-abstract `INugetMaterializable<TSelf>` constraint.** `T` is often `int` or `string`, which cannot implement it.
- **A new ADR-223.** No new mapping and no new consumer surface; the decision is ADR-216's amendment applied to more positions. Recommend amending ADR-216 (and noting ADR-199's sealed slot now also fills at the member).

## Consumer API

No new surface. The values already exist; they now read.

```csharp
using var hamper = new Hamper<short>(7);
using Basket<Basket<short>> nested = hamper.Nested;           // val nested: Basket<Basket<T>>
using Basket<short> inner = nested.Item;                     // was NotSupportedException
Assert.Equal((short)7, inner.Item);

IReadOnlyList<Basket<sbyte>> list = new Hamper<sbyte>(3).Baskets;   // val baskets: List<Basket<T>>
groomer.Visit(true, b => Assert.True(b.Item.Item));          // fun <T> visit(item: T, block: (Basket<Basket<T>>) -> Unit)
await foreach (var b in groomer.Stream('k')) ...             // fun <T> stream(item: T): Flow<Basket<Basket<T>>>
Func<Basket<Basket<float>>> later = groomer.Later(1.5f);     // fun <T> later(item: T): () -> Basket<Basket<T>>
Basket<Basket<ushort>> fetched = await groomer.Fetch((ushort)4);   // suspend fun <T> fetch(item: T): Basket<Basket<T>>
bool ok = groomer.TryNest(Mood.Happy, out var value, out _); // fun <T> nest(item: T): Result<Basket<Basket<T>>>
Pickup<uint> p = new Hamper<uint>(9).Picked.Item;            // val picked: Basket<Pickup<T>> (sealed slot)
Shelf<ulong> s = groomer.WrapShelf(shelf).Item;              // abstract generic class slot
```

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardMarshallingModel.kt` (the collector, `openArguments` KDoc)
- `.../forward/ForwardBridgeTypeClassifier.kt:794-803` (collect sealed nested handles)
- `.../forward/ForwardCirPlanProjection.kt:1443+, 1879-1914` (fill over parameters too, force custom body, `check` instead of silent drop)
- `.../forward/ForwardCirPropertyProjection.kt:280-330` (getter and setter fills)
- `.../cir/CirClassRenderer.kt:313-323` (abstract class slot via its backing)
- Fixture: `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/membergeneric/Groomers.kt` (new `class Hamper<T>(val item: T)`: `nested`, `deep: Basket<Basket<Basket<T>>>`, `baskets: List<Basket<T>>`, `keyed: Map<String, Basket<T>>`, `maybe: Basket<Basket<T>>?`, `picked: Basket<Pickup<T>>`, `fun visit(block: (Basket<Basket<T>>) -> Unit)`; on `Groomer`: `visit`, `stream`, `later`, `fetch`, `nest` (`Result`), `wrapShelf`; top-level extension `val <T> Basket<T>.doubled: Basket<Basket<T>>`; an `abstract class Shelf<T>` with one concrete subclass)
- Tier 1: `nuget-processor/src/test/.../Tier1OwnTypeParameterInGenericClassTest.kt` or a new `Tier1OpenSlotFillTest.kt`
- xunit: `IntegrationTests/MemberGenericMethodTests.cs` (or a new `OpenSlotFillTests.cs`)
- Leak: `LeakTests/LiveHandleTests.cs` (append under an `// ADR-216` banner after `:5899`)
- Docs: `docs/adr/216-own-type-parameter-in-result-and-generic-class.md` (second amendment; replace the "Residual gap" paragraph), `docs/topics/generics.md`, `docs/topics/supported-features.md`, `ROADMAP.md:58` deleted

## Sample test

Red-first discipline: `NugetFactory<T>` slots are process-global and a static constructor fills its own. **Each row uses an instantiation no other test in the same assembly constructs** (finding 11 lists the polluted ones). Suggested: `short`, `sbyte`, `bool`, `char`, `float`, `ushort`, `Mood` (enum), `uint`, `ulong`. **Inferred** that each crosses the ADR-197 box route; replace any that does not with an unused exported class.

```csharp
// ADR-216 (second amendment): an open nested instantiation read before the consumer holds one.
[Fact]
public void OpenSlot_ThroughGetter_ReadsTheInnerBasket()
{
    using var hamper = new Hamper<short>(7);
    using var nested = hamper.Nested;
    using var inner = nested.Item;
    Assert.Equal((short)7, inner.Item);
}

[Fact]
public void OpenSlot_ThreeLevelsThroughGetter_ReadsEveryLevel()
{
    using var hamper = new Hamper<char>('d');
    using var deep = hamper.Deep;
    using var mid = deep.Item;
    using var inner = mid.Item;
    Assert.Equal('d', inner.Item);
}

[Fact]
public void OpenSlot_ListElementThroughGetter_Reads()
{
    using var hamper = new Hamper<sbyte>(3);
    var baskets = hamper.Baskets;
    using var first = baskets[0];
    Assert.Equal((sbyte)3, first.Item);
}

[Fact]
public void OpenSlot_CallbackArgument_ReadsTheInnerBasket()
{
    bool seen = false;
    new Groomer().Visit(true, outer => { using var inner = outer.Item; seen = inner.Item; });
    Assert.True(seen);
}

[Fact]
public async Task OpenSlot_FlowElement_ReadsTheInnerBasket()
{
    await foreach (var outer in new Groomer().Stream(1.5f))
    {
        using var inner = outer.Item;
        Assert.Equal(1.5f, inner.Item);
    }
}

[Fact]
public void OpenSlot_SealedNestedInGetter_ReadsTheArm()
{
    using var hamper = new Hamper<uint>(9);
    using var picked = hamper.Picked;
    var arm = Assert.IsType<Pickup<uint>.Fluffed<uint>>(picked.Item);
    Assert.Equal(9u, arm.Pet);
}
```

Plus rows for `Later` (returned lambda, `ushort`), `Fetch` (suspend), `TryNest` and `Nest` (`Result`, `Mood`), the extension property `Doubled`, `Keyed` and `Maybe`, and `WrapShelf` (abstract, `ulong`).

Leak row, `LeakTests/LiveHandleTests.cs`, with a type unused in that assembly:

```csharp
[Fact]
public void OpenSlot_NestedThroughGetter_ReturnsToBaseline()
{
    long baseline = NugetMarshal.LiveHandles;
    using (var hamper = new Hamper<short>(7))
    using (var nested = hamper.Nested)
    using (var inner = nested.Item)
        Assert.Equal((short)7, inner.Item);
    Assert.Equal(baseline, NugetMarshal.LiveHandles);
}
```

Tier 1 cells: the generated getter of `Hamper.nested` starts with `NugetFactory<global::...Basket<T>>.Create ??=`; `picked` fills `Pickup<T>.FromHandle(handle)`; `visit`, `stream`, `later`, `fetch`, `nest` and `TryNest` each carry the fill; `Shelf<T>` renders a static constructor constructing `Shelf<T>.Backing`; the consumer compiles (`Tier1CSharpCompile`).

## Deferred scope

None.

## Open what-questions

1. ADR-216 second amendment or ADR-223? Recommendation: amendment (finding 8; same mechanism, no new surface).
2. Should class-`T` members also get the owner static-constructor fill as a belt-and-braces? Recommendation: no; one mechanism.
3. Does any admitted shape hit the silent `!hasCustomBody` drop today (finding 2)? Recommendation: replace it with a `check` and let the Tier 1 suite answer.
4. The generic sealed arm static constructor (`CirSealedRenderer.kt:263-270`) and base (`:137-147`) stay; the member fill is additive. Confirm the arm's construct spelling (`Pickup<T>.Fluffed<T>`) is never what a nested slot needs: the read is always typed at the base (`Pickup<T>`). Inferred yes.
5. Interface slots refusing every class handle (finding 9) is out of this item's neighbourhood but is a gap under RULES rule 1; the main thread decides where it is designed.

### Inferred claims (not run)

- The getter, callback, flow, returned-lambda, suspend, `Result`, sealed-nested and abstract-class reads are red today (findings 3-5, 7).
- A backing instance's construction runs its abstract base's static constructor (recommendation 4).
- The `TryX` twin inherits the fill through `resultProjection`.
- The suggested primitive instantiations cross the ADR-197 box route.
- The induction in finding 8 is complete.
