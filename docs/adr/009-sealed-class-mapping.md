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

Currently only nested sealed subclasses are supported (subclasses declared inside the sealed class body). Kotlin also allows flat/unnested sealed hierarchies where subclasses are top-level in the same file:

```kotlin
// Flat — not yet supported
sealed class Observation
data class Alive(val cat: Cat) : Observation()
data class Dead(val cause: String) : Observation()
```

For flat sealed classes, the C# output should mirror the structure — separate classes in the same namespace rather than nested. This is deferred to a future improvement.

## Consequences

- Sealed hierarchies are type-safe and pattern-matchable in C#
- Exhaustiveness is not compiler-enforced (consumer responsibility)
- Discriminator adds one extra bridge call when receiving a sealed type
- Subclass ordinals are determined by declaration order — reordering in Kotlin is a binary breaking change
- Nested classes match Kotlin's scoping (`Observation.Alive` mirrors `Observation.Alive`)
