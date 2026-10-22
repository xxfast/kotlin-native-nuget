# ADR-225: Forward, a generic value class's own members, properties, secondary constructors and `suspend` members bind on its record struct

## Status
Accepted

Extends [ADR-220](220-generic-value-class-record-struct.md) (the positional generic record struct,
which carried no members) and [ADR-229](229-suspend-on-static-owners.md) (the value-class `suspend`
route). Builds on [ADR-147](147-generic-class-methods.md) (hoisted externs, the bare-`T` box),
[ADR-197](197-generic-member-own-type-parameters.md) (a member's own `T`) and
[ADR-198](198-trampoline-for-generic-class-positions.md) (the trampoline). ROADMAP Phase 4.

## Context

ADR-220 bound the struct and every closed instantiation of it at function positions, but named every
public own member and secondary constructor of a generic value class as a skip, because the
value-class member route had no receiver spelling for a type parameter. A member with its own `<R>`
was also refused on every value class, generic or not (`isForwardGenericMemberOwner` returned false
for a value class).

## Decision

### 1. One export per member serves every instantiation

Kotlin erases a generic value class to one runtime class, so a member needs no per-instantiation
export. The receiver is the class's substituted underlying at its own `T`: the bare-`T` box for
`Sticker<T>`, a plain `long` for the phantom `Decal<T>`. `valueClassEntries` no longer returns early
for a generic class, and the diagnostic walk that named the skips is deleted. Members that still
cannot bind get the ordinary per-member skips of the route.

### 2. Members ride `ForwardCallableOrigin.VALUE_CLASS_MEMBER`, with an error slot

The non-generic `valueClassMethod` projection has no error slot, so a member that throws, or a
receiver whose `init` fails, would abort the process. The new origin carries the standard error out
parameter and the general block body (prelude, `finally`), so the receiver and any `T` parameter box
are released on every path. The C# member reads the receiver into a local, passes it as parameter
zero, and guards the zero struct:

```
default(Badge) carries no Value; construct a Badge instead
```

The guard applies where the underlying is a non-null `T`. An unconstrained `T` accepts `default`
(it carries `null`), and a `struct`-constrained enum `T` has no guard.

### 3. Kotlin re-wraps the receiver inside the export

The receiver crosses as its underlying and Kotlin rebuilds the value class in the export, so `init`
runs inside the error slot (a failing `init` throws to C# as the matching `Kotlin*Exception`).
`Sticker<T>` infers its argument from the bounded read. A phantom has nothing to infer from, so the
export spells `pkg.Decal<Nothing>(value)`: `Nothing` satisfies every bound and no member signature
can observe it. A trampolined class `T` ([ADR-198](198-trampoline-for-generic-class-positions.md))
runs the whole body, re-wrap included, under the trampoline unchanged.

### 4. Secondary constructors run `init` at C# construction

Each secondary constructor becomes a `_create_N` export returning the underlying, inside the error
slot. C# renders `public Sticker(string label, int times) : this(CreateChecked_2(label, times))`,
where `CreateChecked_N` is a private static on the struct. A primary constructor still does not run
`init` at C# construction, which is the asymmetry ADR-220 documents.

The Kotlin spelling is the explicit class when a parameter witnesses `T`, `Nothing` for a phantom,
and `pkg.Sticker<Any?>(args)` (the erased bound otherwise) when no parameter mentions `T`. The cast
that produces `T` from a parameter that does not mention it is the author's, not the bridge's. An
unwitnessed secondary constructor on an unspellable bound (an `Enum<T>` or intersection bound) runs
under the ADR-198 trampoline.

### 5. Externs hoist into `{Name}Native`

A generic C# type cannot hold `[DllImport]` (CS7042). The struct hoists its externs into
`internal static class StickerNative`, the [ADR-147](147-generic-class-methods.md) pattern, and the
struct keeps thin private forwarders. The struct header stays constraint-free: no C# `where` clause
is added for the class's bound, so Kotlin checks it, and a violated bound surfaces as
`KotlinInvalidCastException` from the member call: `new Doze<int>(3).Label()` throws it (the
receiver read's `Enum` cast fails in Kotlin) and the process carries on
(`Member_OnAViolatedBound_IsRefusedInKotlin`). A non-null bound (`T : Any`) is refused earlier, in
C#, by section 2's `default(V)` guard.

### 6. Properties and a member's own `<R>`

Member properties (`val shiny: Boolean`, `val peeled: T`) bind as C# properties on the same route.
`isForwardGenericMemberOwner` admits a value class owner, so `fun <R> pairWith(other: R)` binds as
`PairWith<R>` on `Sticker<T>`. Its name is de-clashed against the struct's parameters, so the
method's `R` never collides with `T`. The same holds for a non-generic value class
(`Collar.Tagged<R>`).

### 7. `suspend` members

A `suspend` member extends the ADR-229 value-class async route with a receiver boxed as `T`. Kotlin
rewraps the receiver inside the coroutine, so a failing `init` faults the returned `Task` instead of
throwing from the call. `Task<T> PeelLaterAsync()` and `Task<string> DescribeLaterAsync()` bind on
`Sticker<T>`, and `Task<long> SerialLaterAsync()` on the phantom.

Alternatives rejected:

- A receiver as a boxed handle (`StableRef` of the boxed value class) would mint and dispose a box
  per call for a value type, and needs the box pair of erased positions.
- One export per closed instantiation is unbounded, and `Sticker<T>` at an own `T` has none to
  generate for.
- An extension-function lowering would move the problem: extensions with their own type parameters
  were refused.

## Consequences

- The `GENERIC` diagnostic hint no longer mentions members or secondary constructors.
- ADR-220's member skip row and its deferral are replaced by this decision.
- Tier 1: `Tier1GenericValueClassMemberTest`; integration: `GenericValueClassMemberTests.cs`.
- Leak rows in `LeakTests/LiveHandleTests.cs`:
  `GenericValueClassMembers_ReceiverParameterAndResultBoxes_ReturnToBaseline`,
  `GenericValueClassMembers_FaultPaths_ReturnToBaseline`,
  `GenericValueClassSuspendMembers_ReturnToBaseline`.

### Inferred claims

1. That `Nothing` satisfies every bound is **verified** by a `kotlinc-native` 2.4.20 spike (repo
   pins 2.4.10) of `Decal<Nothing>`, `Decal<Any?>` and a `Comparable` bound; inside a generated
   export it is covered by the integration rows on `Decal<Cat>`.
