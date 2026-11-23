# ADR-242: Immutable all-scalar data classes cross as C# values with no handle

## Status

Accepted (2026-11-23). Proposed 2026-11-09, GitHub issue #611. Implemented and verified; the text
below describes what shipped, with the corrections found during implementation folded in. Amends
[ADR-008](008-data-class-mapping.md).

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
  reading). The change is in classification and C# projection, except for a value's own `suspend`
  and `Flow` members (corrected under Bridge mechanism).
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
  ADR-008's original objection. **`record class` was declined at the gate** (resolved open
  question 2): no `with` that changes a component can run a cross-field `init` `require`, since an
  `init` accessor runs per property before the others are set. This settles the old ROADMAP Future
  Improvements line "Map data classes to C# `record class` if a safe `with`-expression pattern can
  be found (see ADR-008)", which was removed from the ROADMAP when this ADR was accepted (open
  question 5). Do not re-propose it without a new answer to the cross-field validation problem.
- `readonly record struct` (the value class shape): `default(Toy)` bypasses Kotlin's constructor,
  `Toy?` changes meaning from reference-nullable to `Nullable<Toy>` at every existing signature, and
  copying an N-field struct is not free. Rejected.

## Decision

Adopt option 1, with a `public sealed class` (not a record; resolved open question 2). On by
default with no opt-out (resolved open question 1).

### Admission predicate (a "scalar data class")

A class is a scalar data class, and crosses as a value, when all of these hold; otherwise it keeps
ADR-008's handle mapping unchanged:

- it is a `data class` declared in the module being bound (dependency-module data classes stay
  handles in v1; see the klib claim under Labelled claims);
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

As shipped (resolved open question 3), a component may also be a non-generic value class over an
admitted type, `Instant`, `Duration`, `Uuid`, or another scalar data class over an acyclic type
graph (`data class Chain(val next: Chain?)` stays a handle), and their nullables and read-only
collections. The predicate also refuses an `expect` class, an opt-in-marked constructor or
component, and a constructor parameter without a matching public property. `kotlin.Exception` is a
typealias, so the supertype check expands aliases before it reads the declaration.

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

No `IDisposable`, no `INugetHandle`, no public or internal handle field. `==` and `!=` operators
delegate to `Equals` (resolved open question 2).

**Corrected (2026-11-23):** the sketch above stores the arguments and reads nothing back, which
holds only for a constructor with no defaulted parameter. A constructor with defaulted parameters
(ADR-164's widened signature) chains to the internal handle constructor and reads every component
back from Kotlin, so an omitted argument holds what Kotlin's default evaluated (`Config().id` is
the `Uuid.random()` Kotlin minted, stable across reads).

### Bridge mechanism

- **Wire: unchanged.** At every position (parameter, return, property, collection element, `Flow`
  element, `suspend` result, lambda payload, erased `T`, `Any`) the value still crosses as the one
  POINTER slot of an `ObjectHandle`. **Verified by reading** that every Kotlin export the C# needs
  exists: `exports/ClassExports.kt:567-611` (`_equals`, `_hashcode`, `_tostring`, `_copy`) and the
  ordinary constructor and property plans. **Corrected (2026-11-23):** not every Kotlin export
  stays. A value's own `suspend` and `Flow` members moved from instance exports to static-route
  exports taking the minted receiver: a value has no owner, so they run on the shared top-level
  scope and cancel through their `CancellationToken` only, like an `object` or value-class member.
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
  owned branch, not the borrowed `INugetHandle` branch. As shipped, the class implements
  `INugetGenericValue` itself, and the same mint path also covers a value passed as a parameter or
  receiver on the legacy routes (a `suspend` member of an ordinary class taking the value, a
  `suspend` extension on it), not only erased positions.
- **Classification:** a new `BridgeType` variant (working name `BridgeType.DataValue`, carrying the
  `ObjectHandle` and the ordered components). Per the CLAUDE.md rule a `when` over `BridgeType`
  never ends in `else`, so every exhaustive `when` stops compiling until it decides. The plain
  `is BridgeType.ObjectHandle` tests do not fail to compile: there are 158 of them across 26 files
  (grep, verified), and each must be audited by hand, because a missed one silently treats the value
  as not-a-handle. Most forward to the handle arm; only C# lowering and reconstruction differ. A
  boolean on `ObjectHandle` was rejected: a site that ignores it would pass a borrowed handle that
  no longer exists, which fails the C# compile at best and leaks at worst.

### Sample consumer test and leak row

As shipped, from `IntegrationTests/PlushieValueTests.cs` (`BunColors` is `["pink", "white"]`):

