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
refused here; the next change binds them.

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
| `Box<Flow<..>>`, `Box<StateFlow<..>>` | refused here; the next change binds them |
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
