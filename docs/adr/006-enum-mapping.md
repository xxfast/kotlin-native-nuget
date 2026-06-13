# ADR-006: Enum class mapping — ordinal-backed C# enums with extension methods

## Status

Accepted

## Context

Kotlin enum classes can have properties and methods, making them richer than C# enums (which are purely integer-backed values). We need to decide how to bridge these differences.

### Kotlin enums vs C# enums

| Feature              | Kotlin | C#  |
|----------------------|--------|-----|
| Values with ordinals | Yes    | Yes |
| Properties per entry | Yes    | No  |
| Methods              | Yes    | No  |
| Abstract members     | Yes    | No  |
| Interfaces           | Yes    | No  |

C# enums are just named integers — they cannot hold properties or methods.

## Alternatives Considered

### 1. C# enum + extension methods (chosen)

Map the enum itself as a plain `int`-backed C# enum. Map properties and methods as static extension methods on that enum.

```csharp
public enum Mood { Happy = 0, Sleepy = 1, Grumpy = 2 }

public static class MoodExtensions
{
    public static string Description(this Mood mood) => ...;
}
```

**Pros:** Idiomatic C# pattern (used throughout .NET, e.g. `Enum.HasFlag`). Consumers can use dot syntax (`mood.Description()`). Enum values work in `switch` statements.
**Cons:** Extension methods aren't true members — IDE might not autocomplete them without the `using` directive.

### 2. Static class with constants

Model the enum as a static class with `int` constants and instance-style methods.

**Pros:** Can hold any member.
**Cons:** Loses C# enum semantics (`switch` exhaustiveness, `Enum.Parse`, flags). Not idiomatic.

### 3. Class with static instances (Java-style)

Create a full C# class with static readonly instances.

**Pros:** Most feature-parity with Kotlin.
**Cons:** Heavy. Not interchangeable with `int`. Doesn't work with C# `switch`/pattern matching.

## Decision

Use **ordinal-backed C# enum + extension methods**:

- Enum entries → `public enum Name { Entry = ordinal, ... }`
- Entry names: `SCREAMING_SNAKE_CASE` → `PascalCase` (`HAPPY` → `Happy`)
- Properties → extension methods on the enum in a `{Name}Extensions` static class
- Methods → same pattern (extension methods)

### Bridge mechanism

Enums cross the C bridge as `int` (ordinal):

```kotlin
// Kotlin bridge — getter
@CName("mood_get_description")
fun export_mood_get_description(ordinal: Int): String {
    val mood = Mood.entries[ordinal]
    return mood.description
}
```

```csharp
// C# — extension method calls native with ordinal
public static string Description(this Mood mood)
    => Marshal.PtrToStringUTF8(Native_GetDescription((int)mood))!;
```

For enum-typed class properties:
- Getter returns `int` (ordinal), C# casts: `(Mood)Native_Get_mood(handle)`
- Setter takes `int`, C# casts: `Native_Set_mood(handle, (int)value)`

### Filtering inherited members

Kotlin enums inherit `name` and `ordinal` properties from `kotlin.Enum`. These are excluded from generation since they're already represented by the C# enum itself.

## Consequences

- Extension methods require `using` the namespace to be discoverable
- Can't override/polymorph enum methods (not applicable for enums anyway)
- If Kotlin adds/removes/reorders entries, ordinals shift — binary breaking change

**Positive:**
- C# consumers use standard enum patterns (`switch`, `Enum.Parse`, flags)
- Extension methods provide natural dot-syntax for properties
- Zero allocations for enum values (just int casting)
- Familiar pattern to C# developers (widely used in .NET)

**Mitigations:**
- Generated code places extensions in the same namespace as the enum
- Document that enum entry order is significant for binary compatibility
