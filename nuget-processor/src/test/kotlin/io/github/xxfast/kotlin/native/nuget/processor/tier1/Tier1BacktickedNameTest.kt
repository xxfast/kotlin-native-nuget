package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Backticked Kotlin names. A hard keyword (`in`, `object`) is a valid C# and C name already, but
 * the generated Kotlin spelled the call bare (`.get().in()`), which does not parse. A name with a
 * space or symbol (`tug hard`) has no C# or C spelling at all: it used to emit `Tug hard()`, an
 * `@CName("..._tug hard")` and an unbackticked call. It is now a named skip, unless `@CSharpName`
 * declares the C# name, in which case the entry point replaces each bad run with `_` and the
 * Kotlin call is backticked (an extension's import alias encodes the character instead).
 */
class Tier1BacktickedNameTest {

  private val identifier: Regex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

  private fun Tier1Result.assertValidSymbols() {
    Regex("""@CName\("([^"]*)"\)""").findAll(generated).forEach { match ->
      assertTrue(identifier.matches(match.groupValues[1]), "bad @CName ${match.value}")
    }
    Regex("EntryPoint = \"([^\"]*)\"").findAll(generatedCSharp).forEach { match ->
      assertTrue(identifier.matches(match.groupValues[1]), "bad EntryPoint ${match.value}")
    }
  }

  @Test
  fun `keyword names bind with backticked Kotlin calls`() {
    val result = Tier1Harness.run(
      """
      package tier1.backticks.keywords

      class Leash {
        fun `in`(): String = "member"
        fun `class`(): Int = 1
        val `object`: Int = 1
      }

      @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
      fun Leash.`in`(): String = "extension"
      fun Leash.`is`(): String = "extension"
      val Leash.`as`: Int get() = 1
      fun `object`(): Int = 1
      val `fun`: Int = 1
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    result.assertValidSymbols()
    val kotlin: String = result.generated
    assertContains(kotlin, ".get().`in`()")
    assertContains(kotlin, ".get().`class`()")
    assertContains(kotlin, ".get().`object`")
    assertContains(kotlin, ".get().`as`")
    assertContains(kotlin, "tier1.backticks.keywords.`object`()")
    // The alias is a plain identifier, so the extension call needs no backticks.
    assertContains(kotlin, ".get().nuget_ext_tier1__backticks__keywords__in()")
    assertContains(result.generatedCSharp, "public string In()")
    assertContains(result.generatedCSharp, "Is(this global::Interop.Leash receiver)")
  }

  @Test
  fun `a name with a space or symbol is a named skip without @CSharpName`() {
    val result = Tier1Harness.run(
      """
      package tier1.backticks.skipped

      class Leash {
        fun `tug hard`(): String = "member"
        val `slack line`: Int = 1
        fun walk(): String = "member"
      }

      @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
      fun Leash.`tug hard`(): String = "extension"
      fun Leash.`loose end`(): String = "extension"
      fun Leash.`a+b`(): String = "extension"
      fun `top level`(): Int = 1
      val `top prop`: Int = 1
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    result.assertValidSymbols()
    assertContains(result.generated, "@CName(\"library_tier1_backticks_skipped__leash_walk\")")
    listOf("tug hard", "slack line", "loose end", "a+b", "top level", "top prop").forEach { name ->
      assertFalse(result.generated.contains("$name("), "no call to `$name` may be emitted")
      assertTrue(
        result.kspWarnings.any { warning ->
          warning.contains("its name `$name` is not an identifier") &&
            warning.contains("@CSharpName")
        },
        "expected a named skip for `$name`; kspWarnings=${result.kspWarnings}",
      )
    }
    assertTrue(
      result.kspWarnings.any { warning ->
        warning.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION.name) &&
          warning.contains("tug hard")
      },
      "a callable skip reads as SKIPPED_UNSUPPORTED_COMBINATION; kspWarnings=${result.kspWarnings}",
    )
    Regex("""public [^(\n]*\(""").findAll(result.generatedCSharp).forEach { match ->
      assertFalse(match.value.contains(" hard") || match.value.contains("+"), match.value)
    }
  }

  @Test
  fun `a @CSharpName rescues a name with a space onto a sanitised entry point`() {
    val result = Tier1Harness.run(
      """
      package tier1.backticks.named

      import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

      class Leash {
        @CSharpName("TugHard")
        fun `tug hard`(): String = "member"
        @CSharpName("SlackLine")
        val `slack line`: Int = 1
      }

      @CSharpName("TugHard")
      @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
      fun Leash.`tug hard`(): String = "extension"
      @CSharpName("LooseEnd")
      fun Leash.`loose end`(): String = "extension"
      @CSharpName("TopLevel")
      fun `top level`(): Int = 1
      """.trimIndent(),
      libraries = listOf(csharpNameLibrary),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    result.assertValidSymbols()
    val named: String = "library_tier1_backticks_named__"
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"${named}leash_tug_hard\")")
    assertContains(kotlin, "@CName(\"${named}leash_get_slack_line\")")
    assertContains(kotlin, "@CName(\"${named}leash_ext_tug_hard\")")
    assertContains(kotlin, "@CName(\"${named}leash_loose_end\")")
    assertContains(kotlin, "@CName(\"${named}top_level\")")
    assertContains(kotlin, ".get().`tug hard`()")
    assertContains(kotlin, ".get().`slack line`")
    assertContains(kotlin, "tier1.backticks.named.`top level`()")
    // The space is encoded into the alias, so the extension call is a plain identifier.
    assertContains(kotlin, "as nuget_ext_tier1__backticks__named__tug_x0020hard")
    assertContains(kotlin, ".get().nuget_ext_tier1__backticks__named__tug_x0020hard()")
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public string TugHard()")
    assertContains(csharp, "public int SlackLine")
    assertContains(csharp, "TugHard(this global::Interop.Leash receiver)")
    assertContains(csharp, "LooseEnd(this global::Interop.Leash receiver)")
    assertContains(csharp, "public static int TopLevel()")
  }
}
