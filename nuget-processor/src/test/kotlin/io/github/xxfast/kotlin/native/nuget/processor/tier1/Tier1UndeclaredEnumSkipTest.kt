package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The undeclared-enum membership gate. Only *top-level* enums inside the export scope are ever
 * declared as C# enums (`rootEnums` filters `parentDeclaration == null`), but the classifier's
 * enum branch had no membership test at all: every enum-typed member was spelled as a C# enum
 * reference, nested spelling and all, so an enum outside the declared set produced a reference to
 * a type nothing emits — CS0426/CS0234 on the consumer's `Interop.cs`, with no KSP diagnostic to
 * explain it.
 *
 * ADR-133 flips the two nested shapes from absence to presence; the third is untouched, and keeping
 * all three in one file is what makes "the gate collapsed into one message" visible:
 * - (a) a **module-local nested** enum — now DECLARED as the nested `Owner.Mode`, so every position
 *   typed with it binds and nothing skips,
 * - (b) a **cross-module nested** enum on an *admitted* dependency class — now declared once,
 *   nested under `Broadcast`, by the owner walk alone (a second declaration from the dependency
 *   merge would be CS0101, and a namespace-root one would be the pre-2026-09-07 flattening that
 *   `Broadcast.AdBand` references never resolved against),
 * - (c) a **top-level dependency** enum in a never-admitted package — unchanged, and it keeps
 *   `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` and its `include(...)` hint, exactly as an unadmitted
 *   *class* already did; the enum branch simply used to run before that route could be reached.
 *
 * A control rides along in every cell: a top-level, in-scope enum at the same positions still
 * binds, so a gate that over-fires is distinguishable from one that fires correctly.
 */
class Tier1UndeclaredEnumSkipTest {

  /**
   * Shape (a). `Owner.Mode` occupies every classifier-fed position at once — property, method
   * parameter, method return, `List` element and `Map` key — while the top-level `Volume` twin
   * occupies the ordinary ones as the control. (A `List`/`Map` at a *return* or *property*
   * position takes the same `skipReason()` → component → `undeclaredEnumDetail()` path as the
   * element cell here, so it is covered by code path rather than by its own cell.) The
   * constructor cell lives on its own class ([Dial]) so a skipped primary constructor cannot be
   * mistaken for "the gate dropped the owning class".
   */
  private val source: String = """
    package tier1.undeclaredenum

    enum class Volume { LOW, HIGH }

    class Owner {
      enum class Mode { ON, OFF }

      var setting: Mode = Mode.ON
      fun activate(mode: Mode) { this.setting = mode }
      fun current(): Mode = setting
      fun apply(modes: List<Mode>) { this.setting = modes.first() }
      fun index(byMode: Map<Mode, String>) { this.setting = byMode.keys.first() }

      var volume: Volume = Volume.LOW
      fun tune(volume: Volume) { this.volume = volume }
      fun tuning(): Volume = volume

      val label: String = "owner"
    }

    class Dial(mode: Owner.Mode) {
      val label: String = mode.name
    }
  """.trimIndent()

