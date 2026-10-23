# A function's own `T` inside a generic value class binds under `?` and inside a `List`, `Set` or `Map`

- ROADMAP: line 59 as of 2026-10-22: "A function's own `T` inside a generic value class under `?` (`Sticker<T>?`) or inside a `List`, `Set` or `Map` (`List<Sticker<T>>`) is skipped, named: `null` and `Sticker(null)` would share the null pointer, and the collection element needs ADR-212's element box plus the re-wrap ([ADR-220](docs/adr/220-generic-value-class-record-struct.md))"
- Researched: 2026-10-22, about 10 of a shared 35 minute budget (researched together with B1, B3, B4)
- Restatement: forward. `fun <T> maybeStick(v: T): Sticker<T>?`, `fun <T> f(s: Sticker<T>?)`, `fun <T> stickAll(values: List<T>): List<Sticker<T>>`, `Set<Sticker<T>>`, `Map<String, Sticker<T>>`, `List<Sticker<T>?>` bind; C# gets `Sticker<T>?` (a real `Nullable<Sticker<T>>`) and `IReadOnlyList<Sticker<T>>` etc., and `null` stays distinct from `new Sticker<string?>(null)` in both directions. Adjacent, folded in: the same `null` / `V(null)` collision for every value class whose underlying is itself nullable (`Sticker<String?>?`, non-generic `Nick(val name: String?)` at `Nick?` and at `List<Nick?>`, which **binds today and is lossy**), and a star-projected `Sticker<*>` parameter.
- Verdict: fix. Own ADR, reserved **ADR-226** (Proposed, not drafted here): there is a genuine wire choice (has-value fan-out vs box handle). **Depends on B4** (ADR-228) for the box handle.

## Findings

1. **The skip site.** `forwardMemberGenericRefusal` (`forward/ForwardMemberTypeParameters.kt:110-237`): `isOwnInsideGenericValueClass()` (`:164-184`) returns `false` for a nullable outer (`if (isMarkedNullable) return false`, `:165`), and it is deliberately not a component of `carriesOwn()` (`:193-199`), so `Sticker<T>?` and `List<Sticker<T>>` fall to "its type parameter is nested in ..." (`:215-219`, `:221-229`). `isOwnParameterCarrierClass()` (`:291-298`) explicitly excludes value classes. **Verified** by reading. Pinned skips: `Tier1GenericValueClassTest.kt:63` (`maybeStick`), `:67` (`stickAll`), `:158`.
2. **The planner only admits an own-`T` underlying under a non-null outer** (`ForwardCallablePlanner.kt:4564-4570`, the KDoc says so). **Verified** by reading.
3. **The collision is real and already exists for non-generic nullable underlyings.** A collection component `Nullable(ValueClass)` whose underlying is nullable (`List<Nick?>`):
   - passes `isBridgeableComponent` (`ForwardCallablePlanner.kt:5132-5135`: the value-class arm checks the underlying, the nullable arm only refuses a doubly-nullable),
   - C# writes `x?.Name` (`forward/ForwardCirCollectionComponents.kt:259`, `else -> "$access?.$property"`), so `null` and `new Nick(null)` both box as the null pointer,
   - Kotlin reads `?.let { Nick(it) }` (`ForwardKotlinPlanEmitter.kt:688-700`), so both arrive as `null`.
   **Verified** by reading the generator; that a real consumer observes `Nick(null)` turning into `null` is **inferred** (not run). This is a silent wrong output on a shipped shape.
