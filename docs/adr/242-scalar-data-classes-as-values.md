# ADR-242: Immutable all-scalar data classes cross as C# values with no handle

## Status

Proposed

## Context

[ADR-008](008-data-class-mapping.md) maps every Kotlin `data class` to a C# `class : IDisposable`
over an opaque `StableRef` handle, with `Equals`, `GetHashCode`, `ToString` and `Copy` delegated to
Kotlin. Issue #611 reports what that costs for the commonest data class shape, a DTO:

```kotlin
data class Toy(val name: String, val colors: List<String>) {
  val label: String get() = "$name in ${colors.joinToString()}"
}
```

The consumer gets a handle it must dispose, so it copies every `Toy` into its own `record` and
re-implements `label` by hand, and the two drift. Requirements:

1. A data class whose constructor parameters and properties are all strings, primitives, enums or
   collections of those reads in C# with no handle to dispose and no `Dispose()` in sight.
2. Its computed properties (and methods) still return what Kotlin returns.
3. Value equality and `Copy` keep ADR-008's behaviour: equality is Kotlin's `equals`, and `Copy`
   (and construction) run Kotlin's constructor, so `init { require(...) }` still rejects bad values.

Forbidden: a snapshot record that drops computed members, and a `record class` whose `with`
expression builds a value Kotlin's constructor never saw (the trap ADR-008 rejected). A data class
holding a handle-typed member (an ordinary class, an interface, a lambda, a `Flow`) stays as it is.

The tension: computed members must come from Kotlin, yet nothing Kotlin-side may stay alive in a
way the consumer has to manage.

The codebase has already solved the one-component version of this. A value class renders as a
`readonly record struct` holding its underlying value ([ADR-014](014-value-class-mapping.md),
[ADR-035](035-value-class-primary-constructor-validation.md)); every constructor routes through a
Kotlin `_create` export so `init` runs, and every member re-wraps the receiver on the Kotlin side
and calls the member on that fresh instance ([ADR-225](225-generic-value-class-members.md),
[ADR-235](235-value-class-wire-underlyings.md): "Kotlin re-wraps so `init` runs at the crossing").
**Verified by reading**: `forward/ForwardCallablePlanner.kt:1365-1408` plans value-class
constructors and properties on `ForwardCallableOrigin.VALUE_CLASS_MEMBER` with
`withValueClassConstruction(...)`; `forward/ForwardCirPlanProjection.kt:302-330` passes the receiver
as parameter zero. The reverse direction does the N-component version for a C# struct
([ADR-056](056-csharp-structs-in-kotlin.md), [ADR-059](059-nested-struct-components-in-kotlin.md)).
This ADR is the forward N-component generalisation.

