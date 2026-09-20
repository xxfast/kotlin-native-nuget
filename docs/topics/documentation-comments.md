# Documentation comments

KDoc on an exported Kotlin declaration becomes an XML doc comment (`///`) on the matching
generated C# declaration, so a bound member's `<summary>`, `<remarks>`, `<param>`, `<returns>`,
and `<exception>` show up in the consumer's own IDE. Nothing needs to be written differently in
Kotlin to get this.

````kotlin
class BoardingDesk(private val cat: String) {
  /**
   * Books a stay for the cat.
   *
   * Ask for [nights] up front; the cat sleeps through a [Snooze] either way.
   *
   * The reference reads `Pair<Oreo & Mylo>` when both cats check in.
   *
   * ```kotlin
   * val reference = desk.book(nights = 2) // Oreo & Mylo <both>
   * ```
   *
   * @param nights how many nights
   * @param suite which suite, defaults to the sunny one
   * @return the booking reference
   * @throws IllegalArgumentException when nights is not positive
   */
  fun book(nights: Int, suite: String = "sunny"): String {
    require(nights > 0) { "nights must be positive" }
    return "$cat/$suite/$nights"
  }
}

sealed class Snooze
````

```C#
/// <summary>Books a stay for the cat.</summary>
/// <remarks>
/// <para>Ask for <c>nights</c> up front; the cat sleeps through a <see cref="global::TestLibrary.Kdoc.Snooze"/> either way.</para>
/// <para>The reference reads <c>Pair&lt;Oreo &amp; Mylo&gt;</c> when both cats check in.</para>
/// <code>
/// val reference = desk.book(nights = 2) // Oreo &amp; Mylo &lt;both&gt;
/// </code>
/// </remarks>
/// <param name="nights">how many nights</param>
/// <param name="suite">which suite, defaults to the sunny one</param>
/// <returns>the booking reference</returns>
/// <exception cref="KotlinArgumentException">when nights is not positive</exception>
public string Book(int nights, string suite)

/// <summary>Books a stay for the cat.</summary>
/// <remarks>
/// <para>Ask for <c>nights</c> up front; the cat sleeps through a <see cref="global::TestLibrary.Kdoc.Snooze"/> either way.</para>
/// <para>The reference reads <c>Pair&lt;Oreo &amp; Mylo&gt;</c> when both cats check in.</para>
/// <code>
/// val reference = desk.book(nights = 2) // Oreo &amp; Mylo &lt;both&gt;
/// </code>
/// </remarks>
/// <param name="nights">how many nights</param>
/// <returns>the booking reference</returns>
/// <exception cref="KotlinArgumentException">when nights is not positive</exception>
public string Book(int nights)
```

