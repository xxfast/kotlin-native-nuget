# ADR-009: Sealed class mapping — abstract base with nested sealed subclasses

## Status

Accepted

## Context

Kotlin sealed classes restrict which types can extend them — the compiler knows all subtypes at compile time, enabling exhaustive `when` expressions. C# has no direct equivalent that enforces exhaustiveness.

### How other platforms handle sealed classes

- **Java interop**: Abstract class with subclasses. Java 17+ added `sealed`/`permits` but Kotlin targets older JVM.
- **Swift Export**: Regular class hierarchy. Swift's idiomatic equivalent is `enum` with associated values (exhaustive in `switch`), but Kotlin's Swift Export doesn't use this — sealed subclasses can have their own methods/properties which Swift enums can't.
- **ObjC Export**: Regular class hierarchy. ObjC has no abstract/sealed concepts.

## Decision

Map to **abstract base class + nested `sealed` subclasses** with a discriminator function for runtime type resolution.

```csharp
public abstract class Observation : IDisposable
{
    internal IntPtr _handle;

    public sealed class Alive : Observation { ... }
    public sealed class Dead : Observation { ... }

    internal static Observation FromHandle(IntPtr handle)
    {
        return Native_GetType(handle) switch
        {
            0 => new Alive(handle),
            1 => new Dead(handle),
            _ => throw new InvalidOperationException(),
        };
    }
}
```

### Why nested sealed classes

