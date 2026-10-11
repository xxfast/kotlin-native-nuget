package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parameters and overloads on a top-level function returning a generic class
 * (`fun f(...): Crate<Int>`). Until ADR-208 that was a hand-built legacy route with its own
 * parameter encoding; it is on the ADR-062 plan now (the route is deleted), and these cells pin
 * that what the legacy route was taught one bug at a time is the plan's own behaviour.
 *
 * A nullable enum or primitive parameter crosses as a `bool` has-value slot before the value slot
 * (the legacy route once bound `Mood? mood` as `Mood mood`, so C# could not pass null).
 *
 * Two overloads take the planner's ADR-090 overload number, on the entry point and the extern name
 * alike (the legacy route once minted one unsuffixed C entry point for both and failed the build
 * with `ERROR_C_ENTRY_POINT_COLLISION`).
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
        """Native_MoodCrate\(\[MarshalAs\(UnmanagedType\.I1\)\] bool moodHasValue, int mood, """ +
            """out IntPtr error\)""",
      ),
      Regex(
        """Native_MoodCrate\(mood\.HasValue, \(int\)mood\.GetValueOrDefault\(\), """ +
            """out IntPtr error\)""",
      ),
      Regex("""public static \S*Crate<int> NapCrate\(int\? naps, bool\? flag, int label\)"""),
      Regex(
        """Native_NapCrate\(\[MarshalAs\(UnmanagedType\.I1\)\] bool napsHasValue, int naps, """ +
            """\[MarshalAs\(UnmanagedType\.I1\)\] bool flagHasValue, """ +
            """\[MarshalAs\(UnmanagedType\.I1\)\] bool flag, int label, out IntPtr error\)""",
      ),
      Regex(
        """Native_NapCrate\(naps\.HasValue, naps\.GetValueOrDefault\(\), flag\.HasValue, """ +
            """flag\.GetValueOrDefault\(\), label, out IntPtr error\)""",
      ),
    )) {
      assertTrue(expected.containsMatchIn(cs), "expected ${expected.pattern}; cs=$cs")
    }
    val kotlin: String = result.generated
    assertTrue(
      Regex("""moodHasValue: Boolean,\s*mood: Int,""").containsMatchIn(kotlin) &&
          "if (moodHasValue) tier1.genericparams.Mood.entries[mood] else null" in kotlin,
      "expected the Kotlin export to read the has-value slot; generated=$kotlin",
    )
    assertTrue(
      "if (napsHasValue) naps else null" in kotlin && "if (flagHasValue) flag else null" in kotlin,
      "expected the nullable primitives decoded to null; generated=$kotlin",
    )
  }

  // The other nullable shapes: a nullable String crosses as a nullable UTF-8 string, and an
  // exported-class parameter, which the legacy route could not spell and skipped on both halves,
  // binds on the plan as a borrowed nullable handle (ADR-208).
  @Test
  fun `a nullable string binds and a class parameter binds as a borrowed handle`() {
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
          Regex("""Native_NameCrate\(\[MarshalAs\(UnmanagedType\.LPUTF8Str\)\] string\? name, """)
            .containsMatchIn(cs),
      "expected the nullable string to bind; cs=$cs",
    )
    assertTrue(
      Regex("""public static \S*Crate<int> CatCrate\(\S*Cat\? cat\)""").containsMatchIn(cs) &&
          "Native_CatCrate(cat?._handle ?? NugetKotlinHandle.Null, out IntPtr error)" in cs,
      "expected the nullable class parameter to bind as a borrowed handle; cs=$cs",
    )
    assertFalse(
      "IntPtr cat" in cs,
      "a class parameter must never be a public IntPtr (issue #126); cs=$cs",
    )
    assertTrue(
      "catCrate(cat?.asStableRef<tier1.genericparams.Cat>()?.get())" in result.generated,
      "expected the Kotlin half to read the borrowed handle; generated=${result.generated}",
    )
    assertFalse(
      result.kspWarnings.any { "[nuget:SKIPPED_" in it && "tier1.genericparams.catCrate" in it },
      "expected no skip for catCrate; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `overloads returning a generic class take the planner's overload number`() {
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
      Regex(
        """EntryPoint = "library_tier1_genericparams__count_2"\)\]\s*""" +
            """private static extern IntPtr Native_Count_2\(int level, int extra, """,
      ),
      Regex(
        """public static \S*Crate<int> Count\(int level, int extra\)\s*\{\s*""" +
            """IntPtr nativeResult = Native_Count_2\(""",
      ),
      Regex(
        """EntryPoint = "library_tier1_genericparams__count_3"\)\]\s*""" +
            """private static extern IntPtr Native_Count_3\(""",
      ),
      Regex(
        """public static \S*Crate<int> Count\(string name\)\s*\{\s*""" +
            """IntPtr nativeResult = Native_Count_3\(""",
      ),
    )) {
      assertTrue(expected.containsMatchIn(cs), "expected ${expected.pattern}; cs=$cs")
    }
    val kotlin: String = result.generated
    assertTrue(
      "@CName(\"library_tier1_genericparams__count_2\")" in kotlin &&
          "@CName(\"library_tier1_genericparams__count_3\")" in kotlin,
      "expected both overloads to export a numbered symbol; generated=$kotlin",
    )
  }
}
