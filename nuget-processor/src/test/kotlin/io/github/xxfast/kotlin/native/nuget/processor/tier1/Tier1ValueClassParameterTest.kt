package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-077 sub-item 1: a String-underlying value class at an *ordinary parameter* position, driven
 * through the real `NugetProcessor` (not a hand-built plan), so the planner's own
 * `inputSkipReason` / `nativeInputParameters` / helper-set branches are the ones under test. Both
 * projections come off the one plan, so both halves are asserted here: Kotlin re-wraps
 * (`ChartId(id)`), C# unwraps (`id.Value`).
 */
class Tier1ValueClassParameterTest {

  @Test
  fun `String-underlying value class parameter crosses as its underlying on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassparam

      @JvmInline
      value class ChartId(val value: String) {
        fun isValid(): Boolean = value.isNotBlank()
      }

      class Patient(val name: String) {
        fun retag(id: ChartId): String =
          if (id.isValid()) "${'$'}name@${'$'}{id.value}" else "${'$'}name@untagged"
      }

      fun chartSummary(id: ChartId): String = "Chart ${'$'}{id.value}"

      // The ADR-002 two-call route (`_has_value` + `_value`) builds its helper set separately
      // from `planOrSkip`, so a value-class parameter has to be admitted there too.
      fun chartLength(id: ChartId): Int? = if (id.isValid()) id.value.length else null
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected value-class parameter exports to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "id: String")
    assertContains(kotlin, "tier1.valueclassparam.ChartId(id)")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public string Retag(ChartId id)")
    assertContains(cs, "Native_Retag(_handle, id.Value, out IntPtr error)")
    assertContains(cs, "public static string chartSummary(ChartId id)")
    assertContains(cs, "Native_chartSummary(id.Value, out IntPtr error)")
    assertContains(cs, "public static int? chartLength(ChartId id)")
  }
}
