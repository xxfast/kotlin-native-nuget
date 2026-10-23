# A generic interface reference at an output position binds: `fun keep(): Keepsake<String>` returns `IKeepsake<string>`, a C# implementation round-trips as itself

- ROADMAP: line text as of 2026-10-22 (Phase 4, line 63): "A generic interface reference at an output position (return, property getter, or a class property, `fun keep(): Keepsake<String>`) keeps ADR-208's named skip: it needs a generic C# backing wrapper over an erased receiver."
- Researched: 2026-10-22, about 8 of 20 minutes, one two-assembly C# spike run under the JIT and NativeAOT.
- Restatement: forward. Kotlin declares the generic interface (`interface Keepsake<T> { var item: T }`); C# consumes and may implement `IKeepsake<T>`. Every Kotlin position typed by a generic interface reference binds in C#: return, getter, setter, class/object/companion/top-level property, nullable, suspend result, `Flow`/`StateFlow` element, collection component, `Box<..>` argument, lambda payload and result, nested (`Keepsake<Keepsake<String>>`), and open `Keepsake<T>` inside a generic function or a generic class. A C#-implemented instance Kotlin stored comes back as the same C# object (`Assert.Same`); a Kotlin-implemented one comes back as a fresh generic backing wrapper `Keepsake<string> : IKeepsake<string>` the caller disposes (ADR-005).
- Verdict: fix. New ADR-224 recommended (genuine alternatives in the erased-input mechanism, below), amending ADR-222 (its "Not shipped" paragraph and its "erased position is declined" sentence) and ADR-208 (its 2026-10-22 amendment).

## Findings

### Where the skip lives (all **verified by reading**, checkout `kn-p4f-a` at `6ee56924`)

1. `forward/ForwardBridgeTypeClassifier.kt:444-446`: `classify` returns `SpecializedProtocol("generic declaration $qualifiedName")` for any interface with type parameters. Every output position calls `classify`.
2. `forward/ForwardBridgeTypeClassifier.kt:1253-1258`: only `classifyParameter` asks `genericInterfaceOrNull` first. KDoc at `:1260-1268` states the reason: "an output needs a generic backing wrapper, and an erased position could build the generic bridge state only by reflection."
3. `forward/ForwardCallablePlanner.kt:5987`: `"generic declaration "` maps to `ForwardPlanSkipReason.GENERIC`. Receiver sites use `genericInterfaceOrNull` at `forward/ForwardCallablePlanner.kt:2767`; the property planner only records a flag at `forward/ForwardPropertyPlanner.kt:1465`.
4. `NugetProcessor.kt:2492-2496`: `interfaceQualifiedNameOrNull()` drops any `BridgeType.Interface` with a `bridgeEntry` ("ADR-222: a generic interface has no backing wrapper or dispatch exports, only its bridge"), so a generic interface never joins `reachableInterfaces` (`NugetProcessor.kt:2806`). `NugetProcessor.kt:2506-2508` also filters generic interfaces out of the bound-interface promotion.
5. `forward/ForwardCallablePlanner.kt:1495-1498`: `interfaceEntries` plans every member with `receiverType = BridgeType.ObjectHandle(ifaceName)` and passes no `ownerType`. `forward/ForwardPropertyPlanner.kt:587-610`: `interfaceProperties` passes `ForwardPropertyReceiver.Handle(owner)` with no generic owner. Both are the bare-name receiver a generic interface cannot use (`asStableRef<Keepsake>()` does not compile in Kotlin).
6. `cir/CirClassTranslator.kt:4833-4925`: `translateInterfaceBackingClass` builds `CirClass(name, interfaces = listOf("I$name"), isSealed = true, backsInterface = "I$name")` with no `typeParameters`.
7. `NugetProcessor.kt:2280-2305`: `SKIPPED_GENERIC_INTERFACE_ASYNC_MEMBER` names every suspend/Flow member of a generic interface, with the reason that the interface-owned export "would need `asStableRef<Keepsake<...>>()`". This is the same erased-receiver gap one member kind over, and it must be lifted with this item or the backing wrapper cannot implement a generic interface that declares one.

### What already exists and is reused (all **verified by reading**)

