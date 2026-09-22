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
      result.generated.contains("export_library_tier1_sealedinterface__pulse_get_type"),
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
    // ADR-112 amendment: the refused hierarchy warns once. `Odd` is nested, so it used to collect a
    // second SKIPPED_NESTED_DECLARATION whose "move it to the top level" hint ADR-125 made moot.
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("tier1.sealedinterface.Mixed.Odd")
      },
      "expected no nested skip for an arm of an ineligible sealed interface; " +
          "kspWarnings=${result.kspWarnings}",
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
      result.generated.contains("export_library_tier1_sealedinterface_siblingarms__monitor_mixed"),
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
      result.generated.contains("export_library_tier1_sealedinterface_siblingarms__transmission_get_type"),
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
  fun `an enum arm is boxed as {Enum}Arm and the interface becomes eligible`() {
    val result = Tier1Harness.run(siblingArms)

    // ADR-157 inverts ADR-125's control: the arm is boxed rather than refused.
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE.name) &&
            it.contains("`tier1.sealedinterface.siblingarms.Tone`")
      },
      "expected no ineligibility warning for the enum-armed Tone; kspWarnings=" +
          "${result.kspWarnings}",
    )
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public abstract class Tone : IDisposable, INugetHandle")
    assertEquals(
      0,
      csharp.occurrencesOf("public interface ITone"),
      "expected the admitted interface to leave the interface route; generatedCSharp=" +
          "${csharp.lines().filter { it.contains("Tone") }}",
    )
    // The CS0101 trap, which survives the inversion: the enum is declared by `rootEnums`, so the
    // box must take the `PitchArm` name and the enum must keep the bare one, exactly once.
    assertEquals(
      1,
      csharp.occurrencesOf("public enum Pitch"),
      "expected the enum declared exactly once, as an enum; generatedCSharp=" +
          "${csharp.lines().filter { it.contains("Pitch") }}",
    )
    // Spelled with the whole declaration on purpose: `public sealed class PitchArm` does not
    // contain the substring `class Pitch ` either, so a bare `contains("class Pitch")` assertion
    // would pass whatever the box were named.
    assertEquals(
      0,
      csharp.occurrencesOf("class Pitch :"),
      "expected no arm class under the enum's own name; generatedCSharp=" +
          "${csharp.lines().filter { it.contains("Pitch") }}",
    )
    assertEquals(
      1,
      csharp.occurrencesOf("public sealed class PitchArm : Tone"),
      "expected the box declared exactly once; generatedCSharp=" +
          "${csharp.lines().filter { it.contains("Pitch") }}",
    )
    assertContains(csharp, "public PitchArm(global::Interop.Pitch entry)")
    assertContains(csharp, "public global::Interop.Pitch Value")
    assertContains(csharp, "=> obj is PitchArm other && other.Value == Value;")
  }

  /**
   * Requirement 6 of issue #236, the diagnostic half: neither skip fires for the admitted shape,
   * the enum-only one or the mixed one. Asserted as an ABSENCE because the presence assertions
   * above cannot see it: a hierarchy can render and still have dropped every member typed with it.
   */
  @Test
  fun `neither sealed diagnostic fires for an enum-armed or mixed hierarchy`() {
    val result = Tier1Harness.run(enumArms)

    assertTrue(result.compiledClean, "expected the shape to bind; got: ${result.compileErrors}")
    listOf("Marking", "Snack").forEach { hierarchy ->
      assertTrue(
        result.kspWarnings.none {
          it.contains(ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE.name) &&
              it.contains(hierarchy)
        },
        "expected no ineligibility warning for $hierarchy; kspWarnings=${result.kspWarnings}",
      )
      assertTrue(
        result.kspWarnings.none {
          it.contains(ForwardDiagnosticKind.SKIPPED_SEALED_POSITION.name) &&
              it.contains(hierarchy)
        },
        "expected no sealed-position skip naming $hierarchy; kspWarnings=${result.kspWarnings}",
      )
    }
    val csharp: String = result.generatedCSharp
    // The cascade the issue counts: the holder gets its constructor and its copy back.
    assertContains(csharp, "public Portrait(global::Interop.Marking marking)")
    assertContains(csharp, "public Portrait Copy(global::Interop.Marking marking)")
    // Two enum arms of one hierarchy, and the mixed hierarchy's one beside a data arm.
    assertContains(csharp, "public sealed class PatchArm : Marking")
    assertContains(csharp, "public sealed class SwirlArm : Marking")
    assertContains(csharp, "public sealed class CrunchArm : Snack")
    assertContains(csharp, "public sealed class Pouch : Snack")
  }

  /**
   * ADR-157's one new refusal: the box's name is already taken in the same namespace. Refused by
   * name, in the build log, rather than emitted as a CS0101 the author has to decode from a C#
   * compile of generated code.
   */
  @Test
  fun `a taken {Enum}Arm name refuses the hierarchy and says so`() {
    val result = Tier1Harness.run(
      """
      package tier1.sealedinterface.armcollision

      sealed interface Marking

      enum class Patch : Marking { BIB, SOCKS }

      /** The collision: the box would be declared `PatchArm` and this already is. */
      class PatchArm(val note: String)
      """.trimIndent(),
    )

    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE.name) &&
            it.contains("`tier1.sealedinterface.armcollision.Marking`")
      },
    ) { "expected an ineligibility warning for Marking; kspWarnings=${result.kspWarnings}" }
    assertTrue(
      diagnostic.contains("boxed as `PatchArm`") && diagnostic.contains("already declared"),
      "expected the collision named; got: $diagnostic",
    )
    // Both types keep their own declarations; neither is doubled.
    assertEquals(1, result.generatedCSharp.occurrencesOf("public enum Patch"))
    assertEquals(1, result.generatedCSharp.occurrencesOf("public class PatchArm"))
  }

  /**
   * ADR-157's fixture shape, in one cell: two enum arms whose ordinals collide, one of them
   * carrying a property typed as the other's enum, plus a mixed hierarchy and a holder.
   */
  private val enumArms: String = """
    package tier1.sealedinterface.enumarms

    sealed interface Marking

    enum class Patch : Marking { BIB, SOCKS }

    enum class Swirl(val patch: Patch) : Marking {
      COCOA(Patch.BIB),
      CREAM(Patch.SOCKS),
    }

    sealed interface Snack

    enum class Crunch : Snack { BISCUIT, KIBBLE }

    data class Pouch(val flavour: String) : Snack

    data class Portrait(val marking: Marking)
  """.trimIndent()

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

  /**
   * ADR-112 amendment: the same refusal as `Mixed`, with the arm nested. An arm is undeclared
   * because its interface is refused, and the parent's diagnostic says so, so a second
   * nested-declaration warning on the arm only sends the author to a move that ADR-125 made
   * irrelevant. `Helper` is the control for the rule being by supertype, not by enclosing
   * declaration: it is nested in the same refused interface and is not an arm, so it keeps warning.
   *
   * ADR-157: the refusing reason used to be an `enum class` arm, which is admitted now (boxed as
   * `{Enum}Arm`). The cell is about an arm of a refused hierarchy, not about *why* it was refused,
   * so the arm now refuses for the reason that is still refusing: two sealed interfaces, which C#
   * single inheritance cannot express.
   */
  private val nestedArm: String = """
    package tier1.sealedinterface.nestedarm

    sealed interface Beat

    sealed interface Tone {
      class Pitch : Tone, Beat
      class Helper
    }

    class Tuner {
      fun tone(): Tone = Tone.Pitch()
    }
  """.trimIndent()

  @Test
  fun `a nested arm of an ineligible sealed interface warns once, on the parent`() {
    val result = Tier1Harness.run(nestedArm)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertEquals(
      1,
      result.kspWarnings.count {
        it.contains(ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE.name) &&
            it.contains("`tier1.sealedinterface.nestedarm.Tone`")
      },
      "expected one ineligibility warning for the hierarchy; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("tier1.sealedinterface.nestedarm.Tone.Pitch")
      },
      "expected no nested skip for the arm the parent already refused; " +
          "kspWarnings=${result.kspWarnings}",
    )
    // ADR-134: an INELIGIBLE sealed interface still renders as `public interface ITone`, which is
    // now an admitted nested-type owner, so the non-arm is DECLARED inside that block instead of
    // skipped. The control it exists for is unchanged and still by supertype, not by enclosing
    // declaration: the arm `Pitch` is refused with the hierarchy, this sibling is not.
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("tier1.sealedinterface.nestedarm.Tone.Helper")
      },
      "expected no nested skip for a non-arm under an admitted interface owner; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.generatedCSharp.contains("public class Helper"),
      "expected the nested non-arm to be declared inside the interface block; csharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Helper") }}",
    )
  }

  /**
   * ADR-112 amendment: two arms refuse for two different reasons, so the one warning has to name
   * both. The walk used to `return` the first refusal in `getSealedSubclasses()` order, which cost
   * the author one rebuild per arm: fix `Root`, rebuild, discover `Seventh`. Order is KSP's, so the
   * test asserts both clauses are present, never where.
   */
  private val twoRefusals: String = """
    package tier1.sealedinterface.tworefusals

    open class Groove

    sealed interface Riff

    sealed interface Chord {
      class Root : Chord, Riff
      class Seventh : Groove(), Chord
    }

    class Band {
      fun chord(): Chord = Chord.Seventh()
    }
  """.trimIndent()

  @Test
  fun `every refusing arm of a sealed interface is named in the one warning`() {
    val result = Tier1Harness.run(twoRefusals)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val refusals: List<String> = result.kspWarnings.filter {
      it.contains(ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE.name) &&
          it.contains("`tier1.sealedinterface.tworefusals.Chord`")
    }
    assertEquals(
      1,
      refusals.size,
      "expected one ineligibility warning for the hierarchy; kspWarnings=${result.kspWarnings}",
    )
    val diagnostic: String = refusals.single()
    // ADR-157: `Root` used to refuse for being an `enum class`, which is admitted now. Two
    // independently refusing arms is what this cell is about, so it refuses for the other reason.
    assertTrue(
      diagnostic.contains("subclass `Root` implements more than one sealed interface"),
      "expected the two-interface arm to be named; got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains(
        "subclass `Seventh` extends another class `tier1.sealedinterface.tworefusals.Groove`",
      ),
      "expected the second-superclass arm to be named; got: $diagnostic",
    )
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("tier1.sealedinterface.tworefusals.Chord.Seventh")
      },
      "expected no nested skip for an arm the parent already refused; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  private fun String.occurrencesOf(text: String): Int = split(text).size - 1
}
