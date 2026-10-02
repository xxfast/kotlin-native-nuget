# Enums

A Kotlin `enum class` becomes a C# `enum` with matching ordinal values. A property or function
declared on the enum class becomes a C# extension method, since a C# `enum` can't carry behavior
itself. A companion object's functions and properties become statics beside them; see
[Functions and companion members](#enum-functions).

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

A getter that throws surfaces as a catchable, mapped exception rather than crashing the process,
the same as any other property getter. A `var` binds a setter too, `SetX(this Mood mood, value)`,
alongside the getter:

```kotlin
enum class Mood {
  HAPPY, SLEEPY, GRUMPY;

  var nickname: String = name

  val nineLives: Int
    get() = if (this == GRUMPY) throw IllegalStateException("refuses to count") else 9
}
```

```C#
Mood.Happy.SetNickname("Oreo the Biscuit");
string nickname = Mood.Happy.Nickname();   // "Oreo the Biscuit"

try
{
    Mood.Grumpy.NineLives();
}
catch (KotlinInvalidOperationException ex)
{
    // ex.KotlinType == "kotlin.IllegalStateException"
}
```

A property's own name PascalCases the same way any other bridged property does, whatever its
casing or type: a camelCase constructor property (`displayName`) binds as `DisplayName()`, and a
`Boolean` name keeps a leading `is` rather than dropping it (`isCuddly` binds as `IsCuddly()`, not
`Cuddly()`).

```kotlin
enum class Mood(val displayName: String, val isCuddly: Boolean) {
  HAPPY("Purring Oreo", true),
  SLEEPY("Snoozing Mylo", true),
  GRUMPY("Hissing Oreo", false);

  val isSleepy: Boolean
    get() = this == SLEEPY
}
```

```C#
Mood.Happy.DisplayName();  // "Purring Oreo"
Mood.Sleepy.IsCuddly();    // true
Mood.Grumpy.IsSleepy();    // false
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

An enum member property follows the same type rules as any other property: a class-typed member
returns an owned wrapper the caller must dispose (`using var toy = mood.FavouriteToy();`), an
`Int?`-shaped member reads through the usual nullable pair, and a member typed with something a
property can't cross (a lambda, for example) is a named skip rather than a broken export.

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

A property getter returning `Mood?` is evaluated twice when it returns a value. Keep those getters
free of side effects and ensure their result stays stable between evaluations. A top-level function
returning `Mood?`, and ordinary instance methods such as `Soothe` above, evaluate once.

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

## Functions and companion members {id="enum-functions"}

A function declared in the enum class body binds as an extension method, the same way a property
does. An `abstract fun` with a body on each entry dispatches to the entry's own body:

```kotlin
enum class Chatter {
  CHIRP { override fun sound(times: Int): String = List(times) { "chirp" }.joinToString(" ") },
  TRILL { override fun sound(times: Int): String = List(times) { "trrrl" }.joinToString(" ") };

  abstract fun sound(times: Int): String
}
```

```C#
public static string Sound(this Chatter chatter, int times)   // in ChatterExtensions
```

The receiver parameter is named after the enum (`mood`, `chatter`), or `receiver` if the function
already has a parameter of that name. Overloads, default arguments and a throwing function
(a catchable mapped exception) behave as they do on a class. A class-typed return is a fresh
wrapper you must `Dispose`.

A function in the enum's `companion object` binds as a plain static method, and a companion `val`
or `var` as a static property, both on the same `{Enum}Extensions` class. A C# `enum` cannot
declare statics, and the generated code's language version has no static extension members, so
there is no `Mood.Fallback()`:

```kotlin
enum class Mood {
  HAPPY, SLEEPY, GRUMPY;

  fun isLoudNow(): Boolean = this == GRUMPY

  companion object {
    fun fallback(): Mood = SLEEPY
    val houseFavourite: Mood = HAPPY
    var lastSeen: Mood = SLEEPY
  }
}
```

```C#
bool loud = Mood.Grumpy.IsLoudNow();
Mood fallback = MoodExtensions.Fallback();
Mood favourite = MoodExtensions.HouseFavourite;
MoodExtensions.LastSeen = Mood.Grumpy;
```

An enum with only functions still gets its `{Enum}Extensions` class.

### What is not bound

- A `suspend`, generic, or `Flow`-returning enum member function, and a lambda-returning one, is
  skipped with a named diagnostic. Expose it as a top-level function taking the enum as a receiver
  or parameter instead.
- A companion `const val` is skipped (`SKIPPED_UNSUPPORTED_PROPERTY`). Use a top-level `const val`.
- Two declarations that spell the same C# signature in `{Enum}Extensions` fail generation with
  `ERROR_CSHARP_SIGNATURE_COLLISION`: a member function beside a same-named member property
  (`fun description()` beside `val description`), or a companion `val` beside any same-named
  member. Rename one.

## C# enums in Kotlin {id="reverse-enum-naming"}

A bound C# `enum` becomes a Kotlin `enum class` whose entries are `SCREAMING_SNAKE_CASE`. An
acronym run stays one word, and a digit starts a new one:

```C#
public enum VetTriage { OK, HTTPTimeout, IOError, Win32NT }
```

```kotlin
VetTriage.OK            // OK
VetTriage.HTTP_TIMEOUT  // HTTPTimeout
VetTriage.IO_ERROR      // IOError
VetTriage.WIN32_NT      // Win32NT
```

If two members of one enum would get the same entry name (`HTTPStatus` and `HttpStatus` both give
`HTTP_STATUS`), each of them keeps its C# spelling as the Kotlin entry and the build logs one
`info_enum_entry_kept_verbatim` note per member. The enum still binds in full.
