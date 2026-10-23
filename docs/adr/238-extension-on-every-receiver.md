# ADR-238: Forward, an extension binds on every receiver, sync and `suspend`, generic or not

## Status
Accepted (2026-10-23)

Builds on [ADR-062](062-forward-callable-plan.md) (the sync plan route),
[ADR-132](132-extension-receiver-shapes.md) (the receiver shapes it admitted),
[ADR-229](229-suspend-on-static-owners.md) (the static `suspend` route) and
[ADR-230](230-flow-on-static-owners.md) (the static flow route). Uses
[ADR-197](197-method-type-parameters-on-the-callable-plan.md) and
[ADR-213](213-top-level-generic-functions-on-the-plan-only.md) for a function's own `T`,
[ADR-222](222-csharp-implemented-generic-interfaces.md) for a generic interface at an input and
[ADR-226](226-generic-value-class-nullable-and-collections.md) for a star projection. Amends
[ADR-108](108-result-return-mapping.md) and [ADR-195](195-result-try-overload.md) (a `Result<T>`
return on a `suspend` owner) and ADR-229's extension row.

## Context

An extension bound only on the receivers ADR-132 listed, and each route kept its own list. A
generic receiver (`fun <T> Bindle<T>.unpack(): T`), a bare type parameter
(`fun <T : Pet> T.introduce()`) and a star projection (`fun Bindle<*>.isPacked()`) were named
skips, and a member extension (`class Groomer { fun Loafer.brush(...) }`) bound with its receiver
lost. The `suspend` extension route accepted a class, nullable class, `String`, primitive or enum
receiver and nothing else, though the same types already bound as a `suspend` parameter on their
ordinary wires. A `suspend` function returning `Result<T>` had no binding on any owner.

A receiver is an input like any parameter, so the useful rule is one sentence: an extension binds
on every receiver the bridge can pass as a parameter, on the same wire.

## Decision

### 1. Sync extensions: the receiver is checked like a parameter

A sync extension function and property stay on the ADR-062 plan route. The own-`T` gate that
admits a parameter now reads the receiver too: a receiver that mentions the extension's own `T`
must be that `T` itself, or an exported generic class, collection, value class or generic interface
at an applied `T`. A `T` nested anywhere else keeps the named `GENERIC` skip. The multi-bound
witness rule counts the receiver as a witnessing parameter.

The C# method keeps the plan's type parameters and ADR-197's `where` clauses
(`T Unpack<T>(this Bindle<T> receiver)`). An extension property renders as a C# 14 block,
`extension<T>(Bindle<T> receiver) where ...`, with the same constraints helper as a function, so a
generic `val` or `var` reads and writes like any property.

Newly admitted at a property receiver: `ByteArray`, `ByteArray?`, a nullable collection and a
nullable bound C# interface. A nullable bound interface binds at every input.

### 2. A bare `T` receiver has a home

`fun <T : Pet> T.introduce()` lands in the `{FirstBound}Extensions` class in the bound's namespace
(`PetExtensions`, `this T receiver where T : IPet`). An intersection bound homes on the first
declared bound, the one ADR-198 spells first. An unbounded `T` (`Any` or `Any?`) lands in
`ObjectExtensions` in the declaring package's namespace. One helper owns that key for the function
and property group-bys, the `suspend` merge, the namespace choice and the entry-point prefix, so
those sites cannot disagree.

An unbounded extension is visible on every C# type wherever its namespace is imported. That is
what the Kotlin author declared, and Kotlin/JVM does the same.

### 3. Projection receivers

A receiver whose type arguments are all `*` (`Bindle<*>`, `List<*>`, and `Set` or `Map` the same
way) gets a synthesized method type parameter named `T` plus the class name:
`IsPacked<TBindle>(this Bindle<TBindle> receiver)` and
`Headcount<TList>(this IReadOnlyList<TList> receiver)`. More than one star numbers them. Kotlin
reads the receiver as `Bindle<*>` or `List<Any?>`, with the element on ADR-212's boxed wire. This
is ADR-226's rule at the receiver position only. At every other position a star projection is
ADR-237's `Box<object?>`, and an `out` projection stays refused, because C# has none for a generic
class.

### 4. `suspend` extensions: the receiver is a leading parameter

