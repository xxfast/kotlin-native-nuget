# A generic value class's own members and secondary constructors bind on its C# record struct

- ROADMAP: line 61 as of 2026-10-22: "A generic value class's own members and secondary constructors are skipped, named: they need a substituted receiver per instantiation ([ADR-220](docs/adr/220-generic-value-class-record-struct.md))"
- Researched: 2026-10-22, about 10 of a shared 35 minute budget (researched together with B2, B3, B4)
- Restatement: forward. Kotlin declares `value class Sticker<T>(val value: T) { fun describe(): String; val doubled: T; fun with(other: T): Sticker<T>; constructor(text: String, n: Int) : this(...) }`; C# gets those as instance methods, properties and constructors on `public readonly record struct Sticker<T>(T Value)`, working for every closed instantiation (`Sticker<int>`, `Sticker<Cat>`, `Sticker<T>` inside a generic method). Same for a phantom one (`Decal<T>(val serial: Long)`).
- Verdict: fix. Own ADR, reserved **ADR-225** (Proposed, not drafted here). It also folds in an adjacent gap found while reading: a member with its **own** type parameters on **any** value class, generic or not, is refused today.

## Findings

1. **The skip site.** `NugetProcessor.kt:2461-2467` emits `genericValueClassMemberSkips()` for every value class with type parameters; the function is `forward/ForwardValueClassUnderlying.kt:131-158` (one `SKIPPED_UNSUPPORTED_TYPE` per public own member or secondary constructor). **Verified** by reading.
2. **The planner plans nothing.** `forward/ForwardCallablePlanner.kt:1214-1216`: `valueClassEntries` returns `emptyList()` for the `GENERIC` role, before constructors, properties, methods and the box pair are planned. **Verified** by reading.
3. **The non-generic member route already exists and is value-receiver shaped.** `valueClassEntries` (`ForwardCallablePlanner.kt:1193-1252`) builds `ForwardReceiver.Value(underlyingType, name = "value")` or `ForwardReceiver.Handle(...)`, then `valueClassConstructorEntries` (`:1306`), `valueClassPropertyEntries` (`:1370`), `valueClassMethodEntries` (`:1412`). The receiver lowers through `nativeInputParameters(receiver.name, receiver.type, RECEIVER, ...)` (`:4685-4688`). **Verified** by reading.
4. **The substituted receiver is already classifiable.** A value class is `ClassKind.CLASS`, so its own `T` classifies as `BridgeType.TypeParameter` through the `onGenericClass` arm (`ForwardBridgeTypeClassifier.kt:211-225`), and `genericValueClass()` (`:997-1061`) substitutes it into the underlying. So `Sticker<T>` at the class's own `T` is `ValueClass(underlying = TypeParameter(T))` or `Nullable(TypeParameter)` for an unconstrained `T`, which is exactly the ADR-220 own-`T` wire (`isOwnTypeParameterUnderlying`, `ForwardCallablePlanner.kt:4568-4570`, admits any `TypeParameter`, not only a member's own). **Verified** by reading; that the planner then accepts it for a receiver slot is **inferred**.
5. **Kotlin re-wrap of a `T` underlying already exists.** `valueClassUnderlyingLowering` (`ForwardKotlinPlanEmitter.kt:2407-2420`) has `TypeParameter` and `Nullable(TypeParameter)` arms that call `forwardBoundedRead(..., typeVariable())`, so a trampolined class `T` (ADR-198) reads `as T` inside the trampoline. **Verified** by reading.
6. **C# value-class members are expression-bodied.** `ForwardCirPlanProjection.valueClassMethod` / `valueClassProperty` (`forward/ForwardCirPlanProjection.kt:133-200`) build one expression from `nativeReceiverArg` plus call arguments; there is no prelude/finally slot, which a `T` box (`NugetMarshal.Wrap<T>` + `Dispose`) needs. ADR-220 added the prelude/cleanup "read off `.Value`" only for parameters (`ForwardCirPlanProjection.kt:1000`, `:1347`, `:1951`, `:1992`). **Verified** by reading.
7. **A generic C# type cannot hold `[DllImport]`** (CS7042); ADR-147 solved it with `hoistDllImports` (`cir/CirClassRenderer.kt:159`), moving externs to a sibling `{Name}Native` static class. The struct today is rendered by `renderReferenceValueClass` (`cir/CirObjectRenderer.kt:75`) with no members at all. **Verified** by reading (CS7042 itself **verified** in ADR-147).
8. **Adjacent gap: own type parameters on any value class member.** `isForwardGenericMemberOwner` (`forward/ForwardMemberTypeParameters.kt:21-30`) returns `false` for a value class (`ClassKind.CLASS -> !owner.isValueClass()`), and `forwardMemberGenericRefusal` names it "it is declared on a value class" (`:118`). So `value class Tag(val id: String) { fun <R> map(f: (String) -> R): R }` and `fun <R> Sticker<T>.pair(other: R)`-style members skip. **Verified** by reading. Folded in here (rule: no deferrals).
9. **Kotlin compiles every receiver spelling the design needs** (spike, Kotlin/Native 2.4.20 `kotlinc-native`, repo pins 2.4.10):
   ```
   $ kotlinc-native main.kt -o probe && ./probe.kexe
   decal 7      # Decal<Nothing>(7L).describe()
   decal 8      # Decal<Any?>(8L).describe()
   5            # Ranked(read as Comparable<Any?>).top(read), value class Ranked<T : Comparable<T>>
   ```
   **Verified** for the compile and run of these three shapes; not verified inside a generated export.

