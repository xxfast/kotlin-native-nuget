package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-112: a `sealed interface` used to be claimed by two routes and finished by neither. The root
 * scan filtered `classKind == CLASS` before `Modifier.SEALED`, so it fell through to the interface
 * route and was declared as a bare `public interface IPulse` with no members and no subclasses,
 * while its nested subclasses were reported `SKIPPED_NESTED_DECLARATION` and every member typed
 * with it skipped as `SKIPPED_SEALED_POSITION` (a property earlier still, as
 * `SKIPPED_UNSUPPORTED_PROPERTY`). The C# type existed and could never be obtained, implemented or
 * passed.
 *
 * An **eligible** sealed interface (no type parameters, every subclass a nested class or object
 * with no other superclass, no sub-interfaces) now takes the ADR-009 sealed-class route verbatim:
 * `public abstract class Pulse` with nested `sealed` subclasses and a `FromHandle` discriminator,
 * and every ADR-105 position binds through it. No `IPulse` may survive anywhere, since the ADR-040
 * backing wrapper behind it would collide with the abstract class by name (CS0101).
 *
 * `Mixed` is the ineligible control in the same fixture: eligibility is a property of the
 * hierarchy, not of the keyword, so a fix that admits *every* sealed interface has to be visibly
 * wrong here. `Mixed.Odd` extends `Rhythm` as well, which no nested `sealed class Odd : Mixed` can
 * express, so it keeps `IMixed`, keeps skipping every position, and now says why exactly once at
 * the declaration.
 */
class Tier1SealedInterfaceTest {

  private val source: String = """
    package tier1.sealedinterface

    sealed interface Pulse {
      data class Beat(val bpm: Int) : Pulse
      data object Flat : Pulse
    }

    class Monitor {
      var current: Pulse = Pulse.Flat
      val history: List<Pulse> = listOf(Pulse.Beat(60), Pulse.Flat)
      fun latest(): Pulse = current
      fun record(pulse: Pulse): Int = when (pulse) {
        is Pulse.Beat -> pulse.bpm
        Pulse.Flat -> 0
      }
      fun mixed(): Mixed = Mixed.Odd()
    }

    fun anyPulse(): Pulse = Pulse.Beat(72)

    open class Rhythm {
      fun tempo(): String = "steady"
    }

    sealed interface Mixed {
      class Odd : Rhythm(), Mixed
    }
  """.trimIndent()

