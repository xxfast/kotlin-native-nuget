package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-233 (amended): a public `var` of any flow type binds its setter wherever the flow route binds
 * the getter: a class, an interface, a sealed base, a sealed arm and a generic class. The
 * setter hands Kotlin the flow its `KotlinFlow<T>` wraps or mints (`_get_<p>_flow`), and a cold
 * one also takes a C#-made flow through ADR-210's seam (`_set_<p>_source`). Before, only a cold
 * `Flow` on a non-generic, non-sealed owner had one, and every other setter was a named
 * `SKIPPED_UNSUPPORTED_INPUT` partial skip (ADR-075).
 *
 * An object, a companion, an enum and a top-level property have no flow route, so the whole
 * property, getter included, stays a named `SKIPPED_UNSUPPORTED_PROPERTY`.
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

    enum class Light {
      RED;

      var glow: Flow<Int> = flowOf(1)
    }

    fun light(): Light = Light.RED
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

  /** Every diagnostic's own line: the `at <file>:<line>` location KSP appends is dropped. */
  private val diagnostics: List<String> by lazy {
    (result.kspErrors + result.kspWarnings)
      .flatMap { entry -> entry.lines() }
      .filter { line -> line.startsWith("[nuget:") }
  }

  /** The diagnostics of [code] that skip [declaration], whole. */
  private fun skips(code: String, declaration: String): List<String> =
    diagnostics.filter { line -> line.startsWith("[nuget:$code] Skipping $declaration: ") }

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
    assertEquals(
      listOf(
        "library_tier1_flowvar__tap_get_source_collect",
        "library_tier1_flowvar__tap_get_source_flow",
      ),
      exports("tap_get_"),
    )
    assertEquals(emptyList(), diagnostics.filter { line -> "Tap" in line })
  }

  /** ADR-233: a cold `Flow` `var` binds its setter, which hands Kotlin the wrapped flow. */
  @Test
  fun `a var Flow property binds its setter`() {
    val source: List<String> = property("Feeder", "public KotlinFlow<int> Source")

    assertTrue(source.hasAccessor("get"), "got=$source")
    assertTrue(source.hasAccessor("set"), "got=$source")
    assertEquals(
      listOf(
        "library_tier1_flowvar__feeder_get_source_collect",
        "library_tier1_flowvar__feeder_get_source_flow",
      ),
      exports("feeder_get_source"),
    )
    assertEquals(
      listOf(
        "library_tier1_flowvar__feeder_set_source",
        "library_tier1_flowvar__feeder_set_source_source",
      ),
      exports("feeder_set_source"),
    )
    assertEquals(emptyList(), skips(setterCode, "tier1.flowvar.Feeder.source"))
    // A Kotlin-made flow hands its handle (or mints one); a C#-made one crosses the ADR-210 seam.
    val setter: String = source.joinToString("\n")
    assertContains(setter, "value.KotlinHandle")
    assertContains(setter, "value.MintHandle")
    assertContains(setter, "Native_Set_sourceSource(_handle, NugetThunks.NugetAsyncEnumeratePtr")
  }

  /** ADR-233 amendment: a hot flow `var` binds its setter, handed the wrapped or minted flow. */
  @Test
  fun `a var StateFlow property binds its setter`() {
    val source: List<String> = property("Gauge", "public KotlinStateFlow<int> Source")

    assertTrue(source.hasAccessor("get"), "got=$source")
    assertTrue(source.hasAccessor("set"), "got=$source")
    assertEquals(
      listOf(
        "library_tier1_flowvar__gauge_get_source_collect",
        "library_tier1_flowvar__gauge_get_source_flow",
        "library_tier1_flowvar__gauge_get_source_value",
      ),
      exports("gauge_get_source"),
    )
    // C# makes a hot flow only through `Create`, which owns its Kotlin flow: no C# seam.
    assertEquals(listOf("library_tier1_flowvar__gauge_set_source"), exports("gauge_set_source"))
    assertEquals(emptyList(), skips(setterCode, "tier1.flowvar.Gauge.source"))
  }

  @Test
  fun `every other hot flow var binds its setter too`() {
    property("Feeder", "public KotlinSharedFlow<int> Shared")
    assertEquals(listOf("library_tier1_flowvar__feeder_set_shared"), exports("feeder_set_shared"))
    assertEquals(listOf("library_tier1_flowvar__feeder_set_calls"), exports("feeder_set_calls"))
    // ADR-071's write is the flow's `Value`, beside the property's own setter.
    assertEquals(
      listOf(
        "library_tier1_flowvar__feeder_set_level",
        "library_tier1_flowvar__feeder_set_level_value",
      ),
      exports("feeder_set_level"),
    )
  }

  @Test
  fun `a nullable var Flow member binds its setter too`() {
    property("Feeder", "public KotlinFlow<int>? Maybe")
    assertEquals(
      listOf(
        "library_tier1_flowvar__feeder_set_maybe",
        "library_tier1_flowvar__feeder_set_maybe_source",
      ),
      exports("feeder_set_maybe"),
    )
    assertEquals(emptyList(), skips(setterCode, "tier1.flowvar.Feeder.maybe"))
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
  fun `an interface var and its implementer each bind their own setter`() {
    property("Feed", "public KotlinFlow<int> Source")
    property("TimedFeed", "public KotlinFlow<int> Source")

    assertEquals(emptyList(), skips(setterCode, "tier1.flowvar.Feed.source"))
    assertEquals(emptyList(), skips(setterCode, "tier1.flowvar.TimedFeed.source"))
  }

  @Test
  fun `a sealed base and a sealed arm each bind their own setter`() {
    assertTrue(property("Meal", "public KotlinFlow<int> Portions").hasAccessor("set"))
    assertTrue(property("Wet", "public KotlinFlow<int> Source").hasAccessor("set"))

    assertEquals(emptyList(), diagnostics.filter { line -> "portions" in line || "Wet.source" in line })
    assertContains(result.generated, "library_tier1_flowvar__meal_set_portions_source")
    assertContains(result.generated, "library_tier1_flowvar__meal_wet_set_source_source")
  }



  @Test
  fun `an inherited var binds on the class that declares it`() {
    assertEquals(emptyList(), skips(setterCode, "tier1.flowvar.Trough.source"))
    assertEquals(emptyList(), diagnostics.filter { line -> "DeepTrough" in line })
  }

  /**
   * No flow route serves these owners, so the whole property was already named and no setter
   * skip is added beside it: there is no C# property for "is read-only" to be true of.
   */
  @Test
  fun `an object, a companion, an enum and a top-level flow var stay a named whole-property skip`() {
    val wording: String = "its type flow kotlinx.coroutines.flow.Flow has no property getter or " +
        "setter shape. expose a bridgeable property (or a getter function) whose type is not " +
        "flow kotlinx.coroutines.flow.Flow, and export that instead"

    listOf(
      "tier1.flowvar.Kennel.source",
      "tier1.flowvar.Yard.Companion.source",
      "tier1.flowvar.Light.glow",
      "tier1.flowvar.topSource",
    ).forEach { declaration ->
      assertEquals(
        listOf("[nuget:$propertyCode] Skipping $declaration: $wording"),
        diagnostics.filter { line -> "Skipping $declaration: " in line },
      )
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
        "library_tier1_flowvar__crate_get_source_flow",
        "library_tier1_flowvar__crate_get_fixed_collect",
        "library_tier1_flowvar__crate_get_fixed_flow",
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
    assertTrue(crate.any { line -> "Native_Set_sourceSource(" in line }, "crate=$crate")
    assertEquals(emptyList(), skips(setterCode, "tier1.flowvar.Crate.source"))
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

  /** The census: no flow `var` the route binds drops its setter any more. */
  @Test
  fun `no flow var setter is skipped`() {
    assertEquals(
      emptyList(),
      diagnostics
        .filter { line -> line.startsWith("[nuget:$setterCode] ") }
        .map { line -> line.substringAfter("Skipping ").substringBefore(": ") }
        .sorted(),
    )
  }
}
