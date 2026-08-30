package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-077 sub-item 4: primitive- and ObjectHandle-underlying value classes at ordinary positions
 * (param, property, return), plus the one admissible nullable, `Nullable(ValueClass(ObjectHandle))`
 * riding the null pointer. Mirrors the `Dosage`/`ChartRef` integration fixture through the real
 * `NugetProcessor` so the JVM suite covers the same branches the native build exercises.
 */
class Tier1ValueClassHandleAndPrimitiveTest {

  @Test
  fun `primitive- and handle-underlying value classes cross at ordinary positions on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclasskinds

      class Patient(val name: String) {
        var dosage: Dosage = Dosage(1.0)
        var chartRef: ChartRef = ChartRef(this)
        var backupReferral: ChartRef? = null

        fun prescribe(dosage: Dosage): Dosage = Dosage(dosage.milligrams * 2)
        fun reassign(referral: ChartRef): String = "moved to ${'$'}{referral.patient.name}"
        fun ownReferral(): ChartRef = ChartRef(this)
        fun backup(): ChartRef? = backupReferral
        fun transfer(to: ChartRef?): String = to?.patient?.name ?: "none"
      }

      @JvmInline
      value class Dosage(val milligrams: Double)

      @JvmInline
      value class ChartRef(val patient: Patient)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected value-class kinds to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // Primitive underlying: straight through the double wire, boxed/unboxed at the boundary.
    assertContains(kotlin, ".prescribe(tier1.valueclasskinds.Dosage(dosage)).milligrams")
    assertContains(kotlin, "get().dosage.milligrams")
    assertContains(kotlin, "get().dosage = tier1.valueclasskinds.Dosage(value)")
    // Handle underlying: StableRef both ways, composed inside the value-class re-wrap.
    assertContains(kotlin, "get().ownReferral().patient")
    assertContains(
      kotlin,
      "get().chartRef = tier1.valueclasskinds.ChartRef(value.asStableRef<tier1.valueclasskinds.Patient>().get())",
    )
    assertContains(
      kotlin,
      "get().backupReferral = value?.let { tier1.valueclasskinds.ChartRef(it.asStableRef<tier1.valueclasskinds.Patient>().get()) }",
    )
    assertContains(kotlin, "get().backup()?.patient")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public global::Interop.Dosage Prescribe(global::Interop.Dosage dosage)")
    assertContains(cs, "double nativeResult = Native_Prescribe(_handle, dosage.Milligrams, out IntPtr error);")
    assertContains(cs, "return new global::Interop.Dosage(nativeResult);")
    assertContains(cs, "Native_Reassign(_handle, referral.Patient._handle, out IntPtr error)")
    assertContains(cs, "public global::Interop.ChartRef OwnReferral()")
    assertContains(cs, "return new global::Interop.ChartRef(new global::Interop.Patient(nativeResult));")
    assertContains(cs, "public global::Interop.ChartRef? BackupReferral")
    assertContains(cs, "return nativeResult == IntPtr.Zero ? null : new global::Interop.ChartRef(new global::Interop.Patient(nativeResult));")
    assertContains(cs, "Native_Set_backupReferral(_handle, value?.Patient._handle ?? IntPtr.Zero, out IntPtr error)")
    assertContains(cs, "public global::Interop.ChartRef? Backup()")
    // Nullable handle *parameter*: null propagates to IntPtr.Zero, Kotlin `?.let`-re-wraps.
    assertContains(cs, "Native_Transfer(_handle, to?.Patient._handle ?? IntPtr.Zero, out IntPtr error)")
    assertContains(
      kotlin,
      ".transfer(to?.let { tier1.valueclasskinds.ChartRef(it.asStableRef<tier1.valueclasskinds.Patient>().get()) })",
    )
  }
}
