# ADR-212: Forward, a type parameter as a collection element: `fun <T> shuffle(list: List<T>): List<T>` as `IReadOnlyList<T> Shuffle<T>(IReadOnlyList<T> list)`

## Status
Accepted

Extends [ADR-147](147-generic-class-methods.md) (admission rule: "`T` nested anywhere: refused, not
designed here") and [ADR-197](197-method-type-parameters-on-the-callable-plan.md) (Declined: "a `T`
nested in another type (`List<T>`)"). Pairs with ADR-211, which moves a top-level `fun <T>` the
legacy width-variant route refuses onto the ADR-062 callable plan; this ADR is what lets such a
function carry `List<T>` once it is there. Issue #555.

## Context

A function's own `T`, and a class's own `T`, bind on the callable plan as a bare `T`/`T?` parameter
or return: one export, each `T` position crossing as a boxed handle through the generated
`NugetMarshal.Wrap<T>` and `FromHandle<T>` (ADR-147, ADR-197). The same `T` inside a `List`, `Set` or
`Map` is refused by name at four gates. **Verified by reading:**

- `forward/ForwardCallablePlanner.kt:4977-4981` (`isBridgeableComponent`, result and nested
  positions) and `:5253-5255` (`isWrappableComponent`, input positions) return `false` for
  `BridgeType.TypeParameter`, with the ADR-147 comment "a per-element box the write side has no arm
  for".
- `forward/ForwardPropertyPlanner.kt:1023` (`isReadableComponent`) returns `false` for it.
- `forward/ForwardMemberTypeParameters.kt:166-178` (`forwardMemberGenericRefusal`) returns "its type
  parameter is nested in `List<T>`" before planning for a member's own `T`, so the gates above are
  never consulted for a member; `Tier1MemberGenericMethodTest.kt:363,406` pins `fun <T> nested(values:
  List<T>)` as that refusal.
- `forward/ForwardKotlinPlanEmitter.kt:599` (`elementKotlinTypeName`) `error()`s on
  `TypeParameter`, which is why the gates must stay closed until the emitter has an arm.

The comment at the first gate is out of date. Since ADR-073/083/099 the per-element wire is already
element-generic on both halves, and `T` rides it with no new ABI. **Verified by reading:**

- C# write: `CreateList<T>(IEnumerable<T>)` loops `Wrap(value, out owned)` per element
  (`cir/CirMarshalRenderer.kt:491-506`, `:790-803`). `Wrap<T>` returns `IntPtr.Zero` for `null`,
  dispatches on `Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T)` for string and the thirteen
  primitive kinds, then the ADR-171 `Boxers` table, then the `_handle` fallback for a wrapper
  (`:413-450`). This is the same call a bare `T` parameter makes today, instantiated at the
  consumer's `T`.
- C# read: `FromHandle<T>(IntPtr)` returns `default!` on `IntPtr.Zero` (`:238`), then dispatches
  on the nullable underlying and the primitive kinds, then `Materialize<T>`. A collection read emits
  `NugetMarshal.FromHandle<$wireType>($handle)` per element (`forward/ForwardCirCollectionComponents.kt:139-140`),
  and `componentWireCsharpType` falls through to `csharpType(component)` for any component that is
  not a collection, bytes, throwable, enum or value class (`:184`), so for `T` the wire type is the
  public spelling `T` (`forward/ForwardCsharpTypes.kt:58`).
- Kotlin runtime: `nuget_list_add` stores the dereferenced object into a `MutableList<Any?>` and a
  null pointer as `null`; `nuget_list_get` returns `null` for a null element and a fresh
  `NugetHandles.retain` box otherwise (`nuget-runtime/.../NugetRuntime.kt:175-187`). Nothing in the
  runtime knows the element type.
- Kotlin lowering: an incoming list is `handle.asStableRef<MutableList<Any?>>().get().map { it as
  <element> }` (`forward/ForwardKotlinPlanEmitter.kt:2188-2200`, `:2219-2229`), and a bare `T`
  parameter is `pointer.asStableRef<Any>().get()` cast to each bound (`forwardBoundedRead`,
  `:1217-1240`). A member's Kotlin call already names an explicit type argument per own `T`: the
  first bound, `Any?` for an unconstrained one (`forward/ForwardMemberTypeParameters.kt:66-67`,
  `:83`).

Three pitfalls, each **verified by reading**:

1. An unconstrained `T` has upper bound `Any?`, so the classifier wraps it as
   `Nullable(TypeParameter(nullableFromBound = true))` (`forward/ForwardBridgeTypeClassifier.kt:172-176`,
   `:205-209`), which C# spells `T` with no `?` (`forward/ForwardCsharpTypes.kt:77-79`). The
   nullable-component read emits `h == IntPtr.Zero ? (${csharpType})null : FromHandle<...>(h)`
   (`forward/ForwardCirCollectionComponents.kt:103-108`), which for `T` is `(T)null`: CS0403 on an
   unconstrained `T`.
2. The same `Nullable` wrapper makes an unconstrained `K` in `Map<K, V>` a nullable map key, which
   `declinesNullableMapKey` refuses (`forward/ForwardCallablePlanner.kt:5122-5127`), and the C#
   `ReadMap<TKey, TValue>` carries `where TKey : notnull` (`forward/ForwardPropertyPlanner.kt:1583-1587`,
   CS8714 history).
3. A bounded `T` (`<T : Furry>`) must be a checked cast per element (ADR-015 amendment), not an
   unchecked `as T`, and a trampolined `T` (ADR-198, a bound with no closed spelling such as
   `Enum<T>`) is cast to a type variable only the trampoline declares.

Other targets: Kotlin/JVM emits `<T> List<T> shuffle(List<T>)` with erasure; ObjC export erases
`List<T>` to `NSArray<id>`; Swift export does not carry generics. C# consumers expect
`IReadOnlyList<T> Shuffle<T>(IReadOnlyList<T> list)` with `T` inferred from the argument, exactly
as ADR-011/099 spell a concrete element.

## Alternatives Considered

### 1. Admit `T` as a collection component on the existing boxed per-element wire (chosen)
Open the four gates for a non-trampolined `TypeParameter`, give the Kotlin component lowering the
same checked-cast chain a bare `T` uses, and make the C# nullable-component read for a `T` lean on
`FromHandle<T>`'s own null-pointer guard. No ABI change, no runtime change, no new helper: every
instantiation of `T` the bare position already round-trips (primitives, `string`, wrappers, enums
via `Boxers`) round-trips as an element. Cost: a primitive element boxes once per element, as a bare
primitive `T` already does.

### 2. Typed width variants per collection kind on the legacy route
`ShuffleInt(IReadOnlyList<int>)`, `ShuffleString(...)`, ... as the legacy top-level route does for a
bare `T`. Rejected: 13 exports per function per collection kind on a route ADR-197 and ADR-211 are
retiring, no inference, and nothing for a member.

### 3. Erase to `IReadOnlyList<object>`
Rejected: not a C# generic method, no inference, a cast at every consumer site, and a boxed `int`
arriving as `object` would still need the same `Wrap` dispatch.

### 4. Keep the named refusal
Zero cost; the consumer writes `fun shuffle(input: List<String>)` beside the generic. Rejected: the
issue's shape is the first generic a consumer writes, and the wire already carries it.

## Decision

Alternative 1.

```C#
// fun <T> shuffle(list: List<T>): List<T>           on a class, object, companion, sealed arm,
IReadOnlyList<int> ints = deck.Shuffle(new[] { 1, 2, 3 });        //   a class<T> member, or (ADR-211) top level
IReadOnlyList<string> names = deck.Shuffle(new List<string> { "Oreo", "Mylo" });
IReadOnlyList<Tabby> cats = deck.Shuffle(new[] { oreo, mylo });   // each returned element is yours to dispose
// fun <T> pad(list: List<T?>): List<T?>
IReadOnlyList<int?> padded = deck.Pad(new int?[] { 1, null });    // T inferred as int?, null element round-trips
// fun <T : Furry> groom(pets: List<T>): List<T>       where T : IFurry
```

### 1. The mapping

`List<T>`, `MutableList<T>`, `Set<T>`, `MutableSet<T>`, `Map<K, V>` and `MutableMap<K, V>` with the
function's own or the owner's own type parameter at the element, key or value slot bind at every
position the plan binds the same collection with a concrete component: a parameter, a return, a
`val` getter (read only here; *ADR-218 binds the `var` setter*), on a class, object, companion,
sealed arm, generic class member (ADR-147) and, through ADR-211, a top-level function. C# spelling
is the ADR-011 one, `IReadOnlyList<T>` / `IReadOnlySet<T>` / `IReadOnlyDictionary<K, V>`, with the
same `.AsReadOnly()` suffix. A nullable element `List<T?>` spells `IReadOnlyList<T?>`; an
unconstrained `T` spells `T` and still carries null on the wire (pitfall 1, below).

Nested collections of `T` (`List<List<T>>`, `Map<String, List<T>>`) fall out for free: every gate
and both lowerings recurse through `Collection` (ADR-099), so opening the leaf opens the nest. One
Tier 1 cell pins `List<List<T>>` so the free behaviour is a tested one, not an accident. **Inferred**
until that cell is green.

Mechanism, per half:

- **Gates.** `isBridgeableComponent`, `isWrappableComponent` and `isReadableComponent` return
  `true` for a `TypeParameter` whose `trampolined` is `false`. `forwardMemberGenericRefusal`'s
  "nested in" check stops refusing a `List`/`Set`/`Map` (and their mutable kinds) whose argument
  is the own `T` or a collection of it; every other nesting (`Box<T>`, `Result<List<T>>`, a lambda,
  a `Flow`) keeps its refusal text. ADR-147's class-`T` admission comment at
  `ForwardCallablePlanner.kt:4973-4976` is rewritten. **Inferred** (no spike): the three gates are
  the only predicates consulted for a collection component; ADR-099 listed them as such.
- **C# write.** Unchanged. `collectionCreateArgument` returns the parameter name untouched because
  `componentNeedsWireProjection()` is false for a `TypeParameter`
  (`ForwardCirCollectionComponents.kt:330-333`, verified), so the call is
  `NugetMarshal.CreateList(list)` with `T` inferred from `IReadOnlyList<T>`, and `Wrap<T>` runs its
  runtime dispatch per element.
- **C# read.** `componentReadExpression` already renders `NugetMarshal.FromHandle<T>(h)` for a
  non-null `T` component (verified). For a `Nullable` component whose inner is a `TypeParameter`,
  `collectionComponentRead` returns the plain expression with no local and no ternary: `FromHandle`
  returns `default!` on `IntPtr.Zero` (verified `:238`), which is `null` for every reference or
  `Nullable<V>` instantiation. The `(T)null` arm is never emitted for a `T`.
- **Kotlin read (parameter, setter).** `componentLowering`'s `TypeParameter` arm casts the `Any?`
  box element to the bound chain, mirroring `forwardBoundedRead`: `it as kotlin.Any?` for an
  unconstrained `T` (the `Nullable(nullableFromBound)` arm), `(it as Furry)` for one bound,
  `it.let { bounded -> bounded as Furry; bounded as Tricky; bounded }` for several, `?` on each for
  a nullable element. `elementKotlinTypeName` keeps its `error()` for `TypeParameter`; the arm does
  not go through it. The member call's explicit type argument (`shuffle<Any?>(...)`,
  `groom<Furry>(...)`) matches the lowered `List<Any?>` / `List<Furry>` (verified: the argument is
  the first bound or `Any?`, `ForwardMemberTypeParameters.kt:66-67,83`).