  @Test
  fun `an eligible sealed interface renders as the ADR-009 abstract class`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    listOf(
      "public abstract class Pulse : IDisposable, INugetHandle",
      "public sealed class Beat : Pulse",
      "public sealed class Flat : Pulse",
      "internal static Pulse FromHandle(IntPtr handle)",
    ).forEach { declaration ->
      assertTrue(
        result.generatedCSharp.contains(declaration),
        "expected `$declaration`; generatedCSharp=" +
            "${result.generatedCSharp.lines().filter { it.contains("Pulse") }}",
      )
    }
    assertTrue(
      result.generated.contains("export_pulse_get_type"),
      "expected the discriminator export; generated=${result.generated}",
    )
  }

  @Test
  fun `no IPulse is declared and no subclass is reported nested`() {
    val result = Tier1Harness.run(source)

    assertFalse(
      result.generatedCSharp.contains("IPulse"),
      "expected the interface route not to claim an eligible sealed interface; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("IPulse") }}",
    )
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("tier1.sealedinterface.Pulse.")
      },
      "expected no nested-declaration skip for a sealed-interface subclass; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `every position typed with an eligible sealed interface binds`() {
    val result = Tier1Harness.run(source)

    listOf(
      "public global::Interop.Pulse Current",
      "public IReadOnlyList<global::Interop.Pulse> History",
      "public global::Interop.Pulse Latest()",
      "public int Record(global::Interop.Pulse pulse)",
      "public static global::Interop.Pulse AnyPulse()",
    ).forEach { member ->
      assertTrue(
        result.generatedCSharp.contains(member),
        "expected `$member`; generatedCSharp=" +
            "${result.generatedCSharp.lines().filter { it.contains("Pulse") }}",
      )
    }
    assertTrue(
      result.kspWarnings.none { it.contains("tier1.sealedinterface.Pulse)") },
      "expected no skip naming the eligible sealed interface; kspWarnings=${result.kspWarnings}",
    )
  }

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.sealedinterface

    sealed interface Signal {
      data class Ping(val ms: Int) : Signal
    }
    """.trimIndent(),
    fileName = "Signal.kt",
  )

  /**
   * The ADR-066 closure buckets a dependency declaration on its own, so eligibility has to be
   * tested there too: this used to land in `INTERFACE` and be declared as a bare `ISignal`.
   */
  @Test
  fun `a cross-module eligible sealed interface reaches the sealed bucket`() {
    val result = Tier1Harness.run(
      """
      package tier1.sealedinterface.crossmodule

      import dep.sealedinterface.Signal

      class Radio {
        fun signal(): Signal = Signal.Ping(1)
      }
      """.trimIndent(),
      processorOptions = mapOf(
        "nuget.includePackages" to "tier1.sealedinterface.crossmodule,dep.sealedinterface",
      ),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      result.generatedCSharp.contains("public abstract class Signal : IDisposable, INugetHandle") &&
          result.generatedCSharp.contains("public sealed class Ping : Signal"),
      "expected the dependency sealed interface to take the ADR-009 route; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Signal") }}",
    )
    assertFalse(
      result.generatedCSharp.contains("ISignal"),
      "expected no interface declaration for the dependency sealed interface; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("ISignal") }}",
    )
  }

  @Test
  fun `an ineligible sealed interface keeps its interface and says why`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.generatedCSharp.contains("public interface IMixed"),
      "expected the ineligible sealed interface to stay on the interface route; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Mixed") }}",
    )
    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE.name)
      },
    ) { "expected an ineligible-sealed-interface warning; kspWarnings=${result.kspWarnings}" }
    assertTrue(
      diagnostic.contains("tier1.sealedinterface.Mixed") && diagnostic.contains("IMixed"),
      "expected the diagnostic to name the interface and its C# spelling; got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains("subclass `Odd` extends another class") &&
          diagnostic.contains("tier1.sealedinterface.Rhythm"),
      "expected the diagnostic to name the disqualifying subclass and base; got: $diagnostic",
    )
  }

  /**
   * The other three disqualifying reasons, each of which the position-level `SEALED_POSITION` skip
   * can only report as "no discriminator". One fixture, because they are independent hierarchies
   * and the harness cost is per run.
   */
  private val ineligible: String = """
    package tier1.sealedinterface.ineligible

    sealed interface Boxed<T> {
      class Some<T>(val value: T) : Boxed<T>
    }

    sealed interface Split {
      interface Half : Split
    }

    sealed interface Loose

    class Astray : Loose

    fun stray(): Astray = Astray()
  """.trimIndent()

  @Test
  fun `every ineligibility reason is named at the declaration`() {
    val result = Tier1Harness.run(ineligible)

    fun reason(name: String): String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE.name) &&
            it.contains("`tier1.sealedinterface.ineligible.$name`")
      },
    ) { "expected an ineligibility warning for $name; kspWarnings=${result.kspWarnings}" }

    val generic: String = reason("Boxed")
    assertTrue(
      generic.contains("it has type parameters"),
      "expected the generic reason; got: $generic",
    )
    val subInterface: String = reason("Split")
    assertTrue(
      subInterface.contains("subclass `Half` is an interface"),
      "expected the sub-interface reason; got: $subInterface",
    )
    val topLevel: String = reason("Loose")
    assertTrue(
      topLevel.contains("subclass `Astray` is declared outside the sealed interface"),
      "expected the top-level-subclass reason; got: $topLevel",
    )
  }

  @Test
  fun `an ineligible sealed interface still skips its positions and keeps its neighbours`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_SEALED_POSITION.name) &&
            it.contains("Monitor.mixed") &&
            it.contains("tier1.sealedinterface.Mixed")
      },
      "expected Monitor.mixed to keep skipping named; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.generated.contains("export_monitor_mixed"),
      "expected no export for the ineligible position; generated=${result.generated}",
    )
    assertTrue(
      result.generatedCSharp.contains("public string Tempo()"),
      "expected the other superclass to keep binding; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Tempo") }}",
    )
  }
}