4. **Non-generic precedent for nullables with no in-band null.** ADR-079's has-value fan-out (`isHasValueFanOutUnderlying`, `ForwardCallablePlanner.kt:4578-4579`: primitive and enum underlyings only) adds an adjacent BOOLEAN at an input and a BOOLEAN result plus `valueOut` at a return. It cannot carry a nullable primitive underlying (`value class MaybeCount(val n: Int?)` at `MaybeCount?` would need two has-value flags) and cannot carry a collection element at all (one slot). **Verified** by reading.
5. **The non-null collection element almost works already.** C# reads `new Sticker<T>(NugetMarshal.FromHandle<T>(h))` (`ForwardCirCollectionComponents.kt:150-165`, wire type from `componentWireCsharpType`, `:177-194`, which spells the underlying `T`) and writes `x.Value` through `underlyingCs` with the default guard (`:236-249`). Kotlin's `componentLowering` value-class arm (`ForwardKotlinPlanEmitter.kt:663-689`) sends a `TypeParameter` underlying to `elementKotlinTypeName(underlying)`, which `error()`s for a `TypeParameter` (ADR-212: "`elementKotlinTypeName` keeps its `error()` for `TypeParameter`"). So the Kotlin read needs one arm: `pkg.Sticker(typeParameterElementCast(it, underlying, nullable))`. The component gate admits it (`isBridgeableComponent`'s value-class arm recurses to `TypeParameter -> true`, `:5084-5088`). **Verified** by reading.
6. **The box handle distinguishes the two.** B4 finding 7 (Kotlin/Native spike): `Sticker<Any?>(null)` is a non-null boxed object whose `.value` is `null`. B4 finding 8 (C# spike, JIT and NativeAOT): `Wrap<Sticker<string>?>(null)` returns `0`, a present struct goes through the struct's box, and the `NugetFactory<Sticker<T>?>` slot reads it back. `(Sticker<string?>?)null == new Sticker<string?>(null)` is `False` in C#. **Verified**.
7. **Adjacent: a nullable-underlying value class does not bind at a non-null ordinary position either.** `inputSkipReason`'s value-class arm (`ForwardCallablePlanner.kt:4733-4734`) and `valueClassResultShape` (`:4543`) both require `isOrdinaryValueClassUnderlying()` (`:4559-4561`: String, primitive, enum, handle, own `T`), which excludes `Nullable(String)` and `Nullable(Primitive)`. So `fun take(n: Nick)` and `fun give(): Nick` are `VALUE_CLASS` skips although the struct is declared (`docs/topics/value-classes.md:32` lists the nullable underlyings as declared). The closed `Sticker<String?>` (substituted underlying `Nullable(String)`) is the same skip. **Verified** by reading; not run.
8. **Adjacent: star projection.** `genericValueClass()` refuses any non-invariant argument, "a use-site projection of a generic value class has no C# spelling" (`ForwardBridgeTypeClassifier.kt:1003-1012`), pinned by `Tier1GenericValueClassTest.kt:66` (`starred(sticker: Sticker<*>)`). **Verified** by reading.

## Recommendation

### Rule: a value class whose underlying can itself be null crosses `V?` as B4's box handle

When the outer is nullable **and** the (substituted) underlying admits null (`Nullable(TypeParameter)`, i.e. an unconstrained own `T` or `Sticker<T?>`; `Nullable(String)`; `Nullable(Primitive)`), the slot is one POINTER: `IntPtr.Zero` is `null`, otherwise a handle to the boxed Kotlin value class minted by B4's `<prefix>_box` (C#-to-Kotlin) or returned by Kotlin (`NugetHandles.retain(v)`) and read through `<prefix>_unbox`. Kotlin reads `x?.asStableRef<Any>()?.get() as pkg.Sticker<T>?` (unchecked cast through the trampoline's `T` or the erased spelling), so `init` already ran when the box was minted.

Everywhere else nothing changes:
- `T : Any` (non-null `TypeParameter` underlying): `Sticker<T>?` rides the null pointer in-band, exactly like ADR-220's closed `Sticker<Cat>?`. Admit it by letting `isOwnInsideGenericValueClass()` accept a nullable outer when the underlying is non-null.
- A primitive or enum underlying keeps ADR-079's fan-out; a `String` or handle underlying keeps its own null pointer.

Positions: parameter, result, property getter and setter, constructor parameter, ADR-132 extension receiver, and the collection component (`List<Sticker<T>?>`, `List<Nick?>`, `Map<String, Sticker<T>?>` values; a nullable map **key** stays refused by `declinesNullableMapKey`). For the component, C# writes `NugetMarshal.Wrap<Sticker<T>?>(x)` (the B4 `INugetGenericValue` branch, or `Boxers` for Nick) instead of projecting `x?.Value`, and reads `NugetMarshal.FromHandle<Sticker<T>?>(h)` (the B4 slot); Kotlin reads `it as pkg.Sticker<T>?` and writes the element untouched (`componentNeedsProjection` false for this component).

This replaces today's lossy `List<Nick?>` wire (finding 3); the C# signature (`IReadOnlyList<Nick?>`) does not change, only both generated halves.

### Adjacent: a nullable underlying at a non-null outer

`isOrdinaryValueClassUnderlying()` admits `Nullable(String)` (the nullable UTF-8 pointer every `String?` parameter and result already uses) and `Nullable(Primitive)` (ADR-079's has-value pair as the underlying's own wire). With a non-null outer there is no collision: the only null is the underlying's. So `Nick`, `MaybeCount(val n: Int?)` and the closed `Sticker<String?>` / `Sticker<Int?>` bind at every ordinary position; their `V?` takes the box rule above.

