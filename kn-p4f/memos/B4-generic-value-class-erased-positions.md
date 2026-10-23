# A generic value class crosses at every erased position

- ROADMAP: line 60 as of 2026-10-22: "A generic value class at an erased position (`Box<Sticker<Int>>`, a lambda payload or result, a `Flow` element, an awaited `suspend` result) is skipped, named: it needs a per-instantiation box/unbox pair, which has no design ([ADR-220](docs/adr/220-generic-value-class-record-struct.md))"
- Researched: 2026-10-22, about 12 of a shared 35 minute budget (researched together with B1, B2, B3)
- Restatement: forward. `fun boxed(): Box<Sticker<Int>>`, `val onSticker: (Sticker<String>) -> Sticker<Int>`, `fun stream(): Flow<Sticker<Cat>>`, `suspend fun later(): Sticker<Int>`, `suspend fun take(s: Sticker<Int>)`, and the own-`T` forms (`fun <T> boxedOf(v: T): Box<Sticker<T>>`) bind; C# sees `Box<Sticker<int>>`, `KotlinFunc<Sticker<string>, Sticker<int>>`, `KotlinFlow<Sticker<Cat>>`, `Task<Sticker<int>>`, and a C#-chosen `new Box<Sticker<int>>(...)` works too. Adjacent, folded in: a **non-generic** value class with a **nullable underlying** (`value class Nick(val name: String?)`) at the same erased positions.
- Verdict: fix, new design. Own ADR, reserved **ADR-228** (Proposed, not drafted here).

## Findings

1. **The gates.** Every erased position refuses a value class with non-empty `typeArguments`:
   - `isErasedTypeArgument()` (`forward/ForwardGenericSealed.kt:183-206`): `typeArguments.isEmpty() && hasErasedCrossing()` (generic class arguments, ADR-199/208).
   - `isLambdaTypeArgument()` (`forward/ForwardCsharpTypes.kt:251-275`): same test (lambda payload and result).
   - Flow element and awaited `suspend` result: the generic-type refusal text `NugetProcessor.kt:900` / `:922` ("... but not the generic type `Crate<Int>`"), reached because ADR-220 deliberately set `typeArguments` (`ForwardBridgeTypeClassifier.kt:1056-1059`).
   - `hasErasedCrossing()` (`forward/ForwardLegacyRouteCollections.kt:195-197`): String, primitive, enum or object-handle underlying only, so `Nullable(String)` (Nick) and `TypeParameter` (own-`T` Sticker) fail.
   **Verified** by reading.
2. **No box pair is planned for a generic value class.** `valueClassBoxingEntries` (`ForwardCallablePlanner.kt:1265-1300`) returns `emptyList()` when `cls.typeParameters.isNotEmpty()`, and `valueClassEntries` never reaches it for `GENERIC` (`:1214-1216`). **Verified** by reading.
3. **The non-generic ADR-171 mechanism is keyed per C# type.** `Boxers` (`cir/CirMarshalRenderer.kt:176-195`) is a `Dictionary<Type, Func<object, IntPtr>>` looked up by **runtime** type in `Wrap<T>` (`:446-452`); `Factories` is looked up by `typeof(T)` (normalised through `Nullable.GetUnderlyingType`) in `Materialize<T>` (`:201-235`). A generic struct has no single `Type` key: `typeof(Sticker<int>)` and `typeof(Sticker<Cat>)` differ, and an own-`T` `Sticker<T>`'s closed type exists only at run time. **Verified** by reading.
4. **`Wrap<T>` dispatches primitives on the static `typeof(T)`, not on the runtime type** (`CirMarshalRenderer.kt:418-438`: `var type = Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T)`). So a box helper that holds the struct's `Value` as `object` and calls `Wrap<object>` would miss the `int` branch. The inner wrap must be `Wrap<T>` inside the struct's own generic code. **Verified** by reading.
5. **ADR-199 already has a per-closed-type slot.** `internal static class NugetFactory<T> { internal static Func<NugetKotlinHandle, object>? Create; }` (`CirMarshalRenderer.kt:767-774`), consulted by `Materialize<T>` after a `Factories` miss (`:220-225`) and filled by a generic class's static constructor (`cir/CirClassRenderer.kt:313-323`). It is emitted only when `includesFactorySlot` (`cir/CirTranslator.kt:1037`, `cir/CirModel.kt:697`). **Verified** by reading.
6. **The suspend route already spells the closed struct.** `ForwardLegacyRouteCollections.kt:845-851` completes a value class with `${type.csharpType}.NugetUnbox(handle)`; for a generic one `csharpType` is `Sticker<int>` (ADR-220), so a `static` `NugetUnbox` on the generic struct is callable as `Sticker<int>.NugetUnbox(h)`. **Verified** by reading (spelling); not compiled.
7. **Kotlin boxes a generic value class at a generic position as one runtime class, and `Sticker(null)` is a non-null box.** Spike, Kotlin/Native 2.4.20 (repo pins 2.4.10):
   ```kotlin
   val boxed: Any = Box(Sticker(3))
   val raw: Any? = (boxed as Box<*>).item
   println("boxed is Sticker: ${raw is Sticker<*>}, value=${(raw as Sticker<*>).value}")
   val again: Any = Sticker<Any?>(raw.value)
   val nullInside: Any = Sticker<Any?>(null)
   println(Decal<Nothing>(7L).describe())
   ```
   ```
   boxed is Sticker: true, value=3
   rewrapped is Sticker: true
   Sticker(null) boxed non-null: true, value=null
   decal 7
   ```
   **Verified**. So one export pair per generic value class serves every instantiation, and the box handle distinguishes `null` (null pointer) from `Sticker(null)` (a live handle).
