# ADR-232: Forward, a C# class implementing a Kotlin interface with `suspend` and `Flow` members crosses to Kotlin

## Status
Accepted

Amends [ADR-084](084-csharp-implemented-interfaces.md) (the `suspend` member and `Flow`/`StateFlow`
member refusals and the "Deferred" entry), [ADR-222](222-csharp-implemented-generic-interfaces.md)
(a generic interface's `suspend` member is no longer a named refusal) and
[ADR-210](210-flow-and-suspend-lambda-parameters.md) (the legacy suspend and `Flow` routes take an
interface parameter). Builds on [ADR-174](174-interface-async-members.md), which declares the
members on `I<Name>`.

## Context

ADR-174 declares `Task<R> FeedAsync(string cat, CancellationToken cancellationToken = default)`
and `KotlinFlow<string> Meals()` on `IFeeder`, but the bridge planner refused every `suspend` or
`Flow` member, so a C# class implementing the interface could be written and never passed to
Kotlin: the build warned `SKIPPED_UNIMPLEMENTABLE_INTERFACE` and the first crossing threw
`NotSupportedException`. `KotlinFlow<T>` also had no public way to make one from C#, and an
interface that no function takes, returns or stores did not declare its `suspend` members at all.

## Decision

A C# implementation of such an interface is accepted as a bridge.

**`suspend` member.** The slot is an ADR-210 Begin/End pair bound to the implementation's method.
The C# Begin delegate calls `impl.FeedAsync(args, token)` through `NugetTasks.Begin` (or
`BeginBoxed<T>` for a boxed result), and the End thunk for the result type completes the Kotlin
side. The Kotlin bridge object overrides with `override suspend fun feed(cat: String): Int` over
the runtime's `awaitForKotlin`. Cancelling the Kotlin coroutine cancels the token the C# method
received; a cancelled C# task is a cancellation in Kotlin and any other fault is a catchable
`NugetManagedException` ([ADR-161](161-csharp-callback-exception-into-kotlin.md)). A `Unit`
member is a `Task`. Result types are the ADR-084 slot vocabulary: `String`, `String?`, the five
primitives, `Boolean`, an enum, `Throwable`, `Unit` and the interface's own `T`.

**`Flow<T>` member.** The slot is a registered enumeration-factory key over `impl.Meals()`, and the
Kotlin bridge wraps it with `flowForKotlin`, so each Kotlin `collect` calls `GetAsyncEnumerator`
once and Kotlin stopping early disposes the C# enumeration. The element is a non-null `String`,
`Boolean`, `Int`, `Long`, `Float`, `Double` or the interface's own `T` (read from the planner,
`ForwardInterfaceBridgePlanner.flowResult`). A C# implementation makes the flow with the new
`KotlinFlow<T>.From(IAsyncEnumerable<T>)`, which is cold: it holds the source and no Kotlin flow
handle. A `KotlinFlow<T>` the implementation received from Kotlin and returns as it is round-trips
through the same `IAsyncEnumerable` seam, so the wrapper needs no handle of its own.

**Hot flows.** A `StateFlow`, `SharedFlow` or mutable form member hands Kotlin the flow's handle,
so the flow must be a real Kotlin flow. `KotlinMutableStateFlow<T>.Create(T initial)` and
`KotlinMutableSharedFlow<T>.Create(int replay = 0)` mint one through per-library
`<lib>__hotflow_*` exports, emitted only when an interface has such a slot. A wrapper with no
Kotlin flow of its own (the `Level` read from a Kotlin-backed `IThermostat`, returned from
another implementation's hot slot) throws `ArgumentException` at the slot, naming the wrapper type.

**`suspend` member returning `Flow<T>`.** The C# member is `Task<KotlinFlow<T>>`; the slot awaits
it and the Kotlin bridge collects the awaited flow like a `Flow` member. A hot-flow or own-`T`
`Flow` result of a `suspend` member has no End thunk and is refused by name at build time.

**Interface in no position.** An interface no function takes, returns or stores now declares its
`suspend` members on `I<Name>`. Nothing Kotlin-backed can reach C# through it, so its only
consumer is a C# implementation, which is bridged all the same.

**Legacy routes.** The legacy suspend and `Flow` routes (ADR-210's
`ForwardLegacyParameterShape`) gain an `Interface` shape: a Kotlin-backed value lends its handle,
a C# implementation mints its bridge (`HandleFor`, or `MintBridge` for a generic interface) and
disposes it in the call's `finally`. This binds members that were skipped for an interface
parameter. A defaulted interface parameter stays required, as a defaulted `T` does.

**Lifetime.** The slot's delegate is a method of the bridge object, so there is no per-call key
and the ADR-084 cleaner that owns the bridge owns the slot. A flow factory key lives with its
bridge.

## Consequences

- `NugetFlow` and the `KotlinFlow<T>` template gain `From`; `GetAsyncEnumerator` returns the
  source's enumerator when there is one.
- No source break: the members were already declared on `I<Name>`, so an existing implementer
  compiles as before and now also crosses to Kotlin.
- A hot flow `Create` is the only way a C# implementation can satisfy a `StateFlow` or
  `SharedFlow` member.

## Evidence

Verified by test: `IntegrationTests/AsyncInterfaceBridgeTests.cs` (37 rows: completion for every
result wire, a `Unit` member awaited twice, Kotlin timeout and outer-token cancellation reaching
the C# token, a cancelled task as a cancellation, sync, faulted and after-await faults caught and
uncaught, a C#-made flow collected, collected twice, stopped early, faulting mid-stream and
cancelled, a Kotlin-made flow passing through, a generic `ILarder<T>` open and closed, an
unreachable interface, a `suspend` member returning `Flow`, hot-flow reads and writes both ways
and the `ArgumentException`). Leak rows 19a to 19d in `LeakTests/LiveHandleTests.cs`
(`CSharpAsyncInterface_Completion_ReturnsToBaseline`, `CSharpAsyncInterface_Fault_ReturnsToBaseline`,
`CSharpAsyncInterface_Cancellation_ReturnsToBaseline`, `CSharpHotFlowInterface_ReturnsToBaseline`)
and `CSharpAsyncFeeder_ReleasedAfterKotlinGc` in `LeakTests/CollectabilityTests.cs`.

Verified by reading: the element vocabulary and the two `suspend` refusals above come from
`ForwardInterfaceBridgePlanner`; no test row pins the refusal text.
