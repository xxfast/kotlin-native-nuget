package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-080: a bare `Mood?` (**not** value-class-wrapped) binds at every ordinary forward position,
 * riding ADR-079's has-value shapes with the box/unbox step deleted -- the wire is already the
 * `int` ordinal. Before this, the property position crashed the whole KSP run
 * (`Forward property direct nullable getter is invalid for ...`) because `hasValueFanOutInner()`
 * had no bare-`Enum` case and the getter took the `Direct` route, while parameters and returns
 * skipped the whole callable with `NULLABLE`.
 *
 * These cells exist because the branches they cover are otherwise reachable only through the
 * Kotlin/Native `:test-library` KSP run, which has no coverage tooling: every `when` body added by
 * ADR-080 is asserted here.
 */
class Tier1BareNullableEnumTest {

  @Test
  fun `bare nullable enum property rides the has-value fan-out on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.barenullableenum

      enum class Mood { HAPPY, SLEEPY, GRUMPY }

      class MoodJournal {
        // ADR-002's LegacyTwoCall getter (`_has_value` + `_value`) plus the NullableDispatch
        // setter (`set` + `set_null`); the int ordinal has no in-band null to ride.
        var currentMood: Mood? = null
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a bare nullable enum property to bind; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // Getter: the presence call answers null-ness, the value call ships the ordinal.
    assertContains(kotlin, "get().currentMood != null")
    assertContains(kotlin, "get().currentMood!!.ordinal")
    // Setter: the `set` export carries the bare ordinal, so the entry lookup is unconditional
    // (`set_null` is the other export).
    assertContains(kotlin, "get().currentMood = tier1.barenullableenum.Mood.entries[value]")
    assertContains(kotlin, "get().currentMood = null")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public global::Interop.Mood? CurrentMood")
    assertContains(cs, "if (!hasValue) return null;")
    assertContains(cs, "int value = ")
    assertContains(cs, "return (global::Interop.Mood)value;")
    assertContains(cs, "(int)value.Value")
  }

  @Test
  fun `bare nullable enum parameter fans out to the adjacent HasValue pair`() {
    val result = Tier1Harness.run(
      """
      package tier1.barenullableenumparam

      enum class Mood { HAPPY, SLEEPY, GRUMPY }

      class MoodJournal(val observed: Mood?) {
        fun describe(mood: Mood?): String = mood?.name ?: "none"
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a bare nullable enum parameter to bind; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // The HasValue flag decides, so the value slot's dead default is never looked up.
    assertContains(
      kotlin,
      "if (moodHasValue) tier1.barenullableenumparam.Mood.entries[mood] else null",
    )
    assertContains(
      kotlin,
      "if (observedHasValue) tier1.barenullableenumparam.Mood.entries[observed] else null",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "bool moodHasValue, int mood")
    assertContains(cs, "mood.HasValue, (int)mood.GetValueOrDefault()")
    assertContains(cs, "observed.HasValue, (int)observed.GetValueOrDefault()")
  }

  @Test
  fun `bare nullable enum method return uses the BOOLEAN plus valueOut shape`() {
    val result = Tier1Harness.run(
      """
      package tier1.barenullableenumreturn

      enum class Mood { HAPPY, SLEEPY, GRUMPY }

      class MoodJournal {
        // ADR-061's single-call shape: a BOOLEAN has-value result plus an `int` valueOut.
        fun soothe(mood: Mood?): Mood? = if (mood == Mood.GRUMPY) Mood.SLEEPY else mood
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a bare nullable enum return to bind; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "valueOut.reinterpret<IntVar>().pointed.value = result.ordinal")

    val cs: String = result.generatedCSharp
    assertContains(cs, "out int valueOut")
    assertContains(
      cs,
      "hasValue ? (global::Interop.Mood)valueOut : (global::Interop.Mood?)null;",
    )
  }

  @Test
  fun `top-level bare nullable enum return keeps ADR-002's two-call shape`() {
    val result = Tier1Harness.run(
      """
      package tier1.barenullableenumtoplevel

      enum class Mood { HAPPY, SLEEPY, GRUMPY }

      fun napMood(hour: Int): Mood? = if (hour < 0) null else Mood.entries[hour % 3]
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a top-level nullable enum return to bind; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"napMood_has_value\")")
    assertContains(kotlin, "@CName(\"napMood_value\")")
    assertContains(kotlin, "napMood(hour)!!.ordinal")

    val cs: String = result.generatedCSharp
    assertContains(cs, "private static extern int napMood_value(int hour, out IntPtr error);")
    assertContains(cs, "public static global::Interop.Mood? napMood(int hour)")
    assertContains(cs, "return (global::Interop.Mood)__nuget_value;")
  }
}