8. **C# dispatch without a per-type key, under JIT and NativeAOT.** Spike: scratch console app, `public readonly record struct Sticker<T>(T Value) : INugetGenericValue` (an `internal` interface, explicit implementation), a `Wrap<T>` that tests `value is INugetGenericValue`, and a `NugetFactory<T>` slot filled by a static `NugetRegister()` for both `Sticker<T>` and `Sticker<T>?`:
   ```
   $ dotnet run                                   # net10.0, JIT
   $ dotnet publish -c Release -r osx-arm64 -p:PublishAot=true && ./out/probe   # NativeAOT
   S: 8
   S?: 8
   S? null: 0
   object: 1
   int: 7
   unregistered: Sticker`1[System.String]
   read S: Sticker { Value = h5 }
   read S?: Sticker { Value = h6 }
   null vs Sticker(null): False
   ```
   Identical output under both. **Verified**: the interface test reaches the struct for `T = S`, `T = S?` and `T = object`; a `null` `S?` short-circuits to zero; the slot reads back both `S` and `S?`; and **an unregistered closed type misses the slot** (so registration is load-bearing, see the design).
9. **Adjacent: Nick's exclusion.** ADR-171's decision text excludes "a VALUE class whose underlying is itself nullable" from the pair; `docs/topics/value-classes.md:255-262` and `coroutines-and-flow.md:1143-1146` document it as a limitation. **Verified** by reading. Under rule 1 it is folded in here: the pair below needs only a nullable-underlying unbox result.

## Recommendation

**One box/unbox export pair per generic value class, not per instantiation; per-closed-type registration on the C# side.**

### Kotlin: the pair (plan origin `VALUE_CLASS_BOX`, names `<prefix>_box` / `<prefix>_unbox`)

```kotlin
// value class Sticker<T>(val value: T): the underlying crosses on the bare-T wire
@CName("kn_..._stickers__sticker_box")
public fun export_..._sticker_box(unboxed: COpaquePointer?, errorOut: COpaquePointer?): COpaquePointer? = try {
  NugetHandles.retain(pkg.Sticker(unboxed?.asStableRef<Any>()?.get()))       // init runs here
} catch (e: Throwable) { /* ADR-032 error-out */ null }

@CName("kn_..._stickers__sticker_unbox")
public fun export_..._sticker_unbox(boxed: COpaquePointer, errorOut: COpaquePointer?): COpaquePointer? = try {
  (boxed.asStableRef<Any>().get() as pkg.Sticker<*>).value?.let { NugetHandles.retain(it) }
} catch (e: Throwable) { /* ADR-032 error-out */ null }

