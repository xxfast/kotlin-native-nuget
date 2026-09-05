package io.github.xxfast.kotlin.native.nuget.processor.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-109 Decision 2/3: the `nuget.publishedScopes` wire format the plugin writes and this
 * processor reads — entries `;`, fields `:`, lists `|`,
 * `<packageId>:<include1|include2>:<exclude1|exclude2>` — parsed here rather than asserted only
 * through a full KSP round, because a malformed entry is a named *error* and a harness crash is
 * not a readable assertion.
 */
class ForwardPublishedScopeTest {

  @Test
  fun `entries parse into package scopes with this module's own entry dropped`() {
    val scopes: List<PublishedScope> = parsePublishedScopes(
      "Lib:tier1.dup|dep.models:;OtherLib:dep.models:dep.models.internal",
      selfPackageId = "Lib",
    )

    assertEquals(listOf("OtherLib"), scopes.map { it.packageId })
    assertEquals(listOf("dep.models"), scopes.single().scope.include)
    assertEquals(listOf("dep.models.internal"), scopes.single().scope.exclude)
  }

  @Test
  fun `an absent or blank option parses to nothing`() {
    assertTrue(parsePublishedScopes(null, selfPackageId = "Lib").isEmpty())
    assertTrue(parsePublishedScopes("", selfPackageId = "Lib").isEmpty())
  }

  /** ADR-109's documented gap: a publisher with neither `rootPackage` nor `include` exports "all
   *  its own files", which cannot be lowered to packages, so it can never match and stays silent
   *  rather than warning about everything. */
  @Test
  fun `a publisher with an empty include covers nothing`() {
    val scope: PublishedScope =
      parsePublishedScopes("OtherLib::", selfPackageId = "Lib").single()

    assertFalse(scope.covers("dep.models", "dep.models.TopStory"))
  }

  /** A publisher with a blank packageId cannot be named in the hint, so it is dropped too. */
  @Test
  fun `an entry with no packageId is dropped`() {
    assertTrue(parsePublishedScopes(":dep.models:", selfPackageId = "Lib").isEmpty())
  }

  @Test
  fun `covers matches by package prefix, and the publisher's own exclude wins`() {
    val scope: PublishedScope =
      parsePublishedScopes("OtherLib:dep:dep.internal|dep.models.Draft", selfPackageId = "Lib")
        .single()

    assertTrue(scope.covers("dep.models", "dep.models.TopStory"))
    assertFalse(scope.covers("dep.internal", "dep.internal.Cache"))
    // Issue #53: exclude also accepts a qualified declaration name.
    assertFalse(scope.covers("dep.models", "dep.models.Draft"))
    assertFalse(scope.covers("other.models", "other.models.TopStory"))
  }

  @Test
  fun `a malformed entry fails with a named error rather than being guessed at`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      parsePublishedScopes("OtherLib:dep.models", selfPackageId = "Lib")
    }

    assertTrue("nuget.publishedScopes" in failure.message.orEmpty(), failure.message.orEmpty())
    assertTrue("OtherLib:dep.models" in failure.message.orEmpty(), failure.message.orEmpty())
  }
}
