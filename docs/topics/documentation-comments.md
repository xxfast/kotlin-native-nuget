# Documentation comments

KDoc on an exported Kotlin declaration becomes an XML doc comment (`///`) on the matching
generated C# declaration, so a bound member's `<summary>`, `<param>`, `<returns>`, and
`<exception>` show up in the consumer's own IDE. Nothing needs to be written differently in Kotlin
to get this.

```kotlin
class BoardingDesk(private val cat: String) {
  /**
   * Books a stay for the cat.
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
```

```C#
/// <summary>Books a stay for the cat.</summary>
/// <param name="nights">how many nights</param>
/// <param name="suite">which suite, defaults to the sunny one</param>
/// <returns>the booking reference</returns>
/// <exception cref="KotlinArgumentException">when nights is not positive</exception>
public string Book(int nights, string suite)

/// <summary>Books a stay for the cat.</summary>
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
| body, first paragraph | `<summary>`; later paragraphs are dropped |
| `@param name text` | `<param name="name">text</param>` |
| `@return text` | `<returns>text</returns>`, omitted on a `void` member; the same text on a `suspend` function's `Async` projection |
| `@throws Type text` / `@exception Type text` | `<exception cref="...">text</exception>`, spelled from [the exception table](exceptions.md#catching-a-specific-exception-type); an unmapped type crefs `KotlinException` and keeps the Kotlin type name as a plain-text prefix (`RuntimeException: text`) |
| `@suppress`, anywhere in the comment | no doc comment on that declaration at all |
| everything else (`[links]`, `` `code` ``, `@property`, `@constructor`, `@see`, `@sample`, `@since`) | dropped |

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

## `@suppress` opts a declaration out

```kotlin
/** @suppress */
fun secretTreat(): String = "$cat gets a churu"
```

`SecretTreat()` still generates and works exactly as before; it just has no `///` block at all.
`@suppress` anywhere in the comment drops the whole thing, including any `@param`/`@return` on the
same declaration.

## What doesn't carry a doc comment

- **A dependency (klib) declaration renders undocumented.** KSP reads no KDoc from compiled
  metadata, so a type reached only through
  [a dependency module](nuget-dsl.md#cross-module-export-closure) carries no comment in the
  generated C#, however it was documented at its own source.
- **Only the first paragraph survives.** A later paragraph, a `` `code span` ``, a `[link]`,
  `@property`, `@constructor`, and `@see` are all dropped rather than rendered as something
  misleading; keep the summary line self-contained.
- A Kotlin `object`'s own property has no C# surface at all (unrelated to documentation, see
  [ROADMAP.md](https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md)), so there's
  nothing generated for its KDoc to attach to either.

## `expect`/`actual` {id="expectactual"}

KDoc written on an `expect` declaration reaches the generated C#, for every family the
[list above](#what-carries-a-doc-comment) covers — not only a class, as below, but also a member
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
and changes no ABI: it costs nothing at the crossing, only at compile time.

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
