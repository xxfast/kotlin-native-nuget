package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-211: a top-level `fun <T>` the legacy width-variant gate refuses binds on the ADR-062 plan
 * as a static generic method on its file-facade class, its bare `T` on the ADR-147 boxed wire. A
 * shape the gate accepts (`identity`) stays on the legacy route, width variants and all.
 *
 * Oreo gets described; Mylo keeps whatever he is handed.
 */
class Tier1TopLevelGenericMethodTest {

  private val options: Map<String, String> = mapOf("nuget.rootPackage" to "tier1")

  private val source: String = """
    package tier1.toplevelgeneric

    fun <T : Any> describe(value: T): String = "${'$'}value"
    fun <T> label(value: T): String = "bowl of ${'$'}value"
    fun <T> identity(value: T): T = value
    fun <T> pick(first: T, second: T): T = first
    fun <T> scale(x: T, n: Int): T = x
  """.trimIndent()

  private fun run(): Tier1Result =
    Tier1Harness.run(source, fileName = "Descriptions.kt", processorOptions = options)

  private fun assertKotlinCompiles(result: Tier1Result) {
    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
  }

  @Test
  fun `a gate-refused top-level generic binds on the plan`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static partial class Descriptions")
    assertContains(cs, "public static string Describe<T>(T value_) where T : notnull")
    assertContains(cs, "public static string Label<T>(T value_)")
    assertContains(result.generated, ".describe<Any>(")
    assertContains(result.generated, ".label<Any?>(")
    val warnings: List<String> = result.kspWarnings.filter { it.contains("tier1.") }
    assertTrue(warnings.isEmpty(), "expected every function to bind; got: $warnings")
  }

  @Test
  fun `a gate-accepted top-level generic keeps the legacy width variants`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static T Identity<T>(T value)")
    assertContains(cs, "EntryPoint = \"library_toplevelgeneric__identity_int\"")
    assertFalse(
      cs.contains("EntryPoint = \"library_toplevelgeneric__describe_int\""),
      "describe has no width variants: $cs",
    )
  }

  /** Issue #556: the width-variant route carries one parameter, so a second sends it to the plan */
  @Test
  fun `a multi-parameter top-level generic binds on the plan with no width variants`() {
    val result: Tier1Result = run()
    assertKotlinCompiles(result)

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static T Pick<T>(T first, T second)")
    assertContains(cs, "public static T Scale<T>(T x, int n)")
    assertFalse(
      cs.contains("EntryPoint = \"library_toplevelgeneric__pick_int\""),
      "pick has no width variants: $cs",
    )
    assertFalse(
      cs.contains("EntryPoint = \"library_toplevelgeneric__scale_int\""),
      "scale has no width variants: $cs",
    )
    val warnings: List<String> = result.kspWarnings.filter { it.contains("tier1.") }
    assertTrue(warnings.isEmpty(), "expected every function to bind; got: $warnings")
  }

  /** ADR-211 open question: the Try twin's `out value` beside a `value_` parameter. */
  @Test
  fun `a Result-returning top-level generic compiles on both halves`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.toplevelgenericresult

      fun <T> wrap(value: T): Result<T> = Result.success(value)
      """.trimIndent(),
      fileName = "Wraps.kt",
      processorOptions = options,
    )
    assertKotlinCompiles(result)
    val warnings: List<String> = result.kspWarnings.filter { it.contains("tier1.") }
    assertTrue(warnings.isEmpty(), "expected wrap to bind; got: $warnings")

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Toplevelgenericresult;

      namespace Consumer
      {
          public static class Probe
          {
              public static string Run()
              {
                  string wrapped = Wraps.Wrap("mylo");
                  return Wraps.TryWrap("oreo", out string? value, out System.Exception? _) ? value! : wrapped;
              }
          }
      }
      """.trimIndent(),
    )
  }
}
