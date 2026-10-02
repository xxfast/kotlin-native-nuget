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

## 2026-09-26 amendment: camelCase property entry points and enum member skips

*(Superseded in part by the 2026-09-29 amendment below: the enum member and companion function skips, and the companion property skip, no longer exist except for a companion `const val`.)*

The Decision's Bridge mechanism section always meant the property's own name to reach both halves of
the bridge, but the implementation didn't: the C# renderer (`CirEnumRenderer.kt`) lowercased the
property name into the C entry point (`mood_get_issleepy`), while the Kotlin `@CName` export kept it
verbatim (`mood_get_isSleepy`, `EnumExports.kt`). Any camelCase property name disagreed between the
two halves and failed the ADR-055 ABI contract check, aborting generation for the *whole module*
rather than naming a skip for the one property. This was never specific to an `is`-prefixed
`Boolean`; a plain camelCase `String` property (`displayName`) reproduced it identically. The entry
point now keeps the Kotlin spelling verbatim on both sides, exactly like every other property route.
No already-shipping export moves: a single-lowercase-word property name (`description`) was already
spelled identically whether lowercased or not, so the fix is invisible to every enum published before
it. The public C# extension-method spelling this ADR's Decision describes is unchanged
(`IsSleepy(this Mood mood)`).

The same fix carries one more correction: a `Boolean` enum property getter's extern now carries
`[return: MarshalAs(UnmanagedType.I1)]`, the 1-byte marshal every other `bool`-returning route
already emits (without it, .NET reads a 4-byte Win32 `BOOL` over a 1-byte Kotlin `bool` and garbage
in the upper three bytes can turn `false` into `true`). It was missing on this one route because no
enum `Boolean` property had shipped before to notice.

