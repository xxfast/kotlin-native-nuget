# ADR-112: An eligible sealed interface takes the ADR-009 sealed-class route

## Status
Accepted

## Context

A Kotlin `sealed interface` is today claimed by two forward routes and finished by neither.
Every claim below is **verified by reading** the named line unless marked otherwise; nothing in
this ADR was spiked (another agent held Gradle while it was written, and the ADR is read-only for
code by instruction).

- **Root scan.** `rootSealedClasses` (`NugetProcessor.kt:431-435`) filters
  `classKind == ClassKind.CLASS` before `Modifier.SEALED`, so a sealed interface never enters the
  sealed bucket. `rootInterfaces` (`:449-453`) filters `classKind == INTERFACE` and has no `SEALED`
  filter, so the same declaration enters the interface bucket and is declared by
  `translateInterface` (`CirClassTranslator.kt:1451`) as `public interface IShape`, with the
  ADR-040 backing wrapper behind it.
- **Reachability closure.** `ForwardReachabilityClosure.reachabilityBucket()`
  (`ForwardReachabilityClosure.kt:265-269`) tests `classKind == INTERFACE` on line 267 and
  `Modifier.SEALED` on line 268, so a cross-module sealed interface lands in the `INTERFACE`
  bucket too. It does still walk `getSealedSubclasses()` (`:259-261`), because that gate reads the
  modifier alone.
- **Classifier.** `ForwardBridgeTypeClassifier.kt:170` tests `Modifier.SEALED` ahead of the two
  interface branches (`:200-207`), so every position typed with a sealed interface gets
  `SpecializedProtocol("sealed helper <fqn>")`. Its `sealedHandle` is minted only when
  `classDeclaration.classKind == ClassKind.CLASS && qualifiedName in exportedObjectHandles`
  (`:179-180`), so for a sealed interface it stays `null`, `sealedAsHandle()` is a no-op, and the
  planner skips the member as `SEALED_POSITION` (`ForwardCallablePlanner.kt:2547`), reported as
  `SKIPPED_SEALED_POSITION` with the wording "has no generated discriminator, so C# cannot
  reconstruct it: only a sealed *class* inside the export scope gets one"
  (`ForwardDiagnostic.kt:527-535`).
- **Subclass membership.** `declaredSuperClass()` (`ForwardClassMembership.kt:19-25`) keeps only a
  supertype whose `classKind == CLASS`, so `isSealedSubclass()` (`:43-44`) is **false** for every
  subclass of a sealed interface. Three consumers then treat such a subclass as an ordinary nested
  or top-level class: the closure refuses a nested one cross-module as `NESTED_DECLARATION`
  (`ForwardReachabilityClosure.kt:248-256`), the classifier marks a nested one `Unsupported`
  "a nested class is never declared in C#" (`ForwardBridgeTypeClassifier.kt:232-238`), and the
  processor emits `SKIPPED_NESTED_DECLARATION` for it (`NugetProcessor.kt:608-628`). A top-level
  subclass (`class Circle : Shape` beside `sealed interface Shape`) is not excluded from
  `rootClasses` (`:420`) and is declared as a plain namespace-level class.

Net effect for the consumer: `public interface IShape` exists in C#, no exported member can be
typed with it, and its subclasses either vanish (nested) or appear as unrelated flat classes
(top-level). The interface binds nothing useful.

Pinned today by four Tier 1 tests, all asserting the skip rather than the interface:
`Tier1SealedCollectionPropertyTest` (`:32` declares `sealed interface Filter`; `:96` "a sealed
interface component still skips named", as `SKIPPED_UNSUPPORTED_PROPERTY` naming
`Collection (element type sealed helper ...Filter)`), `Tier1SealedPositionSkipTest` (`:34`
`Ghost`, `:50`, `:122`), `Tier1SealedParameterPositionTest` (`:181` "a sealed interface parameter
still skips as a sealed position"), `Tier1NoPublicConstructorWarningTest` (`:42`). No test
anywhere asserts an `IGhost` or `IFilter` C# declaration (grep of `nuget-processor/src/test`,
zero hits), so the interface-route half of today's behaviour is unpinned.

