# ADR-239: Forward, a returned lambda binds as a BCL delegate on every owner, and the callback vocabulary widens to the plan's

## Status
Accepted

Amends [ADR-236](236-lambda-properties-on-every-owner.md) (its closing sentence "a function that
returns a lambda still returns the wrapper" no longer holds), [ADR-012](012-lambda-function-type-mapping.md)
and [ADR-020](020-suspend-lambda-mapping.md) (the `KotlinFunc`, `KotlinAction`, `KotlinSuspendFunc`
and `KotlinSuspendAction` return types), [ADR-160](160-callback-parameter-on-the-forward-plan.md)
and [ADR-210](210-flow-and-suspend-lambda-parameters.md) (the payload and result sets of a plain and a
`suspend` lambda parameter), [ADR-037](037-stored-callbacks.md) (the payload and result refusals of an
`add`/`remove` pair), [ADR-199](199-generic-sealed-hierarchies.md) (the erased-lambda refusal on a
generic sealed arm) and [ADR-039](039-interface-bridging.md) (a subscription pair on a generic owner).

**Breaking** for every function that already returned a lambda. See [Breaking](#breaking).

## Context

ADR-236 made a lambda-typed property a BCL delegate on every owner. A function that returns a lambda
kept the wrapper types, and only a top-level function bound at all. A member of a class, an `object`,
a companion, an enum, an interface, a sealed base or arm, a generic class or an extension, and any
`suspend` lambda return, were named `SKIPPED_UNSUPPORTED_RETURN`.

The callback routes were narrow in the other direction too. A plain or `suspend` lambda parameter, a
stored `add`/`remove` pair, a returned lambda and a lambda property carried `Unit`, a primitive,
`String` or a class handle. An enum, `Char`, a nullable, a collection, `ByteArray`, `Instant`,
`Duration`, `Uuid`, a value class, a type parameter or a bound C# interface was a named skip, or on a
nullable payload a build abort or a host crash (the `ClawStrip` cells). A stored pair on a generic
owner or a generic sealed arm was refused outright.

## Decision

### A returned lambda is a delegate

A function that returns a lambda binds the same delegate ADR-236 gave a property, on every owner:

| Kotlin | C# |
|---|---|
| `(A) -> R` | `Func<A, R>` |
| `(A) -> Unit` | `Action<A>` |
| `suspend (A) -> R` | `Func<A, CancellationToken, Task<R>>` |
| `suspend (A) -> Unit` | `Func<A, CancellationToken, Task>` |
| any of these, nullable | the same type with `?` |

Owners: the top level, an ordinary class, an `object`, a default and a named companion, an enum body
(`FormationExtensions.Call`), a value class, a generic class (typed by the closed `T`), a sealed base
and its arms, a class- or `String`-receiver extension, a Kotlin-backed interface, and a
C#-implemented interface (Kotlin calls the delegate the C# member returns). Each return mints a
fresh native handle that the delegate's target releases when it is collected, so there is nothing to
dispose. A delegate a C# implementation returns is minted into a Kotlin lambda that Kotlin drops
when it is done, and its cleaner frees the delegate's key.

### The callback vocabulary

A plain lambda parameter, a `suspend` lambda parameter, a stored `add`/`remove` pair, a returned
lambda and a lambda property now share one payload and result vocabulary, the one the callable plan
already uses for a method parameter and return:

- payload and result: `Char`, a nullable of any admitted type, `List`, `Set`, `Map`, `Instant`,
  `Duration`, `Uuid`, a value class, a type parameter, and a bound C# interface such as
  `IFeedable`; `ByteArray` on a per-call, `suspend` or property lambda;
- result: also an enum and an exported class handle;
- `Throwable`, as a `System.Exception`, in a payload or result, including a `suspend` lambda's result
  (`Func<int, CancellationToken, Task<Exception>>`) and a nullable `Throwable?` payload. A result
  declared narrower than `RuntimeException` stays refused (ADR-201).

A nullable crosses as no box, which C# reads as `null`, so a nullable `suspend` lambda parameter
(`(suspend (T) -> R)?`) binds too and a C# `null` reaches Kotlin as `null`.

A bound C# interface crosses by two routes. On the direct routes it crosses as the `GCHandle` the
reverse pipeline already mints, so a C#-implemented resident reaches the C# lambda as the same
managed object. At an erased position (a stored pair, a returned lambda, a lambda property) it
crosses through a per-interface `box`/`unbox` export pair that the generated `NugetMarshal` looks up
by `typeof(IFeedable)`.

### Stored pairs

A stored pair binds with a result (`addJudge(pick: (String) -> Spirit)`), with a widened payload
(`addWatcher(listener: (List<String>) -> Unit)`), on a generic owner (`Shaker<T>.addShake`,
`Shaker<T>.addCounter`) and on a generic sealed arm carrying the arm's `T` (`Pyramid.Tier<T>.addClimber`),
which removes ADR-199's erased-lambda refusal. An ADR-039 subscription pair, whose listener is an
interface, binds on a generic owner too (`Stand<T>.addWave`). A per-call `T` result on a generic arm
binds (`Hot<T>.pick`).

### Still refused

Three shapes stay refused by name, each pinned by `Tier1RefusedCallbackShapesTest`:

- A lambda of four or more parameters: a lambda crosses with at most three on every callback route.
  The warning reads "`(Int, Int, Int, Int) -> Int` takes 4 parameters, and a lambda crosses with at
  most 3 on every callback route. Group the rest into a class, or split the lambda".
- A `Pair` or `IntArray` payload, which the bridge maps at no position.
- An `Any` payload. ADR-237, a separate change, binds it.

## Alternatives

- **Keep the wrapper types for a return.** ADR-236's reason (C# never converts a lambda literal to a
  user-defined type, CS1660) does not apply to a return, which a caller only reads. The reasons to
  change anyway: the same Kotlin lambda would be a `Func` as a `val` and a `KotlinFunc` as a `fun`,
  and the wrapper carries a `Dispose` obligation a delegate does not. A delegate also goes straight
  into any .NET API that takes one.
- **Route a bound C# interface through the `GCHandle` at every position.** It is correct on the
  direct routes. At an erased position (a stored pair, a returned lambda, a lambda property) the
  value reaches the bridge without a static interface type, so the erased positions use the
  `box`/`unbox` pair keyed by `typeof`. That reason is inferred from the design; it was not tested by
  routing the erased positions through the `GCHandle`.

## Breaking

A function that returned a lambda changes type, and the owners that used to skip it gain a member.

| Before | After |
|---|---|
| top-level `fun cheer(): (String) -> String` returned `KotlinFunc<string, string>` | `Func<string, string>` |
| `fun clap(): (Int) -> Unit` returned `KotlinAction<int>` | `Action<int>` |
| `fun maybeCheer(loud: Boolean): ((String) -> String)?` | `Func<string, string>?` |
| `fun chant(): suspend (String) -> String`: named `SKIPPED_UNSUPPORTED_RETURN` | `Func<string, CancellationToken, Task<string>>` |
| the same on a class, `object`, companion, enum, interface, sealed arm, generic class or extension: named `SKIPPED_UNSUPPORTED_RETURN` | binds as above |
| `using KotlinFunc<int, int> f = Decks.Adder(2);` | `Func<int, int> f = Decks.Adder(2);` |
| `f.Invoke(3)` | `f(3)` or `f.Invoke(3)` |
| an exported interface spelled `KotlinFunc<IPet, IPet>` | `Func<IPet, IPet>` |
| a callback payload or result of `Char`, a nullable, a collection or any other widened type: named skip | binds |
| `AddX(...)` over a lambda on a generic owner or generic sealed arm: a named skip | binds |

- `using` on a returned lambda no longer compiles (a delegate is not `IDisposable`). Drop the `using`.
- A `suspend` lambda takes a `CancellationToken` argument; pass `CancellationToken.None` when there is
  none. `await f.InvokeAsync(a)` becomes `await f(a, token)`.
- Reflection on the return type compares against `Func<..>`, not `KotlinFunc<..>`.
- A nullable payload on a per-call or stored lambda no longer leaves the member absent: code that
  asserted its absence by reflection now finds it.
- The wrapper classes stay in the generated file as plumbing, but no generated member returns or
  takes them.

## Consequences

- One vocabulary governs the five callback routes, so a payload that works on a per-call parameter
  works on a `suspend` parameter, a stored pair, a returned lambda and a property.
- Each returned delegate holds a native handle until it is collected, the same as ADR-236's getter.
  A returned delegate you drop is released by the GC, not by a `Dispose` call.
- A `Cheerleader`-style owner that awaits a delegate returned by a C# implementation creates its
  coroutine scope handle on the first `suspend` call (ADR-128) and releases it on `Dispose`, so the
  live-handle count rises by two at the first crossing and then stays flat.
- The three refusals above stand by earlier policy; this change leaves no follow-up work.

## Evidence

The bound-interface `box`/`unbox` pair and the `typeof`-keyed tables are verified in the generated
`Interop.cs` of the verification run (`kn_testlibrary__bound_test_menagerie_IFeedable_box` and
`_unbox`, `NugetMarshal` entries keyed `typeof(global::Test.Menagerie.IFeedable)`). The "before"
column for the non-top-level owners is taken from the earlier tests that asserted those members
absent (`UnroutedPositionsTests`).

- Forward fixtures `test-library/.../test/cheersquad/` (`CheersquadSample`, `Tassel`, `Scorecard`),
  `menagerie/Pen.kt` and `skipremarks/ClawStrip.kt`, with `IntegrationTests/ReturnedLambdaOwnerTests.cs`,
  `CallbackResultVocabularyTests.cs`, `BoundInterfaceCallbackTests.cs` and `NullableBoundarySkipTests.cs`,
  and the existing lambda tests moved to the delegate shape.
- Throwable fixture `cheersquad/Referee.kt` with the `Throwable_SuspendResultNullablePayloadAndReturnedSuspendResult`
  integration row and the `ThrowableCallbacks_ReturnToBaseline` leak row.
- Tier 1: `Tier1RefusedCallbackShapesTest`, `Tier1SuspendLambdaVocabularyTest`,
  `Tier1BoundInterfaceCallbackTest`, `Tier1ThrowableCallbackTest`.
- Leak rows in `LeakTests/LiveHandleTests.cs`: `ReturnedLambda_EveryOwner_ReturnsToBaseline`,
  `ReturnedLambda_SuspendResults_ReturnsToBaseline`,
  `ReturnedAsyncDelegate_FromACSharpImplementation_IsFlatAfterTheOwnerScope`,
  `SealedArmDelegate_SetCallClearAndReturn_ReturnsToBaseline`,
  `ListenerDelegate_KotlinObjectStoresAndClearsIt_ReturnsToBaseline`,
  `StoredListenerWithAResult_SubscribeAndDispose_ReturnsToBaseline`,
  `WidenedCallbackPayloadsAndResults_ReturnToBaseline`,
  `WidenedSuspendPayloadsAndGenericPairs_ReturnToBaseline`, `ThrowableCallbacks_ReturnToBaseline`,
  `BoundInterfaceCallbacks_ReturnToBaseline` and `BoundInterfaceErasedPositions_ReturnToBaseline`.
- Collectability rows in `LeakTests/CollectabilityTests.cs`: `StoredListenerWithAResult_Disposed_IsCollected`,
  `BoundInterfaceStoredListener_Disposed_IsCollected`, `ReturnedDelegate_FromACSharpImplementation_IsCollectedOnceKotlinDropsIt`,
  `ReturnedAsyncDelegate_FromACSharpImplementation_IsCollectedOnceKotlinDropsIt`,
  `SealedArmDelegate_ClearedByCSharp_IsCollected`, `SealedArmDelegate_ClearedByKotlin_IsCollected`,
  `ListenerDelegate_KotlinObjectClearsIt_IsCollected` and `ListenerInterfaceDelegate_RemovedListener_IsCollected`.
