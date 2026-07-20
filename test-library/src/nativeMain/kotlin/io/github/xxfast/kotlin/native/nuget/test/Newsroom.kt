package io.github.xxfast.kotlin.native.nuget.test

import dev.other.core.Advertisement
import io.github.xxfast.kotlin.native.nuget.test.models.Byline
import io.github.xxfast.kotlin.native.nuget.test.models.StoryCode
import io.github.xxfast.kotlin.native.nuget.test.models.StoryUri
import io.github.xxfast.kotlin.native.nuget.test.models.TopStory
import io.github.xxfast.kotlin.native.nuget.test.models.Whisker
import io.github.xxfast.kotlin.native.nuget.test.models.catnip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ADR-066 fixture: the forward export set as a reachability closure from module roots.
 * `TopStory`/`Byline`/`StoryCode`/`StoryUri`/`Whisker`/`Purr` all live one Gradle module away, in
 * `:test-models` (the nuget plugin is not applied there), so `Newsroom` is the only thing KSP
 * sees directly; everything else must be discovered by walking from these members. `Advertisement`
 * lives in `:test-models` too but under `dev.other.core`, deliberately outside `rootPackage`, to
 * exercise the admission predicate's negative case. Oreo and Mylo, obviously, are the top story.
 */
class Newsroom {

  /** Reachable via a plain return type. */
  fun latest(): TopStory = TopStory("Oreo escapes the cardboard box (again)", 1, Byline("Mylo"))

  /**
   * Reachable ONLY via a type argument. The `Flow<T>` element-type route maps its element by
   * simple name at five call sites (verified in ADR-066), so this is the qualified-vs-unqualified
   * regression guard: `TopStory` alone does not resolve inside the `TestLibrary` namespace, only
   * `TestLibrary.Models.TopStory` does.
   */
  fun stream(): Flow<TopStory> = flow {
    emit(TopStory("Oreo escapes the cardboard box (again)", 1, Byline("Mylo")))
    emit(TopStory("Mylo naps in a sunbeam for six hours straight", 2, null))
  }

  /** Reachable via a collection type argument. */
  fun archive(): List<TopStory> = listOf(
    TopStory("Oreo escapes the cardboard box (again)", 1, Byline("Mylo")),
    TopStory("Mylo naps in a sunbeam for six hours straight", 2, null),
  )

  /**
   * Cross-module value class guard (ADR-066): a klib reports `Modifier.INLINE`, not
   * `Modifier.VALUE`, for this declaration. Must bind as an unwrapped value, never as an
   * `IDisposable` handle.
   */
  fun code(): StoryCode = StoryCode("BREAKING-001")

  /**
   * `SKIPPED_INHERITED_MEMBER` guard (the ADR-064 amendment ADR-066 forces): the delegated
   * `CharSequence` members (`Get`, `SubSequence`, `Length`) must be filtered out, while the
   * author-declared `Shout()` must survive.
   */
  fun uri(): StoryUri = StoryUri("cats.news/oreo-escapes")

  /**
   * Admission-predicate guard: `Advertisement` is reachable (this method returns it) but its
   * package, `dev.other.core`, is outside `rootPackage`. Must skip with
   * `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` (naming the `include("dev.other.core")` fix), never bind
   * as a handle, and never take the rest of `Newsroom` down with it.
   */
  fun sponsor(): Advertisement = Advertisement("Fancy Feast")

  /**
   * Closure-termination guard: a cyclic pair across the module boundary (`Whisker.purr: Purr?`,
   * `Purr.whisker: Whisker?`). Must not hang or crash `packNuget`.
   */
  fun echo(): Whisker = catnip()
}