- **Kotlin write (return, getter).** Unchanged. `collectionResultProjection` leaves the invocation
  untouched because `componentNeedsProjection()` is false for a `TypeParameter`
  (`ForwardCirCollectionComponents.kt:314-322`, `ForwardKotlinPlanEmitter.kt:762-776`, verified);
  `nuget_list_get` boxes each element at read time.
- **Lifetime.** A passed wrapper element is borrowed (`Wrap<T>` reports `owned = false` for a
  `_handle`, the loop `GC.KeepAlive`s it, verified `:800`); a primitive or string element's box is
  disposed the instant `Add` returns. A returned wrapper element is minted by `nuget_list_get` and
  owned by the consumer, exactly as a `List<Cat>` return is today (ADR-011).
- **ABI contract.** No hand-listed route: the member is covered per declaration by
  `assertMatchesPlan` (ADR-147). **Inferred**.

### 2. The null-element read for an unconstrained `T`

Plain `NugetMarshal.FromHandle<T>(h)`, no ternary, no `default(T)` spelling. **Verified** that the
generated `FromHandle<T>` returns `default!` on `IntPtr.Zero` (`CirMarshalRenderer.kt:238`) before
any dispatch, so a null pointer reads as `null` for `T = string`, `Tabby` or `int?`, and the
`int` instantiation cannot see a null pointer (Kotlin cannot put `null` in a `List<Int>`). Rejected:
`h == IntPtr.Zero ? default(T) : FromHandle<T>(h)`, which compiles but duplicates the guard and
keeps the per-element local the nullable read exists to introduce.

