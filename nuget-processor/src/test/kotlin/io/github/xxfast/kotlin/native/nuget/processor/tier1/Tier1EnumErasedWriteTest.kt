package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-094 (write side): an exported enum written into an erased generic slot. The read side
 * registers every enum in `Factories` (the 2026-09-29 amendment); the write side had no route, so
 * `new Box<Mood>(Mood.Calm)` and `Echo<Mood>(Mood.Calm)` compiled and threw in `Wrap<T>`.
 *
 * Each enum now gets one Kotlin export that retains the entry its ordinal names, and one
 * statically written `NugetMarshal.Boxers` row that calls it, keyed on the enum's own type, so
 * `T = Mood`, `T = Mood?` and `T = object` all reach it. No reflection: the row is a plain lambda
 * over a `DllImport`, the same shape as the ADR-171 value-class rows.
 */
class Tier1EnumErasedWriteTest {

  private val options: Map<String, String> = mapOf("nuget.namespace" to "Tier1")

  private val fixture: String =
    """
    package tier1.enumwrite

    enum class Mood { CALM, GRUMPY }

    class Kennel {
      enum class Gait { WALK, TROT }
    }

    class Box<T>(val value: T) {
      fun describe(other: T): String = "${'$'}other:${'$'}value"
    }

    fun <T> echo(value: T): T = value
    """.trimIndent()

  @Test
  fun `every enum gets a box export and a Boxers row, top-level and nested`() {
    val result: Tier1Result = Tier1Harness.run(fixture, processorOptions = options)

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    // The Kotlin half: the entry the ordinal names, minted through the one handle owner.
    assertContains(result.generated, "NugetHandles.retain(tier1.enumwrite.Mood.entries[entry])")
    assertContains(
      result.generated,
      "NugetHandles.retain(tier1.enumwrite.Kennel.Gait.entries[entry])",
    )
    // The C# half: one row per enum, keyed on the enum type, the ordinal lowered at the call.
    val cs: String = result.generatedCSharp
    assertContains(cs, "[typeof(global::Tier1.Mood)] = static value => NugetErrorNative.Check(")
    assertContains(cs, "((int)(global::Tier1.Mood)value, out IntPtr error), error),")
    assertContains(
      cs,
      "[typeof(global::Tier1.Kennel.Gait)] = static value => NugetErrorNative.Check(",
    )
    assertContains(cs, "((int)(global::Tier1.Kennel.Gait)value, out IntPtr error), error),")
    // The read side is unchanged.
    assertContains(
      cs,
      "[typeof(global::Tier1.Mood)] = static handle => " +
        "(global::Tier1.Mood)UnwrapEnumOrdinal(handle),",
    )

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System;
      using Tier1;

      public static class Probe
      {
          public static string RoundTrip()
          {
              using var box = new Box<Mood>(Mood.Grumpy);
              using var maybe = new Box<Mood?>(null);
              using var boxed = new Box<object>(Mood.Calm);
              Mood? none = Fixture.Echo<Mood?>(null);
              Kennel.Gait gait = Fixture.Echo(Kennel.Gait.Trot);
              return box.Describe(Mood.Calm) + Fixture.Echo(box.Value) + none + gait;
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * The box export is a generated role, and an enum member is exported as `<enum>_<name>`, so the
   * role's name must not be one a conventionally named member produces: `_box` would collide with
   * an enum's own `fun box()` and fail a library that built before as a duplicate export.
   */
  @Test
  fun `an enum member named box does not collide with the generated box export`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.enumwritebox

      enum class Parcel {
        SMALL, LARGE;

        fun box(): Int = ordinal + 1
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.kspSucceeded, "ksp: ${result.kspErrors}")
    assertTrue(result.compiledClean, "generated Kotlin: ${result.compileErrors}")
    val names: List<String> = Regex("@CName\\(\"([^\"]+)\"\\)").findAll(result.generated)
      .map { match -> match.groupValues[1] }
      .filter { name -> name.contains("parcel_box") }
      .toList()
    assertEquals(2, names.size, "$names")
    assertEquals(names.size, names.toSet().size, "$names")
  }
}
