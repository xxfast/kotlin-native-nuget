package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An enum parameter on a top-level function that returns a generic class at a closed type
 * (`fun reaction(mood: Mood): Crate<Int>`) used to fail the whole build with
 * `ERROR_UNSUPPORTED_ENUM_PARAMETER_ROUTE`. The Kotlin export already took the ordinal and decoded
 * it; only the C# half's hand-built native call passed the enum uncast. It binds now, and the kind
 * is gone. Since ADR-208 the function is on the ADR-062 plan (the hand-built route is deleted), so
 * the ordinal cast is the plan's own. The overload beside it (`reaction(level: Int): Int`) takes
 * the next overload suffix (`Tier1GenericReturnRouteParametersTest` covers two generic-returning
 * overloads).
 *
 * The collection-return cells pin that the six collection arms that also raised the kind were
 * unreachable: a collection return is plan-owned, so each of these binds on the plan route.
 */
class Tier1EnumParameterRouteTest {

  @Test
  fun `an enum parameter on a generic-returning top-level function binds beside its overload`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.enumparam

      enum class Mood { Purr, Hiss }

      class Crate<T>(val item: T)

      fun reaction(mood: Mood): Crate<Int> = Crate(mood.ordinal)

      fun reaction(level: Int): Int = level

      fun standoff(oreo: Mood, mylo: Mood): Crate<Int> = Crate(oreo.ordinal * 10 + mylo.ordinal)
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertTrue(
      Regex("""Native_Reaction\(\(int\)mood, out IntPtr error\)""").containsMatchIn(cs),
      "expected the enum cast down to its ordinal at the native call; cs=$cs",
    )
    assertTrue(
      Regex("""Native_Standoff\(\(int\)oreo, \(int\)mylo, out IntPtr error\)""")
        .containsMatchIn(cs),
      "expected both enum parameters cast at the native call; cs=$cs",
    )
    assertTrue(
      Regex("""public static \S*Crate<int> Reaction\(\S*Mood mood\)""").containsMatchIn(cs),
      "expected the public method to keep the C# enum; cs=$cs",
    )
    assertTrue(
      Regex("""public static int Reaction\(int level\)""").containsMatchIn(cs),
      "expected the planned Int overload to keep binding; cs=$cs",
    )
    val kotlin: String = result.generated
    assertTrue(
      "tier1.enumparam.Mood.entries[mood]" in kotlin,
      "expected the Kotlin export to decode the ordinal; generated=$kotlin",
    )
  }

  @Test
  fun `an enum parameter on a collection-returning top-level function binds on the plan`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.enumparam

      enum class Mood { Purr, Hiss }

      fun moodList(mood: Mood): List<Int> = listOf(mood.ordinal)

      fun moodMutableList(mood: Mood): MutableList<Int> = mutableListOf(mood.ordinal)

      fun moodMap(mood: Mood): Map<String, Int> = mapOf(mood.name to mood.ordinal)

      fun moodMutableMap(mood: Mood): MutableMap<String, Int> = mutableMapOf(mood.name to 1)

      fun moodSet(mood: Mood): Set<Int> = setOf(mood.ordinal)

      fun moodMutableSet(mood: Mood): MutableSet<Int> = mutableSetOf(mood.ordinal)
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp
    for (method in listOf(
      "MoodList", "MoodMutableList", "MoodMap", "MoodMutableMap", "MoodSet", "MoodMutableSet",
    )) {
      assertTrue(
        Regex("""public static [^\n(]+ $method\(\S*Mood mood\)""").containsMatchIn(cs),
        "expected $method to bind with its C# enum parameter; cs=$cs",
      )
    }
  }
}
