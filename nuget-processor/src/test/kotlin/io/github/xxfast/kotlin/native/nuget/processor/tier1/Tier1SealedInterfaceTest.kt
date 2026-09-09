package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
 *
 * ADR-125 (issue #130) widens eligibility to an arm declared *beside* the interface, which is the
 * ordinary Kotlin spelling of a closed hierarchy: discovery is `getSealedSubclasses()` either way
 * and the renderer already outdents a sibling arm to namespace level for sealed classes. What
 * nesting was implicitly buying is now refused by name: an `enum class` arm (a C# enum admits only
 * an integral base, CS1008) and an arm implementing two sealed interfaces (C# single inheritance).
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
   * The two structural disqualifying reasons, each of which the position-level `SEALED_POSITION`
   * skip can only report as "no discriminator". One fixture, because they are independent
   * hierarchies and the harness cost is per run.
   *
   * `Loose`/`Astray` used to be the third reason ("subclass `Astray` is declared outside the sealed
   * interface"). ADR-125 admits it, so it stays here as the positive control for the reason that
   * went away: the same fixture that named it must now bind it.
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

    fun stray(): Loose = Astray()
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
    // ADR-125: a sibling arm is no longer a reason at all.
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE.name) &&
            it.contains("`tier1.sealedinterface.ineligible.Loose`")
      },
      "expected a sibling-armed interface to be eligible; kspWarnings=${result.kspWarnings}",
    )
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public abstract class Loose : IDisposable, INugetHandle")
    assertContains(csharp, "\n    public sealed class Astray : Loose")
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

  /**
   * ADR-125: the same hierarchy `Pulse` carries above, with the arms declared *beside* the
   * interface rather than inside it, so only the declaration position differs.
   *
   * `Packet` is the cell issue #130 is actually about, the cascade: `planOrSkip` refuses a whole
   * callable when any input type is ineligible and a `data class`'s `copy` is planned from the same
   * primary-constructor parameters, so one refused interface takes out the constructor and the
   * `Copy` of every class that merely holds one.
   *
   * `Tone`/`Pitch` is the new ineligible control, refused for a reason C# can name rather than a
   * style rule, and its arm is a **top-level** enum on purpose: `rootEnums` used to be the one root
   * bucket without the `!isSealedSubclass()` filter, so admitting an enum arm would declare `Pitch`
   * both as an enum and as a sealed arm class (CS0101 in every consumer).
   */
  private val siblingArms: String = """
    package tier1.sealedinterface.siblingarms

    sealed interface Transmission

    data class Ping(val ms: Int, val label: String) : Transmission

    data object Silence : Transmission

    class Radio {
      var current: Transmission = Silence
      val history: List<Transmission> = listOf(Ping(60, "oreo"), Silence)
      fun latest(): Transmission = current
      fun heard(signal: Transmission): Int = when (signal) {
        is Ping -> signal.ms
        Silence -> 0
      }
    }

    data class Packet(val signal: Transmission)

    sealed interface Tone

    enum class Pitch : Tone { HIGH, LOW }
  """.trimIndent()

  @Test
  fun `a sealed interface with top-level arms renders the abstract class and sibling arms`() {
    val result = Tier1Harness.run(siblingArms)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public abstract class Transmission : IDisposable, INugetHandle")
    assertContains(csharp, "internal static Transmission FromHandle(IntPtr handle)")
    // The leading newline plus four spaces is the namespace level: a nested arm renders at eight.
    assertContains(csharp, "\n    public sealed class Ping : Transmission")
    assertContains(csharp, "\n    public sealed class Silence : Transmission")
    assertFalse(
      csharp.contains("        public sealed class Ping"),
      "expected the arms outdented beside the base, not nested; generatedCSharp=" +
          "${csharp.lines().filter { it.contains("Ping") }}",
    )
    assertFalse(
      csharp.contains("ITransmission"),
      "expected no interface route for an eligible sealed interface; generatedCSharp=" +
          "${csharp.lines().filter { it.contains("ITransmission") }}",
    )
    assertTrue(
      result.generated.contains("export_transmission_get_type"),
      "expected the discriminator export; generated=${result.generated}",
    )
  }

  @Test
  fun `a holder of a top-level-armed sealed interface keeps its constructor and copy`() {
    val result = Tier1Harness.run(siblingArms)

    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public Packet(global::Interop.Transmission signal)")
    assertContains(csharp, "public Packet Copy(global::Interop.Transmission signal)")
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_SEALED_POSITION.name) &&
            it.contains("tier1.sealedinterface.siblingarms.Transmission")
      },
      "expected no sealed-position skip naming the widened interface; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `an enum arm keeps the sealed interface ineligible and names the C# reason`() {
    val result = Tier1Harness.run(siblingArms)

    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE.name) &&
            it.contains("`tier1.sealedinterface.siblingarms.Tone`")
      },
    ) { "expected an ineligibility warning for Tone; kspWarnings=${result.kspWarnings}" }
    assertTrue(
      diagnostic.contains("subclass `Pitch`") && diagnostic.contains("enum"),
      "expected the enum-arm reason; got: $diagnostic",
    )
    // The CS0101 trap: the enum is declared by `rootEnums`, so an arm class for it would be a
    // second declaration under the same name in the same namespace.
    assertEquals(
      1,
      result.generatedCSharp.occurrencesOf("public enum Pitch"),
      "expected the enum arm declared exactly once, as an enum; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Pitch") }}",
    )
    assertFalse(
      result.generatedCSharp.contains("class Pitch"),
      "expected no arm class for an enum arm; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Pitch") }}",
    )
    assertEquals(
      1,
      result.generatedCSharp.occurrencesOf("public interface ITone"),
      "expected the refused interface to stay on the interface route, once; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Tone") }}",
    )
  }

  /**
   * The guard nesting used to provide for free: a nested class has exactly one enclosing
   * declaration, a top-level one may implement two sealed interfaces. C# single inheritance cannot
   * express that, and the renderer would outdent `Both` under each base, two namespace-level
   * `public sealed class Both` declarations under one namespace (CS0101). Source-only: an
   * unrepresentable shape has no place in a shipped fixture.
   */
  private val sharedArm: String = """
    package tier1.sealedinterface.sharedarm

    sealed interface Left

    sealed interface Right

    data class Both(val id: Int) : Left, Right

    class Switchboard {
      fun both(): Both = Both(1)
    }
  """.trimIndent()

  @Test
  fun `an arm implementing two sealed interfaces makes both ineligible`() {
    val result = Tier1Harness.run(sharedArm)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    listOf("Left", "Right").forEach { name ->
      val diagnostic: String = requireNotNull(
        result.kspWarnings.firstOrNull {
          it.contains(ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE.name) &&
              it.contains("`tier1.sealedinterface.sharedarm.$name`")
        },
      ) { "expected an ineligibility warning for $name; kspWarnings=${result.kspWarnings}" }
      assertTrue(
        diagnostic.contains("subclass `Both`") &&
            diagnostic.contains("more than one sealed interface"),
        "expected the shared-arm reason; got: $diagnostic",
      )
    }
    assertEquals(
      0,
      result.generatedCSharp.occurrencesOf("public sealed class Both"),
      "expected no arm declaration for a shared arm; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Both") }}",
    )
  }

  private fun String.occurrencesOf(text: String): Int = split(text).size - 1
}
