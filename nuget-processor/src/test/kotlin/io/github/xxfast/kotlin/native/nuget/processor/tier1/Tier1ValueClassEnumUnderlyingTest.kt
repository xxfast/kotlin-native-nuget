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
    assertContains(cs, "public Mood Mood { get; }")
    assertContains(cs, "private static extern int Native_Create(int mood, out IntPtr error);")
    assertContains(cs, "int underlying = Native_Create((int)mood, out IntPtr error);")
    // The enum cast spells the classifier's qualified name (`global::Interop.Mood` under this
    // harness), so assert the cast-and-assign shape rather than one spelling.
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
    assertContains(cs, "public Temperament Temperament")
    assertContains(cs, "Native_Set_temperament(_handle, (int)value.Mood, out IntPtr error)")
    assertContains(cs, "public Temperament Soothe(Temperament current)")
    assertContains(cs, "Native_Soothe(_handle, (int)current.Mood, out IntPtr error)")
    assertContains(cs, "return new Temperament((global::Interop.Mood)nativeResult);")
  }

  @Test
  fun `nullable primitive- and enum-underlying value classes keep the named skip everywhere`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassnullableskip

      enum class Mood { CALM, ANXIOUS }

      @JvmInline
      value class Temperament(val mood: Mood)

      @JvmInline
      value class Dosage(val milligrams: Double)

      class Patient(val name: String) {
        // ADR-077 deferred: the int/double wire has no in-band null, so every position below must
        // skip with the named VALUE_CLASS diagnostic, never plan and never crash.
        var restingTemperament: Temperament? = null
        var lastDosage: Dosage? = null

        fun calm(current: Temperament?): String = current?.mood?.name ?: "none"

        fun latest(): Dosage? = lastDosage
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected the deferred nullables to skip cleanly; got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    assertFalse(cs.contains("RestingTemperament"), "Nullable(ValueClass(Enum)) property must stay skipped")
    assertFalse(cs.contains("LastDosage"), "Nullable(ValueClass(Primitive)) property must stay skipped")
    assertFalse(cs.contains("Calm("), "Nullable(ValueClass(Enum)) parameter must skip the whole callable")
    assertFalse(cs.contains("Latest("), "Nullable(ValueClass(Primitive)) return must skip the whole callable")
  }
}
