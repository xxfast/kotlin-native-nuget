# ADR-216: Forward, a function's own `T` inside `Result<...>` or a generic class: `fun <T> wrap(value: T): Box<T>` as `Box<T> Wrap<T>(T value)`

## Status
Accepted

Extends [ADR-212](212-type-parameter-as-collection-element.md) (Consequences: "`Result<List<T>>`,
`T` inside a value class or `Box<T>`" deferred), [ADR-197](197-method-type-parameters-on-the-callable-plan.md)
(Declined: "`Result<List<T>>` and a `T` nested in another type") and
[ADR-208](208-generic-instantiation-member-positions.md) (an open `Box<T>` binds inside a generic
owner). Pairs with ADR-211 and ADR-213 (top-level `fun <T>` on the plan). ROADMAP Phase 4,
`ROADMAP.md:61`.

## Context

A class's own `T` inside `Box<T>` or `Result<List<T>>` already binds. On unmodified main,
`class Crate<T> { fun rebox(): Box<T>; fun listed(): Result<List<T>> }` renders `public Box<T>
Rebox()`, `public IReadOnlyList<T> Listed()` and the ADR-195 `TryListed`, and a consumer calling
them compiles. **Verified by spike** (Tier 1 with a real `dotnet build`, 2026-10-22).

A **function's** own `T` in the same shapes is refused by exactly one gate,
`forwardMemberGenericRefusal` (`forward/ForwardMemberTypeParameters.kt:108-199`). **Verified by
reading and by spike**:

- `carriesOwn` (`:151-156`) admits only `List`/`Set`/`Map` and their mutable kinds
  (`OWN_PARAMETER_COLLECTIONS`, `:202-206`), so `Box<T>` at a parameter (`:174-178`) or return
  (`:179-184`) is "its type parameter is nested in `Box<T>`".
- The `Result` exemption (`:180-183`) takes the payload only when it is the bare own `T`
  (`ownParameter()`), so `Result<List<T>>` and `Result<Box<T>>` are refused.

On main, all 19 own-`T` shapes in the probe were skipped `SKIPPED_UNSUPPORTED_COMBINATION`.
**Verified by spike.**

Behind that gate the rest of the pipeline already handles the shapes:

- `genericReference` (`forward/ForwardBridgeTypeClassifier.kt:700-830`) admits a
  `TypeParameter` argument (`isErasedTypeArgument`, `forward/ForwardGenericSealed.kt:174-177`),
  marks the instantiation open so it mints no `Factories` line (`:781`), and spells
  `global::Ns.Box<T>`.
- Its Kotlin read type spells the argument as its first bound or `Any?`
  (`forward/ForwardGenericSealed.kt:205-213`). That is the same type argument ADR-197 passes to the
  call (`ForwardMemberTypeParameters.kt:84`).
- The `Result` route (ADR-108, ADR-195) is payload-generic: a `List<T>` payload rides ADR-212's
  per-element wire.

**Verified by spike** (below).

The value-class half is a different problem. A generic value class has no C# type at any
instantiation: `value class Box<T>` is skipped whole (`Tier1ValueClassNullableResultTest.kt:201-236`)
and `Crate<Int>` of one is absent from both halves (`Tier1GenericInstanceMemberPositionTest.kt:478-491`,
the ADR-208 2026-10-10 amendment). **Verified by reading.** A `T` "inside a value class" can only be
`Tag<T>` of a generic value class, which needs a generic value class mapping first.

Other targets: Kotlin/JVM emits `<T> Box<T> wrap(T)` with erasure; ObjC export erases the argument
(`Box<id>`); Swift export does not carry generics. A C# developer expects `Box<int> b =
depot.Wrap(3)` with `T` inferred, and `depot.Unwrap(b)` back.

## Alternatives Considered

### 1. Widen the one gate (chosen)
`carriesOwn` admits an exported generic class whose arguments carry the own `T`, and the `Result`
exemption takes any payload that `carriesOwn()`. No classifier, emitter, ABI or runtime change. Cost:
a multi-bound `T` witnessed only inside a generic class must be refused explicitly (decision 3), and
`kotlin.Array` must be excluded (decision 2).

### 2. Also route multi-bound-in-`Box` through the ADR-198 trampoline
`fun <T> nugetTrampoline() where T : A, T : B` casting `box as Box<T>`. It works on paper, but it is
the same extension (a trampoline carrying a non-bare `T` position) that ADR-217 designs for a
trampolined `T` in a collection. Left to that lane, so the trampoline gets designed once.

### 3. One ADR per shape (`Result` vs `Box`)
Rejected: both shapes are the same predicate, and the spike shows one edit opens both.

### 4. Bind a `T` inside a generic value class here
Rejected: that is a new mapping (a generic `readonly record struct`, a box/unbox pair per
instantiation, `Factories` lines), not a routing rule. It gets its own ROADMAP line and ADR.

## Decision

Alternative 1.

```C#
// on class Groomer, an object, a companion, a sealed arm, a class<T> member, or (ADR-211) top level
using Basket<int> b = groomer.Basket(3);                     // fun <T> basket(item: T): Basket<T>
int three = groomer.Unpack(b);                               // fun <T> unpack(basket: Basket<T>): T
using Tabby back = groomer.BrushBasket(cat);                 // fun <T : Furry> brushBasket(basket: Basket<T>): T  where T : IFurry
IReadOnlyList<string> names = groomer.Gather(list);          // fun <T> gather(items: List<T>): Result<List<T>>
bool ok = groomer.TryGather(list, out var value, out var failure);
```

### 1. The mapping

A function's own type parameter binds inside:

- an exported generic class (`Box<T>`, `Box<T>?`, `Box<Box<T>>`, `Pair2<T, String>`, a generic
  sealed `Outcome<T>`, `List<Box<T>>`), at a parameter and a return;
- `Result<P>` at a return, for every payload `P` the same gate admits (`List<T>`, `List<T?>`,
  `Set<T>`, `Map<K, V>` with `K : Any`, `List<List<T>>`, `Box<T>`). It gets ADR-195's `Try` twin.

It binds on every owner ADR-197 and ADR-211 admit. C# spells each one exactly as the class-`T` route
already does.

**Verified by spike** (scratch worktree, `ForwardMemberTypeParameters.kt` edited only;
`./gradlew :nuget-processor:test --tests '*Tier1Spike216Test*'`, 2026-10-22). The harness reported
`kspErrors=[] compileErrors=[]`, and `Tier1CSharpCompile` reported `succeeded=true` for a consumer
calling every shape. It emitted:

```
public global::TestLibrary.Spike.Box<T> Wrap<T>(T value)
public T Unwrap<T>(global::TestLibrary.Spike.Box<T> box)
public T Groom<T>(global::TestLibrary.Spike.Box<T> box) where T : global::TestLibrary.Spike.IFurry
public global::TestLibrary.Spike.Box<T>? Maybe<T>(global::TestLibrary.Spike.Box<T>? box)
public global::TestLibrary.Spike.Box<T> Nest<T>(global::TestLibrary.Spike.Box<global::TestLibrary.Spike.Box<T>> box)
public IReadOnlyList<global::TestLibrary.Spike.Box<T>> Boxes<T>(IReadOnlyList<global::TestLibrary.Spike.Box<T>> list)
public IReadOnlyList<T> Listed<T>(IReadOnlyList<T> list)
public bool TryListed<T>(IReadOnlyList<T> list, [MaybeNullWhen(false)] out IReadOnlyList<T> value, [NotNullWhen(false)] out global::System.Exception? failure)
public IReadOnlyDictionary<K, V> Tally<K, V>(IReadOnlyDictionary<K, V> map) where K : notnull
public global::TestLibrary.Spike.Outcome<T> Settle<T>(T value)
public static T UnwrapTop<T>(global::TestLibrary.Spike.Box<T> box)
```

```kotlin
NugetHandles.retain(handle.asStableRef<tier1.spike.Depot>().get().wrap<Any?>(value_?.asStableRef<Any>()?.get()))
val result = handle.asStableRef<tier1.spike.Depot>().get().unwrap<Any?>(box.asStableRef<tier1.spike.Box<Any?>>().get())
NugetHandles.retain(handle.asStableRef<tier1.spike.Depot>().get().groom<tier1.spike.Furry>(box.asStableRef<tier1.spike.Box<tier1.spike.Furry>>().get()))
NugetHandles.retain(handle.asStableRef<tier1.spike.Depot>().get().listed<Any?>(list.asStableRef<MutableList<Any?>>().get().map { it as kotlin.Any? }).also { nugetResult -> ... nugetResult.isFailure }.getOrThrow())
```

**Inferred** (Tier 1 proves compile, not run): the `Box<Any?>` reinterpret round-trips an `int`
through `Box<int>.Value`, and a failed `Result<List<T>>` reaches `TryListed` as `false`. Both rest
on the class-`T` route (ADR-147/208, ADR-195) shipping the same wire. The xunit rows in decision 5
settle it.

### 2. The admission rule

`carriesOwn` additionally admits a type whose declaration meets all of these:

- it is a `KSClassDeclaration` with type parameters;
- its `classKind == CLASS`;
- it is not a value class;
- its package is not `kotlin` or a `kotlin.` subpackage;
- every argument is the own `T`, a type that does not mention it, or one that `carriesOwn()` again.

The `Result` exemption becomes `resultPayload?.carriesOwn() == true`. Once a type is admitted, the
classifier's `genericReference` owns every further refusal: a projection, an unexported class
(ADR-208 names it with its remedy), and an argument the erased wire cannot read (`Box<List<T>>`
stays `SKIPPED_UNSUPPORTED_TYPE`, as `Box<List<Int>>` does).

The package exclusion is required. **Verified by spike**: without it, `fun <T> sort(values:
Array<T>)` left the `GENERIC` combination skip for a `SKIPPED_UNSUPPORTED_TYPE` "kotlin.Array"
skip, and `Tier1SealedArmResidualSkipTest` failed.

### 3. A multi-bound `T` witnessed only inside a generic class

Refused by name, with its own text: "its multi-bound type parameter `T` is witnessed only inside
`Box<T>`, which ADR-217 lifts". The witness check in `forwardMemberGenericRefusal` counts only a
bare `T`, or an ADR-212 collection of it, as a witness. A generic-class argument no longer counts,
and a `T` that some parameter mentions this way is told apart from one no parameter mentions
(which keeps "is mentioned by no parameter"). ADR-217 lifts it in the same release, so no ROADMAP
line carries it.

(Amended from the draft, which reused the "mentioned by no parameter" text. That text is false for
`rank(box: Box<T>)`: a parameter does mention `T`.)

**Verified by spike** that admitting it breaks the generated Kotlin. `fun <T> rank(box: Box<T>): T
where T : Furry, T : Tricky` emitted `rank<_>(box.asStableRef<tier1.spike.Box<tier1.spike.Furry>>().get())`
and failed with "Argument type mismatch: actual type is 'Box<Furry>', but 'Box<uninferred T (of fun
<T : Furry, Tricky> rank)>' was expected." A `List<T>` witness works only because each element
smart-casts to every bound. Lifting this is ADR-217's trampoline work (Alternative 2).

### 4. `Box<T?>` under a non-null bound

Admitted, with ADR-197's bare-`T?` semantics. `fun <T : Any> opt(box: Box<T?>): T?` spells `T?
Opt<T>(Box<T?> box) where T : notnull`. For a value-type `T`, C# reads `T?` as an annotation, so
`Opt<int>` takes a `Box<int>`. **Verified by spike**: a `Box<int?>` argument is CS1503.

**Inferred**: a Kotlin `null` inside such a box reads as `default` for `T = int`. The reasoning is
that `FromHandle<T>` returns `default!` on `IntPtr.Zero`, the same as bare `public T? Make<T>()`
today. One Tier 1 cell pins the spelling.

### 5. Tests

- **Tier 1, `Tier1MemberGenericMethodTest`.** `Deck.gather` moves from declined to bound (with
  `TryGather`). `Depot.gather` leaves the declined fixture; in its place `Depot.rank` (multi-bound
  in `Crate<T>`, named per decision 3) and `Depot.sort` (`Array<T>` keeping its `GENERIC` text).
- **Tier 1, `Tier1OwnTypeParameterInGenericClassTest`** (new). The signatures above, a top-level
  `fun <T> unwrapTop(box: Box<T>): T`, the `Box<Any?>` and `Box<Furry>` Kotlin reads,
  `Result<Map<K, V>>` with `K : Any`, `Result<List<List<T>>>`, `Box<T?>` (decision 4), the
  multi-bound refusal, `Box<List<T>>` keeping its `SKIPPED_UNSUPPORTED_TYPE` skip, and a consumer
  that compiles against all of it.
- **Tier 1, `Tier1UnroutedPositionsTest`.** Row 16's sentinel `structuralRefusedOnTopLevel():
  Result<List<T>>` binds now (**verified by spike**: three cells failed). It is replaced by a durable
  `GENERIC` refusal, `fun <T, U : T> structuralRefusedOnTopLevel(item: U): T`.
- **Fixture, `membergeneric/Groomers.kt`.** On `Groomer`: `basket`, `unpack`, `brushBasket`
  (`T : Furry`), `maybeBasket`, `nestBasket`, `doubleBasket`, `baskets`, `unpackAll`, `pickUp` (a
  generic sealed `Pickup<out T>`), `gather`, `spill` (a `Result.failure`), `queue`
  (`Result<List<T?>>`), `herd`, `ledger`, `litters` and `stash` (`Result<Basket<T>>`). On every
  other owner: `Groomer.firstBasket` (companion), `Salon.exhibit` (object), `Chore.Fetch.stow`
  (sealed arm) and `Basket.regift` (a class-`T` owner).
- **xunit, `MemberGenericMethodTests.cs`.** `int` and `Tabby` baskets, `Unpack`, `BrushBasket`,
  `Gather` over `string` and `Tabby`, and `TrySpill` returning `false` with the message.
- **Leak rows, `LeakTests/LiveHandleTests.cs`**, at the end of the class under an `// ADR-216`
  banner:
  - `MemberGeneric_BoxedTypeParameter_ReturnsToBaseline`: a returned basket is owned and disposed,
    a passed basket is borrowed, and `Basket(Basket(1))` disposes both.
  - `MemberGeneric_ResultListOfT_ReturnsToBaseline`: `Tabby` elements are disposed, and
    `TrySpill`'s error handle is released.

### 6. Ownership

Identical to ADR-208 and ADR-212:

- A returned `Box<T>` is a fresh `StableRef` the consumer disposes.
- A passed `Box<T>` is a borrow: `box._handle` crosses and Kotlin releases nothing.
- A `Result<List<T>>` element is minted per read by `nuget_list_get`.

**Inferred** until the leak rows are green.

## Consequences

- The `GENERIC` sentence and hint (`forward/ForwardDiagnostic.kt`) said a function's own `T` binds
  only "as a bare `T` or `T?` parameter or return", stale since ADR-212. The sentence now reads
  "a function's own type parameter binds on a top-level function, including a `suspend` one, and
  on a class, object, companion or sealed-class member: as a bare `T` or `T?`, inside a `List`,
  `Set` or `Map`, inside an exported generic class or a `Result` payload, and inside a lambda or
  `Flow`", the text the Phase 4 lanes share (the `suspend` and lambda/`Flow` clauses are ADR-214's
  and ADR-215's). The hint lists what is still refused: a `T` bounded by another type parameter, a
  `reified` member `T`, a generic value class. The `forwardMemberGenericRefusal` KDoc changes with
  them.
- `Tier1UnroutedPositionsTest` row 16's sentinel becomes `fun <T, U : T>
  structuralRefusedOnTopLevel(item: U): T` (and its twin in `unrouted/UnroutedPositionsSample.kt`),
  a durable `GENERIC` refusal no Phase 4 lane opens.
- `docs/topics/supported-features.md:96` and `docs/topics/generics.md:594,641` drop `Box<T>` and
  `Result<List<T>>` from the refusal lists.
- No ABI or Kotlin emitter change. An open instantiation still mints no `Factories` line
  (`ForwardBridgeTypeClassifier.kt`), but the C# side changes: every concrete generic class now
  fills the ADR-199 `NugetFactory<T>` slot, and a member whose result nests an open instantiation
  fills it too (see the amendment below).
- ADR-213 moves the legacy `fun <T> wrapInBox(value: T): Box<T>` onto the plan. There it is this
  ADR's `Box<T>` return, so the two are order-independent at the gate.
- Deferred, each named:
  - **A `T` inside a generic value class.** No generic value class has a C# type yet. ADR-220
    builds that mapping in a later PR of the same stack, so no ROADMAP line carries it.
  - **A multi-bound `T` witnessed only inside a generic class.** Refused by name until ADR-217
    lifts it, in the same release.

## Amendment (2026-10-22): a factory for an open instantiation

The draft's "no `Factories` change" was wrong. The xunit rows proved it: `Basket<Basket<int>>.Item`
(after `DoubleBasket(1)`) and a `List<Basket<int>>` element (`Baskets(...)`) threw
`NotSupportedException: No generated factory materialises Basket<int> from a Kotlin handle`, in
`NugetMarshal.Materialize<T>`. **Verified** by `scripts/verify.sh` (two IntegrationTests rows and
one LeakTests row red). An open instantiation mints no `Factories` line, and only an ADR-199 generic
sealed hierarchy had a `NugetFactory<T>` slot. The same read was already broken on `main` for a
class `T`: `Basket<T>.rebasket(): Basket<Basket<T>>`, then `.Item`. That case is inferred from
the same mechanism, not run on `main`; this lane pins it.

The fix has two parts, both without reflection:

1. **The ADR-199 slot, on every generic class.** `CirClassRenderer` gives each concrete generic
   class the static constructor a generic sealed base already has:
   `static Basket() { NugetFactory<Basket<T>>.Create = static handle => new Basket<T>(handle,
   out _); }`. `includesFactorySlot` is true when any generic class or generic sealed hierarchy
   exists. ADR-199 already verified this mechanism under NativeAOT.
2. **A fill at the member, for an instantiation the consumer never touched.** A static
   constructor runs only once the consumer has used that instantiation, and both red rows read a
   `Basket<int>` C# never held. So a member whose result nests an open exported generic class
   (`Basket<T>` inside `Basket<Basket<T>>`, a `List<Basket<T>>` element, at any depth of the
   result's own type arguments) starts its body with
   `NugetFactory<Basket<T>>.Create ??= static handle => new Basket<T>(handle, out _);`. The
   classifier records these as `BridgeType.ObjectHandle.openArguments`, and
   `ForwardCirPlanProjection` emits the fills. A bare `Basket<T>` return needs none: it is
   constructed directly.

Rejected: `RuntimeHelpers.RunClassConstructor` (reflection, not verified under NativeAOT, against
ADR-199), and constructing only the list element directly (it leaves `.Item` broken).

Fixed in this lane: on `main`, a class-`T` member returning `Basket<Basket<T>>` (`Basket<T>.rebasket()`)
threw `NotSupportedException` from `Materialize` when the consumer read `.Item`. It is pinned by
`BoxedTypeParameter_ClassTypeParameterRebasket_ReadsTheInnerBasket`. **Verified** red before the
fix and green after it by `scripts/verify.sh`; no ROADMAP line.

## Amendment (2026-10-22): the fill follows the instantiation to every position

An open exported generic instantiation nested at any depth (`Basket<Basket<T>>`, `List<Basket<T>>`,
`Map<String, Basket<T>>`, `Basket<Pickup<T>>`) now has its `NugetFactory<X>` slot filled at every
position a consumer can first meet it, not only a member's result: property getters and setters,
parameters, collection elements and map values, callback arguments, `Flow` elements, returned
lambdas, `suspend` results and `Result` payloads. This replaces the residual gap above.

- **One collector.** `BridgeType.openSlots()` walks every variant (`Nullable`, `Collection`,
  `Callback`, `AsyncCallback`, `FlowSource`, `ReturnedLambda`, `SpecializedProtocol`) and lists each
  open handle with its factory spelling. Every member body that can bring a Kotlin value to C#
  starts with the fills for its whole signature, deduplicated by C# type.
- **Sealed and abstract.** A generic sealed type nested in an instantiation fills with
  `X.FromHandle(handle)`. An abstract generic class now gets its own static constructor and fills by
  constructing its backing class, so `Basket<Cabinet<T>>` reads even before the consumer holds a
  `Cabinet<int>`.
- **The top-level-handle rule.** A handle the member constructs directly as its own result (a
  bare `Basket<Basket<int>>` getter) fills only what it nests, because its own static constructor
  fills the outer slot. A callback argument, `Flow` element, returned lambda, collection or map
  fills the outer instantiation as well, since nothing constructs it directly.
- **No silent drop.** A member whose signature needs fills always renders a body that carries
  them.

Ownership is unchanged: a fill stores a factory, mints no handle and runs no P/Invoke. Pinned by
`ForwardOpenSlotsTest`, `Tier1OpenSlotFillTest`, `OpenSlotFillTests` and the leak row
`OpenSlot_NestedThroughGetter_ReturnsToBaseline`.
