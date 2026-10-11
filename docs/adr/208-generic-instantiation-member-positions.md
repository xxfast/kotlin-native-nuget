# ADR-208: Forward, a closed user generic instantiation (`Box<String>`) binds at every member position as the ADR-199 applied-spelling `ObjectHandle`, and the legacy generic top-level-return route is deleted

## Status

Accepted

Amends [ADR-147](147-generic-class-methods.md) (its "`Crate<Cat>` as a parameter of another class
stays refused-named (it is a different feature, a generic instantiation at a position)") and
[ADR-199](199-generic-sealed-hierarchies.md) (its classifier seam now serves every exported generic
class, not only a sealed one). Builds on [ADR-005](005-object-return-semantics.md) (a fresh wrapper
per read), [ADR-062](062-forward-callable-plan.md) (the callable plan),
[ADR-094](094-reflection-free-generic-dispatch.md) (`Factories`) and
[ADR-197](197-method-type-parameters-on-the-callable-plan.md) (a generic function's own `T`).

Scope: a closed instantiation of an exported generic class. `Flow` and `StateFlow` arguments are
bound by the amendment at the end of this ADR.

## Context

`class Box<T>(val value: T)` exports as the C# generic `Box<T>` (ADR-147). A *reference* to it with
concrete arguments bound at exactly one place, a top-level function return on a hand-built legacy
route (`translateSpecializedFunction` / `addFunctionExports`, gated by `hasLegacyGenericReturnRoute`).
Everywhere else (a class, object or companion property, a member return, any parameter, a top-level
property or parameter) the classifier returned `SpecializedProtocol("generic declaration ...")`, the
planner mapped that to `ForwardPlanSkipReason.GENERIC`, an unrouted candidate, and the member was
`UNROUTED_POSITION`. **Verified by reading.**

A generic *sealed* hierarchy already bound at every member position (ADR-199). Its classifier seam
returns a plain `BridgeType.ObjectHandle` whose `csharpType` is the applied C# spelling
(`global::Ns.Outcome<int>`) and whose `kotlinReadType` is the applied Kotlin spelling
(`pkg.Outcome<kotlin.Int>`), and registers each closed instantiation so `NugetMarshal.Materialize<T>`
can build it from an erased handle (a `Factories` line). Only the outer `isSealed` test chose between
the discriminator and a plain `new X(handle, out _)`. So the plan route already had an end state for
"a closed generic class reference at a position"; the sealed hierarchy was the only thing it was
switched on for.

## Alternatives Considered

### 1. Route an exported generic class reference through the ADR-199 seam (chosen)

An exported, non-`inner`, `CLASS`-kind declaration with its own type parameters returns the ADR-199
reference instead of the `"generic declaration"` protocol. No new `BridgeType`, no new `when` arm:
every downstream site already handles an `ObjectHandle` with `csharpType`, `kotlinReadType` and
`constructType`.

Pros: smallest change; one end state for sealed and plain generics; the top-level return moves onto
the plan with every other position, which lets the whole legacy route go (below). Cons: the ADR-199
helper names now described plain classes too, so they were renamed (`genericSealedReference` ->
`genericReference`, `closedSealedInstantiations` -> `closedInstantiations`, `isErasedSealedArgument`
-> `isErasedTypeArgument`), and the refusal vocabulary had to stop saying "sealed" for a plain class.

### 2. A new `BridgeType.GenericInstance` variant on the ADR-062 plan

The textbook plan-route answer: a sealed variant so the compiler enumerates every site. **Verified by
spike** (scratch worktree at `main` 376365b8): adding one dummy `data class` to `BridgeType` and
compiling `:nuget-processor:compileKotlin --rerun-tasks` fails with 103 "`when` expression must be
exhaustive" errors in 17 files. Each arm would do exactly what the `ObjectHandle` arm beside it does,
because the wire, ownership and reconstruction are an object handle's. Rejected: 103 duplicated arms
buy nothing the applied-spelling fields do not already carry, and ADR-199 made the same call for the
sealed twin.

### 3. Teach the legacy route the member positions

Rejected: it grows the route the codebase is retiring (ADR-062), duplicates per-position code the
plan already owns, and lands on a different end state from ADR-199's sealed binding.

## Decision

**Classifier.** An exported, non-`inner`, non-interface class with its own type parameters routes
through `genericReference` (Alternative 1). A generic interface keeps the `"generic
declaration"` branch, and an inner class that captures a generic owner keeps the ADR-196 one: both
stay named skips. A plain generic class the module does *not* export (`kotlin.Pair`, a dependency's
`Box<T>`) is no longer deferred to the legacy route; it falls to the ordinary unexported-class
refusal, which names it and the remedy.

**Positions and owners, bound.** Class, object and companion property get and `var` set; member
return and parameter; constructor parameter; top-level property, parameter and return; an extension
receiver (`val Box<Int>.label`, a C# extension on `Box<int>`); `List<Box<T>>`; the suspend result and
parameter; the `Flow` / `StateFlow` element. Arguments: primitive, `String`, exported class, enum,
value class, a nullable argument (`Box<String?>`), and another closed instantiation (`Box<Box<Int>>`,
one `Factories` line each). The outer may be nullable (`Box<String>?`, a zero-pointer sentinel).
`Box<T>` and `Self<T>` inside a generic owner bind as a side effect. A generic function's own
`fun <T> crateOf(item: T): Crate<T>` stays on the ADR-197 route, untouched.

**Ownership.** Identical to every `ObjectHandle`: a result is a fresh `StableRef` per read wrapped in
a fresh `Box<T>` the consumer disposes (ADR-005); `Box<Box<int>>.Value` mints a second wrapper,
disposed separately. A parameter, a `var` setter and a constructor argument are borrows: `box._handle`
crosses, Kotlin reads `asStableRef<Box<kotlin.Int>>().get()` and releases nothing. The cast is an
unchecked generic reinterpret, the same one the ADR-199 sealed twin relies on.

**The legacy generic top-level-return route is deleted** (`exports/FunctionExports.kt`,
`translateFunction`, `translateSpecializedFunction`, `addFunctionExports`,
`hasLegacyGenericReturnRoute`, `genericReturnTypeArgumentDiagnostic`, `addEnumAwareParameters`). With
the classifier change the plan carries everything the route carried that compiled. The only shapes it
still carried alone rendered C# that did not compile, and are now named skips: a top-level
generic-interface return (`Shelf<String>`), `Tin<Int>.Infuser<String>`, `Crate<Depot>` over an
`object`, and `Crate<Unit>` (`Box<void>`).

**Behaviour change.** A top-level function returning a generic class that takes an exported-class
parameter used to be skipped on both halves; it now binds, and the parameter is a borrowed nullable
handle like any other class parameter. `crateOfCrate(): Crate<Crate<Int>>` and `Crate<Int>?` returns,
refused by the legacy route, bind too.

**Declined, named.** Each is a skip with a reason, not a roadmap item. A refused generic class is
`SKIPPED_UNSUPPORTED_TYPE` with "its generic class `X` has no C# spelling here: ..." and a hint to
type the member with a closed instantiation C# can spell (a refusal never says "sealed" for a plain
class).

| Shape | Why declined |
|---|---|
| `Box<List<Int>>`, `Box<Map<..>>`, `Box<ByteArray>` | `Materialize<T>` has no factory for a BCL collection or array type argument |
| `Box<(Int) -> Int>` | a lambda has no handle the erased wire can read (ADR-160) |
| `Box<Any>` | `Any` has no C# spelling the erased `T` can round-trip (ADR-147) |
| `Box<*>`, `Box<out Cat>` | C# has no use-site projection of a generic class |
| `Box<Flow<..>>`, `Box<StateFlow<..>>` | bound, see the amendment below |
| `Box<List<Int>>?` at a return | the planner's existing NULLABLE skip |
| a generic interface reference (`Shelf<String>`) | it keeps its `"generic declaration"` skip; spelling a generic interface is a separate feature |
| an inner class capturing a generic owner | ADR-196 spells it `Tin.Latch<T>`; out of scope |
| an unexported outer (`Pair<Int, Int>`) | nothing declares it in C#; the unexported-class refusal names `kotlin.Pair` |
| `MutableStateFlow<Box<T>>` | binds read-only as `KotlinStateFlow<Box<T>>` in this change |
| a C# class implementing a Kotlin interface whose member returns `Box<Int>` | `SKIPPED_UNIMPLEMENTABLE_INTERFACE`; verified on `Manifest` |

## Consequences

- One end state for generic references on the plan route; the legacy generic-return arm and its
  hand-built C# and Kotlin halves are gone.
- Older ADRs that cite `addFunctionExports` or `hasLegacyGenericReturnRoute` are point-in-time
  records of a route that no longer exists.
- Tier 1 cells that used `Box<..>` as the canonical unroutable type moved to a still-unsupported
  shape (`Box<List<Int>>`, a generic interface), and the legacy-route spelling pins became plan-route
  pins.
- `docs/topics/generics.md` gains a section for member positions; the generics rows of
  `docs/topics/supported-features.md` gain them.

## Evidence

Verified natively: `IntegrationTests/GenericInstanceMemberTests.cs` runs the member matrix against
the built `test-library` (`test/boxshelf/BoxShelves.kt`; the Kotlin/Native
`asStableRef<Box<kotlin.Int>>()` executes), and `Tier1GenericInstanceMemberPositionTest` builds the
generated C# with `dotnet build` in three cells (every bound position, the suspend and `Flow`
positions, and the declined shapes). `LeakTests` rows `GenericInstance_ReturnedBoxes_ReturnToBaseline`,
`GenericInstance_PassedBoxes_ReturnToBaseline` and `GenericInstance_SuspendAndFlow_ReturnToBaseline`
return to baseline.
## Amendment (2026-10-10): Flow and StateFlow as the type argument

Part E of this ADR. Where this section and the Part E text above disagree (export naming, the
scope, `MutableStateFlow`), this section is what shipped. It amends
[ADR-194](194-suspend-returning-flow.md) (Alternative 3's reason; ADR-194 carries its own dated
amendment) and builds on [ADR-068](068-suspend-returning-stateflow.md), [ADR-071](071-mutable-stateflow-mapping.md),
[ADR-117](117-forward-abi-collision-names-owning-declarations.md), [ADR-205](205-shared-flow-mapping.md) and
[ADR-207](207-flow-backpressure.md).

### Rule

`box.Value` on a `Box<Flow<E>>` or `Box<StateFlow<E>>` is a `KotlinFlow<E>` / `KotlinStateFlow<E>`
the caller collects (`await foreach`) and disposes. It binds at every position a closed generic
instantiation binds, plus the top-level function return (`crateOfFlow(): Crate<Flow<Int>>` flips
from a named skip to a binding). Also bound: nested `Box<Box<Flow<E>>>`, `Box<Flow<E>?>` and
`Box<Flow<E>>?`. `Box<SharedFlow<E>>` binds as the plain `KotlinFlow<E>` ([ADR-205](205-shared-flow-mapping.md)).

**Materialiser.** One generated export per closed instantiation, keyed on the flow's own handle and
built by the same builder the acquired `Flow` route uses (`handleKeyedFlowCollectExport`), so each
element is projected by the same `itemBoxExpr`. It is named by the mangled Kotlin element
spelling, not by a counter, so it is stable across unrelated edits and readable in a native
stack: `<lib>_flowarg_flow_kotlin_Int_collect`, `<lib>_flowarg_stateflow_<pkg>_Mood_collect` plus
`_value`, `..._String_nullable_collect`. The C# half is one `[DllImport]` set inside `NugetMarshal`
and one `Factories` line per instantiation. Every symbol is module-local and regenerates with its
C# half: **no `nuget_*` runtime export changes**, and the runtime `nuget_stateflow_*` exports are
not used, so one rule covers every element.

**Scope (supersedes the null-scope rule of Part E above).** The holder creates its **own**
`NugetScopeHandle`, and `Dispose` cancels it before releasing the flow handle (`KotlinFlow<T>` /
`KotlinStateFlow<T>` gained a trailing internal `ownedScope` constructor argument). Consequences:

- Disposing the holder cancels its running collections. Their enumerators then complete.
- The scope is no child of any class's scope, so disposing the producing owner neither cancels these
  collections nor waits for them: `nuget_scope_drain` cannot hang on them (the ADR-207 class).
- An abandoned, undisposed holder leaks its scope and job until finalization; callers dispose it.
- Each `.Value` read mints a fresh holder (a fresh flow handle and a fresh scope) over the same
  Kotlin flow; N reads need N disposes. The scope is created with the holder, not by the first
  collect.

Why not the null scope Part E first proposed: a collection on an unowned root could be ended only by its
enumerator, and `Dispose` on the holder would not stop it (the integration cell parks the flow in
`awaitCancellation` to prove the holder now does).

### Declined, named skips (no ROADMAP lines)

| Shape | Outcome |
|---|---|
| `Box<MutableStateFlow<T>>` | binds read-only as `KotlinStateFlow<T>`; `SKIPPED_UNSUPPORTED_INPUT` named once per instantiation. A settable `.Value` needs a compareAndSet delegate and member-owned write exports, and a boxed flow has no member |
| `Box<Flow<Flow<Int>>>`, a lambda element, a `Unit` element, `Flow<*>`, the open `Box<Flow<T>>` | `SKIPPED_UNSUPPORTED_TYPE`, naming the argument |
| a flow argument of a generic **sealed** type (`Outcome<Flow<Int>>`) | stays refused |
| passing a holder **into** Kotlin (`new Box<KotlinFlow<int>>(flow)`) | throws `NotSupportedException`; the flow-parameter line of Phase 7 covers that direction |

### Fix to the previous item (member-position instantiations)

An interface reached **only** as the type argument of a member-position instantiation
(`fun seen(): Box<Sighting>`) got no backing wrapper and no `Factories` key, so `.Value` had
nothing to materialise through at runtime. Fixed in the reachability walk:
`genericInstancePositionTypes` visits function parameters and member-function signatures, and
`erasedInterfaceArguments` recurses through a generic-class carrier (and through a flow element).
Pinned by `Tier1GenericInstanceMemberPositionTest`.

### Evidence

**Verified.** `Tier1FlowTypeArgumentTest` (8 cells, three of them `dotnet build` of the generated
C# for every element kind) and `IntegrationTests/FlowTypeArgumentTests.cs`, run natively: `Int`,
enum, nullable `String` and `StateFlow` enum/`Int` elements, two enumerations of one holder, the
top-level return, nesting, a nullable argument, a `SharedFlow`, a read-only `MutableStateFlow`,
holder disposal ending a parked collection, and owner disposal mid-collection not hanging. Three
`LeakTests` rows return to baseline: `BoxedFlow_CollectAndDispose_ReturnsToBaseline`,
`BoxedFlow_OwnerOrHolderDisposedMidCollection_ReturnsToBaseline`,
`BoxedFlow_EachValueReadMintsAFreshHolder_ReturnsToBaseline`. `ByteArray` and `Throwable` elements
are verified by Tier 1 and `dotnet build` only, not run natively.

**Inferred.** `Flow<E>` and `Flow<E?>` over a reference `E` are one runtime `Type`, so their two
`Factories` lines collide: the nullable line is rendered last and its export covers both, which is
safe only because no cell declares both in one module.