  @Test
  fun `a module-local nested enum is declared as a nested C# enum`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    // Was: assertFalse(csharp.contains("Owner.Mode")) plus an absent-export assertion for each
    // position. ADR-133 declares the nested enum, so every one of them binds.
    assertContains(result.generatedCSharp, "public enum Mode")
    assertFalse(
      Regex("""^ {4}public enum Mode\b""", RegexOption.MULTILINE)
        .containsMatchIn(result.generatedCSharp),
      "expected no namespace-level twin of the nested enum; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Mode") }}",
    )
    listOf(
      "export_owner_get_setting",
      "export_owner_activate",
      "export_owner_current",
      "export_owner_apply",
      "export_owner_index",
      "export_dial_create",
    ).forEach { export ->
      assertContains(
        result.generated,
        export,
        message = "expected $export to bind now that the nested enum is declared; " +
            "generated=${result.generated}",
      )
    }
  }

  @Test
  fun `the nested enum's own exports carry the enclosing chain`() {
    val result = Tier1Harness.run(source)

    // The enum entry point is composed from the enum's simple name today; ADR-133 makes it the
    // chain, so `Owner.Mode` cannot collide with a top-level `Mode` (ADR-117).
    assertTrue(
      result.generated.contains("owner_mode_") || !result.generated.contains("\"mode_"),
      "expected the nested enum's exports to carry the owner chain; generated=${result.generated}",
    )
    assertFalse(
      result.generated.contains("@CName(\"mode_"),
      "expected no unchained `mode_` entry point; generated=${result.generated}",
    )
  }

  @Test
  fun `no position typed with the nested enum skips any more`() {
    val result = Tier1Harness.run(source)

    // Was: every one of these had to carry SKIPPED_UNSUPPORTED_TYPE naming Owner.Mode, and the
    // property had to carry SKIPPED_UNSUPPORTED_PROPERTY. The collection and map positions stay in
    // the list because an element-type route that keeps its own membership check would fail here
    // while the scalar positions pass.
    listOf(
      "Owner.activate",
      "Owner.current",
      "Owner.apply",
      "Owner.index",
      "Owner.setting",
      "Dial",
    ).forEach { member ->
      assertFalse(
        result.kspWarnings.any {
          it.contains("SKIPPED_") &&
              it.contains(member) &&
              it.contains("tier1.undeclaredenum.Owner.Mode")
        },
        "expected no skip naming the now-declared nested enum for $member; " +
            "kspWarnings=${result.kspWarnings}",
      )
    }
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("tier1.undeclaredenum.Owner.Mode")
      },
      "expected the nested enum itself to be declared, not skipped; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a top-level enum still binds at the same positions`() {
    val result = Tier1Harness.run(source)

    listOf(
      "export_owner_get_volume",
      "export_owner_tune",
      "export_owner_tuning",
      "export_owner_get_label",
      "export_dial_get_label",
    ).forEach { export ->
      assertTrue(
        result.generated.contains(export),
        "expected $export to survive the gate; generated=${result.generated}",
      )
    }
    assertTrue(
      result.generatedCSharp.contains("enum Volume"),
      "expected the top-level control enum to still be declared; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Volume") }}",
    )
  }

  private val admittedDependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.models

    enum class Genre { NEWS, MUSIC }

    class Broadcast {
      enum class AdBand { AM, FM }

      val band: AdBand = AdBand.FM
      val genre: Genre = Genre.MUSIC
      val station: String = "Radio Mylo 101.1"
    }
    """.trimIndent(),
    fileName = "Broadcast.kt",
  )

  private val unadmittedDependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.outside

    enum class Airwave { AM, FM }
    """.trimIndent(),
    fileName = "Airwave.kt",
  )

  private val dependencyFixture: String = """
    package tier1.undeclaredenum.deps

    import dep.models.Broadcast
    import dep.outside.Airwave

    class Newsroom {
      fun broadcast(): Broadcast = Broadcast()
      fun airwave(): Airwave = Airwave.FM
    }
  """.trimIndent()

  /** `dep.models` is admitted, `dep.outside` deliberately is not — the two dependency shapes in
   *  one run, so their diagnostics are compared against the same generated output. */
  private fun dependencyResult(): Tier1Result = Tier1Harness.run(
    dependencyFixture,
    processorOptions = mapOf(
      "nuget.includePackages" to "tier1.undeclaredenum.deps,dep.models",
    ),
    libraries = listOf(admittedDependencyJar, unadmittedDependencyJar),
  )

  @Test
  fun `an admitted dependency's nested enum is declared once, nested under its owner`() {
    val result = dependencyResult()

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    // Was: assertFalse(csharp.contains("AdBand")) and a SKIPPED_UNSUPPORTED_PROPERTY for
    // Broadcast.band. ADR-133's closure edge admits a nested dependency declaration whose whole
    // enclosing chain is admitted, and the OWNER WALK is the sole declarer: if the dependency
    // merge declared it too, `AdBand` would appear twice (CS0101, the issue #54/#110 lesson).
    assertContains(result.generatedCSharp, "public enum AdBand")
    assertFalse(
      Regex("""^ {4}public enum AdBand\b""", RegexOption.MULTILINE)
        .containsMatchIn(result.generatedCSharp),
      "expected no namespace-root twin of the nested dependency enum; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("AdBand") }}",
    )
    assertEquals(
      1,
      Regex("""public enum AdBand\b""").findAll(result.generatedCSharp).count(),
      "expected exactly one declaration of AdBand; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("AdBand") }}",
    )
    assertFalse(
      result.kspWarnings.any {
        it.contains("SKIPPED_") && it.contains("Broadcast.band")
      },
      "expected Broadcast.band to bind now that AdBand is declared; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertContains(result.generated, "export_broadcast_get_band")
    assertTrue(
      result.generated.contains("export_newsroom_broadcast") &&
          result.generated.contains("export_broadcast_get_station"),
      "expected the admitted dependency class and its other member to survive; " +
          "generated=${result.generated}",
    )
    // The control for the closure's new filter: it declines *nested* dependency enums only, so a
    // top-level one in the same admitted package is still admitted, declared and bound.
    assertTrue(
      result.generated.contains("export_broadcast_get_genre") &&
          result.generatedCSharp.contains("enum Genre"),
      "expected the top-level dependency enum to still be admitted and declared; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Genre") }}",
    )
  }

  @Test
  fun `an unadmitted top-level dependency enum keeps the include hint`() {
    val result = dependencyResult()

    assertFalse(
      result.generated.contains("export_newsroom_airwave"),
      "expected Newsroom.airwave to be absent; generated=${result.generated}",
    )
    assertFalse(
      result.generatedCSharp.withoutDocComments().contains("Airwave"),
      "expected no reference to the unadmitted dependency enum; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Airwave") }}",
    )
    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE.name) &&
            it.contains("Newsroom.airwave")
      },
    ) {
      "expected the unadmitted dependency enum to take the ADR-066 route, not the " +
          "undeclared-enum one; kspWarnings=${result.kspWarnings}"
    }
    assertTrue(
      diagnostic.contains("dep.outside"),
      "expected the include(...) hint to name the dependency package; got: $diagnostic",
    )
  }
}
