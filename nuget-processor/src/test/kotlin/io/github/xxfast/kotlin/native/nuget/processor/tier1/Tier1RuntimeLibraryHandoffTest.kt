package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-127 seam 1: the fixed `nuget_*` block stops being generated and comes from the
 * `:nuget-runtime` klib instead.
 *
 * The fixture below is deliberately rich: it opens every gate at `NugetProcessor.kt:1880-1990`
 * (handles, errors, scalars, the three collection kinds, a lambda parameter, the scope/job
 * helpers, `StateFlow`, the .NET ticks pair), so each absence assertion here is a real absence
 * rather than a gate that never opened for this fixture in the first place. One assertion per
 * family, sampling the ADR's 66-name table.
 *
 * These assert on **text**, not on the compile step: the generated file's calls now resolve
 * against the runtime module, which a JVM compile of this harness cannot see until
 * `Tier1RuntimeStub` lands beside [Tier1CinteropStub].
 */
class Tier1RuntimeLibraryHandoffTest {

  private val fixture = """
    package tier1.runtimehandoff

    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlin.time.Duration
    import kotlin.time.Instant

    class Depot(val name: String) {
      val level: StateFlow<Int> = MutableStateFlow(0)
      val stamp: Instant = Instant.fromEpochMilliseconds(0)
      val window: Duration = Duration.ZERO
      val onIdle: () -> Unit = {}

      fun levels(): MutableStateFlow<Int> = MutableStateFlow(0)
      fun labels(): List<String> = listOf(name)
      fun index(): Map<String, Int> = mapOf(name to 1)
      fun tags(): Set<String> = setOf(name)
      fun accept(items: List<String>): Int = items.size
      fun watch(onTick: () -> Unit) { onTick() }
      suspend fun load(id: Int): String = "item-${'$'}id"
    }
    """.trimIndent()

  private fun generated(): String = Tier1Harness.run(
    fixture,
    fileName = "Depot.kt",
    // `StateFlow` must resolve for the flow route to be taken at all; with only kotlin-stdlib on
    // the KSP libraries path the member is skipped and the coroutine gate never opens.
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  ).generated

  /** Sanity: the fixture still generates, so every absence below is about the runtime move. */
  @Test
  fun `the fixture still exports its own declarations`() {
    val generated: String = generated()
    assertTrue(
      "@CName(\"depot_labels\")" in generated,
      "expected the fixture's own exports to survive the runtime move; generated=\n$generated",
    )
  }

  @Test
  fun `the handle table is no longer generated`() {
    val generated: String = generated()
    assertFalse(
      "object NugetHandles" in generated,
      "expected NugetHandles to come from the runtime, not be regenerated; generated=\n$generated",
    )
    assertFalse(
      "@CName(\"nuget_dispose\")" in generated,
      "expected nuget_dispose to live in the runtime; generated=\n$generated",
    )
  }

  @Test
  fun `the error carrier is no longer generated`() {
    val generated: String = generated()
    assertFalse(
      "class NugetError" in generated,
      "expected NugetError to come from the runtime; generated=\n$generated",
    )
  }

  @Test
  fun `the scalar wrap and unwrap exports are no longer generated`() {
    val generated: String = generated()
    assertFalse(
      "@CName(\"nuget_unwrap_" in generated,
      "expected the scalar unwrap exports to live in the runtime; generated=\n$generated",
    )
  }

  @Test
  fun `the collection exports are no longer generated`() {
    val generated: String = generated()
    assertFalse(
      "@CName(\"nuget_list_" in generated,
      "expected the list exports to live in the runtime; generated=\n$generated",
    )
    assertFalse(
      "@CName(\"nuget_map_" in generated,
      "expected the map exports to live in the runtime; generated=\n$generated",
    )
    assertFalse(
      "@CName(\"nuget_set_" in generated,
      "expected the set exports to live in the runtime; generated=\n$generated",
    )
  }

  @Test
  fun `the callback exports are no longer generated`() {
    val generated: String = generated()
    assertFalse(
      "@CName(\"nuget_func0_invoke\")" in generated,
      "expected the lambda-invoke exports to live in the runtime; generated=\n$generated",
    )
  }

  @Test
  fun `the coroutine exports are no longer generated`() {
    val generated: String = generated()
    assertFalse(
      "@CName(\"nuget_scope_create\")" in generated,
      "expected the scope exports to live in the runtime; generated=\n$generated",
    )
    assertFalse(
      "@CName(\"nuget_stateflow_collect\")" in generated,
      "expected the state-flow exports to live in the runtime; generated=\n$generated",
    )
  }

  /** The ticks pair carries no `@CName`; it is a Kotlin-surface move, so pin the definition. */
  @Test
  fun `the dot-net ticks conversions are no longer defined`() {
    val generated: String = generated()
    assertFalse(
      "fun Instant.toDotNetTicks" in generated,
      "expected the Instant ticks conversion to live in the runtime; generated=\n$generated",
    )
    assertFalse(
      "fun Duration.toDotNetTicks" in generated,
      "expected the Duration ticks conversion to live in the runtime; generated=\n$generated",
    )
  }

  /**
   * The ROADMAP's missing structural test, worth adding now that the fixed block leaves the file:
   * every handle must go through ADR-120's counted table, and the only bare `StableRef.create(`
   * left in a generated file was inside that block.
   */
  @Test
  fun `no bare StableRef create survives in the generated file`() {
    val generated: String = generated()
    assertFalse(
      "StableRef.create(" in generated,
      "expected every handle to go through NugetHandles.retain; generated=\n$generated",
    )
  }

  @Test
  fun `the members it still calls are imported from the runtime package`() {
    val generated: String = generated()
    listOf("NugetHandles", "buildError", "NugetRuntimeAbi1").forEach { member ->
      assertTrue(
        generated.importsFromRuntime(member),
        "expected `$member` imported from the runtime package; generated=\n$generated",
      )
    }
  }

  @Test
  fun `the file opt-in carries the runtime marker`() {
    val generated: String = generated()
    val optIn: String = generated.substringBefore("\npackage ")
    assertTrue(
      "OptIn" in optIn && "NugetRuntimeApi" in optIn,
      "expected the file-level OptIn to name NugetRuntimeApi; header=\n$optIn",
    )
  }

  /** ADR-127's skew guard: the one-line compile-time anchor, spelled exactly as the ADR does. */
  @Test
  fun `the generated file anchors the runtime ABI major`() {
    val generated: String = generated()
    assertTrue(
      "private val nugetRuntimeAbi: NugetRuntimeAbi1 = NugetRuntimeAbi1" in generated,
      "expected the ADR-127 ABI anchor line; generated=\n$generated",
    )
  }
}

/** True when [member] is imported from the runtime package, member-wise or via a star import. */
private fun String.importsFromRuntime(member: String): Boolean {
  val prefix = "import io.github.xxfast.kotlin.native.nuget.runtime."
  return lineSequence().any { line ->
    val trimmed: String = line.trim()
    trimmed == "$prefix$member" || trimmed == "$prefix*"
  }
}
