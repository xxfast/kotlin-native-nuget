# ADR-233: Forward, a C#-implemented interface slot carries every type an ordinary member plan carries

## Status
Accepted

Amends [ADR-084](084-csharp-implemented-interfaces.md) (the slot vocabulary and the
"object-typed and collection-typed slot" deferral), [ADR-222](222-csharp-implemented-generic-interfaces.md)
(a class or generic handle in a generic interface's slot),
[ADR-232](232-csharp-implemented-async-interfaces.md) (the `Flow` element vocabulary and the
`ArgumentException` for a Kotlin-backed hot flow) and
[ADR-160](160-callback-parameter-on-the-forward-plan.md) (a class or interface member returning a
lambda).

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

**Lambdas.** A Kotlin lambda passed into a slot arrives as the plan's `Func<>`/`Action<>` over
`KotlinFunc`/`KotlinSuspendFunc` (`Invoke`/`InvokeAsync`). A C# lambda a slot returns rides the
[ADR-160](160-callback-parameter-on-the-forward-plan.md) delegate; its registration key is released
by a cleaner when Kotlin drops the lambda. A `suspend` slot taking a suspend lambda follows
[ADR-210](210-flow-and-suspend-lambda-parameters.md).

**Flows.** A `Flow` slot carries the same boxed elements as any slot (a handle, an enum, a nullable
string, a value class), a nullable flow is `null` or a flow, a `var` flow slot has a setter slot,
a nullable hot flow reads as `null` or its current flow, and a `suspend` member returning a hot
flow is `Task<KotlinStateFlow<T>>`.

**Fixes shipped alongside.**

- A suspend-flow collect export for an owner's `T` imported `T` in the generated Kotlin and failed
  to compile; it reads the awaited flow over the erased owner.
- A `ByteArray` parameter named `bytes` with a `ByteArray` result declared `bytesHandle` twice
  (CS0841 and CS0136); the locals are now distinct.
- A class or interface member returning a lambda returns the C# delegate (`Func<int, string>`,
  `Action<int>`). Before, the class route skipped the member.
- A flow `var` has a real setter on every owner the flow route serves: a class, an interface, a
  sealed base, a sealed arm and a generic class, for `Flow`, `StateFlow`, `SharedFlow` and the
  mutable forms. The setter hands Kotlin the flow its `KotlinFlow<T>` owns, or mints one for a
  member's flow (released after the call). A cold flow made in C# (`KotlinFlow<T>.From`) crosses
  through ADR-210's `flowForKotlin` seam on a sibling `_set_<p>_source` export, one
  `GetAsyncEnumerator` per Kotlin `collect`. C# makes a hot flow only through `Create`, which owns
  its Kotlin flow, so a hot setter needs no seam. An object, a companion, an enum and a top-level
  flow property have no flow route at all, so the whole property stays a named
  `SKIPPED_UNSUPPORTED_PROPERTY`.
- Every Kotlin-backed member flow wrapper (a property of any flow type, and a re-invoked flow
  function return: `Flow`, `StateFlow`, `SharedFlow`) mints its own Kotlin handle when asked
  (`_get_<p>_flow`, `_<f>_flow`), so it can be handed back through a C# implementation or assigned
  to a flow `var`. A function's mint re-runs it with the same arguments, as `ReplayCache` does. A
  function taking an ADR-210 asynchronous parameter mints nothing, since its keys belong to one
  call; a held `MutableStateFlow` or `MutableSharedFlow` return already owns its handle.
- A `var` flow slot registers its `KotlinFlow<E>` factory entry while the bridge is planned, and
  the C# translator reads the closed flow arguments after bridge planning, so an element no other
  position wraps in a `Flow` (`Flow<Paw>` only on a `var` slot) materialises.
- A `Flow<Throwable>` slot carries each C# exception as its managed-exception text, read in Kotlin
  as the `NugetManagedException` a `Throwable` slot result already is.
- A `suspend` slot returning a `Flow` of the interface's own `T` starts through
  `NugetTasks.BeginFlow<TFlow, T>`, whose task the non-generic boxed End thunk registers without
  naming `T`.
