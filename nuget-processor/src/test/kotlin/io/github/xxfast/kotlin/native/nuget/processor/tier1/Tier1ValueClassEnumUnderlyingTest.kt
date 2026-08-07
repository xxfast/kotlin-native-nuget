package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
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
}
