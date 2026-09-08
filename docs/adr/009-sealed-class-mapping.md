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

## Consequences

- Sealed hierarchies are type-safe and pattern-matchable in C#
- Exhaustiveness is not compiler-enforced (consumer responsibility)
- Discriminator adds one extra bridge call when receiving a sealed type
- Subclass ordinals are determined by declaration order — reordering in Kotlin is a binary breaking change
- Nested classes match Kotlin's scoping (`Observation.Alive` mirrors `Observation.Alive`)
