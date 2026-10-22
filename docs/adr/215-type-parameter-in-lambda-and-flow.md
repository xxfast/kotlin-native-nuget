# ADR-215: Forward, a type parameter inside a lambda or `Flow`: `fun <T> map(value: T, transform: (T) -> T): T` as `T Map<T>(T value, Func<T, T> transform)`

## Status
Accepted. Lanes A (lambda), B (`Flow` out of Kotlin) and C (asynchronous `T` into Kotlin) have
landed.

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

### Lane B: Flow out of Kotlin (this change)

Decisions, as built:

1. **A plain generic class takes the legacy Flow route over its erased receiver**, on both halves:
   `forwardOwnerTypeName()` (`asStableRef<Brook<Any?>>()`, `Pride<*>` for a multi-bound owner
   parameter), the spelling a generic sealed arm's flow members already read (ADR-124/199). This
   closes ROADMAP's "a generic class flow property is dropped whole with no diagnostic" line; the
   old `Tier1FlowVarPropertySetterTest` DEFECT cell now asserts the binding.
   - Who: `carriesGenericFlowRoute()` (`forward/ForwardScopeOwnership.kt`): own type parameters, no
     captured owner (an `inner` class of a generic owner keeps ADR-147/196's refusal), not sealed
     and not an arm (the arm route stays ADR-124/199's).
   - Which members: `takesGenericFlowRoute(cls)`: a non-`suspend` Flow/StateFlow-returning method
     or a Flow/StateFlow-typed property that overrides no interface member. An interface member
     keeps ADR-174's explicit forwarding on a generic implementer; a `suspend` member keeps
     ADR-147's refusal (ADR-219's lane).
   - Every selector reads that one predicate: Kotlin `addClassExports`, C# `translateClass`, the
     scope selectors `forwardClassFlowMethods` / `forwardClassFlowProperties` (so the generic class
     owns the scope and is `IAsyncDisposable`), the planner's `nameGenericOwnerLegacyRoutes` and the
     property planner's `flowCarrier` (a generic class's `var` flow property gets the ordinary
     setter skip).
   - `reProjectsKeptBaseMember`'s two generic-base returns flip for Flow members: a generic base now
     projects its Flow member, so a subclass (`Box : Crate<Int>`) inherits it instead of
     re-declaring it (CS0108).
   - C# `nameof(Brook)` is CS0305 inside `Brook<T>`, so the disposed guard names `Brook<T>`.
     Externs hoist into `BrookNative` by the existing ADR-147 hoist.
