package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-077 sub-item 2: a String-underlying value-class-typed `val`/`var` property, driven through
 * the real `NugetProcessor`. Getter unboxes to the underlying `String` on the Kotlin side and C#
 * reconstructs the record struct; setter passes `value.Value` and Kotlin re-wraps. The
 * `Nullable(ValueClass)` guard (sub-item 3) is asserted from the same fixture: a `ChartId?`
 * property must stay entirely absent from the generated C#.
 */
class Tier1ValueClassPropertyTest {

  @Test
  fun `String-underlying value class property plans on both halves and nullable stays guarded`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassprop

      @JvmInline
      value class ChartId(val value: String)

      class Patient(val name: String) {
        var currentChart: ChartId = ChartId("CH-0")

        // Sub-item 3 guard: Nullable(ValueClass) must not ride isPlannable's Nullable recursion.
        var backupChart: ChartId? = null
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected value-class property exports to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "get().currentChart.value")
    assertContains(kotlin, "get().currentChart = tier1.valueclassprop.ChartId(value)")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public ChartId CurrentChart")
    assertContains(cs, "return new ChartId(Marshal.PtrToStringUTF8(nativeResult)!);")
    assertContains(cs, "Native_Set_currentChart(_handle, value.Value, out IntPtr error)")
    assertFalse(cs.contains("BackupChart"), "Nullable(ValueClass) property must stay skipped until sub-item 3")
  }
}
