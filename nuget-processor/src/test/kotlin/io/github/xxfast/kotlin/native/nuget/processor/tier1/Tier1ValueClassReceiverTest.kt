package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Value-class receivers on extensions, a mirror of ADR-077's parameter lowering at the receiver
 * slot. `Tier1OrdinarySurfaceTest` already covers the `String`-underlying property receiver
 * (`receiver.Value`, the ADR-075 shape); this file covers the underlyings that used to be dropped
 * or mis-lowered: an enum receiver rides its `int` ordinal, an ObjectHandle receiver rides its
 * StableRef handle, and an extension *function* re-wraps the receiver instead of calling the
 * member on the raw wire value (which did not compile at all before).
 */
class Tier1ValueClassReceiverTest {

  @Test
  fun `extension property on an enum- or handle-underlying value class receiver binds on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassreceiverprop

      enum class Mood { CALM, ANXIOUS }

      class Patient(val name: String)

      @JvmInline
      value class Temperament(val mood: Mood)

      @JvmInline
      value class ChartRef(val patient: Patient)

      private val notes: MutableMap<Temperament, String> = mutableMapOf()
      private val annotations: MutableMap<ChartRef, String> = mutableMapOf()

      var Temperament.note: String
        get() = notes[this] ?: ""
        set(value) { notes[this] = value }

      var ChartRef.annotation: String
        get() = annotations[this] ?: ""
        set(value) { annotations[this] = value }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected the value-class receivers to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // The wire carries the underlying's representation, so the receiver is reconstructed from the
    // ordinal / the StableRef before the property access.
    assertContains(
      kotlin,
      "tier1.valueclassreceiverprop.Temperament(tier1.valueclassreceiverprop.Mood.entries[receiver]).note",
    )
    assertContains(
      kotlin,
      "tier1.valueclassreceiverprop.ChartRef(receiver.asStableRef<tier1.valueclassreceiverprop.Patient>().get()).annotation",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "private static extern IntPtr Native_TemperamentGetNote(int receiver, out IntPtr error);")
    assertContains(cs, "private static extern IntPtr Native_ChartrefGetAnnotation(IntPtr receiver, out IntPtr error);")
    assertContains(cs, "Native_TemperamentGetNote((int)receiver.Mood, out IntPtr error)")
    assertContains(cs, "Native_TemperamentSetNote((int)receiver.Mood, value, out IntPtr error)")
    assertContains(cs, "Native_ChartrefGetAnnotation(receiver.Patient._handle, out IntPtr error)")
    assertContains(cs, "Native_ChartrefSetAnnotation(receiver.Patient._handle, value, out IntPtr error)")
  }

  @Test
  fun `extension function on a value class receiver re-wraps the receiver on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassreceiverfun

      enum class Mood { CALM, ANXIOUS }

      @JvmInline
      value class ChartId(val value: String)

      @JvmInline
      value class Temperament(val mood: Mood)

      fun ChartId.abbreviate(length: Int): String = value.take(length)

      fun Temperament.escalate(): Temperament =
        if (mood == Mood.CALM) Temperament(Mood.ANXIOUS) else this
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected the value-class receivers to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // Before the widening these read `receiver.abbreviate(length)` / `receiver.escalate()`, which
    // konanc rejected: the parameter is typed as the wire (`String` / `Int`), not the value class.
    assertContains(kotlin, "tier1.valueclassreceiverfun.ChartId(receiver).abbreviate(length)")
    assertContains(
      kotlin,
      "tier1.valueclassreceiverfun.Temperament(tier1.valueclassreceiverfun.Mood.entries[receiver]).escalate().mood.ordinal",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "private static extern IntPtr Native_Abbreviate([MarshalAs(UnmanagedType.LPUTF8Str)] string receiver, int length, out IntPtr error);")
    assertContains(cs, "Native_Abbreviate(receiver.Value, length, out IntPtr error)")
    assertContains(cs, "private static extern int Native_Escalate(int receiver, out IntPtr error);")
    assertContains(cs, "public static global::Interop.Temperament Escalate(this global::Interop.Temperament receiver)")
    assertContains(cs, "Native_Escalate((int)receiver.Mood, out IntPtr error)")
    assertContains(cs, "return new global::Interop.Temperament((global::Interop.Mood)nativeResult);")
  }
}