- **The erased-receiver member route.** A generic class plans members over `ForwardGenericOwner` (`forward/ForwardBridgeTypeClassifier.kt:1607-1616`; erased spelling `Crate<Any?>`, trampoline for a self or multi bound, `forward/ForwardMarshallingModel.kt:952-1005`), threaded through `planOrSkip(ownerType = ...)` (`forward/ForwardCallablePlanner.kt:1626`, `:1679`, `:3153`, `:3190`) and `ForwardPropertyReceiver.Handle(cls.forwardOwnerTypeName() ?: owner, cls.forwardGenericOwner())` (`forward/ForwardPropertyPlanner.kt:513-517`). A `T` parameter, result, `T?` and getter ride the boxed handle wire (`forward/ForwardCallablePlanner.kt:3680`, `:3802`, `:4105`, `:4180`; `forward/ForwardPropertyPlanner.kt:1575`). The generic flow property over the erased receiver is ADR-215 (`forward/ForwardPropertyPlanner.kt:524-525`).
- **Extern hoisting for a generic C# type.** `cir/CirClassRenderer.kt:123-150` `renderClass` hoists every `[DllImport]` out of a generic class into `{Name}Native` (CS7042) when `typeParameters` is non-empty, and already does it for an interface backing wrapper with `nativeCarrier` (ADR-174). So a generic `CirClass` backing an interface renders correctly with no new renderer.
- **The open-instantiation slot.** `cir/CirClassRenderer.kt:313-322`: every non-abstract generic class's static constructor fills `NugetFactory<Name<T>>.Create`. `forward/ForwardCirPlanProjection.kt:1882-1915` (`fillingOpenSlots`, ADR-216 amendment) fills `NugetFactory<X<T>>.Create ??=` at a generic member's call site before it reads an open instantiation through an erased `T`. `cir/CirMarshalRenderer.kt:222-225`: `Materialize<T>` consults the slot after `Factories`.
- **Closed-instantiation `Factories` lines.** `forward/ForwardBridgeTypeClassifier.kt:87` (`closedInstantiations`) and `:817-823` (`new ${constructType ?: csharpType}(handle, out _)` keyed on the applied C# spelling). `genericInterfaceOrNull` currently calls `genericReference(..., register = false)` (`:1281-1287`), which is why no interface line is registered today.
- **Identity on return.** `forward/ForwardCirPlanProjection.kt:2358-2375` `interfaceReturnExpression`: `NugetMarshal.TryResolveCSharp(h, out IFoo o) ? o : new Backing(h, out _)`. `cir/CirMarshalRenderer.kt:744-757`: `TryResolveCSharp<T> where T : class` reads `nuget_csharp_token` (`nuget-runtime/.../NugetRuntime.kt:907-908`), disposes the transfer handle on a hit, casts `GCHandle.Target`. The generic bridge object sets `nugetToken` exactly as the plain one does (`exports/InterfaceBridgeFactoryExports.kt:64-80`). So a stored C# `Diary` resolves back to itself through the existing expression once the csharpType is `IKeepsake<string>`; no new identity machinery.
- **Identity at an erased output.** `cir/CirMarshalRenderer.kt:205-212`: `Materialize<T>` probes the token first whenever `key.IsInterface`, which `IKeepsake<string>` is. So `List<IKeepsake<string>>` elements and `Box<IKeepsake<string>>.Value` already return the original C# object; only the Kotlin-backed miss needs a factory.
- **Erased input today.** `cir/CirMarshalRenderer.kt:413-461` `Wrap<T>`: after `INugetHandle`, it calls `HandleOf((object)value, typeof(T))` into `NugetBridge.HandleFor` (`cir/CirBridgeRenderer.kt:29-75`), which deliberately has no generic-interface arm (`:31-32`). This is the only real hole for erased inputs.
- **Direct input.** ADR-222's `NugetBridge.{Iface}HandleOf<T>` (`cir/CirBridgeRenderer.kt:311-333`) stays as is.

### Spike: an AOT-safe mint for an erased generic-interface input (**verified by execution**)

The design rests on one claim: a default interface method on the generated generic interface can mint `KeepsakeBridgeState<T>` for a C# implementation the consumer compiled in another assembly, at any instantiation (value types included), from an erased `Wrap<T>`, under NativeAOT, without a diamond when one class implements two instantiations. Scratch dir `/var/folders/y8/xtx49fnx1g1cgsnn4fxrkxw40000gn/T/tmp.jo0C3L8uHu` (outside the repo), .NET SDK 10.0.300, `Lib` classlib plus `App` console referencing it:

```csharp
// Lib (stands for the generated assembly)
[EditorBrowsable(EditorBrowsableState.Never)]
public interface INugetBridgeable<TSelf> { internal IntPtr MintBridge(); }
public interface IKeepsake<T> : IDisposable, INugetBridgeable<IKeepsake<T>>
{
    T Item { get; set; }
    IntPtr INugetBridgeable<IKeepsake<T>>.MintBridge() => KeepsakeBridgeState<T>.Create(this);
}
public interface IAlbum<T> : INugetBridgeable<IAlbum<T>> { T Cover { get; } IntPtr INugetBridgeable<IAlbum<T>>.MintBridge() => AlbumBridgeState<T>.Create(this); }
public static IntPtr Wrap<T>(T value) => value is INugetBridgeable<T> b ? b.MintBridge() : throw new NotSupportedException(typeof(T).Name);
public sealed class Box<T> { public Box(T v) { Handle = Marshal2.Wrap<T>(v); } public IntPtr Handle; }

// App (stands for the consumer)
new Box<IKeepsake<int>>(new Counter());          // Counter : IKeepsake<int>
new Box<IKeepsake<Cat>>(new Basket());           // Cat is a struct
var two = new TwoFaced();                        // : IKeepsake<int>, IKeepsake<string>, IAlbum<string>
new Box<IKeepsake<int>>(two); new Box<IKeepsake<string>>(two); new Box<IAlbum<string>>(two);
new Box<object>(two);                            // expected NotSupported
```

Output, identical under `dotnet run` and `dotnet publish -c Release -r osx-arm64` with `PublishAot=true` (`LIBRARY_PATH` set for the Homebrew ssl link, per CLAUDE.md):

```
mint KeepsakeBridgeState<Int32> item=41
mint KeepsakeBridgeState<Cat> item=Mylo
mint KeepsakeBridgeState<Int32> item=7
mint KeepsakeBridgeState<String> item=Oreo
mint AlbumBridgeState<String> cover=sunbeam
42 42 42 42 43
object: NotSupported Object
```

Proves: the consumer class compiles without implementing the `internal` member (the DIM covers it, across assemblies); the self-typed marker removes the diamond (a first try with a non-generic `INugetBridgeable` would make `TwoFaced` CS8705, **inferred**, not compiled: the generic form was adopted before it was tried); a consumer-chosen value-type instantiation (`IKeepsake<Cat>`) has code under NativeAOT; the explicit implementation picks the instantiation the static `T` names, not the first one the runtime type happens to list. The `object` static type is not served, which is right: no Kotlin position spells a generic interface as `Any` (ADR-208 refuses `Box<Any>`). A clean republish (`rm -rf obj bin out`) piped through `grep -cE "warning IL[0-9]+"` printed `0`: no trim or AOT analysis warning (**verified**).

## Recommendation

One end state: a generic interface reference is a `BridgeType.Interface` at every position, with a generic backing wrapper on the Kotlin-backed side and an erased-safe mint on the C#-implemented side. Priced at roughly 12 processor files, one fixture file, two test files and three docs.

### Kotlin half

1. **Classifier.** `classify`'s generic-interface branch (`ForwardBridgeTypeClassifier.kt:444-446`) returns `genericInterfaceOrNull(type)` (refusal-or-Interface) instead of the `"generic declaration"` protocol, so every position sees one classification; `classifyParameter` no longer needs its special entry (keep the call harmless or drop it). `genericInterfaceOrNull` registers the closed instantiation (`register = true`) under the interface key `Ns.IKeepsake<string>` with the backing construct `new global::Ns.Keepsake<string>(handle, out _)`, and sets `BridgeType.Interface.backingType` to the applied backing spelling (`global::Ns.Keepsake<string>`, open: `global::Ns.Keepsake<T>`). The bridge-refusal skip it already emits (`:1291-1298`) applies to output positions too, because a Kotlin-backed return is fine but a C# implementation stored and returned is the same position; see open question 2.
2. **Reachability.** `interfaceQualifiedNameOrNull` (`NugetProcessor.kt:2492-2496`) stops filtering `bridgeEntry != null`, so a generic interface at a return, getter, collection component, lambda payload or generic-class carrier joins `reachableInterfaces` and gets dispatch exports plus a backing wrapper. `genericBridgeNames` (`:2808-2820`) widens from "parameters and receivers" to every plan position that can hand a C# implementation to Kotlin: parameters, setters, constructor arguments, erased input components, a callback result, and an interface-bridge slot result.
3. **Dispatch exports over the erased receiver.** `interfaceEntries` passes `ownerType = iface.forwardGenericOwner()` and an erased `receiverType`; `interfaceProperties` passes `iface.forwardOwnerTypeName()` and `iface.forwardGenericOwner()`, exactly the class route's arguments. `addInterfaceExports` (`exports/InterfaceExports.kt:32-79`) is unchanged: it emits from the plans. Receiver read: `asStableRef<pkg.Keepsake<Any?>>()`; a self-bounded `Podium<T : Comparable<T>>` takes the class route's ADR-198 trampoline. Works for a Kotlin `Locket<T>` and for a C# bridge object alike, since both are `Keepsake<*>` at runtime (**verified** for the bridge read by ADR-222's experiment; for the dispatch export **inferred** from the identical class route).
4. **Async members.** Lift `SKIPPED_GENERIC_INTERFACE_ASYNC_MEMBER` (`NugetProcessor.kt:2280-2305`): the ADR-174 interface async emitters (`exports/InterfaceExports.kt:46-54`) run over the same erased owner the generic class's suspend and flow members use (ADR-147, ADR-215). Without it a generic interface with a `suspend fun` either stays unreturnable or its backing wrapper cannot implement `IKeepsake<T>`.

### C# half

5. **Generic backing wrapper.** `translateInterfaceBackingClass` sets `typeParameters` from the interface (constraints via the ADR-015/198 spelling), `interfaces = listOf("I$name<T>")`, `backsInterface = "I$name<T>"`, and `nativeCarrier = "${name}Native"` always for a generic interface. `renderClass` then hoists the externs (CS7042) with no renderer change. Shape:

   ```csharp
   internal static class KeepsakeNative { [DllImport(...)] internal static extern IntPtr playroom_Keepsake_get_item(IntPtr handle, out IntPtr error); /* ... */ }
   public sealed class Keepsake<T> : IKeepsake<T>, INugetHandle
   {
       static Keepsake()
       {
           NugetFactory<Keepsake<T>>.Create = static h => new Keepsake<T>(h, out _);     // existing CirClassRenderer:313-322
           NugetFactory<IKeepsake<T>>.Create ??= static h => new Keepsake<T>(h, out _);  // new: the interface key
       }
       public T Item { get => NugetMarshal.FromHandle<T>(KeepsakeNative.playroom_Keepsake_get_item(_handle, ...)); set { /* Wrap<T> */ } }
   }
   ```

   Visibility follows today's non-generic backing wrapper (`Pet`), whatever the shipped one is.
6. **Interface `Factories` key.** Closed instantiations register `[typeof(global::Ns.IKeepsake<string>)] = h => new global::Ns.Keepsake<string>(h, out _)` (step 1). Open ones are filled by `fillingOpenSlots` (`ForwardCirPlanProjection.kt:1888-1915`): `openSlotHandles` gains a `BridgeType.Interface` arm (today it returns empty at `:1913`) emitting `NugetFactory<IKeepsake<T>>.Create ??= static h => new Keepsake<T>(h, out _)`. And every exported generic class implementing a generic interface (`Locket<T> : Keepsake<T>`) adds the same `??=` line to its static constructor, so a consumer-constructed `new Box<IKeepsake<Cat>>(new Locket<Cat>(mylo))` reads back through `.Value` (**inferred**: closure argument below, not run).
7. **Return and getter reads** use the unchanged `interfaceReturnExpression(csharpType, backingType)`, now `TryResolveCSharp(h, out IKeepsake<string> o) ? o : new Keepsake<string>(h, out _)`. `ForwardCirPlanProjection.kt:263`, `:284`, `:1530`, `:1677` and `ForwardCirPropertyProjection.kt:437`, `:500` already route `BridgeType.Interface` there; they need the applied `backingType`, nothing else (**verified by reading** that they read `backingType`).
8. **Erased-safe mint (the spike).** Every bridgeable generic interface declares `: INugetBridgeable<IKeepsake<T>>` plus the explicit DIM `IntPtr INugetBridgeable<IKeepsake<T>>.MintBridge() => KeepsakeBridgeState<T>.Create(this).KotlinHandle;`. `INugetBridgeable<TSelf>` is rendered once per generated library, public, `[EditorBrowsable(Never)]`, with an `internal` member (it cannot live in the shared `Xxfast.Kotlin.Native.Interop` contract, whose `internal` the generated library could not call). `NugetMarshal.HandleOf<T>` and `Wrap<T>` test `value is INugetBridgeable<T> b` after `INugetHandle` and before `HandleOf((object)value, typeof(T))`, setting `owned = true` only after the mint (ADR-135). This serves every erased input (collection component, `Box<IKeepsake<X>>` constructor and setter, `Keepsake<Keepsake<String>>`'s item, a lambda result, a `T` boxed as `IKeepsake<X>`), closed or open, including an instantiation only the consumer chose. ADR-222's named `KeepsakeHandleOf<T>` direct entry stays: it is the same mint, and changing a shipped call site is out of this item.

### Closure argument for Kotlin-backed reads at erased positions (**inferred**)

A Kotlin-backed `Keepsake<X>` handle reaches C# only through (a) a generated member typed `Keepsake<X>` closed (a `Factories` line), (b) a generated generic member typed with open `Keepsake<T>` (its `fillingOpenSlots` line), or (c) a value the consumer constructed as an exported implementer (`Locket<X>`, whose static constructor fills the interface slot). A `Box<IKeepsake<X>>` holding it was filled from one of those three. If a fourth path exists, the symptom is loud: `Materialize<T>` throws `NotSupportedException("No generated factory materialises ...")`, not silent corruption.

### Alternatives rejected

- **Closed `HandleFor` arms per closed instantiation plus call-site slot fills for open ones**: AOT-safe, but misses a consumer-constructed `new Box<IKeepsake<Cat>>(basket)`, whose `Wrap<T>` runs inside the `Box<T>` constructor before any Kotlin member mentioning `Keepsake` is called. Leaves a hole, so rejected under the no-deferral rule.
- **`MakeGenericType` in `HandleFor`**: ADR-222 Alternative 3 already rejected it (reflection, value-type instantiations without code under NativeAOT).
- **A non-generic marker `INugetBridgeable`**: a class implementing `IKeepsake<int>` and `IKeepsake<string>` would have two most-specific `MintBridge` implementations (CS8705, **inferred**, not compiled).
- **Erased C# backing over `object` (`Keepsake : IKeepsake<object>`)**: cannot be typed `IKeepsake<string>`; rejected for the same reason as ADR-222 Alternative 4.
- **A new `BridgeType` variant**: ADR-208 Alternative 2 measured 103 `when` arms in 17 files for the twin decision; `BridgeType.Interface` already carries every applied field (`forward/ForwardMarshallingModel.kt:162-177`).

## Consumer API

```kotlin
// test-library playroom/KeepsakeVault.kt (new fixture)
class Vault {
  var kept: Keepsake<String> = Locket("Oreo")              // class property, get + set
  fun keep(): Keepsake<String> = kept                       // member return
  fun keepOrNull(): Keepsake<String>? = null
  fun all(): List<Keepsake<String>> = listOf(kept)
  suspend fun later(): Keepsake<String> = kept
  val updates: Flow<Keepsake<String>> = flowOf(kept)
}
var lastKeepsake: Keepsake<Int> = Locket(41)                // top-level property
fun <T> locketOf(item: T): Keepsake<T> = Locket(item)       // open return
fun <T> stash(items: List<Keepsake<T>>): List<Keepsake<T>> = items.reversed()   // open erased in + out
fun boxed(box: Box<Keepsake<String>>): Box<Keepsake<String>> = box
fun nested(): Keepsake<Keepsake<String>> = Locket(Locket("Mylo"))
fun visit(block: (Keepsake<String>) -> Keepsake<String>): Keepsake<String> = block(Locket("Oreo"))
class Shelf<T>(first: T) { fun keep(): Keepsake<T> = Locket(first) }  // Keepsake<T> in a generic class
```

```csharp
var diary = new Diary();                                    // IKeepsake<string>
using var vault = new Vault();
vault.Kept = diary;
Assert.Same(diary, vault.Keep());                           // identity: the token resolves the original
Assert.Same(diary, vault.Kept);
Assert.Same(diary, vault.All()[0]);
Assert.Same(diary, await vault.Later());

using var locket = Keepsakes.LocketOf("Oreo");              // IKeepsake<string>, Kotlin-backed
Assert.IsType<Keepsake<string>>(locket);
Assert.Equal("Oreo", locket.Item);
locket.Item = "Mylo";                                       // setter export over the erased receiver

var basket = new Basket<Cat>(mylo);                         // consumer-chosen instantiation
var back = Keepsakes.Stash(new[] { basket, new Basket<Cat>(oreo) });
Assert.Same(basket, back[1]);

using var box = new Box<IKeepsake<string>>(diary);          // erased input, minted by the DIM
using var boxBack = Keepsakes.Boxed(box);
Assert.Same(diary, boxBack.Value);

using var shelf = new Shelf<int>(7);
using var seven = shelf.Keep();                             // IKeepsake<int> from Shelf<T>
Assert.Equal(7, seven.Item);

Assert.Same(diary, Keepsakes.Visit(_ => diary));            // lambda result, then return
```

## Files an implementation touches

- `nuget-processor/src/main/.../forward/ForwardBridgeTypeClassifier.kt` (`:444-446`, `genericInterfaceOrNull` register and backing spelling)
- `.../forward/ForwardCallablePlanner.kt` (`interfaceEntries` owner type, `:1495-1560`; `GENERIC` mapping comment at `:5987`)
- `.../forward/ForwardPropertyPlanner.kt` (`interfaceProperties` receiver, `:587-610`; `:1465`)
- `.../forward/ForwardCirPlanProjection.kt` (`openSlotHandles` Interface arm, `:1898-1915`)
- `.../forward/ForwardMarshallingModel.kt` (`Interface.backingType` doc; maybe `openArguments` on `Interface`)
- `.../cir/CirClassTranslator.kt` (`translateInterfaceBackingClass`, `:4833-4925`)
- `.../cir/CirClassRenderer.kt` (static constructor interface-slot line, `:313-322`; implementer classes)
- `.../cir/CirMarshalRenderer.kt` (`Wrap<T>` and `HandleOf<T>` marker test, `:413-461`, `:710-725`)
- `.../cir/CirBridgeRenderer.kt` or the interface renderer (`INugetBridgeable<TSelf>` declaration, the DIM on `IKeepsake<T>`)
- `.../cir/CirTranslator.kt` (`:973`, interface backing list now includes generic ones)
- `.../NugetProcessor.kt` (`:2492-2496`, `:2506-2508`, `:2808-2820`, `:2280-2305`)
- `.../exports/InterfaceExports.kt` (no change expected; check the async branch with a generic owner)
- Fixture: `test-library/src/nativeMain/.../test/playroom/KeepsakeVault.kt` (new)
- Tests: `IntegrationTests/GenericInterfaceOutputTests.cs` (new), `LeakTests/LiveHandleTests.cs`, `LeakTests/CollectabilityTests.cs`, `nuget-processor/src/test/.../tier1/Tier1GenericInterfaceBridgeTest.kt` (output, erased and async cells with `dotnet build`), `Tier1UnroutedPositionsTest.kt` (move any witness still using a generic interface output)
- Docs: `docs/topics/generics.md`, `docs/topics/interfaces-abstract-sealed.md`, `docs/topics/supported-features.md`, ADR-224, ADR-222 and ADR-208 amendment notes, ROADMAP line 63 deleted.

## Sample test

```csharp
public class GenericInterfaceOutputTests
{
    private sealed class Diary : IKeepsake<string> { public string Item { get; set; } = "Oreo"; public void Dispose() { } }

    [Fact]
    public void CSharpKeepsake_StoredByKotlin_ComesBackAsTheSameObject()
    {
        var diary = new Diary();
        using var vault = new Vault();
        vault.Kept = diary;
        Assert.Same(diary, vault.Keep());
        Assert.Same(diary, vault.Kept);
        Assert.Same(diary, vault.All()[0]);
    }

    [Fact]
    public void KotlinKeepsake_ReturnsAsGenericBackingWrapper()
    {
        using var locket = Keepsakes.LocketOf(41);
        Assert.IsType<Keepsake<int>>(locket);
        locket.Item += 1;
        Assert.Equal(42, locket.Item);
    }

    [Fact]
    public void ConsumerChosenInstantiation_RoundTripsThroughAnErasedBox()
    {
        using var mylo = new Cat("Mylo", 9);
        var basket = new Basket<Cat>(mylo);
        using var box = new Box<IKeepsake<Cat>>(basket);
        using var back = Keepsakes.BoxedOpen(box);   // fun <T> boxedOpen(b: Box<Keepsake<T>>): Box<Keepsake<T>>
        Assert.Same(basket, back.Value);
    }
}
```

LeakTests rows (own process, ADR-120):

- `LiveHandleTests.GenericInterfaceOutput_ReturnedKeepsakes_ReturnToBaseline`: 100 x `LocketOf("Oreo")` disposed, 100 x `vault.Keep()` over a stored `Diary`, 100 x `vault.All()`; `NugetMarshal.LiveHandles` back to baseline (each `TryResolveCSharp` hit disposes its transfer handle).
- `LiveHandleTests.GenericInterfaceOutput_ErasedInputs_ReturnToBaseline`: 100 x `Boxed(new Box<IKeepsake<string>>(diary))` and `Stash` over `Basket<Cat>`.
- `CollectabilityTests.CSharpGenericKeepsake_StoredThenDropped_ReleasedAfterKotlinGc`: store a `Diary` in `vault.Kept`, read it back once, overwrite `Kept`, drop locals through a `NoInlining` helper plus `await Task.Yield()` (CLAUDE.md), `nuget_gc_collect`, the `WeakReference<Diary>` dies.

## Deferred scope

None. Two adjacent refusals stay because they are not gaps of this item, and are listed so the main thread can decide:

- A member-level type parameter on a bridged interface (`fun <R> map(...)`) stays refused: ADR-222 argues it is impossible (C# cannot call `impl.Map<R>` with an `R` Kotlin erased). Escalate if the user disagrees.
- ADR-222 refuses to bridge a C# implementation of an interface with a suspend member. Step 4 lifts the Kotlin-backed side of generic-interface async members; the C#-implemented side is a separate, pre-existing refusal for every interface, generic or not. Not deferred here, but not this item: see open question 3.

## Open what-questions

1. **Backing wrapper name.** `Keepsake<T>` (mirrors `Pet` for `IPet`) collides in IntelliSense with nothing today, but a Kotlin class named `Keepsake` in another package would. Recommendation: reuse whatever collision check `CirTranslator.kt:235-240` applies to non-generic backing wrappers; no new rule.
2. **Return of an interface whose C# bridge is refused** (a member's own `<R>`): today `genericInterfaceOrNull` turns the whole reference into an `Unsupported`. At an output position a Kotlin-backed value is still returnable. Recommendation: bind the output and skip only input/erased-input positions by name, since a C# implementation can never reach Kotlin for that interface, so it can never come back either.
3. **C#-implemented generic interface with a suspend member**: with step 4, `IKeepsake<T>` gains the suspend member, and a C# implementer must implement it, but ADR-222's bridge refuses such an interface, so passing the C# object in skips by name. Recommendation: this is the general ADR-084 suspend-slot refusal, not this item; the main thread decides whether it is already a ROADMAP item or needs one designed now (rule 1).
4. **Marker visibility.** `IKeepsake<T> : IDisposable, INugetBridgeable<IKeepsake<T>>` is visible in the type's base list (hidden from completion by `[EditorBrowsable(Never)]` for another assembly). Recommendation: accept; it is the only AOT-safe universal mint found.

## Inferred claims (not verified)

- Dispatch exports over `asStableRef<Keepsake<Any?>>()` compile and dispatch for an interface exactly as for a generic class (same route, different declaration kind). If wrong: Kotlin compile error, loud.
- The closure argument for Kotlin-backed reads at erased positions. If wrong: `NotSupportedException` from `Materialize<T>`, loud.
- CS8705 for a non-generic marker (the rejected alternative); not compiled.
- `TryResolveCSharp<IKeepsake<T>>` on a stored `IKeepsake<int>` read as `IKeepsake<string>` throws `InvalidCastException` (the C# backstop, mirror of ADR-222's Kotlin CCE). Not run; the generated API cannot build that mismatch.