// value class Decal<T>(val serial: Long): a phantom underlying crosses on its own wire
NugetHandles.retain(pkg.Decal<Nothing>(unboxed))                              // Nothing is within every bound
(boxed.asStableRef<Any>().get() as pkg.Decal<*>).serial
```

- The box's underlying read is `forwardBoundedRead` over the class's own `T` (the B1 receiver lowering): `Any?` for an unconstrained `T`, non-null for `T : Any` (C# guards, below), the erased bound for a spellable bound, the ADR-198 trampoline for an unspellable one.
- The unbox result is the bare-`T` handle result (`handleResultBody` / `nullableHandleResultBody`, `ForwardKotlinPlanEmitter.kt:289-305`) for an own-`T` underlying and the existing value-class result for a phantom one.
- Adjacent, Nick (`String?`, primitive `?` underlyings): box takes the nullable wire (`String?` pointer; ADR-079 has-value pair for `Int?`), unbox returns it (the nullable-string result; the ADR-061 BOOLEAN + `valueOut` result for a nullable primitive). `hasErasedCrossing()` becomes true for `Nullable(String)`, `Nullable(Primitive)`, `TypeParameter`, `Nullable(TypeParameter)` and any phantom underlying that already crosses.

### C#: the struct

```csharp
internal static class StickerNative                     // CS7042: hoisted, ADR-147 hoistDllImports (B1 creates it)
{
    [DllImport(...)] internal static extern IntPtr Native_Box(IntPtr unboxed, out IntPtr error);
    [DllImport(...)] internal static extern IntPtr Native_Unbox(IntPtr boxed, out IntPtr error);
}