2. **A member's own `T` on a Flow method** (`fun <T> stream(value: T): Flow<T>`) reuses ADR-214's
   `ForwardLegacyParameterShape.Boxed`: a Flow-returning member takes every boxed `T` (a trampolined
   one included), a `suspend` member every one but a trampolined one, any other legacy host
   refuses. Kotlin reads the box in the prelude, before `collectForCSharp`, and casts at the call
   (an out-of-bound value faults the stream); the call carries `legacyTypeArguments()`
   (`obj.stream<Any?>(...)`). C# carries `cirMethodTypeParameters` and `where` clauses on every Flow
   method render path; the box is `Wrap<T>` with an owned dispose or `GC.KeepAlive` in the collect
   delegate. `forwardMemberGenericRefusal`'s protocol check opens for a **class member's** `Flow` /
   `StateFlow` return only: the element is checked instead of the flow, and must be the own `T`
   (or `T?`) or not mention it. A Flow member that rule refuses is named with the `GENERIC`
   sentence (`warnRefusedLegacyRouteMembers`, ADR-214's arm extended to Flow returns).
3. **Trampolines on the Flow route.** A trampolined own `T : Enum<T>` (ADR-198) and a multi-bound
   owner `T` that a parameter takes (`Pride<T>.join(pet: T)`) wrap the export body in ADR-198's
   `FunSpec.trampolined`, reading the receiver as `Pride<T>` and checking the box against each
   bound. A multi-bound owner `T` only returned reads through `Pride<*>`. A multi-bound method `T`
   needs no trampoline: `rally<_>(pet.let { bounded -> bounded as Furry; bounded as Tricky; bounded })`
   infers the intersection, as ADR-214's suspend route does.
4. **`MutableStateFlow<T>` on a generic owner** (`Brook<T>.level`): `MutableStateFlowElement.Boxed`
   for a `T` with at most one spellable bound (a multi-bound or trampolined `T` reads through a
   star-projected receiver and stays read-only, `KotlinStateFlow<T>`). C# writes through
   `NugetMarshal.Wrap<T>` and disposes the minted box (or `GC.KeepAlive`) in a `finally`, both
   `CompareAndSet` values too; Kotlin reads `value?.asStableRef<Any>()?.get()`, cast to the one
   bound. The held method route (`returnsHeldMutableStateFlow`, `awaitsSettableMutableStateFlow`)
   excludes a `T` element.
5. **Element null guard.** `isFlowElementNullable()`: a type-parameter element whose every bound
   is nullable boxes through `itemBoxExpr`'s null-guarded arm, on arms too.

Fixed shipped bug (verified): `new Dish.Hot<string?>(null).Portions` faulted the stream with
`KotlinNullReferenceException` instead of yielding null. `exports/FlowExports.kt` read
`elementNullable` from `isMarkedNullable` only, so an unconstrained `T` element took
`NugetHandles.retain(value as Any)`; it is now `if (value != null) NugetHandles.retain(value) else
null`. The same rule fixes `_value` of a `StateFlow<T>` over such a `T`. Pinned by xunit
`GenericArm_Portions_NullItem_YieldsNullElements`, Tier 1 `Tier1GenericFlowOutTest` and the leak
row's last block.

Corrections from execution:

- The draft said a cancelled `await foreach ... WithCancellation` throws `OperationCanceledException`.
  The shipped `KotlinFlow` ends the enumeration cleanly (`FlowTests.Flow_WithCancellation_ExitsCleanlyAfterFirstItem`);
  the red runs observed no exception, so `Brook_Babble_CancelledMidStream_StopsAndDisposes`,
  `Creek_Echo_CancelledMidStream_StopsCollecting` and the leak row assert the clean exit.
- The draft inferred that `MutableStateFlow<T>`'s write needs lane A's `WrapOwned` transfer. It
  does not: it is an ordinary C#-to-Kotlin call, and `Wrap<T>` plus an owned dispose suffices
  (verified by `Brook_Level_*` and the leak row).
- ADR-214's cell "a Flow member with its own type parameter does not take the boxed shape" flips to
  "... takes the boxed shape". `Tier1EnumSelfBoundTest`'s `Podium<T : Enum<T>>.replay(): Flow<T>`
  now binds; its `suspend` sibling keeps the refusal. `Tier1GenericOwnerLegacyRouteTest`'s
  `Crate<T>.ticks(): Flow<Int>` binds; the owner's other legacy members stay named.

Verified vs inferred:

- Verified (run): every `MemberGenericFlowTests` fact (26) against the real pipeline; the leak row
  `GenericClass_FlowElements_ReturnToBaseline`; no bare `StableRef.create(` in the generated
  `CNameExports.kt`; the Tier 1 cells above; the full processor suite.
- Verified by reading generated output: the trampoline shapes for `Creek.cheer`/`crown` and
  `Pride.join`, the erased receivers, the boxed `Level` write and `CompareAndSet`.
- Verified by a Tier 1 probe: the ADR-174 shape. `Bin<T> : Feed` keeps explicit `IFeed.Source` /
  `IFeed.Ticks()` and adds public `Updates` / `Flowing()` over `Bin<Any?>`; both halves compile.
- Inferred: that the Tier 1 null-guard cell is red on the pre-lane commit (the old emitter text is
  the `value as Any` arm it asserts against; not re-run there).

Stopped, not opened (verified by a Tier 1 probe): an object's, a companion's and a top-level
`fun <T>` returning a `Flow`. No static owner has a Flow-return route at all: the non-generic
`fun plainTop(): Flow<Int>`, `object Herd { fun plainObj(): Flow<Int> }` and
`Den.Companion.plainComp(): Flow<Int>` are refused today with `SKIPPED_UNSUPPORTED_RETURN`. The
legacy Flow route is instance-keyed (the collect export takes the owner's `handle` and a
`scopeHandle` from `GetOrCreateScope()`, and the `IAsyncDisposable` owner cancels that scope), so a
static owner needs a new mechanism for where the scope lives and who cancels it. The generic forms
keep the `GENERIC` refusal, whose reason blames the type parameter although the position is the
cause; once a static route exists, the `fun <T>` form is this lane's mechanism unchanged. ROADMAP
Phase 6 carries the line.

### Lane C: `T` flowing into Kotlin asynchronously

As built. Where this section differs from the draft, the red tests decided it (noted inline).

- **Gate.** `carriesProtocol` is deleted. `carriesOwn` admits every lambda, suspend lambda and
  `Flow` (`isProtocolCarrier`, `forward/ForwardMemberTypeParameters.kt`), so the refusal reason
  "its signature carries a suspend lambda or a Flow" is gone and each asynchronous route owns its
  own refusal. The `GENERIC` sentence is unchanged. Tier 1: `Tier1MemberGenericMethodTest`'s
  `Depot.lambda` (a non-suspend member with a `suspend (Int) -> Unit`) moved from the declined
  cell to a bound one.
- **The draft's precondition did not hold.** ADR-214, built after the lane C red was written,
  already carries a member's own bare `T` on the suspend route: `dribble(item: T): T` and the
  `value: T` of `brew` bind with no lane C change. Verified (xunit `Dribble_*`).
- **Admission.** `asyncCallbackOrNull` admits every `T`/`T?` payload and result, `flowSourceOrNull`
  every `T`/`T?` element (`isAsyncBoxed`, `forward/ForwardBridgeTypeClassifier.kt`), trampolined
  and multi-bound included. The generated lambda and flow declare their
  component type, so a `T` with one closed spelling is spelled at its bound
  (`suspend (kotlin.Any?) -> kotlin.Any?`, `Flow<Furry>`, from which the member's call infers
  `T`), and any other `T` as the trampoline's own type variable (`Flow<T>`, `suspend (T) -> T`).
- **Trampolined and multi-bound `T` (built on ADR-219: the lambda and flow spell `T` as the trampoline's own type
  variable).** ADR-217's type-variable rule (`needsTypeVariable`,
  `forward/ForwardMemberTypeParameters.kt`) also sends a multi-bound or generic-bound `T` that sits
  inside a suspend lambda or a `Flow` through the trampoline, since no intersection is spellable
  there. The legacy route marks each component `trampolined` exactly when the export declares it
  (`readThroughTrampoline`, `forward/ForwardLegacyRouteCollections.kt`: the member's own `T` with
  trampoline bounds, an owner's multi-bound `T`), and `flowTrampoline` re-declares an owner
  parameter an asynchronous parameter names; the plan route marks them in `readThrough`
  (`forward/ForwardKotlinPlanEmitter.kt`). A marked box is checked against each bound, then cast to
  the variable (`bounded as kotlin.Enum<*>; bounded as T`). C#: no change. Verified (Tier 1
  `Tier1GenericAsyncInputTest`: `tints`, `retint`, multi-bound `stars`; xunit `Tints_*`,
  `Retint_*`). Not exercised: a trampolined single-bound OWNER `T` only an asynchronous parameter
  names (`flowTrampoline` re-declares only multi-bound owner parameters), inferred to spell the
  star bound over the erased receiver.
