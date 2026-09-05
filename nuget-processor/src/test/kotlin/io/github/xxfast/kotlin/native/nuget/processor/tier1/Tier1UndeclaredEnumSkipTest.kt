package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
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
 * Three shapes reach the gate, and they must not collapse into one message:
 * - (a) a **module-local nested** enum — `SKIPPED_UNSUPPORTED_TYPE` / `UNDECLARED_ENUM`, naming the
 *   enum and the move-to-top-level fix,
 * - (b) a **cross-module nested** enum on an *admitted* dependency class — the reachability closure
 *   now refuses to admit it (admitting it declared a namespace-root `public enum AdBand` that the
 *   `Broadcast.AdBand` references never resolved against), so it lands on the same
 *   `UNDECLARED_ENUM` route rather than the `include(...)` one, which cannot help a nested enum,
 * - (c) a **top-level dependency** enum in a never-admitted package — this one keeps
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

      var mode: Mode = Mode.ON
      fun activate(mode: Mode) { this.mode = mode }
      fun current(): Mode = mode
      fun apply(modes: List<Mode>) { this.mode = modes.first() }
      fun index(byMode: Map<Mode, String>) { this.mode = byMode.keys.first() }

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
  fun `a module-local nested enum is never spelled in the generated C#`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      result.generatedCSharp.contains("Owner.Mode"),
      "expected no dangling reference to the undeclared nested enum; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Mode") }}",
    )
    listOf(
      "export_owner_get_mode",
      "export_owner_activate",
      "export_owner_current",
      "export_owner_apply",
      "export_owner_index",
    ).forEach { export ->
      assertFalse(
        result.generated.contains(export),
        "expected $export to be absent from the generated exports; generated=${result.generated}",
      )
    }
  }

  @Test
  fun `every nested-enum position skips named with the undeclared-enum hint`() {
    val result = Tier1Harness.run(source)

    listOf(
      "Owner.activate",
      "Owner.current",
      "Owner.apply",
      "Owner.index",
      "Dial",
    ).forEach { member ->
      val diagnostic: String = requireNotNull(
        result.kspWarnings.firstOrNull {
          it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name) && it.contains(member)
        },
      ) {
        "expected a SKIPPED_UNSUPPORTED_TYPE diagnostic for $member; " +
            "kspWarnings=${result.kspWarnings}"
      }
      assertTrue(
        diagnostic.contains("tier1.undeclaredenum.Owner.Mode"),
        "expected the $member diagnostic to name the undeclared enum; got: $diagnostic",
      )
      assertTrue(
        diagnostic.contains("move it to the top level"),
        "expected the $member diagnostic to name the move-to-top-level fix; got: $diagnostic",
      )
    }
  }

  @Test
  fun `the nested-enum property skips named too`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("Owner.mode") &&
            it.contains("tier1.undeclaredenum.Owner.Mode")
      },
      "expected the property route to skip naming the undeclared enum; " +
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
  fun `an admitted dependency's nested enum is neither declared nor referenced`() {
    val result = dependencyResult()

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      result.generatedCSharp.contains("AdBand"),
      "expected the nested dependency enum to be neither declared at namespace root nor " +
          "referenced; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("AdBand") }}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("Broadcast.band") &&
            it.contains("dep.models.Broadcast.AdBand")
      },
      "expected Broadcast.band to skip naming the undeclared nested enum; " +
          "kspWarnings=${result.kspWarnings}",
    )
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
      result.generatedCSharp.contains("Airwave"),
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
