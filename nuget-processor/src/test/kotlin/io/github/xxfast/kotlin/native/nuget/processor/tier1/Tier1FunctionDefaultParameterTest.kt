package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-164 (superseding ADR-096's omitting overloads): function default parameters widen to their
 * nullable C# form on the five function routes, one signature per declaration.
 *
 * The end-to-end half lives in `whiskers/WhiskersSample.kt` / `FunctionDefaultParameterTests.cs`
 * and `issue297/Issue297Sample.kt` / `Issue297Tests.cs`.
 */
class Tier1FunctionDefaultParameterTest {

  /**
   * ADR-164: a widened `Describe(string, bool?)` is a different C# signature from a declared
   * `Describe(string)`, so the ADR-096 collision no longer fails generation.
   */
  @Test
  fun `a widened method beside a declared shorter namesake does not collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.fundefaultscollision

      class Sitter(val name: String) {
        fun describe(p: String): String = "${'$'}name ${'$'}p"
        fun describe(p: String, excited: Boolean = false): String = "${'$'}name ${'$'}p ${'$'}excited"
      }
      """.trimIndent(),
    )

    assertFalse(
      result.kspErrors.any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
      },
      "expected no collision; kspErrors=${result.kspErrors}",
    )
    assertContains(result.generatedCSharp, "public string Describe(string p, bool? excited = null)")
  }

  /**
   * Structural. The two routes whose emitters walk declarations rather than the catalog. The
   * extension receiver is not a plan parameter, so it is never widened and every arm keeps it.
   */
  @Test
  fun `top-level and extension defaults widen one signature on both halves`() {
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
    assertContains(kotlin, "@CName(\"library_tier1_fundefaultsstatic__hail\")")
    assertFalse(kotlin.contains("hail_2"), "no synthesized export; generated=$kotlin")
    assertContains(kotlin, "@CName(\"library_tier1_fundefaultsstatic__mitten_knead\")")
    assertFalse(kotlin.contains("mitten_knead_2"), "no synthesized export; generated=$kotlin")
    // The load-bearing assertion: the unset arm calls Kotlin without the argument, and the
    // extension keeps its receiver even when every parameter is left unset.
    assertContains(kotlin, "0 -> tier1.fundefaultsstatic.hail(name)")
    assertContains(kotlin, ".get().knead()")
    assertContains(kotlin, ".get().knead(surface = default_surface!!)")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static string Hail(string name, bool? loud = null)")
    assertContains(
      cs,
      "public static string Knead(this global::Interop.Mitten receiver, int? times = null, " +
          "string? surface = null)",
    )
  }

  /**
   * Structural. Two `expect` overloads share one qualified name, so the index has to resolve them
   * by signature: only the second namesake carries a trailing default, and only *its* parameter
   * may widen. Attributing the wrong declaration's defaults would widen `tag` into a
   * `beam(name)` arm whose Kotlin call site does not compile.
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
    assertContains(kotlin, "@CName(\"library_tier1_fundefaultsexpectoverload__beam\")")
    assertContains(kotlin, "@CName(\"library_tier1_fundefaultsexpectoverload__beam_2\")")
    assertFalse(kotlin.contains("beam_3"), "no synthesized export; generated=$kotlin")
    // The *second* namesake (`level`) widens, not the first (`tag`).
    assertContains(kotlin, "0 -> tier1.fundefaultsexpectoverload.beam(name)")
    assertContains(result.generatedCSharp, "public static string Beam(string name, int? level = null)")
    assertContains(result.generatedCSharp, "public static string Beam(string name, string tag)")
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
    assertContains(cs, "partial class PurrKt\n")
    assertContains(cs, "partial class PurrLoud\n")
    assertFalse(
      cs.contains("class PurrActual"),
      "an actual must never name its own per-target file; generated=$cs",
    )
  }

  /**
   * Structural. Kotlin forbids an override from restating a default, so the override's shape comes
   * off its root: base and override render the same widened signature (C# `override` needs them
   * to agree), and neither gets a second export.
   */
  @Test
  fun `an override renders its base's widened signature`() {
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
    assertContains(kotlin, "@CName(\"library_tier1_fundefaultsoverride__animal_speak\")")
    assertContains(kotlin, "@CName(\"library_tier1_fundefaultsoverride__cat_speak\")")
    assertFalse(kotlin.contains("speak_2"), "no synthesized export; generated=$kotlin")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public virtual string Speak(int times, bool? loud = null)")
    assertContains(cs, "public override string Speak(int times, bool? loud = null)")
  }
}
