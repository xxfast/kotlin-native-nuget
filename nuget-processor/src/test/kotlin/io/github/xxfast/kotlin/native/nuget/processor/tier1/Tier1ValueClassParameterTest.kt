package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.valueClassUnderlyingOrThrow
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

      // A top-level nullable-scalar return (ADR-170's single-call valueOut route) admits a
      // value-class parameter like every other planned shape.
      fun chartLength(id: ChartId): Int? = if (id.isValid()) id.value.length else null
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected value-class parameter exports to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "id: String")
    assertContains(kotlin, "tier1.valueclassparam.ChartId(id)")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public string Retag(global::Interop.ChartId id)")
    assertContains(cs, "Native_Retag(_handle, " +
      valueClassUnderlyingOrThrow("id", "Value", "ChartId", "id") +
      ", out IntPtr error)")
    assertContains(cs, "public static string ChartSummary(global::Interop.ChartId id)")
    assertContains(cs, "Native_ChartSummary(" +
      valueClassUnderlyingOrThrow("id", "Value", "ChartId", "id") +
      ", out IntPtr error)")
    assertContains(cs, "public static int? ChartLength(global::Interop.ChartId id)")
  }

  @Test
  fun `nullable value class parameter and return ride the null pointer on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassnullparam

      @JvmInline
      value class ChartId(val value: String)

      class Patient(val name: String) {
        var backup: ChartId? = null

        // ADR-077 sub-item 3 · nullable parameter position.
        fun transferTo(to: ChartId?): String = to?.value ?: "none"

        // ADR-077 sub-item 3 · nullable return position.
        fun previousChart(): ChartId? = backup
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected nullable value-class exports to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "transferTo(to?.let { tier1.valueclassnullparam.ChartId(it) })")
    assertContains(kotlin, "previousChart()?.value")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public string TransferTo(global::Interop.ChartId? to)")
    assertContains(cs, "Native_TransferTo(_handle, to.HasValue ? " +
      valueClassUnderlyingOrThrow("to.Value", "Value", "ChartId", "to") +
      " : null, out IntPtr error)")
    assertContains(cs, "string? to, out IntPtr error);")
    assertContains(cs, "public global::Interop.ChartId? PreviousChart()")
    assertContains(
      cs,
      "return nativeResult == IntPtr.Zero ? null : " +
          "new global::Interop.ChartId(Marshal.PtrToStringUTF8(nativeResult)!);",
    )
  }
}
