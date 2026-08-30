package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-096: function default parameters surface as ADR-091's trailing-omitting overloads on the five
 * function routes.
 *
 * The end-to-end half lives in `whiskers/WhiskersSample.kt` / `FunctionDefaultParameterTests.cs`,
 * which arbitrates numbering, dispatch and the resulting C# overload sets. What is only reachable
 * here is the shape whose correct outcome is a **failed** generation (it can never ship in the
 * fixture library) and the `override` exclusion, whose evidence is the *absence* of an export.
 */
class Tier1FunctionDefaultParameterTest {

  /**
   * Diagnostic. A synthesized overload that collides with a declared namesake must fail generation
   * with the ADR-034 kind rather than emit CS0111 C#.
   */
  @Test
  fun `a synthesized overload colliding with a declared method fires ERROR_CSHARP_SIGNATURE_COLLISION`() {
    val result = Tier1Harness.run(
      """
      package tier1.fundefaultscollision

      class Sitter(val name: String) {
        fun describe(p: String): String = "${'$'}name ${'$'}p"
        fun describe(p: String, excited: Boolean = false): String = "${'$'}name ${'$'}p ${'$'}excited"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
      },
      "expected the synthesized-overload collision to fail generation; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.any { it.contains("remove the default value whose synthesized") },
      "expected the hint to name the defaulted-parameter cause; kspErrors=${result.kspErrors}",
    )
  }

  /**
   * Structural. The two routes whose emitters walk declarations rather than the catalog: a
   * synthesized entry shares its declaration's node, so both halves only see it once `plansFor` is
   * plural. The extension receiver is not a plan parameter, so truncating every parameter leaves it
   * intact.
   */
  @Test
  fun `top-level and extension defaults synthesize omitting overloads on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.fundefaultsstatic

      class Mitten(val name: String)

      fun hail(name: String, loud: Boolean = false): String = if (loud) name else "hi ${'$'}name"

      fun Mitten.knead(times: Int = 2, surface: String = "blanket"): String =
        "${'$'}name ${'$'}times ${'$'}surface"
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"hail\")")
    assertContains(kotlin, "@CName(\"hail_2\")")
    assertContains(kotlin, "@CName(\"mitten_knead\")")
    assertContains(kotlin, "@CName(\"mitten_knead_2\")")
    assertContains(kotlin, "@CName(\"mitten_knead_3\")")
    // The load-bearing assertion: the truncated plans call Kotlin with fewer positional arguments,
    // and the extension keeps its receiver even when every parameter is dropped.
    assertContains(kotlin, "hail(name)")
    assertContains(kotlin, ".get().knead()")

    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"hail_2\"")
    assertContains(cs, "EntryPoint = \"mitten_knead_3\"")
    assertContains(cs, "public static string Knead(this global::Interop.Mitten receiver)")
  }

  /**
   * Structural. The expect index is a `.toMap()` keyed by qualified name, so two `expect` overloads
   * of one name collapse to the last one. The uniqueness guard is what stops the survivor's
   * defaults being attributed to its namesake: without it `beam(name, tag)` would grow an omitting
   * `beam(name)` whose Kotlin call site does not compile.
   */
  @Test
  fun `a top-level expect with a declared namesake consults no defaults`() {
    val result = Tier1Harness.run(
      commonSources = mapOf(
        "Beam.kt" to """
        package tier1.fundefaultsexpectoverload

        expect fun beam(name: String, tag: String): String
        expect fun beam(name: String, level: Int = 3): String
        """.trimIndent(),
      ),
      sources = mapOf(
        "BeamActual.kt" to """
        package tier1.fundefaultsexpectoverload

        actual fun beam(name: String, tag: String): String = "${'$'}name/${'$'}tag"
        actual fun beam(name: String, level: Int): String = "${'$'}name#${'$'}level"
        """.trimIndent(),
      ),
    )

    // The harness compiles common and platform sources as one module, so an expect/actual pair
    // never compiles cleanly here (ADR-091's cell has the same shape); KSP still runs.
    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"beam\")")
    assertContains(kotlin, "@CName(\"beam_2\")")
    assertFalse(
      kotlin.contains("beam_3"),
      "an ambiguous expect key must synthesize nothing on either namesake; generated=$kotlin",
    )
  }

  /**
   * Structural. Kotlin forbids an override from restating a default, and a synthesized entry on the
   * derived class would be `override` against a base signature that does not exist (CS0115), so the
   * derived class synthesizes nothing and inherits the base's omitting overload instead.
   */
  @Test
  fun `an override synthesizes nothing while its base still does`() {
    val result = Tier1Harness.run(
      """
      package tier1.fundefaultsoverride

      open class Animal(val name: String) {
        open fun speak(times: Int, loud: Boolean = false): String = "${'$'}name ${'$'}times ${'$'}loud"
      }

      class Cat(name: String) : Animal(name) {
        override fun speak(times: Int, loud: Boolean): String = "${'$'}name meows ${'$'}times ${'$'}loud"
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"animal_speak\")")
    assertContains(kotlin, "@CName(\"animal_speak_2\")")
    assertContains(kotlin, "@CName(\"cat_speak\")")
    assertFalse(
      kotlin.contains("cat_speak_2"),
      "an override synthesizes nothing (it inherits the base's overload); generated=$kotlin",
    )
  }
}
