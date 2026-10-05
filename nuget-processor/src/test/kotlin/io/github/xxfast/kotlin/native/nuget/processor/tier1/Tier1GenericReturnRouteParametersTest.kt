package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parameters and overloads on the top-level generic-return route (`fun f(...): Crate<Int>`), which
 * is not planned and hand-builds both halves.
 *
 * A nullable enum or primitive parameter used to bind as its non-null type (`Mood? mood` became
 * `Mood mood`, `Int?` became `int`), so C# could not pass null. It now crosses in the encoding the
 * ADR-062 plan routes use for the same types: a `bool` has-value slot before the value slot.
 *
 * Two overloads on this route minted one unsuffixed C entry point and failed the build with
 * `ERROR_C_ENTRY_POINT_COLLISION`. They now take the planner's ADR-090 overload number, on the
 * entry point and the extern name alike.
 */
class Tier1GenericReturnRouteParametersTest {

  @Test
  fun `a nullable enum or primitive parameter crosses with a has-value slot`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.genericparams

      enum class Mood { Purr, Hiss }

      class Crate<T>(val item: T)

      fun moodCrate(mood: Mood?): Crate<Int> = Crate(mood?.ordinal ?: -1)

      fun napCrate(naps: Int?, flag: Boolean?, label: Int): Crate<Int> = Crate(naps ?: label)
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp
    for (expected in listOf(
      Regex("""public static \S*Crate<int> MoodCrate\(\S*Mood\? mood\)"""),
      Regex(
        """MoodCrate_native\(\[MarshalAs\(UnmanagedType\.I1\)\] bool moodHasValue, int mood, """ +
            """out IntPtr error\)""",
      ),
      Regex("""MoodCrate_native\(mood\.HasValue, \(int\)mood\.GetValueOrDefault\(\), out IntPtr error\)"""),
      Regex("""public static \S*Crate<int> NapCrate\(int\? naps, bool\? flag, int label\)"""),
      Regex(
        """NapCrate_native\(\[MarshalAs\(UnmanagedType\.I1\)\] bool napsHasValue, int naps, """ +
            """\[MarshalAs\(UnmanagedType\.I1\)\] bool flagHasValue, """ +
            """\[MarshalAs\(UnmanagedType\.I1\)\] bool flag, int label, out IntPtr error\)""",
      ),
      Regex(
        """NapCrate_native\(naps\.HasValue, naps\.GetValueOrDefault\(\), flag\.HasValue, """ +
            """flag\.GetValueOrDefault\(\), label, out IntPtr error\)""",
      ),
    )) {
      assertTrue(expected.containsMatchIn(cs), "expected ${expected.pattern}; cs=$cs")
    }
    val kotlin: String = result.generated
    assertTrue(
      Regex("""moodHasValue: Boolean,\s*mood: Int,""").containsMatchIn(kotlin) &&
          "if (moodHasValue) (tier1.genericparams.Mood.entries.getOrNull(mood)" in kotlin,
      "expected the Kotlin export to read the has-value slot; generated=$kotlin",
    )
    assertTrue(
      "if (napsHasValue) naps else null" in kotlin && "if (flagHasValue) flag else null" in kotlin,
      "expected the nullable primitives decoded to null; generated=$kotlin",
    )
  }

  // Pins what this route already does with the other nullable shapes: a nullable String crosses
  // as a nullable UTF-8 string, and an exported-class parameter (nullable or not) is no
  // parameter this route can spell, so the function is skipped on both halves.
  @Test
  fun `a nullable string binds and a class parameter skips`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.genericparams

      class Cat(val name: String)

      class Crate<T>(val item: T)

      fun nameCrate(name: String?): Crate<Int> = Crate(name?.length ?: -1)

      fun catCrate(cat: Cat?): Crate<Int> = Crate(cat?.name?.length ?: -1)
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertTrue(
      Regex("""public static \S*Crate<int> NameCrate\(string\? name\)""").containsMatchIn(cs) &&
          Regex("""NameCrate_native\(\[MarshalAs\(UnmanagedType\.LPUTF8Str\)\] string\? name, """)
            .containsMatchIn(cs),
      "expected the nullable string to bind; cs=$cs",
    )
    assertFalse(
      Regex("""static [^\n(]+ CatCrate(_native)?\(""").containsMatchIn(cs),
      "a class parameter has no spelling on this route; cs=$cs",
    )
    assertFalse(
      "catCrate(" in result.generated,
      "the Kotlin half must skip it too; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { "[nuget:SKIPPED_" in it && "tier1.genericparams.catCrate" in it },
      "expected a named skip; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `overloads on the generic-return route take the planner's overload number`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.genericparams

      class Crate<T>(val item: T)

      fun count(level: Int): Int = level

      fun count(level: Int, extra: Int): Crate<Int> = Crate(level + extra)

      fun count(name: String): Crate<Int> = Crate(name.length)
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp
    for (expected in listOf(
      Regex("""public static int Count\(int level\)"""),
      Regex("""EntryPoint = "library_tier1_genericparams__count_2"\)\]\s*private static extern IntPtr Count_2_native\(int level, int extra, """),
      Regex("""public static \S*Crate<int> Count\(int level, int extra\)\s*\{\s*IntPtr nativeResult = Count_2_native\("""),
      Regex("""EntryPoint = "library_tier1_genericparams__count_3"\)\]\s*private static extern IntPtr Count_3_native\("""),
      Regex("""public static \S*Crate<int> Count\(string name\)\s*\{\s*IntPtr nativeResult = Count_3_native\("""),
    )) {
      assertTrue(expected.containsMatchIn(cs), "expected ${expected.pattern}; cs=$cs")
    }
    val kotlin: String = result.generated
    assertTrue(
      "@CName(\"library_tier1_genericparams__count_2\")" in kotlin &&
          "@CName(\"library_tier1_genericparams__count_3\")" in kotlin,
      "expected both legacy overloads to export a numbered symbol; generated=$kotlin",
    )
  }
}