The receiver of a `suspend` extension is the first parameter of the ADR-229 static route. The route
keeps its direct receiver arms and gains a fallback that lowers any other extension receiver by the
same shape a `suspend` parameter uses, so a receiver and a parameter of one type share one wire.
The parameter shapes now cover every wire the ordinary route has: a plain interface, a bound C#
interface, a value class over any underlying, `Uuid`, `Instant`, `Duration`, `ByteArray`, a
collection, a nullable enum, a nullable scalar and a nullable interface. Widening the shapes also
admits all of these as a `suspend` parameter, so a receiver is never accepted where the same type
as a parameter is refused.

A `suspend` function's own `T` in the receiver (`suspend fun <T> Bindle<T>.unpackLater(): T`)
follows ADR-214, and a bare `T` receiver homes as in section 2.

### 5. Member extensions

`class Groomhand { fun Loafer.brush(strokes: Int) }` binds as an instance method of the owner with
the extension receiver as the first parameter, named `receiver`: `hand.Brush(loafer, 3)` and
`BrushLaterAsync(Loafer receiver, ...)`. It cannot be `loafer.Brush()`, because C# declares an
extension method only in a non-generic static class. This is Kotlin/JVM's own lowering of a member
extension. A `suspend` member extension takes the class's `suspend` route with the receiver as a
leading parameter.

A member extension *property* binds as accessor methods taking the receiver first, Kotlin/JVM's own
lowering: `val Loafer.nick` is `GetNick(Loafer receiver)`, a `var` adds `SetMood(Loafer receiver, int value)`,
a generic one is `GetPeekInside<T>(Bindle<T> receiver)`, a nullable receiver keeps its `?`, and a
`Flow` typed property is a flow method, `Stream(Loafer receiver)` returning `KotlinFlow<string>`.
A member extension function declared in an `object`, companion, sealed base or arm, interface, enum
or value class takes the receiver first as well (`Hold.InHold(Loafer receiver, int n)`); it used to
generate Kotlin that did not compile.

Two generator bugs were fixed on the way: a member extension lost its receiver, and the ADR-229
enum receiver `_async` export passed the raw ordinal instead of `Drowse.entries[receiver]`.

### 6. `Result<T>` on the `suspend` route, every owner

A `suspend fun ...: Result<T>` binds as `Task<T>` on every `suspend` owner: a class, a sealed type,
the top level, an `object`, a companion, an enum, a value class and an extension. Kotlin calls
`getOrThrow()` inside the coroutine before it completes the callback, so a failure faults the
`Task` with the exception mapped as any thrown one is
([ADR-029](029-exception-type-mapping.md), [ADR-177](177-exception-mapping-by-class-hierarchy.md)).
There is no `TryXAsync` twin: a `Task` already is the try shape, and ADR-195's `out` pair cannot be
spelled on an awaitable. This is one return-shape rule, not an extension rule. A
`suspend inline fun <reified T> ...: Result<T>` reifies `T` at its bound (ADR-213) and binds the
same way.

### 7. `Flow` on an extension

A sync extension returning `Flow`, `StateFlow` or `SharedFlow` rides the ADR-230 static flow route
with the receiver crossing first, on a class, generic, nullable, collection or nullable enum
receiver. A `suspend` extension returning a `Flow` follows ADR-229's flow-result tails. A `Flow`
parameter on a sync extension binds on a generic and a nullable receiver too.

### Diagnostics

The `SUSPEND` and `GENERIC` skip sentences stop listing an extension on another receiver as
unsupported. An extension whose receiver type is not exported (and is not a stdlib type the bridge
maps) is still skipped, because it has no C# type to extend. That is reachability, not a receiver
kind.

## Alternatives

- **Receiver-only arms on the static `suspend` route** (a collection arm, an interface arm, and so
  on): leaves `suspend` parameters unchanged, but gives every kind a second lowering beside the
  parameter one and accepts a receiver where the same parameter is refused. Two lowerings of one
  wire drift apart (the ADR-159 lesson).
- **Move `suspend` onto the ADR-062 plan**: one lowering for sync and `suspend`, and the likely end
  state, but every `suspend` owner would move, not only extensions. Not this decision.
- **Home a bare `T` on `TExtensions`**: what the code would spell without a rule, and meaningless
  in IntelliSense.
- **One `{Library}Extensions` class for every bare `T`**: hides the bound, and a bounded extension
  would sit away from the bound's other extensions.
