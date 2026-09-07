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
   * Structural. Two `expect` overloads share one qualified name, so the index has to resolve them
   * by signature: only the second namesake carries a trailing default, and the omitting overload
   * must wrap *its* parameters. Attributing the wrong declaration's defaults would truncate
   * `beam(name, tag)` into a `beam(name)` whose Kotlin call site does not compile.
   */
  @Test
  fun `overloaded top-level expects each resolve their own defaults`() {
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
    // Exactly one synthesized overload, and it truncates the *second* namesake (`level`), not the
    // first (`tag`), whose parameters carry no default at all.
    assertContains(kotlin, "@CName(\"beam_3\")")
    assertFalse(
      kotlin.contains("beam_4"),
      "only the defaulted namesake may synthesize; generated=$kotlin",
    )
    assertContains(kotlin, "beam(name)")
  }

  /**
   * Structural. ADR-074 Decision 3 names the C# static class after the `expect`'s file, so two
   * `expect` overloads declared in *different* files of one package must each name their own file
   * rather than collapse onto whichever declaration the index happened to keep. The second
   * overload's nullable and generic parameter types are what the signature match has to render
   * structurally rather than by identity, since the pair is two separate `KSType` instances.
   */
  @Test
  fun `overloaded expects in different files each name their own file`() {
    val result = Tier1Harness.run(
      commonSources = mapOf(
        "Purr.kt" to """
        package tier1.fundefaultsexpectfiles

        expect fun purr(name: String): String
        """.trimIndent(),
        "PurrLoud.kt" to """
        package tier1.fundefaultsexpectfiles

        expect fun purr(name: String?, volumes: List<String>): String
        """.trimIndent(),
      ),
      sources = mapOf(
        "PurrActual.kt" to """
        package tier1.fundefaultsexpectfiles

        actual fun purr(name: String): String = "${'$'}name"
        actual fun purr(name: String?, volumes: List<String>): String =
          "${'$'}name@${'$'}{volumes.size}"
        """.trimIndent(),
      ),
    )

    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    val cs: String = result.generatedCSharp
    // Pre-fix only one of the two can exist: a name-keyed index keeps one `expect` per name, so
    // both actuals land in whichever file survived.
    assertContains(cs, "partial class Purr\n")
    assertContains(cs, "partial class PurrLoud\n")
    assertFalse(
      cs.contains("class PurrActual"),
      "an actual must never name its own per-target file; generated=$cs",
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