## Recommendation

Plan a generic value class's members on the existing value-class member route, with the receiver being the **substituted underlying at the class's own type parameters**. No per-instantiation anything: one export per member serves every instantiation, because Kotlin erases a generic value class to one runtime class and the bridge only ever spells the source-level constructor.

1. **Planner.** `valueClassEntries` stops returning early for `GENERIC`; it classifies `cls.asType(cls.typeParameters as arguments)` to get `ValueClass(underlying = TypeParameter | Nullable(TypeParameter) | phantom underlying)` and builds `ForwardReceiver.Value(underlying, name = "value")`. A phantom underlying is a plain `Value`/`Handle` receiver exactly as today. The primary `_create` stays unplanned (ADR-220: C# constructs positionally); the box pair is B4's business. `genericValueClassMemberSkips` and its call in `NugetProcessor.kt:2461-2467` are deleted; members that still cannot bind get the ordinary per-member named skips the route already produces.
2. **Receiver spelling in Kotlin.** Own-`T` underlying: `pkg.Sticker(<valueClassUnderlyingLowering("value", underlying)>).describe()`; Kotlin infers the argument from the bounded read (`Any?`, `Any`, the erased bound, or the trampoline's `T`). Phantom underlying: the argument cannot be inferred (the ADR-220 compile break), so spell it as `pkg.Decal<Nothing>(value)`: `Nothing` satisfies every bound (spike 9) and no member's own signature can observe it, since a phantom `T` never reaches a value. A trampolined class `T` (ADR-198) moves the whole body into `nugetTrampoline` (`ForwardKotlinPlanEmitter.kt:1371-1393`, which moves `body` wholesale) with no new code.
3. **Secondary constructors.** Planned like a VALUE class's (`valueClassConstructorEntries`): a `_create_N` export returning the underlying (`pkg.Sticker<...>(args).value`, explicit argument for a phantom as in 2, inferred for own `T` when a parameter mentions it, `Nothing` otherwise), inside the error-slot `try`, so `init` runs. C#: `public Sticker(string text, int n) : this(CreateChecked_2(text, n)) { }` where `CreateChecked_2` is a `private static T` (or the phantom underlying type) on the struct calling the hoisted `StickerNative` extern. A secondary constructor runs Kotlin `init` at C# construction; the primary one still does not (document the asymmetry beside ADR-220's).
4. **C# struct rendering.** `renderReferenceValueClass` gains members, and a generic struct hoists its externs with `hoistDllImports` into `internal static class StickerNative`, the ADR-147 pattern. The receiver argument is `NugetMarshal.Wrap<T>(Value, out bool valueOwned)` with a `finally` dispose for a `TypeParameter` underlying, so `valueClassMethod`/`valueClassProperty` take the general block body (the one `ForwardCirPlanProjection` already renders for ADR-220 parameters) whenever the receiver or any parameter needs a box. A non-null `TypeParameter` underlying reads through `valueClassUnderlyingOrThrow` (`ForwardValueClassUnderlying.kt:118-123`), so `default(Sticker<string>).Describe()` throws in C# for `T : Any`.
5. **Members referencing the class `T`.** `fun with(other: T): Sticker<T>` binds as `public Sticker<T> With(T other)`; the parameter is ADR-147's bare `T` box, the result ADR-220's struct reconstruction. No C# `where` clause is added to the struct (ADR-220 kept it constraint-free); a bound is checked in Kotlin by the bounded read's cast and surfaces as `KotlinInvalidCastException`, as ADR-198 documents for foreign enums.
6. **Adjacent: member own type parameters on a value class.** `isForwardGenericMemberOwner` admits a value class owner; `forwardMemberGenericRefusal` drops the "declared on a value class" arm. The receiver is the value receiver from 1, and the member's own `T` rides ADR-197 unchanged. On a generic value class the member's own names are de-clashed against the struct's parameters by `forwardCsharpMethodTypeParameterName` (`ForwardMemberTypeParameters.kt:38-58`, which already adds owner parameters to `taken`).

Alternatives rejected:
- Receiver as a boxed Kotlin handle (`ForwardReceiver.Handle` over a `StableRef` of the boxed `Sticker`): every call would mint and dispose a box for a value type, and it needs B4 first. One line: costlier, same surface.
- One export per closed instantiation: unbounded, and an own-`T` `Sticker<T>` has no closed instantiation to generate for.
- Extension-function lowering (`fun <T> Sticker<T>.describe()`): extensions with own type parameters are refused (`ForwardMemberTypeParameters.kt:114`), so it moves the problem.

## Files an implementation touches

- `nuget-processor/src/main/.../forward/ForwardCallablePlanner.kt` (`valueClassEntries`, `valueClassConstructorEntries`, receiver wire for a `TypeParameter` receiver)
- `nuget-processor/src/main/.../forward/ForwardValueClassUnderlying.kt` (delete `genericValueClassMemberSkips`), `NugetProcessor.kt:2461-2467`
- `nuget-processor/src/main/.../forward/ForwardMemberTypeParameters.kt` (`isForwardGenericMemberOwner`, `:118`)
- `nuget-processor/src/main/.../forward/ForwardKotlinPlanEmitter.kt` (value-class receiver re-wrap, phantom `<Nothing>` spelling, secondary-ctor body)
- `nuget-processor/src/main/.../forward/ForwardPropertyPlanner.kt` (member properties)
- `nuget-processor/src/main/.../forward/ForwardCirPlanProjection.kt` (`valueClassMethod`, `valueClassProperty`, `valueClassConstructor` block body with receiver box)
- `nuget-processor/src/main/.../cir/CirObjectRenderer.kt` (`renderReferenceValueClass` members + hoist), `cir/CirClassTranslator.kt:5093` (translate members for a generic struct), `cir/CirClassRenderer.kt` (`hoistDllImports` reuse)
- `nuget-processor/src/main/.../exports/ValueClassExports.kt:31-32`
- `nuget-processor/src/main/.../forward/ForwardDiagnostic.kt:1425-1432` (GENERIC hint text)
- Tier 1: `Tier1GenericValueClassTest` (flip the member skips to bindings), `Tier1ValueClassNullableResultTest` (`vcgeneric` `maybe`/`label`), a new cell for value-class member own type parameters
- Fixture: `test-library/.../stickers/Stickers.kt`; tests `IntegrationTests/GenericValueClassTests.cs`, `LeakTests/LiveHandleTests.cs`
- Docs: `docs/topics/value-classes.md:73-74` bullet, `docs/topics/supported-features.md`

## Sample test

Fixture additions:

```kotlin
value class Sticker<T>(val value: T) {
  init { require(value != -1) { "Mylo peeled this sticker already" } }
  constructor(label: String, times: Int) : this(label.repeat(times) as T)   // unchecked, fixture only
  val shiny: Boolean get() = value != null
  fun describe(): String = "sticker of $value"
  fun swap(other: T): Sticker<T> = Sticker(other)
  fun <R> pairWith(other: R): String = "$value+$other"
}

value class Decal<T>(val serial: Long) {
  constructor(text: String) : this(text.toLong())
  fun describe(): String = "decal #$serial"
}

value class Tag(val id: String) { fun <R> tagged(item: R): String = "$id:$item" }
```

```csharp
[Fact]
public void Member_OnAClosedInstantiation_ReadsTheReceiver()
{
    Assert.Equal("sticker of 3", new Sticker<int>(3).Describe());
    Assert.True(new Sticker<string?>("Oreo").Shiny);
    Assert.False(new Sticker<string?>(null).Shiny);
}

[Fact]
public void Member_TakingAndReturningTheClassTypeParameter_RoundTrips()
{
    using var mylo = new Cat("Mylo");
    Sticker<Cat> swapped = new Sticker<Cat>(new Cat("Oreo")).Swap(mylo);
    using Cat back = swapped.Value;
    Assert.Equal("Mylo", back.Name);
}

[Fact]
public void Member_RunsInitOnTheReceiver_InKotlin()
{
    Assert.Throws<KotlinArgumentException>(() => new Sticker<int>(-1).Describe());
}

[Fact]
public void SecondaryConstructor_RunsInKotlin_AndBuildsTheStruct()
{
    Assert.Equal("abab", new Sticker<string>("ab", 2).Value);
    Assert.Equal(42L, new Decal<Cat>("42").Serial);
    Assert.ThrowsAny<Exception>(() => new Decal<Cat>("not a number"));
}

[Fact]
public void MemberOwnTypeParameter_OnAValueClass_Binds()
{
    Assert.Equal("Oreo+7", new Sticker<string>("Oreo").PairWith(7));
    Assert.Equal("t1:Mylo", new Tag("t1").Tagged("Mylo"));
}

[Fact]
public void Member_OnADefaultStruct_ThrowsInCSharp_ForANonNullSlot()
{
    // `value class Firm<T : Any>(val value: T) { fun describe(): String }`
    Assert.Throws<ArgumentException>(() => default(Firm<string>).Describe());
}
```

Leak row (`LeakTests/LiveHandleTests.cs`):

```csharp
[Fact]
public void GenericValueClassMembers_ReceiverAndParameterBoxes_ReturnToBaseline()
{
    long before = NugetMarshal.LiveHandles;
    for (int i = 0; i < 100; i++)
    {
        _ = new Sticker<int>(i).Describe();          // receiver box minted and disposed per call
        _ = new Sticker<string>("x").Swap("y");       // receiver + parameter box + result box read back
        _ = new Sticker<string>("ab", 2);             // secondary constructor
    }
    Assert.Equal(before, NugetMarshal.LiveHandles);
}
```

## Deferred scope

None.

## Open what-questions

1. Should the struct carry the class's bound as a C# `where` clause once it has members (`Ranked<T> where T : IComparable<T>`)? Recommendation: no, keep ADR-220's constraint-free header; the bound is checked in Kotlin. Adding one is a breaking change to every existing `Sticker<T>` consumer if it is ever wanted.
2. A member named `Value` or `Deconstruct`, or a member property named like the positional one, collides with the record struct's synthesized members. Recommendation: the existing ADR-117 collision diagnostics apply; confirm in the Tier 1 cell rather than design new renaming.
3. A secondary constructor whose parameters do not mention an own-`T` underlying (`constructor(text: String) : this(text as T)`) can only produce `T` by an unchecked cast. Recommendation: bind it with the `Nothing`-free inferred spelling `pkg.Sticker<Any?>(text)` for an unconstrained `T` and the erased bound otherwise; it is the author's unchecked cast, not the bridge's.