- **Refuse a member extension**: it is a Kotlin shape authors write, and the instance method loses
  nothing.
- **A `TryXAsync` twin for `suspend ...: Result<T>`**: duplicates what a faulted `Task` already
  says.
- **Refuse a projection receiver**: `Bindle<*>.isPacked()` and `List<*>.describe()` are common
  idioms, and the receiver position is the one place a synthesized method type parameter is
  unambiguous.

## Consequences

- The receiver table in the extensions topic collapses to one rule. A new wire for a parameter is a
  new receiver without a second change.
- A bounded or unbounded `T` extension appears on every C# type that satisfies the bound, where its
  namespace is imported. An unbounded one appears on every type, `int` and `string` included.
- A member extension, function or property, is not callable as `loafer.Brush(...)` or `loafer.Nick`. The
  receiver is an argument, and a property is a pair of accessor methods.
- `Task<T>` from a `Result<T>` hides the Kotlin `Result` wrapper, as the sync `T` return does, and
  a failure surfaces on `await`, not at the call.
- A `suspend` receiver and a `suspend` parameter now share their wire, so a fix to one lands on
  both. A `default(V)` receiver over a reference underlying throws `ArgumentException`, as a
  parameter does (ADR-240).
- The Tier 1 cell that pinned a `suspend inline fun <reified T> R.get(): Result<T>` extension as a
  skip lost its special case.

## Evidence

Labels follow the repository rule: **verified** means executed by a test named here, **read from
source** means the rule is in the generator but no fixture row exercises it.

- **Verified** by `IntegrationTests/EveryReceiverExtensionTests.cs` against the fixture
  `test-library/.../test/bindle/Bindles.kt`: a generic, closed and star-projected class receiver, a
  bounded, intersection-bounded, unbounded and nullable bare `T`, a collection, an interface, a
  bound interface, a value class, an enum and a `ByteArray` receiver, `Uuid`, `Instant`,
  `Duration` and the in-band nullables, each as a sync function, an extension property and a
  `suspend` function where the fixture declares one; the `Result<T>` success and failure, the
  `reified` twin and the top-level form; a `Flow`, `StateFlow` and `SharedFlow` result; a `Flow`
  parameter; and a member extension including its `suspend` form and a class `Result` return. The
  reflected shapes (generic method definition, receiver type, the `DozerExtensions` and
  `ObjectExtensions` namespace, the `receiver` parameter name) are asserted there.
  `UnroutedPositionsTests.cs` pins the positions that stay refused.
- **Verified** by `Tier1EveryReceiverExtensionTest`, including the enum receiver read back from its
  ordinal and the generic interface property receiver, which generated non-compiling Kotlin before
  this decision and now reads at its applied type.
- **Verified** leak and lifetime rows in `LeakTests/LiveHandleTests.cs`:
  `GenericExtension_Unpack_ReturnsToBaseline`,
  `GenericExtensionProperty_Lid_ReturnsToBaseline`,
  `SyncExtension_HandleMintingReceivers_ReturnToBaseline`,
  `SuspendCollectionReceiver_TotalAsync_ReturnsToBaseline`,
  `SuspendBoundInterfaceReceiver_ReturnsToBaseline`,
  `SuspendEveryReceiver_Completion_ReturnsToBaseline`,
  `SuspendResult_Failure_ReturnsToBaseline`,
  `FlowResultExtension_EveryReceiver_ReturnsToBaseline` and
  `FlowParameterExtension_GenericAndNullableReceivers_ReturnToBaseline`; and in
  `LeakTests/CollectabilityTests.cs`:
  `SuspendInterfaceReceiver_IsCollectedOnceTheTaskCompletes`,
  `SuspendBoundInterfaceReceiver_IsCollectedOnceTheTaskCompletes` and
  `SuspendCollectionReceiver_IsCollectedOnceTheTaskCompletes`. The crossings are in
  `LeakTests/BindleCrossings.cs`.
- **Read from source, not exercised**: a `Set<*>` or `Map<*, *>` receiver, and the numbering of
  several stars (`TMap1`, `TMap2`). The fixture has a `Bindle<*>` and a `List<*>` only. A partly
  projected receiver (`Map<String, *>`) is outside the rule and keeps the refusal.
- **Read from source, not exercised**: the own-`T` gate admits a receiver that is a collection, value
  class or generic interface at an applied `T` as it does for a parameter, and the multi-bound
  witness rule counts the receiver. The fixture's generic receivers are `Bindle<T>` and a bare `T`.