```csharp
[Fact]
public void Plushie_CrossesAsAValue_WithKotlinComputedMembers()
{
    Plushie p = PlushieSample.FavouritePlushie(); // no using, no Dispose
    Assert.Equal("Bun", p.Name);
    Assert.Equal(BunColors, p.Colors);
    Assert.Equal("Bun (pink/white)", p.Label);                 // Kotlin getter
    Assert.Equal("BUN!", p.Describe(loud: true));               // Kotlin method
    Assert.Equal(p, new Plushie("Bun", BunColors, Mood.Happy, 3)); // Kotlin equals
    Assert.Equal("Plushie(name=Bun, colors=[pink, white], mood=Happy, squeaks=3)", p.ToString());
    Assert.Throws<KotlinArgumentException>(() => p.Copy(name: ""));     // init { require } still runs
    Assert.False(typeof(IDisposable).IsAssignableFrom(typeof(Plushie)));
}
```

And the leak row, from `LeakTests/LiveHandleTests.cs` (`Plush` aliases the `TestLibrary.Plushie`
namespace; `AssertRetainsNothing` settles once, then measures straight after the crossings with no
GC):

```csharp
[Fact]
public void ScalarDataClass_EveryCrossing_ReturnsToBaselineWithoutDispose()
{
    AssertRetainsNothing(() =>
    {
        Plush.Plushie p = Plush.PlushieSample.EchoPlushie(Plush.PlushieSample.FavouritePlushie());
        _ = p.Label;
        _ = p.Describe(false);
        _ = p.GetHashCode();
        _ = p.ToString();
        _ = p.Equals(p.Copy(squeaks: 4));
        _ = Plush.PlushieSample.Plushies().Count;
        _ = Plush.PlushieSample.FindPlushie("Milo");
        _ = Plush.PlushieSample.NameOf(new Plush.Plushie("Milo", ["brown"], Plush.Mood.Sleepy, 0));
    }, iterations: 1000);
}
```

The failing-`require` crossing the draft folded into this row is its own row,
`ScalarDataClass_FailingConstructorRequire_LeaksNoHandle`.
- **Strings:** the snapshot stores the C# string it was given, not the Kotlin round trip. Identical
  for every well-formed UTF-16 string; a lone surrogate would differ (inferred, unspiked).

### Labelled claims

Verified (by reading repo source): the value-class re-wrap route, the existing data-class exports,
the `Wrap<T>` owned-mint protocol, the SafeHandle finalizer, the 158/26 site count.

Verified at implementation (2026-11-23, by the fixtures and a green `scripts/verify.sh`):

- Kotlin's `copy()` calls the primary constructor, so `init` runs: `Copy(name: "")` throws
  `KotlinArgumentException` (`IntegrationTests/PlushieValueTests.cs`).
- The predicate's "no backing field, no delegate" test, labelled red, unverified and load-bearing
  at the gate (it relies on KSP's `hasBackingField` / `isDelegated()`, which nothing in the repo
  used), is reliable for an in-module class: the negative fixtures `StampedPlushie`, `LazyPlushie`,
  `LatePlushie` and `SquishyPlushie` stay handles. A constructor `val` reports `hasBackingField`
  too, so components are skipped by name before the check; a body `val` with an initializer
  reports a backing field; a delegate reports `isDelegated()`; a `var` and a `lateinit var` report
  `isMutable`.

Inferred, not verified:

