package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #230: a `data class` sealed arm whose constructor takes a `Flow<T>`/`StateFlow<T>` used to
 * export the compiler-synthesized `componentN()` for exactly that parameter, on both halves
 * (`public KotlinFlow<int> Component2()` in C#, `@CName("node_session_component2_collect")` in
 * Kotlin). Every other component was filtered upstream, silently, because the ordinary member
 * routes apply a synthetic-member filter the arm flow selector never had.
 *
 * The arm and the non-sealed control below share the same parameter list, so the assertions read as
 * one rule: a `componentN` is filtered before skip reporting, whatever its type and whatever route
 * that type would otherwise take.
 */
class Tier1SealedArmFlowComponentTest {

  private val fixture: String = """
    package tier1.armdestructure

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.StateFlow

    sealed class Node {
      data class Session(
        val id: Int,
        val events: Flow<Int>,
        val state: StateFlow<Int>,
      ) : Node()
    }

    // The control the issue asks for: the same parameter list off a non-sealed data class, which
    // never leaked its components.
    data class Feed(val id: Int, val events: Flow<Int>)
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "NodeSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  @Test
  fun `an arm's flow parameters render as properties without their components`() {
    val result = run()

    val missing: List<String> = listOf(
      "public KotlinFlow<int> Events",
      "public KotlinStateFlow<int> State",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(missing.isEmpty(), "expected the arm's flow properties; missing: $missing")

    val leaked: List<String> = result.generatedCSharp.lines()
      .filter { line -> line.contains("Component") }

    assertTrue(
      leaked.isEmpty(),
      "expected no destructuring operator in the generated C#; got: $leaked",
    )
  }

  @Test
  fun `no component is exported and none is named a skip`() {
    val result = run()

    val exported: List<String> = result.generated.lines()
      .filter { line -> line.contains("@CName(") && line.contains("component") }

    assertTrue(exported.isEmpty(), "expected no componentN export; got: $exported")

    // The fixture's package name avoids the substring on purpose, so this reads the diagnostics
    // and nothing else. The constructor's own Flow-parameter skip is expected and unrelated.
    val named: List<String> = result.kspWarnings.filter { it.contains("component") }

    assertTrue(named.isEmpty(), "expected no componentN diagnostic; got: $named")
  }
}
