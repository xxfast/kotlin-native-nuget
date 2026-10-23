# An extension function or property binds on every receiver, sync and `suspend`, generic or not

- ROADMAP: no line; item A6 of the Phase 4 follow-up batch, as of 2026-10-22: "an extension function or property binds on every receiver, sync and `suspend`, generic or not".
- Researched: 2026-10-22, about 20 minutes of a 20 minute budget. Read-only checkout `kn-p4f-d`, no Gradle run. One C# spike (SDK 10.0.300) in a scratch dir.
- Restatement: forward (Kotlin declares, C# consumes). Every Kotlin extension, function or property, sync or `suspend`, binds as a real C# extension on `{Receiver}Extensions`, whatever its receiver: an exported generic class at an open argument (`fun <T> Bindle<T>.unpack(): T`, `val <T> Bindle<T>.top: T`), a bare type parameter (`fun <T : Pet> T.introduce()`), a collection (`suspend fun List<Int>.total()`), an interface, a bound C# interface, a value class, a converting scalar (`Uuid`, `Instant`, `Duration`), a `ByteArray`, and the nullable form of each. No receiver kind keeps a named skip.
- Verdict: fix. Reserved ADR-238 (not drafted; the main thread drafts it). An ADR is warranted: the bare-`T` receiver's C# home and the suspend receiver's lowering each have genuine alternatives (see Recommendation).

## Findings

Paths under `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/` unless stated. Line numbers at `kn-p4f-d` on 2026-10-22.

### Where each refusal lives

1. **Sync generic extension function: structural `GENERIC` skip.** `forward/ForwardCallablePlanner.kt:2869-2873` classifies any extension with `typeParameters.isNotEmpty()` as `GENERIC` and returns `Skipped(...).nameUnroutedPosition { false }` at `:2896-2897`, before `planOrSkip` (`:2902`). The comment at `:2875-2880` says why: "the generic-function route takes top-level functions, and ADR-197 class-like members, never an extension". Fixture pin: `test-library/.../unrouted/UnroutedPositionsSample.kt:167-168` (`fun <T> Depot.structuralOnExtension(value: T): T`, "row 18, Predicted NONE"), asserted in `nuget-processor/src/test/.../tier1/Tier1UnroutedPositionsTest.kt`. **Verified by reading.**
2. **The classifier refuses a sync extension's own `T`.** `forward/ForwardMemberTypeParameters.kt:22-27`: `isForwardGenericMemberOwner()` returns `suspends` for an extension, so a sync one is `false`. `forward/ForwardBridgeTypeClassifier.kt:171-185` mints `BridgeType.TypeParameter` only when that is true; otherwise `:229-233` returns `Unsupported("type parameters require the named generic legacy route")`. `forwardMemberGenericRefusal()` names it "it is an extension function" (`ForwardMemberTypeParameters.kt:119`). **Verified by reading.**
3. **The own-`T` gate never looks at the receiver.** `forwardMemberGenericRefusal()` checks `parameters` (`ForwardMemberTypeParameters.kt:235-240`) and the return (`:241-249`), never `extensionReceiver`. A suspend `fun <T> Bindle<T>.x()` today never reaches it (finding 5 refuses the receiver first), so this is latent; once the receiver is admitted, an unchecked receiver like `Sticker<T>?` (a shape ADR-220 refuses at a parameter) would pass the gate. **Verified by reading.**
4. **A bare `T` receiver has no C# home.** `forward/ForwardCallablePlanner.kt:2839-2841`: the entry-point prefix falls back to `receiver.declaration.simpleName.lowercase()`, i.e. `t`. `cir/CirTranslator.kt:769-770` (`extensionReceiverKey()`) keys on `simpleName`, so the class would be `TExtensions` (`:794`, `:848`, `:1007`). The suspend route rejects it explicitly: `.takeIf { receiver.declaration !is KSTypeParameter }` (`ForwardCallablePlanner.kt:2885-2886`). The property route refuses `BridgeType.TypeParameter` (`forward/ForwardPropertyPlanner.kt:1029-1033`, comment "an extension property over a bare `T` receiver is not a generic-class member and has no carrier"). **Verified by reading.**
5. **Suspend extension: three receiver arms.** `forward/ForwardStaticSuspendRoute.kt:89-123` (`staticSuspendReceiver`) returns null, so the skip stays named, for: any receiver with type arguments (`:93`, which covers `List<Int>` AND a closed `Box<Int>`), a nullable enum, primitive, `Char` or `String` (`:101-103`), and `Unit`, `Instant`, `Duration`, `Uuid`, `ByteArray`, `Throwable`, `Interface`, `BoundInterface`, `ValueClass`, `Collection`, `TypeParameter` and the rest (`:105-122`). It only admits `Handle` (nullable or not), a non-null `Scalar` and a non-null `Enum` (`:15-33`). Pinned by `tier1/Tier1StaticSuspendTest.kt:84-98` ("a suspend extension on a collection receiver stays named"). **Verified by reading.**
6. **The suspend emitters have exactly those three arms.** Kotlin: `exports/SuspendFunctionExports.kt:200-212` (prelude and read) and `:232-239` (C slot). C#: `cir/CirFunctionTranslator.kt:268-296` (`cirReceiverParameter`). **Verified by reading.**
7. **The legacy parameter shapes already lower most of the missing receiver kinds.** `forward/ForwardLegacyRouteCollections.kt:215-285` (`legacyParameterShape(type: KSType)`, a type-level overload, not tied to a `KSValueParameter`): a closed generic class is a borrowed `Handle` (`:228-236`), an own `T` is `Boxed` (`:240-247`), a nullable handle is `Handle(nullable)` (`:252-253`), an enum is `Enum`, a nullable primitive, `Char`, `String` or enum is `NullableScalar` with the has-value fan-out (`:257-259`, `:97-121`), a collection or nullable collection is `Marshalled` (`:276-281`). It refuses `Interface`, `BoundInterface`, `ValueClass`, `Uuid`, `Instant`, `Duration` and `ByteArray` (`:261-264`, `:282-285`). **Verified by reading.** Whether those refusals also make a suspend member *parameter* of those kinds a named skip today is **inferred** (the selector `staticSuspendRouteMembers` filters on `legacyRefusedParameter`, `ForwardStaticSuspendRoute.kt:143-149`, which reads the same function, so it is likely).
8. **The sync plan route already lowers every receiver kind.** `forward/ForwardKotlinPlanEmitter.kt:1164-1175` (`receiverExpression`) lowers the receiver through `loweredArgument` (`:2064-2110`), the same arms a parameter uses: `Uuid.parse`, `instantFromDotNetTicks`, `durationFromDotNetTicks`, `handleRead` for a class or interface, `forwardBoundedRead` for a `TypeParameter`, `valueHelper` for a bound interface, `loweredCollectionExpression`, `ByteArray`. `ForwardCallablePlanner.kt:4755-4758` builds the receiver's ABI slots with `nativeInputParameters`, has-value slot included. The input gate `inputSkipReason` (`:4762-4850`) admits `TypeParameter` and `Nullable(TypeParameter)`. **Verified by reading.** So a sync generic extension function needs only the planner gate and the type-parameter stamp, not new lowering.
9. **Method type parameters attach after planning.** `ForwardCallablePlanner.kt:2787-2798` (`withMethodTypeParameters`) stamps `forwardMethodTypeParameters()` onto the plan's public signature; used by the class, sealed, object and top-level routes (`:1706`, `:1921`, `:2068`, `:2772`), not by `extensionEntry`. `isUnroutedGeneric` (`:2781-2785`) is the gate those routes use. **Verified by reading.**
10. **Extension property receivers.** `forward/ForwardPropertyPlanner.kt:962-1035` (`isSupportedReceiver`) refuses `ByteArray` (`:1031`, comment "not in the ADR-132 function-route receiver set either"), `TypeParameter` (`:1033`), and `Nullable` over `Collection`, `BoundInterface` or `ByteArray` (`:1000-1019`, these fall to `hasValueFanOutInner() != null`, which is null for them). A receiver with the property's OWN type parameter (`val <T> Bindle<T>.top`) is refused earlier: the property is the type parameter's owner, which the classifier does not handle (`ForwardBridgeTypeClassifier.kt:171-185`, falls to `:229-233`), so `genericReference` refuses `Bindle<T>`. The drop is recorded at `ForwardPropertyPlanner.kt:871-878` and rendered "its extension receiver type ... is not a supported extension-property receiver" at `NugetProcessor.kt:744`. **Verified by reading.** That `genericReference` refuses an `Unsupported` argument (rather than the classifier producing some other shape) is **inferred** from `:785-790` (`isErasedTypeArgument` check), not traced to the end.
11. **The property plan and C# model carry no type parameters.** `forward/ForwardPropertyPlan.kt:199-219` (`ForwardPropertyPlan`) and `cir/CirModel.kt:1383-1397` (`CirExtensionProperty`: `receiverType`, `name`, `type`, `getter`, `setter`, `doc`). The block is rendered `extension(${prop.receiverType} receiver)` at `cir/CirClassRenderer.kt:643`. **Verified by reading.**
12. **`ByteArray` function receiver: the docs and the planner disagree.** `docs/topics/extensions.md:85` says a `ByteArray` receiver is "no" for a function. `ForwardCallablePlanner.kt:4791-4792` admits `ByteArray` at every input, receiver included, and `loweredArgument` lowers it. Which is true today is **not verified**; the implementation pins it with a Tier 1 cell first (see Sample test).
13. **Cell 23 / BUG-010.** `ForwardCallablePlanner.kt:2863-2867` and `:2914-2921`: `suspend inline fun <reified T> Receiver.get(): Result<T>` is `UNSUPPORTED_COMBINATION`, asserted in `tier1/Tier1DiagnosticCellsTest.kt:102-107`. A top-level extension's `parentDeclaration` is null, so ADR-213's reified-at-bound rule (`ForwardMemberTypeParameters.kt:139-143`, refused only when `parentDeclaration != null`) already admits its `reified T`. Whether the suspend route's return shape (`legacyReturnShape`) carries a `Result<T>` payload is **not verified**.
14. **Diagnostic sentences that change.** `forward/ForwardDiagnostic.kt:911-917` (`SUSPEND`: "as an extension on a class, String, primitive or enum receiver ... as an extension on any other receiver") and `:925-935` (`GENERIC`: "a `suspend` enum, value-class or extension member"). **Verified by reading.**
15. **Docs that state the gaps.** `docs/topics/supported-features.md:80`, `:81`, `:97`, `:102` (row 102 lists "extension" among the skips); `docs/topics/extensions.md:71-91`, `:159-163`; `docs/topics/forward-overview.md` (carries the receiver sentence). **Verified by reading.**
16. **Flow return on a suspend extension is a separate item.** `ForwardStaticSuspendRoute.kt:125-133` (`returnsStaticSuspendFlow`) keeps a static owner's `suspend fun ...: Flow<T>` named, "the awaited flow needs a static collect route of its own (ADR-230)". That is memo C2's item, not a receiver kind; this design does not touch it.
17. **Member extensions are not checked.** A class member with an extension receiver (`class Groomer { fun Cat.brush() }`): `ForwardCallablePlanner.kt:2976` says "a member extension is not walked: no route plans one", and there is no fixture or Tier 1 cell for one. Whether the class walk drops it silently, names it, or plans it with the receiver lost is **not verified**. See open question 3.

### C# spike (verified)

Scratch console app, `net10.0`, SDK 10.0.300, nullable enabled. Command: `dotnet run` in `$(mktemp -d)/probe`. Every shape below compiled and ran:

```C#
public static partial class SatchelExtensions {
  public static T Bundle<T>(this Satchel<T> receiver) => receiver.Item;
  public static Task<T> BundleAsync<T>(this Satchel<T> receiver, CancellationToken ct = default) => ...;
  extension<T>(Satchel<T> receiver) {
    public T Bundled => receiver.Item;
    public T Stash { get => receiver.Item; set => receiver.Item = value; }
  }
}
public static partial class ListExtensions {
  public static int Total(this IReadOnlyList<int> receiver) { ... }
  public static Task<int> TotalAsync(this IReadOnlyList<int> receiver, CancellationToken ct = default) => ...;
  public static int Count2(this IReadOnlyList<int>? receiver) => receiver?.Count ?? -1;
  extension(IReadOnlyList<string>? receiver) { public int Longest => receiver?.Count ?? -1; }
}
public static partial class ByteArrayExtensions {
  public static int Checksum(this byte[] receiver) => receiver.Length;
  extension(byte[]? receiver) { public bool IsEmptyOrNull => receiver is null || receiver.Length == 0; }
}
public static partial class PetExtensions {
  public static string Describe<T>(this T receiver) where T : IPet => receiver.Name;
}
public static partial class ObjectExtensions {
  public static string Tagged<T>(this T receiver) => $"tag:{receiver}";
  extension<T>(T receiver) { public string Label => $"label:{receiver}"; }
}
public static partial class CatIdExtensions {
  public static Task<string> BadgeAsync(this CatId receiver, CancellationToken ct = default) => ...;
  public static Task<string> OrStrayAsync(this CatId? receiver, CancellationToken ct = default) => ...;
}
```

Call sites `s.Bundle()`, `s.Bundled`, `s.Stash = 9`, `await s.BundleAsync()`, `l.Total()` on a `List<int>`, `arr.Total()` on an `int[]`, `none.Longest` on a null `List<string>?`, `((byte[]?)null).IsEmptyOrNull`, `new Cat().Describe()`, `42.Tagged()`, `"x".Label`, `await id.OrStrayAsync()` on a null `CatId?`. Output:

```
7 7 7 7
9
6 9 3 3
-1
2 True
Oreo tag:42 label:x
c1 stray
```

So: a C# 14 generic extension block `extension<T>(Satchel<T> receiver)` declares a get/set property with inferred `T`; a classic generic extension method and a generic block coexist in one `static partial class`; `this IReadOnlyList<int>` accepts `List<int>` and `int[]`; a nullable reference receiver accepts a null local. **Verified by the spike.**

## Recommendation

One ADR-238, five parts, all on the routes the codebase is converging on (the ADR-062 plan for sync, the ADR-229 static suspend route for `suspend`).

### Consumer API

```kotlin
package io.github.xxfast.kotlin.native.nuget.test.bindle

class Bindle<T>(var top: T)
interface Pet { val name: String }

fun <T> Bindle<T>.unpack(): T = top
val <T> Bindle<T>.peek: T get() = top
var <T> Bindle<T>.lid: T
  get() = top
  set(value) { top = value }
fun <T : Pet> T.introduce(): String = "I am $name"
fun <T> T.tag(): String = "tag:$this"
val <T> T.label: String get() = "label:$this"
fun Bindle<*>.isPacked(): Boolean = top != null

suspend fun <T> Bindle<T>.unpackLater(): T { delay(1.milliseconds); return top }
suspend fun List<Int>.total(): Int { delay(1.milliseconds); return sum() }
suspend fun List<Int>?.totalOrZero(): Int = this?.sum() ?: 0
suspend fun Pet.greet(): String = "hi $name"
suspend fun CatId.badge(): String = id
suspend fun CatId?.orStray(): String = this?.id ?: "stray"
suspend fun Mood?.loudly(): String = this?.name ?: "quiet"
suspend fun Int?.orNone(): Int = this ?: -1
suspend fun Uuid.short(): String = toString().take(8)
suspend fun ByteArray.checksum(): Int = sum()
val ByteArray?.isBlank: Boolean get() = this == null || isEmpty()
val List<Int>?.size2: Int get() = this?.size ?: -1
```

```C#
using var bindle = new Bindle<int>(7);
int top = bindle.Unpack();                 // BindleExtensions.Unpack<T>(this Bindle<T>)
int peek = bindle.Peek;                    // extension<T>(Bindle<T> receiver) { T Peek { get; } }
bindle.Lid = 9;
string intro = new Cat("Oreo").Introduce(); // PetExtensions.Introduce<T>(this T) where T : IPet
string tag = 42.Tag();                     // ObjectExtensions.Tag<T>(this T)
bool packed = bindle.IsPacked();           // IsPacked<T>(this Bindle<T>)

int later = await bindle.UnpackLaterAsync();
int total = await new List<int> { 1, 2 }.TotalAsync();   // this IReadOnlyList<int>
int zero = await ((IReadOnlyList<int>?)null).TotalOrZeroAsync();
string hi = await pet.GreetAsync();        // this IPet
string badge = await new CatId("c1").BadgeAsync();
CatId? none = null; string stray = await none.OrStrayAsync(); // CS1929 on a bare CatId, as sync
string quiet = await ((Mood?)null).LoudlyAsync();
int n = await ((int?)null).OrNoneAsync();
string s = await Guid.NewGuid().ShortAsync();
int sum = await new byte[] { 1, 2 }.ChecksumAsync();
bool blank = ((byte[]?)null).IsBlank;
```

### Part 1: sync generic extension function on the plan route

- `isForwardGenericMemberOwner()` (`ForwardMemberTypeParameters.kt:22-27`): an extension is an owner, sync or not (`if (extensionReceiver != null) return true`). The classifier then mints `TypeParameter` for it (`ForwardBridgeTypeClassifier.kt:178`).
- `extensionEntry` (`ForwardCallablePlanner.kt:2869-2897`): drop the `typeParameters.isNotEmpty() -> GENERIC` arm; gate on `forwardMemberGenericRefusal() != null` instead (the `isUnroutedGeneric` rule, `:2781-2785`), keeping the `GENERIC` named skip for a refused shape; and append `.withMethodTypeParameters(function)` to the `planOrSkip` result (`:2902`), as `:2772` does.
- `forwardMemberGenericRefusal()` checks the receiver like a parameter: a receiver that mentions an own `T` must be the own `T` itself, `carriesOwn()`, `isOwnInsideGenericValueClass()` or `isOwnInsideGenericInterface()` (ADR-222 already admits a generic interface receiver, `ForwardCallablePlanner.kt:2826-2827`). Otherwise "its type parameter is nested in `<receiver>`", the shipped sentence. The multi-bound witness rule (`:251-259`) counts the receiver as a witnessing parameter.
- Kotlin half: no new code. `receiverExpression` reads the receiver through `loweredArgument`, which already reads `Bindle<T>` as the erased handle and a bare `T` through `forwardBoundedRead` (`ForwardKotlinPlanEmitter.kt:2084-2085`); the invocation passes the explicit type argument ADR-213 spells (`kotlinTypeArgument`). This mirrors `fun <T> unwrapBox(box: Box<T>): T` (`test-library/.../cat/Helpers.kt:13`), which binds today. That the receiver read is byte-for-byte the parameter read is **inferred** from `:1164-1175`; the Tier 1 cell compiles it.
- C# half: `ForwardCirPlanProjection.extension` renders the plan's `publicSignature.typeParameters`, so the method is `public static T Unpack<T>(this global::Ns.Bindle<T> receiver)` with ADR-197's `where` clauses. Whether `extension(...)` reads `typeParameters` today or only the member projections do is **not verified**; if not, it is the one-line hookup the class projection has.

### Part 2: the bare-`T` receiver's home

`fun <T : Pet> T.introduce()` lands on the `{Receiver}Extensions` of its **first bound**: `PetExtensions` in the bound's namespace when the bound is exported (ADR-126's rule), `this T receiver where T : IPet`. An unbounded `T` (bound `Any?` or `Any`) lands on `ObjectExtensions` in the declaring package's namespace, `object` being C#'s spelling of `Any`. One helper owns the key, read by `extensionReceiverKey()` (`CirTranslator.kt:769-770`), the function and property group-bys (`:780-785`, `:840-844`), the suspend merge (`:1002-1004`), `classifier.extensionNamespaceOf` (read by `ForwardPropertyPlanner.kt:163`, `:907`) and the entry-point prefix (`ForwardCallablePlanner.kt:2839-2841`, prefix `pet` or `any`), so all five agree. The property route admits `TypeParameter` and `Nullable(TypeParameter)` at `isSupportedReceiver` (`ForwardPropertyPlanner.kt:1033`), rendered `extension<T>(T receiver)`.