- Record-synthesized equality would compare list components by reference (C# docs). Only relevant
  to the declined `record class`.
- Whether `Modifier.DATA` and the backing-field answers read correctly for a dependency-module data
  class, which is why v1 admits in-module data classes only (ADR-066 found a klib declaration
  reports `INLINE` not `VALUE`, `forward/ForwardBridgeTypeClassifier.kt:1812-1820`).

## Consequences

- **Source and binary break for every consumer of a qualifying type**; see Breaking below. The
  data classes topic page carries the consumer migration note.
- Reading a component no longer crosses; a computed member costs three crossings; `init` re-runs per
  member call and per crossing into Kotlin.
- `Equals`/`GetHashCode`/`ToString`/`Copy` behave as ADR-008 (Kotlin decides), now with no handle.
- ADR-008 stays the mapping for every data class outside the predicate; this ADR amends it.
- A returned `List` of values stays `IReadOnlyList<T>`, not ADR-243's `KotlinList<T>`.

## Breaking

On by default, with no opt-out (resolved open question 1).

- **A qualifying data class is `public sealed class X : IEquatable<X>`, with no `IDisposable` and no
  `INugetHandle`.** `using var x = new X(...)` no longer compiles (CS1674) and `x.Dispose()` is
  gone. In this repo's own tests, 142 `using` sites and 4 `Dispose` calls in `IntegrationTests` and
  13 `using` sites and 1 `Dispose` call in `LeakTests` migrated.
- **`==` and `!=` delegate to Kotlin's `equals`** instead of comparing references (behaviour).
- **The class is `sealed`**, so a C# subclass of it no longer compiles, and code that disposes any
  `IDisposable` it is handed no longer sees it.
- **Binary:** the interface list and `Dispose` are gone, so a consumer built against the previous
  package must be rebuilt (inferred, not run).

## Deferred

Each keeps ADR-008's handle mapping until it lands:

- Generic data classes.
- Sealed arms, and a data class with a superclass or an exported interface (each needs a
  handle-typed C# base or interface).
- `Throwable` data classes (ADR-241's route).
- Dependency-module data classes (the klib answers above are unverified).
- `Deconstruct` for `componentN`.
- A flattened N-slot wire for direct positions, caching `GetHashCode`, and the hybrid (option 4),
  the last gated on the crossing benchmark.
- An opt-out switch: not built, since the gate chose on by default with none; recorded so a request
  for one has a home.
- Falling back to a handle when a would-be value's constructor or a component getter fails to plan.
  Today that fails generation with `ERROR_INTERNAL_GENERATOR_FAILURE`; no fixture reaches it, and
  the fallback needs a second classify-and-plan pass.
- A generic data class negative fixture: pinned at Tier 1 only, because a top-level generic data
  class does not compile today (below).

## Discovered, not fixed

- A top-level generic `data class PlushieBag<T>(val item: T)` gets a `Copy` returning the bare type
  name (CS0305), failing `nugetCompileInterop`. `forward/ForwardCallablePlanner.kt:2564` builds one
  bare `ObjectHandle(owner)` result for constructors and `copy`. Verified by build; not caused by
  this ADR.

### Open questions (resolved at the Step 2 gate, 2026-11-09)

1. **When does the break land?** ROADMAP says 0.9.0 carried every forward break and the parity
   minors allow breaks only to generated Kotlin. Recommendation: default on (GOALS "plug and play",
   and the value class route is unconditional), in the next minor with a "Breaking changes" entry.
   Alternative: a Gradle opt-in until 1.0. A per-declaration annotation is not recommended.
   **Decided:** on by default in the next minor, with a Breaking changes entry and no opt-out.
2. **`sealed class` or `sealed record class`?** Recommendation: `sealed class : IEquatable<T>`,
   because a record must override `Equals`, `GetHashCode` and `ToString` anyway and its `with` can
   only produce an identical copy. Either way, add `==`/`!=` operators delegating to `Equals`: two
   crossings of the same Kotlin value are two C# objects, so reference `==` would always be false.
   **Decided:** as recommended; shipped.
3. **Wider components?** Recommendation: admit value classes, `Instant`, `Duration`, `Uuid`
   (all already handle-free) and nested scalar data classes over an acyclic type graph
   (`data class Node(val next: Node?)` stays a handle) in v1; otherwise `data class Order(val toy:
   Toy)` keeps a `Dispose` that `Toy` lost. **Decided:** as recommended; shipped (`PlushieTag`,
   `PlushieBox`, and `PlushieChain` stays a handle).
4. **Side-effecting `init`.** It re-runs per member call and per crossing into Kotlin.
   Recommendation: accept and document (value class precedent, ADR-235); excluding every class with
   an `init` would also exclude the `require` validation the issue wants kept. **Decided:** accepted
   and documented on the data classes topic page.
5. **The ROADMAP "record class if a safe `with` can be found" line:** no safe `with` that modifies
   a component exists (an `init` accessor runs per property, before the others are set, so it
   cannot run a cross-field `require`). Recommendation: strike the line, pointing here.
   **Decided:** struck; Alternative 5 above is the record, so it is not re-proposed.

### Interaction with sibling ADRs

- [ADR-243](243-disposable-handle-collections.md) (Accepted) makes a returned `List<E>` a
  disposable `KotlinList<E>` when `E` carries a handle, computed by an exhaustive `when` over
  `BridgeType`. It landed first; this ADR added the `DataValue` arm answering "carries no handle",
  so `List<Toy>` stays `IReadOnlyList<Toy>`.
- [ADR-241](241-exported-kotlin-exception-types.md) (Accepted) maps exported exception classes,
  including data-class exceptions; the `Throwable` exclusion above keeps them on its route.
