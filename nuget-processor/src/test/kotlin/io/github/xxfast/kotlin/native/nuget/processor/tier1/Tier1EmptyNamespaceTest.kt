package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An empty or blank `nuget.namespace` option means the same as an absent one: the processor's
 * `Interop` default. Before this, an explicitly empty option (what the plugin passed for a
 * `publish {}` without `packageId`) rendered a bare `namespace ` header and `global::.WireCage`,
 * and a blank one rendered `global::  .WireCage`. Tier 1 compiles only the Kotlin half, so
 * `compiledClean` alone never caught it; the assertions below read the C# text.
 */
class Tier1EmptyNamespaceTest {
  private val source: String = """
    package tier1.cage

    class WireCage(val size: Int) { class Latch(val open: Boolean) }
    fun makeCage(size: Int): WireCage = WireCage(size)
  """.trimIndent()

  @Test
  fun `an empty or blank namespace falls back to Interop`() {
    listOf("", "  ").forEach { blank ->
      val result = Tier1Harness.run(
        source,
        processorOptions = mapOf("nuget.rootPackage" to "tier1.cage", "nuget.namespace" to blank),
      )
      assertTrue(result.compiledClean, "namespace '$blank': got ${result.compileErrors}")

      val csharp: String = result.generatedCSharp
      assertTrue(
        Regex("""(?m)^namespace Interop\s*$""").containsMatchIn(csharp),
        "namespace '$blank': expected a `namespace Interop` header; generatedCSharp:\n$csharp",
      )
      assertContains(csharp, "new global::Interop.WireCage(", message = "namespace '$blank'")
      assertContains(
        csharp,
        "public static global::Interop.WireCage MakeCage(",
        message = "namespace '$blank'",
      )
      assertFalse(
        Regex("""global::\s*\.""").containsMatchIn(csharp),
        "namespace '$blank': bare global:: qualifier; generatedCSharp:\n$csharp",
      )
      assertFalse(
        Regex("""(?m)^namespace\s*$""").containsMatchIn(csharp),
        "namespace '$blank': empty namespace header; generatedCSharp:\n$csharp",
      )
    }
  }
}