ADR-105 scope (d) has shipped on this stack: a sealed *class* now binds at every position
(property, parameter, return, nullable, collection component) through `sealedAsHandle()` and the
ADR-009 `FromHandle` discriminator. The only thing standing between a sealed interface and the
same treatment is the `classKind == CLASS` test in three places and the `declaredSuperClass()`
kind filter.

## Alternatives Considered

### 1. Eligible sealed interface takes the sealed-class route as an abstract class (chosen)

An **eligible** sealed interface is treated exactly as a sealed class: admitted into
`rootSealedClasses`, removed from `rootInterfaces` and from the closure's `INTERFACE` bucket,
rendered by `translateSealedClass` / `CirSealedRenderer` / `SealedClassExports` as
`public abstract class Shape : IDisposable, INugetHandle` with nested `public sealed class`
subclasses, `<prefix>_get_type` and `internal static Shape FromHandle(IntPtr)`. `IShape` is never
declared.

Eligible means all of:
- `Modifier.SEALED` and `classKind == INTERFACE`;
- no type parameters;
- every `getSealedSubclasses()` entry is a `CLASS` or `OBJECT` nested directly in the interface
  (`parentDeclaration` is the interface);
- every subclass has `declaredSuperClass() == null` (no other class supertype; the sealed
  interface itself is not counted because `declaredSuperClass()` keeps only `CLASS` supertypes);
- no subclass is itself an `INTERFACE` (no sub-interface anywhere in the hierarchy).

Pros: identical consumer idiom to ADR-009 (`Shape.Circle` pattern matching, one discriminator,
`FromHandle` at every ADR-105 position for free); nobody outside the hierarchy can implement a
sealed interface in Kotlin, so the C# abstract class with an `internal` constructor loses nothing;
one route touched, no new renderer. Cons: a C# consumer cannot tell a sealed interface from a
sealed class; the `IShape` name disappears.

### 2. Stay on the interface route and add a discriminator there (rejected)

Keep `public interface IShape`, declare `public sealed class Circle : IShape` per subclass, and
add a `static IShape FromHandle(IntPtr)` beside the ADR-040 backing wrapper. Rejected: the
ADR-040 backing wrapper (`translateInterfaceBackingClass`, `CirClassTranslator.kt:1526`, invoked
for every interface at `CirTranslator.kt:423`) already owns the return position for an
interface, so a discriminator there either displaces it or has to coexist with it at every
ADR-105 position (property, parameter, collection component), and each of those is a second route
to teach. It also invents a C# shape (`interface` + external `static FromHandle`) with no
precedent in this repo, where ADR-009's abstract class already is the closed-hierarchy idiom.

### 3. Keep skipping (rejected)

Leave the two half-routes as they are and only improve the diagnostic. Rejected: a sealed
interface is the recommended Kotlin spelling for a closed hierarchy whose members are plain
classes (`sealed interface` + `data class`/`data object`), so a library author who follows the
Kotlin style guide gets a worse binding than one who writes `sealed class` for no C#-visible
reason.

## Decision

Adopt alternative 1.

| File | Change |
|---|---|
| `NugetProcessor.kt:431-435` (`rootSealedClasses`) | Replace `classKind == CLASS` with an eligible-kind test: `CLASS`, or `INTERFACE` passing the eligibility predicate above. |
| `NugetProcessor.kt:449-453` (`rootInterfaces`) | Exclude what `rootSealedClasses` admitted (an eligible sealed interface); an ineligible sealed interface stays here. |
| `NugetProcessor.kt` (declaration-level diagnostic) | For an ineligible sealed interface, emit one new `ForwardDiagnostic` naming the disqualifying reason (see Diagnostics). |
| `forward/ForwardReachabilityClosure.kt:265-269` | Test eligibility before `classKind == INTERFACE` so a cross-module eligible sealed interface lands in `SEALED_CLASS`, not `INTERFACE`. |
| `forward/ForwardBridgeTypeClassifier.kt:179` | `discriminated` becomes eligible-kind `&& qualifiedName in exportedObjectHandles`. An ineligible sealed interface keeps `sealedHandle = null` and skips exactly as today. |
| `forward/ForwardClassMembership.kt:43-44` | `isSealedSubclass()` must recognise a sealed-interface parent. Today it reads `declaredSuperClass()`, which drops every non-`CLASS` supertype (`:23`), so it is **false** for a sealed-interface subclass (**verified by reading**). Change it to look through `superTypes` for any `KSClassDeclaration` carrying `Modifier.SEALED`, regardless of kind, restricted to an *eligible* sealed parent so an ineligible one's subclasses keep today's plain-class handling. |
| `forward/ForwardDiagnostic.kt` | New kind `SKIPPED_INELIGIBLE_SEALED_INTERFACE` (WARNING). |

