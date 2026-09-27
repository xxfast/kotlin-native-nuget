package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
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
 * in an enum's `companion object`, which no route declares, is now a named skip instead of
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
    assertContains(cs, "public static bool IsSecond(this Mood mood)")
    assertContains(cs, "public static string DisplayName(this Mood mood)")
    assertContains(cs, "public static bool IsFirst(this Mood mood)")
    // ADR-006's read-only extension spelling: a body `var` binds getter-only.
    assertContains(cs, "public static bool IsLoud(this Mood mood)")
    assertContains(cs, "public static bool IsOwl(this Aviary.Kind aviarykind)")
    assertContains(cs, "public static bool IsSmall(this Aviary.Kind aviarykind)")
  }

  @Test
  fun `every bool enum getter marshals its return as a 1-byte bool`() {
    val cs: String = result.generatedCSharp
    val lines: List<String> = cs.lines().map { it.trim() }
    for (name in listOf("IsSecond", "IsFirst", "IsLoud", "IsOwl", "IsSmall")) {
      val extern: Int = lines.indexOf("private static extern bool Native_Get$name(int ordinal);")
      assertTrue(extern > 0, "no extern for $name in:\n$cs")
      assertEquals("[return: MarshalAs(UnmanagedType.I1)]", lines[extern - 1], "for $name")
    }
    // A non-bool getter gets no bool marshal.
    val displayName: Int =
      lines.indexOf("private static extern IntPtr Native_GetDisplayName(int ordinal);")
    assertTrue(displayName > 0, "no extern for DisplayName in:\n$cs")
    assertFalse(lines[displayName - 1].contains("UnmanagedType.I1"))
  }

  @Test
  fun `an enum companion property is a named skip, not a silent drop`() {
    val skips: List<String> = result.kspWarnings.filter {
      it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
          it.contains("Mood.Companion.isDefault")
    }
    assertEquals(1, skips.size, "kspWarnings=${result.kspWarnings}")
    assertContains(skips.single(), "companion object of enum class `Mood`")
    assertFalse(result.generated.contains("isDefault"))
    assertFalse(result.generatedCSharp.contains("IsDefault"))
  }

  /**
   * An enum member function and an enum companion function are bound by no route (binding them as
   * extension methods is a separate ROADMAP line), but each is now named once rather than
   * vanishing. The compiler's own `values()` / `valueOf()` and `Any`'s members are never named.
   */
  @Test
  fun `an enum member or companion function is a named skip, not a silent drop`() {
    assertFalse(result.generated.contains("isLoudNow"))
    assertFalse(result.generatedCSharp.contains("IsLoudNow"))
    assertFalse(result.generated.contains("fallback"))

    val skips: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.SKIPPED_ENUM_MEMBER_FUNCTION.name) }
    val member: List<String> = skips.filter { it.contains("tier1.enumcamelcase.Mood.isLoudNow") }
    assertEquals(1, member.size, "kspWarnings=${result.kspWarnings}")
    assertContains(member.single(), "member function of enum class `Mood`")
    assertContains(member.single(), "instance properties only today")
    val companion: List<String> =
      skips.filter { it.contains("tier1.enumcamelcase.Mood.Companion.fallback") }
    assertEquals(1, companion.size, "kspWarnings=${result.kspWarnings}")
    assertContains(companion.single(), "companion object of enum class `Mood`")
    // Exactly those two: nothing the compiler wrote on the enum (values, valueOf, toString, ...).
    assertEquals(2, skips.size, "kspWarnings=${result.kspWarnings}")
  }
}
