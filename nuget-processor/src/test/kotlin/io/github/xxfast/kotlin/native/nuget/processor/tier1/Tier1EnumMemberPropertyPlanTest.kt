package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-006 amendment: an enum's own member properties ride the ADR-062 forward property plan
 * (`ForwardPropertyPosition.ENUM_MEMBER`). Before, the route was hand-written on both halves: the
 * getter export had no error slot (a throwing getter aborted the host), a `var`'s setter was
 * silently dropped, and there was no type gate, so a member of any type was exported as whatever
 * `ClassName.bestGuess` produced and read on the C# side through `mapReturnType`'s `IntPtr`
 * fallback.
 *
 * These cells pin what the plan does with each shape the old route mishandled.
 */
class Tier1EnumMemberPropertyPlanTest {

  private val source: String = """
    package tier1.enummemberplan

    class Crate(val label: String)

    /** The kennel's cats. */
    enum class Litter(val count: Int) {
      OREO(3),
      MYLO(0);

      /** The runt's weight, when there is one. */
      val runt: Int? get() = if (count == 0) null else count

      /** A crate minted per read. */
      val crate: Crate get() = Crate(name)

      val names: List<String> get() = List(count) { index -> "kitten ${'$'}index" }

      var nickname: String = name

      val onPet: () -> Unit get() = {}
    }
  """.trimIndent()

  @Test
  fun `a getter carries the error slot and contains a throw on both halves`() {
    val result = Tier1Harness.run(source)
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_tier1_enummemberplan__litter_get_count\")")
    assertContains(
      kotlin,
      "export_library_tier1_enummemberplan__litter_get_count(`receiver`: Int, " +
          "errorOut: COpaquePointer?): Int = try {",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"library_tier1_enummemberplan__litter_get_count\")]")
    assertContains(cs, "(int receiver, out IntPtr error);")
    // ADR-006's bare spelling and receiver name are kept.
    assertContains(cs, "public static int Count(this global::Interop.Litter litter)")
    assertContains(cs, "throw NugetErrorNative.BuildException(error);")
    assertFalse(
      cs.contains(" GetCount(this"),
      "the enum member getter must keep ADR-006's bare name",
    )
    // The KDoc reaches C#.
    assertContains(cs, "/// <summary>A crate minted per read.</summary>")
  }

  @Test
  fun `a var binds a contained setter`() {
    val result = Tier1Harness.run(source)
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")

    assertContains(
      result.generated,
      "@CName(\"library_tier1_enummemberplan__litter_set_nickname\")",
    )
    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "public static void SetNickname(this global::Interop.Litter litter, string value)",
    )
  }

  @Test
  fun `the plan decides each type the old route passed through ungated`() {
    val result = Tier1Harness.run(source)
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp

    // `Int?` rides the ADR-002 presence/value pair, not a nullable read under `returns(Int)`.
    assertContains(cs, "public static int? Runt(this global::Interop.Litter litter)")
    assertContains(
      result.generated,
      "@CName(\"library_tier1_enummemberplan__litter_get_runt_value\")",
    )
    // A class-typed member returns the owned wrapper, not a raw `IntPtr`.
    assertContains(
      cs,
      "public static global::Interop.Crate Crate(this global::Interop.Litter litter)",
    )
    // A `List<String>` member materializes through the collection helpers.
    assertContains(
      cs,
      "public static IReadOnlyList<string> Names(this global::Interop.Litter litter)",
    )
    assertFalse(cs.contains("IntPtr Crate(this"), "a class-typed member must not surface as IntPtr")

    // A lambda-typed member has no property shape: a named skip, and no member on either half.
    assertFalse(result.generated.contains("litter_get_onPet"), "no Kotlin export for onPet")
    assertFalse(cs.contains("OnPet("), "no C# member for onPet")
    val skips: List<String> = result.kspWarnings.filter {
      it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) && it.contains("onPet")
    }
    assertEquals(
      1, skips.size, "expected one named skip for onPet; kspWarnings=${result.kspWarnings}",
    )
    // ...and the hole is named on the C# enum that lost the member, as a `<remarks>` paragraph.
    val enumBlock: String = cs.substringBefore("public enum Litter").substringAfterLast("}")
    assertContains(enumBlock, "<remarks>")
    assertContains(enumBlock, "onPet")

    // `Enum<E>`'s own members stay unbridged.
    assertFalse(cs.contains(" Name(this"), "name is Enum<E>'s own member")
    assertFalse(cs.contains(" Ordinal(this"), "ordinal is Enum<E>'s own member")
  }
}