Alternatives: `TExtensions` (what the code would spell today, meaningless in IntelliSense); one `{Library}Extensions` class for every bare-`T` extension (hides the bound, and a bounded one would sit away from the bound's other extensions); refusing (rule 1).

### Part 3: generic extension property

- Classifier: a type parameter whose owner is a `KSPropertyDeclaration` with an `extensionReceiver` mints `TypeParameter` exactly as a member function's does (`ForwardBridgeTypeClassifier.kt:171-185`), named by a property twin of `forwardCsharpMethodTypeParameterName` (clash set: the property's C# name).
- `ForwardPropertyPlan` gains `typeParameters: List<ForwardMethodTypeParameter>`, filled by a property twin of `forwardMethodTypeParameters()`; the property's own-`T` gate is the function gate applied to the receiver and the property type (a setter's value is the property type, ADR-218 already carries a `T` setter on a class).
- `CirExtensionProperty` gains `typeParameters` and constraints; `CirClassRenderer.kt:643` renders `extension<T>(Bindle<T> receiver) where T : ...`. Verified to compile by the spike, including a get/set property.
- Kotlin half: the property emitter's receiver lowering (`ForwardPropertyKotlinEmitter.kt:601`, `inputLowering`) gains the `TypeParameter` read (`forwardBoundedRead`) and the export body calls `receiver.peek` on the erased receiver; a `T` result rides the boxed handle ADR-147 class properties use, read back with `NugetMarshal.FromHandle<T>`.
- A star or `out` projection receiver (`fun Bindle<*>.isPacked()`, `val Bindle<out T>.x`) gets a synthesized method type parameter (`IsPacked<T>(this Bindle<T> receiver)`) and Kotlin reads `Bindle<*>`. `genericReference` refuses a projection today (`ForwardBridgeTypeClassifier.kt:730-735`, "C# has no projection of a generic class"), so the receiver position needs its own branch ahead of it. See open question 5.