### 3. A map key with an unconstrained `T`

Refuse, named, unless the key's type parameter is bounded non-null (`K : Any`, or any non-null
bound). Zero code: an unconstrained `K` classifies as `Nullable(TypeParameter)` (verified) and
`declinesNullableMapKey` already refuses it under `NULLABLE_MAP_KEY`; a `K : Any` classifies as a
bare `TypeParameter` and passes. The rule is ADR-083's: `Dictionary` throws on a null key and
`ReadMap`'s `where TKey : notnull` would warn CS8714 on an unconstrained `T`. One thing to check in
the Tier 1 cell: the refusal detail is `key type ${key.diagnosticTypeName()}`
(`ForwardCallablePlanner.kt:5116`); **inferred** that it prints `K` for a `nullableFromBound`
key, and the cell asserts the exact text. Rejected: exempting `nullableFromBound` keys, which
compiles only until a consumer passes a `Dictionary<string?, V>`'s null key and gets an
`ArgumentNullException` out of `ReadMap` on the way back.

### 4. A bounded `T`

Admitted, single- and multi-bound, through the same checked-cast chain `forwardBoundedRead` uses
(decision 1, "Kotlin read"). The C# `where` clause is unchanged (ADR-015, filled by
`withMethodTypeParameterConstraints`). A trampolined `T` (ADR-198, `trampolined = true`) stays
refused by name as "its type parameter `T` has no closed bound at a collection position": the
trampoline casts to a type variable only its own body declares, and a collection of it would need
the trampoline to carry a `List` parameter, which ADR-198 does not design. Rejected: deferring
bounded `T` altogether, which saves nothing (the chain exists) and leaves `fun <T : Furry>
groom(pets: List<T>)` refused for no wire reason.

