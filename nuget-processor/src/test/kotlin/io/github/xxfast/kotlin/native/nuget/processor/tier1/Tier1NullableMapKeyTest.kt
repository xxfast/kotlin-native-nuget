package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-083 amendment (boundary nullability part B): a `Map` whose KEY is nullable is declined at
 * EVERY position, not only at the input one ADR-083 named.
 *
 * ADR-083 left the result-position gates untouched on purpose, and the measured consequence was
 * not a consumer-side warning: the member bound, rendered `IReadOnlyDictionary<string?, int>`
 * whose body calls `NugetMarshal.ReadMap<string?, int>`, and that helper is declared
 * `where TKey : notnull`, so the generated file raised CS8714 -- an ERROR under the
 * generated-bindings csproj
 * (`<Nullable>enable</Nullable>` plus `<TreatWarningsAsErrors>true</TreatWarningsAsErrors>`). A
 * `Map<String?, Int>` return was therefore a `packNuget` abort at seven positions, which is why the
 * skip removes nothing that ever worked.
 *
 * The assertion that discriminates is the absence of `ReadMap<string?` / `ReadMap<int?` anywhere in
 * the rendered file, not just the absence of the member: a fix that suppressed the member while
 * leaving a nested `ReadMap` behind would still not compile.
 */
class Tier1NullableMapKeyTest {

  @Test
  fun `nullable map key is declined at return, property and nested positions`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablemapkey

      class Ledger {
        fun keyedScores(): Map<String?, Int> = mapOf(null to 1)

        val tallies: Map<Int?, String> = emptyMap()

        fun nested(): List<Map<String?, Int>> = emptyList()

        fun keptScores(): Map<String, Int> = mapOf("windowsill" to 2)

        // A nullable VALUE is not the declined slot and must keep binding.
        fun keptValues(): Map<String, Int?> = mapOf("windowsill" to null)
      }

      fun topKeyed(): Map<String?, Int> = mapOf(null to 1)
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the declined members to leave compilable Kotlin; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    // Matched on the declaration text, not the bare name: the skip REMARK names the member too.
    assertFalse("KeyedScores(" in cs, "a nullable-key map return must not bind")
    assertFalse("Tallies\n" in cs, "a nullable-key map property must not bind")
    assertFalse("Nested(" in cs, "a nullable-key map nested one level down must not bind")
    assertFalse("TopKeyed(" in cs, "a nullable-key map at a top-level function must not bind")
    // The load-bearing one: no `where TKey : notnull` violation survives anywhere in the file.
    assertFalse("ReadMap<string?" in cs, "CS8714: `ReadMap<string?, ...>` must never be rendered")
    assertFalse("ReadMap<int?" in cs, "CS8714: `ReadMap<int?, ...>` must never be rendered")
    // The survivors around them, so the skip is not a blanket map refusal.
    assertTrue("KeptScores" in cs, "a non-null-key map must keep binding")
    assertTrue("KeptValues" in cs, "a nullable map VALUE is a different slot and must keep binding")

    // One named diagnostic per declined member, attributed to the KEY rather than to whichever slot
    // is listed first, and carrying the dictionary-cannot-hold-null hint.
    listOf("keyedScores", "tallies", "nested", "topKeyed").forEach { member ->
      assertTrue(
        result.kspWarnings.any { warning -> member in warning && "null key" in warning },
        "expected a named nullable-map-key skip for `$member`; got: ${result.kspWarnings}",
      )
    }
    assertTrue(
      result.kspWarnings.any { warning -> "key type String?" in warning },
      "expected the skip to name the key type; got: ${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.any { warning -> "key type Int?" in warning },
      "expected the Int? key named too, so the rule reads as about nullability not about String",
    )
  }

  @Test
  fun `the suspend and Flow routes inherit the same refusal`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablemapkeyasync

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      class Ledger {
        suspend fun keyed(): Map<String?, Int> = mapOf(null to 1)

        fun stream(): Flow<Map<String?, Int>> = flowOf()
      }

      suspend fun topKeyedAsync(): Map<String?, Int> = mapOf(null to 1)
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the declined async members to leave compilable Kotlin; " +
          "got: ${result.compileErrors}",
    )

    // These routes are the reason `declinesNullableMapKey` is consulted inside the RECURSIVE
    // `isBridgeableComponent` rather than at the four outermost call sites: the spike showed all
    // three rendering `ReadMap<string?, int>` today.
    val cs: String = result.generatedCSharp
    // Matched on the declaration text rather than the bare name: the skip REMARK names the Kotlin
    // member, so `"TopKeyedAsync" in cs` is true from the remark alone (and "KeyedAsync" is a
    // substring of it), which would make a bare-name assertion pass for the wrong reason.
    assertFalse("KeyedAsync(" in cs, "a nullable-key map behind `suspend` must not bind")
    assertFalse("Stream()" in cs, "a nullable-key map as a Flow element must not bind")
    assertFalse("TopKeyedAsync(" in cs, "a top-level suspend one must not bind either")
    assertFalse("ReadMap<string?" in cs, "CS8714: `ReadMap<string?, ...>` must never be rendered")
  }
}
