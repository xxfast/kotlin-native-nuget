package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-090: ordinary-class method overloads, the same numbering ADR-082 gave value-class methods
 * (itself ADR-034's secondary-constructor scheme). First declared overload keeps the bare export
 * name, the n-th further one is `_$n`; the C# surface stays one natural overload set with no
 * visible numbering.
 *
 * The end-to-end half lives in `CatNarrator` / `MethodOverloadTests.cs`. What is only reachable
 * here is the reference-nullability collision, whose correct outcome is a *failed* generation and
 * so cannot go in the shipped fixture library.
 */
class Tier1ClassMethodOverloadTest {

  /**
   * Structural. Three same-name declarations on one class produce `_`, `_2`, `_3` exports whose
   * Kotlin bodies all call the bare `describe(` — the suffix belongs to the plan symbol, the C
   * name and the export function name, never to the call site.
   */
  @Test
  fun `class overloads get numbered exports whose bodies call the declared name`() {
    val result = Tier1Harness.run(
      """
      package tier1.classoverload

      class Narrator(val name: String) {
        fun describe(): String = name
        fun describe(prefix: String): String = "${'$'}prefix ${'$'}name"
        fun describe(prefix: String, excited: Boolean): String =
          if (excited) "${'$'}prefix ${'$'}name!" else "${'$'}prefix ${'$'}name"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected all three overloads to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"narrator_describe\")")
    assertContains(kotlin, "@CName(\"narrator_describe_2\")")
    assertContains(kotlin, "@CName(\"narrator_describe_3\")")
    assertFalse(
      kotlin.contains(".describe_2(") || kotlin.contains(".describe_3("),
      "every Kotlin call site must say the declared name; generated=$kotlin",
    )
    assertEquals(
      3,
      Regex("""\.get\(\)\.describe\(""").findAll(kotlin).count(),
      "expected all three exports to dispatch to `describe(`; generated=$kotlin",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"narrator_describe\"")
    assertContains(cs, "EntryPoint = \"narrator_describe_2\"")
    assertContains(cs, "EntryPoint = \"narrator_describe_3\"")
    assertContains(cs, "public string Describe()")
    assertContains(cs, "public string Describe(string prefix)")
    assertContains(cs, "public string Describe(string prefix, bool excited)")
    // Both halves: the private extern is declared numbered, *and* the body calls the numbered one
    // (the sync-error renderer rebuilds the body independently of the plan's projection).
    assertContains(cs, "private static extern IntPtr Native_Describe_2(")
    assertContains(cs, "private static extern IntPtr Native_Describe_3(")
    assertContains(cs, "Native_Describe_2(_handle, prefix, out IntPtr error)")
    assertContains(cs, "Native_Describe_3(_handle, prefix, excited, out IntPtr error)")
  }

  /**
   * Structural, the CS0111 shape. `Int` and an enum both cross the ABI as `int`, so the two
   * overloads' private externs would collide if only the `EntryPoint` carried the number.
   */
  @Test
  fun `overloads sharing one wire shape get distinct private extern names`() {
    val result = Tier1Harness.run(
      """
      package tier1.classoverloadwire

      enum class Mood { CALM, ANXIOUS }

      class Narrator(val name: String) {
        fun rate(stars: Int): String = "${'$'}name ${'$'}stars"
        fun rate(mood: Mood): String = "${'$'}name ${'$'}mood"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected both rate overloads to compile; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"narrator_rate\"")
    assertContains(cs, "EntryPoint = \"narrator_rate_2\"")
    assertContains(cs, "Native_Rate(_handle, stars, out IntPtr error)")
    assertContains(cs, "Native_Rate_2(_handle, (int)mood, out IntPtr error)")
    assertEquals(
      1,
      Regex("""static extern .*Native_Rate\(""").findAll(cs).count(),
      "one extern name may only be declared once (CS0111); generatedCSharp=$cs",
    )
  }

  /**
   * Diagnostic. C# cannot overload on reference nullability alone, so a `String` / `String?` pair
   * renders two identical C# signatures. That must fail generation with the ADR-034 kind, not
   * emit uncompilable C#. Deliberately in-process only: its correct outcome is a failed build.
   */
  @Test
  fun `reference-nullability-only overloads fire ERROR_CSHARP_SIGNATURE_COLLISION`() {
    val result = Tier1Harness.run(
      """
      package tier1.classoverloadcollision

      class Narrator(val name: String) {
        fun tag(s: String): String = s
        fun tag(s: String?): String = s ?: name
      }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
      },
      "expected the reference-nullability collision to fail generation; " +
          "kspErrors=${result.kspErrors}",
    )
  }

  /**
   * Regression. A class with no same-name members must keep exactly the shipped naming: no
   * spurious `_2`, and the private extern still `Native_$Name`.
   */
  @Test
  fun `a class without overloads keeps its unsuffixed naming`() {
    val result = Tier1Harness.run(
      """
      package tier1.classnooverload

      class Narrator(val name: String) {
        fun describe(): String = name
        fun greet(prefix: String): String = "${'$'}prefix ${'$'}name"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the plain class to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"narrator_describe\")")
    assertContains(kotlin, "@CName(\"narrator_greet\")")
    assertFalse(kotlin.contains("_describe_2"), "no numbering without overloads; generated=$kotlin")
    assertFalse(kotlin.contains("_greet_2"), "no numbering without overloads; generated=$kotlin")

    val cs: String = result.generatedCSharp
    assertContains(cs, "Native_Describe(")
    assertContains(cs, "Native_Greet(")
    assertFalse(
      cs.contains("Native_Describe_2"),
      "no numbering without overloads; generatedCSharp=$cs",
    )
  }
}