### 5. The Kotlin element cast target

The first bound, chained to each remaining bound, `Any?` when unconstrained; never the type
variable `T` (an unchecked cast that ADR-015 replaced with the checked one) and never
`elementKotlinTypeName` (which has no spelling for a type parameter and should keep failing loudly
if a new call path reaches it). A generic bound (`Comparable<Any?>`) needs the export's
`@Suppress("UNCHECKED_CAST")` that `castsToGenericBound` already adds for a bare `T`
(`ForwardKotlinPlanEmitter.kt:1140-1152`, verified); **inferred** that its parameter scan must
also look inside collection components, otherwise the generated file carries a warning.

### 6. Leak harness

One new `LiveHandleTests` row, `MemberGeneric_CollectionElements_ReturnToBaseline`, next to
`GenericInstance_PassedBoxes_ReturnToBaseline` (`LeakTests/LiveHandleTests.cs:354`): pass a
`List<Tabby>` of three live wrappers through `Shuffle` (borrowed; only the caller's `using`
releases them), `using` each returned element (minted by `nuget_list_get`, released by its
Dispose), the same with `int` and `string` elements and with a null element in `Pad`, N times inside
`AssertNoLeak`. The `ThrowableList_ReadEveryElement_ReturnsToBaseline` row (`:1064`) is the model
for the returned-element half.