public readonly record struct Sticker<T>(T Value) : INugetGenericValue
{
    static Sticker() => NugetRegister();

    /// Idempotent. Fills ADR-199's per-closed-type slot for this instantiation and its nullable.
    internal static void NugetRegister()
    {
        NugetFactory<Sticker<T>>.Create ??= static handle => NugetUnbox(handle);
        NugetFactory<Sticker<T>?>.Create ??= static handle => NugetUnbox(handle);
    }

    internal static IntPtr NugetBox(Sticker<T> value)
    {
        NugetRegister();
        IntPtr inner = NugetMarshal.Wrap<T>(value.Value, out bool owned);   // Wrap<T>, not Wrap<object> (finding 4)
        try
        {
            IntPtr handle = StickerNative.Native_Box(inner, out IntPtr error);
            if (error != IntPtr.Zero) { throw NugetErrorNative.BuildException(error); }
            return handle;
        }
        finally { if (owned && inner != IntPtr.Zero) { NugetMarshal.Dispose(inner); } }
    }

    internal static Sticker<T> NugetUnbox(NugetKotlinHandle boxed) { /* Native_Unbox, then new Sticker<T>(NugetMarshal.FromHandle<T>(raw)); dispose the incoming box */ }

    IntPtr INugetGenericValue.NugetBox() => NugetBox(this);
}
```

- `NugetMarshal.Wrap<T>` gains one branch **before** `Boxers`: `if (value is INugetGenericValue generic) { owned = false; IntPtr boxed = generic.NugetBox(); owned = true; return boxed; }`, keeping ADR-171's "`owned` is false while the box export can throw" rule. `INugetGenericValue` is rendered beside `INugetHandle` (internal, per module).
- Read side: `Materialize<T>` already consults `NugetFactory<T>.Create`; `includesFactorySlot` becomes true when any generic value class has a pair.
- **Registration is load-bearing** (finding 8: an unregistered closed type misses). A Kotlin-made `Sticker<int>` can reach `Box<Sticker<int>>.Item` before C# ever touched `Sticker<int>`. So every generated C# member whose signature names a generic value class instantiation at an erased position (a `Box<Sticker<int>>` result, a `KotlinFlow<Sticker<T>>`, a `KotlinFunc<..., Sticker<int>>`, a `Task<Sticker<int>>`) calls `Sticker<int>.NugetRegister();` (or `Sticker<T>.NugetRegister();` in an own-`T` method) before it returns the carrier. Plus the static constructor and `NugetBox` cover C#-made values. A static call, so trimming- and AOT-safe without reflection (finding 8 ran it under NativeAOT).
- `T : Any` underlying (`value class Firm<T : Any>`): `NugetBox` refuses a null `Value` with the existing `valueClassUnderlyingOrThrow` `ArgumentException`, since the Kotlin parameter is non-null.
- Nick: unchanged ADR-171 `Boxers`/`Factories` entries; only the pair's wire changes.

### Gates

- `isErasedTypeArgument`, `isLambdaTypeArgument`: `is BridgeType.ValueClass -> hasErasedCrossing()` (drop `typeArguments.isEmpty()`).
- Flow element and suspend result: route a generic value class instantiation through the value-class arm (`ForwardLegacyReturnShape.ValueClass`, `ForwardLegacyRouteCollections.kt:773-851`) instead of the generic-type refusal; find the site that turns non-empty `typeArguments` into "not the generic type" and exempt `ValueClass`.
- Own-`T` forms (`Box<Sticker<T>>`, `Flow<Sticker<T>>`, `(Sticker<T>) -> Unit`): `carriesOwn()` (`ForwardMemberTypeParameters.kt:193-199`) admits `isOwnInsideGenericValueClass()` as a component; this is the same edit B2 makes for collections.

Alternatives rejected:
- A per-closed-instantiation export pair keyed `typeof(Sticker<int>)` (the ROADMAP line's assumption): unbounded, and impossible for an own-`T` instantiation, which has no closed type at build time. Kotlin's erasure (finding 7) makes it unnecessary.
- `Boxers` keyed by `GetGenericTypeDefinition()` and `MakeGenericType` on read: reflection, not NativeAOT-safe for value-type instantiations.
- `default(T) is INugetGenericValueReader<T>` probe on read: works for `T = Sticker<int>` without registration but not for `T = Sticker<int>?` (`default` is null), so registration is needed anyway; one mechanism, not two.

## Files an implementation touches

- `nuget-processor/src/main/.../forward/ForwardCallablePlanner.kt` (`valueClassBoxingEntries`, `valueClassEntries`)
- `nuget-processor/src/main/.../forward/ForwardLegacyRouteCollections.kt` (`hasErasedCrossing`, suspend/flow value-class arm)
- `nuget-processor/src/main/.../forward/ForwardGenericSealed.kt:183`, `forward/ForwardCsharpTypes.kt:251` (gates)
- `nuget-processor/src/main/.../NugetProcessor.kt:880-930` (generic-type refusal exemption)
- `nuget-processor/src/main/.../forward/ForwardMemberTypeParameters.kt` (`carriesOwn`)
- `nuget-processor/src/main/.../exports/ValueClassExports.kt:31-32` (emit the pair for `GENERIC`), `forward/ForwardKotlinPlanEmitter.kt` (box/unbox bodies, phantom `<Nothing>`)
- `nuget-processor/src/main/.../forward/ForwardCirPlanProjection.kt:75-110` (box/unbox projection), `cir/CirObjectRenderer.kt:75` (struct: interface, cctor, `NugetRegister`, hoisted externs), `cir/CirMarshalRenderer.kt` (`Wrap<T>` branch, `INugetGenericValue`), `cir/CirTranslator.kt:1037` (`includesFactorySlot`)
- Registration call emission at erased-position call sites: the CIR projections for generic-class results, `KotlinFlow`, `KotlinFunc`, `Task` completions
- `forward/ForwardAbiContract.kt` (two exports per generic value class, ADR-117 collision check)
- Tier 1: `Tier1GenericValueClassTest` (`boxed`, `later` flip), `Tier1GenericInstanceMemberPositionTest` (`boxed`, `later`, `laterWith`, flows), `Tier1MutableStateFlowGenericElementTest`, `Tier1MutableStateFlowValueClassElementTest`, `Tier1FlowElementValueClassWithoutStructTest` / `Tier1WrapValueClassTest` (Nick flips), a Tier 1 C# compile of `Sticker<int>.NugetUnbox`
- Fixture `test-library/.../stickers/Stickers.kt`; `IntegrationTests/GenericValueClassTests.cs`, `IntegrationTests/WrapValueClassTests.cs`; `LeakTests/LiveHandleTests.cs`, `LeakTests/CollectabilityTests.cs`
- Docs: `docs/topics/value-classes.md:75-78` and `:255-262`, `coroutines-and-flow.md:1143-1146`, `lambdas-and-callbacks.md:36-39`, `generics.md`, `docs/topics/supported-features.md`, ADR-171 and ADR-220 (cross-reference)

## Sample test

Fixture:

```kotlin
fun boxed(): Box<Sticker<Int>> = Box(Sticker(1))
fun boxedNothing(): Box<Sticker<String?>?> = Box(Sticker(null))   // a present Sticker(null)
fun boxedNull(): Box<Sticker<String?>?> = Box(null)
fun peelBox(box: Box<Sticker<Int>>): Int = box.item.value
fun <T> boxedOf(value: T): Box<Sticker<T>> = Box(Sticker(value))
val onSticker: (Sticker<String>) -> Sticker<Int> = { Sticker(it.value.length) }
fun stream(): Flow<Sticker<Cat>> = flowOf(Sticker(Cat("Oreo")), Sticker(Cat("Mylo")))
suspend fun later(): Sticker<Int> = Sticker(5)
suspend fun bumpLater(sticker: Sticker<Int>): Sticker<Int> = Sticker(sticker.value + 1)
fun boxedDecal(): Box<Decal<Cat>> = Box(Decal(9L))
fun nickBox(): Box<Nick> = Box(Nick(null))                       // adjacent: nullable underlying
```

```csharp
[Fact]
public void ClosedInstantiation_InsideAGenericClass_ReadsBack_WithoutCSharpTouchingItFirst()
{
    using Box<Sticker<int>> box = Stickers.Boxed();
    Assert.Equal(new Sticker<int>(1), box.Item);
}

