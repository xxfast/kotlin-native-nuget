package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A spike that pins what a `var` property of a flow type does TODAY, measured rather than read, so
 * a later fix has a cell to flip.
 *
 * A `var source: Flow<Int>` and a `var source: StateFlow<Int>` on a class each bind exactly as the
 * `val` does: a get-only `KotlinFlow<int>` / `KotlinStateFlow<int>` and no setter export. Nothing
 * names the setter that C# cannot reach, which is the silent partial skip ADR-075 (C3) rejected
 * for every other property type and ADR-064 exists to prevent. No ADR decides this cell: ADR-071's
 * setter is `MutableStateFlow.value`, not the property's own.
 *
 * It is older than ADR-210: the flow property route (`flowProperty`, `addFlowPropertyExports`) has
 * never read `isMutable`, and `ForwardPropertyPlanner.recordDropped` returns for a legacy-routed
 * protocol before it considers a setter.
 *
 * The two honest ways out, either of which rewrites the `DEFECT` cells: name the setter
 * (`SKIPPED_UNSUPPORTED_INPUT`, "its setter is not generated because ..."), as a `var` lambda
 * property already is; or bind it over the `IAsyncEnumerable<int>` ADR-210 gave a `Flow<T>`
 * parameter, which a `StateFlow<T>` still could not use.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without `Flow` on
 * the KSP libraries path the property is an error type and every cell proves nothing.
 *
 * Mylo's feeder streams kibble counts; Oreo watches the gauge.
 */
class Tier1FlowVarPropertySetterTest {

  private val fixture: String = """
    package tier1.flowvar

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    class Feeder {
      var source: Flow<Int> = flowOf(1)
      var bowls: Int = 2
    }

    class Gauge {
      var source: StateFlow<Int> = MutableStateFlow(0)
    }

    class Tap {
      val source: Flow<Int> = flowOf(3)
    }
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "FlowVarSample.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  /** The C# property [declaration] of [owner], from its declaration line to its closing brace. */
  private fun property(owner: String, declaration: String): List<String> {
    val lines: List<String> = result.generatedCSharp.lines()
    val ownerStart: Int = lines.indexOfFirst { line -> line.startsWith("    public class $owner ") }
    assertTrue(ownerStart >= 0, "expected a C# class $owner")
    val start: Int = (ownerStart until lines.size).firstOrNull { index ->
      lines[index] == "        $declaration"
    } ?: -1
    assertTrue(
      start >= 0,
      "expected `$declaration` on $owner; got=" +
          lines.drop(ownerStart).filter { line -> line.startsWith("        public ") }.take(12),
    )
    val end: Int = (start until lines.size).first { index -> lines[index] == "        }" }
    return lines.subList(start, end + 1)
  }

  private fun List<String>.hasAccessor(name: String): Boolean =
    any { line -> line.trim() == name }

  /** The `@CName` exports whose entry point mentions [fragment]. */
  private fun exports(fragment: String): List<String> = result.generated.lines()
    .filter { line -> line.startsWith("@CName(") && fragment in line }
    .map { line -> line.substringAfter("@CName(\"").substringBefore("\")") }

  @Test
  fun `the fixture compiles, and an ordinary var beside the flow keeps its setter`() {
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compileErrors.isEmpty(), "compileErrors=${result.compileErrors}")

    // The control: a setter IS what a `var` renders here, or every absence below is vacuous.
    val bowls: List<String> = property("Feeder", "public int Bowls")
    assertTrue(bowls.hasAccessor("get") && bowls.hasAccessor("set"), "got=$bowls")
    assertEquals(
      listOf("library_tier1_flowvar__feeder_set_bowls"),
      exports("feeder_set_"),
    )
  }

  @Test
  fun `a val Flow property binds get-only and is not named`() {
    val source: List<String> = property("Tap", "public KotlinFlow<int> Source")

    assertTrue(source.hasAccessor("get"), "got=$source")
    assertFalse(source.hasAccessor("set"), "got=$source")
    assertEquals(listOf("library_tier1_flowvar__tap_get_source_collect"), exports("tap_get_"))
    assertEquals(emptyList(), exports("tap_set_"))
  }

  @Test
  fun `DEFECT a var Flow property binds get-only and its setter is dropped with no diagnostic`() {
    val source: List<String> = property("Feeder", "public KotlinFlow<int> Source")

    // The same shape as the `val` on `Tap`: the getter and the one collect export.
    assertTrue(source.hasAccessor("get"), "got=$source")
    assertFalse(source.hasAccessor("set"), "the C# property is read-only today; got=$source")
    assertEquals(
      listOf("library_tier1_flowvar__feeder_get_source_collect"),
      exports("feeder_get_source"),
    )
    assertEquals(emptyList(), exports("feeder_set_source"), "no Kotlin setter export either")
  }

  @Test
  fun `DEFECT a var StateFlow property binds get-only and its setter is dropped with no diagnostic`() {
    val source: List<String> = property("Gauge", "public KotlinStateFlow<int> Source")

    assertTrue(source.hasAccessor("get"), "got=$source")
    assertFalse(source.hasAccessor("set"), "the C# property is read-only today; got=$source")
    assertEquals(
      listOf(
        "library_tier1_flowvar__gauge_get_source_collect",
        "library_tier1_flowvar__gauge_get_source_value",
      ),
      exports("gauge_get_source"),
    )
    assertEquals(emptyList(), exports("gauge_set_source"), "no Kotlin setter export either")
  }

  // The pinned defect: nothing names either dropped setter. The fixture emits no diagnostic of any
  // kind, so this is every diagnostic there is, not a filter that could miss one. When a fix names
  // the setter (or binds it), this is the cell to rewrite.
  @Test
  fun `DEFECT no diagnostic of any kind is emitted for the fixture`() {
    assertEquals(emptyList(), result.kspErrors + result.kspWarnings)
  }
}