- **`Flow<T>` element.** As drafted: the abstract `NugetAsyncEnumeration` gains
  `IntPtr CurrentBoxed()`, `NugetAsyncEnumeration<T>` implements it as
  `NugetMarshal.WrapOwned(Enumerator.Current)`, and the shared `NugetAsyncObjectCurrent` thunk calls
  `NugetAsyncEnumeration.CurrentBoxed(a0)`. Boxing on the read keeps ADR-210 alternative 8.
  Verified (xunit `Gulp_*`, `Skim_*`, `Names_*`, `Sip_*`, `Lap_*`, leak row).
- **`suspend (T) -> T` result.** The Begin delegate calls `NugetTasks.BeginBoxed<T>`, which
  attaches the continuation to the task but stores a `NugetBoxedTask<T>` (non-generic base,
  `IntPtr EndBoxed()`) in the GC handle Kotlin's End thunk reads (`Attach(task, target, ...)`);
  the shared `NugetAsyncObjectEnd` calls `NugetTasks.EndBoxed(a0)`, which frees the handle in its
  `finally`. Verified (xunit `Brew_*`, `Slosh_*`, leak row).
- **Kotlin read.** Each box is lane A's result read: the box (`!!` for a non-null `T`), the value
  through `forwardBoundedRead`, the release, the value (`asyncBoxedRead`,
  `forward/ForwardAsyncCallback.kt`). A null `T` crosses as `IntPtr.Zero` and mints nothing.
- **`suspend (T) -> Unit` payload.** Lane A's boxed payload (`NugetHandles.retain`, null-guarded
  for `T?`), C# `FromHandle<T>(a0)`. Verified (xunit `Tip_*`).
