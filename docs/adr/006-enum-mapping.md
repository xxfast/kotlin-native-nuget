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

## Amendment (2026-09-22): entry names keep their internal capitals, per segment (issue #285)

The Decision above wrote one line for entry names, "`SCREAMING_SNAKE_CASE` to `PascalCase`", and the
expression that implemented it lowercased every `_`-separated segment whole before uppercasing its
first character. That is correct for the shape the line considered and wrong for every other one
Kotlin admits: a PascalCase entry `SecondValue` reached C# as `Secondvalue`, `camelCase` as
`Camelcase`, `XMLParser_V2` as `XmlparserV2`. A consumer could not predict the C# member name from
the Kotlin declaration without compiling to read the `CS0117` or opening `Interop.cs`.

**Rule.** Split the entry name on `_` and drop empty segments. A segment that contains at least one
lowercase letter keeps its internal casing and only gains a capital first character. A segment with
no lowercase letter (all caps, digits) lowercases first, exactly as the original rule. Join the
segments back together. A `const val` name (top-level, `object`, or companion) follows the identical
rule; it shared the same broken expression before this amendment.

| Kotlin name | Before | After | Moves? |
|---|---|---|---|
| `First` | `First` | `First` | no |
| `SecondValue` | `Secondvalue` | `SecondValue` | yes |
| `camelCase` | `Camelcase` | `CamelCase` | yes |
| `XMLParser_V2` | `XmlparserV2` | `XMLParserV2` | yes |
| `HAPPY_CAT` | `HappyCat` | `HappyCat` | no |
| `snake_case` | `SnakeCase` | `SnakeCase` | no |
| `HTTP_Status` | `HttpStatus` | `HttpStatus` | no |
| `AB1C` | `Ab1c` | `Ab1c` | no |
| `_1ST` (Tier 1 pinned) | `1st` (illegal C#, `CS1001`) | `_1st` | yes |
| `_` / `__` (Tier 1 pinned) | empty name (illegal C#, `CS1001`) | `_` | yes |
| `FOO_BAR` + `FooBar` in one enum (Tier 1 pinned) | `FooBar` + `Foobar` (silently distinct) | fatal `ERROR_CSHARP_NAME_COLLISION`, naming both | new error |
| `FOO` + `Foo` in one enum (Tier 1 pinned) | duplicate `Foo` member, `CS0102` at the consumer's compile | same fatal error, at generation | new error replaces a worse one |

**The `AB1C` decision.** `AB1C` stays `Ab1c`, not `AB1C`. An all-caps segment with a digit in it is
indistinguishable from an ordinary all-caps segment (`HAPPY`) by any per-character rule; preserving
`AB1C` verbatim would mean also preserving `HAPPY` as `HAPPY`, which reverses the Decision above and
moves every already-published `SCREAMING_SNAKE_CASE` enum in the wild. `Ab1c` also matches the
.NET acronym-casing convention (`HttpClient`, `Xml`) and the equivalent Java/Xamarin binding rule
(`Bitmap.Config.ARGB_8888` binds as `Argb8888`).

**Two guards** close entry names Kotlin's frontend accepts but C# cannot spell at all, both of which
rendered raw before this amendment (`CS1001` inside `Interop.cs` itself, with no diagnostic naming
the cause): a converted name that ends up empty (`_`, `__`) becomes `_`; one that starts with a digit
(`_1ST` converts to `1st`) takes a `_` prefix.

**Collisions are now fatal.** Two entries whose converted C# names are equal fail generation with
`ERROR_CSHARP_NAME_COLLISION`, naming the enum and every colliding Kotlin entry, with the hint to
rename one entry. This is not new hazard the per-segment rule invents (`FOO_BAR` beside `FooBar` is
the one pair it newly brings together); it upgrades a pre-existing silent defect, since `FOO` beside
`Foo` already rendered two identically-spelled `Foo` members with `CS0102` waiting at the consumer's
compile and no diagnostic at generation time. The fix is a fatal error rather than a deterministic
suffix (`FooBar_1`) on purpose: a silently-appended suffix is the very unpredictability issue #285
reports, and an entry's ordinal must not move to make room for a rename.

**Breaking-change handling.** This ships as a bug fix, not a deprecation cycle: any published
PascalCase, camelCase, or mixed-segment entry (or `const val`) gets a new C# spelling, so a
consumer's existing call to the old (wrong) name fails to compile with a loud `CS0117`, never
silently. No `[Obsolete]` alias ships alongside the corrected name, because two C# enum members
sharing one ordinal makes `ToString()` non-deterministic between the two spellings and doubles the
IDE's autocomplete surface for no benefit. Ordinals, the native ABI, and the ADR-054 export contract
do not move; only the C# spelling changes. No existing fixture name in this repository moved, since
every enum entry and `const val` already in `test-library` was already `SCREAMING_SNAKE_CASE` or a
single Pascal word.

**Scope.** Generator-only (`nuget-processor`): an enum entry crosses the C ABI as its ordinal `int`
in every position, and a `const val` is a compile-time literal, so this is a C#-surface spelling
change with no ABI, runtime, or plugin-side effect. The mirror defect in the reverse direction
(binding a C# enum member with an internal acronym run into Kotlin, `nuget-plugin`'s
`toEnumScreamingSnake`) is a separate module and needs its own acronym-run decision; it is not part
of this amendment (see ROADMAP Phase 10).

### Release note (next release after 0.7.0)

> **A PascalCase or camelCase Kotlin enum entry, or `const val`, keeps its internal capitals in C#.**
> `enum class Example { SecondValue }` is now `Example.SecondValue`, not `Example.Secondvalue`;
> `const val MaxRetries` is now `MaxRetries`, not `Maxretries`. A `SCREAMING_SNAKE_CASE` entry
> (`HAPPY_CAT` to `HappyCat`) and an all-caps-with-a-digit entry (`AB1C` to `Ab1c`) are unchanged.
> Ordinals and the native ABI are unaffected; only the C# spelling moves, so a consumer that
> references the old, incorrectly-cased name gets a build-time `CS0117` and needs a rename. Two
> entries that convert to the same C# name now fail the build with `ERROR_CSHARP_NAME_COLLISION`
> instead of silently compiling two same-named members (previously `CS0102` at the *consumer's*
> compile with no warning from the generator).