The eligibility predicate lives in `ForwardClassMembership.kt` next to `isSealedSubclass()` so
that the root scan, the closure, the classifier and the membership predicate all call the same
function. Four call sites reading four hand-copied conditions is how the two half-routes came to
exist.

**What the sealed route already does that a sealed interface needs, verified by reading:**

- `translateSealedClass` (`CirClassTranslator.kt:1067-1085`) enumerates `getSealedSubclasses()`,
  handles `OBJECT` subclasses (`isDataObject`, `:1082`) and computes `isNested` from
  `parentDeclaration` (`:1083-1084`). None of it reads the base's `classKind`.
- `SealedClassExports.kt:36-49` emits `<prefix>_get_type` as `handle.asStableRef<Fqn>().get()`
  followed by `when (obj) { is Sub -> index }`. `asStableRef<T>()` and `is` accept an interface
  type parameter, so the Kotlin emitted for a sealed interface compiles unchanged (**inferred**:
  Kotlin language semantics, no scratch build was run).
- `CirSealedRenderer.kt:16-64` renders `public abstract class <Name> : IDisposable, INugetHandle`,
  an `internal <Name>(IntPtr)` constructor, nested `public sealed class <Sub> : <Name>`, and the
  `FromHandle` switch over `Native_GetType`. Nothing in it depends on the Kotlin base being a class.
- ADR-111 moved sealed-subclass properties onto `ForwardPropertyPlanner.sealedSubclassProperties`
  with `superClass = null`, which is exactly the shape of a sealed-interface subclass (its only
  supertype is the interface).

**What the eligibility predicate excludes and why:**

- *Generic sealed interface*: the sealed route has no type-parameter rendering, and
  `ForwardBridgeTypeClassifier.kt:200` already routes a generic interface to the "generic
  declaration" legacy protocol.
- *A subclass with another superclass* (`class Circle : Base(), Shape`): the C# nested subclass
  can only extend the abstract `Shape`; a second base is unrepresentable.
- *A sub-interface* (`sealed interface Shape { interface Round : Shape }`): the discriminator is
  a flat `when` over `getSealedSubclasses()`; a sub-interface has no single C# class to construct.
- *A top-level subclass*: excluded from v1 for the same reason ADR-009 originally required
  nesting. The renderer does have the issue-#54 sibling path (`CirSealedRenderer.kt:62`), so this
  is a scope choice, not a mechanism limit; widening is a one-line predicate change once the
  nested case is green.

Members declared on the interface itself with bodies (`fun describe(): String = ...`) are **not**
rendered on the abstract base. This is parity with the sealed-class route, which renders no base
members either (`CirSealedRenderer.kt:16-53` declares only the handle, `FromHandle` and
`Dispose`). A consumer pattern-matches to a subclass and calls the member there, where ADR-111
plans it as an inherited member. Deferred, not rejected.

### Consumer API

```kotlin
sealed interface Shape {
  data class Circle(val radius: Double) : Shape
  data object Empty : Shape
}

fun pick(): Shape = Shape.Circle(1.0)
fun area(shape: Shape): Double = ...
class Canvas(val shape: Shape?, val shapes: List<Shape>)
```

```csharp
public abstract class Shape : IDisposable, INugetHandle
{
    public sealed class Circle : Shape { public double Radius { get; } ... }
    public sealed class Empty : Shape { ... }
    internal static Shape FromHandle(IntPtr handle) { ... }
    public abstract void Dispose();
}

using var shape = Shapes.Pick();
var description = shape switch
{
    Shape.Circle c => $"circle {c.Radius}",
    Shape.Empty => "empty",
    _ => throw new InvalidOperationException(),
};
double a = Shapes.Area(shape);
Shape? maybe = canvas.Shape;
IReadOnlyList<Shape> all = canvas.Shapes;
```