### Non-null collection components

`carriesOwn()` admits `isOwnInsideGenericValueClass()` as a component (the same edit B4 needs for `Box<Sticker<T>>`), and `componentLowering` gains the `TypeParameter` / `Nullable(TypeParameter)` underlying arm `pkg.Sticker(<typeParameterElementCast>)` (finding 5). `List`, `Set`, `Map` (key or value) and their mutable kinds, and nesting (`List<List<Sticker<T>>>`) follow ADR-212's recursion. The element still projects to its underlying on the bare-`T` box, so no B4 box is minted per non-null element.

### Adjacent: a star-projected parameter

`fun starred(sticker: Sticker<*>): String` binds as `public static string Starred<TSticker>(Sticker<TSticker> sticker)`: the classifier turns a `*` (or `out X`) argument at a **parameter** into a synthesized method type parameter (named by `forwardCsharpMethodTypeParameterName`'s de-clash rule), and Kotlin passes `pkg.Sticker(<unbounded read>)`, a `Sticker<Any?>`, which is a `Sticker<*>`. An `out Cat` projection adds `where TSticker : Cat`'s C# spelling. A star-projected **result** is the open question below.

Alternatives rejected:
- ADR-079 has-value fan-out for the top-level positions: no element form, and a nullable primitive underlying would need a nested flag; two mechanisms where one suffices. (Cost of the chosen box: one Kotlin allocation and one handle per present value, the same as every erased position.)
- Keep refusing `Sticker<T>?` for an unconstrained `T`: a deferral.

## Files an implementation touches

- `nuget-processor/src/main/.../forward/ForwardMemberTypeParameters.kt` (`isOwnInsideGenericValueClass` nullable outer, `carriesOwn`)
- `nuget-processor/src/main/.../forward/ForwardCallablePlanner.kt` (`isOwnTypeParameterUnderlying` KDoc and nullable-outer admission, a "nullable-underlying `V?` is a box POINTER" input/result shape, `isBridgeableComponent`/`isWrappableComponent` stay)
- `nuget-processor/src/main/.../forward/ForwardPropertyPlanner.kt` (getter/setter shapes, `isReadableComponent` at `:1675`)
- `nuget-processor/src/main/.../forward/ForwardKotlinPlanEmitter.kt` (`componentLowering` arms at `:648-720`, the box-handle parameter read and result retain)
- `nuget-processor/src/main/.../forward/ForwardCirCollectionComponents.kt` (`:150-165`, `:177-194`, `:236-262`, `componentNeedsProjection` at `:329`)
- `nuget-processor/src/main/.../forward/ForwardCirPlanProjection.kt` (top-level `Wrap`/`FromHandle` for the box handle)
- `nuget-processor/src/main/.../forward/ForwardBridgeTypeClassifier.kt:1003-1012` (star at a parameter)
- `nuget-processor/src/main/.../forward/ForwardDiagnostic.kt:1425-1432` (GENERIC hint loses its last clause, together with B3 and B4)
- Tier 1: `Tier1GenericValueClassTest` (`maybeStick`, `stickAll`, `starred` flip), `Tier1ValueClassWithoutStructPositionsTest` (`Nick`), a new cell for `List<Nick?>` asserting the box wire
- Fixture `test-library/.../stickers/Stickers.kt` and a nullable-underlying value class in the clinic fixture; `IntegrationTests/GenericValueClassTests.cs`, `IntegrationTests/ValueClassCollectionTests.cs`; `LeakTests/LiveHandleTests.cs`
- Docs: `docs/topics/value-classes.md:75-78`, `:125-175` (Nullable section), `docs/topics/supported-features.md`, ADR-079 and ADR-220 cross-reference

## Sample test

Fixture:

```kotlin
fun <T> maybeStick(value: T, present: Boolean): Sticker<T>? = if (present) Sticker(value) else null
fun <T> isAbsent(sticker: Sticker<T>?): Boolean = sticker == null
fun <T : Any> firmMaybe(value: T?): Sticker<T>? = value?.let { Sticker(it) }
fun <T> stickAll(values: List<T>): List<Sticker<T>> = values.map { Sticker(it) }
fun <T> peelAll(stickers: Set<Sticker<T>>): List<T> = stickers.map { it.value }
fun <T> byName(stickers: Map<String, Sticker<T>>): Map<String, Sticker<T>> = stickers
fun <T> gaps(stickers: List<Sticker<T>?>): List<Sticker<T>?> = stickers
fun nicks(): List<Nick?> = listOf(null, Nick(null), Nick("Oreo"))
fun starred(sticker: Sticker<*>): String = "star ${sticker.value}"
```

```csharp
[Fact]
public void NullableSticker_OfAnUnconstrainedT_KeepsNullAndStickerOfNullApart()
{
    Assert.Null(Stickers.MaybeStick<string?>(null, present: false));
    Sticker<string?>? present = Stickers.MaybeStick<string?>(null, present: true);
    Assert.NotNull(present);
    Assert.Null(present!.Value.Value);
    Assert.True(Stickers.IsAbsent<string?>(null));
    Assert.False(Stickers.IsAbsent<string?>(new Sticker<string?>(null)));
}

[Fact]
public void NullableSticker_OfABoundT_RidesTheNullPointer()
{
    Assert.Null(Stickers.FirmMaybe<string>(null));
    Assert.Equal("Oreo", Stickers.FirmMaybe("Oreo")!.Value.Value);
}

[Fact]
public void OwnTypeParameter_InsideCollections_CrossesPerElement()
{
    Assert.Equal(new[] { new Sticker<int>(1), new Sticker<int>(2) }, Stickers.StickAll(new[] { 1, 2 }));
    Assert.Equal(new[] { "Oreo" }, Stickers.PeelAll(new HashSet<Sticker<string>> { new("Oreo") }));
    Assert.Equal(new Sticker<int>(9), Stickers.ByName(new Dictionary<string, Sticker<int>> { ["a"] = new(9) })["a"]);
}

[Fact]
public void NullableElement_KeepsNullAndStickerOfNullApart()
{
    var back = Stickers.Gaps(new Sticker<string?>?[] { null, new Sticker<string?>(null) });
    Assert.Null(back[0]);
    Assert.NotNull(back[1]);
}

[Fact]
public void NullableUnderlyingElement_WasLossy_AndNowRoundTrips()
{
    IReadOnlyList<Nick?> nicks = Clinic.Nicks();
    Assert.Null(nicks[0]);
    Assert.Equal(new Nick(null), nicks[1]);
    Assert.Equal("Oreo", nicks[2]!.Value.Name);
}

[Fact]
public void NullableUnderlying_AtANonNullOuter_BindsWithItsOwnNull()
{
    // fun nickOrAnon(nick: Nick): String = nick.name ?: "anon";  fun blankNick(): Nick = Nick(null)
    Assert.Equal("anon", Clinic.NickOrAnon(new Nick(null)));
    Assert.Equal("Oreo", Clinic.NickOrAnon(new Nick("Oreo")));
    Assert.Null(Clinic.BlankNick().Name);
}

[Fact]
public void StarProjectedParameter_TakesAnyInstantiation()
{
    Assert.Equal("star 3", Stickers.Starred(new Sticker<int>(3)));
    Assert.Equal("star Oreo", Stickers.Starred(new Sticker<string>("Oreo")));
}
```

Leak row:

```csharp
[Fact]
public void GenericValueClassNullableAndCollections_ReturnToBaseline()
{
    long before = NugetMarshal.LiveHandles;
    for (int i = 0; i < 50; i++)
    {
        _ = Stickers.MaybeStick<string?>(null, present: true);                  // box handle result
        _ = Stickers.IsAbsent<string?>(new Sticker<string?>(null));             // box handle parameter
        _ = Stickers.StickAll(new[] { i, i + 1 });                              // per-element T boxes
        _ = Stickers.Gaps(new Sticker<string?>?[] { null, new Sticker<string?>("x") });
    }
    Assert.Equal(before, NugetMarshal.LiveHandles);
}
```

## Deferred scope

None.

## Open what-questions

1. A star-projected or `out`-projected generic value class at a **result** (`fun any(): Sticker<*>`) has no type-safe C# spelling: C# has no existential, and `Sticker<object?>` would read the value through `FromHandle<object>`, which has no branch for a Kotlin-boxed primitive (`CirMarshalRenderer.kt:236-400`, **inferred** to throw `NotSupportedException`). Escalate: either a synthesized caller-chosen `T` (unchecked, `InvalidCastException` on mismatch) or `Sticker<object?>` with a new boxed-primitive read in `FromHandle<object>`. Recommendation: the latter, since it is what Kotlin's own `Sticker<*>` gives a caller (`value: Any?`).
2. Box handle vs fan-out for the top-level positions. Recommendation: box (above); the human may prefer the fan-out's zero allocation for the top level and accept two mechanisms.
