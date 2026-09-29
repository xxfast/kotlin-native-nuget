package io.github.xxfast.kotlin.native.nuget.processor.tier1


import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP line 24: a camelCase property on an enum aborted generation. The ADR-006 enum route's C#
 * half lowercased the Kotlin property name into the entry point (`mood_get_issecond`) while the
 * Kotlin half exported it verbatim (`mood_get_isSecond`), so the ADR-055 contract check failed the
 * whole module with `Forward ABI missing Kotlin export`. Never `is`-specific and never
 * `Boolean`-specific: `displayName: String` aborted the same way.
 *
 * Folded in on the same route: the enum getter's `bool` extern had no
 * `[return: MarshalAs(UnmanagedType.I1)]`, which every other route emits; and a property declared
 * in an enum's `companion object` was a named skip; since the ADR-006 amendment it binds, instead of
 * vanishing silently.
 */
class Tier1EnumCamelCasePropertyTest {

  private val source: String =
    """
    package tier1.enumcamelcase

    enum class Mood(val isSecond: Boolean, val displayName: String) {
      FIRST(false, "first"),
      SECOND(true, "second");

      val isFirst: Boolean get() = this == FIRST

      var isLoud: Boolean = false

      fun isLoudNow(): Boolean = isLoud

      companion object {
        val isDefault: Boolean = true
        fun fallback(): Mood = FIRST
      }
    }

    object Aviary {
      enum class Kind(val isOwl: Boolean) {
        OWL(true),
        WREN(false);

        val isSmall: Boolean get() = this == WREN
      }
    }
    """.trimIndent()

  private val result: Tier1Result by lazy { Tier1Harness.run(source) }

  private val prefix: String = "library_tier1_enumcamelcase__"

  @Test
  fun `every camelCase enum property generates and compiles`() {
    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    assertTrue(result.kspErrors.isEmpty(), "got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
  }

  @Test
  fun `the C# entry point keeps the Kotlin spelling of the export`() {
    val kotlin: String = result.generated
    val cs: String = result.generatedCSharp
    for (entry in listOf(
      "mood_get_isSecond", // constructor `val isX: Boolean`
      "mood_get_displayName", // camelCase, neither `is` nor `Boolean`
      "mood_get_isFirst", // body getter
      "mood_get_isLoud", // body `var`
      "aviary_kind_get_isOwl", // nested enum under an object, constructor property
      "aviary_kind_get_isSmall", // nested enum under an object, body getter
    )) {
      assertContains(kotlin, "@CName(\"$prefix$entry\")")
      assertContains(cs, "EntryPoint = \"$prefix$entry\"")
      assertFalse(
        cs.contains("EntryPoint = \"$prefix${entry.lowercase()}\""),
        "the C# entry point must not lowercase the Kotlin name: $entry",
      )
    }
    // ADR-006 amendment: the plan spells the receiver type fully qualified (the same C# type), and
    // keeps the bare getter name and the lowercased receiver parameter name.
    assertContains(cs, "public static bool IsSecond(this global::Interop.Mood mood)")
    assertContains(cs, "public static string DisplayName(this global::Interop.Mood mood)")
    assertContains(cs, "public static bool IsFirst(this global::Interop.Mood mood)")
    assertContains(cs, "public static bool IsLoud(this global::Interop.Mood mood)")
    // ADR-006 amendment: a body `var` binds its setter now; it was silently dropped before.
    assertContains(cs, "public static void SetIsLoud(this global::Interop.Mood mood, bool value)")
    assertContains(cs, "public static bool IsOwl(this global::Interop.Aviary.Kind aviarykind)")
    assertContains(cs, "public static bool IsSmall(this global::Interop.Aviary.Kind aviarykind)")
  }

  @Test
  fun `every bool enum getter marshals its return as a 1-byte bool`() {
    val cs: String = result.generatedCSharp
    val lines: List<String> = cs.lines().map { it.trim() }
    for (name in listOf("IsSecond", "IsFirst", "IsLoud", "IsOwl", "IsSmall")) {
      val extern: Int = lines.indexOfFirst { line ->
        line.startsWith("private static extern bool Native_") &&
            line.endsWith("Get$name(int receiver, out IntPtr error);")
      }
      assertTrue(extern > 0, "no extern for $name in:\n$cs")
      assertEquals("[return: MarshalAs(UnmanagedType.I1)]", lines[extern - 1], "for $name")
    }
    // A non-bool getter gets no bool marshal.
    val displayName: Int = lines.indexOf(
      "private static extern IntPtr Native_MoodGetDisplayName(int receiver, out IntPtr error);",
    )
    assertTrue(displayName > 0, "no extern for DisplayName in:\n$cs")
    assertFalse(lines[displayName - 1].contains("UnmanagedType.I1"))
  }

  /** ADR-006 amendment: a companion `val` binds as a static property of `MoodExtensions` now. */
  @Test
  fun `an enum companion property binds as a static of the extensions class`() {
    val skips: List<String> = result.kspWarnings.filter { it.contains("Mood.Companion.isDefault") }
    assertTrue(skips.isEmpty(), "kspWarnings=${result.kspWarnings}")
    assertContains(result.generated, "@CName(\"${prefix}mood_companion_get_isDefault\")")
    assertContains(result.generatedCSharp, "public static bool IsDefault")
  }

  /**
   * ADR-006 amendment: an enum member function and an enum companion function bind (as
   * `MoodExtensions.IsLoudNow(this Mood mood)` and the static `MoodExtensions.Fallback()`), so
   * neither is named any more. `Tier1EnumMemberFunctionPlanTest` pins the shapes that stay skips.
   */
  @Test
  fun `an enum member or companion function binds on the extensions class`() {
    assertContains(result.generated, "Mood.entries[receiver].isLoudNow()")
    assertContains(result.generatedCSharp, "public static bool IsLoudNow(this global::Interop.Mood mood)")
    assertContains(result.generatedCSharp, "public static global::Interop.Mood Fallback()")
    val named: List<String> = result.kspWarnings
      .filter { it.contains("Mood.isLoudNow") || it.contains("Mood.Companion.fallback") }
    assertTrue(named.isEmpty(), "kspWarnings=${result.kspWarnings}")
  }
}