Byte-for-byte the same as the ADR-009 + ADR-105 output for `sealed class Shape`. There is no
`IShape`.

### Diagnostics

An **ineligible** sealed interface keeps today's behaviour (declared as `public interface I<Name>`
by the interface route, `SKIPPED_SEALED_POSITION` at every position typed with it) plus one new
declaration-level diagnostic:

```
SKIPPED_INELIGIBLE_SEALED_INTERFACE: sealed interface `pkg.Shape` is declared as `IShape` but
cannot be reconstructed in C#: <reason>. Make every subclass a nested class or object with no
other superclass and no sub-interfaces, or declare it as a sealed class.
```

where `<reason>` is one of: "it has type parameters", "subclass `X` extends another class `Y`",
"subclass `X` is an interface", "subclass `X` is declared outside the sealed interface". Emitted
once per declaration, at the declaration's symbol, before the `hasNothingToProcess` early return
in the same place `SKIPPED_NESTED_DECLARATION` is (`NugetProcessor.kt:616`).

The existing `SKIPPED_SEALED_POSITION` wording at `ForwardDiagnostic.kt:531-534` ("only a sealed
*class* inside the export scope gets one") becomes "only an eligible sealed type inside the
export scope gets one (ADR-009, ADR-112)"; its "no discriminator" claim now applies only to an
ineligible sealed interface and an out-of-scope sealed class.

### Tests to respell

- `Tier1SealedCollectionPropertyTest:96` ("a sealed interface component still skips named")
  inverts: `Album.filters` now binds as `IReadOnlyList<Filter>` through `Filter.FromHandle`. Its
  `Filter` (`:32`, one nested `class ById`) is eligible.
- `Tier1SealedPositionSkipTest` (`Ghost` with a nested `data object Nobody`, eligible),
  `Tier1SealedParameterPositionTest:181`, `Tier1NoPublicConstructorWarningTest:42`: the
  `SEALED_POSITION` skips they assert for `Ghost` invert to bindings. Each test keeps its
  sealed-*class* half unchanged.
- New Tier 1 tests: one per ineligibility reason asserting the new diagnostic and that
  `I<Name>` is still declared; one asserting a cross-module eligible sealed interface reaches the
  `SEALED_CLASS` bucket.

## Consequences

- A C# consumer cannot tell a sealed interface from a sealed class. Accepted: neither can be
  implemented outside the hierarchy on either side, so the distinction carries no information in
  C#.
- **Breaking for the flat `IShape` name.** Any consumer that referenced the generated
  `public interface IShape` loses it. Nothing could be typed with it from the exported surface
  (every position skipped), so the only possible use was a consumer-side implementation of an
  interface whose Kotlin declaration forbids exactly that. Ships in the next minor.
- A nested subclass of an eligible sealed interface stops being reported as
  `SKIPPED_NESTED_DECLARATION` and stops classifying as `Unsupported` nested, because
  `isSealedSubclass()` now admits it. The `rootClasses` exclusion at `NugetProcessor.kt:420` also
  starts excluding a top-level subclass of an eligible sealed interface; since eligibility requires
  nesting in v1, that branch is reachable only once the top-level widening lands.
- The `SEALED_POSITION` skip narrows to ineligible sealed interfaces and out-of-scope sealed
  classes.
- Deferred: top-level subclasses (predicate widening, renderer already supports siblings), base
  members with bodies rendered on the abstract class (sealed-class parity item, applies to both),
  generic sealed interfaces, sub-interfaces.

## Prior art (to the depth that changes the decision)

- **ObjC export**: a Kotlin interface becomes an ObjC protocol and a sealed class an ObjC class;
  sealedness itself is not represented in either case, so a sealed interface exports as a plain
  protocol with class conformers and no discriminator. **Inferred** from the Kotlin docs
  ([ObjC interop](https://kotlinlang.org/docs/native-objc-interop.html#classes-and-objects)), no
  spike. It does not change the decision: ObjC has protocols, so it keeps the interface shape and
  simply loses exhaustiveness; C# has no way to close an interface, so the closed shape is the
  abstract class ADR-009 already chose.
- **Swift Export**: the current Kotlin docs list interfaces among the unsupported declarations
  ([Swift export](https://kotlinlang.org/docs/native-swift-export.html)), so there is no sealed
  interface mapping to mirror. **Inferred** from the docs, no spike.
- **C# idiom**: the closed-hierarchy pattern C# developers write by hand (and the shape the
  `dotnet/csharplang` discriminated-union proposals lower to) is an abstract class with a
  non-public constructor and nested `sealed` subclasses, matched with `switch` patterns. That is
  ADR-009's output. **Inferred** from the proposal text, not load-bearing.
- Skipped: Kotlin/JVM (a sealed interface is a plain JVM interface, no reconstruction seam),
  JS/Wasm export (no handle seam).

## Claims ledger

**Verified by reading** (file and line named inline above): the three `classKind == CLASS` gates;
the closure's `INTERFACE`-before-`SEALED` order; `declaredSuperClass()` dropping non-`CLASS`
supertypes and therefore `isSealedSubclass()` being false for a sealed-interface subclass; the
three consumers of `isSealedSubclass()` that mis-handle such a subclass today; the sealed route
(`translateSealedClass`, `SealedClassExports`, `CirSealedRenderer`) reading nothing from the
base's `classKind`; the `SKIPPED_SEALED_POSITION` wording; the four pinning tests; the absence of
any test asserting `IGhost`/`IFilter`.

**Inferred, nobody has verified**, and what breaks if wrong:

1. KSP reports `Modifier.SEALED` in `modifiers` for a `sealed interface` and
   `getSealedSubclasses()` enumerates its subclasses. If wrong, the eligible predicate never fires
   and the feature silently does nothing (safe failure, but a no-op). The classifier's existing
   `SEALED_POSITION` skip for `Ghost` in `Tier1SealedPositionSkipTest` only fires because the
   modifier *is* present, which is strong but indirect evidence for the first half.
2. The Kotlin emitted by `SealedClassExports` compiles when the `asStableRef<T>()` type argument
   and the `when` subject are interface-typed. If wrong, `packNuget` fails loudly at
   `compileKotlin*` (not silent).
3. A sealed interface `Shape` and an interface-route `IShape` never both render, so no C#
   duplicate arises. Holds if the `rootInterfaces` exclusion and the closure bucket change both
   land; if only one lands, the ADR-040 backing wrapper (named after the bare interface name,
   **inferred** from `translateInterfaceBackingClass` not being read in full) collides with the
   sealed route's `Shape` and every consumer fails with CS0101. The `GeneratedBindingsCheck` in
   `scripts/verify.sh` catches this loudly.
4. `data object` subclasses of a sealed interface ride `isDataObject` (`CirClassTranslator.kt:1082`)
   unchanged. If wrong, the subclass's properties plan where none exist; ADR-111's planner would
   emit an empty plan (safe).

## Post-implementation notes (2026-09-07)

All four inferred claims in the ledger held: KSP reports `Modifier.SEALED` and enumerates
subclasses for a `sealed interface` the same as for a sealed class, the emitted Kotlin compiles
unchanged, no C# duplicate arises between the sealed route and the interface route, and `data
object` subclasses ride `isDataObject` unchanged. Fixture: `Pulse`/`Monitor` in
`test-library/.../test/issue54/SealedInterfaceSample.kt`.

`isSealedSubclass()` is true only for an **eligible** sealed-interface parent; an ineligible
parent's subclasses keep plain-class handling exactly as before this ADR, which is why `Mixed`'s
subclass `Odd` still also gets `SKIPPED_NESTED_DECLARATION` on top of the new
`SKIPPED_INELIGIBLE_SEALED_INTERFACE` on `Mixed` itself, two warnings for one hierarchy.
Suppressing the redundant second warning was out of scope here; see
[ROADMAP.md](../../ROADMAP.md).
