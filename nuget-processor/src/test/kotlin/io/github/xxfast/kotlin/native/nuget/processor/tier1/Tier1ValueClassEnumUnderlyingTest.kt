package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-077 sub-item 4 prerequisite: a bare enum-underlying value-class *declaration* must pack. It
 * used to crash the whole KSP run (`Forward ABI missing C# projection for temperament_create`):
 * the planner classifies with `BridgeType` (enum -> value underlying, `_create` planned), while
 * `CirClassTranslator` / `ValueClassExports` classified by *name* against the primitive maps
 * (enum -> "reference" -> primary constructor deferred per ADR-035), so each half dropped a
 * different side of the same export. The wire is the int ordinal both ways; the public struct
 * member is the C# enum itself.
 */
class Tier1ValueClassEnumUnderlyingTest {

  @Test
  fun `enum-underlying value class declaration renders a consistent create ABI on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassenum

      enum class Mood { CALM, ANXIOUS }

      @JvmInline
      value class Temperament(val mood: Mood)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected enum-underlying value class to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"temperament_create\")")
    assertContains(kotlin, "tier1.valueclassenum.Temperament(tier1.valueclassenum.Mood.entries[mood]).mood.ordinal")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public readonly record struct Temperament")
    assertContains(cs, "public global::Interop.Mood Mood { get; }")
    assertContains(cs, "private static extern int Native_Create(int mood, out IntPtr error);")
    assertContains(cs, "int underlying = Native_Create((int)mood, out IntPtr error);")
    assertContains(cs, "Mood = (global::Interop.Mood)CreateChecked(mood);")
  }

  @Test
  fun `enum-underlying value class at ordinary positions rides the int ordinal on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassenumordinary

      enum class Mood { CALM, ANXIOUS }

      @JvmInline
      value class Temperament(val mood: Mood)

      class Patient(val name: String) {
        var temperament: Temperament = Temperament(Mood.CALM)

        fun soothe(current: Temperament): Temperament =
          if (current.mood == Mood.ANXIOUS) Temperament(Mood.CALM) else current
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected enum-underlying ordinary positions to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "get().temperament.mood.ordinal")
    assertContains(
      kotlin,
      "get().temperament = tier1.valueclassenumordinary.Temperament(tier1.valueclassenumordinary.Mood.entries[value])",
    )
    assertContains(
      kotlin,
      ".soothe(tier1.valueclassenumordinary.Temperament(tier1.valueclassenumordinary.Mood.entries[current])).mood.ordinal",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "public global::Interop.Temperament Temperament")
    assertContains(cs, "Native_Set_temperament(_handle, (int)value.Mood, out IntPtr error)")
    assertContains(
      cs,
      "public global::Interop.Temperament Soothe(global::Interop.Temperament current)",
    )
    assertContains(cs, "Native_Soothe(_handle, (int)current.Mood, out IntPtr error)")
    assertContains(
      cs,
      "return new global::Interop.Temperament((global::Interop.Mood)nativeResult);",
    )
  }

  @Test
  fun `nullable primitive- and enum-underlying value classes ride the has-value fan-out`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassnullableskip

      enum class Mood { CALM, ANXIOUS }

      @JvmInline
      value class Temperament(val mood: Mood)

      @JvmInline
      value class Dosage(val milligrams: Double)

      class Patient(val name: String) {
        // ADR-077 deferred these; ADR-079 binds them. The int/double wire has no in-band null, so
        // each position below rides its own out-of-band has-value channel instead: ADR-002's
        // LegacyTwoCall getter + NullableDispatch setter for the properties, the adjacent
        // name-plus-HasValue parameter pair, ADR-061's BOOLEAN + valueOut for the return.
        var restingTemperament: Temperament? = null
        var lastDosage: Dosage? = null

        fun calm(current: Temperament?): String = current?.mood?.name ?: "none"

        fun latest(): Dosage? = lastDosage

        fun mood(): Temperament? = restingTemperament
      }

      // ADR-002's two-call `_has_value` + `_value`, the top-level return's own shape.
      fun standardDosage(kind: Int): Dosage? = if (kind < 0) null else Dosage(kind * 0.5)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected the nullable underlyings to bind; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // Parameter: the HasValue flag decides, so the value slot's dead default is never re-wrapped.
    assertContains(
      kotlin,
      "if (currentHasValue) tier1.valueclassnullableskip.Temperament(" +
          "tier1.valueclassnullableskip.Mood.entries[current]) else null",
    )
    // Return: the unboxed underlying is written through the underlying's own CVar.
    assertContains(kotlin, "valueOut.reinterpret<DoubleVar>().pointed.value = result.milligrams")
    assertContains(kotlin, "valueOut.reinterpret<IntVar>().pointed.value = result.mood.ordinal")
    // Top-level return: ADR-002's two-call pair, the `_value` half unboxing to the underlying.
    assertContains(kotlin, "@CName(\"standardDosage_has_value\")")
    assertContains(kotlin, "@CName(\"standardDosage_value\")")
    assertContains(kotlin, "standardDosage(kind)!!.milligrams")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public global::Interop.Temperament? RestingTemperament")
    assertContains(cs, "public global::Interop.Dosage? LastDosage")
    assertContains(cs, "return new global::Interop.Temperament((global::Interop.Mood)value);")
    assertContains(cs, "Native_Set_lastDosage(_handle, value.Value.Milligrams, out IntPtr error)")
    assertContains(cs, "Native_Calm(_handle, current.HasValue, (int)current.GetValueOrDefault().Mood")
    assertContains(cs, "public global::Interop.Dosage? Latest()")
    assertContains(
      cs,
      "hasValue ? new global::Interop.Dosage(valueOut) : (global::Interop.Dosage?)null;",
    )
    // The enum underlying's valueOut is the bare `int` ordinal, cast back on reconstruction.
    assertContains(cs, "out int valueOut")
    assertContains(
      cs,
      "hasValue ? new global::Interop.Temperament((global::Interop.Mood)valueOut) : " +
          "(global::Interop.Temperament?)null;",
    )
    assertContains(cs, "private static extern double standardDosage_value(int kind, out IntPtr error);")
    assertContains(cs, "public static global::Interop.Dosage? standardDosage(int kind)")
    assertContains(cs, "return new global::Interop.Dosage(__nuget_value);")
  }
}
