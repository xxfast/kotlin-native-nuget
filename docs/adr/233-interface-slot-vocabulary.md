# ADR-233: Forward, a C#-implemented interface slot carries every type an ordinary member plan carries

## Status
Accepted

Amends [ADR-084](084-csharp-implemented-interfaces.md) (the slot vocabulary and the
"object-typed and collection-typed slot" deferral), [ADR-222](222-csharp-implemented-generic-interfaces.md)
(a class or generic handle in a generic interface's slot) and
[ADR-232](232-csharp-implemented-async-interfaces.md) (the `Flow` element vocabulary and the
`ArgumentException` for a Kotlin-backed hot flow).

## Context

The planner that decides whether a C# class implementing a Kotlin interface can cross to Kotlin
kept its own type table: `String`, five primitives, `Boolean`, enums, `Unit`, `Throwable` and the
interface's own `T`. Anything else (an exported class, a generic class, a collection, a value
class, a nullable primitive or enum, `Char`, `Instant`, `Duration`, `Uuid`, `ByteArray`, a lambda,
a member with more than two parameters, a `Flow` of any of those) refused the bridge with
`SKIPPED_UNIMPLEMENTABLE_INTERFACE`, although a class member typed the same way binds. A member's
own type parameter (`fun <R> map(...)`) stays refused, see ADR-222.

## Decision

The slot wire is the plan's wire. The planner asks the same classifier a class member plan asks and
lowers the answer with the same parameter and result shapes, so an interface slot and a class
member typed alike cross alike. The thunk reads and writes with the helpers the member plan already
renders, and the open-slot fill rule of [ADR-216](216-own-type-parameter-in-result-and-generic-class.md) runs at
thunk entry.

**One box per boxable type.** A class or generic class handle, an interface, a collection, a value
class, a nullable primitive or enum, `Char`, `Instant`, `Duration`, `Uuid` and `ByteArray` each
cross as one box, read and written with the plan's helpers. Nullable handles cross as null.

**Handle ownership.** A class handle a C# slot returns is wrapped as owned by Kotlin through the
[ADR-222](222-csharp-implemented-generic-interfaces.md) retain export. A class handle Kotlin passes
into a slot is borrowed for the call, as at every plan position. Faults thrown by any such slot
are catchable in Kotlin ([ADR-161](161-csharp-callback-exception-into-kotlin.md)).

**Any arity.** The two-parameter cap is gone; parameters cross in declaration order, one slot per
native parameter.

**Inherited `T`.** A slot declared by a generic super-interface is typed with the substituted
argument: `interface Hamper : Basket<String>` declares `string Item` and `string Put(string item)`
inherited through `IBasket<string>`, and a C# `Hamper` implements them.

**Lambdas.** A lambda in a slot crosses as a lambda property does
([ADR-236](236-lambda-properties-on-every-owner.md)). A Kotlin lambda passed into a slot, plain or
`suspend`, nullable too, arrives as the BCL delegate (`Func<>`/`Action<>`, or
`Func<..., CancellationToken, Task<R>>`) bound to the plan's `KotlinFunc`/`KotlinSuspendFunc`
wrapper over the handle Kotlin retained. A C# lambda a slot returns is minted into a Kotlin lambda
through the managed-lambda exports, as a returned lambda is
([ADR-239](239-returned-lambdas-and-the-callback-vocabulary.md)).

**Flows.** A `Flow` slot carries the same boxed elements as any slot (a handle, an enum, a nullable
string, a value class), a nullable flow is `null` or a flow, a `var` flow slot has a setter slot,
a nullable hot flow reads as `null` or its current flow, and a `suspend` member returning a hot
flow is `Task<KotlinStateFlow<T>>`.

**Fixes shipped alongside.**

- A suspend-flow collect export for an owner's `T` imported `T` in the generated Kotlin and failed
  to compile; it reads the awaited flow over the erased owner.
- A `ByteArray` parameter named `bytes` with a `ByteArray` result declared `bytesHandle` twice
  (CS0841 and CS0136); the locals are now distinct.
- A cold `Flow` `var` on a non-generic, non-sealed owner has a real setter, taking a
  `KotlinFlow<T>` that wraps a Kotlin flow. A flow made in C# wraps none and is refused at the call
  with `ArgumentException`, the rule a hot-flow slot already follows. A hot-flow `var`, a generic
  owner and a sealed owner keep the getter only.
- A Kotlin-backed hot-flow property wrapper (`StateFlow`, `SharedFlow` and mutable forms) mints its
  own Kotlin handle when asked, so it can be handed back through a C# implementation. ADR-232's
  `ArgumentException` for such a wrapper no longer applies to a Kotlin-backed member's flow.
- `KotlinMutableStateFlow<T>.Create` and `KotlinMutableSharedFlow<T>.Create` mint a scope; `Dispose`
  releases it. Before, only the finalizer did.

## Alternatives Considered

**One `when` arm per type in the planner.** That is how the vocabulary stopped at nine entries. The
planner has no type table of its own now.

## Consequences

- No source break: an interface that already declared these members on `I<Name>` keeps its
  declaration, and an existing implementer now also crosses to Kotlin.
- A cold `Flow` `var` gains a setter, so a generated API grows.
- A C# class implementing a Kotlin interface now has one rule to follow: implement each member as
  `I<Name>` declares it.

## Evidence

Verified by test: `IntegrationTests/InterfaceSlotVocabularyTests.cs` (a handle returned and
passed, a nullable handle, a generic handle each way, collections of strings, numbers and handles,
a value class, nullable `Int`, `Boolean` and enum, `Char`, `Instant`, `Duration`, `Uuid`,
`ByteArray`, a lambda each way, a suspend lambda, a generic interface result Kotlin-backed and
C#-made, four parameters, a fault from a handle slot and a collection slot, every flow shape
including a mid-stream fault, a `var` flow replaced by Kotlin, nullable flows, a `suspend` member
returning a hot flow, an inherited `T`, a Kotlin-backed hot flow handed back, a `ByteArray`
parameter named `bytes` with a `ByteArray` result, and a suspend flow over a generic owner's `T`).
Leak rows 20a to 20e in `LeakTests/LiveHandleTests.cs`
(`CSharpInterfaceSlotVocabulary_Completion_ReturnsToBaseline`,
`CSharpInterfaceSlotVocabulary_Fault_ReturnsToBaseline`,
`CSharpInterfaceFlowSlots_Completion_ReturnsToBaseline`,
`CSharpInterfaceFlowSlots_Fault_ReturnsToBaseline`,
`CreatedHotFlows_DisposeReleasesTheirScope_WithoutAGc`) and `CSharpSitter_ReleasedAfterKotlinGc`
in `LeakTests/CollectabilityTests.cs`. Tier 1: `Tier1InterfaceSlotVocabularyTest`, which also
compiles the generated C# against an implementation.

Verified by reading generated code: the `ISitter` and `ITracker` declarations and the
`Func<int, string> Purrer()` slot in the generated `Interop.cs`; the cold `Flow` `var` setter by
`Tier1FlowVarPropertySetterTest` (no runtime row exercises a class-level `Flow` `var` setter).