Two shapes the Decision's "Properties → extension methods ... Methods → same pattern (extension
methods)" line describes are still not delivered, and now say so instead of vanishing silently
(ADR-064's per-declaration diagnostic discipline applied to the enum route): a property declared in
an enum's `companion object` is a named skip, `SKIPPED_UNSUPPORTED_PROPERTY`, the kind every dropped
property reports under; a function declared in the enum class body, or in its companion object, is a
named skip under a new kind, `SKIPPED_ENUM_MEMBER_FUNCTION`. Binding an enum's own methods as
extension methods, the way its properties already are, remains open; see ROADMAP.md's Phase 4 item
"An enum member function, and a function declared in an enum's companion object, are not bound at
all."

**Scope.** Generator-only (`nuget-processor`), like the 2026-09-22 amendment: no ABI, runtime, or
plugin-side effect.

## 2026-09-28 amendment: enum member properties move onto the forward property plan

The Bridge mechanism section's Kotlin/C# sample above showed the getter export with no error
channel; that was accurate at the time but is no longer how the route is generated. An enum
member property's getter and setter now bind through
[ADR-172](172-enum-member-properties-on-the-forward-plan.md)'s `ForwardPropertyPosition.ENUM_MEMBER`,
the same plan every other property position uses, rather than through the hand-written
`EnumExports.kt`/`CirEnumRenderer.kt` pair this ADR originally described.

Five points from that move affect what this ADR promises a reader:

- The bare getter spelling this ADR's Decision shows (`Description()`, not `GetDescription()`) is
  kept; so is the receiver parameter name (`mood`, not `receiver`). Neither moved.
- Every getter export gains an ABI-changing error slot (`errorOut` on Kotlin, `out IntPtr error`
  on C#): a throwing getter now surfaces as a catchable mapped `KotlinException` instead of
  aborting the host process. The private extern name changes alongside it
  (`Native_GetX` → `Native_MoodGetX`); the public getter name does not.
- An enum `var`, which this ADR never mentioned binding a setter for, now does:
  `SetX(this Mood mood, value)`, with the same containment as the getter. This is new public
  surface on every enum that already declares a mutable member property.
- A keyword or `Error`-named enum now gets a legal receiver parameter name, fixed incidentally by
  the move: the receiver name goes through the same escape every other parameter name on the plan
  already uses.
- A hand patch that added only the error slot to the old route (2 files, versus the plan route's
  roughly 9) was considered and rejected: it would have fixed the getter abort alone and left the
  setter drop, the missing type gate, and the missing KDoc unfixed. See ADR-172 for the full
  account, including the two collision diagnostics (`ERROR_CSHARP_SIGNATURE_COLLISION`,
  ADR-117's `ERROR_C_ENTRY_POINT_COLLISION`) a same-named member/extension pair now reaches.

**Scope.** Generator-only (`nuget-processor`), like the two amendments above.

## 2026-09-29 amendment: enum member functions and companion members bind on the forward callable plan

The Decision's "Methods → same pattern (extension methods)" line is now delivered, on the
[ADR-062](062-forward-callable-plan.md) forward callable plan, the route
[ADR-172](172-enum-member-properties-on-the-forward-plan.md) moved enum member properties onto. No
second hand-written route was built.

- **A function declared in the enum class body** plans under a new `ForwardCallableOrigin.ENUM_MEMBER`
  with an enum-valued receiver. Kotlin calls `Mood.entries[receiver].isLoudNow()`, so an `abstract fun`
  with per-entry bodies (`Chatter.sound`) dispatches to each entry's body. C# gets
  `public static bool IsLoudNow(this Mood mood)` in `MoodExtensions`. The receiver is named after the
  enum, and falls back to `receiver` when a parameter already has that name. It carries the ordinary
  error slot, overloads, default arguments and KDoc.
- **A function declared in the enum's `companion object`** plans under the existing COMPANION origin,
  extended to enums, and renders as a plain static method in `{Enum}Extensions`
  (`MoodExtensions.Fallback()`). A C# enum cannot declare members, and the static extension members that
  would allow `Mood.Fallback()` are C# 14, above the generated code's C# 12 floor (verified by spike: an
  `extension(Mood) { ... }` block fails with `CS1001` at `LangVersion 12.0`).
- **A companion `val`/`var`** folds in on the same path as a static property in `{Enum}Extensions`
  (`MoodExtensions.HouseFavourite`). A companion `const val` stays a named
  `SKIPPED_UNSUPPORTED_PROPERTY`.
- **An enum with only functions** now gets its `{Enum}Extensions` class.
- **Named skips:** a `suspend`, generic, `Flow`-returning or lambda-returning enum member function is
  named under `UNROUTED_POSITION` (the suspend wording now covers enum owners). The dedicated kind
  `SKIPPED_ENUM_MEMBER_FUNCTION` is removed. It was added 2026-09-27, after the 0.7.0 release, so
  no published `NugetDiagnostics.json` carried it.
- **Collisions** in `{Enum}Extensions` fail generation with `ERROR_CSHARP_SIGNATURE_COLLISION`: a
  member function beside a same-named member property, or a companion `val` beside any same-named
  member.
- **Side effect:** a dependency klib's enum companion function or property now binds too
  (`dev.other.bytype.PurrLevel.Companion.contented`).

Fixtures: `cat/Mood.kt`, `cat/Chatter.kt`; consumer tests `IntegrationTests/EnumMemberFunctionTests.cs`;
`LeakTests` row 1i.

**Scope.** Generator-only (`nuget-processor`). Additive public surface: every enum member or companion
function that used to be skipped with a warning is now a C# member.

## 2026-10-02 amendment: reverse entry names keep acronym runs together

The reverse direction (a bound C# enum generated as a Kotlin `enum class`) spelled each
member as `SCREAMING_SNAKE` by putting `_` before every capital after the first. Every acronym came
apart: `HTTPStatus` bound as `H_T_T_P_STATUS`, `OK` (as in `System.Net.HttpStatusCode.OK`) as `O_K`,
and a C# member already in `SNAKE_CASE` as `S_N_A_K_E__C_A_S_E`.

**Rule.** kotlinx-serialization's `JsonNamingStrategy.SnakeCase`, uppercased. A new word starts at
an uppercase letter that follows a lowercase letter or a digit, and at the last capital of an
uppercase run when a lowercase letter follows it. An existing `_` is a word boundary; repeated,
leading and trailing underscores are dropped.

| C# member        | Kotlin entry       |
|------------------|--------------------|
| `HTTPStatus`     | `HTTP_STATUS`      |
| `IOError`        | `IO_ERROR`         |
| `OK`             | `OK`               |
| `SNAKE_CASE`     | `SNAKE_CASE`       |
| `Foo_Bar`        | `FOO_BAR`          |
| `Win32NT`        | `WIN32_NT`         |
| `AB1C`           | `AB1_C`            |
| `XMLHttpRequest` | `XML_HTTP_REQUEST` |
| `Playful`        | `PLAYFUL`          |

Members with no acronym run convert exactly as before, so no existing binding changes name.

**Collisions.** When two or more members of one enum convert to the same entry (`HTTPStatus` and
`HttpStatus` both give `HTTP_STATUS`), every one of them keeps its C# name verbatim as the Kotlin
entry, and each gets one `info_enum_entry_kept_verbatim` build note. A verbatim name cannot clash
with another entry: C# member names are unique, a converted name has no lowercase letter, and an
all-uppercase verbatim name converts to itself, so it would already be in the colliding set. The
enum still binds in full: unlike an overload set (ADR-072, ADR-155), there is no dispatch to make
ambiguous, and dropping the enum would skip every member typed with it. The diagnostic code follows
the reverse plugin's current lowercase spelling; the ADR-182 code rename uppercases it with the rest.

**Scope.** Generated surface only (`nuget-plugin`, `NugetGenerateBindingsTask`). Entries cross the
ABI as ordinals (`nugetEnumEntry(X.entries, ordinal, ...)`), and the collision pass keeps ordinal
order, so the contract hash, the C# shims and the runtime are unchanged. Reverse is experimental
(ADR-181), so the rename carries no deprecation alias; an alias entry would also shift `entries`.
C# method and property names (`URLPath` binding as `uRLPath`) are a separate item.

Fixtures: `TestDependency/VetTriage.cs`, `test-library/.../test/enums/VetTriageSample.kt`; consumer
test `IntegrationTests/ReverseEnumNamingTests.cs`.
