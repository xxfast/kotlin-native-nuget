package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-074: the forward `expect`/`actual` reproduction matrix. Before Decision 1's `isExpect`
 * filter, `resolver.getAllFiles()` hands the `NugetProcessor` funnel both the `expect` header and
 * the `actual` body of every pair as two files of one compilation (Verified, ADR-074 spike
 * finding 1), so each pair is planned twice under one qualified name and trips the callable/
 * property catalog's duplicate-plan invariant, crashing the entire module's generation.
 *
 * [Tier1Harness.run]'s `commonSources` parameter (wired to `KSPConfig.Builder.commonSourceRoots`)
 * reproduces exactly that split for a plain JVM KSP2 run (ADR-074 spike finding 9), so these cells
 * need no native toolchain.
 */
class Tier1ExpectActualDeclarationsTest {

  private fun assertGeneratesCleanly(
    commonSources: Map<String, String>,
    sources: Map<String, String>,
  ) {
    val result = Tier1Harness.run(sources = sources, commonSources = commonSources)
    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
  }

  // -- Regression cells: each of these crashed with "Forward callable/property catalog has
  // -- duplicate plans for <symbol>" before ADR-074's Decision 1 filter.

  @Test
  fun `expect class with explicit constructor generates cleanly`() {
    assertGeneratesCleanly(
      commonSources = mapOf(
        "Device.kt" to """
        package tier1.expectactual.device

        expect class Device(name: String) {
          fun describe(): String
          val id: String
        }
        """.trimIndent(),
      ),
      sources = mapOf(
        "DeviceActual.kt" to """
        package tier1.expectactual.device

        actual class Device actual constructor(private val name: String) {
          actual fun describe(): String = "device on macos: ${'$'}name"
          actual val id: String = "device-id"
        }
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `expect class with implicit constructor generates cleanly`() {
    assertGeneratesCleanly(
      commonSources = mapOf(
        "Sensor.kt" to """
        package tier1.expectactual.sensor

        expect class Sensor {
          fun reading(): Int
        }
        """.trimIndent(),
      ),
      sources = mapOf(
        "SensorActual.kt" to """
        package tier1.expectactual.sensor

        actual class Sensor {
          actual fun reading(): Int = 42
        }
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `expect fun generates cleanly`() {
    assertGeneratesCleanly(
      commonSources = mapOf(
        "Platform.kt" to """
        package tier1.expectactual.platformfn

        expect fun platformName(): String
        """.trimIndent(),
      ),
      sources = mapOf(
        "PlatformMacos.kt" to """
        package tier1.expectactual.platformfn

        actual fun platformName(): String = "macos"
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `expect object generates cleanly`() {
    assertGeneratesCleanly(
      commonSources = mapOf(
        "PlatformRegistry.kt" to """
        package tier1.expectactual.registryobj

        expect object PlatformRegistry {
          fun count(): Int
        }
        """.trimIndent(),
      ),
      sources = mapOf(
        "PlatformRegistryActual.kt" to """
        package tier1.expectactual.registryobj

        actual object PlatformRegistry {
          actual fun count(): Int = 1
        }
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `expect val generates cleanly`() {
    assertGeneratesCleanly(
      commonSources = mapOf(
        "Tag.kt" to """
        package tier1.expectactual.tagval

        expect val platformTag: String
        """.trimIndent(),
      ),
      sources = mapOf(
        "TagMacos.kt" to """
        package tier1.expectactual.tagval

        actual val platformTag: String = "osx-arm64"
        """.trimIndent(),
      ),
    )
  }

  // -- Structural cells: the generated Interop.cs carries exactly one declaration per name, with
  // -- the actual's members (not the expect's, which has none of a class's, and never a second,
  // -- degenerate copy).

  @Test
  fun `expect class with explicit constructor renders once with the actual's members`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "DeviceActual.kt" to """
        package tier1.expectactual.devicestructural

        actual class Device actual constructor(private val name: String) {
          actual fun describe(): String = "device on macos: ${'$'}name"
          actual val id: String = "device-id"
        }
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Device.kt" to """
        package tier1.expectactual.devicestructural

        expect class Device(name: String) {
          fun describe(): String
          val id: String
        }
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bclass Device\b""").findAll(cs).count(),
      "expected exactly one Device declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public class Device : IDisposable"))
    assertTrue(cs.contains("public Device(string name)"))
    assertTrue(cs.contains("internal Device(IntPtr handle)"))
    assertTrue(cs.contains("public string Describe()"))
    assertTrue(cs.contains("public string Id"))
  }

  @Test
  fun `expect class with implicit constructor renders once with a usable public constructor`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "SensorActual.kt" to """
        package tier1.expectactual.sensorstructural

        actual class Sensor {
          actual fun reading(): Int = 42
        }
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Sensor.kt" to """
        package tier1.expectactual.sensorstructural

        expect class Sensor {
          fun reading(): Int
        }
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bclass Sensor\b""").findAll(cs).count(),
      "expected exactly one Sensor declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public class Sensor : IDisposable"))
    assertTrue(cs.contains("public Sensor()"))
    assertTrue(cs.contains("internal Sensor(IntPtr handle)"))
    assertTrue(cs.contains("public int Reading()"))
  }

  @Test
  fun `expect fun renders once keeping camelCase and the actual's body`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "PlatformMacos.kt" to """
        package tier1.expectactual.platformfnstructural

        actual fun platformName(): String = "macos"
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Platform.kt" to """
        package tier1.expectactual.platformfnstructural

        expect fun platformName(): String
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bplatformName\(""").findAll(cs).count(),
      "expected exactly one platformName declaration in Interop.cs; got: $cs",
    )
    // Top-level functions keep Kotlin camelCase (ForwardCallablePlanner.kt topLevelEntry).
    assertTrue(cs.contains("public static string platformName()"))
  }

  @Test
  fun `expect object renders once with PascalCase members`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "PlatformRegistryActual.kt" to """
        package tier1.expectactual.registryobjstructural

        actual object PlatformRegistry {
          actual fun count(): Int = 1
        }
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "PlatformRegistry.kt" to """
        package tier1.expectactual.registryobjstructural

        expect object PlatformRegistry {
          fun count(): Int
        }
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bclass PlatformRegistry\b""").findAll(cs).count(),
      "expected exactly one PlatformRegistry declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public static class PlatformRegistry"))
    assertTrue(cs.contains("public static int Count()"))
  }

  @Test
  fun `expect val renders once with PascalCase property`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "TagMacos.kt" to """
        package tier1.expectactual.tagvalstructural

        actual val platformTag: String = "osx-arm64"
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Tag.kt" to """
        package tier1.expectactual.tagvalstructural

        expect val platformTag: String
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bPlatformTag\b""").findAll(cs).count(),
      "expected exactly one PlatformTag declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public static string PlatformTag"))
  }

  /**
   * Decision 3: a top-level `actual fun`/`val` takes its C# static class name from the *expect's*
   * file, not the per-target file it happens to be declared in — otherwise `packNuget` packages
   * one target's `PlatformMacos` while shipping every target's binary, and the consumer gets a
   * class named after somebody else's platform.
   */
  @Test
  fun `decision 3 - static class name comes from the expect's file, not the actual's`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "PlatformMacos.kt" to """
        package tier1.expectactual.decision3

        actual fun platformName(): String = "macos"
        actual val platformTag: String = "osx-arm64"
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Platform.kt" to """
        package tier1.expectactual.decision3

        expect fun platformName(): String
        expect val platformTag: String
        """.trimIndent(),
      ),
    )

    val cs: String = result.generatedCSharp
    assertTrue(
      cs.contains("class Platform"),
      "expected the expect-file-derived class Platform; got: $cs",
    )
    assertFalse(
      cs.contains("PlatformMacos"),
      "expected no trace of the per-target file name PlatformMacos; got: $cs",
    )
  }

  /**
   * Decision 2 (2a): an `actual typealias` whose target IS in the export set erases to that
   * target — the C# type is the target's, never the `expect`'s, at both a parameter position
   * (`labelOf`, declared in the common source referencing the still-unfiltered `expect` name) and
   * a return position (`defaultClock`, declared in the platform source).
   */
  @Test
  fun `actual typealias to an exported target erases to it at both positions`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "ClockActual.kt" to """
        package tier1.expectactual.aliasclock

        class SystemClock {
          fun label(): String = "system-clock"
        }

        actual typealias Clock = SystemClock

        fun defaultClock(): Clock = SystemClock()
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "AliasClock.kt" to """
        package tier1.expectactual.aliasclock

        expect class Clock {
          fun label(): String
        }

        fun labelOf(clock: Clock): String = clock.label()
        """.trimIndent(),
      ),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertFalse(
      Regex("""\bclass Clock\b""").containsMatchIn(cs),
      "expected no Clock type at all; got: $cs",
    )
    assertTrue(cs.contains("public class SystemClock : IDisposable"), "got: $cs")
    assertTrue(
      cs.contains("global::Interop.SystemClock defaultClock()"),
      "expected defaultClock's return position to erase to SystemClock; got: $cs",
    )
    assertTrue(
      cs.contains("labelOf(global::Interop.SystemClock clock)"),
      "expected labelOf's parameter position to erase to SystemClock; got: $cs",
    )
  }

  /**
   * Permanent Tier 1 cell (must never live in `test-library`'s build log): `SKIPPED_
   * ACTUAL_TYPEALIAS_TARGET` fires when an `actual typealias`'s erased target is not in the
   * forward export set — here a stdlib type, `kotlin.IllegalStateException`, which the forward
   * direction can never bring into scope.
   */
  @Test
  fun `actual typealias to an unexported stdlib target is skipped with its own diagnostic`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "FailureActual.kt" to """
        package tier1.expectactual.aliasfailure

        actual typealias Failure = kotlin.IllegalStateException
        """.trimIndent(),
      ),
      commonSources = mapOf(
        // Deliberately not named "Failure.kt": the ADR-007 per-file static class name would
        // otherwise coincide with the *type* name "Failure" for an unrelated reason (the file's
        // static wrapper class, empty because failureLabel is skipped, not any Failure type),
        // which would make the assertion below pass for the wrong reason.
        "AliasFailure.kt" to """
        package tier1.expectactual.aliasfailure

        expect class Failure

        fun failureLabel(f: Failure): String = f.toString()
        """.trimIndent(),
      ),
    )

    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_ACTUAL_TYPEALIAS_TARGET.name)
      },
      "expected a SKIPPED_ACTUAL_TYPEALIAS_TARGET diagnostic; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains("Failure") && it.contains("IllegalStateException") },
      "expected the diagnostic to name both the expect (Failure) and the target " +
          "(IllegalStateException); kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.generatedCSharp.contains("class Failure"),
      "expected no Failure type at all in Interop.cs; got: ${result.generatedCSharp}",
    )
  }

  // -- ADR-074 amendment cells (2026-09-05): the shapes the ADR's "What is deferred" list left
  // -- un-exercised. Every one of them is predicted to work by construction — Decision 1's
  // -- `isExpect` filter runs before every root bucket, so each `actual` reaches exactly the route
  // -- the same non-expect declaration would — and nothing had ever crossed them.
  // --
  // -- These cells assert on `result.generated` / `result.generatedCSharp` / `kspWarnings`, never
  // -- on `compiledClean`: `Tier1Harness.compileGenerated` is a plain single-target
  // -- `K2JVMCompiler.exec()` with no `-Xcommon-sources`/multiplatform wiring, so an
  // -- expect/actual fixture legitimately fails that step (the harness says so at its call site),
  // -- and `compileErrors` is informational here exactly as it is for the cells above.

  /**
   * Item 1: an `expect sealed class` whose subclasses are declared in the **actual's** body.
   * Every subclass enumeration on the forward sealed route is `getSealedSubclasses()` on the
   * exported declaration — which, after the filter, is the actual — so this pins that KSP2
   * enumerates inheritors of an `actual sealed class` exactly as it does for a plain one.
   */
  @Test
  fun `expect sealed class with actual-side subclasses renders the hierarchy once`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "SignalMacos.kt" to """
        package tier1.expectactual.sealedactual

        actual sealed class Signal {
          data class Strong(val dbm: Int) : Signal()
          data object Lost : Signal()
        }

        actual fun collarSignal(dbm: Int): Signal =
          if (dbm < 0) Signal.Lost else Signal.Strong(dbm)
        """.trimIndent(),
      ),
      commonSources = mapOf(
        // Not "Signal.kt": ADR-007's per-file static class name would then collide with the type
        // name and make the single-declaration assertion below pass for the wrong reason.
        "SignalApi.kt" to """
        package tier1.expectactual.sealedactual

        expect sealed class Signal

        expect fun collarSignal(dbm: Int): Signal
        """.trimIndent(),
      ),
    )

    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")

    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bclass Signal\b""").findAll(cs).count(),
      "expected exactly one Signal declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public abstract class Signal"), "got: $cs")
    assertTrue(cs.contains("class Strong : Signal"), "got: $cs")
    assertTrue(cs.contains("class Lost : Signal"), "got: $cs")

    // The discriminator is the loud half: `SealedClassExports` renders an exhaustive `when` over
    // `getSealedSubclasses()`, so a missing inheritor could not silently produce a plausible file.
    val kotlin: String = result.generated
    assertTrue(kotlin.contains("signal_get_type"), "got: $kotlin")
    assertTrue(
      Regex("""is \S*Signal\.Strong ->""").containsMatchIn(kotlin),
      "expected the get_type `when` to name Strong; generated=$kotlin",
    )
    assertTrue(
      Regex("""is \S*Signal\.Lost ->""").containsMatchIn(kotlin),
      "expected the get_type `when` to name Lost; generated=$kotlin",
    )
  }

  /**
   * Item 1, second placement: a subclass declared **beside the expect**, in the common source set
   * (the KEEP permits subclasses in any module on the dependency path between the expect and its
   * actual, including the declaring module). The `expect sealed class Signal()` carries an
   * explicit no-arg constructor deliberately: an `expect class` with no declared constructor is
   * not constructible from common code, so `: Signal()` would otherwise fail the frontend for a
   * reason unrelated to this pin.
   */
  @Test
  fun `expect sealed class with a common-side subclass names it in the discriminator`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "SignalCommonSubActual.kt" to """
        package tier1.expectactual.sealedcommonsub

        actual sealed class Signal actual constructor() {
          data object Lost : Signal()
        }

        actual fun latestSignal(): Signal = Signal.Lost
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "SignalCommonSubApi.kt" to """
        package tier1.expectactual.sealedcommonsub

        expect sealed class Signal()

        class CommonPing(val strength: Int) : Signal()

        expect fun latestSignal(): Signal
        """.trimIndent(),
      ),
    )

    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    val kotlin: String = result.generated
    assertTrue(
      Regex("""is \S*CommonPing ->""").containsMatchIn(kotlin),
      "expected the get_type `when` to name the common-side subclass; generated=$kotlin",
    )
    assertTrue(
      Regex("""is \S*Signal\.Lost ->""").containsMatchIn(kotlin),
      "expected the get_type `when` to name the actual-side subclass too; generated=$kotlin",
    )
  }

  /**
   * Item 2, the pin (not a mapping): an `actual typealias` onto a **generic** target stays on
   * `SKIPPED_ACTUAL_TYPEALIAS_TARGET`. `ForwardBridgeTypeClassifier.classifyActualTypeAliasTarget`
   * refuses `target.typeParameters.isNotEmpty()` outright; binding it would need a type-argument
   * rewrite (`Bag<String>` -> `Crate<String>`) the redirect has no map for, since it substitutes a
   * `KSClassDeclaration`, not a `KSType`.
   *
   * The target is a module-local **invariant** `class Crate<T>`, not a stdlib collection: the
   * ROADMAP's own example `actual typealias Bag = List<String>` is not a Kotlin program
   * (`ACTUAL_TYPE_ALIAS_WITH_COMPLEX_SUBSTITUTION`, an ERROR: zero alias type parameters against
   * one expansion argument), and even `Bag<T> = List<T>` is rejected a second time by
   * `ACTUAL_TYPE_ALIAS_TO_CLASS_WITH_DECLARATION_SITE_VARIANCE` because `List<out E>` is
   * covariant. So this is the narrowest shape that actually reaches the classifier's refusal.
   */
  @Test
  fun `actual typealias to a generic target is skipped with its own diagnostic`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "BagActual.kt" to """
        package tier1.expectactual.aliasgeneric

        class Crate<T>(val item: T)

        actual typealias Bag<T> = Crate<T>

        actual fun bagOf(): Bag<String> = Crate("a")
        """.trimIndent(),
      ),
      commonSources = mapOf(
        // Not "Bag.kt", for the same ADR-007 reason as the `Failure` cell above.
        "AliasBag.kt" to """
        package tier1.expectactual.aliasgeneric

        expect class Bag<T>

        expect fun bagOf(): Bag<String>
        """.trimIndent(),
      ),
    )

    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_ACTUAL_TYPEALIAS_TARGET.name)
      },
      "expected a SKIPPED_ACTUAL_TYPEALIAS_TARGET diagnostic; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains("Bag") && it.contains("Crate") },
      "expected the diagnostic to name both the expect (Bag) and the generic target (Crate); " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      Regex("""\bclass Bag\b""").containsMatchIn(result.generatedCSharp),
      "expected no Bag type at all in Interop.cs; got: ${result.generatedCSharp}",
    )
  }

  /**
   * Item 6a: an `expect interface`, bound at an ADR-040 return position. The interface route reads
   * its members off the actual; the per-target implementing class is `internal`, so its name never
   * reaches `Interop.cs` and the packaged C# stays identical across targets.
   */
  @Test
  fun `expect interface renders once as ITransponder with an ADR-040 backing class`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "TransponderMacos.kt" to """
        package tier1.expectactual.interfaceresidual

        actual interface Transponder {
          actual fun ping(): String
        }

        internal class MacosTransponder : Transponder {
          override fun ping(): String = "pong from macos"
        }

        actual fun transponder(): Transponder = MacosTransponder()
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "TransponderApi.kt" to """
        package tier1.expectactual.interfaceresidual

        expect interface Transponder {
          fun ping(): String
        }

        expect fun transponder(): Transponder
        """.trimIndent(),
      ),
    )

    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\binterface ITransponder\b""").findAll(cs).count(),
      "expected exactly one ITransponder declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public interface ITransponder : IDisposable"), "got: $cs")
    assertTrue(cs.contains("public sealed class Transponder : ITransponder"), "got: $cs")
    assertFalse(
      cs.contains("MacosTransponder"),
      "the per-target implementing class is internal and must never reach Interop.cs; got: $cs",
    )
    assertTrue(result.generated.contains("transponder_ping"), "got: ${result.generated}")
  }

  /**
   * Item 6b: an `expect enum class`. The entries on the wire are the **actual's** own
   * (`CirClassTranslator` reads them from that declaration's `declarations`), and the ordinal
   * wire is therefore the actual's ordering.
   */
  @Test
  fun `expect enum class renders once with the actual's ordinal-backed entries`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "BandMacos.kt" to """
        package tier1.expectactual.enumresidual

        actual enum class Band {
          LOW,
          HIGH,
        }

        actual fun band(): Band = Band.HIGH
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "BandApi.kt" to """
        package tier1.expectactual.enumresidual

        expect enum class Band {
          LOW,
          HIGH,
        }

        expect fun band(): Band
        """.trimIndent(),
      ),
    )

    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\benum Band\b""").findAll(cs).count(),
      "expected exactly one Band declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public enum Band"), "got: $cs")
    assertTrue(cs.contains("Low = 0"), "got: $cs")
    assertTrue(cs.contains("High = 1"), "got: $cs")
  }

  /**
   * Item 6c: an `expect value class`. `@JvmInline` is a requirement of this JVM harness only
   * (`isValueClass()` keys on the `VALUE` modifier, which every form carries); the native
   * `test-library` fixture declares the same pair without it. A value class must declare its
   * primary constructor, so ADR-074 spike finding 6's `ctors = 0` hazard cannot arise.
   */
  @Test
  fun `expect value class renders once as a record struct`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "FrequencyMacos.kt" to """
        package tier1.expectactual.valueclassresidual

        @JvmInline
        actual value class Frequency actual constructor(actual val hertz: Int)

        actual fun frequency(): Frequency = Frequency(5800)
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "FrequencyApi.kt" to """
        package tier1.expectactual.valueclassresidual

        expect value class Frequency(val hertz: Int)

        expect fun frequency(): Frequency
        """.trimIndent(),
      ),
    )

    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertEquals(
      1,
      Regex("""\bstruct Frequency\b""").findAll(cs).count(),
      "expected exactly one Frequency declaration in Interop.cs; got: $cs",
    )
    assertTrue(cs.contains("public readonly record struct Frequency"), "got: $cs")
    assertFalse(
      Regex("""\bclass Frequency\b""").containsMatchIn(cs),
      "a value class must never also render as a handle class; got: $cs",
    )
  }
}