The second overload above is [the omitting overload](top-level-declarations.md#function-default-parameters)
`suite`'s default synthesizes; it keeps the doc but drops the `<param>` it has no parameter for.

## What carries a doc comment {id="what-carries-a-doc-comment"}

Every generated declaration that mirrors a documented Kotlin one: a class, object, interface (and
its members), enum (and a documented entry), sealed base and arm, value class, constructor
(including a secondary one), method, property, extension function, top-level function, and the
`Async` projection of a `suspend` function (see [Coroutines and Flow](coroutines-and-flow.md)).

## Tag mapping

| KDoc | C# |
|---|---|
| body, first paragraph | `<summary>` |
| a later paragraph | a `<para>` inside one `<remarks>` element (see below) |
| `` `code` `` | `<c>code</c>`, escaped |
| a fenced code block | a `<code>` element: a child of `<remarks>`, or of `<summary>` when the fence sits in the first paragraph; the language tag (`` ```kotlin ``) is discarded |
| `[Type]` | `<see cref="global::...">` when `Type` is a non-generic type this generated file itself declares and the name is unambiguous; otherwise `<c>Type</c>` with the author's own spelling |
| `[label][Type]` | as above, with the C# `<see>`/`<c>` carrying `label` instead of `Type` |
| `@param name text` | `<param name="name">text</param>` |
| `@return text` | `<returns>text</returns>`, omitted on a `void` member; the same text on a `suspend` function's `Async` projection |
| `@throws Type text` / `@exception Type text` | `<exception cref="...">text</exception>`, spelled from [the exception table](exceptions.md#catching-a-specific-exception-type); an unmapped type crefs `KotlinException` and keeps the Kotlin type name as a plain-text prefix (`Type: text`) |
| `@property name text` | the `<summary>` of the C# property `Name`, only when that property has no KDoc of its own; also the `<param>` text of a same-named primary-constructor parameter, when the class has no `@param` for it |
| `@constructor text` | the `<summary>` of the primary constructor |
| `@see Target` | `<seealso cref="global::...">` when `Target` resolves the same way `[Type]` does; otherwise a closing `<para>See also: <c>Target</c></para>` in `<remarks>` |
| `@suppress`, on its own line anywhere in the comment | no doc comment on that declaration at all |
| `[member]`, `[parameter]`, a member/parameter `@see`, a generic type link | `<c>` with the author's own spelling; none of these has a `cref` the bridge can spell |
| `@sample`, `@since`, `@author`, `@receiver` | dropped |

A tag section (`@param`, `@throws`, …) runs through any blank lines up to the next tag; the whole
section is one string, joined with spaces.

## `<remarks>`: everything after the summary, in order

Every later paragraph, every fenced block outside a tag, and an unresolved `@see` all land in one
`<remarks>` element, never more than one. `Rehome` below shows three behaviors at once: a `@param`
section that runs through a blank line, an unmapped `@throws`, and both halves of `@see` (one
resolves, one doesn't):

```kotlin
/**
 * Rehomes the cat.
 *
 * @param home where the cat goes
 *
 * once the sunbeam moves
 * @throws RuntimeException when the boarding desk is closed
 * @see SunSpot
 * @see book
 */
fun rehome(home: String): String
```

```C#
/// <summary>Rehomes the cat.</summary>
/// <remarks>
/// <para>See also: <c>book</c></para>
/// </remarks>
/// <param name="home">where the cat goes once the sunbeam moves</param>
/// <exception cref="KotlinException">RuntimeException: when the boarding desk is closed</exception>
/// <seealso cref="global::TestLibrary.Kdoc.SunSpot"/>
public string Rehome(string home)
```

`home`'s text includes "once the sunbeam moves" from *after* the blank line, because a blank line
inside a tag section continues that section rather than starting a body paragraph; the section
ends only at the next tag. `RuntimeException` isn't in [the exception table](exceptions.md), so it
crefs `KotlinException` and keeps its own name as a plain-text prefix. `@see SunSpot` resolves to a
type this file declares, so it becomes a navigable `<seealso cref>`; `@see book` names a member,
which has no `cref` spelling the bridge can produce, so it closes `<remarks>` as prose instead.

## Every parameter gets a tag, or none do

C# requires either every parameter of a member to carry a `<param>` tag or none of them to
(otherwise an IDE, and this project's own build, sees a partial set as a defect, `CS1573`). A
partially-documented member still gets a tag for the parameter it didn't document, just an empty
one:

```kotlin
/**
 * Grooms the cat.
 *
 * @param brush which brush to use
 */
fun groom(brush: String, gentle: Boolean): String = /* ... */
```

```C#
/// <summary>Grooms the cat.</summary>
/// <param name="brush">which brush to use</param>
/// <param name="gentle"></param>
public string Groom(string brush, bool gentle)
```

The same rule covers the `cancellationToken` parameter the generator adds to a `suspend` function's
`Async` projection: there's no Kotlin `@param` for it, so it gets an empty tag too, next to the
Kotlin parameters that do have one:

```C#
/// <summary>Waits for the cat to settle.</summary>
/// <param name="minutes">how long to wait</param>
/// <param name="cancellationToken"></param>
/// <returns>what the cat did</returns>
public Task<string> SettleAsync(int minutes, CancellationToken cancellationToken = default)
```

## `@property` and `@constructor` document a different member than the one they sit on

A primary-constructor property (`class Bowl(val flavour: String)`) and the primary constructor
itself carry no KDoc of their own in Kotlin: `@property` and `@constructor` on the class comment
are the only way to document them.

```kotlin
/**
 * The bowl Mylo empties in one sitting.
 *
 * @property flavour what Mylo is eating
 * @property rinsed whether the bowl was hosed down first
 * @constructor Fills the bowl for one sitting.
 * @param scoops how many scoops went in
 */
class KdocFoodBowl(val flavour: String, val scoops: Int) {

  /** Whether Oreo licked it clean first. */
  val rinsed: Boolean get() = scoops > 1
}
```

```C#
/// <summary>The bowl Mylo empties in one sitting.</summary>
public class KdocFoodBowl : IDisposable, INugetHandle
{
    /// <summary>Fills the bowl for one sitting.</summary>
    /// <param name="flavour">what Mylo is eating</param>
    /// <param name="scoops">how many scoops went in</param>
    public KdocFoodBowl(string flavour, int scoops)

    /// <summary>what Mylo is eating</summary>
    public string Flavour { get; }

    public int Scoops { get; }

    /// <summary>Whether Oreo licked it clean first.</summary>
    public bool Rinsed { get; }
}
```

`Flavour` takes its `<summary>` from `@property flavour`, and the same text becomes the
constructor's `<param name="flavour">`, since the class has no `@param flavour` of its own.
`scoops` has a class-level `@param` instead of a `@property`, so it documents the constructor
parameter only: `Scoops` the property gets no doc comment at all. A property with its own KDoc
(`rinsed`) always keeps it, even when the class also has a `@property rinsed` tag for it.

## `@suppress` opts a declaration out

```kotlin
/** @suppress */
fun secretTreat(): String = "$cat gets a churu"
```

`SecretTreat()` still generates and works exactly as before; it just has no `///` block at all.
`@suppress` on its own line anywhere in the comment drops the whole thing, including any
`@param`/`@return` on the same declaration.

## What doesn't carry a doc comment, or only partly does

- **A dependency (klib) declaration renders undocumented.** KSP reads no KDoc from compiled
  metadata, so a type reached only through
  [a dependency module](nuget-dsl.md#cross-module-export-closure) carries no comment in the
  generated C#, however it was documented at its own source.
- **A member link, a parameter link, and a link to a generic type stay `<c>` with the Kotlin
  spelling.** `[book]`, `[nights]`, and `[Crate]` (a generic class) never become a `cref`: a member
  needs an overload-qualified spelling the bridge doesn't produce yet, and a bare cref to a generic
  type doesn't resolve.
- **`[label](url)` is left as literal prose**, not a `<see href>`.

## `expect`/`actual` {id="expectactual"}

KDoc written on an `expect` declaration reaches the generated C#, for every family the
[list above](#what-carries-a-doc-comment) covers: not only a class, as below, but also a member
of an `expect class`, a top-level `expect val`, an `expect object`/`interface`/`sealed
class`/`value class`, and an enum entry. If the `actual` itself carries its own KDoc, that wins
instead; most authors write the one shared doc on the `expect` and leave the `actual` bare, which
is why the example below documents only the `expect` side.

```kotlin
/** Oreo's sunny window perch. */
expect class SunSpot {
  fun sunbeam(): String
}
```

```C#
/// <summary>Oreo's sunny window perch.</summary>
public class SunSpot : IDisposable, INugetHandle
```

See [expect/actual declarations](expect-actual.md) for how the two sides otherwise relate.

## No separate documentation file to pack

The generated shim ships as source (see [Publishing Kotlin to C#](forward-overview.md)), so there
is no `lib/<tfm>/*.xml` documentation file in the package: turn on
`<GenerateDocumentationFile>true</GenerateDocumentationFile>` in the *consuming* project and these
entries show up in its own IntelliSense. A malformed comment (an unresolvable `cref`, a partial
`<param>` set, mismatched tags) would already have failed the library's own build before the
package was published, so nothing here can reach a consumer broken. This mapping mints no handle
and changes no ABI: it costs nothing at the crossing, only at compile time. Keep the first
paragraph self-contained: it is the whole `<summary>`, and everything after it lands in
`<remarks>` instead.

<seealso>
    <category ref="related">
        <a href="forward-overview.md">Publishing Kotlin to C#</a>
        <a href="classes-and-objects.md">Classes and objects</a>
        <a href="exceptions.md">Exceptions</a>
        <a href="expect-actual.md">expect/actual declarations</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/150-kdoc-to-csharp-xml-docs.md">ADR-150: KDoc on an exported Kotlin declaration becomes an XML doc comment on its C# declaration</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
    </category>
</seealso>
