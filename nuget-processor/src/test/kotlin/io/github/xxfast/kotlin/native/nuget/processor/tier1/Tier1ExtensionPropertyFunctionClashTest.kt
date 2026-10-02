package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-188: a C# 14 extension property and a classic extension method of one C# name on one
 * receiver both declare, but every `cat.NameOrStray` access is then ambiguous (CS9339). The
 * property is skipped on both halves with `SHADOWED_BY_EXTENSION_FUNCTION`, the function keeps the
 * name, and the hint names `@CSharpName` (ADR-179), which this test also proves keeps both.
 */
class Tier1ExtensionPropertyFunctionClashTest {

  private val sources: Map<String, String> = mapOf(
    "Cat.kt" to """
      package tier1.extclash

      import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

      class Cat(val name: String)

      fun Cat?.nameOrStray(): String = this?.name ?: "stray"
      val Cat?.nameOrStray: String get() = this?.name ?: "stray"

      fun Cat.homeLabel(): String = name
      @CSharpName("HomeTag")
      val Cat.homeLabel: String get() = name
    """.trimIndent(),
  )

  @Test
  fun `an extension property sharing a C# name with an extension function is a named skip`() {
    val result = Tier1Harness.run(sources, libraries = listOf(csharpNameLibrary))

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    // The function keeps the name on both halves.
    assertContains(csharp, "public static string NameOrStray(this global::Interop.Cat? receiver)")
    // The property is gone from both halves: no export, no import, no extension member.
    assertFalse(kotlin.contains("cat_get_nameOrStray"), "property must not export; generated=$kotlin")
    assertFalse(csharp.contains("cat_get_nameOrStray"), "property must not import; csharp=$csharp")
    assertFalse(csharp.contains("public string NameOrStray"), "no extension member; csharp=$csharp")

    val warning: List<String> =
      result.kspWarnings.filter { it.contains("SHADOWED_BY_EXTENSION_FUNCTION") }
    assertEquals(1, warning.size, "expected exactly one clash warning; kspWarnings=${result.kspWarnings}")
    assertContains(warning.single(), "SKIPPED_UNSUPPORTED_PROPERTY")
    assertContains(warning.single(), "the extension function `nameOrStray`")
    assertContains(warning.single(), "CS9339")
    assertContains(warning.single(), "@CSharpName")
  }

  @Test
  fun `a CSharpName on the extension property keeps both`() {
    val result = Tier1Harness.run(sources, libraries = listOf(csharpNameLibrary))

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public static string HomeLabel(this global::Interop.Cat receiver)")
    assertContains(csharp, "extension(global::Interop.Cat receiver)")
    assertContains(csharp, "public string HomeTag")
    assertFalse(
      result.kspWarnings.any { it.contains("homeLabel") },
      "a renamed property must not warn; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Two extension properties rendering one C# name into one class were fatal as two `GetTag`
   * methods; as two `public string Tag` extension members they must stay fatal, not reach the
   * consumer's compiler.
   */
  @Test
  fun `two extension properties of one C# name on one receiver stay a fatal collision`() {
    val result = Tier1Harness.run(
      mapOf(
        "Cat.kt" to """
          package tier1.extpropnames

          import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

          class Cat(val name: String)

          val Cat.tag: String get() = name
          @CSharpName("Tag")
          val Cat.label: String get() = name
        """.trimIndent(),
      ),
      libraries = listOf(csharpNameLibrary),
    )

    assertTrue(
      result.kspErrors.any { it.contains("ERROR_CSHARP_SIGNATURE_COLLISION") && it.contains("Tag") },
      "expected a fatal name collision; kspErrors=${result.kspErrors}",
    )
  }
}