- **Retain export.** `<lib>__retain_handle` is now needed by a legacy route too, so both halves
  widen their gate: Kotlin emits it when a plan carries a boxed `T` INTO Kotlin (`boxesIntoKotlin`,
  `forward/ForwardRetainHandle.kt`) or when the legacy suspend route sighted one
  (`ForwardBridgeTypeClassifier.boxedAsyncHost`, the export's ADR-117 owner); C# imports it when
  a plan does or when an emitted suspend lambda or `Flow` boxes one. The C# set is a subset of the
  Kotlin one, so the one-directional contract holds. Tier 1 `Tier1GenericAsyncInputTest` pins it
  with no lambda plan in the module.
- **Generic owners (ADR-219).** `Cistern<T>` and `Spill.Puddle<T>`
  bind their `Flow<T>`, `suspend (T) -> T` and `suspend (T) -> Unit` members over the erased
  receiver with no lane C change beyond the above. Verified (xunit `Cistern_*`, `Puddle_*`).
- **Not bound, found by the red run.** A `suspend` member taking a plain lambda
  (`decant(value: T, pour: (T) -> T)`) is refused for any payload, `T` or not: the legacy suspend
  route lowers no ADR-036 callback, and its per-call key would have to outlive the P/Invoke until
  the task completes. A Phase 6 gap, not generics. Fixture kept (named skip); its xunit fact is
  commented out. Its
  `SKIPPED_UNSUPPORTED_RETURN` used to say a callback cannot return `T`, stale since lane A; a
  `T` result now names the position instead ("binds only as a per-call lambda on an ordinary
  (non-suspend) member", `exports/ClassExports.kt`), pinned by `Tier1GenericAsyncInputTest`.

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
- Tier 1, lane B (`Tier1GenericFlowOutTest`): a generic class's `Flow`, `StateFlow` and
  `MutableStateFlow` members over the erased receiver; a member's own `T` on a Flow method; an
  unconstrained `T` element boxes null-guarded, the generic arm included. xunit
  `MemberGenericFlowTests.cs`.
- Fixture `test-library/.../membergeneric/`: lane A's `Decks.kt` (`Dealer`, top-level `echoer`) and
  `Feeds.kt` (`Serving<T>`, `Helping<out T>.Seconds<T>`); xunit `MemberGenericLambdaTests.cs`.

## Consequences

- Lane A: `Tier1MemberGenericMethodTest` (its protocol cell is now a suspend lambda),
  `Tier1GenericOwnerLegacyRouteTest` and `Tier1GenericSealedArmNamedSkipTest` (the per-call `T`
  callback binds; the stored pair keeps `GENERIC_ARM_ERASED_LAMBDA`). Lane B:
  `Tier1GenericSealedFlowPropertyTest` (null element), the old flow-property DEFECT cell,
  `Tier1AbstractChainBackingTest`, `Tier1ScopeOwnerChainBranchesTest`, `Tier1EnumSelfBoundTest`
  and `Tier1GenericOwnerLegacyRouteTest`.
- A new per-library export `<lib>__retain_handle`, generated, outside the frozen `nuget_*` ABI.
- ADR-160's "an object LAMBDA return stays a SpecializedProtocol" is narrowed: the `WrapOwned`
  transfer is the mechanism a later concrete object lambda result (`() -> Cat`) can reuse; this ADR
  admits only `T`.
- A trampolined `T` (ADR-198) in a lambda binds (a widening over the draft).
- Still refused, by name: a `T` payload of a stored callback pair (`GENERIC_ARM_ERASED_LAMBDA` on an
  arm, ADR-147 on a generic class), a `var` flow property setter (ROADMAP line 232), a
  `StateFlow`/`SharedFlow` parameter (ADR-210), a `Flow<T>` parameter (lane C) and a `suspend` member
  of a generic class (ADR-147; ADR-219's lane).
- Lane B found a `Flow` return on an `object`, companion or top-level function has no route,
  generic or not; ROADMAP Phase 6.
- Deferred: nothing beyond those pre-existing refusals.

## Amendment (2026-10-22): the static `Flow` return binds

The "Stopped, not opened" paragraph in lane B and the line in Consequences are superseded by
[ADR-230](230-flow-on-static-owners.md): a `Flow` return on an `object`, a companion or the top
level binds, generic or not, and a member's own `T` uses this ADR's trampoline with no owner.