- `sealed` on subclasses prevents further subclassing (mirrors Kotlin's restriction)
- Nesting groups the hierarchy visually — consumers see `Observation.Alive`, `Observation.Dead`
- C# pattern matching works: `result switch { Observation.Alive a => ..., Observation.Dead d => ... }`

### Discriminator function

When a sealed type is returned from Kotlin, C# needs to know which subclass to construct. A bridge function `_get_type` returns an ordinal that maps to the subclass:

```kotlin
@CName("observation_get_type")
fun export_observation_get_type(handle: COpaquePointer): Int {
    return when (handle.asStableRef<Observation>().get()) {
        is Observation.Alive -> 0
        is Observation.Dead -> 1
    }
}
```

## Exhaustiveness

Kotlin's `when` on sealed classes is compile-time exhaustive — the compiler errors if a branch is missing.

C# pattern matching on abstract classes is **not exhaustive** — the compiler emits a warning (`CS8509`) but not an error. Consumers must add a default arm:

```csharp
string message = result switch
{
    Observation.Alive a => ...,
    Observation.Dead d => ...,
    _ => throw new InvalidOperationException(),  // required by C#
};
```

This is a fundamental language gap — C# cannot enforce that all subtypes are handled. The `_ => throw` arm serves as a safety net that should never be reached.

## Limitations

### Amendment (2026-09-07): flat (sibling) sealed hierarchies are supported

A sealed subclass declared *beside* its base, not nested inside it, used to be collected twice:
once by the ordinary class route as a namespace-level type with its own constructor, and once by
this ADR's sealed route as a nested type with a discriminator arm. One Kotlin type produced two
different C# types, so an `is` check disagreed with itself depending on which one the caller held,
and `FromHandle` could only ever hand back the nested one.

The sealed route is now the sole owner of every sealed subclass. `rootClasses` and the
reachability closure exclude any class whose declared superclass is sealed
(`isSealedSubclass()` in `ForwardClassMembership.kt`), so a sibling subclass is declared at
namespace level beside its base, `public sealed class Label : FlatShape`, with an `internal`
constructor and its own `flatshape_label_*` exports, exactly like a nested subclass would be
except for the enclosing scope. A subclass that really is nested inside its sealed base stays
nested (`FlatShape.Circle`). Member positions referencing either spell it by its actual Kotlin
scope, not by whether it happens to be sealed.

### Amendment (2026-09-08): an `object` sibling subclass is declared once, not twice

The 2026-09-07 sibling-hierarchy fix above filtered `isSealedSubclass()` out of `rootClasses`, but
not out of `rootObjects`: a sibling `object`/`data object` subclass was still collected a second
time as an empty namespace-level `public static class Loaf { }` alongside the sealed route's own
`public sealed class Loaf : FlatShape`. CS0101 (duplicate type), plus CS0722 at any position
returning the concrete arm, since C# cannot return a `static` type. Every pre-existing
sealed-subclass-object fixture sat in the one combination this bug is invisible in (module-local
**and** nested), so nothing caught it until a top-level, cross-module `object` arm did.

Fixed at two routes: `NugetProcessor.kt`'s `rootObjects` gained the same
`.filter { !it.isSealedSubclass() }` `rootClasses` already had, and
`ForwardReachabilityClosure.reachabilityBucket()`'s object branch became
`classKind == OBJECT && !isSealedSubclass() -> OBJECT`, falling through to `SEALED_SUBCLASS`
otherwise. Only the `OBJECT` kind is qualified in that `when`, deliberately: an *intermediate*
sealed class is both sealed and a sealed subclass and must keep `SEALED_CLASS`, so hoisting the
check above the `when` would have broken that case instead.

Fixture: `FlatShapeSample.kt`'s `data object Loaf : FlatShape()` (sibling, module-local) and
`test-models/.../models/Nap.kt` via `Newsroom.nap()`/`deepNap()` (cross-module). `SealedSubclassObjectTests`
is 12 `[Fact]`, not 14.

### Amendment (2026-09-07): a class, object, or companion method returning a sealed base binds through the same discriminator

A sealed base at any callable **return** position, class method, object or companion member,
extension function, or top-level function, bare, nullable, or as a `List`/`Map`/`Set` component,
now binds through this ADR's `FromHandle` discriminator. Previously only a top-level function's
bare sealed return bound, through a legacy hand-rolled route in `FunctionExports.kt` and
`CirFunctionTranslator.kt`; the same signature on a class, object, or companion member generated
nothing and warned nothing (`NestedShapeFactory.shapeOf(): NestedShape`, `FlatShapeFactory.of(radius):
FlatShape`, `Issue54Shapes.pick(n): Issue54Shape`, `Issue54Shapes.everyShape(): List<Issue54Shape>`).

The fix rewrites a sealed **result** through a shared `BridgeType.sealedAsHandle()`, lifted from the
[ADR-105](105-sealed-property-position.md) property planner and now shared by both, which recurses
through `Nullable` and `Collection` components; parameters are untouched. `ForwardCirPlanProjection`
reconstructs the handle through `X.FromHandle(nativeResult)` the same way the property projection
does. The legacy top-level sealed arms in `FunctionExports.kt`/`CirFunctionTranslator.kt` are now
unreachable (every sealed return goes through the plan) and were deleted rather than left dead; a
top-level `fun shapes(): List<Shape>` used to bind through the generic legacy route, not the plan,
and now binds through the plan instead, rendering the same idiom. Export names are unchanged; the
private C# import method for a top-level sealed return is now spelled `Native_Make` rather than
`Make_native` internally (the `DllImport` `EntryPoint` itself is unchanged).

Rejected alternative: a third, class-route copy of the legacy hand-rolled arm, inheriting the
legacy route's own gaps (no `enum` parameter support, bare simple-name spelling) rather than the
plan's.

Parameters remain deferred; see the [Limitations](../topics/interfaces-abstract-sealed.md#limitations)
section of the forward sealed-class page and [ROADMAP.md](../../ROADMAP.md).

### Amendment (2026-09-07): subclass properties move onto the ADR-062 property plan

A sealed subclass's own properties no longer have their own hand-rolled marshalling in this
route. They are planned by the [ADR-062](062-forward-callable-plan.md) property plan and
projected by the same shared emitter and C# projection an ordinary class property uses; see
[ADR-111](111-sealed-subclass-properties-on-the-property-plan.md). This route keeps only what has
no plan shape: the discriminator export and `FromHandle` dispatcher, `Dispose`, and the
data-class `equals`/`hashCode`/`toString` methods.

### Amendment (2026-09-07): a `data object` subclass binds its data methods like a data class subclass

A `data object` sealed subclass is a data class as far as Kotlin's generated members go, so its C#
wrapper now binds the same `_equals`/`_hashcode`/`_tostring` exports a `data class` subclass binds,
instead of a fixed `ToString()` literal and no `Equals`/`GetHashCode` at all. The prior rendering
left those three Kotlin exports orphaned and meant two C# wrappers over the one Kotlin singleton were
never `Equals`, since every read mints a fresh wrapper and reference equality never held.
`CirSealedRenderer` now treats a `data object` the same as a `data class` subclass for this purpose.

### Amendment (2026-09-07): an eligible sealed interface takes this same route

[ADR-112](112-sealed-interface-mapping.md) extends this route to a `sealed interface` whose subclasses are all nested classes/objects with no other superclass and no sub-interfaces: it renders exactly as above, `public abstract class Pulse` with nested `sealed` subclasses and `Pulse.FromHandle`, and no C# interface is declared for it.

### Amendment (2026-09-11): an `open` arm renders `public class`, and its `open` members render `virtual`

An arm declared `open class Perch(open val height: Int) : Roost()` now renders `public class Perch : Roost`,
not `public sealed class`, and its declared `open val` / `open fun` render `public virtual`. A
Kotlin `class HighPerch : Roost.Perch()` is not an arm (`isSealedSubclass()` claims only a class
whose declared base carries `sealed`), so it takes the ordinary class route, and that route now
spells its base by nested C# name: `public class HighPerch : Roost.Perch`.

Before this, all three halves disagreed with each other. Every arm was rendered `public sealed class`
unconditionally, every arm member was pinned to neither `virtual` nor `override`, and the ordinary
class route spelled its base by simple name. A Kotlin subclass of an arm therefore produced an
`Interop.cs` that did not compile: `CS0509` (cannot derive from sealed `Perch`), `CS0246` for the
unqualified `: Perch` spelling of a nested type, and `CS0506` on each member the subclass overrode.

The `virtual` gate is the arm being `open`, at both member sites (`ForwardCallablePlanner`'s
`sealedSubclassEntries` for methods, `CirClassTranslator`'s arm property loop for properties),
reusing the same `isOpenForOverride()` predicate an ordinary class uses ([ADR-101](101-unexported-supertype-skip.md)).
A final arm is unchanged byte for byte: `virtual` inside a `public sealed class` is CS0549, and an
`open` member of a final arm is unreachable in Kotlin anyway. `isOverride` stays `false` on every
arm: the generated abstract base declares no member to override (CS0115).

`FromHandle` is unchanged and still yields the arm. The Kotlin discriminator's `when (obj)` is over
direct arms only, so a `HighPerch` instance materialises as a `Perch` wrapper. The handle is the
same object and Kotlin dispatch stays virtual, so a call through it reaches `HighPerch.describe()`;
only the static C# type is the arm. Widening the discriminator to non-direct subclasses would
reopen this ADR's flat-ordinal shape and was rejected here.

An `abstract` arm was out of scope here (`FromHandle`'s `new Perch(handle)` would be CS0144); the
2026-10-04 amendment below adds it.

### Amendment (2026-09-13): generalised to every nested declaration kind

[ADR-133](133-nested-types.md) lifts this ADR's nested-block rendering (`indentNestedBody()`,
promoted from `private` to `internal`) out of the sealed route entirely: a plain nested `class`,
`object`, `interface`, or `enum class` under a non-generic, non-`inner` `class` or `object` owner now
nests in the generated C# the same way a sealed arm always has, at any depth. The sealed route itself
is unchanged; it is now one caller of the shared mechanism rather than the mechanism's only caller.

### Pointer (2026-09-13): a sealed base or arm may now own its own nested declaration

[ADR-134](134-nested-types-under-deferred-owners.md) admits a sealed base and a sealed arm as nesting
owners in their own right, not only as something ADR-133's shared mechanism is borrowed for: a plain
`class`/`object`/`interface`/`enum class`/`value class` declared beside the arms binds through the
same `nestedDeclarations` slot this ADR's blocks already render (`Purr.Detail` beside `On`/`Off`), and
one declared inside an arm binds inside that arm's own block (`Purr.On.Trace`). An ADR-112 *eligible*
sealed interface owns its nested declarations the same way, under the `public abstract class` it
renders as. `FromHandle` and the discriminator are unaffected; a nested declaration is not itself a
subclass, so it never appears in the `switch`.

## Consequences

- Sealed hierarchies are type-safe and pattern-matchable in C#
- Exhaustiveness is not compiler-enforced (consumer responsibility)
- Discriminator adds one extra bridge call when receiving a sealed type
- Subclass ordinals are determined by declaration order — reordering in Kotlin is a binary breaking change
- Nested classes match Kotlin's scoping (`Observation.Alive` mirrors `Observation.Alive`)

### Amendment (2026-10-04): an `abstract` arm renders `abstract`; abstract classes get a backing wrapper

The 2026-09-11 amendment above left an `abstract` arm out of scope, and
[ADR-064](064-forward-unsupported-declaration-diagnostics.md)'s 2026-10-03 amendment called its
`public sealed class` rendering harmless because the arm has no constructor. Neither held. An
exported Kotlin subclass of an abstract arm (`class DeepTorpor : Torpor.Dormant()`) failed the C#
build with CS0509 (cannot derive from a sealed class) and then CS0115 on its overrides, and an
abstract arm's `open` members were not `virtual` (CS0506).

**Arm modifier.** An `abstract` arm renders `public abstract class`, an `open` arm `public class`,
and a final arm `public sealed class`, unchanged. One predicate, `isForwardExtensible()` (`open` or
`abstract`), now drives both the class modifier and the `virtual` gate on the arm's own `open`
members, and is the same rule the ordinary class route uses. An abstract arm declares its
`abstract` members (plain, lambda-parameter and property) `abstract` in C#.

**Backing wrapper.** C# cannot construct an abstract class, and a Kotlin handle to any subclass of
the arm, exported or not, still has to come back as the arm. Every site that reconstructs a value
typed as the arm therefore constructs an `internal sealed class Backing : Arm` nested in it: the
sealed discriminator's `FromHandle`, the `Factories` entry, a return, a property and a list element.
The wrapper overrides each abstract member with a call-through export, so Kotlin's virtual dispatch
picks the implementation, and inherits everything else, `Dispose` included. The name is `Backing`,
or the first free `Backing_`-suffixed spelling when the arm's own name, one of its members, a type
nested in the arm or a type nested in the sealed base already claims it (CS0102, CS0108, CS0542).
Consumers never name it.

A Kotlin `DeepTorpor` returned as `Torpor` or `Torpor.Dormant` therefore satisfies
`is Torpor.Dormant`, not `is DeepTorpor`, and every member answers as the Kotlin object. That is the
2026-09-11 behaviour for an `open` arm's subclass; the discriminator still reads direct arms only,
and the flat-ordinal shape is unchanged.

**Ordinary abstract classes.** Abstract-class mapping has no owning ADR
([ADR-075](075-collection-property-getter-setter-independence.md) and
[ADR-101](101-unexported-supertype-skip.md) touch it), so the rule is recorded here. A non-generic
ordinary `abstract class` used as a return type, a property type or a list element rendered
`new Animal(...)` (CS0144). It now constructs the same nested `Backing` wrapper, which also
implements the abstract `Dispose()` and `DisposeAsync()` and the interface members the class leaves
unimplemented. To give the wrapper something to call, `isForwardPlannableMemberOf` also plans an
inherited interface member the class does not implement. A member the plan refuses is left out of
C# instead of declared `abstract`, because the wrapper could not override it (CS0534). An ordinary
abstract class qualifies only when no abstract or sealed class sits above it; a sealed arm always
qualifies. A generic abstract class never does.

**`Result` members.** An abstract class's or abstract arm's `Result`-returning member keeps its
[ADR-195](195-result-try-overload.md) `TryX` twin: the owner declares an `abstract` twin and the
wrapper overrides it. The wrapper builds its overrides from the owner's member list after the twin
collision pass, so it overrides a twin exactly when the owner still declares it. Built before that
pass, it overrode a twin the pass had dropped from an override chain, which fails with CS0115 (a
cross-PR defect found on the assembled stack and fixed). Verified by
`Tier1ResultTryInheritanceTest`, a cell in `Tier1AbstractClassBackingTest` that covers both owner
kinds with a surviving and a dropped twin, and three `TryWeigh` tests in `AbstractBackingTests.cs`.

**Verified.** `Tier1SealedAbstractArmTest` and `Tier1AbstractClassBackingTest` pin the arm
modifiers, the wrapper and the compiling output. `Tier1SuspendOwnerAuditTest` moved two members
from "named skip" to "declared abstract and overridden"
([ADR-064](064-forward-unsupported-declaration-diagnostics.md)'s 2026-10-04 amendment). Fixture
`test-library/.../torpor/TorporSample.kt` and `IntegrationTests/AbstractBackingTests.cs` (12 tests)
run it through the native pipeline, and `LeakTests` row
`AbstractBacking_BaseArmAndClassTypedReturns_ReturnToBaseline` pins the release.
`:nuget-processor:test` 1678 passed, 0 failed; native pipeline IntegrationTests 3070, LeakTests
168, 7 AOT shapes.

**Known limits, verified, not fixed here.** A follow-up item of the same batch owns them.

- An abstract class below another abstract or sealed class (`Puppy : Animal`) gets no wrapper, so
  returning one still fails with CS0144.
- `abstract fun ticks(): Flow<Int>` on an abstract class fails the build with "Forward ABI missing
  Kotlin export ..._ticks_collect": the Kotlin export builder (`ClassExports.kt`) drops abstract
  `Flow` members while C# still declares them.
- An arm member `backing()` beside a `class Backing` nested in the sealed base fails with CS0108.
- `Dog.OnPet` renders `override` against an owner that never declared it (CS0115) on an abstract
  class without a wrapper.
