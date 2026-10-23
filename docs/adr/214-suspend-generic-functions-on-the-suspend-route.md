# ADR-214: Forward, a `suspend fun <T>` binds on the suspend route with ADR-147's boxed `T`

## Status
Accepted

Extends [ADR-211](211-top-level-generic-functions-on-the-plan.md) (its deferred "`suspend fun <T>`
at top-level") and [ADR-019](019-suspend-function-mapping.md) (the suspend route). Reuses
[ADR-197](197-method-type-parameters-on-the-callable-plan.md)'s owner and shape rules and
ADR-147's boxed-handle wire.

Amended by [ADR-219](219-generic-owner-suspend-members.md): a trampolined `T` parameter and a generic
owner's suspend members bind on the suspend route too.

## Context

A top-level `suspend fun <T>` has no working route. **Verified by Tier 1 spike** (2026-10-22, at
`5e001177`):

- `suspend fun <T> fetch(value: T): T` passes `legacyGenericRouteParameterIndex()`
  (`exports/GenericFunctionExports.kt:41-63`, which never reads `Modifier.SUSPEND`) and lands on
  the width-variant route. The Kotlin half emits 13 synchronous exports that call the suspend
  function, and fails with `Suspend function 'suspend fun <T> fetch(value: T): T' can only be called
  from a coroutine or another suspend function`, with no `[nuget:...]` diagnostic. A consumer
  declaring this shape cannot build today.
- `suspend fun <T : Any> describeLater(value: T): String` and `suspend fun <T> pickLater(first: T,
  second: T): T` are refused by that gate and named `SKIPPED_UNSUPPORTED_RETURN` with ADR-211's
  `GENERIC` sentence, which says a top-level function's own `T` binds as a bare parameter or return.
  False for a suspend one.
- `isPlannedTopLevelFunction()` (`NugetProcessor.kt:3953-3956`) keeps every `suspend fun <T>` off
  the catalog, so it can only reach the width route.

No owner binds a suspend member's own `T` either. **Verified by spike**: `class Sitter { suspend
fun <T> fetch(value: T): T }` is named `SKIPPED_UNSUPPORTED_INPUT ... but not value: T`; on an
`object` no suspend member binds at all (`SKIPPED_UNSUPPORTED_COMBINATION`, not generic-specific);
a generic class's suspend members are refused by `forwardSuspendRouteMethods`
(`forward/ForwardScopeOwnership.kt:63`).

Worse than "not bound" on a class too, which the spike did not cover. **Verified by the red
integration run** (2026-10-22): `class Courier { suspend fun <T> ferryAll(items: List<T>):
List<T> }` is not refused at all. ADR-212's collection classification already accepts a
`List<T>` of a member's own `T` at a legacy position, and `forwardSuspendRouteMethods` never read
`forwardMemberGenericRefusal()`, so both halves emitted it, and C# declared
`Task<IReadOnlyList<T>> FerryAllAsync(IReadOnlyList<T> items, ...)` with no method `<T>`: CS0246
in the generated `Interop.cs`. A class with that member broke the consumer's C# build.

The suspend route is not on the ADR-062 plan: `staticEntry` skips `SUSPEND` and the per-function
builders own both halves (`exports/SuspendFunctionExports.kt:61-225`,
`cir/CirFunctionTranslator.kt:34-...`, the class route in `cir/CirClassTranslator.kt`), classifying
through `legacyParameterShapes` / `legacyReturnShape` (`forward/ForwardLegacyRouteCollections.kt`),
which have no arm for `BridgeType.TypeParameter` and refuse it. **Verified by reading.**

## Alternatives Considered

### 1. The suspend route gains a boxed `T` parameter and return shape (chosen)

Route every `suspend fun <T>` to the suspend route, admit it under ADR-197's
`forwardMemberGenericRefusal()`, and add one `Boxed` arm to the legacy parameter shape and one to
the return shape, spelled from the same wire pieces the plan already emits for a sync `T`. One
export per function, no width variants. Cost: one new shape pair on a route both halves share, so
the class-member owner comes along for free.

### 2. Put `suspend` on the ADR-062 plan

One planner for both. Rejected: the plan has no async callable kind, so this rewrites the whole
suspend route (ADR-019, 068, 114, 118, 119, 131, 194, 210 behaviour) to bind one more shape.

### 3. Async width variants

`fetch_int_async`, ..., `fetch_object_async`. Rejected: 13 exports per function on the route
ADR-213 is retiring, and a `String` return has no width to dispatch on.

### 4. Refuse and name only

Guard the width gate against `suspend` and name the function. Fixes the build break, binds nothing.
Kept as step 1 of the chosen option, not as the decision.

## Decision

Alternative 1.

```C#
// suspend fun <T> fetchLater(value: T): T
public static Task<T> FetchLaterAsync<T>(T value, CancellationToken cancellationToken = default)
// suspend fun <T : Any> describeLater(value: T): String
public static Task<string> DescribeLaterAsync<T>(T value, CancellationToken cancellationToken = default) where T : notnull
// suspend fun <T> pickLater(first: T, second: T): T
public static Task<T> PickLaterAsync<T>(T first, T second, CancellationToken cancellationToken = default)
// class Courier { suspend fun <T> ferry(value: T): T }
public Task<T> FerryAsync<T>(T value, CancellationToken cancellationToken = default)
```

### Routing

1. `legacyGenericRouteParameterIndex()` returns `-1` for a function with `Modifier.SUSPEND`, and
   `isPlannedTopLevelFunction()` drops its `!modifiers.contains(Modifier.SUSPEND)` term. **Verified
   by spike**: with exactly those two edits all three top-level functions above leave the width
   route, land in the `suspendFunctions` partition (`NugetProcessor.kt:1879-1881`), compile clean,
   and are named `SKIPPED_UNSUPPORTED_INPUT ... not value: T`. If ADR-213 has already retired the
   width route, only the second edit remains.
   In the stack ADR-213 landed first: the width route is gone, `isPlannedTopLevelFunction()` is
   `typeParameters.isEmpty() || !isReifiedWithUnspellableBound()`, and the edit is the suspend
   partition, which now takes every planned `suspend` candidate, generic or not.
2. `isForwardGenericMemberOwner()` (`forward/ForwardMemberTypeParameters.kt:21-30`) then answers
   true for a top-level suspend generic without change, since the gate refuses it; the classifier
   spells its `T` as `BridgeType.TypeParameter`. **Verified by reading.**
3. Admission: a suspend function or member with type parameters routes when
   `forwardMemberGenericRefusal()` is null, the ADR-197/211 rules unchanged (no reified, no bound by
   a type parameter, no nested `T` beyond ADR-212's collections, no lambda or `Flow`). Its refusal
   is named with the plan's `GENERIC` skip and the `(here, ...)` clause, replacing the
   `SKIPPED_UNSUPPORTED_INPUT ... value: T` sentence. `forwardSuspendRouteMethods` admits a
   non-generic class's member and a sealed arm's own member the same way; a generic owner keeps
   ADR-147's refusal.

### Wire

Every piece below already exists on another route. **Verified in generated Tier 1 output**
(spike of `fun <T> pick(first: T, second: T): T` and `suspend fun ping(value: String?)`):

| Position | Kotlin half | C# half |
|---|---|---|
| `T` parameter | `COpaquePointer?` slot; prelude local `val valueArg = value?.asStableRef<Any>()?.get()` (the erased read only) | `IntPtr valueBox = NugetMarshal.Wrap<T>(value!, out bool valueOwned);` before the extern; `finally`: `if (valueOwned) NugetMarshal.Dispose(valueBox); else GC.KeepAlive(value);` (`Wrap<T>` reports `owned = false` for a null, so the zero pointer is never disposed) |
| call | `fetchLater<Any?>(valueArg)`, each `ForwardMethodTypeParameter.kotlinTypeArgument`; a bound's checked cast (`(amountArg as kotlin.Number)`) or a `T : Any`'s `valueArg!!` is the argument, so it runs inside the launch | n/a |
| `T` result | `if (result == null) null else NugetHandles.retain(result)` (the suspend body's existing nullable boxing) | `TaskCompletionSource<T>`; `t.SetResult(NugetMarshal.FromHandle<T>(resultPtr))` |
| constraints | n/a | `typeParameter.cirConstraints(context, logger, function, label)` (`cir/CirClassTranslator.kt:1018-1043`), called by the suspend translator since a suspend function is not a planned entry |

**Lifetime, load-bearing.** The owned box is disposed in a `finally` once the `_async` extern
returns, not when the task completes. That is safe only because the suspend body evaluates its
parameter prelude on the caller's thread **before** `launchForCSharp`
(`exports/SuspendFunctionExports.kt:244-249` top-level, `:280-285` class). **Verified by
reading.** The `Boxed` shape must therefore lower to a prelude local, never inline inside the
launch lambda; inlining it would read a disposed handle on the coroutine's thread. A borrowed
wrapper argument is kept alive across the extern call only; Kotlin holds the dereferenced object
for the suspension, as ADR-208's `Box<T>` suspend parameter already does.

**A C#-implemented interface as `T`.** `Wrap<T>` mints a bridge transfer handle for it (ADR-173),
disposed when the extern returns, and the Kotlin bridge object outlives that disposal for the whole
suspension. **Verified by Tier 2** (`SnoozeLaterAsync_CSharpInterfaceImplementation_IsCalledBackAfterTheExternReturned`,
the callback runs after a `delay`). The red run found a precondition the draft missed: an
interface reachable **only** as a `T` bound (`suspend fun <T : Snoozer> snoozeLater(snoozer: T)`)
was never made reachable, so it had no bridge and `NugetBridge.HandleFor` threw
`NotSupportedException` before the extern. The legacy-position reachability walk
(`legacyCarriedInterfaceNames` in `NugetProcessor`) now adds a non-generic interface named by a
suspend member's `T` bound. Scoped to the suspend positions: promoting it from the plan's
positions too changed the output (and the `unsafe` need) of seven unrelated Tier 1 cells, so the
sync `fun <T : Snoozer>` keeps its shipped behaviour, which is the same gap and is not this ADR's.

**Out-of-bound `T`, decided in the lane.** The draft placed ADR-015's checked cast at the Kotlin
read, in the prelude. That prelude runs on the caller's thread before `launchForCSharp`, outside
its catch, and the `_async` export has no error slot, so a throw there would escape the C ABI. The
`Boxed` shape therefore splits the plan route's `forwardBoundedRead`: the prelude does only the
erased `asStableRef<Any>()` read (which keeps the lifetime rule above), and the bound cast (or a
non-null `T`'s `!!`) is the call argument, evaluated inside the launch, where a
`ClassCastException` completes the `Task` as faulted through the shared error path.
**Verified by Tier 1** (`Tier1TopLevelSuspendGenericTest`, the cast is absent from the prelude and
present at the call); the faulted `Task` itself is the Tier 2 row
`WeighLaterAsync_OutOfBoundTypeArgument_IsAFaultedTask`.

**Not admitted**: a trampolined `T` parameter (ADR-198, a bound with no closed spelling such as
`T : Enum<T>`). Its read needs a type variable the launch lambda cannot declare, so the shape stays
`Refused` and the member is named `SKIPPED_UNSUPPORTED_INPUT`, as before this ADR. A trampolined
`T` result binds (an erased read needs no type variable). The `Boxed` parameter shape is also the
suspend route's alone: a `Flow`-returning member's own `T` keeps its refusal (ADR-215 owns that).

### Diagnostics

The `GENERIC` sentence (`forward/ForwardDiagnostic.kt`) takes the batch's final text: "a
function's own type parameter binds on a top-level function, including a `suspend` one, and on a
class, object, companion or sealed-class member: as a bare `T` or `T?`, inside a `List`, `Set` or
`Map`, inside an exported generic class or a `Result` payload, and inside a lambda or `Flow`".
A suspend member that `forwardMemberGenericRefusal()` refuses is named by
`warnRefusedLegacyRouteMembers` with that sentence, the structural kind
(`SKIPPED_UNSUPPORTED_COMBINATION`) and its `(here, ...)` rule, ahead of the parameter and return
walks, so it is never named as an input the route cannot take. (An object's suspend members still
have no route at all; that refusal is the separate `SKIPPED_UNSUPPORTED_COMBINATION`, unchanged,
and a ROADMAP Phase 6 line.)

## Consequences

- `suspend fun <T> fetch(value: T): T` builds again; it was a consumer build break on every shipped
  version, so no shipped entry point moves.
- Every bindable top-level `suspend fun <T>` is one `_async` export and one generic `Task<T>`
  method on the file-facade class; a non-generic class's and a sealed arm's own suspend generic
  members bind as instance methods.
- ADR-211's Consequences drop "`suspend fun <T>` at top-level"; ADR-197:200's "suspend members"
  refusal narrows to a generic owner.
- Not changed: `suspend` on an `object` / companion (no suspend route, generic or not; a ROADMAP
  Phase 6 line).
- Open gaps owned by sibling work in the same stack, so not ROADMAP lines here: a trampolined `T`
  parameter on the suspend route and suspend members of a generic class or generic sealed arm (both
  ADR-219), and a `Flow`-returning member's own `T` (ADR-215).
- ADR-212's `List<T>` / `Set<T>` / `Map<K, V>` of a function's own `T` rides the legacy
  `Marshalled` shape at a suspend parameter and return with no new helper: the classification and
  the ADR-212 lowering (`loweredCollectionExpression`, `CreateList` over `Wrap<T>`) already accepted
  a `TypeParameter` element. What was missing was the admission gate and the C# method's `<T>`.
  **Verified** by Tier 1 and the `ShuffleLater`/`DedupeLater`/`KeepLater`/`FerryAll` xunit rows.
- Leak row: `TopLevelSuspendGeneric_BoxedTypeParameter_ReturnsToBaseline` in
  `LeakTests/LiveHandleTests.cs`, a 200-iteration loop over an `int`, a `string` and a wrapper
  argument plus one pre-canceled call, at the end of the class under an `// ADR-214` banner.

## Amendment (2026-10-22): static owners bind

The statements above that an `object`'s or companion's suspend members have no route
(`SKIPPED_UNSUPPORTED_COMBINATION`, generic or not), and the parenthetical on the `GENERIC`
sentence, are superseded: both owners bind, with their own `T`, as
[ADR-229](229-suspend-on-static-owners.md) records.