[Fact]
public void CSharpBuiltBox_CarriesTheStruct_AndRunsInitInKotlin()
{
    using var box = new Box<Sticker<int>>(new Sticker<int>(4));
    Assert.Equal(4, Stickers.PeelBox(box));
    Assert.Throws<KotlinArgumentException>(() => new Box<Sticker<int>>(new Sticker<int>(-1)));
}

[Fact]
public void NullAndStickerOfNull_StayDistinct_AtAnErasedPosition()
{
    using var present = Stickers.BoxedNothing();
    using var absent = Stickers.BoxedNull();
    Assert.Equal(new Sticker<string?>(null), present.Item);
    Assert.Null(absent.Item);
}

[Fact]
public void OwnTypeParameter_InsideABox_RegistersPerInstantiation()
{
    using var ints = Stickers.BoxedOf(3);
    using var names = Stickers.BoxedOf("Oreo");
    Assert.Equal(3, ints.Item.Value);
    Assert.Equal("Oreo", names.Item.Value);
}

[Fact]
public void LambdaPayloadAndResult_CrossBoxed()
{
    using KotlinFunc<Sticker<string>, Sticker<int>> on = Stickers.OnSticker;
    Assert.Equal(4, on.Invoke(new Sticker<string>("Oreo")).Value);
}

[Fact]
public async Task FlowElement_AndSuspendResultAndParameter_CrossBoxed()
{
    var names = new List<string>();
    await foreach (Sticker<Cat> s in Stickers.Stream()) { using Cat cat = s.Value; names.Add(cat.Name); }
    Assert.Equal(new[] { "Oreo", "Mylo" }, names);
    Assert.Equal(5, (await Stickers.Later()).Value);
    Assert.Equal(6, (await Stickers.BumpLater(new Sticker<int>(5))).Value);
}

[Fact]
public void PhantomAndNullableUnderlying_CrossBoxed()
{
    using var decal = Stickers.BoxedDecal();
    Assert.Equal(9L, decal.Item.Serial);
    using var nick = Stickers.NickBox();
    Assert.Null(nick.Item.Name);
}
```

Leak rows (`LeakTests/LiveHandleTests.cs`; a collectability row in `CollectabilityTests.cs` for the `KotlinFunc`):

```csharp
[Fact]
public async Task GenericValueClass_ErasedPositions_ReturnToBaseline()
{
    long before = NugetMarshal.LiveHandles;
    for (int i = 0; i < 50; i++)
    {
        using (var box = new Box<Sticker<int>>(new Sticker<int>(i))) { _ = box.Item; }   // C#-made box + inner T box
        using (var box = Stickers.BoxedOf("x")) { _ = box.Item; }                        // Kotlin-made, unbox per read
        _ = await Stickers.BumpLater(new Sticker<int>(i));                               // suspend param + result
    }
    Assert.Equal(before, NugetMarshal.LiveHandles);
}
```

## Deferred scope

None.

## Open what-questions

1. Is registration at every erased-position call site acceptable cost? It is a static call plus two `??=` per call. Recommendation: yes; it is the only AOT-safe way to make a Kotlin-made `Sticker<int>` readable before C# touched the type (finding 8). Alternative is the static constructor alone, which leaves `Box<Sticker<int>>.Item` throwing `NotSupportedException` until the consumer happens to construct one.
2. `Box<object>` holding a Kotlin `Sticker` cannot recover `T` (C# never named it). Recommendation: unchanged behaviour, the existing `NotSupportedException` (the same as a non-generic value class at `T = object` today, `CirMarshalRenderer.kt:226-229`); document it.
3. The adjacent ineligible-sealed-interface underlying (also excluded from ADR-171's pair, `value-classes.md:255`) is **not** on this code path: its blocker is the sealed interface itself having no C# crossing. Escalate as a separate item; this memo does not design it.
