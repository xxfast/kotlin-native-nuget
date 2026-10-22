# ADR-215: Forward, a type parameter inside a lambda or `Flow`: `fun <T> map(value: T, transform: (T) -> T): T` as `T Map<T>(T value, Func<T, T> transform)`

## Status
Proposed. Lane A (lambda) lands in this change; lanes B (`Flow` out) and C (asynchronous `T` into
Kotlin) follow in the same release.

Extends [ADR-212](212-type-parameter-as-collection-element.md) (Deferred: "`T` in a lambda or
`Flow`"), [ADR-147](147-generic-class-methods.md) (a generic class has no legacy route),
[ADR-160](160-callback-parameter-on-the-forward-plan.md) (per-call callback payload/result sets),
[ADR-199](199-generic-sealed-hierarchies.md) (`GENERIC_ARM_ERASED_LAMBDA`), [ADR-124](124-flow-route-sealed-arm-owners.md) (erased arm
receiver on the Flow route) and [ADR-210](210-flow-and-suspend-lambda-parameters.md) (Flow and
suspend lambda parameters, v1 primitive/`String` set). ADR file names checked against `docs/adr/`.

## Context

A function's own `T` and a class's own `T` bind as a bare `T`, `T?`, `Result<T>` and, since ADR-212,
as a `List`/`Set`/`Map` component. Inside a lambda or a `Flow` they are refused. **Verified by a Tier 1
spike** (scratch `Tier1SpikeTwoOneFiveTest` on detached `main` 5e001177, EXIT 0):

| Declaration | Today |
|---|---|
| `Deck.fun <T> each(items: List<T>, action: (T) -> Unit)` | `SKIPPED_UNSUPPORTED_COMBINATION ... (here, its signature carries a lambda or a Flow)` |
| `Deck.fun <T> map(value: T, transform: (T) -> T): T` | same, plus `a callback can return Unit, a primitive or a String, but transform: (T) -> T returns T` |
| `Deck.fun <T> stream(value: T): Flow<T>` | `a Flow-returning or suspend member can take ... but not value: T` |
| `Feed<T>.fun each(action: (T) -> Unit)` | `it is a callback member of a generic class, and the route ... cannot spell a generic receiver yet (ADR-147)` |
| `Feed<T>.fun stream(): Flow<T>` | `it is a Flow-returning member of a generic class ... (ADR-147)` |
| `Feed<T>.val updates: Flow<T>` (and `Flow<Int>`) | absent from C#, no diagnostic (ROADMAP line 237) |
| `Dish.Hot<T>.val items: Flow<T>` | binds: `KotlinFlow<T> Items`, Kotlin `asStableRef<Dish.Hot<Any?>>()`, `NugetHandles.retain(value as Any)` |
| `Deck.fun plain(action: (Int) -> Unit)` | binds on the ADR-160 plan: `Native_Plain(_handle, NugetThunks.NugetIntVoidCallbackPtr, actionCtx, out IntPtr error)` |

The gates, **verified by reading** (paths under `nuget-processor/.../processor/`):

- `forward/ForwardMemberTypeParameters.kt:158-173`: `carriesProtocol` refuses any lambda or Flow
  anywhere in a generic member's signature, whether or not it mentions `T`.
- `forward/ForwardBridgeTypeClassifier.kt:1140-1168` (`isCallbackPayload`) and `:1171-1190`
  (`isCallbackResult`) exclude `TypeParameter` and `Nullable`; the result set excludes every object.
- `forward/ForwardCirCallbackProjection.kt:194, 225, 260` (`error()` on `TypeParameter` for payload
  read, wire type, delegate segment) and `:126` (result lowering).
- `exports/ClassExports.kt:332-340`: a generic class takes no legacy route on either half, Flow
  and callback included; `forward/ForwardCallablePlanner.kt:1739-1797` names the drops.
- `forward/ForwardBridgeTypeClassifier.kt:1100-1133`: a Flow parameter element and a suspend lambda
  result are primitive or `String` only.

What the wire already does, **verified by reading**:

- A callback's C# delegate is a per-call closure registered with `NugetThunks.RegisterCtx`; the
  `[UnmanagedCallersOnly]` thunk is keyed to the WIRE name (`ForwardCirCallbackProjection.kt:71-140`).
  A closure built inside `Each<T>` captures `T`, so nothing generic has to be unmanaged-callable.
- An object payload crosses as `IntPtr`, segment `Object`, read `NugetMarshal.FromHandle<X>(slot)`
  (`:172-173`, `:207-209`, `:238`). Kotlin's default payload arm already emits
  `NugetHandles.retain($argument)` for a `TypeParameter` (`forward/ForwardKotlinPlanEmitter.kt:2125-2158`).
- `FromHandle<T>` returns `default!` on `IntPtr.Zero` and consumes each handle it reads
  (`cir/CirMarshalRenderer.kt:236-238`).
- `Wrap<T>(value, out owned)` returns a borrowed handle (`owned = false`) for an `INugetHandle`,
  an owned one for a minted box or bridge transfer (`cir/CirMarshalRenderer.kt:413-465`).
- The Flow route boxes each element erased and C# `KotlinFlow<T>` reads `FromHandle<T>`
  (`exports/FlowExports.kt:544-561`, `cir/CirFlowRenderer.kt:84,100`).
- `NugetAsyncEnumeration` is a non-generic base over `NugetAsyncEnumeration<T>`
  (`cir/CirCallbackRenderer.kt:568-597`); `NugetTasks.End<T>` casts a `Task<T>` (`:382-392`).
- The `nuget_*` runtime ABI is frozen (ADR-201, `docs/adr/201-throwable-beyond-the-property-getter.md:256`).

Pitfalls:

1. **No route hands an object from a C# lambda back to Kotlin.** `Wrap<T>`'s `owned` is known only to
   C#, and the callback thunk returns before Kotlin reads the box, so there is no C# "after the
   call" to dispose in, and a borrowed wrapper handle can be released by its finalizer between the
   thunk's return and Kotlin's read. The race is **inferred** (reasoned from SafeHandle
   finalization; not reproduced).
2. **An unconstrained `T` element is nullable without `?`.** The Flow route boxes `retain(value as
   Any)` when `isMarkedNullable` is false (`exports/FlowExports.kt:327`), which is the case for
   `T : Any?`, so `Hot<string?>` emitting `null` fails inside `collect`. **Inferred** (Kotlin cast
   semantics; not run). A latent bug on the shipped arm route.
3. **A nullable payload.** `Nullable(TypeParameter(nullableFromBound = true))` (ADR-212 pitfall 1)
   must retain-or-null on Kotlin's side; `retain` taking a non-null `Any` is **inferred**.

Other targets: Kotlin/JVM erases `(T) -> R` to `Function1<T, R>` and `Flow<T>` to `Flow<T>`; ObjC
export erases a block's `T` to `id`; Swift export does not carry generics (all **inferred** from
ADR-212's survey, not re-checked). C# consumers expect `Action<T>`, `Func<T, R>` and
`IAsyncEnumerable<T>` with `T` inferred from the call, which is how ADR-160 and ADR-026 already spell
the concrete cases.

## Alternatives Considered

### 1. `T` rides the `Object` wire inside every protocol, with an always-owned result transfer (chosen)
One rule for every protocol position: a `T` (or `T?`) component crosses as the boxed `IntPtr` the
bare `T` already uses. Kotlin to C#: Kotlin retains, C# `FromHandle<T>` consumes. C# to Kotlin: C#
hands an OWNED box, Kotlin reads, disposes and casts to the bound chain. No `nuget_*` change.

### 2. An `owned` out-slot on the result delegate
`IntPtr Callback(IntPtr a0, IntPtr ctx, IntPtr err, byte* owned)`; Kotlin disposes only when set.
Cheaper (no extra crossing for a wrapper result). Rejected: leaves pitfall 1's finalizer race on
the borrowed path, and every future object result inherits it.

### 3. A runtime `nuget_retain` export
Same semantics as the chosen per-library export, shared. Rejected: the runtime ABI is frozen
(ADR-201).

### 4. Two ADRs, lambda and Flow
Rejected: the boxing rule and the owned transfer are one decision; the Flow parameter and the
suspend lambda result (lane C) reuse lane A's transfer. Kept as separate LANES instead.

### 5. Width variants per instantiation, or erasing to `object`
Rejected for ADR-212's reasons (alternatives 2 and 3 there).

### 6. Keep the refusal
Rejected: `each`/`map`/`stream` are the first generic higher-order functions a Kotlin author
writes, and the arm route already proves the Flow half.

## Decision

Alternative 1, in three lanes.

```C#
// fun <T> each(items: List<T>, action: (T) -> Unit)
deck.Each(new[] { "Oreo", "Mylo" }, name => seen.Add(name));
deck.Each(new[] { oreo, mylo }, cat => { using (cat) { names.Add(cat.Name); } }); // payload is yours
// fun <T> map(value: T, transform: (T) -> T): T
int doubled = deck.Map(2, n => n * 2);
using Tabby same = deck.Map(oreo, cat => cat);
// fun <T> stream(value: T): Flow<T>
await foreach (int tick in deck.Stream(3)) { }
// class Feed<T>(seed: T) { val updates: Flow<T>; fun each(action: (T) -> Unit) }
using var feed = new Feed<string?>(null);
await foreach (string? item in feed.Updates) Assert.Null(item);
feed.Each(item => Assert.Null(item));
// fun <T> pour(source: Flow<T>): List<T>          (lane C)
IReadOnlyList<Tabby> poured = await deck.PourAsync(Cats());
```

### Lane A: lambda parameter on the plan (and an ADR-211 top-level returned lambda), this change

As built. Where this section differs from the draft, the red tests decided it (noted inline).

- **Gate.** `carriesProtocol` (`forward/ForwardMemberTypeParameters.kt`) no longer counts a
  non-suspend lambda as a protocol, with or without `T`; its components are still walked, so a
  suspend lambda or a `Flow` anywhere keeps the refusal ("its signature carries a suspend lambda
  or a Flow") until lanes B and C. `carriesOwn` admits a non-suspend lambda whose components carry
  `T`, so `(T) -> Unit` is not refused as "nested". Verified (Tier 1 `Tier1GenericLambdaTest`).
- **Admission.** `callbackType` admits a `TypeParameter` or `Nullable(TypeParameter)` component,
  payload or result, through a separate `isBoxedTypeParameter` check. `isCallbackPayload` and
  `isCallbackResult` themselves are **not** widened (the draft said they were): the interface
  bridge listener and the suspend lambda read those sets, and widening them would have opened
  lane C's suspend payload and a generic listener member without their halves. An explicit `T?`
  under a non-null bound (`fun <T : Any> peek(seat: T?, look: (T?) -> Unit)`) and an unconstrained
  `T` (`Nullable(TypeParameter(nullableFromBound = true))`) both classify. A trampolined `T`
  (ADR-198, `fun <T : Enum<T>> restyle(tint: T, step: (T) -> T)`) is admitted too (Item 3
  widening; the draft refused it).
- **Payload, Kotlin to C#.** Wire `IntPtr`, segment `Object`, so `(T) -> Unit` shares
  `NugetObjectVoidCallback` with `(Cat) -> Unit`; C# reads `NugetMarshal.FromHandle<T>(a0)` inside
  the closure. Kotlin retains `NugetHandles.retain(arg)`, or `arg?.let { NugetHandles.retain(it) }`
  for a nullable `T`, so a null `T` mints nothing. Ownership is ADR-036's: the consumer owns a
  wrapper payload. Verified by Tier 1 (generated Kotlin compiles) and the xunit and leak rows.
- **Result, C# to Kotlin.** The closure returns `NugetMarshal.WrapOwned<T>(call)`:
  `Wrap(value, out owned)`; when not owned (a borrowed `INugetHandle`) the handle is replaced by
  `Native_retain_handle(handle)`; then `GC.KeepAlive(value)`. `Native_retain_handle` binds the
  generated per-library export `<lib>__retain_handle(handle: COpaquePointer): COpaquePointer`
  (`NugetHandles.retain(handle.asStableRef<Any>().get())`), ADR-163 library stem, no package
  segment. Both halves are emitted only when some plan carries a `T` callback result
  (`objectCallbackResultOwner`, `forward/ForwardRetainHandle.kt`); that plan is the export's
  ADR-117 owner. Kotlin reads the box (`nugetCallbackCall { ... }!!` for a non-null `T`), casts it
  through `forwardBoundedRead` (the ADR-212 bound chain, or ADR-198's trampoline cast), releases
  the box with `NugetHandles.release`, and returns the value: exactly one box disposed per result.
- **ABI contract.** The retain import is rendered as text in `NugetMarshal`, so it is not a plan
  node and `assertMatchesPlan` does not see it (the draft inferred it would). It is covered by
  `ForwardAbiContract.assertMatches` over the `csharpLegacy` scrape against the Kotlin `@CName`
  set, like ADR-208's flow-argument exports; a Tier 1 cell pins the scraped signature.
- **Generic class and arm.** `Serving<T>.each(action: (T) -> Unit)` and the ADR-199 arm member
  `Helping.Seconds<T>.each` bind on the plan once the payload classifies, over the erased receiver
  the plan already spells (`asStableRef<Serving<Any?>>()`). Verified: no
  `LambdaParameterExports` change was needed.
- **Stored callbacks and interface-bridge pairs** (ADR-036 add/remove) keep their legacy route
  and their refusal for a `T` payload. So `GENERIC_ARM_ERASED_LAMBDA` is **not** deleted (the
  draft said it was): an arm's `addTaste(listener: (T) -> Unit)` pair still takes it.
- **ReturnedLambda** (ADR-211 top-level `fun <T> echoer(): (T) -> T`): binds as
  `KotlinFunc<T, T> Echoer<T>()` once `isLambdaTypeArgument` admits a `TypeParameter`; `KotlinFunc`
  already `Wrap`s its argument and `FromHandle`s its result. Verified by Tier 1 and xunit.

### Lane B: Flow out of Kotlin (follows in the same release)

- A generic class takes the arm's erased receiver on the Flow route on both halves
  (`asStableRef<Feed<Any?>>()`, first bound, `<*>` for multi-bound, exactly ADR-124/199's arm
  spelling, verified on arms by `Tier1GenericSealedFlowPropertyTest.kt:130-160`). This binds
  `Feed<T>.updates`, `Feed<T>.stream()` and closes ROADMAP line 237's silent drop.
- A method's own `T` on the Flow route: a bare-`T` parameter shape on the legacy route
  (`asStableRef<Any>().get()` cast to the bound, as `forwardBoundedRead`; C# `Wrap<T>` plus owned
  dispose) and a method type-parameter list with `where` clauses on the C# Flow method.
  `carriesProtocol` stops refusing a Flow return.
- A `nullableFromBound` element boxes through `itemBoxExpr`'s null-guarded arm (pitfall 2), on arms
  too. C# `KotlinFlow<T>`'s `FromHandle<T>` already reads `IntPtr.Zero` as `default!`.
- `StateFlow<T>`/`SharedFlow<T>` returns of a generic member follow the same receiver spelling;
  `MutableStateFlow<T>`'s write seam with a `T` value is **inferred** to need the `WrapOwned`
  transfer of lane A (C# to Kotlin) and lands with it.

### Lane C: `T` flowing into Kotlin asynchronously (after lane A, follows in the same release)

- `Flow<T>` parameter: `flowSourceOrNull` admits a `T` element; the abstract base gains
  `IntPtr CurrentBoxed()`, implemented in `NugetAsyncEnumeration<T>` as
  `NugetMarshal.WrapOwned(Enumerator.Current)`; a shared `NugetAsyncObjectCurrent` thunk calls it.
  Boxing on the read keeps ADR-210 alternative 8 (nothing minted for an element nobody reads).
- `suspend (T) -> T` result: the Begin registration stores a `NugetBoxedTask<T>` (non-generic base,
  `IntPtr EndBoxed()`) in place of the bare `Task<T>`; a shared `NugetAsyncObjectEnd` calls it.
- `suspend (T) -> Unit` payload: free with lane A's payload admission.
- Kotlin reads each box exactly as lane A's result. All **inferred** (designed, not built).

### Leak harness

Four `LiveHandleTests` rows next to `MemberGeneric_CollectionElements_ReturnToBaseline`
(`LeakTests/LiveHandleTests.cs:3586`) and row 13 (`:4374`, the 5000-iteration per-invocation model):
`MemberGeneric_LambdaPayload_ReturnToBaseline` (int, string, `Tabby` with `using`, `string?` null),
`MemberGeneric_LambdaResult_ReturnToBaseline` (int, string, borrowed `Tabby` through the retain
export, a fresh `Tabby` created in the lambda and dropped, null), `GenericClass_FlowElements_ReturnToBaseline`
(`Feed<Tabby>`, `Feed<string?>`), `MemberGeneric_FlowParameterAndSuspendResult_ReturnToBaseline`
(lane C, including a cancellation half-way).

### Sample tests

- Tier 1, lane A (`Tier1GenericLambdaTest`): `Dealer.each` as
  `public void Each<T>(IReadOnlyList<T> items, Action<T> action)` with `FromHandle<T>(a0)` in the
  closure; `map`, `fold`, `rename`, `peek`, `fill`, `restyle` (trampolined), `deal` (a lambda
  without `T`); `Serving<T>.Each(Action<T>)` and `Helping.Seconds<T>.Each(Action<T>)`; the retain
  export on both halves only when a plan carries a `T` result; `Echoer<T>()` as `KotlinFunc<T, T>`.
- Tier 1, lane B: `Feed<T>.Updates` as `KotlinFlow<T>` over `asStableRef<Feed<Any?>>()`;
  `Hot<T>.items` boxes null-guarded.
- Fixture `test-library/.../membergeneric/`: lane A's `Decks.kt` (`Dealer`, top-level `echoer`) and
  `Feeds.kt` (`Serving<T>`, `Helping<out T>.Seconds<T>`); xunit `MemberGenericLambdaTests.cs`.

## Consequences

- Lane A: `Tier1MemberGenericMethodTest` (its protocol cell is now a suspend lambda),
  `Tier1GenericOwnerLegacyRouteTest` and `Tier1GenericSealedArmNamedSkipTest` (the per-call `T`
  callback binds; the stored pair keeps `GENERIC_ARM_ERASED_LAMBDA`). Lane B:
  `Tier1GenericSealedFlowPropertyTest` (null element) and the ROADMAP line 237 DEFECT cell.
- A new per-library export `<lib>__retain_handle`, generated, outside the frozen `nuget_*` ABI.
- ADR-160's "an object LAMBDA return stays a SpecializedProtocol" is narrowed: the `WrapOwned`
  transfer is the mechanism a later concrete object lambda result (`() -> Cat`) can reuse; this ADR
  admits only `T`.
- A trampolined `T` (ADR-198) in a lambda binds (a widening over the draft).
- Still refused, by name: a `T` payload of a stored callback pair (`GENERIC_ARM_ERASED_LAMBDA` on an
  arm, ADR-147 on a generic class), a `var` flow property setter (ROADMAP line 232), a
  `StateFlow`/`SharedFlow` parameter (ADR-210).
- Deferred: nothing beyond those pre-existing refusals.
