package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ADR-202: the runtime-owned routes (`nuget_suspend_func{0..3}_invoke`, `nuget_stateflow_collect`)
 * classify with the module's `nugetMappedType` once the generated code installed it. The install
 * runs at every Kotlin mint site of a handle C# can later invoke one of those routes on, as the
 * export's first statement, so it has run before C# holds the handle:
 *
 *  - a class's suspend-lambda property getter (the only producer of `KotlinSuspendFunc` /
 *    `KotlinSuspendAction`);
 *  - a suspend member or top-level function returning `StateFlow` (ADR-068's awaited flow);
 *  - a function returning a held `MutableStateFlow` (ADR-071's acquire export).
 *
 * Every other export is unchanged: a plain lambda getter, a non-StateFlow suspend return and a
 * plain `Flow` return (which collects through its own generated, already-mapped `_collect`).
 */
class Tier1RuntimeRouteMappedTypeTest {

  private val install: String = "nugetInstallMappedType(::nugetMappedType)"

  private val fixture: String = """
    package tier1.runtimeroutemapping

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    class LitterTruck(val catName: String) {
      val onDeliver: suspend () -> String = { "litter for ${'$'}catName" }
      val onHonk: () -> String = { "honk for ${'$'}catName" }

      suspend fun awaitRoute(): StateFlow<String> = MutableStateFlow("route for ${'$'}catName")
      suspend fun awaitDriver(): String = "driver for ${'$'}catName"
      suspend fun awaitStops(): Flow<String> = flowOf("stop for ${'$'}catName")

      fun gauge(): MutableStateFlow<Int> = MutableStateFlow(3)
    }

    suspend fun watchDepot(): StateFlow<Int> = MutableStateFlow(7)

    suspend fun countDepot(): Int = 7
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(fixture, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  @Test
  fun `the fixture compiles against the runtime surface`() {
    assertTrue(
      result.compiledClean,
      "expected the install calls to compile; got: ${result.compileErrors} ${result.kspErrors}",
    )
  }

  @Test
  fun `a suspend lambda property getter installs the module classifier first`() {
    assertInstalledFirst(exportBody("_get_onDeliver"))
  }

  @Test
  fun `a plain lambda property getter installs nothing`() {
    assertNoInstall(exportBody("_get_onHonk"))
  }

  @Test
  fun `a suspend member returning StateFlow installs the module classifier first`() {
    assertInstalledFirst(exportBody("_awaitRoute_async"))
  }

  @Test
  fun `a top-level suspend function returning StateFlow installs the module classifier first`() {
    assertInstalledFirst(exportBody("_watchDepot_async"))
  }

  @Test
  fun `a held MutableStateFlow acquire installs the module classifier first`() {
    assertInstalledFirst(exportBody("_gauge"))
  }

  @Test
  fun `suspend returns that never reach a runtime-owned route install nothing`() {
    assertNoInstall(exportBody("_awaitDriver_async"))
    assertNoInstall(exportBody("_awaitStops_async"))
    assertNoInstall(exportBody("_countDepot_async"))
  }

  @Test
  fun `the install is imported from the runtime`() {
    assertTrue(
      "import io.github.xxfast.kotlin.native.nuget.runtime.nugetInstallMappedType" in
        result.generated,
      result.generated,
    )
  }

  /**
   * The whole text of the one export whose Kotlin function name ends with [suffix], up to the
   * blank line KotlinPoet puts after every function. A single-`return` export renders as an
   * expression body (`= ...`), so the text is not assumed to contain a `{`.
   */
  private fun exportBody(suffix: String): String {
    val generated: String = result.generated
    val header: Regex = Regex("""fun (export_\w*${Regex.escape(suffix)})\(""")
    val matches: List<MatchResult> = header.findAll(generated).toList()
    if (matches.size != 1) {
      fail("expected exactly one export ending in $suffix, found ${matches.size}: $generated")
    }
    val start: Int = matches.single().range.first
    val end: Int = generated.indexOf("\n\n", start).takeIf { it >= 0 } ?: generated.length
    return generated.substring(start, end)
  }

  private fun assertInstalledFirst(function: String) {
    val body: String = function.substringAfter("{\n", missingDelimiterValue = "")
    assertTrue(
      body.trimStart().startsWith(install),
      "expected `$install` as the first statement; function=$function",
    )
  }

  private fun assertNoInstall(body: String) {
    assertFalse(install in body, "expected no install; body=$body")
  }
}
