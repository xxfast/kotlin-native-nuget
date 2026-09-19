package io.github.xxfast.kotlin.native.nuget.processor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one "is this package under that prefix" test, shared by admission (`PackageScope.covers` /
 * `.excludes`, `NugetProcessor.isExported`) and naming (`mapPackageToNamespace`). It is
 * segment-bounded: the two used to be hand-copied, and an unbounded `startsWith` on the naming
 * side once built a namespace out of half a package segment that no admission decision agreed
 * with (ADR-066 section 5 amendment).
 */
class PackageNamesTest {

  @Test
  fun `a sub-package is under its parent`() {
    assertTrue(isUnderPackage("a.b", "a"))
    assertTrue(isUnderPackage("a.b.c", "a"))
  }

  @Test
  fun `a package is under itself`() {
    assertTrue(isUnderPackage("a.b", "a.b"))
  }

  @Test
  fun `a package sharing only a partial segment is not under the prefix`() {
    assertFalse(isUnderPackage("a.bc", "a.b"))
    assertFalse(isUnderPackage("ab", "a"))
  }

  @Test
  fun `a parent is not under its own sub-package`() {
    assertFalse(isUnderPackage("a", "a.b"))
  }
}
