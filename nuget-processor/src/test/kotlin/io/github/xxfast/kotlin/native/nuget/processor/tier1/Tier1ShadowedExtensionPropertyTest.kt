package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An extension property shadowed by a member property of the same name. The extension export's
 * body is `receiver.x`, and Kotlin resolves that to the MEMBER, so before this pin the C#
 * `FooExtensions.GetX()` silently returned the member's value. Kotlin call syntax cannot reach the
 * extension either, so the route skips it by name (`SHADOWED_BY_MEMBER`) on both halves.
 */
class Tier1ShadowedExtensionPropertyTest {

  private val sources: Map<String, String> = mapOf(
    "Foo.kt" to """
      package tier1.shadowed.model

      open class Base {
        val inherited: Int = 10
      }

      class Foo : Base() {
        val x: Int = 1
        private val hidden: Int = 3
        fun y(): Int = 1
      }
    """.trimIndent(),
    "Ext.kt" to """
      package tier1.shadowed.ext

      import tier1.shadowed.model.Foo

      val Foo.x: Int get() = 2
      val Foo.inherited: Int get() = 20
      val Foo.hidden: Int get() = 30
      fun Foo.y(): Int = 2
    """.trimIndent(),
    "Nullable.kt" to """
      package tier1.shadowed.nullable

      import tier1.shadowed.model.Foo

      val Foo?.x: Int get() = 4
    """.trimIndent(),
  )

  @Test
  fun `a member-shadowed extension property is a named skip with no export on either half`() {
    val result = Tier1Harness.run(sources)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    // The member keeps its own export.
    assertContains(kotlin, "@CName(\"library_tier1_shadowed_model__foo_get_x\")")
    // Declared and inherited shadowing members both drop the extension, on both halves.
    listOf("foo_get_x", "foo_get_inherited").forEach { symbol ->
      assertFalse(
        kotlin.contains("\"library_tier1_shadowed_ext__$symbol\""),
        "shadowed extension $symbol must not export; generated=$kotlin",
      )
      assertFalse(
        csharp.contains("EntryPoint = \"library_tier1_shadowed_ext__$symbol\""),
        "shadowed extension $symbol must not import; csharp=$csharp",
      )
    }
    val warnings: List<String> = result.kspWarnings.filter { it.contains("SHADOWED_BY_MEMBER") }
    val declaredShadowWarned: Boolean = warnings.any { it.contains("`Foo.x` shadows it") }
    val inheritedShadowWarned: Boolean = warnings.any { it.contains("`Base.inherited` shadows it") }
    assertTrue(
      declaredShadowWarned && inheritedShadowWarned,
      "expected a named shadow warning for both; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `an invisible member or a nullable receiver does not shadow`() {
    val result = Tier1Harness.run(sources)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    // A private member is not a candidate from the generated file, so the extension resolves.
    assertContains(result.generated, "@CName(\"library_tier1_shadowed_ext__foo_get_hidden\")")
    // `(receiver).x` on a `Foo?` has no member candidate, so `val Foo?.x` resolves to itself.
    assertContains(result.generated, "@CName(\"library_tier1_shadowed_nullable__foo_get_x\")")
  }

  /**
   * The extension FUNCTION twin is not skipped: it is called through an aliased import, which
   * reaches the extension past the member `Foo.y()` (see `Tier1ShadowedExtensionFunctionTest`).
   */
  @Test
  fun `a member-shadowed extension function exports and calls the extension`() {
    val result = Tier1Harness.run(sources)

    assertContains(result.generated, "@CName(\"library_tier1_shadowed_model__foo_y\")")
    assertContains(result.generated, "@CName(\"library_tier1_shadowed_ext__foo_y\")")
    assertContains(result.generated, ".get().nuget_ext_tier1__shadowed__ext__y()")
  }
}
