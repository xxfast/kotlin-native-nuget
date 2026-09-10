package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-126: a merged `{Receiver}Extensions` class for an *unexported* receiver (`String` here) lands
 * in the namespace of the package that declares the extension. Before ADR-126 the translator picked
 * `namespaceOf(funcs.first().packageName)`, so whichever extension KSP visited first dragged every
 * same-receiver extension in the library into its package's namespace, and the extension *property*
 * group picked again, independently, splitting one receiver across two classes.
 *
 * Both source maps below declare the same two packages; only the map's iteration order differs.
 * Whether that order actually reaches KSP's visit order is *inferred*, not proven, so the reversed
 * cell may be a tautology on some KSP build. It is kept because it is free and states the intent:
 * neither package's extensions may relocate the other's class.
 */
class Tier1ExtensionNamespaceTest {

  private val root: Pair<String, String> = "Root.kt" to """
    package tier1.ext

    fun String.meowify(): String = "${'$'}this meow!"
    val String.wordCount: Int get() = split(" ").size
  """.trimIndent()

  private val nested: Pair<String, String> = "Nested.kt" to """
    package tier1.ext.reserved

    fun String.tag(other: String): String = "${'$'}this:${'$'}other"
  """.trimIndent()

  private fun run(sources: Map<String, String>): String {
    val result = Tier1Harness.run(
      sources,
      processorOptions = mapOf(
        "nuget.rootPackage" to "tier1.ext",
        "nuget.namespace" to "Ext",
      ),
    )
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    return result.generatedCSharp
  }

  /**
   * The body of one `namespace X { ... }` block: from its header to the next top-level `namespace`
   * header, which is how the renderer separates them.
   */
  private fun namespaceBlock(cs: String, namespace: String): String {
    val header: String = "namespace $namespace\n"
    assertContains(cs, header)
    return cs.substringAfter(header).substringBefore("\nnamespace ")
  }

  @Test
  fun `extension class lands in the declaring package namespace in either visit order`() {
    for (sources in listOf(mapOf(root, nested), mapOf(nested, root))) {
      val cs: String = run(sources)
      val rootBlock: String = namespaceBlock(cs, "Ext")
      val nestedBlock: String = namespaceBlock(cs, "Ext.Reserved")

      assertContains(rootBlock, "public static string Meowify(")
      assertContains(nestedBlock, "public static string Tag(")

      // The partition, stated as an absence: neither package's extension joins the other's class.
      assertFalse(rootBlock.contains("Tag("), "root block leaked Tag: $rootBlock")
      assertFalse(nestedBlock.contains("Meowify("), "nested block leaked Meowify: $nestedBlock")
    }
  }

  @Test
  fun `extension functions and properties of one package share one class`() {
    for (sources in listOf(mapOf(root, nested), mapOf(nested, root))) {
      val cs: String = run(sources)
      val rootBlock: String = namespaceBlock(cs, "Ext")

      assertContains(rootBlock, "public static int GetWordCount(")
      assertEquals(
        1,
        Regex("class StringExtensions").findAll(rootBlock).count(),
        "expected exactly one StringExtensions class in `namespace Ext`: $rootBlock",
      )
      assertEquals(
        1,
        Regex("class StringExtensions").findAll(namespaceBlock(cs, "Ext.Reserved")).count(),
        "expected exactly one StringExtensions class in `namespace Ext.Reserved`",
      )
    }
  }
}
