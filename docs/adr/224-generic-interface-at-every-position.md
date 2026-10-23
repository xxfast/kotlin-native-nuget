# ADR-224: Forward, a generic interface binds at every position: `fun keep(): Keepsake<String>` returns `IKeepsake<string>`

## Status
Accepted

Amends [ADR-222](222-csharp-implemented-generic-interfaces.md) (its "Not shipped" paragraph and its
"erased position is declined" sentence) and
[ADR-208](208-generic-instantiation-member-positions.md) (the 2026-10-22 amendment that kept the
named skip at output and erased positions). Amends
[ADR-174](174-interface-async-members.md) (a generic interface's async members are no longer a named
skip).

## Context

ADR-222 let a C#-implemented `IKeepsake<string>` reach Kotlin through a parameter. Every other
position still carried the `generic declaration` skip: a return, a getter, a class, object or
top-level property, a nullable, a collection component, a `suspend` result, a `Flow` element, a
`Box<Keepsake<String>>` argument, a lambda payload or result, and `Keepsake<T>` inside a generic
function or class. A C# implementation Kotlin stored could not be read back.

Two things were missing. A Kotlin-backed value needs a C# wrapper that is itself generic and that
dispatches over a receiver Kotlin has erased. A C#-implemented value crossing an erased position
(`List<IKeepsake<string>>`, `Box<IKeepsake<Cat>>`) needs a handle minted without reflection, for an
instantiation only the consumer chose.

## Alternatives Considered

1. **Closed `HandleFor` arms per closed instantiation plus a call-site slot fill for open ones.**
   AOT-safe, but a consumer-constructed `new Box<IKeepsake<Cat>>(basket)` runs `Wrap<T>` inside the
   `Box<T>` constructor before any Kotlin member mentioning `Keepsake` is called. Rejected: it
   leaves that call unserved.
2. **`MakeGenericType` in `HandleFor`.** Rejected by ADR-222 (reflection; a value-type
   instantiation has no code under NativeAOT).
3. **A non-generic marker interface.** A class implementing `IKeepsake<int>` and `IKeepsake<string>`
   would have two most-specific `MintBridge` implementations. The generic, self-typed marker has no
   diamond.
4. **A backing wrapper erased over `object` (`Keepsake : IKeepsake<object>`).** It cannot be typed
   `IKeepsake<string>`.
5. **A new `BridgeType` variant.** `BridgeType.Interface` already carries every applied field.

## Decision

A generic interface is a `BridgeType.Interface` at every position, so one classification serves
all of them.

**Kotlin-backed values.** A reachable generic interface gets a generic backing wrapper
`Keepsake<T> : IKeepsake<T>` beside `IKeepsake<T>`. Its externs are hoisted into a `KeepsakeNative`
class (CS7042), and its members dispatch over the erased receiver exactly as a generic class's
members do (`asStableRef<Keepsake<Any?>>()`; a self-bounded `T` takes the ADR-198 trampoline). A
return reads `TryResolveCSharp(h, out IKeepsake<string> o) ? o : new Keepsake<string>(h, out _)`.
A closed instantiation registers its `NugetFactory` line; an open one is filled at the call site
(`NugetFactory<IKeepsake<T>>.Create ??=`) and by the static constructor of every generic class that
implements the interface.

**C#-implemented values.** A C# implementation Kotlin stored comes back as the same object. An
erased input (a collection component, a `Box<..>` argument, a lambda result, a `T` boxed as
`IKeepsake<X>`) is minted by a default interface method: each bridgeable generic interface extends
the per-library `INugetBridgeable<out TSelf>` marker and implements its `MintBridge` explicitly,
and `Wrap<T>`/`HandleOf<T>` test `value is INugetBridgeable<T>` after `INugetHandle`. The marker is
`[EditorBrowsable(Never)]` with an `internal` member, so a consumer class implements the interface
without implementing it. A spike confirmed the shape compiles across two assemblies, serves a
value-type instantiation, picks the instantiation the static `T` names when one class implements
two, and gives identical output with no IL warnings under the JIT and NativeAOT.

**Async members.** `SKIPPED_GENERIC_INTERFACE_ASYNC_MEMBER` is removed. The ADR-174 emitters run over
the same erased owner a generic class's `suspend` and `Flow` members use, so `IHeirloom<T>` declares
`Task<T> PolishAsync(...)`, `KotlinFlow<T> Glints()` and is `IAsyncDisposable`.

**Constraints.** A constrained generic interface restates its bridge's `where` clause
(`IPerch<T> where T : IFurry`) so the mint compiles. A stdlib bound such as `Comparable<T>` drops to
`where T : notnull` with `INFO_DROPPED_BOUND`, as it does on a generic class.

**A refused bridge.** An interface whose C# bridge cannot plan (a member with its own type
parameter) still binds every output
position, because a Kotlin-backed value needs no bridge. Each input and erased input is skipped with
the bridge's named refusal, since a C# implementation can never reach Kotlin and so can never come
back.

**Member type parameters.** A generic interface member with its own `<R>` is left off `IKeepsake<T>`
as the class route does. C# cannot call `impl.Map<R>` with an `R` Kotlin erased.

## Consequences

- `fun keep(): Keepsake<String>` binds as `IKeepsake<string> Keep()`; a Kotlin `Locket` is a
  `Keepsake<string>` the caller disposes, a stored `Diary` is the same `Diary`.
- Two bugs fixed. A generic bridge reached only at an erased position emitted a call to a
  nonexistent `NugetMarshal.WrapOwned` (CS0117). A generic interface member with its own `<R>` was
  declared as a phantom `IntPtr Map(T)`, which no implementer could satisfy (CS0535).
- `IKeepsake<T>` now lists `INugetBridgeable<IKeepsake<T>>` in its base list. Hand-written
  implementers need no change.
- An implementer of a generic interface that gains `suspend` or `Flow` members must implement them,
  as for a non-generic one (ADR-174).
- A C# `IKeepsake<int>` that comes back at an `IKeepsake<string>` position throws a catchable
  `InvalidCastException` from the token cast in `TryResolveCSharp`, and the process carries on.
  Kotlin code builds the mismatch with an unchecked cast (`launder` in `KeepsakeTrove.kt`); no
  generated signature does. The transfer handle is disposed before the cast, so nothing leaks.
  `CSharpKeepsake_ReadBackAtTheWrongInstantiation_ThrowsInvalidCast` runs it.

## Evidence

- `GenericInterfaceOutputTests` in `IntegrationTests/`, with the `Trove`, `Mantel`, `Heirloom` and
  `Perch` fixtures in `KeepsakeTrove.kt`: identity at every output, Kotlin-backed wrappers, erased
  inputs over a consumer-chosen `IKeepsake<short>`, async members and the constrained interface.
- `LeakTests`: `GenericInterfaceOutput_ReturnedKeepsakes_ReturnToBaseline`,
  `GenericInterfaceOutput_ErasedInputs_ReturnToBaseline` and
  `CSharpGenericKeepsake_StoredThenDropped_ReleasedAfterKotlinGc`.

## Amendment (2026-10-23): a member's own `<R>` is declared erased

The **Member type parameters** paragraph no longer holds: a generic interface member with its own
`<R>` is declared on `IKeepsake<T>` with `R` erased to `object?` (`object? Map(Func<T, object?> f)`),
and the interface can be implemented in C# and passed to Kotlin. Three shapes are
still refused, named ([ADR-237](237-kotlin-any-as-object.md)).