### 7. Sample tests

- Tier 1, `Tier1MemberGenericMethodTest`: `Depot.nested` moves from `declined` to bound; new cells
  for `fun <T> shuffle(list: List<T>): List<T>` (asserting `IReadOnlyList<T> Shuffle<T>(IReadOnlyList<T> list)`,
  the `CreateList(list)` call, the `FromHandle<T>` element read with no `(T)null`, and the Kotlin
  `map { it as kotlin.Any? }` lowering), `List<T?>`, `Set<T>`, `Map<K, V>` with `K : Any`, a
  bounded `List<T>` (`as Furry` per element), `List<List<T>>`, and three refusals: `Map<K, V>` with
  unconstrained `K`, a trampolined `T`, `Result<List<T>>` (still refused, ADR-197).
- Fixture, `test-library/.../membergeneric/Groomers.kt`: `fun <T> shuffle(list: List<T>): List<T>`,
  `fun <T> pad(list: List<T?>): List<T?>`, `fun <T : Furry> groom(pets: List<T>): List<T>`,
  `fun <K : Any, V> tally(map: Map<K, V>): Int` on `Groomer`, and one on `Crate<T>` for the class-`T`
  half.
- xunit, `IntegrationTests/MemberGenericMethodTests.cs`: `Shuffle` with `int`, `string`, `Tabby`
  (elements disposed) and `int?` with a null; `Groom` with a `Tabby` list; `Groom` with a list
  whose element is not a `Furry` through the erased `where` gap is not possible in C# (the
  constraint is spelled), so the checked-cast path is covered by `Portion`-style `uint` only where a
  builtin bound drops (ADR-015), one row.
- Top level, `ShuffleKt.Shuffle` Tier 1 cell and xunit row: contingent on ADR-211 landing first;
  written as a follow-up on that branch, not here.

## Consequences

- `Tier1MemberGenericMethodTest.kt:363,406` and the ADR-197 "nested in `List<T>`" refusal text
  change; the structural `GENERIC` skip no longer names a collection of `T`.
- `docs/topics/supported-features.md:90,93,94` and `docs/topics/generics.md:296-298,431-433`
  drop "`T` in a collection" from the refusal lists; `:499` changes with ADR-211.
- Deferred, each still refused by name: `T` in a lambda or `Flow`, `Result<List<T>>`, `T` inside a
  value class or `Box<T>`, a trampolined `T` in a collection, a `T` setter (`var items: List<T>`,
  dropped as ADR-147 drops a bare `T` setter). *Amended by [ADR-218](218-type-parameter-property-setter.md):
  the collection setters were already compiled by this ADR, and the bare `T` setter binds too.*
- The ADR-147 comment "a per-element box the write side has no arm for" was stale from ADR-083
  onward; this ADR replaces it with the real reason history.
- Fixed in the same change: a collection parameter named `list` or `map` beside a collection return
  produced CS0136 in the generated C#, because the result's handle local reused the parameter's name.
  The result handle now takes a trailing `_` on a collision; a Tier 1 `relist` cell pins it.
