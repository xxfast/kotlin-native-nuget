# Enums

A Kotlin `enum class` becomes a C# `enum` with matching ordinal values. Members declared on the
enum class (properties, methods) become C# extension methods, since a C# `enum` can't carry
behavior itself.

```kotlin
enum class Mood {
  HAPPY,
  SLEEPY,
  GRUMPY;

  val description: String
    get() = when (this) {
      HAPPY -> "The cat is happy and content."
      SLEEPY -> "The cat is sleepy and ready for a nap."
      GRUMPY -> "The cat is grumpy and doesn't want to be disturbed."
    }
}
```

```C#
Mood mood = Mood.Happy;                   // HAPPY -> Happy, ordinal 0
string description = mood.Description();  // extension method
```

An entry's C# name is predictable from its Kotlin spelling alone: split the entry name on `_`, and a
segment with a lowercase letter in it keeps its own casing (only its first character is
capitalized); a segment with no lowercase letter (all caps, digits) is treated as one word and
PascalCased as before. A `const val`, wherever it's declared, follows the same rule.

| Kotlin entry | C# member |
|---|---|
| `First` | `First` |
| `SecondValue` | `SecondValue` |
| `camelCase` | `CamelCase` |
| `HAPPY_CAT` | `HappyCat` |
| `snake_case` | `SnakeCase` |
| `XMLParser_V2` | `XMLParserV2` |
| `HTTP_Status` | `HttpStatus` |
| `AB1C` | `Ab1c` |

`AB1C` stays `Ab1c`: an all-caps segment with a digit in it can't be told apart from an ordinary
all-caps segment (`HAPPY`) by any per-character rule, and preserving it would mean also preserving
`HAPPY` verbatim. Two entries whose C# names collide after this conversion (for example `FOO_BAR`
and `FooBar`) fail the build with `ERROR_CSHARP_NAME_COLLISION`, naming both; rename one of them.

A property or method typed with the enum, in any parameter, return, or getter/setter position,
binds like any other enum-typed member: `var mood: Mood` becomes a settable `Mood` property. This
also covers a member whose own type is a *different* enum, such as `enum class Swirl(val patch:
Patch)`: `swirl.Patch()` returns the C# `Patch` enum, not a raw handle.

## Nullable {id="nullable"}

A bare `Mood?` (not wrapped in a [value class](value-classes.md)) binds as C# `Mood?` at every
ordinary position: property, constructor/method/extension/top-level parameter, and return. `null`
stays distinct from every ordinal, including `Mood.Happy` at ordinal 0.

```kotlin
class MoodJournal(observed: Mood?) {
  var currentMood: Mood? = null
  fun soothe(mood: Mood?): Mood? = if (mood == Mood.GRUMPY) Mood.SLEEPY else mood
}
```

```C#
using var journal = new MoodJournal(null);
journal.CurrentMood = Mood.Grumpy;
Mood? soothed = journal.Soothe(Mood.Grumpy); // Mood.Sleepy
```

A top-level function or property getter returning `Mood?` is evaluated twice when it returns a
value. Keep those functions and getters free of side effects and ensure their result stays stable
between evaluations. Ordinary instance methods, such as `Soothe` above, evaluate once.

## As a collection component {id="as-a-collection-component"}

A bare enum crosses a `List`/`Map`/`Set` element, key, or value (nullable included) as its `int`
ordinal, both ways.

```kotlin
enum class Mood { CALM, ANXIOUS, PLAYFUL }

class MoodLedger {
  fun logMoods(moods: List<Mood>): String = moods.joinToString(",")
  fun moodsOnFile(): List<Mood> = listOf(Mood.CALM, Mood.PLAYFUL)
}
```

```C#
using var ledger = new MoodLedger();
ledger.LogMoods(new[] { Mood.Calm, Mood.Playful, Mood.Calm });
IReadOnlyList<Mood> moods = ledger.MoodsOnFile();
```

This also makes a bare-enum collection **property** settable, not get-only; see
[Collections: Mutable collection properties](collections.md#mutable-collection-properties). A
nested collection element (`List<List<Mood>>`) has no representation and is skipped; see
[Collections](collections.md).

## Nested enums {id="nested-enums-skip-named"}

An `enum class` nested inside an admitted owner (a non-generic, non-`inner` `class`, `object`, or
`interface`, at any depth; see [Classes and objects: Owners ADR-134
admits](classes-and-objects.md#nested-adr-134-owners)) is declared as a real nested C# `enum`,
`Outer.Kind`. Its extension methods are hoisted to a top-level class instead (`OuterKindExtensions`,
named for the whole enclosing chain), because C# forbids an extension method inside a nested class
(CS1109).

```kotlin
class NestedModeOwner {
  enum class Mode { ON, OFF }
  var setting: Mode = Mode.ON
  fun set(mode: Mode) { this.setting = mode }
  fun current(): Mode = setting
}
```

Name a property that holds the nested enum something other than the enum's own name (`setting`,
not `mode`): PascalCasing a `mode` property to `Mode` would collide with the nested type `Mode`
itself (CS0102).

A nested enum under a still-deferred owner (an `inner class`, a generic, or another `enum class`;
see [Classes and objects: Nested types](classes-and-objects.md#nested-classes-and-objects)) is not
declared: the declaration itself is skipped with `SKIPPED_NESTED_DECLARATION`, and a parameter,
return, or property typed with it is skipped with `SKIPPED_UNSUPPORTED_TYPE`/`SKIPPED_UNSUPPORTED_PROPERTY`
naming `UNDECLARED_ENUM`; the owning class still generates with its other members.
