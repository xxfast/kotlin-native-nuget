package io.github.xxfast.kotlin.native.nuget.processor.cir

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Issue #223: the structural guard that no identifier containing `$` is ever rendered.
 *
 * The defect it stands for came in through one route (the ADR-134 nested-declaration walk reaching
 * kotlinx.serialization's synthesized `$serializer`), and the walk refuses that declaration now.
 * This asserts the rule itself rather than that route, because `$` is a name no C# compiler can
 * read wherever it comes from, and six CS1056-family errors per site in a consumer's build is a
 * terrible way to find out.
 *
 * The harness cannot grow a real `$serializer`: Tier 1 runs K2 without the serialization plugin.
 * `:test-library` carries the real cell, through `:test-models`' `@Serializable Carton`.
 */
class CirDollarIdentifierGuardTest {

  @Test
  fun `a nested declaration named with a dollar fails the render, naming the identifier`() {
    val owner = CirClass(
      name = "Carton",
      libraryName = "models",
      nativePrefix = "carton",
      constructor = null,
      properties = emptyList(),
      methods = emptyList(),
      nestedDeclarations = listOf(
        CirObject(
          name = "\$serializer",
          libraryName = "models",
          nativePrefix = "carton_serializer",
          methods = emptyList(),
        ),
      ),
    )

    val failure = assertFailsWith<IllegalStateException> {
      CirRenderer().render(CirFile(namespaces = listOf(CirNamespace("Sample", listOf(owner)))))
    }

    assertContains(failure.message.orEmpty(), "\$serializer")
    assertContains(failure.message.orEmpty(), "generator defect")
  }

  /**
   * The guard reads declarations, not raw text. `CirRuntimeHelper` renders two interpolated
   * strings (`$"[nuget:interop] runtime ..."`), which is the one place `$` is legal C#, so a
   * naive `contains('$')` would fail every library ADR-129 touches, which is all of them.
   */
  @Test
  fun `an interpolated string is not an identifier and renders normally`() {
    val rendered: String = CirRenderer().render(
      CirFile(namespaces = listOf(CirNamespace("Sample", listOf(CirRuntimeHelper("models"))))),
    )

    assertTrue(
      rendered.contains("\$\"[nuget:interop]"),
      "expected the runtime helper's interpolated strings to survive; rendered=$rendered",
    )
  }
}
