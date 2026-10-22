package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A public `var` of a flow type binds get-only, and its setter is NAMED: the partial skip ADR-075
 * requires of every other property type (`SKIPPED_UNSUPPORTED_INPUT`, "its setter is not
 * generated because ..."), which the flow property route never emitted because it never read
 * `isMutable`. This class began as the spike that pinned the silence.
 *
 * The getter is untouched: a `var source: Flow<Int>` still renders the `KotlinFlow<int> Source`
 * the `val` does, over the same one collect export. What the skip adds is the diagnostic and the
 * `<remarks>` on the C# property.
 *
 * The remedy differs by temperature. A cold `Flow<T>` has a way in, as an ADR-210 parameter. A hot
 * one (`StateFlow`, `SharedFlow` and their mutable forms) has none, so it is pointed at the
 * hot-flow parameter's own remedy (`stateIn` / `shareIn` over a `Flow<T>` parameter).
 *
 * Owners: the skip fires wherever the flow route binds the getter, which is a class, an
 * interface, a sealed base and a sealed arm. ADR-230: so do an object, a companion and the top
 * level now. Before it, an object, a companion and a top-level property had
 * no flow route, so the whole property was already a named `SKIPPED_UNSUPPORTED_PROPERTY`. A
 * generic class is the owner left pinned as a `DEFECT`: it renders no flow property of its own
 * and nothing names that, which is a silent whole-property drop rather than a setter one.
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
    import kotlinx.coroutines.flow.MutableSharedFlow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.SharedFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    class Feeder {
      var source: Flow<Int> = flowOf(1)
      var bowls: Int = 2
      var guarded: Flow<Int> = flowOf(1)
        private set
      var housed: Flow<Int> = flowOf(1)
        internal set
      var maybe: Flow<Int>? = null
      var shared: SharedFlow<Int> = MutableSharedFlow()
      var level: MutableStateFlow<Int> = MutableStateFlow(0)
      var calls: MutableSharedFlow<Int> = MutableSharedFlow()
      var odd: Flow<Pair<Int, Int>> = flowOf()
    }

    class Gauge {
      var source: StateFlow<Int> = MutableStateFlow(0)
    }

    class Tap {
      val source: Flow<Int> = flowOf(3)
    }

    interface Feed {
      var source: Flow<Int>
    }

    class TimedFeed : Feed {
      override var source: Flow<Int> = flowOf(1)
    }

    class Holder {
      fun take(feed: Feed) = Unit
    }

    sealed class Meal {
      var portions: Flow<Int> = flowOf(1)

      class Wet(val grams: Int) : Meal() {
        var source: Flow<Int> = flowOf(1)
      }

      object None : Meal()
    }

    open class Trough {
      open var source: Flow<Int> = flowOf(1)
    }

    class DeepTrough : Trough()

    object Kennel {
      var source: Flow<Int> = flowOf(1)
    }

    class Yard {
      companion object {
        var source: Flow<Int> = flowOf(1)
      }
    }

    var topSource: Flow<Int> = flowOf(1)

    class Crate<T>(val item: T) {
      var source: Flow<Int> = flowOf(1)
      val fixed: StateFlow<Int> = MutableStateFlow(0)
      var odd: Flow<Pair<Int, Int>> = flowOf()
    }

    class Bin<T>(val item: T) : Feed {
      override var source: Flow<Int> = flowOf(1)
    }
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "FlowVarSample.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private val setterCode: String = "SKIPPED_UNSUPPORTED_INPUT"
  private val propertyCode: String = "SKIPPED_UNSUPPORTED_PROPERTY"

  private val coldReason: String =
    "its setter is not generated because a Flow property binds read-only: its getter hands out " +
        "the Kotlin flow, and no setter route turns a C# stream back into one"

  private fun coldHint(publicName: String): String =
    "the C# property $publicName is read-only; to let C# replace the flow, expose a function " +
        "taking a `Flow<T>` parameter instead, which binds as `IAsyncEnumerable<T>` for a " +
        "primitive or String element"

  private fun hotReason(flow: String): String =
    "its setter is not generated because a $flow property binds read-only: its getter hands " +
        "out the Kotlin flow, and C# has no source for a synchronous current value or a replay " +
        "cache to set it from"

  private fun hotHint(publicName: String): String =
    "the C# property $publicName is read-only; to let C# replace the flow, expose a function " +
        "instead: declare the parameter as `Flow<T>` and call `stateIn(scope)` (or `shareIn`) " +
        "on it in Kotlin"

  /** Every diagnostic's own line: the `at <file>:<line>` location KSP appends is dropped. */
  private val diagnostics: List<String> by lazy {
    (result.kspErrors + result.kspWarnings)
      .flatMap { entry -> entry.lines() }
      .filter { line -> line.startsWith("[nuget:") }
  }

  /** The diagnostics of [code] that skip [declaration], whole. */
  private fun skips(code: String, declaration: String): List<String> =
    diagnostics.filter { line -> line.startsWith("[nuget:$code] Skipping $declaration: ") }

  private fun assertSetterNamed(declaration: String, reason: String, hint: String) {
    assertEquals(
      listOf("[nuget:$setterCode] Skipping $declaration: $reason. $hint"),
      skips(setterCode, declaration),
      "diagnostics=$diagnostics",
    )
  }

  /** The C# property [declaration] of [owner], its doc comment included, to its closing brace. */
  private fun property(owner: String, declaration: String): List<String> {
    val lines: List<String> = result.generatedCSharp.lines()
    val ownerStart: Int = lines.indexOfFirst { line ->
      line.trimStart().startsWith("public ") && " class $owner " in "$line "
    }
    assertTrue(ownerStart >= 0, "expected a C# class $owner")
    val start: Int = (ownerStart until lines.size).firstOrNull { index ->
      lines[index].trim() == declaration
    } ?: -1
    assertTrue(
      start >= 0,
      "expected `$declaration` on $owner; got=" +
          lines.drop(ownerStart).filter { line -> line.trim().startsWith("public ") }.take(12),
    )
    val indent: String = lines[start].takeWhile { char -> char == ' ' }
    val end: Int = (start until lines.size).first { index -> lines[index] == "$indent}" }
    val docStart: Int = (start - 1 downTo 0)
      .takeWhile { index -> lines[index].trim().startsWith("///") }
      .lastOrNull() ?: start
    return lines.subList(docStart, end + 1)
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
    assertEquals(listOf("library_tier1_flowvar__feeder_set_bowls"), exports("feeder_set_bowls"))
    assertEquals(emptyList(), skips(setterCode, "tier1.flowvar.Feeder.bowls"))
  }

  @Test
  fun `a val Flow property binds get-only and is not named`() {
    val source: List<String> = property("Tap", "public KotlinFlow<int> Source")

    assertTrue(source.hasAccessor("get"), "got=$source")
    assertFalse(source.hasAccessor("set"), "got=$source")
    assertEquals(listOf("library_tier1_flowvar__tap_get_source_collect"), exports("tap_get_"))
    assertEquals(emptyList(), diagnostics.filter { line -> "Tap" in line })
  }

  @Test
  fun `a var Flow property keeps its getter and names the setter it cannot bind`() {
    val source: List<String> = property("Feeder", "public KotlinFlow<int> Source")

    // The same shape as the `val` on `Tap`: the getter and the one collect export, no setter.
    assertTrue(source.hasAccessor("get"), "got=$source")
    assertFalse(source.hasAccessor("set"), "got=$source")
    assertEquals(
      listOf("library_tier1_flowvar__feeder_get_source_collect"),
      exports("feeder_get_source"),
    )
    assertEquals(emptyList(), exports("feeder_set_source"))
    assertSetterNamed("tier1.flowvar.Feeder.source", coldReason, coldHint("Source"))
    // Issue #249: the property survives, so the remark is on it rather than on its type.
    assertContains(source, "        /// Kotlin `source`: $coldReason ($setterCode).")
  }

  @Test
  fun `a var StateFlow property names its setter with the hot flow remedy`() {
    val source: List<String> = property("Gauge", "public KotlinStateFlow<int> Source")

    assertTrue(source.hasAccessor("get"), "got=$source")
    assertFalse(source.hasAccessor("set"), "got=$source")
    assertEquals(
      listOf(
        "library_tier1_flowvar__gauge_get_source_collect",
        "library_tier1_flowvar__gauge_get_source_value",
      ),
      exports("gauge_get_source"),
    )
    assertEquals(emptyList(), exports("gauge_set_source"))
    assertSetterNamed("tier1.flowvar.Gauge.source", hotReason("StateFlow"), hotHint("Source"))
  }

  @Test
  fun `every other hot flow var names its setter by its own type`() {
    assertSetterNamed("tier1.flowvar.Feeder.shared", hotReason("SharedFlow"), hotHint("Shared"))
    assertSetterNamed(
      "tier1.flowvar.Feeder.level",
      hotReason("MutableStateFlow"),
      hotHint("Level"),
    )
    assertSetterNamed(
      "tier1.flowvar.Feeder.calls",
      hotReason("MutableSharedFlow"),
      hotHint("Calls"),
    )
    // ADR-071's write is the flow's `Value`, which is still there: only the property is get-only.
    assertEquals(
      listOf("library_tier1_flowvar__feeder_set_level_value"),
      exports("feeder_set_level"),
    )
  }

  @Test
  fun `a nullable var Flow member names its setter too`() {
    property("Feeder", "public KotlinFlow<int>? Maybe")
    assertSetterNamed("tier1.flowvar.Feeder.maybe", coldReason, coldHint("Maybe"))
  }

  /** There is no public setter to lose, so there is nothing to name. */
  @Test
  fun `a private or internal setter is silent`() {
    property("Feeder", "public KotlinFlow<int> Guarded")
    property("Feeder", "public KotlinFlow<int> Housed")

    assertEquals(emptyList(), diagnostics.filter { line -> "guarded" in line || "housed" in line })
  }

  /** The whole property is refused, by its own walk: "the C# property is read-only" would lie. */
  @Test
  fun `a var whose flow element is refused is named once, as the whole property`() {
    assertEquals(
      listOf(
        "[nuget:$propertyCode] Skipping Feeder.odd: a Flow or StateFlow element can be a " +
            "List/Set/Map, but not the generic type Pair<Int, Int>. make the element a " +
            "non-nullable List/Set/Map, or a non-generic type",
      ),
      diagnostics.filter { line -> "Feeder.odd" in line },
    )
  }

  @Test
  fun `an interface var and its implementer each name their own setter`() {
    property("Feed", "public KotlinFlow<int> Source")
    property("TimedFeed", "public KotlinFlow<int> Source")

    assertSetterNamed("tier1.flowvar.Feed.source", coldReason, coldHint("Source"))
    assertSetterNamed("tier1.flowvar.TimedFeed.source", coldReason, coldHint("Source"))
  }

  @Test
  fun `a sealed base and a sealed arm each name their own setter`() {
    property("Meal", "public KotlinFlow<int> Portions")
    property("Wet", "public KotlinFlow<int> Source")

    assertSetterNamed("tier1.flowvar.Meal.portions", coldReason, coldHint("Portions"))
    assertSetterNamed("tier1.flowvar.Meal.Wet.source", coldReason, coldHint("Source"))
    // The base's property is named on the base alone, not again on each arm that inherits it.
    assertEquals(1, diagnostics.count { line -> "portions" in line }, "diagnostics=$diagnostics")
  }

  @Test
  fun `an inherited var is named on the class that declares it alone`() {
    assertSetterNamed("tier1.flowvar.Trough.source", coldReason, coldHint("Source"))
    assertEquals(emptyList(), diagnostics.filter { line -> "DeepTrough" in line })
  }

  /**
   * ADR-230: the static flow route serves these owners, so each binds its getter and names
   * its dropped setter once, like every other owner the flow route serves.
   */
  @Test
  fun `an object, a companion and a top-level flow var bind get-only and name the setter`() {
    // ADR-230: the static flow route binds the getter, so the setter is the one named skip.
    listOf(
      "tier1.flowvar.Kennel.source",
      "tier1.flowvar.Yard.Companion.source",
      "tier1.flowvar.topSource",
    ).forEach { declaration ->
      val lines: List<String> = diagnostics.filter { line -> "Skipping $declaration: " in line }
      assertEquals(1, lines.size, "$declaration: $lines")
      assertTrue(lines.single().startsWith("[nuget:$setterCode] "), lines.single())
    }
  }

  /**
   * ADR-215 (ROADMAP "generic class flow property dropped whole", closed): a generic class's own
   * flow property binds over the erased receiver (`asStableRef<Crate<Any?>>()`), so `source` and
   * `fixed` are on both halves, and the `var`'s dropped setter is named like any other owner's.
   */
  @Test
  fun `a generic class flow property binds over the erased receiver`() {
    assertEquals(
      listOf(
        "library_tier1_flowvar__crate_get_source_collect",
        "library_tier1_flowvar__crate_get_fixed_collect",
        "library_tier1_flowvar__crate_get_fixed_value",
      ),
      exports("crate_get_source") + exports("crate_get_fixed"),
    )
    assertContains(result.generated, "asStableRef<tier1.flowvar.Crate<Any?>>()")
    val crate: List<String> = result.generatedCSharp.lines()
      .dropWhile { line -> !line.startsWith("    public class Crate<T> ") }
      .drop(1)
      .takeWhile { line -> line != "    }" }
    assertTrue(crate.isNotEmpty(), "expected a C# class Crate<T>")
    assertTrue(crate.any { line -> "public KotlinFlow<int> Source" in line }, "crate=$crate")
    assertTrue(crate.any { line -> "public KotlinStateFlow<int> Fixed" in line }, "crate=$crate")
    assertSetterNamed("tier1.flowvar.Crate.source", coldReason, coldHint("Source"))
    // The refused element is still named, once, by the refused-legacy-route walk.
    assertEquals(
      listOf(
        "[nuget:$propertyCode] Skipping Crate.odd: a Flow or StateFlow element can be a " +
            "List/Set/Map, but not the generic type Pair<Int, Int>. make the element a " +
            "non-nullable List/Set/Map, or a non-generic type",
      ),
      diagnostics.filter { line -> "Crate." in line && "Crate.source" !in line },
    )
  }

  /**
   * A generic class carries an interface's flow property as an explicit member (ADR-174), read
   * through the interface's own export. The interface names the setter; the implementer has no
   * public property of its own to call read-only.
   */
  @Test
  fun `a generic implementer's interface var is named on the interface alone`() {
    assertContains(result.generatedCSharp, "KotlinFlow<int> IFeed.Source")
    assertEquals(emptyList(), exports("bin_get_source"))
    assertEquals(emptyList(), diagnostics.filter { line -> "Bin." in line || "Bin<" in line })
  }

  /** The census: every setter skip the fixture emits, so one that fires twice or astray fails. */
  @Test
  fun `the setter skip fires once per dropped public setter and nowhere else`() {
    assertEquals(
      listOf(
        "tier1.flowvar.Crate.source",
        "tier1.flowvar.Feed.source",
        "tier1.flowvar.Feeder.calls",
        "tier1.flowvar.Feeder.level",
        "tier1.flowvar.Feeder.maybe",
        "tier1.flowvar.Feeder.shared",
        "tier1.flowvar.Feeder.source",
        "tier1.flowvar.Gauge.source",
        "tier1.flowvar.Kennel.source",
        "tier1.flowvar.Meal.Wet.source",
        "tier1.flowvar.Meal.portions",
        "tier1.flowvar.TimedFeed.source",
        "tier1.flowvar.Trough.source",
        "tier1.flowvar.Yard.Companion.source",
        "tier1.flowvar.topSource",
      ),
      diagnostics
        .filter { line -> line.startsWith("[nuget:$setterCode] ") }
        .map { line -> line.substringAfter("Skipping ").substringBefore(": ") }
        .sorted(),
    )
  }
}