- **Same-package evidence**: the fixture declares `Dozer` and its extensions in one package, so it
  cannot tell "the bound's namespace" from "the declaring package's namespace" for a bounded `T`.
  The rule is read from the namespace helper.
- **Verified** for member extension properties by `EveryReceiverExtensionTests.cs` against `Groomhand`
  and the leak row `MemberExtensionProperties_ReturnToBaseline`. **Verified at Tier 1 only** (generated
  C# and Kotlin compile, no runtime call) for member extension functions on an `object`, companion,
  sealed type, interface, enum and value class, by `Tier1MemberExtensionPropertyTest`.
- **Reported by the implementation, not pinned by a test of its own**: the nullable scalar or enum
  receiver on the `suspend` route had an operator-precedence bug in the generated null branch
  (`if (has) X else null.loudly()`), and the member extension lost its receiver. The rows above
  exercise both, but no test targets either alone.
- `scripts/verify.sh`, including NativeAOT, passed on the final tree.

## Amendment (2026-10-23): member extensions on every owner

Section 5 bound a member extension on a class. The same shape declared inside any other owner used
to generate Kotlin that did not compile, with no diagnostic. It now binds on every owner, and the
receiver is always an argument, never a C# `this`, except where the owner is itself a C# extension
class.

| Owner | Extension property | Extension function (sync, `suspend`, `Flow`) |
|---|---|---|
| `object`, companion | static `GetSnooze(Loafer receiver)`, `SetDozeCount(...)` | static `CurlUpAsync(Loafer receiver, int minutes)`, `Purrs(Loafer receiver, int count)` |
| enum | `GetSnooze(this Drowse drowse, Loafer receiver)` | `CurlUpAsync(this Drowse receiver, Loafer receiver_, ...)`, `Purrs(this Drowse receiver, Loafer receiver_, ...)` |
| value class | `paws.GetPawPrint(Loafer receiver)` | `paws.StretchAsync(Loafer receiver_, ...)`, `paws.Kneads(Loafer receiver_, ...)` |
| sealed base and arm | instance `GetSnooze`, `GetDream` | already bound |
| interface | `IChorus.GetShout(string receiver)` | `Echo(string receiver, int times)` |

A `Flow` typed extension property is a flow method on every owner (`Snores(Loafer receiver)`).
Every owner's member walk drops its member extension properties and adds accessor functions in
their place, and an extension function inside an `object`, companion, enum or value class carries the
receiver as an extra leading parameter on both the `suspend` and `Flow` routes.

**Naming.** On the enum and value class `suspend` and `Flow` routes `receiver` is already the owner
slot, so the extension receiver is named `receiver_` (C# would reject two parameters named
`receiver`, CS0100). Calls are positional, so the name only shows in IntelliSense.

**Interface.** The accessors and functions are declared on the generated `IChorus`. A Kotlin
implementer (`Choir`) works, and so does a C# implementer, the receiver its first argument: a
`String` (`IChorus`) or a class handle (`IHideaway` over `Loafer`), since ADR-233 made the slot wire
the plan wire. Rows `Chorus_InterfaceMemberExtensions_BindOnBothSides` and
`Hideaway_ClassReceiverMemberExtensions_BindOnBothSides`.

Consequences:

- A member extension property is a pair of methods on every owner, so none reads as a C# property.
- The enum and value class `receiver_` name is the one place a receiver parameter is not named
  `receiver`.

Evidence: `Tier1MemberExtensionOwnersTest` (eight cells, the generated Kotlin and C# both compile);
six rows in `EveryReceiverExtensionTests.cs` against `Napkin`, `Cradle`, `Drowse`, `Paws`,
`Slumber` and `Choir`/`IChorus` in `Bindles.kt`; leak rows
`MemberExtensions_ObjectOwner_ReturnToBaseline`, `MemberExtensions_CompanionOwner_ReturnToBaseline`,
`MemberExtensions_EnumOwner_ReturnToBaseline`, `MemberExtensions_ValueClassOwner_ReturnToBaseline`,
`MemberExtensions_SealedOwner_ReturnToBaseline` and
`MemberExtensions_InterfaceOwner_BothSides_ReturnToBaseline`. The owner table is verified by those
rows; the `receiver_` name is read from the generated signatures.