- A backticked member reaches the bridge under the class route's name: a keyword (`in`) is `In` on
  both, and a name with a space and no `@CSharpName` is skipped on both and named.
- `KotlinMutableStateFlow<T>.Create` and `KotlinMutableSharedFlow<T>.Create` mint a scope; `Dispose`
  releases it. Before, only the finalizer did.

## Alternatives Considered

**One `when` arm per type in the planner.** That is how the vocabulary stopped at nine entries. The
planner has no type table of its own now.

## Consequences

- No source break: an interface that already declared these members on `I<Name>` keeps its
  declaration, and an existing implementer now also crosses to Kotlin.
- A class or interface member returning a lambda changes from skipped to bound, and a flow `var`
  gains a setter, so a generated API grows.
- A C# class implementing a Kotlin interface now has one rule to follow: implement each member as
  `I<Name>` declares it.

## Evidence

Verified by test: `IntegrationTests/InterfaceSlotVocabularyTests.cs` (a handle returned and
passed, a nullable handle, a generic handle each way, collections of strings, numbers and handles,
a value class, nullable `Int`, `Boolean` and enum, `Char`, `Instant`, `Duration`, `Uuid`,
`ByteArray`, a lambda each way, a suspend lambda, a generic interface result Kotlin-backed and
C#-made, four parameters, a fault from a handle slot and a collection slot, every flow shape
including a mid-stream fault, a `var` flow replaced by Kotlin, nullable flows, a `suspend` member
returning a hot flow, an inherited `T`, a Kotlin-backed hot flow handed back from a property and
from a member function, a `ByteArray` parameter named `bytes` with a `ByteArray` result, a suspend
flow over a generic owner's `T`, and a C# `suspend` slot returning a Flow of its own `T`).
`IntegrationTests/CatterySlotTests.cs` (a generic super-interface inherited at a closed `Int`,
Kotlin-backed member-function hot flows handed back, a Kotlin-backed interface and a class `var`
flow set to another member's flow and to a C#-made flow, a `var` flow slot of an element no other
position wraps, a `Flow<Throwable>` from C#, and a class member returning a lambda).
`IntegrationTests/FlowVarSetterOwnerTests.cs` (`StateFlow`, `MutableStateFlow`, `SharedFlow` and
`MutableSharedFlow` `var`s, a sealed base and arm, and a generic owner, each set from C#).
Leak rows 20a to 20g in `LeakTests/LiveHandleTests.cs`
(`CSharpInterfaceSlotVocabulary_Completion_ReturnsToBaseline`,
`CSharpInterfaceSlotVocabulary_Fault_ReturnsToBaseline`,
`CSharpInterfaceFlowSlots_Completion_ReturnsToBaseline`,
`CSharpInterfaceFlowSlots_Fault_ReturnsToBaseline`,
`CreatedHotFlows_DisposeReleasesTheirScope_WithoutAGc`,
`ClassMemberReturnedLambda_DroppedDelegate_ReleasedByTheGc`,
`CSharpInterfaceSlotVocabularyAmendment_Completion_ReturnsToBaseline`) and `CSharpSitter_ReleasedAfterKotlinGc`
in `LeakTests/CollectabilityTests.cs`. Row 20f verifies that the GC releases the `KotlinFunc` a
class member's returned delegate wraps, and row 20g the amendment's flow slots. Tier 1: `Tier1InterfaceSlotVocabularyTest`, which also
compiles the generated C# against an implementation; `Tier1FlowVarPropertySetterTest` (every flow
`var` setter, its `_flow` and `_source` exports, and the whole-property skip on the static and enum
owners); `Tier1BridgeBacktickedNameTest` (a keyword member is `In` on both routes; a spaced member
is skipped and named on both, which no runtime fixture can carry because Kotlin/Native links no
symbol with a space).

Verified by reading generated code: the `ISitter` and `ITracker` declarations and the
`Func<int, string> Purrer()` slot in the generated `Interop.cs`.