### Part 4: every suspend extension receiver (the ADR choice)

Replace `ForwardStaticSuspendReceiver`'s three arms with the receiver's `ForwardLegacyParameterShape` (`legacyParameterShape(receiverType)`, the type-level overload, `ForwardLegacyRouteCollections.kt:215`), and extend `legacyParameterShape` with the arms the sync plan route already lowers and it does not: `Interface` (borrowed handle, `NugetMarshal.HandleOf` in C#, `handleRead` in Kotlin), `BoundInterface` (transfer GCHandle, `nuget{Iface}Value`), `ValueClass` (the underlying's wire, rewrapped in Kotlin; nullable over the underlying's null or fan-out), `Converting` for `Uuid` / `Instant` / `Duration` (string, ticks, ticks; nullable over the null pointer or fan-out), and `Bytes` for `ByteArray` (`NugetMarshal.CreateBytes`, disposed in the C# `finally`). The receiver is then exactly a leading parameter: the Kotlin export reads it in the prelude before the launch (`SuspendFunctionExports.kt:200-212`, ADR-122's "read before the launch" rule), and C# lowers it with the parameter's own lowering (`CirFunctionTranslator.kt:268-296` becomes a call into the parameter path, rendered as the `this` parameter). A collection receiver's wire container is disposed by C# after the `_async` export returns, which is safe because Kotlin copied it in the prelude, as a `Marshalled` suspend parameter is today (ADR-114). That last point is **inferred** from the prelude ordering, and the LeakTests row below proves it.

The suspend member's own `T` in the receiver (`suspend fun <T> Bindle<T>.unpackLater()`) is the `Handle` arm at an applied type (`:228-236`) plus the Part 1 receiver gate; `legacyTypeArguments()` already spells the call's type argument (`SuspendFunctionExports.kt:216-219`). A bare-`T` suspend receiver is the `Boxed` arm, homed by Part 2. `.takeIf { receiver.declaration !is KSTypeParameter }` (`ForwardCallablePlanner.kt:2885-2886`) goes.

Alternatives:
- **Receiver-only arms on `ForwardStaticSuspendReceiver`** (add `Collection`, `Interface`, `ValueClass`, `NullableScalar`, ...): no change to suspend parameters, but a second lowering of every kind beside the legacy shapes, and a receiver accepted where the same type as a parameter is refused. Rejected: two lowerings of one wire drift (the ADR-159 lesson).
- **Move suspend onto the ADR-062 plan** (one lowering for sync and suspend): the end state long term, but every suspend owner moves, not just extensions; far outside this item's file set. Rejected for this ADR; noted in its Consequences as the direction.

### Part 5: the remaining sync property receivers

`isSupportedReceiver` (`ForwardPropertyPlanner.kt:962-1035`) admits `ByteArray`, `Nullable(ByteArray)`, `Nullable(Collection)` (gated on `isSetterEligible()` as the non-null arm is) and `Nullable(BoundInterface)`. The property emitter's `inputLowering` (`ForwardPropertyKotlinEmitter.kt:601`) and the C# `inputArgument` (`forward/ForwardCirPropertyProjection.kt:878`) gain the null arms the function route already has (`IntPtr.Zero` for a null container, byte handle or GCHandle). Cell 23 (finding 13) loses its special case: a top-level `suspend inline fun <reified T> R.get(): Result<T>` binds on Part 4 with `T` reified at its bound, ADR-213's rule, provided the suspend return shape carries `Result<T>` (open question 4).

### Diagnostics

`ForwardDiagnostic.kt:911-917` drops "as an extension on any other receiver" (the sentence then names only the Flow-result and unreachable-interface cases); `:925-935` adds "an extension (any receiver, sync or `suspend`)" and drops the extension from the refused list. `NugetProcessor.kt:744` stays for an unexported receiver only (an unexported non-stdlib type has no C# type to extend; that is reachability, not a receiver kind).

## Files an implementation touches

- `forward/ForwardMemberTypeParameters.kt` (owner rule, receiver in the gate, property twins of the name and parameter helpers)
- `forward/ForwardBridgeTypeClassifier.kt` (property-owned `T`; projection receiver branch)
- `forward/ForwardCallablePlanner.kt` (`extensionEntry`: generic gate, `withMethodTypeParameters`, receiver prefix for bare `T`, suspend receiver slot; cell 23 removal)
- `forward/ForwardStaticSuspendRoute.kt` (receiver becomes a legacy shape)
- `forward/ForwardLegacyRouteCollections.kt` (new shapes: `Interface`, `BoundInterface`, `ValueClass`, `Converting`, `Bytes`)
- `exports/SuspendFunctionExports.kt` (receiver prelude, read and slot off the shape; parameter path gains the new shapes)
- `cir/CirFunctionTranslator.kt` (`cirReceiverParameter` off the shape; parameter lowering for the new shapes)
- `forward/ForwardPropertyPlanner.kt`, `forward/ForwardPropertyPlan.kt`, `forward/ForwardPropertyKotlinEmitter.kt`, `forward/ForwardCirPropertyProjection.kt` (receiver admission, type parameters, null arms)
- `forward/ForwardCirPlanProjection.kt` (extension method type parameters, if not already rendered)
- `cir/CirModel.kt`, `cir/CirClassRenderer.kt` (`CirExtensionProperty.typeParameters`, `extension<T>(...)`)
- `cir/CirTranslator.kt` (one receiver-key helper for all five sites, bare `T` home)
- `forward/ForwardDiagnostic.kt` (two sentences), `NugetProcessor.kt:744` comment
- Fixtures: new `test-library/.../bindle/Bindles.kt` (names checked unused: `Bindle`, `bindle`); `catnap/SwingExtensions.kt` gains the suspend receivers; `unrouted/UnroutedPositionsSample.kt:167-168` row 18 flips to BINDS
- Tests: `IntegrationTests/ExtensionFunctionTests.cs`, `ExtensionPropertyTests.cs`, `StaticSuspendTests.cs`; `LeakTests/LiveHandleTests.cs`, `LeakTests/CollectabilityTests.cs`; Tier 1 below; `tier1/Tier1StaticSuspendTest.kt:84-98`, `tier1/Tier1UnroutedPositionsTest.kt`, `tier1/Tier1DiagnosticCellsTest.kt:102-107` flip
- Docs: `docs/topics/supported-features.md:80`, `:81`, `:97`, `:102`; `docs/topics/extensions.md:71-91`, `:159-163` (the "no" rows go; nullable collection and bound interface become "yes" for a property); `docs/topics/generics.md`; `docs/topics/coroutines-and-flow.md`; `docs/topics/forward-overview.md`; ADR-238

## Sample test

xunit (`IntegrationTests/ExtensionFunctionTests.cs`, `ExtensionPropertyTests.cs`, `StaticSuspendTests.cs`):

```C#
[Fact]
public void Bindle_Unpack_GenericReceiverInfersT()
{
    using var bindle = new Bindle<string>("kibble");
    Assert.Equal("kibble", bindle.Unpack());
    MethodInfo unpack = typeof(BindleExtensions).GetMethod("Unpack")!;
    Assert.True(unpack.IsGenericMethodDefinition);
}

[Fact]
public void Bindle_Lid_GenericExtensionPropertyRoundTrips()
{
    using var bindle = new Bindle<int>(1);
    bindle.Lid = 9;
    Assert.Equal(9, bindle.Peek);
}

[Fact]
public void Pet_Introduce_BareTypeParameterReceiverHonoursTheBound()
{
    using var oreo = new Cat("Oreo");
    Assert.Equal("I am Oreo", oreo.Introduce());
    Assert.Equal("tag:42", 42.Tag());
}

[Fact]
public async Task List_TotalAsync_CollectionReceiverAcceptsAnyReadOnlyList()
{
    Assert.Equal(3, await new List<int> { 1, 2 }.TotalAsync());
    Assert.Equal(9, await new[] { 4, 5 }.TotalAsync());
    Assert.Equal(0, await ((IReadOnlyList<int>?)null).TotalOrZeroAsync());
}

[Fact]
public async Task SuspendExtension_EveryReceiverKindBinds()
{
    using var oreo = new Cat("Oreo");
    Assert.Equal("hi Oreo", await ((IPet)oreo).GreetAsync());
    Assert.Equal("c1", await new CatId("c1").BadgeAsync());
    CatId? none = null;
    Assert.Equal("stray", await none.OrStrayAsync());
    Assert.Equal("quiet", await ((Mood?)null).LoudlyAsync());
    Assert.Equal(-1, await ((int?)null).OrNoneAsync());
    Assert.Equal(3, await new byte[] { 1, 2 }.ChecksumAsync());
}
```

LeakTests (`LiveHandleTests.cs`, own process per ADR-120): `GenericExtension_Unpack_ReturnsToBaseline` (receiver borrowed, boxed `T` result released), `SuspendCollectionReceiver_TotalAsync_ReturnsToBaseline` (the C#-built list container is disposed after the call completes, finding in Part 4), `SuspendBoundInterfaceReceiver_ReturnsToBaseline` (the transfer GCHandle is owned by Kotlin's cleaner). `CollectabilityTests.cs`: `SuspendInterfaceReceiver_IsCollectedOnceTheTaskCompletes`, the mirror of the `RockAsync` row (`LeakTests/CollectabilityTests.cs:1157-1185`), with the `NoInlining` + `await Task.Yield()` helper CLAUDE.md prescribes.

Tier 1 cells (`nuget-processor/src/test/.../tier1/`):
- `Tier1GenericReceiverExtensionTest`: `fun <T> Bindle<T>.unpack(): T` compiles both halves; C# declares `Unpack<T>(this global::...Bindle<T> receiver)`; no `GENERIC` warning. A refused shape (`fun <T> Sticker<T>?.x()`) keeps the named `GENERIC` skip with "nested in".
- `Tier1BareTypeParameterReceiverTest`: bounded lands on `PetExtensions` with `where T : global::...IPet`; unbounded on `ObjectExtensions`; a suspend one beside a sync one merges into one class.
- `Tier1GenericExtensionPropertyTest`: `val`/`var <T> Bindle<T>.x` renders `extension<T>(...)` and the Kotlin export compiles; `Tier1CSharpCompile` compiles the C#.
- `Tier1StaticSuspendTest`: replace "a suspend extension on a collection receiver stays named" (`:84-98`) with one cell per receiver kind (collection, nullable collection, interface, bound interface, value class over each underlying and nullable, `Uuid`/`Instant`/`Duration` and nullable, `ByteArray`, nullable primitive/`Char`/`String`/enum, closed `Box<Int>`, own `T` inside `Bindle<T>`, bare `T`), each compiling both halves; assert the C# `this` spelling and that no `SKIPPED_` line names the member.
- `Tier1ReceiverShapesExtensionPropertyTest`: `ByteArray`, `ByteArray?`, `List<Int>?`, nullable bound interface bind.
- `Tier1ReceiverShapesExtensionTest`: pin `fun ByteArray.checksum()` first (finding 12), red or green.
- A collection receiver whose component the bridge refuses takes it from `Tier1UnwrappableWitness`, per CLAUDE.md, not a hard-coded type.

## Deferred scope

None.

## Open what-questions

1. **Bare-`T` home.** Bounded `T` on `{Bound}Extensions`, unbounded on `ObjectExtensions` in the declaring namespace? Recommendation: yes. An unbounded `fun <T> T.tag()` then shows on every C# type wherever that namespace is imported; that is what the Kotlin author declared (Kotlin/JVM compiles it to a static `tag(Object)`), so it stays.
2. **Widen suspend parameters too.** Extending `legacyParameterShape` (Part 4) also makes a suspend member *parameter* of type interface, bound interface, value class, `Uuid`/`Instant`/`Duration` or `ByteArray` bind, if those are refused today (finding 7, inferred). Recommendation: yes, one lowering for receiver and parameter; the main thread confirms the parameter refusals with one Tier 1 cell before the lane is sized.
3. **Member extensions.** `class Groomer { fun Cat.brush() }` is unverified (finding 17). Recommendation: check first; if it drops or binds wrong, it binds as an instance method with the extension receiver first, `groomer.Brush(Cat receiver)`, Kotlin/JVM's own lowering of a member extension (C# cannot declare an extension method inside a non-static class, so it cannot be `cat.Brush()`). That is a different owner shape and could be its own ADR section.
4. **Cell 23.** Does the suspend route's return shape carry `Result<T>`? If not, `Result<T>` on the suspend route is part of this item under rule 1. Recommendation: one Tier 1 cell decides it.
5. **Projection receivers.** `fun Bindle<*>.isPacked()` and `fun List<*>.describe()` are common Kotlin idioms; Part 3's synthesized method type parameter is the proposal. Recommendation: include; the classifier's projection refusal applies to every other position and stays there.
6. **Docs claim for `ByteArray` function receivers** (finding 12): the Tier 1 pin decides whether `extensions.md:85` was already stale.