Prior art outside the repo points the other way and is worth stating. Swift Export keeps a Kotlin
data class a Swift `class` (reference type); a JetBrains engineer said unconditional conversion to
a struct would be incorrect and an opt-in annotation might come later
([Swift Export docs](https://kotlinlang.org/docs/native-swift-export.html),
[kotlinlang Slack thread](https://slack-chats.kotlinlang.org/t/26928229/has-anyone-seen-anything-about-value-type-swift-struct-expor),
inferred from those pages). ObjC export renders a data class as a class with `doCopy`, `isEqual`,
`hash` and `description`
([interopedia](https://www.github.com/kotlin-hands-on/kotlin-swift-interopedia/blob/main/docs/classesandinterfaces/Data%20classes.md)).
Their reason (a Kotlin instance has identity, mutable state and side-effecting `init`) is the
reason this ADR gates on a strict predicate instead of converting every data class.

## Alternatives Considered

### 1. Value snapshot, members re-materialise a transient Kotlin instance per call (chosen)

C# holds the components as plain get-only properties. Every Kotlin-defined member (computed
property, method, `Equals`, `GetHashCode`, `ToString`, `Copy`) mints a transient Kotlin instance
from the components through the existing `_create` export, calls the existing handle-receiver
export, and releases the handle before returning. A value Kotlin returns is read component by
component through the existing getters and its handle released at once.

- Pro: nothing to dispose and nothing held between calls, so `NugetMarshal.LiveHandles` returns
  to baseline after every crossing deterministically, without a GC.
- Pro: a computed member runs Kotlin's own code on every access, as Kotlin does (a getter that
  reads a clock reads it again, matching Kotlin; eager evaluation would not).
- Pro: every Kotlin export it needs already exists (`_create`, `_get_<prop>`, `_equals`,
  `_hashcode`, `_tostring`, `_copy`, each member's export, `ClassExports.kt:567-611`, verified by
  reading). The change is in classification and C# projection only.
- Con: a member call costs three crossings (create, call, release) instead of one; a component
  read costs zero instead of one. Components are re-marshalled per member call, so a large `List`
  component is copied again on every computed-member access.
- Con: `init` runs again at every re-materialisation. A pure `require` passes again; a
  side-effecting `init` (a counter, a log line) repeats. The value class route already accepts
  this (ADR-235); a data class with that shape diverges and is documented, not detected.
- Con: Kotlin identity (`===`) is not preserved across a round trip. Acceptable for a value.

### 2. Snapshot with computed properties evaluated eagerly at the crossing

Read every computed property once when the value crosses and store it.

- Pro: cheapest reads.
- Con: does not cover methods with parameters at all, so requirement 2 fails for `describe(loud)`.
- Con: freezes a non-pure getter at crossing time; that is not "what Kotlin returns".
- Con: a value constructed in C# would have to cross to compute them anyway. Rejected.

### 3. Keep the handle, finalizer-owned, and drop `IDisposable` from the public surface

The smallest diff: the existing `NugetKotlinHandle : SafeHandle` already releases on finalization
(`cir/CirRenderer.kt:76-84`, verified by reading), so hiding `Dispose` satisfies requirement 1's
letter.

- Con: Kotlin memory lives until the C# GC finalizes, and the GC cannot see Kotlin heap size; a
  hot loop over large values grows the Kotlin heap invisibly. The leak row needs a forced GC.
- Con: every component read still crosses. It is the handle the issue asked to remove, hidden.
  Rejected.

### 4. Snapshot fields plus a cached finalizer-owned handle for member calls (hybrid)

Store components eagerly and keep the original Kotlin instance for members, minting one lazily
for a C#-constructed value.

- Pro: `init` runs once; one crossing per member call.
- Con: reintroduces option 3's GC-coupled lifetime for every value that touched a member or was
  passed to Kotlin. Deferred as a possible optimisation, gated on the crossing benchmark.

### 5. `sealed record class` (or `readonly record struct`) as the C# type

- `record class`: with only get-only properties, `with { Name = "x" }` does not compile and
  `with { }` yields an equal copy, so no validation bypass (inferred from the C# records spec, not
  verified: no .NET SDK was reachable in the research sandbox). But record-synthesized equality
  compares `IReadOnlyList` components by reference and `double` with .NET rules, both different from
  Kotlin (inferred), so `Equals`, `GetHashCode` and `ToString` must be overridden anyway, which is
  ADR-008's original objection. Kept as an open question.
- `readonly record struct` (the value class shape): `default(Toy)` bypasses Kotlin's constructor,
  `Toy?` changes meaning from reference-nullable to `Nullable<Toy>` at every existing signature, and
  copying an N-field struct is not free. Rejected.

## Decision

Adopt option 1, with a `public sealed class` (not a record; see open question 2).

### Admission predicate (a "scalar data class")

A class is a scalar data class, and crosses as a value, when all of these hold; otherwise it keeps
ADR-008's handle mapping unchanged:

- it is a `data class` declared in the module being bound (dependency-module data classes stay
  handles in v1; see the red claim below);
- it is not generic, not a sealed arm, not a `Throwable` (ADR-241 owns exception types), has no
  superclass other than `Any` and implements no exported Kotlin
  interface (each of those needs a handle-typed C# base or interface; deferred);
- its primary constructor is `public` and every parameter is a `val` (no `var`);
- every constructor parameter type is a primitive, `Char`, `String`, an exported enum, a nullable of
  those, or a read-only `List`/`Set`/`Map` of those (no `Mutable*`, no `ByteArray`, whose Kotlin
  `equals` is identity);
- its body declares no `var`, no `lateinit`, and no property with a backing field or a delegate
  (only getter-only computed properties), so re-materialising from components reproduces the
  instance exactly. A getter-only computed property or a method may return any type the plan route
  binds (a handle-typed result is an ordinary owned wrapper); only constructor components and body
  state are restricted.

Value classes, `Instant`, `Duration`, `Uuid` and nested scalar data class components are open
question 3.

### C# shape

```csharp
public sealed class Toy : IEquatable<Toy>
{
    public Toy(string name, IReadOnlyList<string> colors)
    {
        // Native_Create(name, colors, out error): Kotlin's constructor and init run here;
        // a failing require throws the mapped Kotlin exception (ADR-031; `KotlinArgumentException` in today's tests). The handle is released before return.
        Name = name;
        Colors = Array.AsReadOnly(colors.ToArray()); // defensive copy: the snapshot is immutable
    }

    public string Name { get; }
    public IReadOnlyList<string> Colors { get; }

    public string Label { get { /* mint, Native_GetLabel(self), release */ } }
    public string Describe(bool loud) { /* mint, Native_Describe(self, loud), release */ }

    public Toy Copy(string? name = default, IReadOnlyList<string>? colors = default) { /* ADR-164 */ }

    public bool Equals(Toy? other) { /* mint both, Native_Equals(self, other), release both */ }
    public override bool Equals(object? obj) => obj is Toy other && Equals(other);
    public override int GetHashCode() { /* mint, Native_HashCode(self), release */ }
    public override string ToString() { /* mint, Native_ToString(self), release */ }
}
```

No `IDisposable`, no `INugetHandle`, no public or internal handle field.

### Bridge mechanism

- **Wire: unchanged.** At every position (parameter, return, property, collection element, `Flow`
  element, `suspend` result, lambda payload, erased `T`, `Any`) the value still crosses as the one
  POINTER slot of an `ObjectHandle`. Kotlin exports are not changed. **Verified by reading** that
  every Kotlin export the C# needs exists: `exports/ClassExports.kt:567-611` (`_equals`,
  `_hashcode`, `_tostring`, `_copy`) and the ordinary constructor and property plans.
- **Reading a value Kotlin hands out (MATERIALIZED, `ForwardOwnership` at
  `forward/ForwardMarshallingModel.kt:677`):** an `internal Toy(NugetKotlinHandle owned)` reads each
  component through its existing getter extern, then disposes the handle. Every reconstruction site
  that today calls `new Toy(handle)` keeps working and stops retaining.
- **Passing a value to Kotlin:** the lowering mints `Native_Create(components)` into a local and
  disposes it in the call's `finally`, the same owned-temporary pattern `NugetMarshal.Wrap<T>` uses
  for a minted value-class box (`owned = false; mint; owned = true`,
  `cir/CirMarshalRenderer.kt:622-641`, verified by reading). At an erased position the class
  implements an internal mint interface (`INugetGenericValue.NugetBox()` today,
  `cir/CirRenderer.kt:43-46`; reuse or a sibling, implementer's choice), so `Wrap<T>` takes the
  owned branch, not the borrowed `INugetHandle` branch.
- **Classification:** a new `BridgeType` variant (working name `BridgeType.DataValue`, carrying the
  `ObjectHandle` and the ordered components). Per the CLAUDE.md rule a `when` over `BridgeType`
  never ends in `else`, so every exhaustive `when` stops compiling until it decides. The plain
  `is BridgeType.ObjectHandle` tests do not fail to compile: there are 158 of them across 26 files
  (grep, verified), and each must be audited by hand, because a missed one silently treats the value
  as not-a-handle. Most forward to the handle arm; only C# lowering and reconstruction differ. A
  boolean on `ObjectHandle` was rejected: a site that ignores it would pass a borrowed handle that
  no longer exists, which fails the C# compile at best and leaks at worst.

### Sample consumer test and leak row

```csharp
[Fact]
public void Plushie_CrossesAsAValue_WithKotlinComputedMembers()
{
    Plushie p = PlushieSampleKt.FavouritePlushie(); // no using, no Dispose
    Assert.Equal("Bun", p.Name);
    Assert.Equal(new[] { "pink", "white" }, p.Colors);
    Assert.Equal("Bun (pink/white)", p.Label);                // Kotlin getter
    Assert.Equal("BUN!", p.Describe(loud: true));              // Kotlin method
    Assert.Equal(p, new Plushie("Bun", new[] { "pink", "white" }, Mood.Happy, 3)); // Kotlin equals
    Assert.Equal("Plushie(name=Bun, colors=[pink, white], mood=Happy, squeaks=3)", p.ToString());
    Assert.Throws<KotlinArgumentException>(() => p.Copy(name: ""));    // init { require } still runs
    Assert.False(typeof(IDisposable).IsAssignableFrom(typeof(Plushie)));
}

// LeakTests/LiveHandleTests.cs: deterministic, no GC
[Fact]
public void ScalarDataClass_EveryCrossing_ReturnsToBaselineWithoutDispose()
{
    long before = NugetMarshal.LiveHandles;
    for (int i = 0; i < 1000; i++)
    {
        Plushie p = PlushieSampleKt.EchoPlushie(PlushieSampleKt.FavouritePlushie());
        _ = p.Label; _ = p.Describe(false); _ = p.GetHashCode(); _ = p.ToString();
        _ = p.Equals(p.Copy(squeaks: 4));
        _ = PlushieSampleKt.Plushies().Count;
        Assert.Throws<KotlinArgumentException>(() => new Plushie("", [], Mood.Happy, 0));
    }
    Assert.Equal(before, NugetMarshal.LiveHandles); // no GC.Collect: nothing was retained
}
```
- **Strings:** the snapshot stores the C# string it was given, not the Kotlin round trip. Identical
  for every well-formed UTF-16 string; a lone surrogate would differ (inferred, unspiked).

### Labelled claims

Verified (by reading repo source): the value-class re-wrap route, the existing data-class exports,
the `Wrap<T>` owned-mint protocol, the SafeHandle finalizer, the 158/26 site count.

Inferred, not verified (no .NET SDK or Kotlin/Native toolchain reachable in the research sandbox;
`dotnet-install.sh` was refused by the proxy, 403):

- Kotlin's `copy()` calls the primary constructor, so `init` runs (Kotlin docs). If wrong, nothing
  breaks here: C# `Copy` keeps calling the existing `_copy` export.
- Record-synthesized equality would compare list components by reference (C# docs). Only relevant
  to open question 2.
- **Red, unverified, load-bearing:** the predicate's "no backing field, no delegate" test relies on
  KSP's `KSPropertyDeclaration.hasBackingField` / `isDelegated()`, which nothing in the repo uses
  yet (grep, verified absent). If it reports `false` for a body `val createdAt = now()`, that class
  is admitted and every member call silently sees a fresh `createdAt`. ADR-066 already found a klib
  declaration reports different modifiers (`INLINE` not `VALUE`) from an in-module one
  (`forward/ForwardBridgeTypeClassifier.kt:1812-1820`), which is why v1 admits in-module data
  classes only. **The implementer must pin this with a Tier 1 cell** over a body `val x = ...`, a
  `by lazy`, a `lateinit var` and a getter-only `val`, and must not ship the predicate on the
  docs' word. Whether `Modifier.DATA` is reported for a dependency-module data class is likewise
  unverified.

## Consequences

- **Source break for every consumer of a qualifying type.** `using var toy = new Toy(...)` no longer
  compiles (CS1674, inferred). `toy.Dispose()` disappears. 44 `using` sites over `Toy` alone in
  `IntegrationTests`/`LeakTests` (grep, verified) need rewriting, plus every other qualifying
  fixture. Release-note entry under "Breaking changes"; see open question 1 for timing.
- Reading a component no longer crosses; a computed member costs three crossings; `init` re-runs per
  member call and per crossing into Kotlin.
- `Equals`/`GetHashCode`/`ToString`/`Copy` behave as ADR-008 (Kotlin decides), now with no handle.
- ADR-008 stays the mapping for every data class outside the predicate; this ADR amends it.
- Deferred: generic data classes, sealed arms, data classes with an exported superclass or
  interface, dependency-module data classes, `Deconstruct`, a flattened N-slot wire, hash caching,
  the hybrid (option 4), and an opt-out.

### Open questions (for the Step 2 gate)

1. **When does the break land?** ROADMAP says 0.9.0 carried every forward break and the parity
   minors allow breaks only to generated Kotlin. Recommendation: default on (GOALS "plug and play",
   and the value class route is unconditional), in the next minor with a "Breaking changes" entry.
   Alternative: a Gradle opt-in until 1.0. A per-declaration annotation is not recommended.
2. **`sealed class` or `sealed record class`?** Recommendation: `sealed class : IEquatable<T>`,
   because a record must override `Equals`, `GetHashCode` and `ToString` anyway and its `with` can
   only produce an identical copy. Either way, add `==`/`!=` operators delegating to `Equals`: two
   crossings of the same Kotlin value are two C# objects, so reference `==` would always be false.
3. **Wider components?** Recommendation: admit value classes, `Instant`, `Duration`, `Uuid`
   (all already handle-free) and nested scalar data classes over an acyclic type graph
   (`data class Node(val next: Node?)` stays a handle) in v1; otherwise `data class Order(val toy:
   Toy)` keeps a `Dispose` that `Toy` lost.
4. **Side-effecting `init`.** It re-runs per member call and per crossing into Kotlin.
   Recommendation: accept and document (value class precedent, ADR-235); excluding every class with
   an `init` would also exclude the `require` validation the issue wants kept.
5. **The ROADMAP "record class if a safe `with` can be found" line:** no safe `with` that modifies
   a component exists (an `init` accessor runs per property, before the others are set, so it
   cannot run a cross-field `require`). Recommendation: strike the line, pointing here.

### Interaction with sibling Proposed ADRs

- [ADR-243](243-disposable-handle-collections.md) makes a returned `List<E>` a disposable
  `KotlinList<E>` when `E` carries a handle, computed by an exhaustive `when` over `BridgeType`. The
  new `DataValue` variant must answer "carries no handle" there, so `List<Toy>` stays
  `IReadOnlyList<Toy>`. Whichever lands second adds the arm.
- [ADR-241](241-exported-kotlin-exception-types.md) maps exported exception classes, including
  data-class exceptions; the `Throwable` exclusion above keeps them on its route.
