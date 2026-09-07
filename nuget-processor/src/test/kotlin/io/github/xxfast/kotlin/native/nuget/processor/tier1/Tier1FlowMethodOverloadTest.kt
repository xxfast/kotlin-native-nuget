package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #97: the Flow/StateFlow method route composed its `_collect`, `_value`, `_has_value` and
 * `_set_value` entry points off the bare method name, so two overloads returning a flow asked for
 * the same C symbol and the forward ABI check failed on a duplicate import. The planner already
 * numbers these methods (they land in the catalog as `FLOW_PROTOCOL` skips with the suffixed
 * symbol); both legacy emitters now read that suffix back, matching ADR-090's scheme.
 *
 * The end-to-end half lives in `CatRadio` / `FlowMethodOverloadTests.cs`.
 */
class Tier1FlowMethodOverloadTest {

  @Test
  fun `flow-returning overloads get numbered entry points on every sibling export`() {
    val result = Tier1Harness.run(
      """
      package tier1.flowoverload

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flow

      class Radio {
        private val volumes = MutableStateFlow(3)
        fun play(station: String): StateFlow<String> = MutableStateFlow(station)
        fun play(channel: Int): StateFlow<String> = MutableStateFlow("channel ${'$'}channel")
        fun schedule(station: String): Flow<String> = flow { emit(station) }
        fun schedule(channel: Int): Flow<String> = flow { emit("channel ${'$'}channel") }
        fun volume(station: String): MutableStateFlow<Int> = volumes
        fun volume(channel: Int): MutableStateFlow<Int> = volumes
        fun maybePlay(station: String): StateFlow<String>? = null
        fun maybePlay(channel: Int): StateFlow<String>? = null
      }
      """.trimIndent(),
      // `Flow` and `StateFlow` must resolve for the flow route to be taken at all; with only
      // kotlin-stdlib on the KSP libraries path every method here is skipped as an unsupported type.
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected every overload pair to compile; got: ${result.compileErrors} ${result.kspErrors}",
    )

    val kotlin: String = result.generated
    listOf(
      "radio_play_collect", "radio_play_2_collect",
      "radio_play_value", "radio_play_2_value",
      "radio_schedule_collect", "radio_schedule_2_collect",
      "radio_volume_collect", "radio_volume_2_collect",
      "radio_volume_value", "radio_volume_2_value",
      "radio_volume_set_value", "radio_volume_2_set_value",
      "radio_maybePlay_collect", "radio_maybePlay_2_collect",
      "radio_maybePlay_has_value", "radio_maybePlay_2_has_value",
    ).forEach { entryPoint ->
      assertContains(kotlin, "@CName(\"$entryPoint\")")
      assertEquals(
        1,
        Regex("EntryPoint = \"$entryPoint\"").findAll(result.generatedCSharp).count(),
        "expected exactly one C# import for $entryPoint; generatedCSharp=${result.generatedCSharp}",
      )
    }
  }

  /** Regression: a class with one flow method per name keeps its unsuffixed naming byte for byte. */
  @Test
  fun `a flow method without overloads keeps its unsuffixed naming`() {
    val result = Tier1Harness.run(
      """
      package tier1.flownooverload

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      class Radio {
        fun play(station: String): StateFlow<String> = MutableStateFlow(station)
      }
      """.trimIndent(),
      // `Flow` and `StateFlow` must resolve for the flow route to be taken at all; with only
      // kotlin-stdlib on the KSP libraries path every method here is skipped as an unsupported type.
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected the plain class to compile; got: ${result.compileErrors}")
    assertContains(result.generated, "@CName(\"radio_play_collect\")")
    assertContains(result.generated, "@CName(\"radio_play_value\")")
    assertContains(result.generatedCSharp, "Native_PlayCollect(")
    assertContains(result.generatedCSharp, "Native_PlayValue(")
  }
}
