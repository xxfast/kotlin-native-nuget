package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-077 sub-items 2 and 3: value-class-typed properties, driven through the real
 * `NugetProcessor`. Non-null (sub-item 2): the getter unboxes to the underlying `String` on the
 * Kotlin side and C# reconstructs the record struct; the setter passes `value.Value` and Kotlin
 * re-wraps. Nullable (sub-item 3): the same wire, with the null pointer carrying `null` and `?.`
 * propagation on both sides, surfacing as C# `ChartId?` (a genuine `Nullable<ChartId>`, since the
 * record struct is a value type).
 */
class Tier1ValueClassPropertyTest {

  @Test
  fun `String-underlying value class property plans on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassprop

      @JvmInline
      value class ChartId(val value: String)

      class Patient(val name: String) {
        var currentChart: ChartId = ChartId("CH-0")
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected value-class property exports to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "get().currentChart.value")
    assertContains(kotlin, "get().currentChart = tier1.valueclassprop.ChartId(value)")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public global::Interop.ChartId CurrentChart")
    assertContains(cs, "return new global::Interop.ChartId(Marshal.PtrToStringUTF8(nativeResult)!);")
    assertContains(cs, "Native_Set_currentChart(_handle, value.Value, out IntPtr error)")
  }

  @Test
  fun `nullable String-underlying value class property rides the null pointer on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassnullprop

      @JvmInline
      value class ChartId(val value: String)

      class Patient(val name: String) {
        var backupChart: ChartId? = null
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected nullable value-class property exports to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "get().backupChart?.value")
    assertContains(kotlin, "get().backupChart = value?.let { tier1.valueclassnullprop.ChartId(it) }")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public global::Interop.ChartId? BackupChart")
    assertContains(cs, "return nativeResult == IntPtr.Zero ? null : new global::Interop.ChartId(Marshal.PtrToStringUTF8(nativeResult)!);")
    assertContains(cs, "Native_Set_backupChart(_handle, value?.Value, out IntPtr error)")
    assertContains(cs, "string? value, out IntPtr error);")
  }
}
