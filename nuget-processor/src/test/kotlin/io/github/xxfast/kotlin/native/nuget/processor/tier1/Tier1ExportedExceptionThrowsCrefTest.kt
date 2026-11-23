package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-241 with ADR-150: a `@throws T` naming an exported exception class crefs the generated C#
 * type the consumer catches (nested arm or top-level), not the row it would map to. A stdlib row
 * and an unexported class keep their ADR-150/177 cref. Built with the XML documentation file, so a
 * cref that does not resolve is CS1574.
 *
 * Mylo writes the cupboard's warning label.
 */
class Tier1ExportedExceptionThrowsCrefTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.label

      sealed class StoreException(message: String) : Exception(message) {
        data class NotFound(val key: String) : StoreException("no ${'$'}key")
      }

      class StaleCache(message: String) : IllegalStateException(message)

      class Gnawed(message: String) : Exception(message)

      internal class Hidden(message: String) : Exception(message)

      class Cupboard {
        /**
         * Opens the cupboard.
         *
         * @throws StoreException.NotFound when the treat is gone
         * @throws StaleCache when the kibble is stale
         * @throws Gnawed when Oreo got there first
         * @throws IllegalArgumentException when the key is blank
         * @throws Hidden when Mylo hid it
         */
        fun open(key: String): String = key
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
  }

  @Test
  fun `a thrown exported class crefs its generated type`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "/// <exception cref=\"global::Interop.Label.StoreException.NotFound\">when the treat is gone</exception>",
    )
    assertContains(cs, "/// <exception cref=\"global::Interop.Label.StaleCache\">when the kibble is stale</exception>")
    // An exported class with no row: no `Gnawed: ` prefix, it IS the cref.
    assertContains(cs, "/// <exception cref=\"global::Interop.Label.Gnawed\">when Oreo got there first</exception>")
  }

  @Test
  fun `a stdlib row and an unexported class keep their cref`() {
    val cs: String = result.generatedCSharp
    assertContains(cs, "/// <exception cref=\"KotlinArgumentException\">when the key is blank</exception>")
    assertContains(cs, "/// <exception cref=\"KotlinException\">Hidden: when Mylo hid it</exception>")
  }

  @Test
  fun `every cref resolves under the documentation build`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      "namespace Consumer { public static class Probe { } }",
      documentationFile = true,
    )
  }
}
